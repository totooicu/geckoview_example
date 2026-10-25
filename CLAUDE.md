# 项目：DatingRecord 安卓壳（geckoview_example）

一句话定位：**DatingRecord 网页应用的原生载体**——GeckoView/WebView 壳 + 原生桥，
让网页在后台/锁屏下继续定位打点，并提供蓝牙/文件/录屏等网页拿不到的能力。
壳不实现业务，**业务真相永远在网页里**；壳只做「承载、兜底、桥接」三件事。

配套关系：网页工程宪法在 DatingRecord 仓库 `CLAUDE.md`，两边铁律同源；
壳的独有纪律（桥契约、语义对齐、前台服务）以本文件为准。

## 铁律一：每次对话必须提交并推送 git

- 本仓库**没有** git-sync 钩子（不像网页工程有 `scripts/git-sync.sh`），
  每轮对话结束前必须**手动**提交并推送当前分支，可用 `git status` 确认无遗漏。
- 提交信息用中文写清「改了什么、为什么」，照现有历史风格
  （如「麦克风授权链路加诊断Toast：onPermissionRequest触发/未识别资源/授权结果可见」）。
- 不要提交空提交；推送失败不中断对话，如实告知用户原因（改动已安全在本地提交里）。
- **提交位置受铁律五约束**：一律在任务分支，不直接提交 `main`——包括修订本宪法，**无例外**。

## 铁律二：不提交密钥，不碰用户数据

- keystore（`*.jks` / `*.keystore`）、签名密码、`google-services.json` 等一律不入库；
  用户在本地保管，提交信息里可提醒一句。
- `.gitignore` 目前**没有** keystore 规则——新增签名配置时必须同步补上，别等泄密了才补。
- 运行时数据（`filesDir/location_log.jsonl`、录屏文件、用户经 `file.*` 选的文件）
  只在设备上，不进库；APK 里不许预置假定位数据冒充用户记录。

## 铁律三：桥协议是契约，文档先行，双边同步

- 任何桥 action（`ble.` / `file.` / `screen.` / `location.` / `ui.`）的增改，
  **先更新 docs 接口表**（`ADAPTATION_GUIDE.md` §3.2、对应功能的开发文档），再动 Java。
- 网页（DatingRecord）与原生壳是同一产品的两半：桥变更必须**两边同版本适配**，
  不允许单边上线把另一边摔坏（页面调一个壳里没有的 action = 功能静默死亡）。
- 新增原生能力的最小改动路径照 `ADAPTATION_GUIDE.md` §6：
  `JsBridge.dispatch()` 加路由 → 新建 `FooBridge.java` → 页面端 `bridge('foo.bar', …)` 调用。
  不发明第二套协议、不绕过 `respond/emit` 自己搞回调。

## 铁律四：与网页语义严格对齐

- 定位三件套——**appendPoint 去重、连续打点策略、rev 语义**——与网页 `locationAuto.ts`
  一一对应；改原生算法前先读网页实现，**两边不一致就是 bug**。
- **配置权威在网页**：`localStorage` 的 `dr.autoLocation` 是唯一来源，原生只经
  SharedPreferences 镜像；不许发明平行配置源，不许让原生配置反过来覆盖网页。
- 落盘格式（`location_log.jsonl` 每行一个点）是网页 `getHistory` 合并的契约：
  改格式先改开发文档 §6，并同步网页侧，老数据要能读。

## 铁律五：一任务一分支，验证通过才合并，main 只许前进

用户 2026-10-23 为 DatingRecord 立规、壳工程沿用：

> **① 每次修改都应该创建新的分支，分支任务完成且没有问题后再与 main 分支合并。**
> **② 不与 main 冲突的前提下合并；一旦冲突，解决自己的分支，main 慎重改动。**

**分支与门禁（缺一不可）：**

1. **开工先建分支**：`git fetch origin` → `git switch -c feat/<简短名> origin/main`，
   建好立刻 `git push -u origin feat/<简短名>`——分支必须能在 GitHub 上看到，
   只在本地存在等于没建分支。**任何改动**（Java、资源、文档、本宪法修订）都不直接动 `main`。
2. 分支内可自由提交，有进展就 `git push`；`main` 保持随时可发布。
3. **门禁**（按改动类型）：
   - 纯文档/注释改动：通读一遍 diff 即可，无需构建；
   - Java 改动：必须在有 Android SDK 的环境跑 `./gradlew :app:assembleDebug --no-daemon` 通过
     （沙箱里没有 SDK，见「环境备注」）；本地至少过一遍 `javac` 语法检查 + 通读 diff。
4. **不走 PR**：直接本地合并，**本地构建就是唯一门禁**，跳过构建直接合并视同违规。
5. 合并前必做：`git fetch origin --prune` → `git diff --name-only origin/main...HEAD`
   确认文件重叠 → **先在本分支 `git merge origin/main`**（冲突在分支内解决，
   **以 main 为准**，我的改动改名/挪位/重写适配）→ 只许 `git merge --ff-only`，
   **禁止在 main 上 rebase / reset / amend / 手改代码**；`--ff-only` 失败说明 main 已前移，
   退回上一步，不许 `--no-ff` 硬合。
6. 收尾：`git push origin main` → 校验 `git rev-parse main origin/main` 一致 →
   删除该分支（本地 `git branch -d` + 远端 `git push origin --delete`），
   远端 `git ls-remote --heads origin` 应只剩 `main`。

## 铁律六：前台服务与权限纪律（踩过的坑，不许回退）

- `startForeground` 必须**先于**任何定位/录屏工作（API 29+ 三参带
  `FOREGROUND_SERVICE_TYPE_LOCATION`）；同录屏规则，见 `ADAPTATION_GUIDE.md` §5.3。
- 权限被拒要**降级可解释**：Toast 说明 + 引导系统设置页，不静默失败、不崩溃、不空转。
- `START_STICKY` 恢复分支（`onStartCommand(intent==null)` 从文件尾恢复
  lastPoint/rev）必须保持：杀 App 重开要能续记且不重复。
- 返回键转发（`ui.backPressed` → 网页 `ui.finish`/`ui.goBack` 决策 → 3s 超时兜底）
  的兜底逻辑不许删；退出时录屏要停、定位服务按设计保持。

## 铁律七：写过的工具/脚本/文档不许删，必须留下说明

- `docs/`、脚本、调试入口只增不删；被取代的标 `@deprecated` 或注明
  「已被 X 取代，保留原因：…」。
- 每篇文档顶部注明对应壳版本（照 `DEVELOPMENT_GUIDE.md`「对应版本 v0.2.0+」惯例），
  版本推进时同步更新，别让文档描述的壳和代码里的壳是两个世代。

## 铁律八：换皮只改规定的 4 处

- 目标网页地址 `DEFAULT_URL`、域名锁 `ALLOWED_HOST`、`app_name`、`logo.png`——
  照 `ADAPTATION_GUIDE.md` §2.2，不散落硬编码。
- `applicationId` **发布后不可改**，改它等于换一个 App（用户数据、签名全作废），
  必须用户明确确认才动。

## 铁律九：网页的 `/releases/*/` 是定稿，壳这边也不许碰

用户 2026-10-25 立规：**不要动 `/releases/*/` 里的东西，`/releases/*/` 已经是定稿了。**

- 壳只读它们：`DEFAULT_URL` 目前指向站点根路径；就算哪天切回快照，也只是读，
  **不重写、不删除、不"更新一版"**。
- 壳发版不许要求网页侧重写快照来配合；网页回滚走 `main` 的 revert + 重新部署。
- 网页仓库 `CLAUDE.md` 铁律七是同一件事的正本，冲突时以那份为准。

## 目录结构

```
geckoview_example/
├── CLAUDE.md                    本文件（项目约定）
├── docs/
│   ├── ADAPTATION_GUIDE.md      换皮教程 + 桥协议全貌 + 权限坑位（改桥前必读）
│   ├── DEVELOPMENT_GUIDE.md     长期定位原生兜底服务开发文档（location.* 全貌）
│   └── RELEASE_GUIDE.md         壳发布手册（版本对齐 / 签名钥匙 / 出包校验 / 回滚）
├── app/src/main/java/org/mozilla/geckoview_example/
│   ├── WebViewActivity.java     主壳：加载页面、注入 ADAPTER_JS、域名锁、权限、返回键
│   └── jsbridge/
│       ├── JsBridge.java        桥入口：invoke/action 路由 + respond/emit + getEnv()
│       ├── LocationBridge.java  location.* 路由 + 权限申请 + 后台权限引导
│       ├── LocationConfig.java  配置模型 + SharedPreferences 镜像（dr.autoLocation）
│       ├── LocationTrackingService.java  前台服务：连续打点、appendPoint、JSONL 落盘
│       ├── BleManager.java      蓝牙 Central/Peripheral
│       ├── FileBridge.java      文件读写
│       ├── ScreenCaptureManager.java + ScreenCaptureService.java  录屏
├── app/src/main/AndroidManifest.xml  权限 + Activity/Service 声明
└── app/build.gradle             applicationId / versionName / SDK 级别
```

## 开发流程

1. **动手前先读文档**：桥相关先读 `ADAPTATION_GUIDE.md`（协议全貌），
   定位相关先读 `DEVELOPMENT_GUIDE.md`（状态机/去重/落盘契约）。
2. 顺序固定为 **桥协议/文档 → 原生实现 → 网页适配 → 真机验证**，不可跳步；
   不允许先写 Java 后补协议（那是给后人埋雷）。
3. 术语照现有文档，不自造同义词：壳 / 桥 / 页面（网页）/ 前台服务 / 兜底 /
   打点 / 去重 / rev / 镜像。
4. 完成标准（DoD）：文档已更新、构建门禁通过（见铁律五第 3 条）、
   涉及桥的改动在提交信息里写明网页侧是否需要同步适配。

## 版本对齐

- 壳 `versionName` 与所搭载网页版本的对应关系，在合并涉及桥或加载逻辑的
  提交时于提交信息或 docs 里记一笔。
- **桥协议不兼容变更 = 网页与壳同版本发布**：DatingRecord 的 Pages 部署与
  APK 分发要互相等齐，别让新壳配旧网页或旧壳配新网页上线。
- **版本时间写死规则（网页查询用）**：`app/build.gradle` 里
  `BuildConfig.BUILD_TIME` 必须写死为**该版本提交 GitHub 的时间**（Asia/Shanghai，
  `yyyy-MM-dd HH:mm`），禁止用 `new Date()` 随构建动态生成——同号重打包会变，
  网页的版本条就说不清「跑的是哪份代码」。发版流程：push 到 GitHub → 用
  `curl https://api.github.com/repos/totooicu/geckoview_example/commits/<短哈希>`
  取提交时间（`commit.committer.date` 转 UTC+8）→ 写进 `BUILD_TIME` 并注释对应
  提交哈希。`GIT_COMMIT` 由构建时动态取当前 HEAD 短哈希（ProcessBuilder，
  无 git 的机器为 `"unknown"`，发版必须在有 git 的机构建）。
- **网页如何获取版本**：页面调 `AndroidBridge.getEnv()`（见 `ADAPTATION_GUIDE.md`
  §3.1），拿 `version`（versionName）、`builtAt`（写死的提交时间）、
  `commit`（git 短哈希），版本条显示「壳 1.1.1（10-25 18:10 @ a1b2c3d）」。
- 发版照 `docs/RELEASE_GUIDE.md` 执行；配对记录的唯一真相源在网页仓库 `RELEASE.md`，
  本仓库那份是镜像，两边同步加行。

## 环境备注

- git 身份：本机未配置全局身份，提交时显式带
  `-c user.name=totooicu -c user.email=totooicu@users.noreply.github.com`（照仓库历史）。
- 远程仓库：`origin → git@github.com:totooicu/geckoview_example.git`，走 SSH
  （`~/.ssh/id_ed25519_github`，已认证为 `totooicu`，实测有读写权限）。
- **沙箱无 Android SDK**：`java`/`javac` 有（sdkman），`./gradlew` 构建必须在
  用户机器或 CI 跑；门禁细节见铁律五第 3 条。
- **`gh` 未登录、无 token**：看 Actions 用公开 API
  `curl -s https://api.github.com/repos/totooicu/geckoview_example/actions/runs?per_page=5`
  （本仓库目前无 workflow；若要加 CI，注意 APK 构建runner 需要 Android SDK）。
- **发布形态不同**：网页侧走 DatingRecord 的 `deploy-pages.yml` 自动部署；
  壳侧 APK 手动分发，没有自动发布链路——「合并即发布」不适用于本仓库，
  合并只代表代码进入 `main`。
