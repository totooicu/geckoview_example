# 壳发布手册（APK）

> 对应版本：v1.1.0+（WebView 壳）
> 配套手册：网页仓库 `DatingRecord/RELEASE.md`（**版本配对的唯一真相源**）

壳发版的难点不在构建，在**不可逆**：APK 装到手机上就收不回来，签名钥匙换一次等于换一个 App，
`versionCode` 加少了系统直接拒绝安装。所以这本手册只讲三件事：版本怎么对齐、包怎么出、出了事怎么退。

---

## 一、壳要报的三个数

| 数字 | 在哪 | 规则 |
|---|---|---|
| `versionName` | `app/build.gradle` | 与网页 `package.json` 同号（网页 1.0.1 → 壳 1.0.1） |
| `versionCode` | `app/build.gradle` | **只增**：1.0.0=3、1.0.1=4……每次发版 +1 |
| `proto` | `JsBridge.getEnv()` | 桥协议版本，与网页 `BRIDGE_PROTO` 一致；删 action / 改语义才升 |

`versionName` 还会被拼进 UA（`DatingRecord/1.0.1`），网页据此识别壳版本；`getEnv()` 返回
`{"app","version","builtAt","commit","platform","isApp","bridge","proto"}`，网页的「版本配对」卡片读的就是它。
`builtAt` 是打包时间、`commit` 是打包时的最后一次 git 提交（短哈希），都由 `app/build.gradle`
注入 `BuildConfig`：同号重打包时 `versionName` 分不出先后，人物主页 head 上的版本条用它们
显示「壳 x.y.z（MM-dd HH:mm @ 短哈希）」、说清跑的是哪份代码。

---

## 二、加载地址：站点根路径（跟随网页最新）

```java
DEFAULT_URL = "https://totooicu.github.io/DatingRecord/";
ALLOWED_HOST = "totooicu.github.io";
```

- **v1.1.0 起指向站点根路径**：壳打开的就是网页 `main` 的最新部署，网页修 bug 不必再发壳；
  代价是壳里的页面会随网页发版变化，排查问题时先看「版本配对」卡里的网页版本。
- 冻结快照 `releases/v1/` 是**定稿、只读**（网页仓库 `CLAUDE.md` 铁律七）：不再生成、
  不再覆盖，壳只可能读它，绝不写。壳这边也不许要求网页侧「重打 tag 更新快照」。
- `ALLOWED_HOST` 是域名锁，指到子路径不影响；站外链接仍被拦掉。

---

## 三、签名钥匙纪律（最重要）

- **一把钥匙用到底**。换钥匙 = 换 App：已装版本无法升级，只能卸载重装，而卸载会清掉
  WebView 里的本地数据（用户记录就没了）。
- 钥匙文件与口令**不进库**（铁律二）：`.jks` / `.keystore` 只在你自己手里。
- 现状（v1.x）：`app/build.gradle` 的 release 构建复用 `signingConfigs.debug`，
  也就是构建机上 `~/.android/debug.keystore` 那把自动生成的钥匙。
  **必须把这把文件备份到你自己的机器**，否则下次换环境重新生成会得到另一把钥匙，升级立刻断。
- 想换成正式的 release keystore，只能在「接受卸载重装」的前提下换，且要一次性换完并备份。

---

## 四、发布前检查

1. **版本号**：`versionName` 与网页同号；`versionCode` 比上一版大。
2. **加载地址**：`DEFAULT_URL` 是站点根路径（v1.1.0 起的策略）；临时切回快照只能读，不许写（铁律九）。
3. **桥的改动**：新增 action 可以照常发；删/改语义必须与网页同版本，并升 `proto`。
4. **网页已就绪**：根路径加载的是网页 `main` 的部署，确认 `.../DatingRecord/version.json`
   已经是最新一次部署。
5. **文档同步**：涉及桥的改动先改 `docs/`，再改 Java（铁律三）；版本推进时更新两份 guide 的
   头部版本号（铁律七）。

---

## 五、发布步骤

```bash
git fetch origin --prune
git switch -c feat/release-<版本> origin/main
git push -u origin feat/release-<版本>          # 铁律五：分支必须能在 GitHub 上看到

# 改 app/build.gradle（versionName/versionCode）、必要时改 DEFAULT_URL、docs
./gradlew :app:assembleRelease --no-daemon      # 唯一门禁

git add <改动文件>
git commit -m "壳 v<版本>：<一句话说清改了什么、为什么>"
git merge origin/main                           # 冲突在本分支解决，以 main 为准
git switch main && git merge --ff-only feat/release-<版本>
git push origin main
git tag -a v<版本> -m "壳 v<版本>（配网页 v<版本>，proto N）" && git push origin v<版本>
git branch -d feat/release-<版本> && git push origin --delete feat/release-<版本>
```

**产物校验（三条都要看）**

```bash
/opt/android-sdk/build-tools/<ver>/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -2
#   → versionCode / versionName 对不对

.../apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep SHA-256
#   → 与上一版同一个指纹（换钥匙就露馅了）

unzip -p app/build/outputs/apk/release/app-release.apk classes*.dex | strings | grep -c "totooicu.github.io/DatingRecord/"
#   → >0：加载地址在包里（根路径）
unzip -p app/build/outputs/apk/release/app-release.apk classes*.dex | strings | grep -c "releases/v1"
#   → 0：没有误指回冻结快照
```

**分发**

- 命名 `zhaohuixisi-<版本>.apk`，**只增不覆盖**（旧包要能重新发）。
- 附 `sha256sum`，装之前核对。
- 装完在手机上验：设置 → 功能体验 → 软件适配测试 →「版本配对」应为
  「网页 <版本> · 壳 <版本> · proto 1 · 配对正常」。

---

## 六、回滚

| 场景 | 做法 |
|---|---|
| 壳有严重 bug | 重新分发上一版 APK（同签名可直接覆盖安装，本地数据不丢） |
| 壳加载的页面出问题 | 不用动壳：网页侧修完推 `main`（或 revert 有问题的提交）即可，壳下次打开就换回来 |
| 已推的 tag 指向错提交 | `git tag -f v<版本> <正确提交> && git push -f origin v<版本>`，并在提交信息里写明原因 |
| 想彻底停掉某版本 | 不改已发 APK；网页侧回滚 `main` 让页面「变好」，而不是指望用户卸载 |

**永远不要**为了回滚去改 `applicationId` 或换签名——那等于让所有已装用户变成重装。

---

## 七、常见故障

| 现象 | 原因 | 处理 |
|---|---|---|
| 安装报「应用未安装 / 签名不一致」 | 构建机换了，钥匙不是同一把 | 找回原 `debug.keystore`；找不回就只能卸载重装（数据会丢，先让用户导出） |
| 安装被拒但签名对 | `versionCode` 没加 | 加 1 重新出包 |
| 启动白屏 | `DEFAULT_URL` 不可达 / 网页部署失败 | 浏览器先打开同一 URL；确认网页 `deploy-pages` 最近一次成功 |
| 页面能开但原生全失效 | 桥没注入 | 看 Logcat `WebViewActivity`；确认 `data-gv-bridge-injected` |
| 页面停在旧版 | 网页根路径部署没成功 / 缓存 | 查网页 `version.json` 的时间戳；必要时清站点数据 |
| 页面版本和壳不配套 | 壳跟根路径走，网页可能已更新 | 看「版本配对」卡；不在支持范围就发同版本壳 |
| 适配测试显示「壳太旧」 | 网页 `SHELL_MIN` 高于壳版本 | 出同版本壳，或用配套旧网页 |
| 录音报 `NotReadableError` | 机型侧给静音数据（原生也录不到） | 自检报告会指出；按 `docs/ADAPTATION_GUIDE.md` FAQ 排查，不是壳的权限问题 |

---

## 八、发布记录（镜像，以网页仓库 `RELEASE.md` 为准）

| 日期 | 壳 | 网页 | proto | versionCode | 说明 |
|---|---|---|---|---|---|
| 2026-10-25 | v1.1.0 | v1.1.0 | 1 | 5 | 连续打点策略（同点 2^n 退避，1→64s）；加载地址改回站点根路径 |
| 2026-10-25 | v1.0.1 | v1.0.1 | 1 | 4 | 加载冻结快照 `releases/v1/` |
| 2026-10-25 | v1.0.0 | v1.0.0 | 1 | 3 | 第一个正式版（指向根路径，已被取代） |
| 2026-10-25 | v0.2.1 | — | — | 2 | 麦克风自检诊断版（仅测试） |

每次发版在表里加一行，同时在网页仓库手册的发布记录里加对应一行。
