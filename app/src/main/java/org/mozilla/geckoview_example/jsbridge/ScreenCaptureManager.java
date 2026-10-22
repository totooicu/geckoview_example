package org.mozilla.geckoview_example.jsbridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Screen capture bridge: obtains the MediaProjection permission via the activity,
 * starts/stops {@link ScreenCaptureService}, reports status and extracts a JPEG
 * thumbnail from the finished MP4 for in-page preview.
 *
 * <p>Actions: screen.start, screen.stop, screen.status, screen.thumbnail.
 */
public class ScreenCaptureManager {
  private static final String LOGTAG = "ScreenCaptureManager";
  public static final int REQ_PROJECTION = 3001;

  private final Activity mActivity;
  private final Context mAppContext;
  private final JsBridge mBridge;

  private String mPendingId = "";

  public ScreenCaptureManager(Activity activity, JsBridge bridge) {
    mActivity = activity;
    mAppContext = activity.getApplicationContext();
    mBridge = bridge;
    ScreenCaptureService.setListener(
        new ScreenCaptureService.Listener() {
          @Override
          public void onRecordingStarted(String path) {
            try {
              JSONObject d = new JSONObject();
              d.put("path", path);
              mBridge.emit("screen.recordingStarted", d);
            } catch (Exception e) {
              Log.e(LOGTAG, "started", e);
            }
          }

          @Override
          public void onRecordingStopped(String path, long sizeBytes) {
            try {
              JSONObject d = new JSONObject();
              d.put("path", path);
              d.put("size", sizeBytes);
              mBridge.emit("screen.recordingStopped", d);
            } catch (Exception e) {
              Log.e(LOGTAG, "stopped", e);
            }
          }

          @Override
          public void onRecordingError(String error) {
            mBridge.emitError("screen.recordingError", error);
          }
        });
  }

  public void handle(String id, String action, JSONObject params, JsBridge bridge) {
    switch (action) {
      case "screen.start":
        handleStart(id, params, bridge);
        break;
      case "screen.stop":
        stop();
        bridge.respond(id, true, null, null);
        break;
      case "screen.status":
        handleStatus(id, bridge);
        break;
      case "screen.thumbnail":
        handleThumbnail(id, params, bridge);
        break;
      default:
        bridge.respond(id, false, null, "unknown screen action: " + action);
    }
  }

  private void handleStart(String id, JSONObject params, JsBridge bridge) {
    if (ScreenCaptureService.isRunning()) {
      bridge.respond(id, false, null, "已在录屏中");
      return;
    }
    mPendingId = id;
    android.media.projection.MediaProjectionManager mp =
        (android.media.projection.MediaProjectionManager)
            mAppContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    Intent intent = mp.createScreenCaptureIntent();
    mActivity.startActivityForResult(intent, REQ_PROJECTION);
    // Response sent in onActivityResult.
  }

  /** Called from GeckoViewActivity.onActivityResult. */
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    if (requestCode != REQ_PROJECTION) {
      return;
    }
    if (resultCode != Activity.RESULT_OK || data == null) {
      mBridge.respond(mPendingId, false, null, "用户拒绝了录屏授权");
      mPendingId = "";
      return;
    }
    // API 34+ requires a foreground service of type mediaProjection.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      if (!ScreenCaptureService.isRunning()) {
        // The service will start its own FGS notification; the system asks the
        // user to confirm screen sharing again on Android 15+.
      }
    }
    Intent service = new Intent(mAppContext, ScreenCaptureService.class);
    service.setAction(ScreenCaptureService.ACTION_START);
    service.putExtra("resultCode", resultCode);
    service.putExtra("data", data);
    service.putExtra("name", "capture_" + System.currentTimeMillis() + ".mp4");
    mAppContext.startForegroundService(service);
    mBridge.respond(mPendingId, true, null, null);
    mPendingId = "";
  }

  private void stop() {
    Intent service = new Intent(mAppContext, ScreenCaptureService.class);
    service.setAction(ScreenCaptureService.ACTION_STOP);
    try {
      mAppContext.startService(service);
    } catch (Exception e) {
      Log.e(LOGTAG, "stop", e);
    }
  }

  private void handleStatus(String id, JsBridge bridge) {
    try {
      JSONObject r = new JSONObject();
      r.put("recording", ScreenCaptureService.isRunning());
      r.put("path", ScreenCaptureService.currentPath());
      bridge.respond(id, true, r, null);
    } catch (Exception e) {
      bridge.respond(id, false, null, e.getMessage());
    }
  }

  private void handleThumbnail(String id, JSONObject params, JsBridge bridge) {
    String path = params.optString("path", "");
    long atMs = params.optLong("atMs", 1000);
    if (path.isEmpty()) {
      bridge.respond(id, false, null, "缺少 path");
      return;
    }
    try {
      MediaMetadataRetriever retriever = new MediaMetadataRetriever();
      try {
        retriever.setDataSource(path);
      } catch (Exception e) {
        // Public MediaStore path may not be readable directly; try content URI.
        retriever.setDataSource(mAppContext, android.net.Uri.parse(path));
      }
      android.graphics.Bitmap frame = retriever.getFrameAtTime(atMs * 1000);
      if (frame == null) {
        bridge.respond(id, false, null, "抽帧失败");
        return;
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
      byte[] jpeg = out.toByteArray();
      JSONObject r = new JSONObject();
      r.put("jpegBase64", Base64.encodeToString(jpeg, Base64.NO_WRAP));
      r.put("width", frame.getWidth());
      r.put("height", frame.getHeight());
      bridge.respond(id, true, r, null);
    } catch (Exception e) {
      Log.e(LOGTAG, "thumbnail", e);
      bridge.respond(id, false, null, "抽帧失败: " + e.getMessage());
    }
  }
}
