package org.mozilla.geckoview_example.jsbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that records the screen via MediaProjection and encodes H.264 into MP4.
 *
 * <p>MediaProjection authorization must be obtained by {@link ScreenCaptureManager} (activity)
 * before starting this service. On API 34+ this service runs with foregroundServiceType
 * "mediaProjection", which also triggers the system's extra screen-share confirmation.
 */
public class ScreenCaptureService extends Service {
  private static final String LOGTAG = "ScreenCaptureService";

  public static final String ACTION_START = "org.mozilla.geckoview_example.START_CAPTURE";
  public static final String ACTION_STOP = "org.mozilla.geckoview_example.STOP_CAPTURE";

  private static final String CHANNEL_ID = "screen_capture";
  private static final int NOTIFICATION_ID = 2001;
  private static final int BIT_RATE = 4_000_000;
  private static final int FRAME_RATE = 30;
  private static final int I_FRAME_INTERVAL = 1;

  public interface Listener {
    void onRecordingStarted(String path);

    void onRecordingStopped(String path, long sizeBytes);

    void onRecordingError(String error);
  }

  @Nullable private static Listener sListener;

  private static volatile boolean sRunning = false;
  private static volatile String sCurrentPath = "";
  private static volatile long sStartedAt = 0;

  public static boolean isRunning() {
    return sRunning;
  }

  public static String currentPath() {
    return sCurrentPath;
  }

  public static void setListener(@Nullable Listener listener) {
    sListener = listener;
  }

  private final AtomicBoolean mStopping = new AtomicBoolean(false);
  private HandlerThread mCodecThread;
  private Handler mCodecHandler;

  private MediaProjection mProjection;
  private MediaCodec mCodec;
  private MediaMuxer mMuxer;
  private android.view.Surface mInputSurface;
  private int mTrackIndex = -1;
  private boolean mMuxerStarted = false;
  private MediaProjectionManager mProjectionManager;

  /** MediaStore output handle (API 29+); null on API 26-28 (file path output). */
  @Nullable private Uri mOutUri;
  @Nullable private ParcelFileDescriptor mPfd;
  private String mOutPath = "";
  private String mDisplayName = "";
  private final Handler mMainHandler = new Handler(Looper.getMainLooper());

  @Override
  public void onCreate() {
    super.onCreate();
    mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    createNotificationChannel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    String action = intent.getAction();
    if (ACTION_STOP.equals(action)) {
      stopCapture();
      return START_NOT_STICKY;
    }
    if (ACTION_START.equals(action)) {
      int resultCode = intent.getIntExtra("resultCode", 0);
      Intent data;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        data = intent.getParcelableExtra("data", Intent.class);
      } else {
        data = intent.getParcelableExtra("data");
      }
      String name = intent.getStringExtra("name");
      // Defensive guard: any uncaught exception in a foreground service kills the
      // whole process ("三方应用异常"). Report and stop gracefully instead.
      try {
        startCapture(resultCode, data, name);
      } catch (Exception e) {
        Log.e(LOGTAG, "startCapture failed", e);
        notifyError("录屏服务异常: " + e.getMessage());
        stopSelf();
      }
    }
    return START_NOT_STICKY;
  }

  private void startCapture(int resultCode, Intent data, @Nullable String name) {
    if (sRunning || data == null) {
      stopSelf();
      return;
    }
    mStopping.set(false);
    mDisplayName = name != null && !name.isEmpty() ? name : "capture_" + System.currentTimeMillis() + ".mp4";

    // API 34+ (Android 14) rule: a mediaProjection foreground service must call
    // startForeground() with the mediaProjection type BEFORE getMediaProjection().
    // Reversed order or a 2-arg startForeground throws SecurityException.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
          NOTIFICATION_ID,
          buildNotification(),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    } else {
      startForeground(NOTIFICATION_ID, buildNotification());
    }

    if (!prepareOutput()) {
      notifyError("无法创建保存目标");
      stopSelf();
      return;
    }

    mProjection = mProjectionManager.getMediaProjection(resultCode, data);
    if (mProjection == null) {
      notifyError("MediaProjection 获取失败");
      stopSelf();
      return;
    }

    // API 34+ (Android 14): a MediaProjection.Callback MUST be registered before
    // createVirtualDisplay(), otherwise the system throws:
    // "Must register a callback before starting capture, to manage resources in
    // response to MediaProjection states."
    mProjection.registerCallback(
        new MediaProjection.Callback() {
          @Override
          public void onStop() {
            Log.i(LOGTAG, "MediaProjection stopped by system");
            // User revoked / system stopped the projection: tear down gracefully.
            if (!mStopping.get()) {
              stopCapture();
            }
          }
        },
        mMainHandler);

    mCodecThread = new HandlerThread("ScreenEncoder");
    mCodecThread.start();
    mCodecHandler = new Handler(mCodecThread.getLooper());
    mCodecHandler.post(() -> startEncoding());
  }

  /**
   * Create the MP4 output target:
   * <ul>
   *   <li>API 29+: public Movies via MediaStore (no storage permission needed,
   *       works under scoped storage with targetSdk 30+).</li>
   *   <li>API 26-28: app-private directory.</li>
   * </ul>
   */
  private boolean prepareOutput() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, mDisplayName);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
        mOutUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (mOutUri == null) {
          return false;
        }
        mPfd = getContentResolver().openFileDescriptor(mOutUri, "rw");
        if (mPfd == null) {
          return false;
        }
        mOutPath = mOutUri.toString();
        return true;
      }
      File dir = new File(getFilesDir(), "captures");
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
      mOutPath = new File(dir, mDisplayName).getAbsolutePath();
      return true;
    } catch (Exception e) {
      Log.e(LOGTAG, "prepareOutput", e);
      return false;
    }
  }

  private void startEncoding() {
    try {
      WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
      Display display = wm.getDefaultDisplay();
      android.graphics.Point size = new android.graphics.Point();
      display.getRealSize(size);
      int width = size.x;
      int height = size.y;
      // Cap width to keep encoding cost reasonable on large screens.
      if (width > 1280) {
        float scale = 1280f / width;
        width = 1280;
        height = (int) (height * scale);
      }
      int density = getResources().getDisplayMetrics().densityDpi;

      MediaFormat format =
          MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
      format.setInteger(
          MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
      format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

      mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
      mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      mInputSurface = mCodec.createInputSurface();
      mCodec.setCallback(
          new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
              // surface input: nothing to do
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
              ByteBuffer buffer = codec.getOutputBuffer(index);
              if (buffer == null) {
                codec.releaseOutputBuffer(index, false);
                return;
              }
              if (info.size > 0) {
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                if (!mMuxerStarted) {
                  Log.w(LOGTAG, "output before muxer start, dropped");
                } else if (mTrackIndex >= 0) {
                  mMuxer.writeSampleData(mTrackIndex, buffer, info);
                }
              }
              codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
              Log.e(LOGTAG, "codec error", e);
              notifyError("编码器错误: " + e.getMessage());
              stopSelf();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat fmt) {
              if (!mMuxerStarted) {
                try {
                  if (mPfd != null) {
                    mMuxer =
                        new MediaMuxer(
                            mPfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                  } else {
                    mMuxer = new MediaMuxer(mOutPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                  }
                  mTrackIndex = mMuxer.addTrack(fmt);
                  mMuxer.start();
                  mMuxerStarted = true;
                } catch (Exception e) {
                  Log.e(LOGTAG, "muxer start", e);
                  notifyError("MP4 初始化失败: " + e.getMessage());
                  stopSelf();
                }
              }
            }
          });
      mCodec.start();

      // NOTE: on API 34+ this service must run with foregroundServiceType=mediaProjection;
      // the system performs the extra screen-share confirmation itself, so the legacy
      // createVirtualDisplay overload is fine on all supported API levels.
      mProjection.createVirtualDisplay(
          "GeckoBridgeCapture",
          width,
          height,
          density,
          android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
          mInputSurface,
          null,
          null);

      sRunning = true;
      sCurrentPath = mOutPath;
      sStartedAt = System.currentTimeMillis();
      Log.i(LOGTAG, "recording started: " + mOutPath + " (" + width + "x" + height + ")");
      if (sListener != null) {
        sListener.onRecordingStarted(mOutPath);
      }
    } catch (Exception e) {
      Log.e(LOGTAG, "startEncoding", e);
      notifyError("录屏启动失败: " + e.getMessage());
      releaseResources();
      stopSelf();
    }
  }

  private void stopCapture() {
    if (mStopping.getAndSet(true)) {
      return;
    }
    if (mCodecHandler != null) {
      mCodecHandler.post(this::finishEncoding);
    } else {
      stopSelf();
    }
  }

  private void finishEncoding() {
    // NOTE: stopCapture() already set mStopping=true and guarantees this runs
    // exactly once; do NOT re-check here or the teardown will be skipped.
    try {
      if (mProjection != null) {
        mProjection.stop();
      }
      if (mCodec != null) {
        try {
          mCodec.signalEndOfInputStream();
          // Drain remaining buffers synchronously is tricky with callback mode;
          // a short wait ensures the last frames land in the muxer.
          Thread.sleep(200);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
      }
      if (mMuxer != null && mMuxerStarted) {
        mMuxer.stop();
        mMuxer.release();
      }
      if (mCodec != null) {
        mCodec.stop();
        mCodec.release();
      }
      if (mInputSurface != null) {
        mInputSurface.release();
      }

      long size = 0;
      if (mPfd != null) {
        long s = mPfd.getStatSize();
        size = s > 0 ? s : 0;
      } else {
        size = new File(mOutPath).length();
      }
      sRunning = false;
      sCurrentPath = mOutPath;
      Log.i(LOGTAG, "recording finished: " + mOutPath + " " + size + "B");

      if (sListener != null) {
        sListener.onRecordingStopped(mOutPath, size);
      }
    } catch (Exception e) {
      Log.e(LOGTAG, "finishEncoding", e);
      notifyError("结束录屏失败: " + e.getMessage());
    } finally {
      releaseResources();
      stopSelf();
    }
  }

  private void releaseResources() {
    try {
      if (mProjection != null) {
        mProjection.stop();
        mProjection = null;
      }
    } catch (Exception ignored) {
    }
    try {
      if (mPfd != null) {
        mPfd.close();
        mPfd = null;
      }
    } catch (Exception ignored) {
    }
    mOutUri = null;
    if (mCodecThread != null) {
      mCodecThread.quitSafely();
      mCodecThread = null;
    }
    mCodec = null;
    mInputSurface = null;
    mMuxer = null;
    mTrackIndex = -1;
    mMuxerStarted = false;
  }

  private void notifyError(String error) {
    sRunning = false;
    Log.e(LOGTAG, error);
    if (sListener != null) {
      sListener.onRecordingError(error);
    }
  }

  @Override
  public void onDestroy() {
    if (sRunning) {
      finishEncoding();
    }
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  /* ----------------------------------------------------------------------------
   * Notification
   * -------------------------------------------------------------------------- */

  private void createNotificationChannel() {
    NotificationManager nm = getSystemService(NotificationManager.class);
    NotificationChannel channel =
        new NotificationChannel(CHANNEL_ID, "屏幕录制", NotificationManager.IMPORTANCE_LOW);
    nm.createNotificationChannel(channel);
  }

  private Notification buildNotification() {
    Intent stopIntent = new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP);
    PendingIntent stopPi =
        PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("正在录屏")
        .setContentText("GeckoBridge 正在录制屏幕…")
        .setSmallIcon(android.R.drawable.presence_video_online)
        .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
        .setOngoing(true)
        .build();
  }
}
