package org.mozilla.geckoview_example.jsbridge;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline snapshot + version-check cache.
 *
 * <p>First visit downloads the page and its same-host resources (css/js/img/fonts,
 * plus {@code url()} refs inside CSS) into the app-private directory as a local
 * snapshot. Later launches render the snapshot instantly and check the remote
 * version endpoint in the background; only when the version differs is the new
 * copy downloaded (into a temp dir) and atomically swapped in.</p>
 *
 * <p>Version endpoint convention: {@code <pageUrl>version.json} returning
 * {@code {"version":"x.y.z"}}. If the endpoint is missing/unreachable the check is
 * skipped (local snapshot stays), so a 404 from a site that has no version file
 * degrades to "keep local".</p>
 */
public class OfflineCache {
  private static final String LOGTAG = "OfflineCache";
  private static final String DIR = "offline_cache";
  private static final String DIR_TMP = "offline_cache_tmp";
  private static final String DIR_OLD = "offline_cache_old";
  private static final String INDEX_FILE = "index.html";
  private static final String MANIFEST_FILE = "manifest.json";
  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int READ_TIMEOUT_MS = 20000;
  private static final int MAX_RESOURCES = 200;
  private static final long MAX_RESOURCE_BYTES = 20L * 1024 * 1024;
  private static final String UA =
      "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) DatingRecord/0.2.0";

  private final Context mContext;
  private final String mPageUrl;
  private final String mAllowedHost;
  private final File mRoot;
  private final File mTmp;
  private final File mOld;
  private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

  public OfflineCache(Context context, String pageUrl, String allowedHost) {
    mContext = context.getApplicationContext();
    mPageUrl = pageUrl;
    mAllowedHost = allowedHost;
    mRoot = new File(mContext.getFilesDir(), DIR);
    mTmp = new File(mContext.getFilesDir(), DIR_TMP);
    mOld = new File(mContext.getFilesDir(), DIR_OLD);
  }

  /** Version endpoint: <page>/version.json */
  public String getVersionUrl() {
    String base = mPageUrl;
    if (!base.endsWith("/")) base += "/";
    return base + "version.json";
  }

  public boolean hasSnapshot() {
    return new File(mRoot, INDEX_FILE).exists();
  }

  public String getIndexPath() {
    return new File(mRoot, INDEX_FILE).getAbsolutePath();
  }

  public String getLocalVersion() {
    try {
      JSONObject o = readJson(new File(mRoot, MANIFEST_FILE));
      return o.optString("version", "");
    } catch (Exception e) {
      return "";
    }
  }

  /** Render the snapshot immediately; the page itself references local files. */
  public String getIndexFileUrl() {
    return "file://" + getIndexPath();
  }

  /* ----------------------------------------------------------------------------
   * Version check + update
   * -------------------------------------------------------------------------- */

  public interface UpdateListener {
    /** Called on UI thread. updated=true when a new snapshot replaced the old one. */
    void onUpdateResult(boolean updated, String newVersion, String error);
  }

  /** Check remote version; if different, download & atomically swap. */
  public void checkAndUpdateAsync(UpdateListener listener) {
    mExecutor.execute(() -> {
      String error = null;
      boolean updated = false;
      String newVersion = null;
      try {
        String remote = fetchVersion();
        String local = getLocalVersion();
        if (remote == null) {
          // Version endpoint unreachable: keep local, try again next launch.
          Log.i(LOGTAG, "version check skipped (endpoint unavailable)");
          error = "版本接口不可用，保留本地";
        } else if (remote.isEmpty()) {
          // Endpoint returned nothing useful: keep local.
          Log.i(LOGTAG, "version check skipped (empty response)");
          error = "版本接口无有效内容，保留本地";
        } else if (remote.equals(local)) {
          Log.i(LOGTAG, "version unchanged: " + remote);
        } else {
          Log.i(LOGTAG, "new version " + remote + " (local " + local + ") - downloading");
          if (fetchAndStore()) {
            updated = true;
            newVersion = remote;
            Log.i(LOGTAG, "snapshot updated to " + remote);
          } else {
            error = "下载新版失败，保留本地";
          }
        }
      } catch (Exception e) {
        Log.w(LOGTAG, "checkAndUpdate failed", e);
        error = e.getMessage();
      }
      final String fErr = error;
      final boolean fUpd = updated;
      final String fVer = newVersion;
      post(() -> listener.onUpdateResult(fUpd, fVer, fErr));
    });
  }

  /**
   * Download the page + same-host resources into a temp dir and atomically
   * replace the current snapshot. Returns true on success.
   */
  public boolean fetchAndStore() {
    try {
      deleteDir(mTmp);
      if (!mTmp.mkdirs() && !mTmp.isDirectory()) {
        return false;
      }
      Snapshot s = Snapshot.download(mPageUrl, mAllowedHost, mTmp);
      s.writeIndex();
      s.writeManifest();

      // Atomic swap: cur -> old, tmp -> cur, delete old.
      deleteDir(mOld);
      if (mRoot.exists() && !mRoot.renameTo(mOld)) {
        Log.w(LOGTAG, "cannot rename current snapshot to old");
      }
      if (!mTmp.renameTo(mRoot)) {
        Log.e(LOGTAG, "rename tmp -> root failed");
        return false;
      }
      deleteDir(mOld);
      return true;
    } catch (Exception e) {
      Log.e(LOGTAG, "fetchAndStore failed", e);
      deleteDir(mTmp);
      return false;
    }
  }

  /** Full refresh regardless of version (used for the first-ever snapshot). */
  public void refreshSnapshotAsync(UpdateListener listener) {
    mExecutor.execute(() -> {
      String error = null;
      boolean updated = false;
      String version = null;
      try {
        String v = fetchVersion();
        if (fetchAndStore()) {
          updated = true;
          version = (v == null || v.isEmpty()) ? "?" : v;
        } else {
          error = "保存网站失败";
        }
      } catch (Exception e) {
        error = e.getMessage();
      }
      final String fErr = error;
      final boolean fUpd = updated;
      final String fVer = version;
      post(() -> listener.onUpdateResult(fUpd, fVer, fErr));
    });
  }

  private String fetchVersion() throws IOException {
    HttpURLConnection c = open(getVersionUrl(), "GET");
    try {
      int code = c.getResponseCode();
      if (code != 200) {
        return null;
      }
      String body = new String(readBody(c), StandardCharsets.UTF_8).trim();
      try {
        JSONObject o = new JSONObject(body);
        return o.optString("version", "");
      } catch (Exception je) {
        return body;
      }
    } finally {
      c.disconnect();
    }
  }

  /* ----------------------------------------------------------------------------
   * Snapshot download
   * -------------------------------------------------------------------------- */

  private static final Pattern ATTR_URL =
      Pattern.compile("(src|href|poster|data-original)\\s*=\\s*([\"'])(.*?)\\2", Pattern.CASE_INSENSITIVE);
  private static final Pattern CSS_URL =
      Pattern.compile("url\\(\\s*([\"']?)(.*?)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);

  private static class Snapshot {
    String html;
    final String version;
    final File dir;
    final Map<String, String> urlToLocal = new HashMap<>(); // absolute url -> relative file name
    final Set<String> pending = new HashSet<>();

    Snapshot(String html, String version, File dir) {
      this.html = html;
      this.version = version;
      this.dir = dir;
    }

    static Snapshot download(String pageUrl, String allowedHost, File dir) throws IOException {
      HttpURLConnection c = open(pageUrl, "GET");
      String html;
      String version;
      String finalUrl = pageUrl;
      try {
        int code = c.getResponseCode();
        if (code >= 300 && code < 400) {
          String loc = c.getHeaderField("Location");
          if (loc != null) {
            finalUrl = new URL(new URL(pageUrl), loc).toString();
            c.disconnect();
            c = open(finalUrl, "GET");
            code = c.getResponseCode();
          }
        }
        if (code != 200) {
          throw new IOException("HTTP " + code);
        }
        html = new String(readBody(c), StandardCharsets.UTF_8);
        String lastMod = c.getHeaderField("Last-Modified");
        String etag = c.getHeaderField("ETag");
        version = lastMod != null ? lastMod : (etag != null ? etag : "snapshot");
      } finally {
        c.disconnect();
      }

      Snapshot s = new Snapshot(html, version, dir);
      s.collectAndDownload(finalUrl, allowedHost);
      return s;
    }

    /** Download same-host resources, build rewrite map, rewrite local copies. */
    private void collectAndDownload(String pageUrl, String allowedHost) throws IOException {
      URL base = new URL(pageUrl);

      // 1) queue src/href/poster attributes and url() inside inline styles.
      Matcher m = ATTR_URL.matcher(html);
      while (m.find()) {
        queueRef(m.group(3), base, allowedHost);
      }
      Matcher sm = CSS_URL.matcher(html);
      while (sm.find()) {
        queueRef(sm.group(2), base, allowedHost);
      }

      // 2) download all direct resources.
      for (String url : new ArrayList<>(pending)) {
        downloadOne(url);
      }

      // 3) scan downloaded CSS bodies for nested url() refs and download them.
      List<String> cssUrls = new ArrayList<>();
      for (Map.Entry<String, String> e : urlToLocal.entrySet()) {
        if (e.getKey().contains(".css")) {
          cssUrls.add(e.getKey());
        }
      }
      for (String cssUrl : cssUrls) {
        String local = urlToLocal.get(cssUrl);
        if (local == null) continue;
        String body = readTextFile(new File(dir, local));
        Matcher cm = CSS_URL.matcher(body);
        while (cm.find()) {
          queueRef(cm.group(2), new URL(cssUrl), allowedHost);
        }
      }
      for (String url : new ArrayList<>(pending)) {
        downloadOne(url);
      }

      // 4) rewrite index html.
      StringBuilder sb = rewriteAttrs(new StringBuilder(html), base, allowedHost);
      sb = rewriteCssUrls(sb, base, allowedHost);
      html = sb.toString();

      // 5) rewrite css files.
      for (String cssUrl : cssUrls) {
        String local = urlToLocal.get(cssUrl);
        if (local == null) continue;
        String body = readTextFile(new File(dir, local));
        writeTextFile(new File(dir, local), rewriteCssUrls(new StringBuilder(body), new URL(cssUrl), allowedHost).toString());
      }
    }

    private void queueRef(String ref, URL base, String allowedHost) {
      if (isSkippableRef(ref)) return;
      try {
        URL u = new URL(base, ref.trim());
        if (allowedHost.equalsIgnoreCase(u.getHost()) && "https".equalsIgnoreCase(u.getProtocol())) {
          String abs = u.toString();
          if (urlToLocal.containsKey(abs) || pending.contains(abs)) return;
          if (urlToLocal.size() + pending.size() >= MAX_RESOURCES) return;
          pending.add(abs);
        }
        // External resources are left untouched (online only).
      } catch (Exception ignored) {
      }
    }

    private void downloadOne(String urlStr) {
      if (!pending.remove(urlStr)) return;
      try {
        URL u = new URL(urlStr);
        HttpURLConnection c = open(urlStr, "GET");
        try {
          int code = c.getResponseCode();
          if (code != 200) return;
          String type = c.getContentType();
          String name = localName(urlStr, type);
          File f = new File(dir, name);
          try (InputStream in = new BufferedInputStream(c.getInputStream());
               OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            long total = 0;
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
              total += n;
              if (total > MAX_RESOURCE_BYTES) {
                throw new IOException("resource too large");
              }
              out.write(buf, 0, n);
            }
          }
          urlToLocal.put(urlStr, name);
          Log.d(LOGTAG, "cached " + urlStr + " -> " + name);
        } finally {
          c.disconnect();
        }
      } catch (Exception e) {
        Log.w(LOGTAG, "skip resource " + urlStr + ": " + e.getMessage());
      }
    }

    private StringBuilder rewriteAttrs(StringBuilder sb, URL base, String allowedHost) {
      Matcher m = ATTR_URL.matcher(sb.toString());
      StringBuffer out = new StringBuffer();
      while (m.find()) {
        String attr = m.group(1);
        String q = m.group(2);
        String v = m.group(3).trim();
        String local = resolveLocal(v, base, allowedHost);
        if (local != null) {
          m.appendReplacement(out, Matcher.quoteReplacement(attr + "=" + q + local + q));
        }
      }
      m.appendTail(out);
      return new StringBuilder(out.toString());
    }

    private StringBuilder rewriteCssUrls(StringBuilder sb, URL base, String allowedHost) {
      Matcher m = CSS_URL.matcher(sb.toString());
      StringBuffer out = new StringBuffer();
      while (m.find()) {
        String q = m.group(1);
        String v = m.group(2).trim();
        String local = resolveLocal(v, base, allowedHost);
        if (local != null) {
          m.appendReplacement(out, Matcher.quoteReplacement("url(" + q + local + q + ")"));
        }
      }
      m.appendTail(out);
      return new StringBuilder(out.toString());
    }

    /** Resolve a ref to a local relative file name, or null to keep online. */
    private String resolveLocal(String ref, URL base, String allowedHost) {
      if (isSkippableRef(ref)) return null;
      try {
        URL u = new URL(base, ref.trim());
        if (!allowedHost.equalsIgnoreCase(u.getHost()) || !"https".equalsIgnoreCase(u.getProtocol())) {
          return null;
        }
        String abs = u.toString();
        String local = urlToLocal.get(abs);
        if (local == null) {
          // Not queued yet (e.g. only referenced inside a CSS file already
          // rewritten): download now so the local copy is complete.
          queueRef(ref, base, allowedHost);
          if (pending.contains(abs)) {
            downloadOne(abs);
          }
          local = urlToLocal.get(abs);
        }
        return local;
      } catch (Exception e) {
        return null;
      }
    }

    private String localName(String urlStr, String contentType) {
      String hash;
      try {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(urlStr.getBytes(StandardCharsets.UTF_8));
        StringBuilder hb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
          hb.append(String.format(Locale.US, "%02x", d[i]));
        }
        hash = hb.toString();
      } catch (Exception e) {
        hash = String.valueOf(Math.abs(urlStr.hashCode()));
      }
      return hash + extFor(contentType, urlStr);
    }

    private String extFor(String contentType, String urlStr) {
      if (contentType != null) {
        String ct = contentType.toLowerCase(Locale.US);
        if (ct.contains("text/css")) return ".css";
        if (ct.contains("javascript")) return ".js";
        if (ct.contains("text/html")) return ".html";
        if (ct.contains("image/png")) return ".png";
        if (ct.contains("image/jpeg")) return ".jpg";
        if (ct.contains("image/gif")) return ".gif";
        if (ct.contains("image/svg")) return ".svg";
        if (ct.contains("image/webp")) return ".webp";
        if (ct.contains("application/json")) return ".json";
        if (ct.contains("font/woff2") || ct.contains("woff2")) return ".woff2";
        if (ct.contains("font/woff") || ct.contains("woff")) return ".woff";
        if (ct.contains("octet-stream")) {
          String p = pathOf(urlStr).toLowerCase(Locale.US);
          if (p.endsWith(".woff2")) return ".woff2";
          if (p.endsWith(".woff")) return ".woff";
          if (p.endsWith(".ttf")) return ".ttf";
        }
      }
      String p = pathOf(urlStr).toLowerCase(Locale.US);
      int dot = p.lastIndexOf('.');
      if (dot >= 0) {
        String ext = p.substring(dot);
        if (ext.matches("\\.[a-z0-9]{2,5}")) return ext;
      }
      return ".bin";
    }

    private static String pathOf(String urlStr) {
      try {
        return new URL(urlStr).getPath();
      } catch (Exception e) {
        return urlStr;
      }
    }

    private static boolean isSkippableRef(String v) {
      if (v == null || v.isEmpty()) return true;
      String t = v.trim().toLowerCase(Locale.US);
      return t.startsWith("data:")
          || t.startsWith("javascript:")
          || t.startsWith("mailto:")
          || t.startsWith("tel:")
          || t.startsWith("#")
          || t.startsWith("about:");
    }

    void writeIndex() throws IOException {
      writeTextFile(new File(dir, INDEX_FILE), html);
    }

    void writeManifest() throws IOException {
      try {
        JSONObject o = new JSONObject();
        o.put("version", version);
        o.put("savedAt", System.currentTimeMillis());
        writeTextFile(new File(dir, MANIFEST_FILE), o.toString());
      } catch (org.json.JSONException e) {
        throw new IOException("manifest", e);
      }
    }
  }

  /* ----------------------------------------------------------------------------
   * Helpers
   * -------------------------------------------------------------------------- */

  private static HttpURLConnection open(String url, String method) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setRequestMethod(method);
    c.setConnectTimeout(CONNECT_TIMEOUT_MS);
    c.setReadTimeout(READ_TIMEOUT_MS);
    c.setRequestProperty("User-Agent", UA);
    c.setInstanceFollowRedirects(false);
    return c;
  }

  private static byte[] readBody(HttpURLConnection c) throws IOException {
    InputStream in = c.getInputStream();
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    } finally {
      try {
        in.close();
      } catch (IOException ignored) {
      }
    }
  }

  private static JSONObject readJson(File f) throws Exception {
    return new JSONObject(readTextFile(f));
  }

  private static String readTextFile(File f) throws IOException {
    try (InputStream in = new FileInputStream(f)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static void writeTextFile(File f, String content) throws IOException {
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void deleteDir(File dir) {
    if (dir == null || !dir.exists()) return;
    File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.isDirectory()) {
          deleteDir(f);
        } else {
          //noinspection ResultOfMethodCallIgnored
          f.delete();
        }
      }
    }
    //noinspection ResultOfMethodCallIgnored
    dir.delete();
  }

  private void post(Runnable r) {
    android.os.Handler h = new android.os.Handler(mContext.getMainLooper());
    h.post(r);
  }

  public void shutdown() {
    mExecutor.shutdown();
  }
}
