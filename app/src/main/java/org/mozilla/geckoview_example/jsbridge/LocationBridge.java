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
 *   <li>location.setConfig {enabled, thresholdM, moveThresholdM, ...} — page pushes
 *       its dr.autoLocation config; native saves it and auto-starts/stops the
 *       service when enabled toggles (needs fine-location permission).</li>
 *   <li>location.start — request permissions and start the foreground service.</li>
 *   <li>location.stop — stop the service.</li>
 *   <li>location.status — {tracking, startedAt, points, rev, lastPoint}.</li>
 *   <li>location.getHistory — all recorded points ({points:[...], rev:N}) so the
 *       page can merge them through its own appendPoint().</li>
 *   <li>location.clearHistory — wipe the recorded points.</li>
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
        }
      } else {
        stopService();
      }
      JSONObject r = new JSONObject();
      r.put("enabled", cfg.enabled);
      r.put("thresholdM", cfg.thresholdM);
      r.put("moveThresholdM", cfg.moveThresholdM);
      r.put("tracking", LocationTrackingService.isRunning());
      bridge.respond(id, true, r, null);
    } catch (Exception e) {
      Log.e(LOGTAG, "setConfig failed", e);
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
