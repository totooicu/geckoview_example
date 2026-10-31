# `net.*` 原生网络代理（`NetBridge` + `NetHttp`）

这条能力是从坚果云测试线（`origin/test/nutstore` 的 `e26dfad`）验好后**间接并进 main** 的，
测试线本身保留不动。网页侧的封装在 DatingRecord `src/core/sync/nativeHttp.ts`。

## 为什么要有它

坚果云的 WebDAV 不返回跨域头，网页里的 `fetch` 一定被浏览器拦下；`PROPFIND` / `MKCOL`
这些非标准动词连预检都过不去。壳在系统网络栈上发请求，不受 CORS 约束，于是把
「发一次 HTTP 请求」这一件事交给原生——**同步策略、冲突处理、凭据存放全部留在网页里**，
壳只承载，不做业务判断。

## 契约

| action | 参数 | 返回 | 说明 |
|---|---|---|---|
| `net.capabilities` | — | `{methods, maxRequestBytes, maxResponseBytes, maxTimeoutMs, version}` | 探测用。网页想知道壳有没有这能力，就发一次看有没有人回；不读 UA、不猜环境 |
| `net.request` | `{method, url, headers?, bodyBase64?, timeoutMs?, maxResponseBytes?}` | `{status, statusText, headers, bodyBase64, bodyLength}` | 发一次 HTTP，响应**原样交回**，壳不判断成败 |

`net.request` 的规矩（都在代码里，改行为必须同时改这里）：

- **动词白名单**：`GET HEAD OPTIONS PUT DELETE TRACE CONNECT PATCH POST`
  + WebDAV 的 `PROPFIND PROPPATCH MKCOL COPY MOVE LOCK UNLOCK`，其它一律拒。
- **地址只允许 http/https**：`file://`、`content://` 直接拒绝——否则网页就能借壳读 App 私有目录。
  URL 里带 `user:pass@` 也拒，认证只走 `Authorization` 头。
- 请求头里的换行被拒（挡下请求走私）；头数量上限 32 个。
- `Host` / `Content-Length` / `Connection` / `Transfer-Encoding` 由壳接管，页面传了会被忽略。
- 请求体与响应体一律 **base64**：请求体按 `Base64.DEFAULT` 解（兼容带换行），
  响应体按 `NO_WRAP` 编（一行到底、带 padding，网页 `atob` 正好解）。
- 上限：请求体 12MB、响应体 32MB（可用 `maxResponseBytes` 调小）、单次等待 120s
  （连接超时另按 ≤15s 截）。超过响应上限是**报错**，不是悄悄截断。
- **不自动跟随 3xx**：WebDAV 的 PUT/MKCOL 一旦被重定向语义就变了，
  3xx 连同 `Location` 头原样交回页面决定。
- 出错时 `ok:false`，`error` 串已经是给用户看的话（超时 / 加密连接失败 / 地址不合法…），
  页面直接显示，不再翻译一遍。
- 协议只增动作、不删不改既有参数语义，所以 `getEnv()` 里没有 `proto` 也照样兼容。

## 代码分两层，改动时别混

- `NetHttp.java`：纯 `java.*` 的 HTTP/1.1 客户端（自己写请求行、自己收响应）。
  不用 `HttpURLConnection` 是因为 Android 的实现只认 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，
  发 `PROPFIND` 直接抛 `ProtocolException`，反射改私有字段在 ART 上属于隐藏接口、随时会失效。
  **这一层可以在桌面上真跑**：`tools/net-http-test/`（假坚果云 + 24 项断言）。
- `NetBridge.java`：只做 base64、线程池（两条 daemon 线程）、应答格式。
  沙箱没有 Android SDK，这一层在桌面上**只编译不运行**（`run.sh` 第 0 步拿
  `tools/file-write-test/stubs/` 编一遍），行为要靠真机或网页侧验。

## 真机上还要看什么

- 网页侧 `nativeCaps()` 探测要求 `methods.PROPFIND` 与 `methods.MKCOL` 都为 true 才算
  「这条通道可用」，光有人回 `net.capabilities` 不够。
- 明文 `http://` 能不能发，受 `app/src/main/res/xml/network_security_config.xml` 管；
  坚果云是 https，正常路径不涉及。
- 现场验这条路，走网页本体（`nativeHttp.ts` 只在这条通道上发同步请求）就够了。
  要按钮级的现场工具，在测试线：`origin/test/nutstore` 的 `assets/test.html` 有
  「网络代理 · WebDAV（net.*）」三个按钮（探测能力 / 不带凭据 PROPFIND / 带凭据 PROPFIND），
  配那条线的调试页入口（`--es page test.html`）打开。**main 线上没带这三个按钮**——
  那个测试页要填坚果云邮箱与应用密码，属于测试线的东西，没跟着并进 main。
