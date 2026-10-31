package org.mozilla.geckoview_example.jsbridge;

/**
 * JVM 自测入口：把 DataShareServer 单独跑起来，由 client.mjs 用真实 WebSocket
 * 和 HTTP 走一遍中继协议。不做任何 Android 调用。
 */
public final class DataShareSelfTestServer {
  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 41799;
    String token = args.length > 1 ? args[1] : "selftest01";
    DataShareServer.get().start(port, token);
    System.out.println("READY " + port + " " + token);
    Thread.sleep(120000);
  }
}
