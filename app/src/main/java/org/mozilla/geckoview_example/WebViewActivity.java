package org.mozilla.geckoview_example;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.mozilla.geckoview_example.jsbridge.JsBridge;

import java.util.HashMap;
import java.util.Map;

/**
 * WebView shell hosting the test page and the injected {@code AndroidBridge}.
 *
 * <p>Transport: the page talks to native Android through the same postMessage
 * protocol as before; an adapter script (injected at page start) forwards page
 * requests to {@code window.AndroidBridge.invoke(...)} and delivers replies back
 * via {@code window.__gvBridgeCallback(...)}. {@link JsBridge} owns the routing
 * to the BLE / file / screen-capture modules and all runtime permission flows.</p>
 */
public class WebViewActivity extends AppCompatActivity {
  private static final String LOGTAG = "WebViewActivity";

  public static final String DEFAULT_URL =
      "https://totooicu.github.io/DatingRecord/";

  /** Only this host may be loaded; any other link is blocked (no external nav). */
  public static final String ALLOWED_HOST = "totooicu.github.io";

  /** Pending WebRTC PermissionRequest awaiting a runtime permission grant. */
  private static final Map<Integer, PermissionRequest> sPendingMediaPerms = new HashMap<>();
  private static final Map<Integer, GeolocationPermissions.Callback> sPendingGeoPerms =
      new HashMap<>();
  private static final Map<Integer, String> sPendingGeoOrigins = new HashMap<>();
  private static int sPermSeq = 3001;

  private static final int PERM_MEDIA_BASE = 2001;
  private static final int PERM_GEO = 2002;
  private static final int REQ_FILE_CHOOSER = 4001;

  private WebView mWebView;
  private JsBridge mJsBridge;
  /** Pending file-chooser callback from <input type="file">. */
  @Nullable private ValueCallback<Uri[]> mFilePathCallback;

  /**
   * Adapter script: registers the page-facing callback and forwards the page's
   * postMessage requests into the injected AndroidBridge object. Idempotent.
   */
  private static final String ADAPTER_JS =
      "(function(){"
          + "if (window.__gvBridgeAdapter) return;"
          + "window.__gvBridgeAdapter = true;"
          + "var doc = document.documentElement;"
          + "try { doc.setAttribute('data-gv-bridge-injected','true');"
          + " doc.setAttribute('data-gv-bridge-stage','adapter-ready'); } catch(e){}"
          + "window.__gvBridgeCallback = function(msg){"
          + "  try {"
          + "    if (doc) doc.setAttribute('data-gv-bridge-native',"
          + "      JSON.stringify({mode:'jsi', ok: !!(msg && msg.ok), id: msg && msg.id}));"
          + "    window.postMessage(msg, '*');"
          + "  } catch(e){}"
          + "};"
          + "window.addEventListener('message', function(e){"
          + "  var m = e.data;"
          + "  if (!m || m.type !== 'gv-bridge-request') return;"
          + "  try { if (doc) doc.setAttribute('data-gv-bridge-stage','adapter-received:' + m.action); } catch(e2){}"
          + "  if (window.AndroidBridge) {"
          + "    try { window.AndroidBridge.invoke(String(m.id), String(m.action), JSON.stringify(m.params || {})); }"
          + "    catch(err){ window.__gvBridgeCallback({type:'gv-bridge-response', id:m.id, ok:false, error:'adapter:' + err}); }"
          + "  } else {"
          + "    window.__gvBridgeCallback({type:'gv-bridge-response', id:m.id, ok:false, error:'AndroidBridge missing'});"
          + "  }"
          + "});"
          + "})();";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mJsBridge = new JsBridge(this);
    buildUi();

    WebSettings s = mWebView.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    // Identify this custom browser to the web page via a distinct User-Agent
    // marker (the page can also call AndroidBridge.getEnv()).
    s.setUserAgentString(
        s.getUserAgentString()
            + " DatingRecord/" + org.mozilla.geckoview_example.BuildConfig.VERSION_NAME);
    // Mixed content: our own pages are https; keep default (blocked) for safety.

    mWebView.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            injectAdapter(view);
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            injectAdapter(view);
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Lock navigation to our own host: only totooicu.github.io may load,
            // everything else (external links, other schemes) is blocked.
            Uri u = request.getUrl();
            String host = u != null ? u.getHost() : null;
            if (host != null && ALLOWED_HOST.equals(host)) {
              return false; // allow in-site navigation
            }
            Log.i(LOGTAG, "blocked external navigation to: " + (u != null ? u.toString() : "?"));
            return true; // block
          }
        });

    mWebView.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onPermissionRequest(PermissionRequest request) {
            handlePermissionRequest(request);
          }

          @Override
          public void onGeolocationPermissionsShowPrompt(
              String origin, GeolocationPermissions.Callback callback) {
            handleGeolocation(origin, callback);
          }

          @Override
          public boolean onShowFileChooser(
              WebView webView,
              ValueCallback<Uri[]> filePathCallback,
              FileChooserParams fileChooserParams) {
            // Cancel any pending callback first.
            if (mFilePathCallback != null) {
              mFilePathCallback.onReceiveValue(null);
              mFilePathCallback = null;
            }
            mFilePathCallback = filePathCallback;
            Intent intent;
            try {
              intent = fileChooserParams.createIntent();
            } catch (ActivityNotFoundException e) {
              mFilePathCallback = null;
              return false;
            }
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
              intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
              startActivityForResult(intent, REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
              mFilePathCallback = null;
              Toast.makeText(WebViewActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
              return false;
            }
            return true;
          }

          @Override
          public boolean onConsoleMessage(
              android.webkit.ConsoleMessage consoleMessage) {
            Log.d(
                LOGTAG,
                "[console:" + consoleMessage.messageLevel() + "] " + consoleMessage.message());
            return true;
          }
        });

    mWebView.addJavascriptInterface(mJsBridge, "AndroidBridge");
    mJsBridge.attach(mWebView);

    loadUrl(DEFAULT_URL);
  }

  /* ----------------------------------------------------------------------------
   * UI
   * -------------------------------------------------------------------------- */

  private void buildUi() {
    // Fullscreen shell with a top spacer matching the old address-bar height
    // (6dp padding + 42dp control + 6dp padding = 54dp) so content is not
    // hidden behind the status bar.
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);

    android.view.View spacer = new android.view.View(this);
    spacer.setBackgroundColor(Color.parseColor("#f3f4f6"));
    root.addView(
        spacer,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

    mWebView = new WebView(this);
    mWebView.setBackgroundColor(Color.WHITE);
    root.addView(
        mWebView,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    setContentView(root);
  }

  private void loadUrl(String url) {
    Uri u = Uri.parse(url);
    String host = u.getHost();
    if (host == null || !ALLOWED_HOST.equals(host)) {
      Log.w(LOGTAG, "loadUrl blocked (outside allowed host): " + url);
      return;
    }
    mWebView.loadUrl(url);
  }

  /** Inject the adapter script; safe to call repeatedly. */
  private void injectAdapter(WebView view) {
    view.evaluateJavascript(ADAPTER_JS, null);
  }

  /* ----------------------------------------------------------------------------
   * Web permissions (getUserMedia / geolocation)
   * -------------------------------------------------------------------------- */

  private void handlePermissionRequest(PermissionRequest request) {
    boolean needsAudio = false;
    boolean needsCamera = false;
    for (String r : request.getResources()) {
      if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
        needsAudio = true;
      } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
        needsCamera = true;
      }
    }
    // Deny anything not involving capture (notifications, protected media, ...).
    if (!needsAudio && !needsCamera) {
      request.deny();
      return;
    }
    final String[] missing = new String[2];
    int n = 0;
    if (needsAudio
        && ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
      missing[n++] = Manifest.permission.RECORD_AUDIO;
    }
    if (needsCamera
        && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
      missing[n++] = Manifest.permission.CAMERA;
    }
    if (n == 0) {
      request.grant(request.getResources());
      return;
    }
    String[] perms = new String[n];
    System.arraycopy(missing, 0, perms, 0, n);
    int code = PERM_MEDIA_BASE + (sPermSeq++ % 100);
    sPendingMediaPerms.put(code, request);
    ActivityCompat.requestPermissions(this, perms, code);
  }

  private void handleGeolocation(String origin, GeolocationPermissions.Callback callback) {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED) {
      callback.invoke(origin, true, false);
      return;
    }
    int code = PERM_GEO + (sPermSeq++ % 100);
    sPendingGeoPerms.put(code, callback);
    sPendingGeoOrigins.put(code, origin);
    ActivityCompat.requestPermissions(
        this, new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, code);
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    boolean granted = grantResults.length > 0;
    for (int r : grantResults) {
      if (r != PackageManager.PERMISSION_GRANTED) {
        granted = false;
        break;
      }
    }

    // WebRTC capture permission.
    PermissionRequest req = sPendingMediaPerms.remove(requestCode);
    if (req != null) {
      if (granted) {
        req.grant(req.getResources());
      } else {
        req.deny();
        Toast.makeText(this, "麦克风/摄像头权限被拒绝", Toast.LENGTH_SHORT).show();
      }
    }

    // Geolocation permission.
    GeolocationPermissions.Callback geo = sPendingGeoPerms.remove(requestCode);
    String origin = sPendingGeoOrigins.remove(requestCode);
    if (geo != null) {
      geo.invoke(origin == null ? "" : origin, granted, false);
    }

    // Bridge modules (BLE / file / screen).
    JsBridge.onPermissionsResult(requestCode, grantResults);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    // File chooser result for <input type="file">.
    if (requestCode == REQ_FILE_CHOOSER) {
      if (mFilePathCallback != null) {
        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
          if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            results = new Uri[count];
            for (int i = 0; i < count; i++) {
              results[i] = data.getClipData().getItemAt(i).getUri();
            }
          } else if (data.getDataString() != null) {
            results = new Uri[] {Uri.parse(data.getDataString())};
          }
        }
        mFilePathCallback.onReceiveValue(results);
        mFilePathCallback = null;
      }
      return;
    }

    mJsBridge.onActivityResult(requestCode, resultCode, data);
  }

  @Override
  public void onDestroy() {
    mJsBridge.onDestroy();
    if (mWebView != null) {
      mWebView.destroy();
      mWebView = null;
    }
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (mWebView != null && mWebView.canGoBack()) {
      mWebView.goBack();
      return;
    }
    super.onBackPressed();
  }

  private int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
  }
}
