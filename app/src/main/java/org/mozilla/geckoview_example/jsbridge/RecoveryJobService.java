package org.mozilla.geckoview_example.jsbridge;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import org.json.JSONObject;

/**
 * 第二道兜底网：15 分钟一遍的周期任务。
 *
 * <p>和闹钟的分工——{@code setPersisted(true)} 让系统在整机重启后自己把它恢复，这是闹钟
 * 做不到的（闹钟不跨重启，除非另有 {@code BOOT_COMPLETED} 接收器补排，本包两条都留了）；
 * 代价是周期下限 15 分钟，太粗，不能当唯一的网。</p>
 *
 * <p>注意 {@code force-stop}（设置里「强行停止」、部分 ROM 划掉任务）会连任务带闹钟一起清掉，
 * 而且清掉后应用进入 stopped state，被动唤醒全废——那种死法只有下一条 net 能救：用户再次
 * 打开壳，或者 ROM 的自启白名单放行后的广播。这也是本包坚持要「亮屏/解锁」广播的原因。</p>
 */
public class RecoveryJobService extends JobService {
  private static final String LOGTAG = "RecoveryJobService";

  @Override
  public boolean onStartJob(JobParameters params) {
    LocationConfig cfg = LocationConfig.load(this);
    boolean running = LocationTrackingService.isRunning();
    JSONObject o = new JSONObject();
    try {
      o.put("why", RecoveryArm.WHY_JOB);
      o.put("stage", "job");
      o.put("enabled", cfg.enabled);
      o.put("running", running);
      o.put("exact", RecoveryArm.canExact(this));
    } catch (Exception ignored) {
    }
    LocationTrackingService.journalStatic(this, "recover", o);
    if (!cfg.enabled) {
      RecoveryArm.cancelAll(this);
      Log.i(LOGTAG, "job: enabled=false, nets cleared");
      return false;
    }
    if (running) {
      RecoveryArm.armNets(this);
      return false;
    }
    if (!RecoveryArm.hasLocationPerms(this)) {
      JSONObject bad = new JSONObject();
      try {
        bad.put("why", RecoveryArm.WHY_JOB);
        bad.put("msg", "no fine location permission");
      } catch (Exception ignored) {
      }
      LocationTrackingService.journalStatic(this, "fail", bad);
      return false;
    }
    // 任务上下文起前台服务，和接收器同一套规矩：先把网续上，再试一跳。
    RecoveryArm.armNets(this);
    RecoveryArm.fromBroadcast(this, RecoveryArm.WHY_JOB);
    return false;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return false;
  }
}
