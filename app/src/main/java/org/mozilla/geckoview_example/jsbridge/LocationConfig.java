package org.mozilla.geckoview_example.jsbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

/**
 * Configuration for the long-running location tracking service.
 *
 * <p>The page owns the "source of truth" in its browser storage (localStorage key
 * {@code dr.autoLocation}); this class is the native mirror persisted to
 * SharedPreferences. The page pushes changes via {@code location.setConfig}, and the
 * activity also pulls the value once on page load so native always has the latest
 * copy even if the page never calls the bridge.</p>
 *
 * <p>Semantics are aligned with the page's locationAuto.ts:</p>
 * <ul>
 *   <li>thresholdM: dedupe distance in meters; non-number or negative is normalized
 *       to 0 (no dedupe, record every fix). Default 5.</li>
 *   <li>moveThresholdM: distance that counts as "location is moving", driving the
 *       1s fast frequency. Taken from the page config; default 10.</li>
 *   <li>intervalFastMs / intervalMidMs / intervalSlowMs: 1s / 30s / 60s defaults,
 *       overridable by the page config.</li>
 * </ul>
 */
public class LocationConfig {
  private static final String LOGTAG = "LocationConfig";

  public static final String PREF_NAME = "dr_location_config";
  private static final String KEY_JSON = "json";

  /** Whether long-running tracking is enabled (from page config enabled field). */
  public volatile boolean enabled = false;

  /** Dedupe threshold in meters; 0 = record every fix. Default 5. */
  public volatile double thresholdM = 5;

  /** Distance (m) that marks "location moving" -> fast 1s interval. Default 10. */
  public volatile double moveThresholdM = 10;

  /** Fast interval (location moving). */
  public volatile long intervalFastMs = 1000;

  /** Mid interval (phone moving, location still). */
  public volatile long intervalMidMs = 30000;

  /** Slow interval (nothing moving). */
  public volatile long intervalSlowMs = 60000;

  /** Parse from the page's dr.autoLocation JSON; missing fields keep defaults. */
  public static LocationConfig fromJson(String json) {
    LocationConfig cfg = new LocationConfig();
    if (json == null || json.isEmpty()) {
      return cfg;
    }
    try {
      JSONObject o = new JSONObject(json);
      if (o.has("enabled")) {
        cfg.enabled = o.optBoolean("enabled", false);
      }
      cfg.thresholdM = normalizeNonNegative(o, "thresholdM", 5);
      cfg.moveThresholdM = normalizePositive(o, "moveThresholdM", 10.0);
      cfg.intervalFastMs = normalizePositive(o, "intervalFastMs", 1000L);
      cfg.intervalMidMs = normalizePositive(o, "intervalMidMs", 30000L);
      cfg.intervalSlowMs = normalizePositive(o, "intervalSlowMs", 60000L);
    } catch (Exception e) {
      Log.w(LOGTAG, "fromJson failed: " + e.getMessage());
    }
    return cfg;
  }

  /** thresholdM: non-number or negative -> 0 (dedupe disabled). */
  private static double normalizeNonNegative(JSONObject o, String key, double def) {
    if (!o.has(key)) {
      return def;
    }
    double v = o.optDouble(key, Double.NaN);
    if (Double.isNaN(v) || v < 0) {
      return 0;
    }
    return v;
  }

  /** Other numeric fields: non-number or non-positive -> default. */
  private static long normalizePositive(JSONObject o, String key, long def) {
    if (!o.has(key)) {
      return def;
    }
    double v = o.optDouble(key, Double.NaN);
    if (Double.isNaN(v) || v <= 0) {
      return def;
    }
    return Math.max(1, Math.round(v));
  }

  private static double normalizePositive(JSONObject o, String key, double def) {
    if (!o.has(key)) {
      return def;
    }
    double v = o.optDouble(key, Double.NaN);
    if (Double.isNaN(v) || v <= 0) {
      return def;
    }
    return v;
  }

  public void save(Context context) {
    try {
      JSONObject o = new JSONObject();
      o.put("enabled", enabled);
      o.put("thresholdM", thresholdM);
      o.put("moveThresholdM", moveThresholdM);
      o.put("intervalFastMs", intervalFastMs);
      o.put("intervalMidMs", intervalMidMs);
      o.put("intervalSlowMs", intervalSlowMs);
      context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
          .edit()
          .putString(KEY_JSON, o.toString())
          .apply();
    } catch (Exception e) {
      Log.w(LOGTAG, "save failed: " + e.getMessage());
    }
  }

  public static LocationConfig load(Context context) {
    try {
      String json =
          context
              .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
              .getString(KEY_JSON, null);
      return fromJson(json);
    } catch (Exception e) {
      Log.w(LOGTAG, "load failed: " + e.getMessage());
      return new LocationConfig();
    }
  }
}
