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
 * <p>采样不含任何策略：按页面给的 intervalBaseMs 固定间隔取一次定位，取到就存
 * 一条、就推一条 location.point 事件。不去重、不退避、不看位移。</p>
 *
 * <p>为什么不做：去重和退避归网页（locationAuto.ts 的 appendPoint / nextSampleMs）。
 * 壳里再实现一份，两边状态必然不同步——页面刷新会把自己退避计数清零，壳的服务
 * 跨刷新活着、计数不清零，于是「刷新前 1s 一个点，刷新后变成 8s 一个点」。
 * thresholdM / strategy / intervalMaxMs 照收（旧页面会推），但一概不使用。</p>
 *
 * <p>数据存 app 私有目录的 JSONL，一行一点：
 * {@code {"ts":...,"lat":...,"lng":...,"accuracy":...,"rev":N}}，每追加一条
 * rev+1。location.getHistory 支持可选 since（毫秒），只回比它新的点，
 * 免得跑久了每次回传全量把桥撑爆。</p>
 */
public class LocationTrackingService extends Service {
  private static final String LOGTAG = "LocationTrackingService";

  public static final String ACTION_START = "org.mozilla.geckoview_example.LOCATION_START";
  public static final String ACTION_STOP = "org.mozilla.geckoview_example.LOCATION_STOP";

  private static final String CHANNEL_ID = "location_tracking";
  private static final int NOTIFICATION_ID = 2002;
  private static final String FILE_NAME = "location_log.jsonl";

  /** 定位采集 Toast 节流时间戳（测试内容）。 */
  private static volatile long sLastFixToastAt = 0;

  /** Location fix watchdog: if no fix arrives within interval+slack, retry. */
  private static final long FIX_WATCHDOG_SLACK_MS = 800;

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

  /**
   * Recorded points for location.getHistory. sinceMs > 0 时只回比它新的点——
   * 网页把已合并到的时间戳传过来，跑久了也不会每次回传全量。
   */
  public static JSONObject historyJson(long sinceMs) {
    JSONObject o = new JSONObject();
    try {
      LocationTrackingService svc = sInstance;
      JSONArray points = svc != null ? svc.readAllPoints(sinceMs) : new JSONArray();
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

  private LocationManager mLocationManager;
  private String mProvider;

  /** Last appended point (only used for status / restart restore). */
  @Nullable private JSONObject mLastPoint;
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
      scheduleNext(sConfig.intervalBaseMs);
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
      scheduleNext(sConfig.intervalBaseMs);
      return;
    }

    // 取到就记一条：不去重、不合并，判断全交给网页
    addPoint(point);

    if (sListener != null) {
      try {
        sListener.onPoint(point);
      } catch (Exception e) {
        Log.w(LOGTAG, "listener onPoint failed", e);
      }
    }
    scheduleNext(sConfig.intervalBaseMs);
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
  // 记点：取到就存一条，不做任何判断
  // ---------------------------------------------------------------------------

  /** 追加一条点：rev+1、立即落盘。去重 / 顶替归网页的 appendPoint()，壳不插手。 */
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
    persistLine(point);
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

  /** Append one point line to the JSONL log. */
  private synchronized void persistLine(JSONObject point) {
    try (FileOutputStream fos = new FileOutputStream(mLogFile, true);
        OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
      writer.write(point.toString());
      writer.write("\n");
      writer.flush();
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

  /**
   * sinceMs > 0：只回 ts 比它新的点。文件按时间正序，所以从尾部往前扫，
   * 扫到不比 since 新的那一行就停——跑久了也不会每次解析全量。
   * 返回仍按时间正序（网页 appendPoint 依赖顺序）。
   */
  private JSONArray readAllPoints(long sinceMs) {
    List<String> lines = readLines();
    ArrayList<JSONObject> picked = new ArrayList<>();
    for (int i = lines.size() - 1; i >= 0; i--) {
      try {
        JSONObject p = new JSONObject(lines.get(i));
        if (sinceMs > 0 && p.optLong("ts", 0) <= sinceMs) {
          break;
        }
        picked.add(p);
      } catch (Exception ignored) {
      }
    }
    JSONArray arr = new JSONArray();
    for (int i = picked.size() - 1; i >= 0; i--) {
      arr.put(picked.get(i));
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
