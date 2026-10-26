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
 *       the service when enabled toggles (needs fine-location permission).</li>
 *   <li>location.start — request permissions and start the foreground service
 *       (also persists enabled=true so a system restart resumes).</li>
 *   <li>location.stop — stop the service (persists enabled=false).</li>
 *   <li>location.status — {tracking, startedAt, points, rev, lastPoint, wakeLock,
 *       provider, lastFixAt, fixes, failures, lastError, intervalCurrentMs,
 *       fineLocation, backgroundLocation, batteryOptimized, config:{...}}.</li>
 *   <li>location.setDiag {on} — 取证模式：每秒申请一次定位 + 写 bg_journal.jsonl
 *       账本（tick/fix/fail/screen/provider/start/stop/taskRemoved/destroy）。</li>
 *   <li>location.journal {since?, tail?} → {lines, count, total, bytes, ticks, diag,
 *       tracking} — 网页据此判「壳在后台是被冻住了，还是醒着取不到点」。</li>
 *   <li>location.clearJournal — 换一轮实验时清空账本。</li>
 *   <li>location.getHistory — all recorded points ({points:[...], rev:N}) so the
 *       page can merge them through its own appendPoint().</li>
 *   <li>location.clearHistory — wipe the recorded points.</li>
 *   <li>location.requestPermissions {background?:bool} — ask for ACCESS_FINE_LOCATION;
 *       with background=true also guide/ask for the "allow all the time" permission.
 *       → {fine, background, note}</li>
 *   <li>location.batteryOptimization — {ignoring} 当前是否已在电池优化白名单。</li>
 *   <li>location.requestBatteryExemption — 拉起系统的"忽略电池优化"弹窗/设置页。
 *       → {launched}</li>
 * </ul>
 *
 * <p>Events: location.point（每记一个点推一次）、location.state（服务起/停时推
 * {tracking, points, rev, reason}）。</p>
 */
public class LocationBridge {
  private static final String LOGTAG = "LocationBridge";

  private final Context mContext;
  private final JsBridge mBridge;

  public LocationBridge(Context context, JsBridge bridge) {
    mContext = context.getApplicationContext();
    mBridge = bridge;
    LocationTrackingService.setListener(
        new LocationTrackingService.Listener() {
          @Override
          public void onPoint(JSONObject point) {
            mBridge.emit("location.point", point);
          }

          @Override
          public void onState(JSONObject state) {
            mBridge.emit("location.state", state);
          }
        });
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
      case "location.setDiag":
        handleSetDiag(id, params, bridge);
        break;
      case "location.journal":
        handleJournal(id, params, bridge);
        break;
      case "location.getHistory":
        handleGetHistory(id, bridge);
        break;
      case "location.clearJournal":
        handleClearJournal(id, bridge);
        break;
      case "location.clearHistory":
        handleClearHistory(id, bridge);
        break;
      case "location.requestPermissions":
        handleRequestPermissions(id, params, bridge);
        break;
      case "location.batteryOptimization":
        handleBatteryStatus(id, bridge);
        break;
      case "location.requestBatteryExemption":
        handleBatteryExemption(id, bridge);
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
      // 老页面不带 diag：别把正在进行的取证模式静默关掉
      if (!params.has("diag")) {
        cfg.diag = LocationConfig.load(mContext).diag;
      }
      // 取证开着就不能停：页面一次 enabled=false 会把正在取证的采集停掉，
      // 账本立刻一片空白，看起来就像「壳睡着了」——分不清是冻还是停。
      boolean keptForDiag = false;
      if (!cfg.enabled && cfg.diag) {
        cfg.enabled = true;
        keptForDiag = true;
      }
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
      r.put("strategy", cfg.strategy);
      r.put("intervalBaseMs", cfg.intervalBaseMs);
      r.put("intervalMaxMs", cfg.intervalMaxMs);
      r.put("tracking", LocationTrackingService.isRunning());
      // 开了自动打点但没权限：网页据此弹「请求定位权限」
      r.put("needPermission", cfg.enabled && !hasFineLocation());
      if (keptForDiag) {
        r.put("keptForDiag", true);
        r.put("keptForDiagNote", "取证模式开着，enabled 被强行留在 true（要真停先关取证）");
      }
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
    // 显式 start 也把 enabled 落盘：系统重建服务时靠它决定要不要恢复。
    markEnabled(true);
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
    markEnabled(false);
    stopService();
    bridge.respond(id, true, status(), null);
  }

  private void handleStatus(String id, JsBridge bridge) {
    bridge.respond(id, true, status(), null);
  }

  /**
   * 权限申请：前台定位（ACCESS_FINE_LOCATION）是必需项；background=true 时再引导
   * 「始终允许」。Android 11+ 规定后台定位必须去系统设置里单独授予，不能再和
   * 前台权限一次弹窗解决，所以这里只负责"拉到那个页面"，然后回报当前状态。
   */
  private void handleRequestPermissions(String id, JSONObject params, JsBridge bridge) {
    final boolean wantBackground = params.optBoolean("background", false);
    if (hasFineLocation()) {
      if (wantBackground) {
        maybeGuideBackgroundPermission();
      }
      bridge.respond(id, true, permissionJson(), null);
      return;
    }
    JsBridge.requestPermissions(
        bridge.activity(),
        JsBridge.PERM_LOCATION,
        new String[] {Manifest.permission.ACCESS_FINE_LOCATION},
        new JsBridge.PermissionCallback() {
          @Override
          public void onGranted() {
            if (wantBackground) {
              maybeGuideBackgroundPermission();
            }
            bridge.respond(id, true, permissionJson(), null);
          }

          @Override
          public void onDenied() {
            bridge.respond(id, false, null, "定位权限被拒绝");
          }
        });
  }

  private void handleBatteryStatus(String id, JsBridge bridge) {
    try {
      JSONObject o = new JSONObject();
      o.put("ignoring", isIgnoringBatteryOptimizations());
      bridge.respond(id, true, o, null);
    } catch (Exception e) {
      bridge.respond(id, false, null, "batteryOptimization: " + e.getMessage());
    }
  }

  /**
   * 忽略电池优化：国产 ROM（以及原生 Doze）都会在熄屏一段时间后收紧后台定位，
   * 把壳加白是最有效的一招。优先用系统的直连弹窗，拿不到就退到设置列表页。
   */
  private void handleBatteryExemption(String id, JsBridge bridge) {
    Activity activity = mBridge.activity();
    JSONObject r = new JSONObject();
    try {
      if (isIgnoringBatteryOptimizations()) {
        r.put("launched", false);
        r.put("ignoring", true);
        r.put("note", "已经在白名单里");
        bridge.respond(id, true, r, null);
        return;
      }
      boolean launched = false;
      if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
          Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
          direct.setData(Uri.parse("package:" + activity.getPackageName()));
          activity.startActivity(direct);
          launched = true;
        } catch (Exception e) {
          Log.w(LOGTAG, "direct battery dialog failed, fall back to settings list", e);
        }
        if (!launched) {
          try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            launched = true;
          } catch (Exception e) {
            Log.w(LOGTAG, "battery settings fallback failed", e);
          }
        }
      }
      r.put("launched", launched);
      r.put("ignoring", isIgnoringBatteryOptimizations());
      if (!launched) {
        r.put("note", "没能拉起系统页面，请手动到「电池 → 应用启动管理」里放行");
      }
      bridge.respond(id, true, r, null);
    } catch (Exception e) {
      bridge.respond(id, false, null, "requestBatteryExemption: " + e.getMessage());
    }
  }

  /**
   * 开关取证模式：写进配置存档（系统重建服务也还知道在取证），并立刻按每秒一次
   * 重排采样。打开时顺带把 enabled 也打开——不然取证没意义（根本没在采集）。
   */
  private void handleSetDiag(String id, JSONObject params, JsBridge bridge) {
    try {
      boolean on = params.optBoolean("on", false);
      LocationConfig cfg = LocationConfig.load(mContext);
      cfg.diag = on;
      if (on) {
        cfg.enabled = true;
      }
      cfg.save(mContext);
      LocationTrackingService.applyConfig(mContext);
      LocationTrackingService.notifyDiag(on);
      JSONObject r = LocationTrackingService.statusJson();
      r.put("diag", on);
      r.put("enabled", cfg.enabled);
      r.put("needPermission", cfg.enabled && !hasFineLocation());
      if (on && !LocationTrackingService.isRunning() && hasFineLocation()) {
        startService();
      }
      bridge.respond(id, true, r, null);
    } catch (Exception e) {
      bridge.respond(id, false, null, "setDiag: " + e.getMessage());
    }
  }

  /** 读取证账本：{since?, tail?}，默认最近 3000 行。 */
  private void handleJournal(String id, JSONObject params, JsBridge bridge) {
    long since = params.optLong("since", 0);
    int tail = (int) Math.max(1, Math.min(50000, params.optInt("tail", 3000)));
    bridge.respond(id, true, LocationTrackingService.journalJson(since, tail), null);
  }

  /** 清空账本：换一轮实验时用，免得上一轮的记录混进分析。 */
  private void handleClearJournal(String id, JsBridge bridge) {
    try {
      java.io.File f = new java.io.File(mContext.getFilesDir(), "bg_journal.jsonl");
      boolean gone = !f.exists() || f.delete();
      JSONObject r = new JSONObject();
      r.put("cleared", gone);
      r.put("bytes", f.exists() ? f.length() : 0);
      bridge.respond(id, true, r, null);
    } catch (Exception e) {
      bridge.respond(id, false, null, "clearJournal: " + e.getMessage());
    }
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
      o.put("strategy", cfg.strategy);
      o.put("intervalBaseMs", cfg.intervalBaseMs);
      o.put("intervalMaxMs", cfg.intervalMaxMs);
      JSONObject c = new JSONObject();
      c.put("enabled", cfg.enabled);
      c.put("thresholdM", cfg.thresholdM);
      c.put("strategy", cfg.strategy);
      c.put("intervalBaseMs", cfg.intervalBaseMs);
      c.put("intervalMaxMs", cfg.intervalMaxMs);
      o.put("config", c);
      o.put("fineLocation", hasFineLocation());
      o.put("backgroundLocation", hasBackgroundLocation());
      o.put("batteryOptimized", !isIgnoringBatteryOptimizations());
    } catch (Exception ignored) {
    }
    return o;
  }

  /** 权限现状：网页的「权限」一行用这两个布尔值。 */
  private JSONObject permissionJson() {
    JSONObject o = new JSONObject();
    try {
      o.put("fine", hasFineLocation());
      o.put("background", hasBackgroundLocation());
      if (hasFineLocation() && !hasBackgroundLocation() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        o.put("note", "后台定位需在系统设置里选「始终允许」");
      }
    } catch (Exception ignored) {
    }
    return o;
  }

  /** 把 enabled 写回配置存档（start/stop 走这条路，保证重启语义一致）。 */
  private void markEnabled(boolean enabled) {
    try {
      LocationConfig cfg = LocationConfig.load(mContext);
      cfg.enabled = enabled;
      cfg.save(mContext);
      LocationTrackingService.applyConfig(mContext);
    } catch (Exception e) {
      Log.w(LOGTAG, "markEnabled failed", e);
    }
  }

  private boolean hasFineLocation() {
    return mContext.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasBackgroundLocation() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return hasFineLocation();
    }
    return mContext.checkCallingOrSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  /** 是否已在电池优化白名单（true = 系统不会再限制后台）。 */
  private boolean isIgnoringBatteryOptimizations() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return true;
    }
    try {
      android.os.PowerManager pm =
          (android.os.PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
      return pm != null && pm.isIgnoringBatteryOptimizations(mContext.getPackageName());
    } catch (Exception e) {
      return false;
    }
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
