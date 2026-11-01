# 壳模拟收费服务器 · 开发文档

> 对应分支：`test/payment-recharge`（不进 main）
> 协议权威：`/app/DatingRecord/docs/PAY_MOCK.md`

---

## 1. 功能与边界

**没有真钱。** 壳在局域网 HTTP 服务（`DataShareServer`，默认端口 4173）上加了一组
`/__pay/*` 路由，模拟一次性服务购买的完整流程——包括支付宝 / 微信的 scheme 跳转。

- 订单状态放内存（`PayMock.java`），**壳重启就没了**。
- scheme 链接（`alipays://`、`weixin://`）的参数是假的，真 app 打开只会报「订单不存在」。
- 将来接商户号，只换服务器签名那一段，页面一行都不用改。

## 2. 文件清单

| 文件 | 职责 |
|---|---|
| `jsbridge/PayMock.java` | 订单状态机、收银台 HTML 生成、scheme 串构造 |
| `jsbridge/DataShareServer.java` | 新增 `handlePay()` / `writePayJson()`，把 `/__pay/*` 接进请求分发 |
| `WebViewActivity.java` | `shouldOverrideUrlLoading` 里加 scheme 拦截分支 |
| `AndroidManifest.xml` | 加 `<queries>` 声明支付宝 / 微信包名（Android 11+ 必需） |
| `tools/pay-test/` | 回归工具：`run.sh` + `client.mjs` + `PayMockSelfTestServer.java` |

## 3. 路由一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/__pay/order` | 下单（body: `{service, returnUrl?, channel?}`） |
| GET | `/__pay/order?id=` | 查单 |
| POST | `/__pay/order?id=&action=` | 驱动状态（pay/fail/cancel/expire/refund） |
| POST | `/__pay/notify?id=&times=N` | 模拟异步回调 |
| GET | `/__pay/cashier?order=&channel=` | 假收银台 HTML |
| GET | `/__pay/orders` | 列全部订单 |

## 4. 状态机

```
pending ──pay──→ paid ──refund──→ refunded
  │                                  ↑
  ├──fail──→ failed                   │
  ├──cancel──→ cancelled              │
  └──expire──→ expired                │
                                     │
            paid ──────refund─────────┘
```

终态（paid/failed/cancelled/expired/refunded）不可逆。paid 后重复 action=pay 返回同一个
tradeNo（幂等）。

## 5. Scheme 拦截

`WebViewActivity.shouldOverrideUrlLoading` 里识别 `alipays` / `alipay` / `weixin` 三个 scheme：

1. 用 `packageManager.getLaunchIntentForPackage()` 判装没装对应 app；
2. 装了 → `Intent(ACTION_VIEW)` 交给系统拉起，WebView 不跟着走；
3. 没装 → 不白屏，跳壳自己的假收银台（`http://127.0.0.1:4173/__pay/cashier?...`），
   并 Toast 提示「没检测到支付宝 / 微信，已改用网页收银台」。

**Android 11+ 包可见性：** `AndroidManifest.xml` 必须声明 `<queries>` 包含
`com.eg.android.AlipayGphone` 和 `com.tencent.mm`，否则 `getLaunchIntentForPackage`
恒返回 null。这条写进了 manifest 注释里。

> **未真机验证：** 沙箱里没有设备也没有 adb，scheme 拦截这部分只做了代码实现，
> 没有在真机上跑过。实现尽量克制——只在已有的 `shouldOverrideUrlLoading` 回调里加分支。

## 6. 回归

```bash
cd tools/pay-test && ./run.sh
```

依赖：JDK 8+、Node 18+。脚本会：
1. `javac` 编译 `PayMock.java` + `DataShareServer.java` + `PayMockSelfTestServer.java`
2. 起 HTTP 服务端（端口 41788）
3. `node client.mjs` 走完整协议（78 项断言）

**网页侧跨仓复用启动命令：**

```bash
# 在 geckoview_example 仓库根目录下
# 最小编译集合：PayMock.java + DataShareServer.java + 入口类（纯 java.*，不依赖 Android API）
javac -encoding UTF-8 -d /tmp/paymock-classes \
  app/src/main/java/org/mozilla/geckoview_example/jsbridge/PayMock.java \
  app/src/main/java/org/mozilla/geckoview_example/jsbridge/DataShareServer.java \
  tools/pay-test/PayMockSelfTestServer.java

java -cp /tmp/paymock-classes \
  org.mozilla.geckoview_example.jsbridge.PayMockSelfTestServer 41788 paytest01
```

依赖链：`PayMock.java` 和 `DataShareServer.java` 都只用 `java.*`，不依赖 Android API，
可以一把 `javac` 过。`PayMockSelfTestServer.java` 是入口，调 `DataShareServer.get().start(port, token)`。

端口用 417xx 段，**避开 4190**（WHATWG 禁用端口，node fetch 会直接报 bad port）。

## 7. 限制

- 内存态，壳重启订单全丢
- 没有真钱，scheme 参数是假的
- 不做签名校验（那是接真商户号时才值钱的东西）
- 不做自动续费 / 余额 / 扣次数
- scheme 拦截未真机验证
