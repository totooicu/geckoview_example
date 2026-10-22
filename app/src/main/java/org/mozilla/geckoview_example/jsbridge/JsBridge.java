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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;
import org.mozilla.geckoview_example.GeckoViewActivity;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between the injected WebExtension content script and native Android modules.
 *
 * <p>Message protocol (JSON):</p>
 * <ul>
 *   <li>page -&gt; native: {"id": "...", "action": "ble.startScan", "params": {...}}</li>
 *   <li>native -&gt; page (reply): {"id": "...", "ok": true, "result": {...}} or
 *       {"id": "...", "ok": false, "error": "..."}</li>
 *   <li>native -&gt; page (event): {"event": "ble.deviceFound", "data": {...}}</li>
 * </ul>
 */
public class JsBridge implements WebExtension.MessageDelegate, WebExtension.PortDelegate {
  private static final String LOGTAG = "JsBridge";
  private static final String NATIVE_APP = "bridge";
  private static final String EXTENSION_URI = "resource://android/assets/webextension.xpi";

  /** Permission request codes. */
  public static final int PERM_BLE = 1001;
  public static final int PERM_FILE = 1002;

  private final WeakReference<GeckoViewActivity> mActivity;
  private final Handler mMainHandler = new Handler(Looper.getMainLooper());

  @Nullable private WebExtension.Port mPort;
  @Nullable private WebExtension mExtension;

  private BleManager mBle;
  private FileBridge mFiles;
  private ScreenCaptureManager mScreen;

  public JsBridge(GeckoViewActivity activity) {
    mActivity = new WeakReference<>(activity);
    mBle = new BleManager(activity, this);
    mFiles = new FileBridge(activity, this);
    mScreen = new ScreenCaptureManager(activity, this);
  }

  /* ----------------------------------------------------------------------------
   * Install & connect
   * -------------------------------------------------------------------------- */

  public void install(GeckoViewActivity activity) {
    final WebExtensionController controller = activity.getGeckoRuntime().getWebExtensionController();
    controller
        .list()
        .accept(
            installed -> {
              // 先卸载所有旧的同名实例（可能残留 MV3 坏实例或脏状态），再全新安装
              removeOld(controller, installed);
            },
            e -> installFresh(controller));
  }

  private void removeOld(WebExtensionController controller, java.util.List<WebExtension> installed) {
    removeAll(controller, installed, 0);
  }

  /** 递归卸载全部已装扩展（App 内只会存在本桥扩展），再全新安装避免脏状态。 */
  private void removeAll(
      WebExtensionController controller, java.util.List<WebExtension> installed, int index) {
    if (index >= installed.size()) {
      installFresh(controller);
      return;
    }
    controller.uninstall(installed.get(index)).accept(
        unused -> removeAll(controller, installed, index + 1),
        e -> removeAll(controller, installed, index + 1));
  }

  private void installFresh(WebExtensionController controller) {
    // 把 assets 里的 webextension.xpi 复制到私有目录，用 file:// 安装（等同桌面端"临时加载扩展"，
    // 未签名也可安装）；resource:// 与 installBuiltIn 在 nightly 160 均不可靠。
    final GeckoViewActivity a = mActivity.get();
    if (a == null) {
      return;
    }
    try {
      java.io.InputStream in = a.getAssets().open("webextension.xpi");
      java.io.File out = new java.io.File(a.getFilesDir(), "webextension.xpi");
      try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
          fos.write(buf, 0, n);
        }
      }
      in.close();
      controller
          .install("file://" + out.getAbsolutePath())
          .accept(
              extension -> {
                mExtension = extension;
                extension.setMessageDelegate(this, NATIVE_APP);
                Log.i(LOGTAG, "Bridge extension installed: " + extension.id);
                toast("桥扩展已安装 v1.0.5");
              },
              e -> {
                Log.e(LOGTAG, "Bridge extension INSTALL FAILED: " + e.getMessage(), e);
                showInstallError(e);
              });
    } catch (Exception ex) {
      Log.e(LOGTAG, "copy xpi failed", ex);
      showInstallError(ex);
    }
  }

  /** 用 AlertDialog 显示完整错误，并把全文复制到剪贴板（正文可能显示不全/过淡）。 */
  private void showInstallError(final Throwable e) {
    final GeckoViewActivity a = mActivity.get();
    if (a == null) {
      return;
    }
    StringBuilder sb = new StringBuilder();
    Throwable t = e;
    int depth = 0;
    while (t != null && depth < 8) {
      if (sb.length() > 0) {
        sb.append("\n--- caused by ---\n");
      }
      String msg = t.getMessage();
      sb.append(t.getClass().getName())
          .append(": ")
          .append(msg == null || msg.isEmpty() ? "(no message)" : msg);
      t = t.getCause();
      depth++;
    }
    if (sb.length() == 0) {
      sb.append("(empty error)");
    }
    final String text = sb.toString();
    mMainHandler.post(
        () -> {
          try {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager)
                    a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
              cm.setPrimaryClip(
                  android.content.ClipData.newPlainText("gvbridge_error", text));
            }
          } catch (Exception ignored) {
          }
          new android.app.AlertDialog.Builder(a)
              .setTitle("桥扩展安装失败 v1.0.5（完整错误）")
              .setMessage(text + "\n\n[错误全文已复制到剪贴板，直接粘贴即可]")
              .setPositiveButton("知道了", null)
              .show();
        });
  }

  private void toast(final String text) {
    final GeckoViewActivity a = mActivity.get();
    if (a == null) {
      return;
    }
    mMainHandler.post(() -> android.widget.Toast.makeText(a, text, android.widget.Toast.LENGTH_LONG).show());
  }

  @Override
  public void onConnect(WebExtension.Port port) {
    Log.i(LOGTAG, "Bridge port connected");
    mPort = port;
    port.setDelegate(this);
    toast("原生桥已连接");
  }

  @Override
  public void onPortMessage(Object message, WebExtension.Port port) {
    JSONObject json = (JSONObject) message;
    if (json == null) {
      return;
    }
    final String id = json.optString("id", "");
    final String action = json.optString("action", "");
    final JSONObject params = json.optJSONObject("params");

    Log.i(LOGTAG, "bridge <- " + action);
    try {
      dispatch(id, action, params == null ? new JSONObject() : params);
    } catch (Exception e) {
      Log.e(LOGTAG, "dispatch failed for " + action, e);
      respond(id, false, null, "bridge: " + e.getMessage());
    }
  }

  @Override
  public void onDisconnect(WebExtension.Port port) {
    Log.i(LOGTAG, "Bridge port disconnected");
    if (mPort == port) {
      mPort = null;
    }
  }
  /* ----------------------------------------------------------------------------
   * Routing
   * -------------------------------------------------------------------------- */

  private void dispatch(String id, String action, JSONObject params) throws Exception {
    if ("ping".equals(action)) {
      JSONObject r = new JSONObject();
      r.put("pong", true);
      respond(id, true, r, null);
    } else if (action.startsWith("ble.")) {
      mBle.handle(id, action, params, this);
    } else if (action.startsWith("file.")) {
      mFiles.handle(id, action, params, this);
    } else if (action.startsWith("screen.")) {
      mScreen.handle(id, action, params, this);
    } else {
      respond(id, false, null, "unknown action: " + action);
    }
  }

  /* ----------------------------------------------------------------------------
   * Reply / event helpers
   * -------------------------------------------------------------------------- */

  /** Send a success reply to the page. */
  public void respond(String id, boolean ok, @Nullable JSONObject result, @Nullable String error) {
    mMainHandler.post(
        () -> {
          if (mPort == null) {
            return;
          }
          try {
            JSONObject msg = new JSONObject();
            msg.put("id", id);
            msg.put("ok", ok);
            if (result != null) {
              msg.put("result", result);
            }
            if (error != null) {
              msg.put("error", error);
            }
            mPort.postMessage(msg);
          } catch (Exception e) {
            Log.e(LOGTAG, "respond failed", e);
          }
        });
  }

  /** Push an asynchronous event to the page. */
  public void emit(String event, @Nullable JSONObject data) {
    mMainHandler.post(
        () -> {
          if (mPort == null) {
            return;
          }
          try {
            JSONObject msg = new JSONObject();
            msg.put("event", event);
            if (data != null) {
              msg.put("data", data);
            }
            mPort.postMessage(msg);
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

  /* ----------------------------------------------------------------------------
   * Permission helpers
   * -------------------------------------------------------------------------- */

  public interface PermissionCallback {
    void onGranted();

    default void onDenied() {}
  }

  private static final Map<Integer, PermissionCallback> sPermissionCallbacks = new HashMap<>();

  /** Request runtime permissions from the hosting activity; result handled by the activity. */
  public static void requestPermissions(Activity activity, int code, String[] permissions, PermissionCallback cb) {
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

  /** Called from GeckoViewActivity.onRequestPermissionsResult. */
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

  public GeckoViewActivity activity() {
    return mActivity.get();
  }

  /** Notify the bridge that the activity was destroyed. */
  public void onDestroy() {
    if (mBle != null) {
      mBle.onDestroy();
    }
    mPort = null;
  }

  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    if (mScreen != null) {
      mScreen.onActivityResult(requestCode, resultCode, data);
    }
  }
}
