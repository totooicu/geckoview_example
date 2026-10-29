# 壳侧工具索引

规矩与网页仓库一致：**写过的工具不许删**。被取代就地标 `@deprecated`，
真要删除必须在提交信息写明理由，并在本表留一行「已移除：X（原因）」。

| 目录 / 文件 | 用途 | 入口命令 | 依赖 | 注意 |
|---|---|---|---|---|
| `net-http-test/` | 在桌面上测 `jsbridge/NetHttp.java`：假坚果云 + 25 项断言 | `cd tools/net-http-test && ./run.sh`（加 `--live` 打真实坚果云探活） | JDK、Node 18+ | 每次自启假服务端；**改了 NetHttp.java 必须先这里全绿再动 NetBridge.java**；假服务端在内存存分片，留旧状态会假失败 |
| `net-http-test/server.mjs` | 假坚果云 WebDAV：MKCOL/PROPFIND/PUT/GET/HEAD/DELETE + 几条畸形响应专用路径 | `node server.mjs [port]`（默认 8078） | Node | `/__last` 回读上一条请求的形状，`__` 前缀不记 |
| `net-http-test/NetHttpSelfTest.java` | 断言本体（与 `NetHttp` 一起用 javac 编译，不需要 Android SDK） | 由 `run.sh` 调用 | 同上 | 包名就是 `org.mozilla.geckoview_example.jsbridge`，别改 |
