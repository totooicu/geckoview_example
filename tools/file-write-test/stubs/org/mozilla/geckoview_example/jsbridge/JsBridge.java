package org.mozilla.geckoview_example.jsbridge;

import android.app.Activity;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * 桩：签名照真实的 jsbridge/JsBridge.java 抄（run.sh 每次核一遍），
 * 行为上「权限一律给」并把 respond 记进 {@link #replies()}，好让桌面断言看得见结果。
 */
public class JsBridge {
  public static final int PERM_FILE = 1002;

  public interface PermissionCallback {
    void onGranted();

    default void onDenied() {}
  }

  /** 一条回包 */
  public static final class Reply {
    public final String id;
    public final boolean ok;
    @Nullable public final JSONObject result;
    @Nullable public final String error;

    Reply(String id, boolean ok, @Nullable JSONObject result, @Nullable String error) {
      this.id = id;
      this.ok = ok;
      this.result = result;
      this.error = error;
    }
  }

  private static final List<Reply> sReplies = new ArrayList<>();

  public static List<Reply> replies() { return sReplies; }

  public static void clearReplies() { sReplies.clear(); }

  public void respond(String id, boolean ok, @Nullable JSONObject result, @Nullable String error) {
    sReplies.add(new Reply(id, ok, result, error));
  }

  /** 桌面一律当「用户给了权限」，权限框本身要真机验 */
  public static void requestPermissions(
      Activity activity, int code, String[] permissions, PermissionCallback cb) {
    cb.onGranted();
  }

  public Activity activity() { return null; }
}
