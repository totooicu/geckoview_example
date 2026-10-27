package org.mozilla.geckoview_example.jsbridge;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 「被杀了还能自己起来」的排网层。
 *
 * <p>一个已经死掉的进程什么都做不了，所以自启不能由服务自己负责，只能靠<b>系统还记得的
 * 登记项</b>：清单里注册的广播接收器（{@link RecoveryReceiver}）、AlarmManager 里排着的
 * 闹钟、JobScheduler 里排的周期任务（{@link RecoveryJobService}）。这三样都会在进程被杀、
 * 甚至整机重启之后仍然按时回来敲门，门后面再决定要不要把 {@link LocationTrackingService}
 * 拉起来。</p>
 *
 * <p>每张网一个自己的 requestCode：{@code PendingIntent} 的匹配只看 action / data /
 * type / class / categories（{@code filterEquals}），<b>不看 extras</b>。要是六张网共用
 * 一个 requestCode，后排的会把先排的顶掉，reason 也就会错。所以 reason 编在 requestCode 里
 * （{@link #REQ_BASE} + 序号），extras 只当可读副本。</p>
 *
 * <p>硬规矩：广播里<b>不</b>直接把前台服务起死。接收器那 10 秒窗口在很多国产 ROM 上不算
 * 「前台」，被 {@code ForegroundServiceStartNotAllowedException} 拒了就把这张网白白烧掉
 * 一次；正确做法是排一个几秒后的闹钟，让「起服务」这一跳落在闹钟那一跳上。接收器里那次
 * 直接尝试只是对照实验，失败也不取消闹钟。</p>
 *
 * <p>都认持久化的 {@code enabled}（{@link #gate}）：用户在页面上关掉自动打点之后，这些网
 * 一律不许把服务拉起来，并且顺手把网撤掉，不留着白敲门。</p>
 */
public final class RecoveryArm {
  private static final String LOGTAG = "RecoveryArm";

  // ---- 六张网的名字（会原样进取证账本的 why 字段，网页据此判「谁把它拉回来的」）----
  /** 兜底周期网：进程死了也照敲，唯一一条不需要自己续的网。 */
  public static final String WHY_SWEEP = "sweep";
  /** 精确版兜底网：需要「闹钟和白名单」权限，一次性，靠每次到点自己续。 */
  public static final String WHY_SWEEP_EXACT = "sweep-exact";
  /** 用户从最近任务划掉壳：30 秒后来敲门（划掉常被 ROM 当 force-stop，闹钟也可能被清）。 */
  public static final String WHY_TASK_REMOVED = "task-removed";
  public static final String WHY_BOOT = "boot";
  public static final String WHY_SCREEN_ON = "screen-on";
  public static final String WHY_UNLOCK = "unlock";
  public static final String WHY_REPLACED = "replaced";
  /** 周期任务那条网（{@link RecoveryJobService}），不走闹钟。 */
  public static final String WHY_JOB = "job";

  /** 顺序即 requestCode 偏移；cancelAll() 会遍历整个数组。 */
  private static final String[] WHYS = {
    WHY_SWEEP, WHY_SWEEP_EXACT, WHY_TASK_REMOVED, WHY_BOOT, WHY_SCREEN_ON, WHY_UNLOCK, WHY_REPLACED
  };

  /** 7701 已被服务自己的 60 秒心跳闹钟占了，这里另起一段，互不覆盖。 */
  private static final int REQ_BASE = 7702;
  static final int JOB_ID = 7712;

  /** 兜底网周期：12 分钟。比 JobScheduler 的 15 分钟下限短，两道网错开敲。 */
  static final long SWEEP_REPEAT_MS = 12 * 60 * 1000L;
  /** 周期任务下限就是 15 分钟（API 24+），只能这么长。 */
  static final long JOB_PERIOD_MS = 15 * 60 * 1000L;
  /** 接收器不直接起服务，改排几秒后的闹钟。 */
  static final long BROADCAST_DELAY_MS = 8 * 1000L;
  /** 划掉任务后的冷却：30 秒够系统做完清理，又不像 15 分钟那样错过整段记录。 */
  static final long TASK_REMOVED_DELAY_MS = 30 * 1000L;

  private RecoveryArm() {}

  private static int reqOf(String why) {
    for (int i = 0; i < WHYS.length; i++) {
      if (WHYS[i].equals(why)) {
        return REQ_BASE + i;
      }
    }
    return REQ_BASE + WHYS.length; // 未知 reason 也别撞 7701/7702
  }

  private static PendingIntent alarmIntent(Context ctx, String why) {
    Intent i = new Intent(ctx.getApplicationContext(), LocationTrackingService.class);
    i.setAction(LocationTrackingService.ACTION_WAKE);
    i.putExtra(LocationTrackingService.EXTRA_RECOVERY_WHY, why);
    // FLAG_IMMUTABLE：extras 在建 PI 时就冻住，之后只有「改 requestCode」才能换 extras，
    // 这里每张网一个 requestCode，正好对得上。
    return PendingIntent.getForegroundService(
        ctx.getApplicationContext(), reqOf(why), i, PendingIntent.FLAG_IMMUTABLE);
  }

  private static AlarmManager alarms(Context ctx) {
    return (AlarmManager) ctx.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
  }

  /** 有没有「精确闹钟」权限（Android 12+ 要 SCHEDULE_EXACT_ALARM；更早版本恒为可）。 */
  static boolean canExact(Context ctx) {
    try {
      AlarmManager am = alarms(ctx);
      if (am == null) {
        return false;
      }
      return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
    } catch (Exception e) {
      return false;
    }
  }

  static boolean hasLocationPerms(Context ctx) {
    try {
      return ctx.getApplicationContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
          == PackageManager.PERMISSION_GRANTED;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * 起不起得来的判断只有一处：配置开着 + 还有精确定位权限。
   * 任何一张网敲门前都要过这道门，否则「用户明明关了」还在后台耗电。
   */
  static boolean gate(Context ctx) {
    if (LocationTrackingService.isRunning()) {
      return false; // 已经在跑了，不需要拉
    }
    LocationConfig cfg = LocationConfig.load(ctx);
    return cfg.enabled && hasLocationPerms(ctx);
  }

  /**
   * 排一张网。
   *
   * @param repeatMs 0 = 一次性；&gt;0 = {@code setRepeating}（不准点，但不需要权限，
   *     而且进程死了也照样循环——这条正是兜底网要的）
   * @return 有没有排上
   */
  static boolean arm(Context ctx, String why, long delayMs, long repeatMs) {
    try {
      AlarmManager am = alarms(ctx);
      if (am == null) {
        return false;
      }
      PendingIntent pi = alarmIntent(ctx, why);
      long due = SystemClock.elapsedRealtime() + delayMs;
      if (repeatMs > 0) {
        am.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, repeatMs, pi);
      } else if (canExact(ctx)) {
        // setAndAllowWhileIdle 不吃 SCHEDULE_EXACT_ALARM，但在 Doze 里会被压到 ~15 分钟一次；
        // 有精确权限就换 setExactAndAllowWhileIdle，准点得多。
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pi);
      } else {
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pi);
      }
      Log.i(LOGTAG, "armed " + why + " in " + delayMs + "ms repeat=" + repeatMs + "ms");
      return true;
    } catch (Exception e) {
      Log.w(LOGTAG, "arm(" + why + ") failed", e);
      return false;
    }
  }

  /** 这张网现在排着没有（问的是系统记的那份，进程死了也照样问得到）。 */
  static boolean isArmed(Context ctx, String why) {
    try {
      Context app = ctx.getApplicationContext();
      PendingIntent pi = PendingIntent.getForegroundService(
          app, reqOf(why), new Intent(app, LocationTrackingService.class)
              .setAction(LocationTrackingService.ACTION_WAKE),
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
      return pi != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * 服务活着 / 刚接回链时把「不需要别人帮忙就能自己回来」的三张网补齐：
   * 周期兜底网 + 精确兜底网（有权限才排）+ 15 分钟周期任务。
   *
   * <p>两条闹钟都**已经有了就不重排**：重排会把下一次敲门一直往后推，于是「刚敲完门
   * 就被杀」要等满一整轮才有人来。一次性那张（{@code sweep-exact}）靠到点后
   * {@link LocationTrackingService#onRecovery} 那一跳自己续，周期那张本来就自己循环。</p>
   */
  static void armNets(Context ctx) {
    if (!isArmed(ctx, WHY_SWEEP)) {
      arm(ctx, WHY_SWEEP, SWEEP_REPEAT_MS, SWEEP_REPEAT_MS);
    }
    if (canExact(ctx) && !isArmed(ctx, WHY_SWEEP_EXACT)) {
      arm(ctx, WHY_SWEEP_EXACT, SWEEP_REPEAT_MS, 0);
    }
    scheduleJob(ctx);
  }

  /** 接收器（开机/亮屏/解锁/覆盖安装）专用：先排一张网，再试着起一次。 */
  static void fromBroadcast(Context ctx, String why) {
    arm(ctx, why, BROADCAST_DELAY_MS, 0);
    armNets(ctx);
    // 对照实验：这一跳在多数 ROM 上会被拒，拒了也不影响闹钟那一跳。
    try {
      Intent i = new Intent(ctx.getApplicationContext(), LocationTrackingService.class);
      i.setAction(LocationTrackingService.ACTION_WAKE);
      i.putExtra(LocationTrackingService.EXTRA_RECOVERY_WHY, why);
      ctx.getApplicationContext().startForegroundService(i);
    } catch (Exception e) {
      Log.w(LOGTAG, "direct start from " + why + " refused: " + e.getMessage());
    }
  }

  /** 划掉任务那一跳：服务可能跟着任务一起死，先在遗言里排一张 30 秒后的网。 */
  static void fromTaskRemoved(Context ctx) {
    arm(ctx, WHY_TASK_REMOVED, TASK_REMOVED_DELAY_MS, 0);
    armNets(ctx);
  }

  static void cancel(Context ctx, String why) {
    try {
      AlarmManager am = alarms(ctx);
      PendingIntent pi = alarmIntent(ctx, why);
      if (am != null && pi != null) {
        am.cancel(pi);
      }
    } catch (Exception ignored) {
    }
  }

  /** 用户关掉自动打点、或主动停服时调用：网全部撤干净，不留白敲门的。 */
  static void cancelAll(Context ctx) {
    for (String why : WHYS) {
      cancel(ctx, why);
    }
    cancelJob(ctx);
  }

  /** 15 分钟周期任务：{@code setPersisted(true)} 让它跨过整机重启。 */
  static void scheduleJob(Context ctx) {
    try {
      JobScheduler js =
          (JobScheduler) ctx.getApplicationContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if (js == null) {
        return;
      }
      JobInfo info =
          new JobInfo.Builder(
                  JOB_ID, new ComponentName(ctx.getApplicationContext(), RecoveryJobService.class))
              .setPersisted(true)
              .setPeriodic(JOB_PERIOD_MS)
              .build();
      int r = js.schedule(info);
      Log.i(LOGTAG, "scheduleJob -> " + r);
    } catch (Exception e) {
      Log.w(LOGTAG, "scheduleJob failed", e);
    }
  }

  static void cancelJob(Context ctx) {
    try {
      JobScheduler js =
          (JobScheduler) ctx.getApplicationContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if (js != null) {
        js.cancel(JOB_ID);
      }
    } catch (Exception ignored) {
    }
  }

  static boolean jobScheduled(Context ctx) {
    try {
      JobScheduler js =
          (JobScheduler) ctx.getApplicationContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if (js == null) {
        return false;
      }
      for (JobInfo info : js.getAllPendingJobs()) {
        if (info != null && info.getId() == JOB_ID) {
          return true;
        }
      }
    } catch (Exception e) {
      Log.w(LOGTAG, "jobScheduled check failed", e);
    }
    return false;
  }

  /**
   * 网还在不在（{@code FLAG_NO_CREATE} 查的是系统记的那份，进程死了也能查到）。
   * 给 {@code location.status} 用，磨合台据此一眼看出「自启的网有没有排上」。
   */
  static JSONObject state(Context ctx) {
    JSONObject o = new JSONObject();
    JSONArray live = new JSONArray();
    try {
      for (String why : WHYS) {
        if (isArmed(ctx, why)) {
          live.put(why);
        }
      }
      o.put("alarms", live);
      o.put("job", jobScheduled(ctx));
      o.put("exact", canExact(ctx));
    } catch (Exception e) {
      Log.w(LOGTAG, "state failed", e);
    }
    return o;
  }
}
