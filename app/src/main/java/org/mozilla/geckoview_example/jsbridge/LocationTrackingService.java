package org.mozilla.geckoview_example.jsbridge;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.BatteryManager;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview_example.WebViewActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that keeps recording location while the app is in the
 * background / screen off. This is the native "fallback" half of the mixed
 * design: while the page is visible it records via watchPosition itself; once
 * the page cannot run, this service takes over.
 *
 * <p><b>后台续记（feat/location-tune 磨合重点）</b>——让"壳退到后台/熄屏还记"
 * 真正成立的四件事，缺一件都会断：</p>
 * <ol>
 *   <li><b>前台服务 + location 类型</b>：Android 10+ 起，后台访问定位必须有
 *       foregroundServiceType="location" 的前台服务（清单已声明，Android 14+
 *       还要 FOREGROUND_SERVICE_LOCATION 权限）。startForeground 必须发生在
 *       任何取点之前。</li>
 *   <li><b>PARTIAL_WAKE_LOCK</b>：熄屏后 CPU 会睡，Handler 的 tick 会被推迟到
 *       下一次亮屏。持一把部分唤醒锁，tick 和 GPS 回调才按点到达（整机 Doze
 *       期间前台服务 + 唤醒锁是官方允许的持续定位组合）。</li>
 *   <li><b>脱离页面的生命周期</b>：采集全在本服务里，页面只是配置来源和展示层。
 *       WebView 退到后台会被冻结（JS 定时器停跳），但本服务与它的任务无关；
 *       用户从最近任务划掉壳，服务也继续（onTaskRemoved 不自杀）。</li>
 *   <li><b>重启语义</b>：START_STICKY 让系统在杀进程后重建服务；重建时只在
 *       配置仍 enabled 的情况下恢复（用户停过的服务不许自己醒来）。配置随
 *       SharedPreferences 持久化，所以重建不依赖页面。</li>
 * </ol>
 *
 * <p>采点策略与页面一致（locationAuto.ts，见 pushed config）：</p>
 * <ul>
 *   <li>fixed - sample every intervalBaseMs.</li>
 *   <li>backoff - after n consecutive fixes merged into the same point, wait
 *       intervalBaseMs * 2^n, capped at intervalMaxMs (defaults 1s → 64s). A new
 *       point resets n to 0, so moving keeps the 1s cadence.</li>
 * </ul>
 *
 * <p>Deduplication mirrors the page's appendPoint(): a new fix is compared only
 * with the last recorded point (haversine). Within thresholdM it merges (update
 * ts + accuracy, keep coordinates/note), otherwise it appends a new point. rev
 * increments only on real append/delete. New points persist immediately;
 * time-only refreshes persist at most every FLUSH_MS. Data is stored as JSONL in
 * the app-private files dir, one point per line:
 * {@code {"ts":...,"lat":...,"lng":...,"accuracy":...,"rev":N}}.</p>
 */
public class LocationTrackingService extends Service {
  private static final String LOGTAG = "LocationTrackingService";

  public static final String ACTION_START = "org.mozilla.geckoview_example.LOCATION_START";
  public static final String ACTION_STOP = "org.mozilla.geckoview_example.LOCATION_STOP";
  /** 闹钟心跳：取证模式下每 60 秒醒一次，用来分清「进程被冻」和「线程被挂起」。 */
  public static final String ACTION_WAKE = "org.mozilla.geckoview_example.LOCATION_WAKE";

  /** 闹钟间隔：60 秒。setAndAllowWhileIdle 在 Doze 里也是系统能给你的最密档位。 */
  private static final long WAKE_ALARM_MS = 60000L;
  private static final int WAKE_ALARM_REQ = 7701;

  private static final String CHANNEL_ID = "location_tracking";
  private static final int NOTIFICATION_ID = 2002;
  private static final String FILE_NAME = "location_log.jsonl";

  /** Persist throttle for time-only refreshes (page FLUSH_MS = 15000). */
  private static final long FLUSH_MS = 15000;

  /** 通知栏文案刷新节流：每次追加都通知会刷屏，够用就行。 */
  private static final long NOTIFY_THROTTLE_MS = 5000;

  /** 取证账本：诊断模式下每秒一行，把「后台这一秒到底发生了什么」写进文件。 */
  private static final String JOURNAL_FILE = "bg_journal.jsonl";

  /** 账本上限，超了砍掉较旧的一半（约 1.5MB ≈ 1.2 万行），不吃手机存储。 */
  private static final long JOURNAL_MAX_BYTES = 1500000L;

  /** 取证模式的采样间隔：用户要求「每秒申请一次定位」。 */
  private static final long DIAG_INTERVAL_MS = 1000L;

  /** 部分唤醒锁：熄屏后让 tick 与 GPS 回调照常到达。 */
  private static final String WAKE_LOCK_TAG = "dr:location-tracking";

  /** Location fix watchdog: if no fix arrives within interval+slack, retry. */
  private static final long FIX_WATCHDOG_SLACK_MS = 800;

  /** Exponent clamp for the backoff (2^16 * 1s already far beyond any cap). */
  private static final int MAX_BACKOFF_N = 16;

  /** Bridge-facing listener (set by LocationBridge) for events. */
  public interface Listener {
    void onPoint(JSONObject point);

    /** 服务起/停时推一次（页面据此即时更新状态，不用等下一次轮询）。 */
    default void onState(JSONObject state) {}
  }

  @Nullable private static Listener sListener;
  @Nullable private static volatile LocationTrackingService sInstance;

  /** Current config snapshot (refreshed on start and on applyConfig). */
  private static volatile LocationConfig sConfig = new LocationConfig();

  private static volatile boolean sRunning = false;
  private static volatile long sStartedAt = 0;
  private static volatile int sPoints = 0;
  private static volatile int sRev = 0;
  private static volatile double sLastLat = 0;
  private static volatile double sLastLng = 0;
  private static volatile long sLastTs = 0;
  private static volatile double sLastAccuracy = 0;
  private static volatile String sLastNote = "";
  /** 诊断字段：磨合后台续记时要能从网页一眼看出"服务到底在干什么"。 */
  private static volatile boolean sWakeLockHeld = false;
  /** 下一次闹钟到点的 elapsedRealtime；0 = 没排过。醒来时用它算「迟到了多久」。 */
  private static volatile long sWakeDueEl = 0;
  private static volatile String sProvider = "";
  private static volatile long sLastFixAt = 0;
  private static volatile long sFixes = 0;
  private static volatile long sFailures = 0;
  private static volatile String sLastError = "";
  private static volatile long sIntervalCurrentMs = 0;
  /** tick 计数：诊断模式的「服务醒着跳了几次」，用来分清「被冻」与「取不到点」。 */
  private static volatile long sTicks = 0;
  private static volatile long sLastNotifyAt = 0;

  public static boolean isRunning() {
    return sRunning;
  }

  public static void setListener(@Nullable Listener listener) {
    sListener = listener;
  }

  /** Snapshot for location.status. */
  public static JSONObject statusJson() {
    try {
      JSONObject o = new JSONObject();
      o.put("tracking", sRunning);
      o.put("startedAt", sStartedAt);
      o.put("points", sPoints);
      o.put("rev", sRev);
      JSONObject last = new JSONObject();
      last.put("ts", sLastTs);
      last.put("lat", sLastLat);
      last.put("lng", sLastLng);
      last.put("accuracy", sLastAccuracy);
      last.put("note", sLastNote);
      o.put("lastPoint", last);
      // 后台续记诊断：网页的「原生心跳」用的就是这几个字段
      o.put("wakeLock", wakeLockHeld());
      o.put("provider", sProvider);
      o.put("lastFixAt", sLastFixAt);
      o.put("fixes", sFixes);
      o.put("failures", sFailures);
      o.put("lastError", sLastError);
      o.put("intervalCurrentMs", sIntervalCurrentMs);
      o.put("ticks", sTicks);
      o.put("diag", sConfig.diag);
      LocationTrackingService js = sInstance;
      o.put("journalBytes",
          js != null && js.mJournalFile != null && js.mJournalFile.exists()
              ? js.mJournalFile.length()
              : 0);
      return o;
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  /** 通知一次服务状态变化（起/停）。 */
  private static void emitState(String reason) {
    Listener l = sListener;
    if (l == null) {
      return;
    }
    try {
      JSONObject o = statusJson();
      o.put("reason", reason);
      l.onState(o);
    } catch (Exception e) {
      Log.w(LOGTAG, "emitState failed", e);
    }
  }

  /** All recorded points for location.getHistory. */
  public static JSONObject historyJson() {
    JSONObject o = new JSONObject();
    try {
      LocationTrackingService svc = sInstance;
      JSONArray points = svc != null ? svc.readAllPoints() : new JSONArray();
      o.put("points", points);
      o.put("rev", sRev);
    } catch (Exception e) {
      Log.e(LOGTAG, "historyJson failed", e);
      try {
        o.put("points", new JSONArray());
        o.put("rev", sRev);
      } catch (Exception ignored) {
      }
    }
    return o;
  }

  /**
   * 读取证账本（location.journal）：since 只回这段时间之后的行，tail 限制条数。
   * 页面拿这些行判「壳在后台有没有被休眠」：tick 行之间出现大空档 = 进程被冻，
   * tick 连续但没有 fix 行 = 系统没送定位过来。
   */
  public static JSONObject journalJson(long since, int tail) {
    JSONObject o = new JSONObject();
    JSONArray lines = new JSONArray();
    LocationTrackingService svc = sInstance;
    File f = svc != null ? svc.mJournalFile : null;
    long bytes = f != null && f.exists() ? f.length() : 0;
    int total = 0;
    try {
      List<String> all = svc != null ? svc.readJournalLines() : new ArrayList<String>();
      total = all.size();
      int from = Math.max(0, total - Math.max(1, tail));
      for (int i = from; i < total; i++) {
        String line = all.get(i);
        if (line == null || line.isEmpty()) {
          continue;
        }
        try {
          JSONObject j = new JSONObject(line);
          if (since <= 0 || j.optLong("t") >= since) {
            lines.put(j);
          }
        } catch (Exception ignored) {
        }
      }
    } catch (Exception e) {
      Log.e(LOGTAG, "journalJson failed", e);
    }
    try {
      o.put("lines", lines);
      o.put("count", lines.length());
      o.put("total", total);
      o.put("bytes", bytes);
      o.put("ticks", sTicks);
      o.put("diag", sConfig.diag);
      o.put("tracking", sRunning);
    } catch (Exception ignored) {
    }
    return o;
  }

  /** 开关取证模式：只改内存快照并重排采样，持久化由 LocationBridge 负责。 */
  public static void notifyDiag(boolean on) {
    LocationTrackingService svc = sInstance;
    if (svc != null) {
      svc.journal("diag", diagExtra(on));
      if (sRunning) {
        svc.mSameCount = 0;
        svc.reschedule();
      }
    }
    Log.i(LOGTAG, "diag mode " + (on ? "ON" : "OFF"));
  }

  private static JSONObject diagExtra(boolean on) {
    try {
      JSONObject e = new JSONObject();
      e.put("on", on);
      e.put("intervalMs", on ? DIAG_INTERVAL_MS : sConfig.intervalBaseMs);
      return e;
    } catch (Exception ex) {
      return new JSONObject();
    }
  }

  /** Clear all recorded points (location.clearHistory). */
  public static void clearHistory() {
    LocationTrackingService svc = sInstance;
    if (svc != null) {
      svc.clearFile();
    }
    sRev = 0;
    sPoints = 0;
    sLastTs = 0;
    sLastLat = 0;
    sLastLng = 0;
    sLastAccuracy = 0;
    sLastNote = "";
  }

  /** Push a new config while running (interval changes apply on next tick). */
  public static void applyConfig(Context ctx) {
    sConfig = LocationConfig.load(ctx);
    LocationTrackingService svc = sInstance;
    if (svc != null) {
      svc.reschedule();
    }
  }

  // ---------------------------------------------------------------------------
  // Instance state
  // ---------------------------------------------------------------------------

  private final Handler mHandler = new Handler(Looper.getMainLooper());

  /** Consecutive fixes merged into the same point; backoff exponent n. */
  private int mSameCount = 0;

  private LocationManager mLocationManager;
  private String mProvider;

  /** 部分唤醒锁（只在本服务记录期间持有）。 */
  @Nullable private PowerManager.WakeLock mWakeLock;

  /** Last appended point (dedupe baseline), mirrors page rows[last]. */
  @Nullable private JSONObject mLastPoint;
  private long mLastPersistTs = 0;
  private File mLogFile;
  /** 取证账本文件（app 私有目录，网页经 location.journal 读）。 */
  private File mJournalFile;
  private android.content.BroadcastReceiver mScreenReceiver;
  private long mJournalWrites = 0;
  /** 上一行「有效落笔」的三种时刻，用来在空档结束后补一行探针。 */
  private long mLastJournalWall = 0;
  private long mLastJournalUp = 0;
  private long mLastJournalEl = 0;
  private String mLastJournalKind = "";
  private boolean mPendingFix = false;
  private long mCurrentIntervalMs = 1000;
  private boolean mStopping = false;

  private final Runnable mTick = this::onTick;
  private final Runnable mFixWatchdog = this::onFixWatchdog;

  private final LocationListener mFixListener =
      new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
          onFix(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
          // ignore
        }

        @Override
        public void onProviderEnabled(String provider) {
          JSONObject e = new JSONObject();
          try {
            e.put("provider", provider);
            e.put("on", true);
          } catch (Exception ignored) {
          }
          journal("provider", e);
        }

        @Override
        public void onProviderDisabled(String provider) {
          JSONObject e = new JSONObject();
          try {
            e.put("provider", provider);
            e.put("on", false);
          } catch (Exception ignored) {
          }
          journal("provider", e);
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    mProvider = pickProvider();
    mLogFile = new File(getFilesDir(), FILE_NAME);
    mJournalFile = new File(getFilesDir(), JOURNAL_FILE);
    registerScreenReceiver();
    createNotificationChannel();
    sInstance = this;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopTracking("page");
      return START_NOT_STICKY;
    }
    if (intent != null && ACTION_WAKE.equals(intent.getAction())) {
      // 闹钟这条路径自己收尾：不走下面的 enabled 判断，免得把自己停掉。
      onWakeAlarm();
      return sRunning ? START_STICKY : START_NOT_STICKY;
    }
    // 这里会收到三种启动：页面 enabled=true（ACTION_START）、页面显式 start，
    // 以及系统杀进程后的重建（intent == null）。
    sConfig = LocationConfig.load(this);
    boolean explicitStart = intent != null && ACTION_START.equals(intent.getAction());
    if (!explicitStart && !sConfig.enabled) {
      // 系统重建但用户早就关掉了：不许自己醒来（否则停了还在后台耗电）。
      Log.i(LOGTAG, "restart ignored: config.enabled=false");
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!hasFineLocation()) {
      Log.w(LOGTAG, "no fine location permission, refusing to start");
      stopSelf();
      return START_NOT_STICKY;
    }
    startTracking(intent == null ? "system-restart" : "page");
    return START_STICKY;
  }

  /**
   * 用户从最近任务划掉壳：Activity/WebView 没了，但采集属于「用户已经打开过的
   * 自动打点」，服务不该跟着任务一起死——这是后台续记最容易断的一环。
   */
  @Override
  public void onTaskRemoved(Intent rootIntent) {
    journal("taskRemoved", null);
    Log.i(LOGTAG, "onTaskRemoved: keep tracking (running=" + sRunning + ")");
    super.onTaskRemoved(rootIntent);
  }

  private void startTracking(String reason) {
    if (sRunning) {
      return;
    }
    mStopping = false;
    // Android 14+ rule: the location foreground service must be started with the
    // location type BEFORE any location work, exactly like the mediaProjection rule.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFICATION_ID,
          buildNotification(),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    } else {
      startForeground(NOTIFICATION_ID, buildNotification());
    }
    acquireWakeLock();
    armWakeAlarm();

    restoreTailFromFile();
    sRunning = true;
    sStartedAt = System.currentTimeMillis();
    sLastError = "";

    mSameCount = 0;
    mCurrentIntervalMs = sConfig.intervalBaseMs;
    sIntervalCurrentMs = mCurrentIntervalMs;
    mProvider = pickProvider();
    if (sConfig.diag) {
      mCurrentIntervalMs = DIAG_INTERVAL_MS;
      sIntervalCurrentMs = DIAG_INTERVAL_MS;
    }
    mHandler.removeCallbacks(mTick);
    mHandler.postDelayed(mTick, 0);
    JSONObject st = new JSONObject();
    try {
      st.put("why", reason);
      st.put("enabled", sConfig.enabled);
      st.put("diag", sConfig.diag);
      st.put("wl", wakeLockHeld());
    } catch (Exception ignored) {
    }
    journal("start", st);
    Log.i(LOGTAG, "tracking started (" + reason + "), provider=" + mProvider
        + " strategy=" + sConfig.strategy + " interval=" + mCurrentIntervalMs + "ms");
    updateNotification(true);
    emitState(reason);
  }

  /** 停也要留原因：判读时「被谁停的」比「有没有睡」更要紧。 */
  private static JSONObject stopExtra(String reason) {
    JSONObject o = new JSONObject();
    try {
      o.put("why", reason);
      o.put("enabled", sConfig != null && sConfig.enabled);
      o.put("diag", sConfig != null && sConfig.diag);
    } catch (Exception ignored) {
    }
    return o;
  }

  private void stopTracking(String reason) {
    if (mStopping) {
      return;
    }
    mStopping = true;
    sRunning = false;
    mHandler.removeCallbacks(mTick);
    mHandler.removeCallbacks(mFixWatchdog);
    mPendingFix = false;
    releaseWakeLock();
    cancelWakeAlarm();
    journal("stop", stopExtra(reason));
    Log.i(LOGTAG, "tracking stopped (" + reason + "), points=" + sPoints + " rev=" + sRev);
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
    emitState("stop:" + reason);
  }

  /* ---------------------------------------------------------------------------
   * 唤醒锁：熄屏后让 tick / GPS 回调按点到达
   * ------------------------------------------------------------------------ */

  private void acquireWakeLock() {
    if (mWakeLock != null && mWakeLock.isHeld()) {
      return;
    }
    try {
      PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      if (pm == null) {
        sLastError = "wake lock: no PowerManager";
        return;
      }
      PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
      wl.setReferenceCounted(false);
      wl.acquire();
      mWakeLock = wl;
      sWakeLockHeld = wl.isHeld();
      Log.i(LOGTAG, "wake lock acquired, held=" + sWakeLockHeld);
    } catch (Exception e) {
      mWakeLock = null;
      sWakeLockHeld = false;
      sLastError = "wake lock: " + e.getMessage();
      Log.w(LOGTAG, "wake lock acquire failed", e);
    }
  }

  /** 锁到底在不在手上——直接问系统，不吃可能过期的缓存标记。 */
  public static boolean wakeLockHeld() {
    LocationTrackingService svc = sInstance;
    try {
      return svc != null && svc.mWakeLock != null && svc.mWakeLock.isHeld();
    } catch (Exception e) {
      return false;
    }
  }

  /** 空档探针：三个时钟各走了多久 + 当时的电源/定位状态。 */
  private JSONObject probeGap(long now) {
    JSONObject o = new JSONObject();
    try {
      long up = SystemClock.uptimeMillis();
      long el = SystemClock.elapsedRealtime();
      o.put("gapWall", now - mLastJournalWall);
      o.put("gapUp", mLastJournalUp > 0 ? up - mLastJournalUp : -1);
      o.put("gapEl", mLastJournalEl > 0 ? el - mLastJournalEl : -1);
      o.put("before", mLastJournalKind);
      o.put("n", sTicks);
    } catch (Exception ignored) {
    }
    JSONObject ps = powerSnapshot();
    try {
      java.util.Iterator<String> it = ps.keys();
      while (it.hasNext()) {
        String k = it.next();
        o.put(k, ps.get(k));
      }
    } catch (Exception ignored) {
    }
    return o;
  }

  /** 电源/省电/Provider 快照：判「整机深睡」「省电掐 GPS」「亮屏但被冻」全靠它。 */
  private JSONObject powerSnapshot() {
    JSONObject o = new JSONObject();
    try {
      PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
      if (pm != null) {
        o.put("interactive", pm.isInteractive());
        o.put("saver", pm.isPowerSaveMode());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          o.put("idle", pm.isDeviceIdleMode());
          o.put("light", pm.isDeviceLightIdleMode());
        }
        o.put("opt", pm.isIgnoringBatteryOptimizations(getPackageName()));
      }
      if (mLocationManager != null) {
        o.put("gpsOn", mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
        o.put("netOn", mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
      }
      IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
      Intent batt = registerReceiver(null, filter);
      if (batt != null) {
        int lvl = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        o.put("bat", scale > 0 ? Math.round(lvl * 100f / scale) : -1);
        o.put("plugged", (batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) & 0x0F) != 0);
      }
    } catch (Exception e) {
      Log.w(LOGTAG, "powerSnapshot failed", e);
    }
    return o;
  }

  /** 排一次闹钟心跳（只在服务活着的时候排，别把它变成开机自启）。 */
  private void armWakeAlarm() {
    try {
      AlarmManager am = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
      if (am == null) {
        return;
      }
      Intent i = new Intent(this, LocationTrackingService.class);
      i.setAction(ACTION_WAKE);
      PendingIntent pi = PendingIntent.getService(getApplicationContext(), WAKE_ALARM_REQ, i,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      long due = SystemClock.elapsedRealtime() + WAKE_ALARM_MS;
      sWakeDueEl = due;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pi);
      } else {
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pi);
      }
    } catch (Exception e) {
      Log.w(LOGTAG, "armWakeAlarm failed", e);
    }
  }

  private void cancelWakeAlarm() {
    sWakeDueEl = 0;
    try {
      AlarmManager am = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
      Intent i = new Intent(this, LocationTrackingService.class);
      i.setAction(ACTION_WAKE);
      PendingIntent pi = PendingIntent.getService(getApplicationContext(), WAKE_ALARM_REQ, i,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
      if (am != null && pi != null) {
        am.cancel(pi);
      }
    } catch (Exception ignored) {
    }
  }

  /**
   * 闹钟到点了。这一行是整轮判读的关键对照：
   * late 大 = 连闹钟的投递都被压着 → 进程真被冻住；
   * late 小而 tick 有空档 = 进程活着，只是线程没被调度；
   * running=false = 采集在闹钟到点时已经不在跑了（被停或被杀）。
   */
  private void onWakeAlarm() {
    long late = sWakeDueEl > 0 ? SystemClock.elapsedRealtime() - sWakeDueEl : -1;
    boolean wasRunning = sRunning;
    if (!wasRunning) {
      sConfig = LocationConfig.load(this);
    }
    JSONObject w = new JSONObject();
    boolean diag = sConfig != null && sConfig.diag;
    if (diag) {
      // 只在取证时落账：平时这条心跳只当兜底，不往账本里灌水
      try {
        w.put("late", Math.max(-1, late));
        w.put("running", wasRunning);
        w.put("enabled", sConfig != null && sConfig.enabled);
        JSONObject ps = powerSnapshot();
        java.util.Iterator<String> it = ps.keys();
        while (it.hasNext()) {
          String k = it.next();
          w.put(k, ps.get(k));
        }
      } catch (Exception ignored) {
      }
    }
    if (diag) {
      journal("wake", w);
    }
    if (wasRunning) {
      armWakeAlarm();
      return;
    }
    // 闹钟是唯一稳定到点的入口：采集要是没了、配置还开着、权限还在，就在这里把它拉回来。
    // Android 12+ 后台起服务会被拒，拒了就记一行，别让它变成静默失效。
    if (sConfig != null && sConfig.enabled && hasFineLocation()) {
      try {
        startTracking("wake-resume");
        armWakeAlarm();
      } catch (Exception e) {
        JSONObject bad = new JSONObject();
        try {
          bad.put("why", "wake-resume-blocked");
          bad.put("msg", String.valueOf(e.getMessage()));
        } catch (Exception ignored) {
        }
        journal("fail", bad);
        Log.w(LOGTAG, "wake resume blocked", e);
      }
    }
  }

  private void releaseWakeLock() {
    try {
      if (mWakeLock != null && mWakeLock.isHeld()) {
        mWakeLock.release();
      }
    } catch (Exception ignored) {
    }
    mWakeLock = null;
    sWakeLockHeld = false;
  }

  // ---------------------------------------------------------------------------
  // Collection loop
  // ---------------------------------------------------------------------------

  private void onTick() {
    if (!sRunning || mStopping) {
      return;
    }
    if (mPendingFix) {
      // 上一拍的请求还没回：这拍不发请求，但取证时照样留一行——
      // 网页算 tick 覆盖率量的是「进程还活着」，不是「请求发出去了」。
      if (sConfig.diag) {
        JSONObject sk = new JSONObject();
        try {
          sk.put("why", "pending-fix");
          sk.put("waitMs", mCurrentIntervalMs);
        } catch (Exception ignored) {
        }
        journal("skip", sk);
      }
      scheduleNext(mCurrentIntervalMs);
      return;
    }
    if (sConfig.diag && !wakeLockHeld()) {
      // 锁被系统收走 = 下一段熄屏一定会停拍，先记账再补拿一次
      JSONObject lo = new JSONObject();
      try {
        lo.put("why", "wakelock-lost");
      } catch (Exception ignored) {
      }
      journal("fail", lo);
      acquireWakeLock();
    }
    sTicks++;
    mPendingFix = true;
    if (sConfig.diag) {
      journal("tick", null);
    }
    try {
      mLocationManager.requestSingleUpdate(mProvider, mFixListener, Looper.getMainLooper());
    } catch (SecurityException | IllegalArgumentException e) {
      Log.e(LOGTAG, "requestSingleUpdate failed", e);
      sFailures++;
      sLastError = "requestSingleUpdate: " + e.getMessage();
      JSONObject err = new JSONObject();
      try {
        err.put("why", "requestSingleUpdate");
        err.put("msg", String.valueOf(e.getMessage()));
      } catch (Exception ignored) {
      }
      journal("fail", err);
      mPendingFix = false;
      // 提供者被关掉时换一个再试（GPS → 网络）
      mProvider = pickProvider();
      sProvider = mProvider;
      scheduleNext(5000);
      return;
    }
    // Watchdog: if no fix arrives in time, drop the pending flag and move on.
    mHandler.removeCallbacks(mFixWatchdog);
    mHandler.postDelayed(mFixWatchdog, mCurrentIntervalMs + FIX_WATCHDOG_SLACK_MS);
  }

  private void onFixWatchdog() {
    if (mPendingFix) {
      mPendingFix = false;
      sFailures++;
      sLastError = "取点超时（" + mCurrentIntervalMs + "ms 内没有回调）";
      if (sConfig.diag) {
        JSONObject to = new JSONObject();
        try {
          to.put("why", "timeout");
          to.put("waitMs", mCurrentIntervalMs);
        } catch (Exception ignored) {
        }
        journal("fail", to);
      }
      Log.w(LOGTAG, "fix watchdog fired, provider=" + mProvider);
      mProvider = pickProvider();
      sProvider = mProvider;
      scheduleNext(nextInterval());
    }
  }

  private void onFix(Location location) {
    mPendingFix = false;
    mHandler.removeCallbacks(mFixWatchdog);
    if (!sRunning || mStopping) {
      return;
    }
    long now = System.currentTimeMillis();
    double lat = location.getLatitude();
    double lng = location.getLongitude();
    long ts = location.getTime();
    float accuracy = location.getAccuracy();
    sFixes++;
    sLastFixAt = now;
    if (sConfig.diag) {
      JSONObject fx = new JSONObject();
      try {
        // 账本存原生 WGS-84 原始坐标，页面负责换算成高德用的 GCJ-02
        fx.put("lat", lat);
        fx.put("lng", lng);
        fx.put("acc", accuracy);
        fx.put("fixTs", ts);
      } catch (Exception ignored) {
      }
      journal("fix", fx);
    }
    sLastError = "";

    JSONObject point = new JSONObject();
    try {
      point.put("ts", ts);
      point.put("lat", lat);
      point.put("lng", lng);
      point.put("accuracy", accuracy);
    } catch (Exception e) {
      Log.e(LOGTAG, "point build failed", e);
      scheduleNext(nextInterval());
      return;
    }

    // 新点：同点连击归零；合并：n+1，下一次等待翻倍
    boolean added = appendPoint(point);
    mSameCount = added ? 0 : mSameCount + 1;

    if (sListener != null) {
      try {
        sListener.onPoint(point);
      } catch (Exception e) {
        Log.w(LOGTAG, "listener onPoint failed", e);
      }
    }
    updateNotification(false);
    scheduleNext(nextInterval());
  }

  /**
   * Next interval by the page's continuous-sampling strategy (locationAuto.ts
   * nextSampleMs): fixed = base; backoff = base * 2^n capped at max.
   */
  private long nextInterval() {
    if (sConfig.diag) {
      // 取证模式：固定每秒一次，不退避——要的是「后台每一秒都问一遍」的密集样本
      return DIAG_INTERVAL_MS;
    }
    if (LocationConfig.STRATEGY_FIXED.equals(sConfig.strategy)) {
      return sConfig.intervalBaseMs;
    }
    int n = Math.min(Math.max(0, mSameCount), MAX_BACKOFF_N);
    long ms = sConfig.intervalBaseMs;
    for (int i = 0; i < n && ms < sConfig.intervalMaxMs; i++) {
      ms *= 2;
    }
    return Math.min(ms, sConfig.intervalMaxMs);
  }

  private void scheduleNext(long intervalMs) {
    mCurrentIntervalMs = intervalMs;
    sIntervalCurrentMs = intervalMs;
    if (!sRunning || mStopping) {
      return;
    }
    mHandler.removeCallbacks(mTick);
    mHandler.postDelayed(mTick, intervalMs);
  }

  /** Apply a config change while running (just reschedule the next tick). */
  private void reschedule() {
    if (sRunning) {
      mHandler.removeCallbacks(mTick);
      mHandler.postDelayed(mTick, 0);
    }
  }

  // ---------------------------------------------------------------------------
  // appendPoint() — native replica of the page's locationAuto.ts
  // ---------------------------------------------------------------------------

  /**
   * Compare only with the last recorded point (haversine). Inside the threshold:
   * merge (refresh ts + accuracy, keep coordinates/note), do not add, rev stays.
   * Outside (or threshold 0): append a new point, rev++, persist immediately.
   *
   * @return true if a new point was appended, false if merged into the last one
   */
  private boolean appendPoint(JSONObject point) {
    if (mLastPoint == null) {
      addPoint(point);
      return true;
    }
    try {
      double distM =
          haversineMeters(
              mLastPoint.getDouble("lat"),
              mLastPoint.getDouble("lng"),
              point.getDouble("lat"),
              point.getDouble("lng"));
      double threshold = sConfig.thresholdM;
      if (threshold > 0 && distM < threshold) {
        // Merged: refresh ts + accuracy only; coordinates/note keep the first value.
        mLastPoint.put("ts", point.getLong("ts"));
        mLastPoint.put("accuracy", point.getDouble("accuracy"));
        updateLastPointStatics(mLastPoint);
        maybePersistTimeRefresh();
        return false;
      }
      addPoint(point);
      return true;
    } catch (Exception e) {
      Log.e(LOGTAG, "appendPoint failed", e);
      return false;
    }
  }

  private void addPoint(JSONObject point) {
    sRev++;
    try {
      point.put("rev", sRev);
    } catch (Exception e) {
      // ignore
    }
    mLastPoint = point;
    sPoints++;
    updateLastPointStatics(point);
    persistLine(point, false);
  }

  private void updateLastPointStatics(JSONObject point) {
    try {
      sLastTs = point.getLong("ts");
      sLastLat = point.getDouble("lat");
      sLastLng = point.getDouble("lng");
      sLastAccuracy = point.getDouble("accuracy");
      sLastNote = point.optString("note", "");
    } catch (Exception ignored) {
    }
  }

  /** Time-only refreshes persist at most once every FLUSH_MS. */
  private void maybePersistTimeRefresh() {
    long now = System.currentTimeMillis();
    if (now - mLastPersistTs >= FLUSH_MS) {
      if (mLastPoint != null) {
        persistLine(mLastPoint, true);
      }
      mLastPersistTs = now;
    }
  }

  /**
   * Append a new point line (fast path), or rewrite the last line for a merged
   * (time-refresh) update so read-back semantics stay identical to the page's rows.
   */
  private synchronized void persistLine(JSONObject point, boolean replaceLast) {
    try {
      if (!replaceLast) {
        try (FileOutputStream fos = new FileOutputStream(mLogFile, true);
            OutputStreamWriter writer =
                new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
          writer.write(point.toString());
          writer.write("\n");
          writer.flush();
        }
        return;
      }
      List<String> lines = readLines();
      if (!lines.isEmpty()) {
        lines.remove(lines.size() - 1);
      }
      lines.add(point.toString());
      try (FileOutputStream fos = new FileOutputStream(mLogFile, false);
          OutputStreamWriter writer =
              new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
        for (String line : lines) {
          writer.write(line);
          writer.write("\n");
        }
        writer.flush();
      }
    } catch (Exception e) {
      Log.e(LOGTAG, "persistLine failed", e);
    }
  }

  // ---------------------------------------------------------------------------
  // File helpers
  // ---------------------------------------------------------------------------

  /** 一行一条 JSON 追加写；文件过大时丢掉较旧的一半。 */
  private void journal(String kind, @Nullable JSONObject extra) {
    if (mJournalFile == null) {
      return;
    }
    long now = System.currentTimeMillis();
    // 空档本身不会自己说话：这一段为什么没落笔，要用三个时钟的差值来判。
    // elapsedRealtime 含深睡，uptimeMillis 不含深睡，墙钟含一切——
    // 三者一比就知道到底是整机睡了、进程冻了，还是单纯取不到点。
    if (sConfig != null && sConfig.diag && mLastJournalWall > 0 && now - mLastJournalWall > 5000
        && !"probe".equals(kind) && !"wake".equals(kind) && !"start".equals(kind)
        && !"stop".equals(kind) && !"diag".equals(kind)) {
      JSONObject gap = probeGap(now);
      mLastJournalWall = now;
      mLastJournalUp = SystemClock.uptimeMillis();
      mLastJournalEl = SystemClock.elapsedRealtime();
      journal("probe", gap);
    }
    if (kind.equals("tick") || kind.equals("fix") || kind.equals("fail")
        || kind.equals("skip") || kind.equals("probe") || kind.equals("wake")) {
      mLastJournalWall = now;
      mLastJournalUp = SystemClock.uptimeMillis();
      mLastJournalEl = SystemClock.elapsedRealtime();
    }
    try {
      JSONObject line = new JSONObject();
      line.put("t", System.currentTimeMillis());
      line.put("k", kind);
      line.put("n", sTicks);
      line.put("p", sProvider);
      line.put("wl", wakeLockHeld());
      line.put("f", sFixes);
      line.put("x", sFailures);
      line.put("pts", sPoints);
      mLastJournalKind = kind;
      if (sConfig != null) {
        line.put("diag", sConfig.diag);
      }
      if (extra != null) {
        java.util.Iterator<String> it = extra.keys();
        while (it.hasNext()) {
          String key = it.next();
          line.put(key, extra.get(key));
        }
      }
      synchronized (this) {
        try (FileOutputStream fos = new FileOutputStream(mJournalFile, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
          w.write(line.toString());
          w.write("\n");
          w.flush();
        }
        mJournalWrites++;
        if (mJournalWrites % 200 == 0 && mJournalFile.length() > JOURNAL_MAX_BYTES) {
          List<String> keep = readJournalLines();
          int half = keep.size() / 2;
          keep = keep.subList(half, keep.size());
          try (FileOutputStream fos = new FileOutputStream(mJournalFile, false);
              OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            for (String l : keep) {
              w.write(l);
              w.write("\n");
            }
            w.flush();
          }
          Log.i(LOGTAG, "journal trimmed to " + keep.size() + " lines");
        }
      }
    } catch (Exception e) {
      Log.w(LOGTAG, "journal write failed", e);
    }
  }

  private List<String> readJournalLines() {
    List<String> out = new ArrayList<>();
    File f = mJournalFile;
    if (f == null || !f.exists()) {
      return out;
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isEmpty()) {
          out.add(line);
        }
      }
    } catch (Exception e) {
      Log.e(LOGTAG, "readJournal failed", e);
    }
    return out;
  }

  /** 熄屏/亮屏/解锁都记账：休眠分析要把「亮灭屏」和「tick 空档」对齐看。 */
  private void registerScreenReceiver() {
    try {
      android.content.IntentFilter filter = new android.content.IntentFilter();
      filter.addAction(Intent.ACTION_SCREEN_OFF);
      filter.addAction(Intent.ACTION_SCREEN_ON);
      filter.addAction(Intent.ACTION_USER_PRESENT);
      mScreenReceiver =
          new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
              String a = intent != null ? intent.getAction() : null;
              JSONObject e = new JSONObject();
              try {
                e.put("what", a);
              } catch (Exception ignored) {
              }
              journal("screen", e);
            }
          };
      registerReceiver(mScreenReceiver, filter);
    } catch (Exception e) {
      Log.w(LOGTAG, "screen receiver not registered", e);
    }
  }

  private List<String> readLines() {
    List<String> lines = new ArrayList<>();
    if (mLogFile == null || !mLogFile.exists()) {
      return lines;
    }
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new FileInputStream(mLogFile), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isEmpty()) {
          lines.add(line);
        }
      }
    } catch (Exception e) {
      Log.e(LOGTAG, "readLines failed", e);
    }
    return lines;
  }

  private JSONArray readAllPoints() {
    JSONArray arr = new JSONArray();
    for (String line : readLines()) {
      try {
        arr.put(new JSONObject(line));
      } catch (Exception ignored) {
      }
    }
    return arr;
  }

  /** Restore last point / rev / count from the persisted file on (re)start. */
  private void restoreTailFromFile() {
    List<String> lines = readLines();
    if (lines.isEmpty()) {
      sPoints = 0;
      sRev = 0;
      mLastPoint = null;
      return;
    }
    sPoints = lines.size();
    String tail = lines.get(lines.size() - 1);
    try {
      mLastPoint = new JSONObject(tail);
      sRev = mLastPoint.optInt("rev", 0);
      updateLastPointStatics(mLastPoint);
    } catch (Exception e) {
      Log.w(LOGTAG, "restoreTail failed", e);
      mLastPoint = null;
      sRev = 0;
      sPoints = 0;
    }
  }

  private void clearFile() {
    synchronized (this) {
      if (mLogFile != null && mLogFile.exists()) {
        //noinspection ResultOfMethodCallIgnored
        mLogFile.delete();
      }
      mLastPoint = null;
    }
  }

  // ---------------------------------------------------------------------------
  // Misc
  // ---------------------------------------------------------------------------

  private boolean hasFineLocation() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  private String pickProvider() {
    if (mLocationManager == null) {
      return LocationManager.GPS_PROVIDER;
    }
    try {
      if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        return LocationManager.GPS_PROVIDER;
      }
      if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
        return LocationManager.NETWORK_PROVIDER;
      }
    } catch (Exception ignored) {
    }
    return LocationManager.GPS_PROVIDER;
  }

  /** Standard haversine great-circle distance in meters. */
  public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
    double R = 6371000.0;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
    return 2 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
  }

  @Override
  public void onDestroy() {
    journal("destroy", null);
    if (mScreenReceiver != null) {
      try {
        unregisterReceiver(mScreenReceiver);
      } catch (Exception ignored) {
      }
      mScreenReceiver = null;
    }
    if (sRunning) {
      sRunning = false;
      mHandler.removeCallbacks(mTick);
      mHandler.removeCallbacks(mFixWatchdog);
    }
    releaseWakeLock();
    cancelWakeAlarm();
    if (sInstance == this) {
      sInstance = null;
    }
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  // ---------------------------------------------------------------------------
  // Notification
  // ---------------------------------------------------------------------------

  private void createNotificationChannel() {
    NotificationManager nm = getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel(CHANNEL_ID, "位置记录", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("自动打点在后台记录位置时的常驻通知");
    nm.createNotificationChannel(channel);
  }

  /**
   * 常驻通知：既是前台服务的"门票"，也是后台续记的活体证据——熄屏后瞄一眼
   * 通知栏就知道刚才有没有在记（点数、最近一次定位、当前间隔）。
   */
  private Notification buildNotification() {
    Intent stopIntent = new Intent(this, LocationTrackingService.class).setAction(ACTION_STOP);
    PendingIntent stopPi =
        PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Intent openIntent = new Intent(this, WebViewActivity.class);
    openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent openPi =
        PendingIntent.getActivity(
            this, 2, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

    StringBuilder text = new StringBuilder();
    text.append("已记 ").append(sPoints).append(" 个点");
    if (sLastFixAt > 0) {
      text.append(" · 最近 ").append(clock(sLastFixAt));
      if (sLastAccuracy > 0) {
        text.append(" ±").append(Math.round(sLastAccuracy)).append("m");
      }
    } else if (sFailures > 0) {
      text.append(" · 还没取到点（失败 ").append(sFailures).append(" 次）");
    } else {
      text.append(" · 等待第一个定位点…");
    }
    if (sIntervalCurrentMs > 0) {
      text.append(" · 间隔 ").append(sIntervalCurrentMs / 1000).append("s");
    }
    return new Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("正在记录位置")
        .setContentText(text.toString())
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentIntent(openPi)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .build();
  }

  /** 刷新通知文案（节流；force=true 用于起停这种关键时刻）。 */
  private void updateNotification(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && now - sLastNotifyAt < NOTIFY_THROTTLE_MS) {
      return;
    }
    sLastNotifyAt = now;
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      nm.notify(NOTIFICATION_ID, buildNotification());
    } catch (Exception e) {
      Log.w(LOGTAG, "notify failed", e);
    }
  }

  /** 通知里的 HH:mm:ss（后台诊断只看得懂这个粒度）。 */
  private static String clock(long ms) {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA);
    return f.format(new java.util.Date(ms));
  }
}
