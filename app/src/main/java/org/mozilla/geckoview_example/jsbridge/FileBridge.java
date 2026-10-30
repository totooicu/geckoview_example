package org.mozilla.geckoview_example.jsbridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * File bridge: private app storage (no permission needed) and public Downloads
 * (MediaStore on API 29+; WRITE_EXTERNAL_STORAGE on API 26-28).
 *
 * <p>Actions: file.capabilities, file.writePrivate, file.appendPrivate,
 * file.readPrivate, file.deletePrivate, file.listPrivate, file.writePublic,
 * file.appendPublic, file.readPublic, file.deletePublic.
 *
 * <p>file.writePublic 自 2026-10-30 起支持二进制与分片续写（v2）：
 * <pre>
 *   {name, mime, encoding: "utf8"|"base64", content, append: bool, uri}
 *   → {uri|path, name, location, bytes}
 * </pre>
 * 为什么要它：网页导出备份 / 分享包 / 下载附件一直是 &lt;a download&gt; + blob:，
 * 那套在 WebView 里没有任何人接（DownloadManager 拿不到 blob:），用户点了
 * 就是「一点反应都没有」。网页侧现在先发 file.capabilities 探能力（有人回才走
 * 二进制写，没人回说明是浏览器，退回 &lt;a download&gt;），再把字节按块交过来。
 * 第一片建文件、后续片带 uri + append:true 续写；块大小由 maxChunkBytes 给出上限。
 */
public class FileBridge {
  private static final String LOGTAG = "FileBridge";

  private final Context mContext;
  private final JsBridge mBridge;
  private final File mPrivateDir;

  public FileBridge(Context context, JsBridge bridge) {
    mContext = context.getApplicationContext();
    mBridge = bridge;
    mPrivateDir = new File(mContext.getFilesDir(), "bridge");
    //noinspection ResultOfMethodCallIgnored
    mPrivateDir.mkdirs();
  }

  public void handle(String id, String action, JSONObject params, JsBridge bridge) {
    try {
      switch (action) {
        case "file.writePrivate":
          writePrivate(id, params, bridge, false);
          break;
        case "file.appendPrivate":
          writePrivate(id, params, bridge, true);
          break;
        case "file.readPrivate":
          readPrivate(id, params, bridge);
          break;
        case "file.deletePrivate":
          deletePrivate(id, params, bridge);
          break;
        case "file.listPrivate":
          listPrivate(id, bridge);
          break;
        case "file.capabilities":
          capabilities(id, bridge);
          break;
        case "file.writePublic":
          writePublic(id, params, bridge);
          break;
        case "file.appendPublic":
          params.put("append", true);
          writePublic(id, params, bridge);
          break;
        case "file.readPublic":
          readPublic(id, params, bridge);
          break;
        case "file.deletePublic":
          deletePublic(id, params, bridge);
          break;
        default:
          bridge.respond(id, false, null, "unknown file action: " + action);
      }
    } catch (Exception e) {
      Log.e(LOGTAG, action, e);
      bridge.respond(id, false, null, e.getMessage());
    }
  }

  /* ----------------------------------------------------------------------------
   * Private storage (filesDir/bridge) - no permission required
   * -------------------------------------------------------------------------- */

  private String safeName(String name) {
    String n = name.replace("..", "_").replace("/", "_").replace("\\", "_");
    return n.isEmpty() ? "unnamed" : n;
  }

  private void writePrivate(String id, JSONObject params, JsBridge bridge, boolean append) throws Exception {
    String name = safeName(params.optString("name", ""));
    String content = params.optString("content", "");
    File f = new File(mPrivateDir, name);
    try (FileOutputStream out = new FileOutputStream(f, append)) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
    }
    JSONObject r = new JSONObject();
    r.put("path", f.getAbsolutePath());
    r.put("size", f.length());
    bridge.respond(id, true, r, null);
  }

  private void readPrivate(String id, JSONObject params, JsBridge bridge) throws Exception {
    String name = safeName(params.optString("name", ""));
    File f = new File(mPrivateDir, name);
    if (!f.exists()) {
      bridge.respond(id, false, null, "文件不存在: " + name);
      return;
    }
    try (FileInputStream in = new FileInputStream(f)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] tmp = new byte[8192];
      int n;
      while ((n = in.read(tmp)) > 0) {
        buf.write(tmp, 0, n);
      }
      JSONObject r = new JSONObject();
      r.put("name", name);
      r.put("size", f.length());
      r.put("content", new String(buf.toByteArray(), StandardCharsets.UTF_8));
      bridge.respond(id, true, r, null);
    }
  }

  private void deletePrivate(String id, JSONObject params, JsBridge bridge) throws Exception {
    String name = safeName(params.optString("name", ""));
    File f = new File(mPrivateDir, name);
    boolean deleted = f.exists() && f.delete();
    JSONObject r = new JSONObject();
    r.put("deleted", deleted);
    bridge.respond(id, true, r, null);
  }

  private void listPrivate(String id, JsBridge bridge) throws Exception {
    File[] files = mPrivateDir.listFiles();
    JSONArray arr = new JSONArray();
    if (files != null) {
      for (File f : files) {
        JSONObject fo = new JSONObject();
        fo.put("name", f.getName());
        fo.put("size", f.length());
        fo.put("modified", f.lastModified());
        arr.put(fo);
      }
    }
    JSONObject r = new JSONObject();
    r.put("files", arr);
    bridge.respond(id, true, r, null);
  }

  /* ----------------------------------------------------------------------------
   * Public storage (Downloads)
   * -------------------------------------------------------------------------- */

  /** 单块上限：网页按这个值切分，超了不报错但也不建议（JSON 过桥会变慢） */
  private static final int MAX_CHUNK = 4 * 1024 * 1024;

  /** v2 的续写兜底：某些 provider 不认 "wa"，读回来重写，避免半截文件 */
  private static final long REWRITE_LIMIT = 64L * 1024 * 1024;

  private void capabilities(String id, JsBridge bridge) throws Exception {
    JSONObject r = new JSONObject();
    r.put("version", 2);
    r.put("binary", true);
    r.put("encodings", new JSONArray().put("utf8").put("base64"));
    r.put("append", true);
    r.put("maxChunkBytes", MAX_CHUNK);
    r.put("downloads", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? "MediaStore" : "File");
    bridge.respond(id, true, r, null);
  }

  /** content 按 encoding 解成字节；utf8 是默认值，老调用方不用改 */
  private byte[] contentBytes(JSONObject params) {
    String content = params.optString("content", "");
    if ("base64".equals(params.optString("encoding", "utf8"))) {
      return android.util.Base64.decode(content, android.util.Base64.DEFAULT);
    }
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private void writePublic(String id, JSONObject params, JsBridge bridge) throws Exception {
    String name = safeName(params.optString("name", "file_" + System.currentTimeMillis() + ".txt"));
    String content = params.optString("content", "");
    String mime = params.optString("mime", "text/plain");
    byte[] bytes = contentBytes(params);
    boolean append = params.optBoolean("append", false);
    String uriParam = params.optString("uri", "");

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // MediaStore.Downloads - no storage permission needed on API 29+
      ContentResolver cr = mContext.getContentResolver();
      android.net.Uri uri = uriParam.isEmpty() ? null : android.net.Uri.parse(uriParam);
      if (uri == null) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
          bridge.respond(id, false, null, "创建公共文件失败");
          return;
        }
      }
      boolean wrote = writeUri(cr, uri, bytes, append && !uriParam.isEmpty());
      if (!wrote) {
        bridge.respond(id, false, null, "写入公共文件失败（续写不支持且超过重写上限）");
        return;
      }
      JSONObject r = new JSONObject();
      r.put("uri", uri.toString());
      r.put("name", name);
      r.put("location", "Downloads");
      r.put("bytes", bytes.length);
      bridge.respond(id, true, r, null);
    } else {
      // API 26-28: WRITE_EXTERNAL_STORAGE runtime permission
      JsBridge.PermissionCallback cb =
          new JsBridge.PermissionCallback() {
            @Override
            public void onGranted() {
              try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = uriParam.isEmpty() ? new File(dir, name) : new File(uriParam);
                writeFile(f, bytes, append);
                JSONObject r = new JSONObject();
                r.put("path", f.getAbsolutePath());
                r.put("name", f.getName());
                r.put("location", "Downloads");
                r.put("bytes", bytes.length);
                bridge.respond(id, true, r, null);
              } catch (Exception e) {
                bridge.respond(id, false, null, e.getMessage());
              }
            }

            @Override
            public void onDenied() {
              bridge.respond(id, false, null, "用户拒绝存储权限");
            }
          };
      JsBridge.requestPermissions(
          mBridge.activity(),
          JsBridge.PERM_FILE,
          new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
          cb);
    }
  }

  /** 写一个 MediaStore uri：覆盖写 "w"，续写先试 "wa"，不认就整份读回来重写 */
  private boolean writeUri(ContentResolver cr, android.net.Uri uri, byte[] bytes, boolean append)
      throws Exception {
    if (!append) {
      try (OutputStream out = cr.openOutputStream(uri, "w")) {
        if (out == null) throw new Exception("打开公共文件失败");
        out.write(bytes);
      }
      return true;
    }
    try (OutputStream out = cr.openOutputStream(uri, "wa")) {
      if (out == null) throw new Exception("打开公共文件失败");
      out.write(bytes);
      return true;
    } catch (Exception e) {
      Log.w(LOGTAG, "append mode rejected, rewriting whole file", e);
    }
    byte[] existing;
    try (InputStream in = cr.openInputStream(uri)) {
      if (in == null) throw new Exception("读取已有公共文件失败");
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] tmp = new byte[8192];
      int n;
      long total = 0;
      while ((n = in.read(tmp)) > 0) {
        total += n;
        if (total > REWRITE_LIMIT) return false;
        buf.write(tmp, 0, n);
      }
      existing = buf.toByteArray();
    }
    try (OutputStream out = cr.openOutputStream(uri, "w")) {
      if (out == null) throw new Exception("打开公共文件失败");
      out.write(existing);
      out.write(bytes);
    }
    return true;
  }

  private void writeFile(File f, byte[] bytes, boolean append) throws Exception {
    try (FileOutputStream out = new FileOutputStream(f, append)) {
      out.write(bytes);
    }
  }

  private void readPublic(String id, JSONObject params, JsBridge bridge) throws Exception {
    String uri = params.optString("uri", "");
    if (uri.isEmpty()) {
      bridge.respond(id, false, null, "缺少 uri");
      return;
    }
    android.net.Uri u = android.net.Uri.parse(uri);
    ContentResolver cr = mContext.getContentResolver();
    try (InputStream in = cr.openInputStream(u)) {
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] tmp = new byte[8192];
      int n;
      while ((n = in.read(tmp)) > 0) {
        buf.write(tmp, 0, n);
      }
      JSONObject r = new JSONObject();
      r.put("content", new String(buf.toByteArray(), StandardCharsets.UTF_8));
      bridge.respond(id, true, r, null);
    }
  }

  private void deletePublic(String id, JSONObject params, JsBridge bridge) throws Exception {
    String uri = params.optString("uri", "");
    if (uri.isEmpty()) {
      bridge.respond(id, false, null, "缺少 uri");
      return;
    }
    int deleted = mContext.getContentResolver().delete(android.net.Uri.parse(uri), null, null);
    JSONObject r = new JSONObject();
    r.put("deleted", deleted > 0);
    bridge.respond(id, true, r, null);
  }
}
