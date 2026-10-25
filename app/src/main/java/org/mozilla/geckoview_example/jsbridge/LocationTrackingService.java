package org.mozilla.geckoview_example.jsbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * <p>Sampling strategy mirrors the page (locationAuto.ts, strategy in the pushed
 * config):</p>
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

  private static final String CHANNEL_ID = "location_tracking";
  private static final int NOTIFICATION_ID = 2002;
  private static final String FILE_NAME = "location_log.jsonl";

  /** Persist throttle for time-only refreshes (page FLUSH_MS = 15000). */
  private static final long FLUSH_MS = 15000;

  /** 定位采集 Toast 节流时间戳（测试内容）。 */
  private static volatile long sLastFixToastAt = 0;

  /** Accelerometer magnitude-change threshold to treat the phone as "moving". */
  private static final double SENSOR_MOVE_THRESHOLD = 0.4; // m/s^2 change
  /** How long a detected movement keeps the "moving" flag alive. */
  private static final long SENSOR_MOVE_WINDOW_MS = 1500;
  /** Location fix watchdog: if no fix arrives within interval+slack, retry. */
  private static final long FIX_WATCHDOG_SLACK_MS = 800;

  /** Exponent clamp for the backoff (2^16 * 1s already far beyond any cap). */
  private static final int MAX_BACKOFF_N = 16;

  /** Bridge-facing listener (set by LocationBridge) for events. */
  public interface Listener {
    void onPoint(JSONObject point);
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
      return o;
    } catch (Exception e) {
      return new JSONObject();
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

  /** Last appended point (dedupe baseline), mirrors page rows[last]. */
  @Nullable private JSONObject mLastPoint;
  private long mLastPersistTs = 0;
  private File mLogFile;
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
          // ignore
        }

        @Override
        public void onProviderDisabled(String provider) {
          // ignore
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    mProvider = pickProvider();
    mLogFile = new File(getFilesDir(), FILE_NAME);
    createNotificationChannel();
    sInstance = this;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopTracking();
      return START_NOT_STICKY;
    }
    // ACTION_START, or a system restart (intent == null, START_STICKY): resume.
    if (hasFineLocation()) {
      startTracking();
      return START_STICKY;
    }
    Log.w(LOGTAG, "no fine location permission, refusing to start");
    stopSelf();
    return START_NOT_STICKY;
  }

  private void startTracking() {
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

    restoreTailFromFile();
    sConfig = LocationConfig.load(this);
    sRunning = true;
    sStartedAt = System.currentTimeMillis();
    // 测试内容：原生定位服务启动时 Toast 提示。
    try {
      Toast.makeText(this, "原生定位服务正在记录位置", Toast.LENGTH_SHORT).show();
    } catch (Exception ignored) {
    }

    mSameCount = 0;
    mCurrentIntervalMs = sConfig.intervalBaseMs;
    mHandler.removeCallbacks(mTick);
    mHandler.postDelayed(mTick, 0);
    Log.i(LOGTAG, "tracking started, strategy=" + sConfig.strategy + " interval=" + mCurrentIntervalMs + "ms");
  }

  private void stopTracking() {
    if (mStopping) {
      return;
    }
    mStopping = true;
    sRunning = false;
    mHandler.removeCallbacks(mTick);
    mHandler.removeCallbacks(mFixWatchdog);
    mPendingFix = false;
    Log.i(LOGTAG, "tracking stopped, points=" + sPoints + " rev=" + sRev);
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  // ---------------------------------------------------------------------------
  // Collection loop
  // ---------------------------------------------------------------------------

  private void onTick() {
    if (!sRunning || mStopping || mPendingFix) {
      scheduleNext(mCurrentIntervalMs);
      return;
    }
    mPendingFix = true;
    try {
      mLocationManager.requestSingleUpdate(mProvider, mFixListener, Looper.getMainLooper());
    } catch (SecurityException | IllegalArgumentException e) {
      Log.e(LOGTAG, "requestSingleUpdate failed", e);
      mPendingFix = false;
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
      scheduleNext(nextInterval());
    }
  }

  private void onFix(Location location) {
    mPendingFix = false;
    mHandler.removeCallbacks(mFixWatchdog);
    if (!sRunning || mStopping) {
      return;
    }
    // 测试内容：每次取到定位时 Toast 提示（30s 节流，避免高频 1s 档刷屏）。
    long now = System.currentTimeMillis();
    if (now - sLastFixToastAt > 30000) {
      sLastFixToastAt = now;
      try {
        Toast.makeText(this, "原生定位服务取到定位点", Toast.LENGTH_SHORT).show();
      } catch (Exception ignored) {
      }
    }
    double lat = location.getLatitude();
    double lng = location.getLongitude();
    long ts = location.getTime();
    float accuracy = location.getAccuracy();

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
    scheduleNext(nextInterval());
  }

  /**
   * Next interval by the page's continuous-sampling strategy (locationAuto.ts
   * nextSampleMs): fixed = base; backoff = base * 2^n capped at max.
   */
  private long nextInterval() {
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
    if (sRunning) {
      sRunning = false;
      mHandler.removeCallbacks(mTick);
      mHandler.removeCallbacks(mFixWatchdog);
    }
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
    nm.createNotificationChannel(channel);
  }

  private Notification buildNotification() {
    Intent stopIntent = new Intent(this, LocationTrackingService.class).setAction(ACTION_STOP);
    PendingIntent stopPi =
        PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("正在记录位置")
        .setContentText("长期定位记录服务运行中")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
        .setOngoing(true)
        .build();
  }
}
