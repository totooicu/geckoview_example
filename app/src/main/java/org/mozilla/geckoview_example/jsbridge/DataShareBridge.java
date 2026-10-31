package org.mozilla.geckoview_example.jsbridge;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * 桥动作 {@code remote.*}：让网页（壳里的那份）控制局域网服务器与自启动。
 *
 * <ul>
 *   <li>{@code remote.capabilities} → 版本与动作表；网页用它判断「有没有壳端服务」</li>
 *   <li>{@code remote.status} → running / port / token / autostart / hostOnline /
 *       clients / addresses</li>
 *   <li>{@code remote.start {port?, token?}} → 绑定端口并拉起前台服务</li>
 *   <li>{@code remote.stop} → 停服</li>
 *   <li>{@code remote.autostart {enabled}} → 只改开机偏好，不动当前监听</li>
 * </ul>
 */
public final class DataShareBridge {
  private final Context mApp;

  public DataShareBridge(Context ctx) {
    mApp = ctx.getApplicationContext();
  }

  public void handle(String id, String action, JSONObject params, JsBridge bridge) {
    try {
      switch (action) {
        case "remote.capabilities": {
          JSONObject r = new JSONObject();
          r.put("version", 1);
          r.put("defaultPort", DataShareStore.DEFAULT_PORT);
          JSONArray actions = new JSONArray();
          actions.put("remote.status");
          actions.put("remote.start");
          actions.put("remote.stop");
          actions.put("remote.autostart");
          r.put("actions", actions);
          bridge.respond(id, true, r, null);
          return;
        }
        case "remote.status":
          bridge.respond(id, true, statusJson(), null);
          return;
        case "remote.start": {
          DataShareStore.Prefs prefs = DataShareStore.read(mApp);
          int port = params.has("port") ? params.optInt("port", prefs.port) : prefs.port;
          String token = params.has("token") ? params.optString("token", prefs.token).trim() : prefs.token;
          if (token.isEmpty()) token = DataShareStore.tokenOrCreate(mApp);
          DataShareStore.write(mApp, port, token, prefs.autostart);
          try {
            DataShareServer.get().start(port, token);
          } catch (IOException e) {
            bridge.respond(id, false, null, e.getMessage());
            return;
          }
          ContextCompat.startForegroundService(
              mApp, new Intent(mApp, DataShareService.class).setAction(DataShareService.ACTION_START));
          bridge.respond(id, true, statusJson(), null);
          return;
        }
        case "remote.stop":
          DataShareServer.get().stop();
          mApp.stopService(new Intent(mApp, DataShareService.class));
          bridge.respond(id, true, statusJson(), null);
          return;
        case "remote.autostart": {
          boolean enabled = params.optBoolean("enabled", false);
          DataShareStore.Prefs prefs = DataShareStore.read(mApp);
          DataShareStore.write(mApp, prefs.port, prefs.token, enabled);
          bridge.respond(id, true, statusJson(), null);
          return;
        }
        default:
          bridge.respond(id, false, null, "unknown remote action: " + action);
      }
    } catch (Exception e) {
      bridge.respond(id, false, null, "remote: " + e.getMessage());
    }
  }

  private JSONObject statusJson() throws Exception {
    DataShareStore.Prefs prefs = DataShareStore.read(mApp);
    DataShareServer.Status st = DataShareServer.get().status();
    JSONObject r = new JSONObject();
    r.put("ok", true);
    r.put("running", st.running);
    r.put("port", st.running ? st.port : prefs.port);
    r.put("token", st.running ? st.token : prefs.token);
    r.put("autostart", prefs.autostart);
    r.put("hostOnline", st.hostOnline);
    r.put("clients", st.clients);
    JSONArray addresses = new JSONArray();
    for (String a : st.addresses) addresses.put(a);
    r.put("addresses", addresses);
    return r;
  }
}
