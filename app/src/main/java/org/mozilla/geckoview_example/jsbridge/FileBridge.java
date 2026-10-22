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
 * <p>Actions: file.writePrivate, file.appendPrivate, file.readPrivate,
 * file.deletePrivate, file.listPrivate, file.writePublic, file.readPublic,
 * file.deletePublic.
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
        case "file.writePublic":
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

  private void writePublic(String id, JSONObject params, JsBridge bridge) throws Exception {
    String name = safeName(params.optString("name", "file_" + System.currentTimeMillis() + ".txt"));
    String content = params.optString("content", "");
    String mime = params.optString("mime", "text/plain");

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // MediaStore.Downloads - no storage permission needed on API 29+
      ContentResolver cr = mContext.getContentResolver();
      ContentValues values = new ContentValues();
      values.put(MediaStore.Downloads.DISPLAY_NAME, name);
      values.put(MediaStore.Downloads.MIME_TYPE, mime);
      values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
      android.net.Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
      if (uri == null) {
        bridge.respond(id, false, null, "创建公共文件失败");
        return;
      }
      try (OutputStream out = cr.openOutputStream(uri)) {
        out.write(content.getBytes(StandardCharsets.UTF_8));
      }
      JSONObject r = new JSONObject();
      r.put("uri", uri.toString());
      r.put("name", name);
      r.put("location", "Downloads");
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
                File f = new File(dir, name);
                try (FileOutputStream out = new FileOutputStream(f)) {
                  out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                JSONObject r = new JSONObject();
                r.put("path", f.getAbsolutePath());
                r.put("name", name);
                r.put("location", "Downloads");
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
