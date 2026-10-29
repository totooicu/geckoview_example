package org.mozilla.geckoview_example;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
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

  /** 壳固定加载的网页根路径（跟随网页仓库 main 最新部署）。 */
  public static final String DEFAULT_URL =
      "https://totooicu.github.io/DatingRecord/";

  /** Only this host may be loaded; any other link is blocked (no external nav). */
  public static final String ALLOWED_HOST = "totooicu.github.io";

  /**
   * 调试页入口：`adb shell am start -n org.mozilla.geckoview_example/.WebViewActivity
   * --es page nutstore-test.html`。
   *
   * <p>只认下面这张白名单里的文件名——它们都是打进 APK 的 asset，不接受任意路径、
   * 不接受 URL、不接受外部内容。域名锁管的是「网上能加载谁」，这条管的是「壳自己带了几页」，
   * 两回事，但同样不许放宽成任意 file://。</p>
   */
  public static final String EXTRA_PAGE = "page";

  private static final String[] DEBUG_PAGES = {"test.html", "nutstore-test.html"};

  private static final String ASSET_PREFIX = "file:///android_asset/";

  /** Pending WebRTC PermissionRequest awaiting a runtime permission grant. */
  private static final Map<Integer, PermissionRequest> sPendingMediaPerms = new HashMap<>();
  private static final Map<Integer, GeolocationPermissions.Callback> sPendingGeoPerms =
      new HashMap<>();
  private static final Map<Integer, String> sPendingGeoOrigins = new HashMap<>();
  private static int sPermSeq = 3001;

  private static final int PERM_MEDIA_BASE = 2100;
  private static final int PERM_GEO = 2200;
  private static final int REQ_FILE_CHOOSER = 4001;

  /** 网页定位 Toast 节流时间戳（测试内容）。 */
  private static long sLastGeoToastAt = 0;

  private WebView mWebView;
  private JsBridge mJsBridge;
  /** Pending file-chooser callback from <input type="file">. */
  @Nullable private ValueCallback<Uri[]> mFilePathCallback;

  /** True once the current page finished loading (bridge handoff is safe). */
  private boolean mPageReady = false;
  /** True while waiting for the page to answer a forwarded back press. */
  private boolean mBackWaitActive = false;
  /** How long the page may answer a forwarded back press before the shell falls back. */
  private static final long BACK_TIMEOUT_MS = 3000;
  private final Handler mMainHandler = new Handler(Looper.getMainLooper());
  private final Runnable mBackTimeout =
      () -> {
        if (mBackWaitActive) {
          mBackWaitActive = false;
          performDefaultBack();
        }
      };

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
            mPageReady = false;
            injectAdapter(view);
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            injectAdapter(view);
            pullLocationConfig(view);
            mPageReady = true;
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
            if (isDebugAsset(u)) {
              return false; // 白名单里的 asset 调试页之间可以互相跳
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

    // Forward the system back press to the page (ui.backPressed event) and let
    // the page decide (ui.finish / ui.goBack); fall back after a timeout so the
    // back button never becomes dead. Uses OnBackPressedCallback (API 33+ uses
    // the back dispatcher; the deprecated onBackPressed() is not called).
    mJsBridge.setUiActionHandled(
        () -> {
          mMainHandler.removeCallbacks(mBackTimeout);
          mBackWaitActive = false;
        });
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                onBackKey();
              }
            });

    openStartPage();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    // launchMode=singleTop：壳已经在跑时再 `am start --es page …` 走这里。
    // 没带 page 参数就什么都不做——正常拉起不该把用户当前页面刷掉。
    setIntent(intent);
    String page = intent != null ? intent.getStringExtra(EXTRA_PAGE) : null;
    if (page != null) {
      openDebugPage(page);
    }
  }

  /** 拉起时的入口：带 --es page 就开调试页，否则照旧加载线上首页。 */
  private void openStartPage() {
    String page = getIntent() != null ? getIntent().getStringExtra(EXTRA_PAGE) : null;
    if (page == null) {
      loadUrl(DEFAULT_URL);
      return;
    }
    openDebugPage(page);
  }

  private void openDebugPage(String page) {
    if (!isDebugPageName(page)) {
      Toast.makeText(this, "调试页不在白名单里：" + page, Toast.LENGTH_LONG).show();
      Log.w(LOGTAG, "debug page rejected: " + page);
      loadUrl(DEFAULT_URL);
      return;
    }
    Log.i(LOGTAG, "loading debug page: " + page);
    // file:///android_asset 不受 setAllowFileAccess(false) 影响（系统始终允许自己包里的资源），
    // 所以开这个入口不需要把任意 file:// 访问放开。
    mWebView.loadUrl(ASSET_PREFIX + page);
  }

  private static boolean isDebugPageName(String name) {
    for (String p : DEBUG_PAGES) {
      if (p.equals(name)) {
        return true;
      }
    }
    return false;
  }

  /** 判定「是不是白名单里的 asset 页」：协议必须是 file，路径必须是 /android_asset/<白名单>。 */
  private static boolean isDebugAsset(@Nullable Uri u) {
    if (u == null || !"file".equals(u.getScheme())) {
      return false;
    }
    String path = u.getPath();
    if (path == null || !path.startsWith("/android_asset/")) {
      return false;
    }
    return isDebugPageName(path.substring("/android_asset/".length()));
  }

  /** 长按顶部空白：不带 adb 也能在手机上选调试页（选「线上首页」回去）。 */
  private void showDebugPagePicker() {
    final String[] labels = new String[DEBUG_PAGES.length + 1];
    labels[0] = "线上首页（DatingRecord）";
    for (int i = 0; i < DEBUG_PAGES.length; i++) {
      labels[i + 1] = DEBUG_PAGES[i];
    }
    new AlertDialog.Builder(this)
        .setTitle("调试页")
        .setItems(labels, (d, which) -> {
          if (which == 0) {
            loadUrl(DEFAULT_URL);
          } else {
            openDebugPage(DEBUG_PAGES[which - 1]);
          }
        })
        .show();
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
    // 长按顶部这条空白 = 调试页选择器（不带 adb 也能在手机上打开壳自带的测试页）。
    // 它不在网页内容里，不会被页面脚本碰到，也不改变正常使用的观感。
    spacer.setLongClickable(true);
    spacer.setOnLongClickListener(
        v -> {
          showDebugPagePicker();
          return true;
        });
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

  /**
   * Pull the page's dr.autoLocation config out of browser storage once the page
   * has loaded, so native has the latest copy even if the page never calls
   * location.setConfig. The JS returns a wrapper object to avoid string-escaping
   * surprises from evaluateJavascript.
   */
  private void pullLocationConfig(WebView view) {
    view.evaluateJavascript(
        "(function(){try{var v=localStorage.getItem('dr.autoLocation');"
            + "return JSON.stringify({ok:true,v:v});}"
            + "catch(e){return JSON.stringify({ok:false});}})()",
        value -> {
          if (value == null || value.isEmpty() || "null".equals(value)) {
            return;
          }
          try {
            org.json.JSONObject wrapper = new org.json.JSONObject(value);
            if (!wrapper.optBoolean("ok", false)) {
              return;
            }
            String v = wrapper.optString("v", "");
            if (v.isEmpty()) {
              return;
            }
            org.mozilla.geckoview_example.jsbridge.LocationConfig cfg =
                org.mozilla.geckoview_example.jsbridge.LocationConfig.fromJson(v);
            mJsBridge.location().handle(
                "config-pull", "location.setConfig", new org.json.JSONObject(cfgToJson(cfg)), mJsBridge);
          } catch (Exception e) {
            Log.w(LOGTAG, "pullLocationConfig failed: " + e.getMessage());
          }
        });
  }

  /** Serialize a config back to JSON params for the bridge handler. */
  private String cfgToJson(org.mozilla.geckoview_example.jsbridge.LocationConfig cfg) {
    try {
      org.json.JSONObject o = new org.json.JSONObject();
      o.put("enabled", cfg.enabled);
      o.put("thresholdM", cfg.thresholdM);
      o.put("moveThresholdM", cfg.moveThresholdM);
      o.put("strategy", cfg.strategy);
      o.put("intervalBaseMs", cfg.intervalBaseMs);
      o.put("intervalMaxMs", cfg.intervalMaxMs);
      return o.toString();
    } catch (Exception e) {
      return "{}";
    }
  }

  /* ----------------------------------------------------------------------------
   * Web permissions (getUserMedia / geolocation)
   * -------------------------------------------------------------------------- */

  private void handlePermissionRequest(PermissionRequest request) {
    boolean needsAudio = false;
    boolean needsCamera = false;
    StringBuilder resources = new StringBuilder();
    for (String r : request.getResources()) {
      if (resources.length() > 0) {
        resources.append(",");
      }
      resources.append(r);
      if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
        needsAudio = true;
      } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
        needsCamera = true;
      }
    }
    Log.i(
        LOGTAG,
        "media permission: "
            + resources
            + " webview="
            + webViewVersion()
            + " recordAudio="
            + (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED));
    // Deny anything not involving capture (notifications, protected media, ...).
    if (!needsAudio && !needsCamera) {
      Log.w(LOGTAG, "unsupported permission resources: " + resources);
      Toast.makeText(this, "桥:未识别权限请求 " + resources, Toast.LENGTH_LONG).show();
      request.deny();
      return;
    }
    Toast.makeText(
            this,
            "桥:页面请求" + (needsAudio ? "麦克风" : "") + (needsCamera ? "摄像头" : "") + "权限",
            Toast.LENGTH_SHORT)
        .show();
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
      grantMediaWithSelfCheck(request);
      return;
    }
    String[] perms = new String[n];
    System.arraycopy(missing, 0, perms, 0, n);
    int code = PERM_MEDIA_BASE + (sPermSeq++ % 100);
    sPendingMediaPerms.put(code, request);
    ActivityCompat.requestPermissions(this, perms, code);
  }

  /** WebView 内核版本：Chrome 能用、壳里麦克风不行时，先排除内核太旧/被厂商替换 */
  private static String webViewVersion() {
    PackageInfo pi = WebView.getCurrentWebViewPackage();
    return pi == null ? "未知" : pi.packageName + "/" + pi.versionName;
  }

  /**
   * 放行网页的采集请求。授权弹窗期间请求可能已经失效（页面切走、重复调用），
   * grant 会抛 IllegalStateException——宁可提示一句，也不要让壳崩掉。
   */
  private void grantMedia(PermissionRequest req) {
    try {
      req.grant(req.getResources());
    } catch (RuntimeException e) {
      Log.w(LOGTAG, "grant media failed: " + e);
      Toast.makeText(this, "桥:放行失败 " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  /**
   * 放行前先做一次原生录音自检（只针对含麦克风的请求）：区分「系统没把麦克风交出来」
   * 与「WebView 内核的音频路径起不来」——前者能照着改设置解决，后者只能换内核。
   * 自检只录 0.2 秒且立刻释放，不持有麦克风。
   */
  private void grantMediaWithSelfCheck(PermissionRequest req) {
    boolean audio = false;
    for (String r : req.getResources()) {
      if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
        audio = true;
        break;
      }
    }
    if (!audio) {
      grantMedia(req);
      return;
    }
    new Thread(
            () -> {
              MicDiagnostics.Result res;
              try {
                res = MicDiagnostics.collect(this);
              } catch (Throwable e) {
                Log.w(LOGTAG, "mic self-check failed", e);
                grantMediaOnUi(req);
                return;
              }
              Log.i(LOGTAG, "mic self-check:\n" + res.report);
              runOnUiThread(
                  () -> {
                    grantMedia(req);
                    // 只有真出问题才弹窗（自检两路都没拿到数据 / AppOps 被关），
                    // 平时录音不该被一个诊断弹窗打断。
                    if (!res.ok || res.blocked) {
                      mMainHandler.postDelayed(() -> showMicReport(res.report, res.blocked), 900);
                    }
                  });
            },
            "mic-selfcheck")
        .start();
  }

  /** 后台线程里失败时也要把请求放行，不能把页面的 getUserMedia 挂死。 */
  private void grantMediaOnUi(PermissionRequest req) {
    runOnUiThread(() -> grantMedia(req));
  }

  /** 自检报告弹窗：可选中、可复制，AppOps 被关时给一条去系统设置的直路。 */
  private void showMicReport(String report, boolean blocked) {
    if (isFinishing() || isDestroyed()) {
      return;
    }
    StringBuilder msg = new StringBuilder(report);
    if (blocked) {
      msg.append("\n\n系统把本 App 的麦克风关掉了（AppOps=被关掉）：去「设置 → 应用 → ")
          .append(getString(R.string.app_name))
          .append(" → 权限 → 麦克风」打开，再回页面重试。");
    }
    TextView tv = new TextView(this);
    int pad = dp(16);
    tv.setPadding(pad, pad, pad, pad);
    tv.setTextSize(12);
    tv.setTextIsSelectable(true);
    tv.setTypeface(Typeface.MONOSPACE);
    tv.setText(msg.toString());
    ScrollView sv = new ScrollView(this);
    sv.addView(tv);

    AlertDialog.Builder b =
        new AlertDialog.Builder(this)
            .setTitle(blocked ? "麦克风被系统挡住了" : "麦克风自检")
            .setView(sv)
            .setPositiveButton("知道了", null)
            .setNeutralButton(
                "复制", (d, w) -> copyText("麦克风自检", report));
    if (blocked) {
      b.setNegativeButton("去设置", (d, w) -> openAppSettings());
    }
    b.show();
  }

  private void copyText(String label, String text) {
    try {
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null) {
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "已复制，可直接发出来", Toast.LENGTH_SHORT).show();
      }
    } catch (Throwable e) {
      Log.w(LOGTAG, "copy failed", e);
    }
  }

  private void openAppSettings() {
    try {
      startActivity(
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.fromParts("package", getPackageName(), null)));
    } catch (Throwable e) {
      Log.w(LOGTAG, "open app settings failed", e);
      Toast.makeText(this, "打不开系统设置页，请手动进设置", Toast.LENGTH_LONG).show();
    }
  }

  private void handleGeolocation(String origin, GeolocationPermissions.Callback callback) {
    // 测试内容：网页使用定位时 Toast 提示（10s 节流，避免刷屏）。
    long now = System.currentTimeMillis();
    if (now - sLastGeoToastAt > 10000) {
      sLastGeoToastAt = now;
      Toast.makeText(this, "网页正在使用定位功能", Toast.LENGTH_SHORT).show();
    }
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
        Toast.makeText(this, "麦克风/摄像头权限已授予，正在放行", Toast.LENGTH_LONG).show();
        grantMediaWithSelfCheck(req);
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
    mMainHandler.removeCallbacks(mBackTimeout);
    // The screen-capture service depends on this activity's MediaProjection token;
    // stop it when the shell is torn down. Location tracking keeps running by
    // design (it is the background fallback of the long-running record feature).
    if (org.mozilla.geckoview_example.jsbridge.ScreenCaptureService.isRunning()) {
      try {
        Intent stop =
            new Intent(this, org.mozilla.geckoview_example.jsbridge.ScreenCaptureService.class)
                .setAction(
                    org.mozilla.geckoview_example.jsbridge.ScreenCaptureService.ACTION_STOP);
        startService(stop);
      } catch (Exception e) {
        Log.w(LOGTAG, "stop screen capture failed", e);
      }
    }
    mJsBridge.onDestroy();
    if (mWebView != null) {
      mWebView.destroy();
      mWebView = null;
    }
    super.onDestroy();
  }

  /**
   * System back key handling: forward to the page instead of navigating directly.
   *
   * <ul>
   *   <li>Page not ready (loading, no bridge): behave like a normal browser.</li>
   *   <li>First press while ready: emit ui.backPressed and wait BACK_TIMEOUT_MS.</li>
   *   <li>Second press while waiting, or timeout: default behavior
   *       (goBack if possible, otherwise finish).</li>
   * </ul>
   */
  private void onBackKey() {
    if (!mPageReady || mJsBridge == null || mWebView == null) {
      performDefaultBack();
      return;
    }
    if (mBackWaitActive) {
      // User pressed again before the page answered: stop waiting, act now.
      mMainHandler.removeCallbacks(mBackTimeout);
      mBackWaitActive = false;
      performDefaultBack();
      return;
    }
    mBackWaitActive = true;
    try {
      org.json.JSONObject data = new org.json.JSONObject();
      data.put("atRoot", !mWebView.canGoBack());
      mJsBridge.emit("ui.backPressed", data);
    } catch (Exception e) {
      Log.w(LOGTAG, "emit ui.backPressed failed", e);
      mBackWaitActive = false;
      performDefaultBack();
      return;
    }
    mMainHandler.removeCallbacks(mBackTimeout);
    mMainHandler.postDelayed(mBackTimeout, BACK_TIMEOUT_MS);
  }

  private void performDefaultBack() {
    if (mWebView != null && mWebView.canGoBack()) {
      mWebView.goBack();
      return;
    }
    finish();
  }

  private int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
  }
}
