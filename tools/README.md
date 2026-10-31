# 壳侧工具索引（main 线）

规矩与网页仓库一致（壳 `CLAUDE.md` 铁律七）：**写过的工具不许删**。被取代就地标
`@deprecated`，真要删除必须在提交信息写明理由，并在本表留一行「已移除：X（原因）」。

> 这份索引在 `main` 线上是第一条 `tools/` 记录。坚果云测试那条线
> （`origin/test/nutstore`）早就有 `tools/net-http-test/`（假坚果云 + 25 项断言）与
> `tools/nutstore-page-test/`，那是那条线的工具，两边不共用；那条线合并过来时，
> 把它的条目并到本表，不要各留一份 README。

| 目录 / 文件 | 用途 | 入口命令 | 依赖 | 注意 |
|---|---|---|---|---|
| `file-write-test/` | 在桌面上验 `jsbridge/FileBridge.java` 的公共目录收口：**位置只能由壳发出去**，页面自报的 `file:` / 裸路径 / 别人的 content uri 一律拒 | `cd tools/file-write-test && ./run.sh` | JDK（javac/java 11+，实测 21）；不需要 Android SDK、不需要连手机 | **改了 `FileBridge.java`、`PublicWriteScope.java`，或 `stubs/` 里的桩，必须先在这里全绿**；跑两件：`PublicWriteScopeSelfTest`（判定 31 项）+ `FileBridgePublicBranchTest`（真 FileBridge 走 API 26-28，落盘是真文件，24 项）；29+ 的 MediaStore 与 `"wa"` 续写桩做不了，靠真机 `assets/test.html` 的「分片续写」「越界位置续写」两个按钮 |
| `file-write-test/stubs/` | 最小 Android / org.json 桩，唯一目的是在没有 SDK 的机器上把 `FileBridge.java` 真编译一遍、并让 26-28 分支真跑起来 | 由 `run.sh` 调用 | 同上 | 桩必须照真机脾气：`ContentResolver` 对非 `content:` 一律 `Unknown URI`（放宽就等于纵容真机上必坏的写法）；`JsBridge` 桩的签名由 `run.sh` 第 0 步与真实 `jsbridge/JsBridge.java` 逐行核对，漂了就报错——**别把这条检查删掉换省事** |
| `file-write-test/run.sh` | 上面那条流水线的入口；文件头记了反证（把收口摘掉会红哪九条） | `./run.sh` | 同上 | 每次都用临时目录，不留上次落盘的文件；临时目录路径来自 `java.io.tmpdir` |
