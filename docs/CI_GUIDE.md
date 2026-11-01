# CI 取包手册（编 APK 用 GitHub Actions）

> 对应版本：壳 v1.2.0-test1（测试线，`test/payment-recharge`）起
> 工作流文件：`.github/workflows/apk.yml`
> 发版**不走**这条，见 `RELEASE_GUIDE.md`

一句话：**这台机器（沙箱）和你手上都没有 Android SDK，Java 改动编不了**，所以把
`./gradlew :app:assembleDebug` 搬进 Actions；产物挂在本次 run 的 Artifacts 上，
手机浏览器登录 GitHub 就能下，不用电脑中转。

---

## 一、怎么拿包（全程手机）

1. 打开仓库 `totooicu/geckoview_example` → 顶部 **Actions**。
2. 左侧选「编 APK（取包用，不是发版）」，点最上面那次 run（推送分支会自动触发；
   也可以点 **Run workflow** 手动选分支再跑）。
3. 跑完往下滚到 **Artifacts**，点 `apk-<分支>-<短哈希>` 下载，得到一个 zip，解压出 `app-debug.apk`。
4. 手机上装之前**先解 zip**；浏览器会警告「来自 GitHub 的文件可能有害」，是常规提示。

看 run 摘要（run 页面顶上那段）能确认下的是哪一份：**分支、提交、versionName、GIT_COMMIT**。
`BUILD_TIME` 也在摘要里，但它是**写死在 `app/build.gradle` 里的**（发版规则禁止随构建动态生成），
所以它**不区分**这些包，别拿它认新旧——认 `GIT_COMMIT`。

## 二、装包之前必须知道的三件事

- **签名每次可能不同**：runner 用的是它自己临时生成的 debug 钥匙（铁律二：keystore 不入库，
  CI 也拿不到你本地那把固定钥匙）。同签名才能覆盖安装；签名不同就得先卸载，
  **卸载会连同 WebView 里的本地库一起清掉**——先在网页「设置 → 导出备份」拿一份 `.drp`。
- **这不是发布包**：不打 tag、不写 `releases/`、不进 `RELEASE.md` 的配对记录。
  它的价值只有两个：证明这份 Java 真能编过（沙箱里编不了的那道门禁），和给你一个能上手的包。
- **`versionCode 1001` 是测试号段**（`app/build.gradle` 里注释了），永远高于真实发版号，
  不占 `7 → 8 → 9` 那条序列。

## 三、CI 里已经处理掉的两个坑

1. `gradle.properties` 有一行 `org.gradle.java.home=/home/user/jdk/jdk-17.0.20.1+1`——
   那是本机的绝对路径，runner 上不存在，Gradle 会直接报 invalid。工作流**不改库里那行**
   （改了会破坏你本地构建），在命令行用 `-Dorg.gradle.java.home="$JAVA_HOME"` 覆盖过去。
2. `compileSdk` 是 API 37（还带 minor），镜像里不一定预装。工作流先接受许可、再逐个试装
   几个候选包名，装不上也不让这一步失败——真正解析由 AGP 完成，缺什么会在 `assembleDebug`
   的日志里明说。

## 四、跑挂了怎么读

- run 页面点进失败的步骤看红字；日志是公开的，手机浏览器就能看，不需要登录以外的权限。
- 常见三类：Gradle 发行版下载不动（`gradle-wrapper.properties` 指向的是腾讯镜像）、
  SDK 平台缺件、以及 Java 本身编译错误——**第三类是这次改动真的有问题**，
  把日志里 `error:` 那几行抄回来即可定位。
- 门禁口径没变：Java 改动的「编过」以前只能在你本机证，现在由这条 CI 证。
  在 CI 绿之前，不要把 `main` 侧合并到含未编译 Java 改动的分支（壳宪法铁律五）。
