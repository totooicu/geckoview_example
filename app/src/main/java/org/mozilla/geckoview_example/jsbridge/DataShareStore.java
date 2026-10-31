package org.mozilla.geckoview_example.jsbridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

/** 「跨设备操作」的持久化：端口、token、是否随开机自启动。 */
public final class DataShareStore {
  public static final int DEFAULT_PORT = DataShareServer.DEFAULT_PORT;
  private static final String PREF = "data_share";
  private static final String KEY_PORT = "port";
  private static final String KEY_TOKEN = "token";
  private static final String KEY_AUTOSTART = "autostart";

  private DataShareStore() {}

  public static final class Prefs {
    public final int port;
    public final String token;
    public final boolean autostart;

    Prefs(int port, String token, boolean autostart) {
      this.port = port;
      this.token = token;
      this.autostart = autostart;
    }
  }

  public static Prefs read(Context ctx) {
    SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    int port = p.getInt(KEY_PORT, DEFAULT_PORT);
    if (port <= 0 || port > 65535) port = DEFAULT_PORT;
    String token = p.getString(KEY_TOKEN, "");
    if (token == null) token = "";
    return new Prefs(port, token, p.getBoolean(KEY_AUTOSTART, false));
  }

  public static void write(Context ctx, int port, String token, boolean autostart) {
    ctx.getApplicationContext()
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_PORT, port)
        .putString(KEY_TOKEN, token == null ? "" : token)
        .putBoolean(KEY_AUTOSTART, autostart)
        .apply();
  }

  public static String tokenOrCreate(Context ctx) {
    Prefs p = read(ctx);
    if (!p.token.isEmpty()) return p.token;
    String token = randomToken();
    write(ctx, p.port, token, p.autostart);
    return token;
  }

  public static String randomToken() {
    byte[] bytes = new byte[5];
    new SecureRandom().nextBytes(bytes);
    StringBuilder b = new StringBuilder(10);
    for (byte x : bytes) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
    return b.toString();
  }
}
