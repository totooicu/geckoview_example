package org.mozilla.geckoview_example.jsbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

/**
 * 系统广播这一层的自启网：开机、亮屏、解锁、覆盖安装。
 *
 * <p>必须是清单里静态注册的接收器——运行时注册的接收器跟着进程一起死，进程死了就没有
 * 接收器了，正好是「被杀后再也起不来」的那个坑。静态注册的四条 action 都由系统投递，
 * 进程不在也会被拉起来：</p>
 *
 * <ul>
 *   <li>{@code BOOT_COMPLETED} / {@code LOCKED_BOOT_COMPLETED} — 整机重启后</li>
 *   <li>{@code SCREEN_ON} / {@code USER_PRESENT} — 用户每一次点亮屏幕、每一次解锁，
 *       是这几条里最常敲门的（也是国产 ROM「自启动」白名单默认最愿意放行的一类）</li>
 *   <li>{@code MY_PACKAGE_REPLACED} — 覆盖安装升级后，自己接回链，不用用户再打开一次</li>
 * </ul>
 *
 * <p>收到广播只做三件事：记一行账（{@link LocationTrackingService#journalStatic}，服务还没
 * 起来也要留痕）、按持久化的 {@code enabled} 决定排不排网、剩下的交给
 * {@link RecoveryArm#fromBroadcast}。这里<b>不</b>按返回 true 之类的有序广播语义做任何事，
 * 也不弹界面。</p>
 */
public class RecoveryReceiver extends BroadcastReceiver {
  private static final String LOGTAG = "RecoveryReceiver";

  @Override
  public void onReceive(Context ctx, Intent intent) {
    String action = intent == null ? null : intent.getAction();
    String why = reasonOf(action);
    if (why == null) {
      return;
    }
    LocationConfig cfg = LocationConfig.load(ctx);
    if (!cfg.enabled) {
      // 用户早就关了：不排网，还顺手把可能残留的网撤掉，免得白敲门又白耗电。
      RecoveryArm.cancelAll(ctx);
      Log.i(LOGTAG, action + ": enabled=false, nets cleared");
      return;
    }
    boolean running = LocationTrackingService.isRunning();
    JSONObject o = new JSONObject();
    try {
      o.put("why", why);
      o.put("stage", "broadcast");
      o.put("enabled", true);
      o.put("running", running);
      o.put("exact", RecoveryArm.canExact(ctx));
      o.put("job", RecoveryArm.jobScheduled(ctx));
    } catch (Exception ignored) {
    }
    LocationTrackingService.journalStatic(ctx, "recover", o);
    if (running) {
      // 进程还活着、采集还在跑：只补齐网（闹钟可能是上一次剩下的），不去动服务。
      RecoveryArm.armNets(ctx);
      Log.i(LOGTAG, action + ": already running, nets refreshed");
      return;
    }
    if (!RecoveryArm.hasLocationPerms(ctx)) {
      JSONObject bad = new JSONObject();
      try {
        bad.put("why", why);
        bad.put("msg", "no fine location permission");
      } catch (Exception ignored) {
      }
      LocationTrackingService.journalStatic(ctx, "fail", bad);
      return;
    }
    RecoveryArm.fromBroadcast(ctx, why);
    Log.i(LOGTAG, action + ": armed recovery net (" + why + ")");
  }

  /** action → 账本里的 reason；不认识的 action 一律不管。 */
  private static String reasonOf(String action) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(action)
        || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
      return RecoveryArm.WHY_BOOT;
    }
    if (Intent.ACTION_SCREEN_ON.equals(action)) {
      return RecoveryArm.WHY_SCREEN_ON;
    }
    if (Intent.ACTION_USER_PRESENT.equals(action)) {
      return RecoveryArm.WHY_UNLOCK;
    }
    if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      return RecoveryArm.WHY_REPLACED;
    }
    return null;
  }
}
