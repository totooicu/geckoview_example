package org.mozilla.geckoview_example.jsbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between the web page and native Android modules, injected into the
 * WebView as the JS object {@code AndroidBridge}.
 *
 * <p>Page protocol (unchanged from the GeckoView bridge, JSON over
 * {@code window.postMessage}):</p>
 * <ul>
 *   <li>page -&gt; native: {"type":"gv-bridge-request","id":"...","action":"...","params":{...}}</li>
 *   <li>native -&gt; page (reply): {"type":"gv-bridge-response","id":"...","ok":true,"result":{...}}</li>
 *   <li>native -&gt; page (event): {"type":"gv-bridge-event","event":"ble.deviceFound","data":{...}}</li>
 * </ul>
 *
 * <p>Transport: an injected adapter script listens for the page's postMessage,
 * calls {@link #invoke(String, String, String)} on the injected object, and the
 * reply is delivered back through {@code window.__gvBridgeCallback(...)} which the
 * adapter forwards to the page with postMessage. The test page needs no changes.</p>
 */
public class JsBridge {
  private static final String LOGTAG = "JsBridge";

  /** Permission request codes. */
  public static final int PERM_BLE = 1001;
  public static final int PERM_FILE = 1002;
  public static final int PERM_LOCATION = 1003;

  private final WeakReference<Activity> mActivity;
  private final Handler mMainHandler = new Handler(Looper.getMainLooper());

  /** The WebView used to push replies/events back to the page. */
  @Nullable private WebView mWebView;

  private BleManager mBle;
  private FileBridge mFiles;
  private ScreenCaptureManager mScreen;
  private LocationBridge mLocation;

  public JsBridge(Activity activity) {
    mActivity = new WeakReference<>(activity);
    mBle = new BleManager(activity, this);
    mFiles = new FileBridge(activity, this);
    mScreen = new ScreenCaptureManager(activity, this);
    mLocation = new LocationBridge(activity, this);
  }

  /** Called by the hosting activity once the WebView exists. */
  public void attach(WebView webView) {
    mWebView = webView;
  }

  /**
   * Optional callback invoked when the page handles a back press itself
   * (ui.finish / ui.goBack), so the activity can cancel its back timeout.
   */
  @Nullable private Runnable mUiActionHandled;

  public void setUiActionHandled(Runnable r) {
    mUiActionHandled = r;
  }

  /* ----------------------------------------------------------------------------
   * JS interface (injected as window.AndroidBridge)
   * -------------------------------------------------------------------------- */

  /** Entry point called by the injected adapter script for each page request. */
  @JavascriptInterface
  public void invoke(String id, String action, String paramsJson) {
    Log.i(LOGTAG, "invoke: action=" + action + " id=" + id);
    try {
      JSONObject params =
          (paramsJson == null || paramsJson.isEmpty())
              ? new JSONObject()
              : new JSONObject(paramsJson);
      dispatch(id, action, params);
    } catch (Exception e) {
      Log.e(LOGTAG, "invoke dispatch failed for " + action, e);
      respond(id, false, null, "bridge: " + e.getMessage());
    }
  }

  /** Diagnostics helper exposed to the page. */
  @JavascriptInterface
  public boolean isReady() {
    return true;
  }

  /**
   * Environment identity so the page can tell it is running inside this custom
   * browser (rather than Chrome / a normal browser) and pick the right API.
   * The page checks: window.AndroidBridge && AndroidBridge.getEnv().
   */
  @JavascriptInterface
  public String getEnv() {
    try {
      JSONObject o = new JSONObject();
      o.put("app", "朝汇夕思");
      o.put("version", org.mozilla.geckoview_example.BuildConfig.VERSION_NAME);
      o.put("platform", "android");
      o.put("isApp", true);
      o.put("bridge", "jsi-v1");
      return o.toString();
    } catch (Exception e) {
      return "{}";
    }
  }

  /* ----------------------------------------------------------------------------
   * Routing (same action space as the old WebExtension bridge)
   * -------------------------------------------------------------------------- */

  private void dispatch(String id, String action, JSONObject params) throws Exception {
    if ("ping".equals(action)) {
      JSONObject r = new JSONObject();
      r.put("pong", true);
      r.put("engine", "webview");
      respond(id, true, r, null);
    } else if (action.startsWith("ble.")) {
      mBle.handle(id, action, params, this);
    } else if (action.startsWith("file.")) {
      mFiles.handle(id, action, params, this);
    } else if (action.startsWith("screen.")) {
      mScreen.handle(id, action, params, this);
    } else if (action.startsWith("location.")) {
      mLocation.handle(id, action, params, this);
    } else if (action.startsWith("ui.")) {
      handleUi(id, action, params);
    } else {
      respond(id, false, null, "unknown action: " + action);
    }
  }

  /** UI control actions requested by the page (back handling handoff). */
  private void handleUi(String id, String action, JSONObject params) {
    switch (action) {
      case "ui.finish":
        // Page decided to leave: cancel the back timeout, then finish the activity.
        if (mUiActionHandled != null) {
          mUiActionHandled.run();
        }
        Activity a = mActivity.get();
        if (a != null) {
          a.finish();
          respond(id, true, null, null);
        } else {
          respond(id, false, null, "activity gone");
        }
        break;
      case "ui.goBack":
        // Page asked the shell to navigate back: cancel timeout, goBack if possible.
        if (mUiActionHandled != null) {
          mUiActionHandled.run();
        }
        WebView wv = mWebView;
        if (wv != null && wv.canGoBack()) {
          wv.goBack();
          respond(id, true, null, null);
        } else {
          respond(id, true, null, null);
        }
        break;
      default:
        respond(id, false, null, "unknown ui action: " + action);
    }
  }

  /* ----------------------------------------------------------------------------
   * Reply / event helpers
   * -------------------------------------------------------------------------- */

  /** Send a success reply to the page through the injected adapter callback. */
  public void respond(String id, boolean ok, @Nullable JSONObject result, @Nullable String error) {
    mMainHandler.post(
        () -> {
          try {
            JSONObject msg = new JSONObject();
            msg.put("type", "gv-bridge-response");
            msg.put("id", id);
            msg.put("ok", ok);
            if (result != null) {
              msg.put("result", result);
            }
            if (error != null) {
              msg.put("error", error);
            }
            sendToPage(msg);
          } catch (Exception e) {
            Log.e(LOGTAG, "respond failed", e);
          }
        });
  }

  /** Push an asynchronous event to the page (e.g. ble.deviceFound). */
  public void emit(String event, @Nullable JSONObject data) {
    mMainHandler.post(
        () -> {
          try {
            JSONObject msg = new JSONObject();
            msg.put("type", "gv-bridge-event");
            msg.put("event", event);
            if (data != null) {
              msg.put("data", data);
            }
            sendToPage(msg);
          } catch (Exception e) {
            Log.e(LOGTAG, "emit failed", e);
          }
        });
  }

  public void emitError(String event, String error) {
    try {
      JSONObject data = new JSONObject();
      data.put("error", error);
      emit(event, data);
    } catch (Exception e) {
      Log.e(LOGTAG, "emitError failed", e);
    }
  }

  /**
   * Deliver a JSON message to the page. A JSON object is a legal JavaScript
   * object literal, so we can pass it straight into the injected callback.
   */
  private void sendToPage(JSONObject msg) {
    final WebView wv = mWebView;
    if (wv == null) {
      Log.w(LOGTAG, "sendToPage: WebView not attached");
      return;
    }
    wv.evaluateJavascript(
        "window.__gvBridgeCallback && window.__gvBridgeCallback(" + msg + ")", null);
  }

  /* ----------------------------------------------------------------------------
   * Permission helpers (unchanged semantics)
   * -------------------------------------------------------------------------- */

  public interface PermissionCallback {
    void onGranted();

    default void onDenied() {}
  }

  private static final Map<Integer, PermissionCallback> sPermissionCallbacks = new HashMap<>();

  /** Request runtime permissions from the hosting activity; result handled by the activity. */
  public static void requestPermissions(
      Activity activity, int code, String[] permissions, PermissionCallback cb) {
    boolean allGranted = true;
    for (String p : permissions) {
      if (ActivityCompat.checkSelfPermission(activity, p) != PackageManager.PERMISSION_GRANTED) {
        allGranted = false;
        break;
      }
    }
    if (allGranted) {
      cb.onGranted();
      return;
    }
    sPermissionCallbacks.put(code, cb);
    ActivityCompat.requestPermissions(activity, permissions, code);
  }

  /** Called from WebViewActivity.onRequestPermissionsResult. */
  public static void onPermissionsResult(int requestCode, @NonNull int[] grantResults) {
    PermissionCallback cb = sPermissionCallbacks.remove(requestCode);
    if (cb == null) {
      return;
    }
    boolean granted = grantResults.length > 0;
    for (int r : grantResults) {
      if (r != PackageManager.PERMISSION_GRANTED) {
        granted = false;
        break;
      }
    }
    if (granted) {
      cb.onGranted();
    } else {
      cb.onDenied();
    }
  }

  public static boolean hasPermission(Context ctx, String permission) {
    return ActivityCompat.checkSelfPermission(ctx, permission)
        == PackageManager.PERMISSION_GRANTED;
  }

  /** Runtime Bluetooth permissions for this build (API 31+) or empty array. */
  public static String[] blePermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return new String[] {
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
      };
    }
    return new String[0];
  }

  /* ----------------------------------------------------------------------------
   * Module accessors
   * -------------------------------------------------------------------------- */

  public BleManager ble() {
    return mBle;
  }

  public FileBridge files() {
    return mFiles;
  }

  public ScreenCaptureManager screen() {
    return mScreen;
  }

  public LocationBridge location() {
    return mLocation;
  }

  public Activity activity() {
    return mActivity.get();
  }

  /** Notify the bridge that the activity was destroyed. */
  public void onDestroy() {
    if (mBle != null) {
      mBle.onDestroy();
    }
    mWebView = null;
  }

  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    if (mScreen != null) {
      mScreen.onActivityResult(requestCode, resultCode, data);
    }
  }
}
