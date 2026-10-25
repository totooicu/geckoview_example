package org.mozilla.geckoview_example.jsbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Long-running location tracking bridge.
 *
 * <p>Actions (via JsBridge):</p>
 * <ul>
 *   <li>location.setConfig {enabled, thresholdM, strategy, intervalBaseMs, intervalMaxMs}
 *       — page pushes its dr.autoLocation config; native saves it and auto-starts/stops
 *       the service when enabled toggles. When enabled but fine-location is missing,
 *       the system permission dialog is requested first (like location.start); on
 *       denial the reply is ok:false so the page can fall back to its own timer.</li>
 *   <li>location.start — request permissions and start the foreground service.</li>
 *   <li>location.stop — stop the service.</li>
 *   <li>location.status — {tracking, startedAt, points, rev, lastPoint}.</li>
 *   <li>location.getHistory {since?} — recorded points ({points:[...], rev:N}) with
 *       ts &gt; since (omit for all), so the page can merge them through its own
 *       appendPoint(). 去重 / 顶替全在网页那一层，壳不做判断。</li>
 *   <li>location.clearHistory — wipe the recorded points.</li>
 * </ul>
 *
 * <p>壳不做任何定位策略：不按位移退避、不去重、不看传感器。取样就是「按
 * intervalBaseMs 固定间隔取一次，取到就存一条、推一条」；什么时候记、记不记由
 * 网页决定。两套策略并存只会状态不同步（页面刷新把自己的退避清零，壳的服务
 * 跨刷新活着不清零，于是刷新前后节奏不一样）。</p>
 */
public class LocationBridge {
  private static final String LOGTAG = "LocationBridge";

  private final Context mContext;
  private final JsBridge mBridge;

  public LocationBridge(Context context, JsBridge bridge) {
    mContext = context.getApplicationContext();
    mBridge = bridge;
    LocationTrackingService.setListener(
        point -> mBridge.emit("location.point", point));
  }

  /** Called by JsBridge router. */
  public void handle(String id, String action, JSONObject params, JsBridge bridge) {
    switch (action) {
      case "location.setConfig":
        handleSetConfig(id, params, bridge);
        break;
      case "location.start":
        handleStart(id, bridge);
        break;
      case "location.stop":
        handleStop(id, bridge);
        break;
      case "location.status":
        handleStatus(id, bridge);
        break;
      case "location.getHistory":
        handleGetHistory(id, params, bridge);
        break;
      case "location.clearHistory":
        handleClearHistory(id, bridge);
        break;
      default:
        bridge.respond(id, false, null, "unknown location action: " + action);
    }
  }

  /* ----------------------------------------------------------------------------
   * Actions
   * -------------------------------------------------------------------------- */

  private void handleSetConfig(String id, JSONObject params, JsBridge bridge) {
    try {
      LocationConfig cfg = LocationConfig.fromJson(params.toString());
      cfg.save(mContext);
      LocationTrackingService.applyConfig(mContext);
      // Auto start/stop by the page's enabled flag.
      if (cfg.enabled) {
        if (hasFineLocation()) {
          startService();
          respondSetConfig(id, cfg, bridge);
        } else {
          // 没有定位权限时不静默跳过（铁律六：权限被拒要降级可解释）：
          // 复用 location.start 的系统权限申请路径。授予后起服务并回复
          // tracking:true；拒绝则回复 ok:false，让网页退回页面定时器并提示用户。
          Activity activity = bridge.activity();
          if (activity == null) {
            bridge.respond(id, false, null, "壳界面不可用，无法申请定位权限");
            return;
          }
          JsBridge.requestPermissions(
              activity,
              JsBridge.PERM_LOCATION,
              new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
              new JsBridge.PermissionCallback() {
                @Override
                public void onGranted() {
                  maybeGuideBackgroundPermission();
                  startService();
                  respondSetConfig(id, cfg, bridge);
                }

                @Override
                public void onDenied() {
                  bridge.respond(id, false, null, "定位权限被拒绝");
                }
              });
        }
      } else {
        stopService();
        respondSetConfig(id, cfg, bridge);
      }
    } catch (Exception e) {
      Log.e(LOGTAG, "setConfig failed", e);
      bridge.respond(id, false, null, "setConfig: " + e.getMessage());
    }
  }

  private void respondSetConfig(String id, LocationConfig cfg, JsBridge bridge) {
    try {
      JSONObject r = new JSONObject();
      r.put("enabled", cfg.enabled);
      r.put("thresholdM", cfg.thresholdM);
      r.put("moveThresholdM", cfg.moveThresholdM);
      r.put("strategy", cfg.strategy);
      r.put("intervalBaseMs", cfg.intervalBaseMs);
      r.put("intervalMaxMs", cfg.intervalMaxMs);
      r.put("tracking", LocationTrackingService.isRunning());
      bridge.respond(id, true, r, null);
    } catch (Exception e) {
      Log.e(LOGTAG, "respondSetConfig failed", e);
      bridge.respond(id, false, null, "setConfig: " + e.getMessage());
    }
  }

  private void handleStart(String id, JsBridge bridge) {
    if (LocationTrackingService.isRunning()) {
      try {
        JSONObject r = status();
        r.put("alreadyRunning", true);
        bridge.respond(id, true, r, null);
      } catch (Exception e) {
        bridge.respond(id, true, status(), null);
      }
      return;
    }
    if (hasFineLocation()) {
      maybeGuideBackgroundPermission();
      startService();
      bridge.respond(id, true, status(), null);
      return;
    }
    // Request the runtime permission first, then start.
    JsBridge.requestPermissions(
        bridge.activity(),
        JsBridge.PERM_LOCATION,
        new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
        new JsBridge.PermissionCallback() {
          @Override
          public void onGranted() {
            maybeGuideBackgroundPermission();
            startService();
            bridge.respond(id, true, status(), null);
          }

          @Override
          public void onDenied() {
            bridge.respond(id, false, null, "定位权限被拒绝");
          }
        });
  }

  private void handleStop(String id, JsBridge bridge) {
    stopService();
    bridge.respond(id, true, status(), null);
  }

  private void handleStatus(String id, JsBridge bridge) {
    bridge.respond(id, true, status(), null);
  }

  private void handleGetHistory(String id, JSONObject params, JsBridge bridge) {
    // 网页把已合并到的时间戳传过来（since），跑久了也不用每次回传全量
    long since = params == null ? 0 : params.optLong("since", 0);
    bridge.respond(id, true, LocationTrackingService.historyJson(since), null);
  }

  private void handleClearHistory(String id, JsBridge bridge) {
    LocationTrackingService.clearHistory();
    bridge.respond(id, true, LocationTrackingService.statusJson(), null);
  }

  /* ----------------------------------------------------------------------------
   * Helpers
   * -------------------------------------------------------------------------- */

  private JSONObject status() {
    JSONObject o = LocationTrackingService.statusJson();
    try {
      LocationConfig cfg = LocationConfig.load(mContext);
      o.put("enabled", cfg.enabled);
      o.put("thresholdM", cfg.thresholdM);
      o.put("moveThresholdM", cfg.moveThresholdM);
      o.put("strategy", cfg.strategy);
      o.put("intervalBaseMs", cfg.intervalBaseMs);
      o.put("intervalMaxMs", cfg.intervalMaxMs);
    } catch (Exception ignored) {
    }
    return o;
  }

  private boolean hasFineLocation() {
    return mContext.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * Android 10+ background location is a separate permission granted only from
   * the system settings page. We keep the service running (it records while the
   * app is foreground) and guide the user to enable "Allow all the time".
   */
  private void maybeGuideBackgroundPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return;
    }
    Activity activity = mBridge.activity();
    if (activity == null) {
      return;
    }
    if (activity.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        == PackageManager.PERMISSION_GRANTED) {
      return;
    }
    Toast.makeText(
            activity, "请允许「始终使用位置」，后台记录才会持续", Toast.LENGTH_LONG)
        .show();
    try {
      Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
      i.setData(Uri.parse("package:" + activity.getPackageName()));
      activity.startActivity(i);
    } catch (Exception e) {
      Log.w(LOGTAG, "open settings failed", e);
    }
  }

  private void startService() {
    try {
      Intent i = new Intent(mContext, LocationTrackingService.class);
      i.setAction(LocationTrackingService.ACTION_START);
      mContext.startForegroundService(i);
    } catch (Exception e) {
      Log.e(LOGTAG, "startService failed", e);
    }
  }

  private void stopService() {
    try {
      Intent i = new Intent(mContext, LocationTrackingService.class);
      i.setAction(LocationTrackingService.ACTION_STOP);
      mContext.startService(i);
    } catch (Exception e) {
      Log.w(LOGTAG, "stopService failed", e);
    }
  }
}
