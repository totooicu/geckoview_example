package org.mozilla.geckoview_example.jsbridge;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
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
 * Offline snapshot + two-level version update.
 *
 * <p>First visit downloads the page and its same-host resources (css/js/img/fonts,
 * plus {@code url()} refs inside CSS) into the app-private directory as a local
 * snapshot. Later launches render the snapshot instantly and check the remote
 * version endpoint in the background.</p>
 *
 * <p>Version endpoint convention: {@code <pageUrl>version.json}:</p>
 * <pre>
 * {
 *   "version": "2026-10-24",          // overall version (any comparable string)
 *   "link": "https://.../index.html", // (optional) entry link
 *   "subversion": [                   // per-file versions
 *     {"version": "2026-10-24", "link": "https://.../app.js"},
 *     {"version": "2026-10-23", "link": "https://.../style.css"}
 *   ]
 * }
 * </pre>
 *
 * <p>Update policy:
 * <ul>
 *   <li>overall {@code version} differs → full re-download of every resource;</li>
 *   <li>overall version equal, but some {@code subversion} entry changed →
 *       download only that file (the local file name is derived from the URL
 *       hash, so an unchanged URL maps to the same file name and is just
 *       overwritten);</li>
 *   <li>endpoint unreachable/empty → keep the local snapshot, retry next launch.</li>
 * </ul>
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
    // A valid snapshot must have a non-trivial index.html (a corrupted/empty
    // leftover must not block startup).
    File f = new File(mRoot, INDEX_FILE);
    return f.exists() && f.length() > 500;
  }

  /** Delete the whole snapshot so the next launch re-snapshots online. */
  public void clear() {
    deleteDir(mRoot);
  }

  public String getIndexPath() {
    return new File(mRoot, INDEX_FILE).getAbsolutePath();
  }

  public String getIndexFileUrl() {
    return "file://" + getIndexPath();
  }

  /* ----------------------------------------------------------------------------
   * Version check + update
   * -------------------------------------------------------------------------- */

  public interface UpdateListener {
    /** Called on UI thread. updated=true when something changed locally. */
    void onUpdateResult(boolean updated, String newVersion, String error);
  }

  /** Remote version.json config. */
  private static class VersionConfig {
    String version = "";
    final List<SubFile> subs = new ArrayList<>();
  }

  private static class SubFile {
    String version = "";
    String link = "";
  }

  /** Check remote version; full re-download or incremental file update. */
  public void checkAndUpdateAsync(UpdateListener listener) {
    mExecutor.execute(() -> {
      String error = null;
      boolean updated = false;
      String newVersion = null;
      try {
        VersionConfig remote = fetchConfig();
        if (remote == null) {
          Log.i(LOGTAG, "version check skipped (endpoint unavailable)");
          error = "版本接口不可用，保留本地";
        } else if (remote.version.isEmpty()) {
          Log.i(LOGTAG, "version check skipped (empty response)");
          error = "版本接口无有效内容，保留本地";
        } else {
          VersionConfig local = readLocalConfig();
          if (!remote.version.equals(local.version)) {
            // Overall version changed -> full re-download.
            Log.i(LOGTAG, "overall version " + local.version + " -> " + remote.version + " (full)");
            if (fetchAndStore(remote)) {
              updated = true;
              newVersion = remote.version;
              Log.i(LOGTAG, "full snapshot updated to " + remote.version);
            } else {
              error = "下载新版失败，保留本地";
            }
          } else {
            // Same overall version -> update only changed sub files.
            List<SubFile> changed = changedSubs(remote, local);
            if (changed.isEmpty()) {
              Log.i(LOGTAG, "version unchanged: " + remote.version);
            } else {
              Log.i(LOGTAG, changed.size() + " sub file(s) changed");
              boolean ok = true;
              for (SubFile sf : changed) {
                if (!downloadSubFile(sf)) {
                  ok = false;
                  Log.w(LOGTAG, "sub file download failed: " + sf.link);
                }
              }
              if (ok) {
                writeLocalConfig(remote);
                updated = true;
                newVersion = remote.version + " (+" + changed.size() + "文件)";
                Log.i(LOGTAG, "incremental update done: " + changed.size() + " file(s)");
              } else {
                error = "增量下载部分失败，保留本地";
              }
            }
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

  /** Full refresh regardless of version (used for the first-ever snapshot). */
  public void refreshSnapshotAsync(UpdateListener listener) {
    mExecutor.execute(() -> {
      String error = null;
      boolean updated = false;
      String version = null;
      try {
        VersionConfig remote = fetchConfig(); // may be null if no version.json yet
        if (fetchAndStore(remote)) {
          updated = true;
          version = remote == null || remote.version.isEmpty() ? "?" : remote.version;
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

  private VersionConfig fetchConfig() throws IOException {
    HttpURLConnection c = open(getVersionUrl(), "GET");
    try {
      int code = c.getResponseCode();
      if (code != 200) {
        return null;
      }
      String body = new String(readBody(c), StandardCharsets.UTF_8).trim();
      VersionConfig cfg = new VersionConfig();
      try {
        JSONObject o = new JSONObject(body);
        cfg.version = o.optString("version", "");
        JSONArray arr = o.optJSONArray("subversion");
        if (arr != null) {
          for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            SubFile sf = new SubFile();
            sf.version = e.optString("version", "");
            String link = e.optString("link", "");
            // Normalize relative links against the page URL.
            try {
              URL base = new URL(mPageUrl);
              URL u = new URL(base, link);
              sf.link = u.toString();
            } catch (Exception ex) {
              sf.link = link;
            }
            if (!sf.link.isEmpty()) {
              cfg.subs.add(sf);
            }
          }
        }
      } catch (Exception je) {
        // Not JSON: treat the raw body as the overall version (plain-text endpoint).
        cfg.version = body;
      }
      return cfg;
    } finally {
      c.disconnect();
    }
  }

  private VersionConfig readLocalConfig() {
    VersionConfig cfg = new VersionConfig();
    try {
      String text = readTextFile(new File(mRoot, MANIFEST_FILE));
      JSONObject o = new JSONObject(text);
      cfg.version = o.optString("version", "");
      JSONArray arr = o.optJSONArray("subversion");
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          JSONObject e = arr.optJSONObject(i);
          if (e == null) continue;
          SubFile sf = new SubFile();
          sf.version = e.optString("version", "");
          sf.link = e.optString("link", "");
          if (!sf.link.isEmpty()) {
            cfg.subs.add(sf);
          }
        }
      }
    } catch (Exception e) {
      Log.w(LOGTAG, "readLocalConfig", e);
    }
    return cfg;
  }

  private void writeLocalConfig(VersionConfig cfg) throws IOException {
    try {
      JSONObject o = new JSONObject();
      o.put("version", cfg.version);
      o.put("savedAt", System.currentTimeMillis());
      JSONArray arr = new JSONArray();
      for (SubFile sf : cfg.subs) {
        JSONObject e = new JSONObject();
        e.put("version", sf.version);
        e.put("link", sf.link);
        arr.put(e);
      }
      o.put("subversion", arr);
      writeTextFile(new File(mRoot, MANIFEST_FILE), o.toString());
    } catch (org.json.JSONException e) {
      throw new IOException("manifest", e);
    }
  }

  /** Subs present remotely but missing/changed locally. */
  private List<SubFile> changedSubs(VersionConfig remote, VersionConfig local) {
    List<SubFile> changed = new ArrayList<>();
    for (SubFile r : remote.subs) {
      boolean found = false;
      for (SubFile l : local.subs) {
        if (r.link.equals(l.link)) {
          found = true;
          if (!r.version.equals(l.version)) {
            changed.add(r);
          }
          break;
        }
      }
      if (!found) {
        changed.add(r);
      }
    }
    return changed;
  }

  /** Download a single changed sub file and overwrite its local copy. */
  private boolean downloadSubFile(SubFile sf) {
    try {
      if (!mRoot.exists() && !mRoot.mkdirs()) {
        return false;
      }
      HttpURLConnection c = open(sf.link, "GET");
      try {
        int code = c.getResponseCode();
        if (code != 200) {
          return false;
        }
        String type = c.getContentType();
        String name = isEntryUrl(sf.link) ? INDEX_FILE : localName(sf.link, type);
        File f = new File(mRoot, name);
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
        Log.i(LOGTAG, "sub file updated: " + sf.link + " -> " + name);
        return true;
      } finally {
        c.disconnect();
      }
    } catch (Exception e) {
      Log.w(LOGTAG, "downloadSubFile failed: " + sf.link, e);
      return false;
    }
  }

  /** True when the link resolves to the entry page itself (must overwrite index.html). */
  private boolean isEntryUrl(String link) {
    String base = mPageUrl;
    if (!base.endsWith("/")) base += "/";
    String candidate = link;
    int q = candidate.indexOf('?');
    if (q >= 0) candidate = candidate.substring(0, q);
    String noSlash = candidate.endsWith("/") ? candidate.substring(0, candidate.length() - 1) : candidate;
    String baseNoSlash = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    return noSlash.equals(baseNoSlash) || noSlash.equals(baseNoSlash + "/index.html");
  }

  /**
   * Download the page + same-host resources into a temp dir and atomically
   * replace the current snapshot. Writes the local config afterwards.
   */
  public boolean fetchAndStore(VersionConfig remote) {
    try {
      deleteDir(mTmp);
      if (!mTmp.mkdirs() && !mTmp.isDirectory()) {
        return false;
      }
      Snapshot s = Snapshot.download(mPageUrl, mAllowedHost, mTmp);
      s.writeIndex();

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

      // Persist the config: prefer version.json values, fall back to Last-Modified.
      VersionConfig cfg = new VersionConfig();
      cfg.version =
          (remote != null && !remote.version.isEmpty()) ? remote.version : s.version;
      if (remote != null) {
        cfg.subs.addAll(remote.subs);
      }
      writeLocalConfig(cfg);
      return true;
    } catch (Exception e) {
      Log.e(LOGTAG, "fetchAndStore failed", e);
      deleteDir(mTmp);
      return false;
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
    final String version; // Last-Modified / ETag fallback
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
  }

  /* ----------------------------------------------------------------------------
   * Helpers
   * -------------------------------------------------------------------------- */

  /** Local file name for a resource: hash of the absolute URL + extension. */
  private static String localName(String urlStr, String contentType) {
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

  private static String extFor(String contentType, String urlStr) {
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
