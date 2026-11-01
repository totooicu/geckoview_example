package org.mozilla.geckoview_example.jsbridge;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 壳模拟收费服务器：管理一次性服务购买订单。
 *
 * <p>内存态：壳重启后订单全部丢失。这是测试用途，不持久化。</p>
 *
 * <p>协议详见 /app/DatingRecord/docs/PAY_MOCK.md</p>
 */
public final class PayMock {
  private static final PayMock INSTANCE = new PayMock();

  public static PayMock get() {
    return INSTANCE;
  }

  private PayMock() {}

  // 状态机：6 个状态
  public static final String STATUS_PENDING = "pending";
  public static final String STATUS_PAID = "paid";
  public static final String STATUS_FAILED = "failed";
  public static final String STATUS_CANCELLED = "cancelled";
  public static final String STATUS_EXPIRED = "expired";
  public static final String STATUS_REFUNDED = "refunded";

  // 订单存储：orderId -> Order
  private final Map<String, Order> orders = Collections.synchronizedMap(new LinkedHashMap<String, Order>());

  // 订单序号：每天从 0001 开始
  private int dailySeq = 0;
  private String currentDay = "";

  /** 订单结构 */
  public static final class Order {
    public final String orderId;
    public final String serviceId;
    public final String serviceTitle;
    public final long amountCents;
    public final String returnUrl;
    public final String channel; // 下单时锁定的渠道，可空
    public String status;
    public final long createdAt;
    public final long expireAt;
    public Long paidAt;
    public String tradeNo;
    public String paidChannel;
    public int notifyCount; // 异步回调计数

    public Order(String orderId, String serviceId, String serviceTitle, long amountCents,
                 String returnUrl, String channel, long expireAt) {
      this.orderId = orderId;
      this.serviceId = serviceId;
      this.serviceTitle = serviceTitle;
      this.amountCents = amountCents;
      this.returnUrl = returnUrl;
      this.channel = channel;
      this.status = STATUS_PENDING;
      this.createdAt = System.currentTimeMillis();
      this.expireAt = expireAt;
      this.notifyCount = 0;
    }

    public String toJson() {
      StringBuilder b = new StringBuilder();
      b.append("{");
      b.append("\"orderId\":").append(q(orderId));
      b.append(",\"status\":").append(q(status));
      b.append(",\"amountCents\":").append(amountCents);
      b.append(",\"currency\":\"CNY\"");
      b.append(",\"serviceId\":").append(q(serviceId));
      b.append(",\"serviceTitle\":").append(q(serviceTitle));
      if (returnUrl != null) b.append(",\"returnUrl\":").append(q(returnUrl));
      if (channel != null) b.append(",\"channel\":").append(q(channel));
      b.append(",\"createdAt\":").append(createdAt);
      b.append(",\"expireAt\":").append(expireAt);
      if (paidAt != null) b.append(",\"paidAt\":").append(paidAt);
      if (tradeNo != null) b.append(",\"tradeNo\":").append(q(tradeNo));
      if (paidChannel != null) b.append(",\"channel\":").append(q(paidChannel));
      b.append(",\"notifyCount\":").append(notifyCount);
      b.append("}");
      return b.toString();
    }

    /** 下单响应 JSON（带 channels 数组） */
    public String toCreateJson() {
      StringBuilder b = new StringBuilder();
      b.append("{");
      b.append("\"orderId\":").append(q(orderId));
      b.append(",\"status\":").append(q(status));
      b.append(",\"amountCents\":").append(amountCents);
      b.append(",\"currency\":\"CNY\"");
      b.append(",\"expireAt\":").append(expireAt);
      b.append(",\"channels\":[");
      // 支付宝
      b.append("{\"kind\":\"alipay\",\"label\":\"支付宝\"");
      b.append(",\"scheme\":").append(q(alipayScheme(orderId)));
      b.append(",\"webUrl\":").append(q(cashierUrl(orderId, "alipay")));
      b.append("}");
      b.append(",");
      // 微信
      b.append("{\"kind\":\"wechat\",\"label\":\"微信\"");
      b.append(",\"scheme\":").append(q(wechatScheme(orderId)));
      b.append(",\"webUrl\":").append(q(cashierUrl(orderId, "wechat")));
      b.append("}");
      b.append("]");
      b.append("}");
      return b.toString();
    }

    private static String alipayScheme(String orderId) {
      return "alipays://platformapi/startapp?appId=099999&orderVo=PAYMOCK-" + orderId;
    }

    private static String wechatScheme(String orderId) {
      return "weixin://app/paymock/pay?prepayId=PAYMOCK-" + orderId;
    }

    private static String cashierUrl(String orderId, String channel) {
      return "/__pay/cashier?order=" + orderId + "&channel=" + channel;
    }

    private static String q(String s) {
      return s == null ? "null" : "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
      StringBuilder b = new StringBuilder(s.length());
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"': b.append("\\\""); break;
          case '\\': b.append("\\\\"); break;
          case '\n': b.append("\\n"); break;
          case '\r': b.append("\\r"); break;
          case '\t': b.append("\\t"); break;
          default:
            if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
      }
      return b.toString();
    }
  }

  /** 下单：同一 serviceId + 同金额且 pending 的重复下单返回同一张单 */
  public Order createOrder(String serviceId, String serviceTitle, long amountCents,
                           String returnUrl, String channel) {
    // 先找：同一 serviceId + 同金额 + pending 的订单
    synchronized (orders) {
      for (Order o : orders.values()) {
        if (o.serviceId.equals(serviceId)
            && o.amountCents == amountCents
            && STATUS_PENDING.equals(o.status)) {
          return o;
        }
      }
      // 新建
      String orderId = nextOrderId();
      long expireAt = System.currentTimeMillis() + 30 * 60 * 1000; // 30 分钟
      Order o = new Order(orderId, serviceId, serviceTitle, amountCents, returnUrl, channel, expireAt);
      orders.put(orderId, o);
      return o;
    }
  }

  /** 生成订单号：PAY-YYYYMMDD-0001 */
  private String nextOrderId() {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
    sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    String day = sdf.format(new Date());
    if (!day.equals(currentDay)) {
      currentDay = day;
      dailySeq = 0;
    }
    dailySeq++;
    return String.format(Locale.ROOT, "PAY-%s-%04d", day, dailySeq);
  }

  /** 查单 */
  public Order getOrder(String orderId) {
    return orders.get(orderId);
  }

  /** 列全部订单（按下单时间升序） */
  public List<Order> listOrders() {
    synchronized (orders) {
      return new ArrayList<>(orders.values());
    }
  }

  /** 手动驱动状态转移 */
  public ActionResult doAction(String orderId, String action) {
    Order o = orders.get(orderId);
    if (o == null) {
      return new ActionResult(404, "{\"error\":\"order_not_found\"}");
    }

    synchronized (o) {
      String cur = o.status;

      // 终态不可逆检查
      if (STATUS_PAID.equals(cur)) {
        if ("pay".equals(action)) {
          // 幂等：返回同一个 tradeNo
          return new ActionResult(200, paidResponseJson(o));
        }
        if ("cancel".equals(action) || "fail".equals(action) || "expire".equals(action)) {
          return new ActionResult(409, "{\"error\":\"bad_transition\"}");
        }
        if ("refund".equals(action)) {
          o.status = STATUS_REFUNDED;
          return new ActionResult(200, o.toJson());
        }
      }

      if (STATUS_REFUNDED.equals(cur)) {
        return new ActionResult(409, "{\"error\":\"bad_transition\"}");
      }
      if (STATUS_CANCELLED.equals(cur) || STATUS_EXPIRED.equals(cur) || STATUS_FAILED.equals(cur)) {
        return new ActionResult(409, "{\"error\":\"bad_transition\"}");
      }

      // pending -> 其他
      if (STATUS_PENDING.equals(cur)) {
        if ("pay".equals(action)) {
          // 检查过期
          if (System.currentTimeMillis() > o.expireAt) {
            o.status = STATUS_EXPIRED;
            return new ActionResult(409, "{\"error\":\"order_expired\"}");
          }
          o.status = STATUS_PAID;
          o.paidAt = System.currentTimeMillis();
          o.tradeNo = generateTradeNo(o.orderId);
          o.paidChannel = o.channel != null ? o.channel : "alipay";
          return new ActionResult(200, paidResponseJson(o));
        }
        if ("fail".equals(action)) {
          o.status = STATUS_FAILED;
          return new ActionResult(200, o.toJson());
        }
        if ("cancel".equals(action)) {
          o.status = STATUS_CANCELLED;
          return new ActionResult(200, o.toJson());
        }
        if ("expire".equals(action)) {
          o.status = STATUS_EXPIRED;
          return new ActionResult(200, o.toJson());
        }
        if ("refund".equals(action)) {
          // pending 不能 refund
          return new ActionResult(409, "{\"error\":\"bad_transition\"}");
        }
      }

      return new ActionResult(400, "{\"error\":\"unknown_action\"}");
    }
  }

  private String paidResponseJson(Order o) {
    StringBuilder b = new StringBuilder();
    b.append("{");
    b.append("\"orderId\":").append(q(o.orderId));
    b.append(",\"status\":\"paid\"");
    b.append(",\"amountCents\":").append(o.amountCents);
    b.append(",\"paidAt\":").append(o.paidAt);
    b.append(",\"tradeNo\":").append(q(o.tradeNo));
    b.append(",\"channel\":").append(q(o.paidChannel));
    b.append(",\"expireAt\":").append(o.expireAt);
    b.append("}");
    return b.toString();
  }

  private String generateTradeNo(String orderId) {
    // MOCK + 日期 + 序号
    return "MOCK" + orderId.replace("PAY-", "").replace("-", "");
  }

  /** 模拟异步回调 */
  public ActionResult doNotify(String orderId, int times) {
    Order o = orders.get(orderId);
    if (o == null) {
      return new ActionResult(404, "{\"error\":\"order_not_found\"}");
    }
    synchronized (o) {
      o.notifyCount += times;
      StringBuilder b = new StringBuilder();
      b.append("{\"ok\":true");
      b.append(",\"orderId\":").append(q(o.orderId));
      b.append(",\"status\":").append(q(o.status));
      b.append(",\"notifyCount\":").append(o.notifyCount);
      b.append("}");
      return new ActionResult(200, b.toString());
    }
  }

  /** 生成收银台 HTML */
  public String cashierHtml(String orderId, String channel) {
    Order o = orders.get(orderId);
    if (o == null) {
      return "<!doctype html><meta charset=utf-8><title>订单不存在</title>"
          + "<body style=\"font:15px/1.6 system-ui;padding:24px\">"
          + "<h3>订单不存在</h3><p>orderId=" + escapeHtml(orderId) + "</p></body>";
    }

    String channelLabel = "alipay".equals(channel) ? "支付宝" : "微信";
    String returnUrl = o.returnUrl != null ? o.returnUrl : "/";
    double amountYuan = o.amountCents / 100.0;

    StringBuilder html = new StringBuilder();
    html.append("<!doctype html>\n");
    html.append("<html lang=zh-CN>\n");
    html.append("<head>\n");
    html.append("<meta charset=utf-8>\n");
    html.append("<meta name=viewport content=\"width=device-width,initial-scale=1\">\n");
    html.append("<title>收银台 · ").append(escapeHtml(o.serviceTitle)).append("</title>\n");
    html.append("<style>\n");
    html.append("body{font:15px/1.6 system-ui;padding:24px;max-width:480px;margin:auto}\n");
    html.append(".card{border:1px solid #ddd;border-radius:8px;padding:16px;margin:16px 0}\n");
    html.append(".amount{font-size:24px;font-weight:bold;color:#333}\n");
    html.append(".btn{display:block;width:100%;padding:12px;margin:8px 0;border:none;border-radius:6px;");
    html.append("font-size:16px;cursor:pointer}\n");
    html.append(".btn-pay{background:#1677ff;color:#fff}\n");
    html.append(".btn-fail{background:#ff4d4f;color:#fff}\n");
    html.append(".btn-cancel{background:#f0f0f0;color:#333}\n");
    html.append("</style>\n");
    html.append("</head>\n");
    html.append("<body>\n");
    html.append("<h2>收银台</h2>\n");
    html.append("<div class=card>\n");
    html.append("<div>订单号：").append(escapeHtml(o.orderId)).append("</div>\n");
    html.append("<div>服务：").append(escapeHtml(o.serviceTitle)).append("</div>\n");
    html.append("<div>渠道：").append(channelLabel).append("</div>\n");
    html.append("<div class=amount>¥ ").append(String.format(Locale.ROOT, "%.2f", amountYuan)).append("</div>\n");
    html.append("</div>\n");
    html.append("<button class=\"btn btn-pay\" onclick=\"doAction('pay')\">确认支付</button>\n");
    html.append("<button class=\"btn btn-fail\" onclick=\"doAction('fail')\">支付失败</button>\n");
    html.append("<button class=\"btn btn-cancel\" onclick=\"doAction('cancel')\">取消</button>\n");
    html.append("<script>\n");
    html.append("function doAction(action){\n");
    html.append("  fetch('/__pay/order?id=").append(escapeJs(o.orderId)).append("&action='+action,{method:'POST'})\n");
    html.append("    .then(r=>r.json())\n");
    html.append("    .then(d=>{\n");
    html.append("      var status=d.status||'unknown';\n");
    html.append("      var ret=").append(qJson(escapeJs(returnUrl))).append(";\n");
    html.append("      var sep=ret.indexOf('?')>=0?'&':'?';\n");
    html.append("      location.href=ret+sep+'orderId=").append(escapeJs(o.orderId)).append("&status='+status;\n");
    html.append("    })\n");
    html.append("    .catch(e=>alert('请求失败：'+e));\n");
    html.append("}\n");
    html.append("</script>\n");
    html.append("</body>\n");
    html.append("</html>\n");
    return html.toString();
  }

  private static String q(String s) {
    return s == null ? "null" : "\"" + escapeJsonStr(s) + "\"";
  }

  private static String qJson(String s) {
    return "\"" + escapeJsonStr(s) + "\"";
  }

  private static String escapeJsonStr(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else b.append(c);
      }
    }
    return b.toString();
  }

  private static String escapeHtml(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }

  private static String escapeJs(String s) {
    if (s == null) return "";
    // 防 </script> 突破：把 < 转成 \x3c
    return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        .replace("<", "\\x3c").replace(">", "\\x3e");
  }

  /** 操作结果 */
  public static final class ActionResult {
    public final int status;
    public final String json;

    public ActionResult(int status, String json) {
      this.status = status;
      this.json = json;
    }
  }
}
