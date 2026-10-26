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
 *   <li>strategy: "fixed" or "backoff". fixed records every intervalBaseMs; backoff
 *       waits intervalBaseMs * 2^n after n consecutive fixes merged into the same
 *       point (n resets on every new point), capped at intervalMaxMs.</li>
 *   <li>intervalBaseMs / intervalMaxMs: 1000 / 64000 defaults (i.e. 1→2→4→…→64s),
 *       overridable by the page config.</li>
 *   <li>moveThresholdM: legacy from shell &lt;= 1.0.1 (its 1s/30s/60s tiers). Still
 *       parsed so old pages keep sending it; no longer drives scheduling.</li>
 * </ul>
 */
public class LocationConfig {
  private static final String LOGTAG = "LocationConfig";

  public static final String PREF_NAME = "dr_location_config";
  private static final String KEY_JSON = "json";

  public static final String STRATEGY_FIXED = "fixed";
  public static final String STRATEGY_BACKOFF = "backoff";

  /** Whether long-running tracking is enabled (from page config enabled field). */
  public volatile boolean enabled = false;

  /** Dedupe threshold in meters; 0 = record every fix. Default 5. */
  public volatile double thresholdM = 5;

  /** @deprecated legacy "movement" gate of the 1s/30s/60s tiers; kept for old pages. */
  @Deprecated public volatile double moveThresholdM = 10;

  /** Sampling strategy: fixed or backoff. Default backoff (same-point doubling). */
  public volatile String strategy = STRATEGY_BACKOFF;

  /** Base interval: delay after a new point / fixed-mode cadence. */
  public volatile long intervalBaseMs = 1000;

  /**
   * 取证模式（diag）：每秒申请一次定位、不做退避，并把每一秒发生了什么追加写进
   * bg_journal.jsonl（tick / fix / fail / screen / provider / start / stop /
   * taskRemoved / destroy / diag）。网页测试端读 location.journal 判壳在后台
   * 有没有被系统休眠。默认关，避免长期吃存储与耗电。
   */
  public volatile boolean diag = false;

  /** Ceiling of the exponential backoff. */
  public volatile long intervalMaxMs = 64000;

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
      cfg.strategy = normalizeStrategy(o, "strategy", STRATEGY_BACKOFF);
      if (o.has("diag")) {
        cfg.diag = o.optBoolean("diag", false);
      }
      cfg.intervalBaseMs = normalizePositive(o, "intervalBaseMs", 1000L);
      cfg.intervalMaxMs = normalizePositive(o, "intervalMaxMs", 64000L);
      if (cfg.intervalMaxMs < cfg.intervalBaseMs) {
        cfg.intervalMaxMs = cfg.intervalBaseMs;
      }
    } catch (Exception e) {
      Log.w(LOGTAG, "fromJson failed: " + e.getMessage());
    }
    return cfg;
  }

  /** Unknown / missing strategy -> backoff (the page's default). */
  private static String normalizeStrategy(JSONObject o, String key, String def) {
    String v = o.optString(key, def);
    return STRATEGY_FIXED.equals(v) ? STRATEGY_FIXED : STRATEGY_BACKOFF;
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
      o.put("diag", diag);
      o.put("thresholdM", thresholdM);
      o.put("moveThresholdM", moveThresholdM);
      o.put("strategy", strategy);
      o.put("intervalBaseMs", intervalBaseMs);
      o.put("intervalMaxMs", intervalMaxMs);
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
