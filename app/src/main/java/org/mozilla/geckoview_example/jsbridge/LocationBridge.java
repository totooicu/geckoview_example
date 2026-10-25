package org.mozilla.geckoview_example.jsbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 *   <li>location.getHistory — all recorded points ({points:[...], rev:N}) so the
 *       page can merge them through its own appendPoint().</li>
 *   <li>location.clearHistory — wipe the recorded points.</li>
 *   <li>location.getFix — one-shot fix for manual "我的位置 / 打点": same native GPS
 *       source as the continuous tracker, bypassing the WebView's
 *       navigator.geolocation (which silently fails in this shell). Replies
 *       {lat, lng, accuracy} on success (WGS-84 metres, page converts to GCJ-02),
 *       or ok:false on failure (e.g. permission denied / no signal); missing
 *       fine-location triggers the system permission prompt first, denial replies
 *       ok:false. Never stay silent — the page would hang until its own timeout.</li>
 * </ul>
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
        handleGetHistory(id, bridge);
        break;
      case "location.clearHistory":
        handleClearHistory(id, bridge);
        break;
      case "location.getFix":
        handleGetFix(id, bridge);
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

  private void handleGetHistory(String id, JsBridge bridge) {
    bridge.respond(id, true, LocationTrackingService.historyJson(), null);
  }

  private void handleClearHistory(String id, JsBridge bridge) {
    LocationTrackingService.clearHistory();
    bridge.respond(id, true, LocationTrackingService.statusJson(), null);
  }

  /** Manual one-shot fix: "我的位置 / 打点" prefers this over the WebView's
   *  navigator.geolocation (which silently fails in this shell). */
  private void handleGetFix(String id, JsBridge bridge) {
    if (hasFineLocation()) {
      doGetFix(id, bridge);
      return;
    }
    // 缺定位权限：先弹系统权限申请（同 setConfig/start），授予后再取点；拒绝回 ok:false
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
            doGetFix(id, bridge);
          }

          @Override
          public void onDenied() {
            bridge.respond(id, false, null, "定位权限被拒绝");
          }
        });
  }

  /** Request a single fix directly from the system LocationManager — the same
   *  native source the continuous tracker uses, so it works where WebView
   *  geolocation does not. Replies WGS-84 (page converts to GCJ-02). */
  private void doGetFix(String id, JsBridge bridge) {
    LocationManager lm =
        (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    if (lm == null) {
      bridge.respond(id, false, null, "定位服务不可用");
      return;
    }
    final String provider = pickProvider(lm);

    // Single one-shot listener + timeout so the page never hangs waiting.
    final LocationListener[] oneShot = new LocationListener[1];
    final Handler handler = new Handler(Looper.getMainLooper());
    final Runnable timeout =
        () -> {
          if (oneShot[0] != null) {
            try {
              lm.removeUpdates(oneShot[0]);
            } catch (Exception ignored) {
            }
          }
          bridge.respond(id, false, null, "定位超时");
        };

    oneShot[0] =
        new LocationListener() {
          @Override
          public void onLocationChanged(Location location) {
            try {
              lm.removeUpdates(this);
            } catch (Exception ignored) {
            }
            handler.removeCallbacks(timeout);
            try {
              // LocationManager 给的是 WGS-84，页面会换算成 GCJ-02
              JSONObject r = new JSONObject();
              r.put("lat", location.getLatitude());
              r.put("lng", location.getLongitude());
              r.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 0);
              bridge.respond(id, true, r, null);
            } catch (Exception e) {
              bridge.respond(id, false, null, "getFix: " + e.getMessage());
            }
          }

          @Override
          public void onStatusChanged(String p, int s, Bundle e) {}

          @Override
          public void onProviderEnabled(String p) {}

          @Override
          public void onProviderDisabled(String p) {
            try {
              lm.removeUpdates(this);
            } catch (Exception ignored) {
            }
            handler.removeCallbacks(timeout);
            bridge.respond(id, false, null, "定位已被关闭");
          }
        };

    try {
      lm.requestSingleUpdate(provider, oneShot[0], Looper.getMainLooper());
    } catch (SecurityException | IllegalArgumentException e) {
      bridge.respond(id, false, null, "getFix: " + e.getMessage());
      return;
    }
    // 比页面 9s 超时略短，先回错误让页面降级，避免两边同时超时
    handler.postDelayed(timeout, 8000);
  }

  /** Pick the best-enabled provider, mirroring LocationTrackingService.pickProvider. */
  private static String pickProvider(LocationManager lm) {
    try {
      if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        return LocationManager.GPS_PROVIDER;
      }
      if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
        return LocationManager.NETWORK_PROVIDER;
      }
    } catch (Exception ignored) {
    }
    return LocationManager.GPS_PROVIDER;
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
