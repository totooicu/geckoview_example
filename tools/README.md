# 壳侧工具索引

规矩与网页仓库一致：**写过的工具不许删**。被取代就地标 `@deprecated`，
真要删除必须在提交信息写明理由，并在本表留一行「已移除：X（原因）」。

| 目录 / 文件 | 用途 | 入口命令 | 依赖 | 注意 |
|---|---|---|---|---|
| `net-http-test/` | 在桌面上测 `jsbridge/NetHttp.java`：假坚果云 + 25 项断言 | `cd tools/net-http-test && ./run.sh`（加 `--live` 打真实坚果云探活） | JDK、Node 18+ | 每次自启假服务端；**改了 NetHttp.java 必须先这里全绿再动 NetBridge.java**；假服务端在内存存分片，留旧状态会假失败 |
| `net-http-test/server.mjs` | 假坚果云 WebDAV：MKCOL/PROPFIND/PUT/GET/HEAD/DELETE + 几条畸形响应专用路径 | `node server.mjs [port]`（默认 8078） | Node | `/__last` 回读上一条请求的形状，`/__log` 请求序列，`/__files` 现存路径，`/__reset` 清空；**`__` 前缀与 OPTIONS 都不进 `/__last`**，否则 Java 侧回读到的永远是回查自身 |
| `net-http-test/NetHttpSelfTest.java` | 断言本体（与 `NetHttp` 一起用 javac 编译，不需要 Android SDK） | 由 `run.sh` 调用 | 同上 | 包名就是 `org.mozilla.geckoview_example.jsbridge`，别改 |
| `nutstore-page-test/run.mjs` | 在桌面上跑壳里那页 `assets/nutstore-test.html`：假壳（`net.*`）+ 假坚果云，P1 有壳整套跑通 / P2 无壳自曝 / P3 壳报错原样上抛 | `node tools/nutstore-page-test/run.mjs`（加 `--big` 顺带 2MB） | Node 18+、Chromium | 借 `net-http-test/server.mjs`（自己回跨域头，否则假壳发不出去），端口 **8091** 与 Java 侧错开；**改了 nutstore-test.html 必须先这里全绿再往手机上装** |
