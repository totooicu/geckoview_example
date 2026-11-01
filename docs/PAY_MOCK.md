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
3. `node client.mjs` 走完整协议（100 项断言，含 12 项源码级检查：5 项「壳加载地址」+ 7 项「取包 CI」）

`javac` 只编这三个纯 `java.*` 的文件，**编不了 `WebViewActivity.java`**（要 Android SDK）。
那道门禁现在由 CI 补上：推分支自动跑 `assembleDebug`，产物挂在 Actions 的 Artifacts 上、
手机浏览器直接下载，取包步骤与边界见 `docs/CI_GUIDE.md`。

> 实测：run `41286281262`（提交 `a713e67`）绿，Artifacts 里 3.4 MB 的 `app-debug.apk`。
> 这条改动动过的 `WebViewActivity.java`（加载地址、退路、`onReceivedError`、scheme 拦截）**第一次真编过**——
> 之前它只在沙箱里做过语法探测，从没链接过 Android SDK。

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

## 6b. 壳加载哪份网页

main 那条线加载站点根路径（跟随网页最新部署）。**这条测试线不一样**：支付入口只活在网页仓库的
`test/payment-recharge` 上，它的产物被发到 gh-pages 的子路径，而壳从**自己那台局域网服务**上把它
读出来：

```
壳的加载地址 = http://127.0.0.1:4173/pay-test/
             （= DataShareServer 反代 https://totooicu.github.io/DatingRecord/pay-test/）
```

**为什么不直接写 https 那份**：收费的 `/__pay/*` 就在本机回环上，而浏览器把「公网 https 源 → 本机
回环」当敏感访问拦掉了。实测（Chromium）报的是
`Permission was denied for this request to access the loopback address`，给预检补
`Access-Control-Allow-Private-Network: true` 也不够——那条头只是必要条件之一。页面改成与
`/__pay/*` 同源，这两个限制都不再触发；39 项跨仓回归正是打在 `http://127.0.0.1:4173/pay-test/`
上跑绿的。

**代价：这台服务必须先开着。** `127.0.0.1:4173` 就是「跨设备操作」那个局域网服务，端口能改，
地址是写死的——所以 `tools/pay-test/client.mjs` 里钉了一条「加载地址的端口 ==
`DataShareServer.DEFAULT_PORT`」。服务没开时这一跳必然连接失败，`onReceivedError` 会退回
`FALLBACK_URL`（线上根路径那份），人在里面把服务打开、再重进壳，就回到测试线；不退回的话
开局就是白屏，连那颗「开启」按钮都够不着。

域名锁 `ALLOWED_HOST` 原来只认 `totooicu.github.io`，这条线上多放行 `127.0.0.1` 一个字面量
（`isAllowedHost()`，导航锁和壳自己的 `loadUrl()` 两处都走它，漏一处页面就加载不了）。
**这两处改动连同加载地址都不许合进 main。**

网页那份产物是 `base: './'` + 相对路径注册 SW，所以子路径只认自己这一份，不抢站点根的作用域。
**它是临时的**：网页仓库的 `deploy-pages.yml` 每次部署只保留 `assets/` 与 `releases/`，
其余根目录内容全删——下次推 main 这个子目录就没了，要重发。

## 7. 限制

- 内存态，壳重启订单全丢
- 没有真钱，scheme 参数是假的
- 不做签名校验（那是接真商户号时才值钱的东西）
- 不做自动续费 / 余额 / 扣次数
- scheme 拦截未真机验证
