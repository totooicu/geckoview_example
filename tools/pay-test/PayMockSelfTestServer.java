package org.mozilla.geckoview_example.jsbridge;

/**
 * JVM 自测入口：把 DataShareServer 单独跑起来（含 /__pay/* 路由），
 * 由 client.mjs 用真实 HTTP 走一遍支付协议。不做任何 Android 调用。
 *
 * <p>端口用 417xx 段高位随机端口，避开 4190（WHATWG 禁用端口，node fetch 会报 bad port）。</p>
 */
public final class PayMockSelfTestServer {
  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 41788;
    String token = args.length > 1 ? args[1] : "paytest01";
    DataShareServer server = DataShareServer.get();
    server.start(port, token);
    System.out.println("READY " + port + " " + token);
    // 保持运行 2 分钟（够 client.mjs 跑完）
    Thread.sleep(120000);
  }
}
