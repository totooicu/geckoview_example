# 壳侧工具索引（main 线）

规矩与网页仓库一致（壳 `CLAUDE.md` 铁律七）：**写过的工具不许删**。被取代就地标
`@deprecated`，真要删除必须在提交信息写明理由，并在本表留一行「已移除：X（原因）」。

> 这份索引在 `main` 线上是第一条 `tools/` 记录。坚果云测试那条线
> （`origin/test/nutstore`）早就有 `tools/net-http-test/`（假坚果云 + 24 项断言）与
> `tools/nutstore-page-test/`，那是那条线的工具，两边不共用；那条线合并过来时，
> 把它的条目并到本表，不要各留一份 README。
>
> 2026-10-31：`net.*` 的原生代理已经按「测试线验完 → 间接并进 main」的规矩落到 main
> （`net-http-test/` 一并过来，`nutstore-page-test/` 留在测试线——它验的是那条线独有的
> 坚果云测试页）。`net-http-test` 复用 `file-write-test/stubs/` 的桩，两边共用一份，
> 别再抄第二套。

| 目录 / 文件 | 用途 | 入口命令 | 依赖 | 注意 |
|---|---|---|---|---|
| `file-write-test/` | 在桌面上验 `jsbridge/FileBridge.java` 的公共目录收口：**位置只能由壳发出去**，页面自报的 `file:` / 裸路径 / 别人的 content uri 一律拒 | `cd tools/file-write-test && ./run.sh` | JDK（javac/java 11+，实测 21）；不需要 Android SDK、不需要连手机 | **改了 `FileBridge.java`、`PublicWriteScope.java`，或 `stubs/` 里的桩，必须先在这里全绿**；跑两件：`PublicWriteScopeSelfTest`（判定 31 项）+ `FileBridgePublicBranchTest`（真 FileBridge 走 API 26-28，落盘是真文件，24 项）；29+ 的 MediaStore 与 `"wa"` 续写桩做不了，靠真机 `assets/test.html` 的「分片续写」「越界位置续写」两个按钮 |
| `file-write-test/stubs/` | 最小 Android / org.json 桩，唯一目的是在没有 SDK 的机器上把 `FileBridge.java`（以及 `NetBridge.java`）真编译一遍、并让 26-28 分支真跑起来 | 由两个 `run.sh` 调用 | 同上 | 桩必须照真机脾气：`ContentResolver` 对非 `content:` 一律 `Unknown URI`（放宽就等于纵容真机上必坏的写法）；`JsBridge` 桩的签名由 `run.sh` 第 0 步与真实 `jsbridge/JsBridge.java` 逐行核对，漂了就报错——**别把这条检查删掉换省事**；`JSONObject` 桩不解析 JSON 文本，构造时直接抛，谁依赖字符串解析就会在这里炸出来 |
| `file-write-test/run.sh` | 上面那条流水线的入口；文件头记了反证（把收口摘掉会红哪九条） | `./run.sh` | 同上 | 每次都用临时目录，不留上次落盘的文件；临时目录路径来自 `java.io.tmpdir` |
| `net-http-test/` | 桌面验 `jsbridge/NetHttp.java`（net.* 的 HTTP 层）：起一个假坚果云（`server.mjs`），拿真代码走 PROPFIND / MKCOL / PUT / GET / DELETE 全链路 | `cd tools/net-http-test && ./run.sh`（加 `--live` 顺带向真实 `dav.jianguoyun.com` 发一次无凭据 PROPFIND） | JDK 21、Node 18+；不需要 Android SDK | 第 0 步先拿 `file-write-test/stubs/` 把 `NetBridge.java` 编一遍（桥接层只编不跑，原因写在 `run.sh` 头上）；第 1 步 24 项断言含安全边界：`file://` 被拒、URL 里带 `user:pass@` 被拒、请求头含换行被拒、超响应上限报错而非截断；`NetBridge` 的应答格式改动仍需真机或网页侧验 |
