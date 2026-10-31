package org.mozilla.geckoview_example.jsbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * {@link NetHttp} 的自测：对着同目录的假坚果云（server.mjs）把 WebDAV 会用到的每一种
 * 请求与响应都走一遍，再顺带把校验类的错误路径全打一遍。
 *
 * <p>为什么能在桌面上跑：{@code NetHttp.java} 只依赖 {@code java.*}，不碰任何 Android API，
 * 所以壳里那一行 HTTP 代码在这里就是同一份编译产物。桥外层（base64、线程、respond）
 * 要真机才验得到，见 {@code docs/ADAPTATION_GUIDE.md} 的 net.* 一节。</p>
 *
 * <p>用法（先起服务）：{@code node server.mjs} 然后 {@code ./run.sh}；
 * 加 {@code --live} 会额外向真实的 dav.jianguoyun.com 发一次无凭据 PROPFIND，
 * 用来验 TLS + SNI + 非标准动词真的能到坚果云。</p>
 */
public final class NetHttpSelfTest {

  private static int passed = 0;
  private static int failed = 0;
  private static String base = "http://127.0.0.1:8078";

  private NetHttpSelfTest() {}

  public static void main(String[] args) throws Exception {
    boolean live = false;
    for (String a : args) {
      if (a.equals("--live")) {
        live = true;
      } else if (a.startsWith("--base=")) {
        base = a.substring("--base=".length());
      }
    }
    String auth = basicAuth("tester@example.com", "app-pass-123");

    mkcolAndList(auth);
    putGetRoundtrip(auth);
    headAndNoLength(auth);
    deleteAndMissing(auth);
    oversizedResponse();
    requestValidation(auth);
    lastRequestShape(auth);
    if (live) {
      liveNutstore();
    }

    System.out.println();
    System.out.println(failed == 0
        ? "PASS: NetHttp 全部断言通过（" + passed + " 项）"
        : "FAIL: " + failed + " 项未通过 / " + (passed + failed) + " 项");
    if (failed > 0) {
      System.exit(1);
    }
  }

  /* ------------------------------------------------------------ 正向 */

  private static void mkcolAndList(String auth) throws Exception {
    NetHttp.Response created = send("MKCOL", base + "/dav/DatingRecord", auth, null, null);
    check("MKCOL 新建目录 → 201", created.status == 201, "实际 " + created.status);

    NetHttp.Response again = send("MKCOL", base + "/dav/DatingRecord", auth, null, null);
    check("MKCOL 已存在 → 405（页面按成功处理）", again.status == 405, "实际 " + again.status);

    // PROPFIND 是 WebDAV 的心脏：动词发不出去，整个同步就无从谈起
    String body =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>";
    Map<String, String> ph = new LinkedHashMap<>();
    ph.put("Depth", "1");
    ph.put("Content-Type", "application/xml;charset=utf-8");
    NetHttp.Response prop = sendBytes("PROPFIND", base + "/dav/DatingRecord", auth, ph, body.getBytes(StandardCharsets.UTF_8), null);
    check("PROPFIND → 207", prop.status == 207, "实际 " + prop.status);
    check("PROPFIND 响应体经 chunked 完整收到", new String(prop.body, StandardCharsets.UTF_8).contains("<d:multistatus"),
        "长度 " + prop.body.length);
    check("响应头小写化后可读", "application/xml;charset=utf-8".equals(prop.headers.get("content-type")),
        String.valueOf(prop.headers.get("content-type")));
  }

  private static void putGetRoundtrip(String auth) throws Exception {
    // 64KB + 随机字节：和真实分片大小一致，能同时验 Content-Length 写体与定长读体
    byte[] payload = new byte[64 * 1024];
    new Random(20261029L).nextBytes(payload);
    payload[0] = 0;
    payload[64 * 1024 - 1] = 127;
    Map<String, String> ph = new LinkedHashMap<>();
    ph.put("Content-Type", "application/octet-stream");
    NetHttp.Response put = sendBytes("PUT", base + "/dav/DatingRecord/chunks/abc.bin", auth, ph, payload, null);
    check("PUT 分片 → 201", put.status == 201, "实际 " + put.status);
    check("服务端带 etag", put.headers.get("etag") != null, String.valueOf(put.headers.get("etag")));

    NetHttp.Response got = send("GET", base + "/dav/DatingRecord/chunks/abc.bin", auth, null, null);
    check("GET 回来状态 200", got.status == 200, "实际 " + got.status);
    check("GET 回来的字节与 PUT 完全一致（64KB 逐字节）", java.util.Arrays.equals(got.body, payload),
        "长度 " + got.body.length + " / 应为 " + payload.length);

    // 空分片：WebDAV 允许 0 字节对象，别在写体那一步把它当成「没有请求体」
    NetHttp.Response emptyPut = sendBytes("PUT", base + "/dav/DatingRecord/chunks/empty.bin", auth, ph, new byte[0], null);
    check("PUT 空分片 → 201", emptyPut.status == 201, "实际 " + emptyPut.status);
    NetHttp.Response emptyGet = send("GET", base + "/dav/DatingRecord/chunks/empty.bin", auth, null, null);
    check("GET 空分片 → 200 且零字节", emptyGet.status == 200 && emptyGet.body.length == 0,
        "状态 " + emptyGet.status + " 长度 " + emptyGet.body.length);
  }

  private static void headAndNoLength(String auth) throws Exception {
    NetHttp.Response head = send("HEAD", base + "/dav/DatingRecord/chunks/abc.bin", auth, null, null);
    check("HEAD 不被 Content-Length 骗去多读（声明 4096 也不该等）", head.status == 200 && head.body.length == 0,
        "长度 " + head.body.length);

    NetHttp.Response noLen = send("GET", base + "/__no-length", auth, null, null);
    check("无 Content-Length 的响应读到连接关闭", noLen.status == 200
            && new String(noLen.body, StandardCharsets.UTF_8).equals("读到连接关闭为止"),
        new String(noLen.body, StandardCharsets.UTF_8));
  }

  private static void deleteAndMissing(String auth) throws Exception {
    NetHttp.Response del = send("DELETE", base + "/dav/DatingRecord/chunks/empty.bin", auth, null, null);
    check("DELETE → 204 且无实体", del.status == 204 && del.body.length == 0, "状态 " + del.status);

    NetHttp.Response gone = send("GET", base + "/dav/DatingRecord/chunks/empty.bin", auth, null, null);
    check("删除后再 GET → 404 带可读正文", gone.status == 404 && gone.body.length > 0, "状态 " + gone.status);
  }

  /* ------------------------------------------------------------ 护栏 */

  private static void oversizedResponse() {
    NetHttp.Request r = new NetHttp.Request();
    r.method = "GET";
    r.url = base + "/__giant";
    r.headers.put("Authorization", basicAuth("tester@example.com", "app-pass-123"));
    r.maxResponseBytes = 64 * 1024;
    String msg = errorOf(r);
    check("超过 maxResponseBytes 直接报错而不是撑内存", msg != null && msg.contains("超过上限"), String.valueOf(msg));
  }

  private static void requestValidation(String auth) {
    check("非白名单动词被拒（FOO）",
        errorText("FOO", base + "/dav/DatingRecord", auth).contains("不支持的 HTTP 方法"), "");
    check("file:// 被拒（不暴露 App 私有目录）",
        errorOf("file:///android_asset/x", "GET").contains("只允许 http/https"), "");
    check("URL 里的 userinfo 被拒（认证只走 Authorization 头）",
        errorOf("http://u:p@127.0.0.1:8078/dav/", "GET").contains("不要带用户名密码"), "");
    String injected = errorWithHeader(base + "/dav/DatingRecord", auth, "X-Evil", "a\r\nGET /__evil HTTP/1.1");
    check("请求头里的换行被拒（挡下请求走私）", injected.contains("有换行"), injected);
    check("空地址被拒", errorOf("", "GET").contains("缺少请求地址"), "");
  }

  /** 请求行的形状：方法、原始路径、非默认端口的 Host 头，都由假服务端回读确认。 */
  private static void lastRequestShape(String auth) throws Exception {
    send("MKCOL", base + "/dav/DatingRecord/%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95", auth, null, null);
    NetHttp.Response last = send("GET", base + "/__last", auth, null, null);
    String json = new String(last.body, StandardCharsets.UTF_8);
    check("非默认端口写进 Host 头", json.contains("\"host\":\"127.0.0.1:8078\""), json);
    check("百分号编码的路径原样送达（中文目录名靠它）",
        json.contains("%E4%B8%AD%E6%96%87%20%E7%9B%AE%E5%BD%95"), json);

    // 认证头透传：错的密码必须让服务端回 401，而不是被壳悄悄改掉
    NetHttp.Response wrong = send("GET", base + "/dav/DatingRecord/abc.bin", basicAuth("tester@example.com", "wrong"), null, null);
    check("错误应用密码 → 401 交给页面提示", wrong.status == 401, "实际 " + wrong.status);
  }

  /* ------------------------------------------------------------ 真机前置 */

  /** 无凭据打一次真实坚果云：能拿到 4xx 就说明 TLS、SNI 与 PROPFIND 这条路是通的。 */
  private static void liveNutstore() {
    NetHttp.Request r = new NetHttp.Request();
    r.method = "PROPFIND";
    r.url = "https://dav.jianguoyun.com/dav/";
    r.headers.put("Depth", "0");
    r.headers.put("Content-Type", "application/xml;charset=utf-8");
    r.body =
        ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>")
            .getBytes(StandardCharsets.UTF_8);
    r.maxResponseBytes = 256 * 1024;
    r.connectTimeoutMs = 10_000;
    r.readTimeoutMs = 15_000;
    try {
      NetHttp.Response res = NetHttp.send(r);
      check("真实坚果云 PROPFIND 有回应（无凭据也应有状态码）", res.status > 0, "状态 " + res.status);
      System.out.println("  live 坚果云状态码：" + res.status + " " + res.reason);
    } catch (IOException e) {
      System.out.println("  live 项跳过：本机连不到 dav.jianguoyun.com（" + e.getMessage() + "）");
    }
  }

  /* ------------------------------------------------------------ 工具 */

  private static NetHttp.Response send(String method, String url, String auth, Map<String, String> headers, byte[] body)
      throws IOException {
    return sendBytes(method, url, auth, headers, body, null);
  }

  private static NetHttp.Response sendBytes(
      String method, String url, String auth, Map<String, String> headers, byte[] body, Integer maxBytes)
      throws IOException {
    NetHttp.Request r = new NetHttp.Request();
    r.method = method;
    r.url = url;
    r.headers.put("Authorization", auth);
    if (headers != null) {
      r.headers.putAll(headers);
    }
    r.body = body;
    if (maxBytes != null) {
      r.maxResponseBytes = maxBytes;
    }
    return NetHttp.send(r);
  }

  private static String errorText(String method, String url, String auth) {
    NetHttp.Request r = new NetHttp.Request();
    r.method = method;
    r.url = url;
    r.headers.put("Authorization", auth);
    return errorOf(r);
  }

  private static String errorOf(NetHttp.Request r) {
    try {
      NetHttp.send(r);
      return null;
    } catch (IOException e) {
      return String.valueOf(e.getMessage());
    }
  }

  private static String errorOf(String url, String method) {
    NetHttp.Request r = new NetHttp.Request();
    r.method = method;
    r.url = url;
    return errorOf(r);
  }

  private static String errorWithHeader(String url, String auth, String key, String value) {
    NetHttp.Request r = new NetHttp.Request();
    r.method = "GET";
    r.url = url;
    r.headers.put("Authorization", auth);
    r.headers.put(key, value);
    String msg = errorOf(r);
    return msg == null ? "" : msg;
  }

  private static String basicAuth(String user, String pass) {
    return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
  }

  private static void check(String name, boolean ok, String detail) {
    if (ok) {
      passed++;
      System.out.println("  ✓ " + name);
    } else {
      failed++;
      System.out.println("  ✗ " + name + (detail == null || detail.isEmpty() ? "" : " — " + detail));
    }
  }
}
