package org.mozilla.geckoview_example.jsbridge;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 原生网络代理（桥的 {@code net.*} 动作空间）。
 *
 * <p>存在的唯一理由：坚果云 WebDAV 不返回跨域头，浏览器里 fetch 不到；
 * 壳在系统网络栈上发请求，不受 CORS 约束。网页把「方法 + 地址 + 头 + base64 请求体」
 * 交给 {@code net.request}，壳把响应原样交回，不做任何业务判断——
 * 同步策略、冲突处理、凭据存放全部留在网页里（壳只承载、兜底、桥接）。</p>
 *
 * <p>HTTP 细节在 {@link NetHttp}：那份代码不依赖 Android，可以在桌面上单独跑测试
 * （{@code tools/net-http-test/}），本类只负责 base64、线程与应答格式。</p>
 */
public class NetBridge {
  private static final String LOGTAG = "NetBridge";

  /** 请求体上限 12MB（base64 前）：同步分片是 64KB，留足给整份备份上传。 */
  static final int MAX_REQUEST_BYTES = 12 * 1024 * 1024;
  /** 响应体上限 32MB：超过就报错，不能让一次 GET 把壳撑进低内存。 */
  static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
  /** 单次请求最长等待；网页侧另有自己的超时，两边都要有界。 */
  static final int MAX_TIMEOUT_MS = 120_000;

  /** 两条线程：同步是分片顺序上传，但用户可能一边手动测试一边后台自动同步。 */
  private final ExecutorService mPool;

  public NetBridge() {
    mPool =
        Executors.newFixedThreadPool(
            2,
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "dr-net");
                t.setDaemon(true);
                return t;
              }
            });
  }

  public void handle(String id, String action, JSONObject params, JsBridge bridge) {
    switch (action) {
      case "net.capabilities":
        // 页面探测能力用（项目的规矩：想知道有没有，就发一次 action 看有没有人回）
        try {
          JSONObject caps = new JSONObject();
          caps.put("methods", new JSONObject("{\"GET\":true,\"PUT\":true,\"DELETE\":true,\"MKCOL\":true,"
              + "\"PROPFIND\":true,\"PROPPATCH\":true,\"COPY\":true,\"MOVE\":true,\"LOCK\":true,"
              + "\"UNLOCK\":true,\"HEAD\":true,\"OPTIONS\":true,\"PATCH\":true,\"POST\":true}"));
          caps.put("maxRequestBytes", MAX_REQUEST_BYTES);
          caps.put("maxResponseBytes", MAX_RESPONSE_BYTES);
          caps.put("maxTimeoutMs", MAX_TIMEOUT_MS);
          caps.put("version", org.mozilla.geckoview_example.BuildConfig.VERSION_NAME);
          bridge.respond(id, true, caps, null);
        } catch (Exception e) {
          bridge.respond(id, false, null, "capabilities: " + e.getMessage());
        }
        break;

      case "net.request":
        final NetHttp.Request request;
        try {
          request = toRequest(params);
        } catch (IllegalArgumentException e) {
          bridge.respond(id, false, null, e.getMessage());
          break;
        }
        mPool.execute(() -> run(id, request, bridge));
        break;

      default:
        bridge.respond(id, false, null, "unknown net action: " + action);
    }
  }

  /** 参数校验全在这一层：宁可拒得明白，也别让半截参数进到网络栈。 */
  private NetHttp.Request toRequest(JSONObject params) {
    String method = params.optString("method", "").trim().toUpperCase(Locale.US);
    String url = params.optString("url", "").trim();
    if (method.isEmpty()) {
      throw new IllegalArgumentException("net.request 缺少 method");
    }
    if (url.isEmpty()) {
      throw new IllegalArgumentException("net.request 缺少 url");
    }

    NetHttp.Request req = new NetHttp.Request();
    req.method = method;
    req.url = url;
    req.userAgent = "DatingRecord-Shell/" + org.mozilla.geckoview_example.BuildConfig.VERSION_NAME;

    JSONObject headers = params.optJSONObject("headers");
    if (headers != null) {
      int count = 0;
      for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
        String key = it.next();
        if (count++ > 32) {
          throw new IllegalArgumentException("请求头太多（上限 32 个）");
        }
        Object value = headers.opt(key);
        if (value instanceof String) {
          req.headers.put(key, (String) value);
        }
      }
    }

    String bodyBase64 = params.optString("bodyBase64", "");
    if (!bodyBase64.isEmpty()) {
      byte[] body;
      try {
        // NO_WRAP：网页侧给的 base64 不带换行；DEFAULT 兼容带换行的写法
        body = Base64.decode(bodyBase64, Base64.DEFAULT);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("bodyBase64 不是合法的 base64");
      }
      if (body.length > MAX_REQUEST_BYTES) {
        throw new IllegalArgumentException(
            "请求体超过壳的上限 " + (MAX_REQUEST_BYTES / 1024 / 1024) + " MB（当前 " + (body.length / 1024) + " KB）");
      }
      req.body = body;
    }

    int timeout = params.optInt("timeoutMs", 0);
    if (timeout > 0) {
      int capped = Math.min(timeout, MAX_TIMEOUT_MS);
      req.connectTimeoutMs = Math.min(capped, 15_000);
      req.readTimeoutMs = capped;
    }
    int maxResp = params.optInt("maxResponseBytes", 0);
    if (maxResp > 0) {
      req.maxResponseBytes = Math.min(maxResp, MAX_RESPONSE_BYTES);
    }
    return req;
  }

  private void run(String id, NetHttp.Request request, JsBridge bridge) {
    try {
      NetHttp.Response res = NetHttp.send(request);
      JSONObject out = new JSONObject();
      out.put("status", res.status);
      out.put("statusText", res.reason);
      out.put("bodyLength", res.body.length);
      out.put("bodyBase64", Base64.encodeToString(res.body, Base64.NO_WRAP));
      out.put("headers", new JSONObject(res.headers));
      bridge.respond(id, true, out, null);
      Log.i(LOGTAG, request.method + " " + request.url + " -> " + res.status + " (" + res.body.length + "B)");
    } catch (IOException e) {
      bridge.respond(id, false, null, describe(e));
      Log.w(LOGTAG, request.method + " " + request.url + " failed: " + e);
    } catch (Exception e) {
      bridge.respond(id, false, null, "壳里发请求出错：" + e.getMessage());
      Log.e(LOGTAG, "net.request crashed", e);
    }
  }

  /** 把 Java 的异常翻成人话：页面直接把这串当提示显示，所以别丢一句 NullPointerException 上去。 */
  private static String describe(IOException e) {
    if (NetHttp.isTlsFailure(e)) {
      return "与服务器建立加密连接失败（证书或网络问题）：" + e.getMessage();
    }
    if (NetHttp.isTimeout(e)) {
      return "坚果云响应超时，检查一下网络再试";
    }
    String msg = e.getMessage();
    if (msg == null || msg.isEmpty()) {
      return "请求发不出去：" + e.getClass().getSimpleName();
    }
    return msg;
  }

  /** Activity 销毁时收线程；桥线程是守护线程，不拦退出，这里只做显式关闭。 */
  public void onDestroy() {
    mPool.shutdownNow();
  }
}
