# 后台续记：壳在后台/熄屏继续收集定位

> 探索记录起自分支 `feat/location-tune`（定位磨合），现已并入 `main`：
> 后台续记这套能力在正式线上，磨合台只是它的测试面。
> 结论先写前面，实现对照在中间，测试步骤和已知边界在最后。

## 一、要解决的问题

网页端的自动打点是 `setInterval` + `getCurrentPosition`。WebView 一旦退到后台，
Chromium 会把页面的 JS 定时器冻住，打点就断了；熄屏更彻底——整个 App 进程被
系统降级调度。所以「连续定位」不能靠页面，必须由原生侧承担。

本分支把网页端定位功能搬到一个精简的在线测试页（定位磨合台，网页仓库
`public/location-lab/`）上，专门磨「后台还记不记得」。磨合期间壳把 `DEFAULT_URL`
指到它；页面现已并进网页 main，站点根路径即可访问
（<https://totooicu.github.io/DatingRecord/location-lab/>），早先手工放在 gh-pages
`assets/location-lab/` 的那份仍在（部署保留 `assets/`），已装的旧测试包继续可用。

## 二、结论：让后台续记成立的四件事（缺一件都会断）

1. **前台服务 + `location` 类型**
   Android 10 起，后台访问定位必须有 `foregroundServiceType="location"` 的前台服务；
   Android 14+ 还要清单里的 `FOREGROUND_SERVICE_LOCATION` 权限。
   `startForeground()` 必须在任何取点动作**之前**调用，否则直接 `SecurityException`。

2. **`PARTIAL_WAKE_LOCK`（熄屏续命）**
   熄屏后 CPU 会睡，`Handler.postDelayed` 的 tick 与 GPS 回调会被推迟到下次亮屏，
   表现为「记着记着断了、亮屏又续上」。持一把部分唤醒锁，整机 Doze 期间也能按点工作
   （前台服务 + 唤醒锁是官方允许的持续定位组合）。服务停止/销毁时释放。

3. **采集与页面生命周期解耦**
   - 页面只是「配置来源 + 展示层」：配置写 `localStorage['dr.autoLocation']`（网页侧），
     同时 `location.setConfig` 推给原生，原生持久化到 SharedPreferences。
   - WebView 被冻结/重建都不影响服务；回到前台时页面用 `location.getHistory {since}`
     按游标补齐断档（事件收不到，历史不会少）。
   - 用户从最近任务划掉壳：`onTaskRemoved` 什么都不做，服务继续（划掉任务 ≠ 停止打点）。

4. **重启语义：只在配置仍然 enabled 时恢复**
   `START_STICKY` 让系统在内存回收后重建服务。重建时如果不看配置就恢复，
   用户明明关过自动打点却还在耗电；所以服务只认 `LocationConfig.enabled`（已落盘），
   页面 `location.start` / `stop` 也会把 enabled 写回配置，两条入口语义一致。

## 三、实现对照（本分支）

| 位置 | 做了什么 |
| --- | --- |
| `AndroidManifest.xml` | `ACCESS_BACKGROUND_LOCATION`、`FOREGROUND_SERVICE_LOCATION`、`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；声明 `LocationTrackingService`（`foregroundServiceType="location"`）；自启组：`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM` + `RecoveryReceiver`（静态注册）+ `RecoveryJobService`（`BIND_JOB_SERVICE`） |
| `LocationTrackingService` | 前台服务 + 唤醒锁；`START_STICKY` + 只认 enabled 的恢复语义；`onTaskRemoved` 不自杀，临走前把 30 秒后的网排下去当遗言；`onDestroy` 若并非用户/页面要停，补齐网并重新 `startForeground` 抢回前台身份；JSONL 落盘；通知栏活体证据；诊断字段；`journalStatic` 让「服务还没起来」的那一跳也留痕 |
| `RecoveryArm` | 排网的唯一出口：一张网一个 `requestCode`（7702 起，7701 是 60 秒心跳闹钟），`setRepeating` 兜底网 + `setExactAndAllowWhileIdle` 精确网 + 15 分钟 `setPersisted` 周期任务；已经排着的网**不重排**（重排等于把下一次敲门一直往后推，「刚敲完门就被杀」要等满一轮）；`gate()` 只认持久化的 `enabled` + 精确定位权限；`state()` 报「网还在不在」 |
| `RecoveryReceiver` | 清单静态注册的自启网：`BOOT_COMPLETED`（含厂商 `QUICKBOOT_POWERON`）、`SCREEN_ON`、`USER_PRESENT`、`MY_PACKAGE_REPLACED`。广播里**不**直接起前台服务（Android 12+ 会拒，且拒一次就把这张网烧掉），只排一个 8 秒后的闹钟；enabled=false 时顺手撤网 |
| `RecoveryJobService` | 第二道兜底：15 分钟周期 + `setPersisted(true)`（跨整机重启由系统恢复）；到点同样先记账再决定拉不拉 |
| `LocationBridge` | `location.*` 全部动作：`setConfig / start / stop / status / getHistory / clearHistory`，新增 `requestPermissions`、`batteryOptimization`、`requestBatteryExemption`；取证组 `setDiag / journal / clearJournal`；事件 `location.point`、`location.state` |
| `WebViewActivity` | 正式包 `DEFAULT_URL` 仍是站点根路径（磨合期在 `feat/location-tune` 上指向定位磨合台）；`onPageFinished` 后 `pullLocationConfig()` 主动读页面的 `dr.autoLocation`（页面一次桥都没调也不怕） |
| `JsBridge` | 路由 `location.` 前缀；`PERM_LOCATION` 权限码；`location()` 访问器 |

### 采集循环

```
tick(按策略定时) → requestSingleUpdate(provider)
   ├─ 回调 onFix：算距离 → 阈值内合并（刷 ts/精度）/ 阈值外追加（rev+1、落盘）
   │   → 推 location.point 给页面 → 通知栏刷新 → 下一个 tick
   └─ 看门狗超时：失败计数 +1、换 provider 再来
```

策略与页面 `locationAuto.ts` 一致：`fixed` 固定间隔；`backoff` 同点连续 n 次后等
`base * 2^n`，封顶 `intervalMaxMs`（默认 1s → 64s），换点归零。

### 通知栏（后台的活体证据）

常驻通知显示「已记 N 个点 · 最近 HH:mm:ss ±Xm · 间隔 Ns」，点开回到网页，
右侧「停止」直接停服务。熄屏时瞄一眼通知栏就能判断刚才有没有在记。

### 诊断字段（`location.status`，网页的「原生心跳」就靠它们）

| 字段 | 含义 |
| --- | --- |
| `tracking` / `startedAt` | 是否在记 / 本次开始时间 |
| `points` / `rev` | 累计点数 / 版本号 |
| `wakeLock` | 唤醒锁是否持有（熄屏续记的关键状态） |
| `provider` | 当前用 gps 还是 network |
| `lastFixAt` / `fixes` / `failures` | 最近取点时刻 / 成功次数 / 失败次数 |
| `lastError` | 最近一次失败原因（取点超时、权限、提供者被关…） |
| `intervalCurrentMs` | 当前处于哪一档间隔（退避到第几档一眼可见） |
| `fineLocation` / `backgroundLocation` / `batteryOptimized` | 权限与省电状态 |
| `recovery` | 自启的网：`{alarms:["sweep",…], job:true, exact:true}`；`alarms` 空 = 一张闹钟网都没排上，`job` = 15 分钟周期任务在不在，`exact` = 有没有精确闹钟权限 |

## 四、测试步骤（配合定位磨合台）

1. 装壳 → 打开「定位磨合台」→「请求定位权限」（首次会弹窗）→ 顺手点一次
   「忽略电池优化」把壳加白（国产 ROM 还要在「电池 → 应用启动管理」里放行）。
2. 打开「开启自动打点」，确认状态行变成「记录中」，通知栏出现「正在记录位置」。
3. **熄屏**，把手机揣兜里走一段（10 分钟以上），中途只看通知栏：
   点数应当持续增长、「最近」时刻不断刷新——这就是后台续记在工作的直接证据。
4. 回到前台看两行心跳：**页面心跳**停在离开前（JS 定时器被冻），
   **原生心跳**刚更新过。点「拉取原生历史」补齐断档，地图上橙色的原生点连成轨迹。
5. 进阶：从最近任务里划掉壳，等几分钟再打开——`points` 应当比划掉时更多
   （服务没跟着任务死）；重启手机后再打开，`enabled` 还在，服务应自行恢复记录。

## 五、已知边界（本分支没做 / 做不到的）

- **开机自启**：0.3.4-tune 起已经做了（见 §十一），但仍**不是**用户可关的独立开关——
  现在拿「自动打点」的 `enabled` 当唯一开关：关了就撤网，绝不在用户明确关掉之后又开机复活。
- **国产 ROM 的自启动/省电策略**：壳只能引导（电池优化弹窗、设置页），
  「应用启动管理」这类厂商入口必须用户手动放行，任何 App 都绕不过。
- **Android 11+ 的后台定位权限**：只能跳系统设置由用户选「始终允许」，
  不能再靠一次运行时弹窗拿到。
- **兜底调度**：0.3.4-tune 起接了 AlarmManager（`setRepeating` + 有权限时
  `setExactAndAllowWhileIdle`）+ 15 分钟 `setPersisted` 周期任务 + 静态广播，
  不再只靠 `START_STICKY` 那一次不保证准点的重建。
- **轨迹质量**：只有阈值去重，没有速度/漂移过滤；后台期间 GPS 冷启动的第一个点
  可能偏差较大（看 `accuracy`）。
## 七、取证账本（0.3.1-tune）：把「后台这一秒发生了什么」落成文件

光看最终点数分不清两件事：**进程被系统冻住**，还是**醒着但系统不给定位**。
所以加一本逐秒账本，网页测试端（定位磨合台）读它来判。

- 开关：桥动作 `location.setDiag {on}`。打开后采样**固定每秒一次**（`nextInterval()`
  直接返回 DIAG_INTERVAL_MS，不退避），并顺带把 `enabled` 打开——没在采集的取证没意义。
  `diag` 随 `LocationConfig` 落 SharedPreferences，服务被系统重建后仍在取证。
- 记账：`bg_journal.jsonl`（app 私有目录，一行一条 JSON，追加写；超过 1.5MB 砍掉旧的一半）。
  每行公共字段：`t`（时刻）、`k`（类型）、`n`（tick 序号）、`p`（provider）、`wl`（唤醒锁）、
  `f`（累计取点）、`x`（累计失败）、`pts`（点数）、`diag`。
  类型：`tick` 每秒申请一次 / `fix` 申请成功并带 `lat,lng,acc,fixTs`（WGS-84 原始值）/
  `fail` 申请失败或取点超时（带 `why`）/ `screen` 灭屏亮屏解锁（`what`）/
  `provider` 开关定位服务 / `start`、`stop` / `taskRemoved`（用户划掉任务）/
  `destroy`（服务销毁，进程若被杀这条是最后一证）/ `diag`（开关取证）。
- 读：`location.journal {since?, tail?}` → `{lines, count, total, bytes, ticks, diag, tracking}`；
  `location.clearJournal` 换一轮实验时清空。`location.status` 新增 `ticks`、`diag`、`journalBytes`。
- 网页侧的判读：账本里 **tick 序号连续、时间戳也连续** → 壳没睡；
  **tick 之间出现大空档** → 那段时间被冻结（对齐 `screen` 行看是不是熄屏引起）；
  **tick 连续但没有 fix 行** → 醒着但拿不到定位（多为没给「始终允许」或 provider 被省电掐掉）。
- 老页面不带 `diag` 字段时，`location.setConfig` 会保留现有取证开关，不会被静默关掉。

## 八、0.3.2-tune：停因留痕、唤醒锁真值、跳拍也记账

上一轮实测出现「后台 113 秒账本一片空白，回前台立刻有拍」，而且状态行报
「未记录 / 无唤醒锁」。这说明空白段不一定等于「被冻结」——也可能**采集根本是停着的**。
这一版把这两种情况分开：

- **停机也要留原因**：`start` / `stop` 行现在带 `why`（`page`=页面推的配置或显式停、
  `system-restart`=系统重建后恢复、`config`=配置变化）、`enabled`、`diag`。网页看到
  「大空档前面正好有一行 stop」就报「不是睡，是被停」，不再误判成休眠。
- **取证开着不许停**：`location.setConfig` 里如果 `enabled=false` 但 `diag=true`，
  壳会把 `enabled` 强留为 `true` 并回 `keptForDiag`。原因：磨合台页面自己的「自动打点」
  开关是 localStorage 里的，页面一重载就把 `enabled=false` 推下来，把正在取证的采集停了，
  账本随之断流——这正是上一轮那片空白的最可能来源。
- **唤醒锁问真值**：`location.status.wakeLock` 和账本每行的 `wl` 不再吃缓存标记，
  直接 `WakeLock.isHeld()`。`acquireWakeLock()` 也允许在锁被系统收走后重新拿一把。
- **锁丢了当场记账并补拿**：取证模式下每拍检查一次，锁不在手上就写一行
  `fail {why:"wakelock-lost"}` 再重新 acquire。没锁 = 熄屏后 CPU 一定睡，这是壳的锅，
  必须留在账本里。
- **跳拍也算一拍**：上一发请求还没回时，这一拍不重复发请求，但写一行 `skip {why:"pending-fix"}`。
  于是网页的 tick 覆盖率量的是「进程活着」，而不是「请求发出去了」，两者不再互相污染。

## 九、0.3.3-tune：三个时钟探针 + 60 秒闹钟心跳（判「谁在睡」）

0.3.2 的实测把问题定性了：**国产 ROM 的「后台行为」限制不放行时，前台服务 + 唤醒锁 + 电池加白
三件套照样会被冻**（熄屏后 135 秒账本一行不写，回前台立刻恢复）。放行之后同一台机
tick 覆盖率 98%，每一拍都取得到点。剩下要回答的是「以后再出现空白，是谁睡的」。

- **probe 行（空档解剖）**：任何一拍发现距上一次落笔超过 5 秒，就先补一行
  `probe`，记这三个时钟各走了多久——`gapWall`（墙钟）、`gapEl`（`elapsedRealtime`，含深睡）、
  `gapUp`（`uptimeMillis`，**不含**深睡），再加当时的电源快照：`interactive`（亮屏）、
  `saver`（省电模式）、`idle`/`light`（Doze / 轻度 Doze）、`gpsOn`/`netOn`（两个 provider 的开关）、
  `bat`/`plugged`（电量、是否充电）、`opt`（电池优化白名单）、`wl`（唤醒锁）。
  判读：`gapUp ≈ gapWall` → CPU 全程醒着，是**壳的进程被冻**；`gapUp` 少一大截 → **整机真睡了**。
- **wake 行（闹钟对照）**：`startTracking` 排一个 60 秒的 `setAndAllowWhileIdle`
  （`ELAPSED_REALTIME_WAKEUP`，`PendingIntent.getService` 指向自己），每次到点重排。
  它带 `late`（比预定时刻晚了多少）。这一段空档里一次 `wake` 都没有 → 连闹钟投递都被压着，
  进程是被冻/被杀；`wake` 准点进来而 `tick` 没跟上 → 进程能醒，问题在采集线程。
  闹钟到点时若 `enabled` 还开着、权限还在而服务没在跑，就地 `startTracking("wake-resume")`
  ——这是 `START_STICKY` 之外的第二条复活路径（`START_STICKY` 的重建时机由系统定，不保证准点）。
  只在取证模式下写 `wake` 行，平时这条心跳纯做兜底，不灌水进账本。
- 停服 / 销毁时 `cancelWakeAlarm()`，不让它变成开机自启。

网页侧（`lab 10-26.8` 起）已经把这两类行接上判读：结论后面直接写
「CPU 全程醒着（开机时钟也走了 N 秒）→ 手机没睡，是壳的进程被冻住 · 已进入 Doze · GPS 开关被关了」
这类物证，并把每个大空档的「闹钟 N/M」列出来。

## 十、覆盖率这把尺子在改之前量不准（76% ≠ 冻）

一台机熄屏实测（0.3.2-tune）：2 分 37 秒里 壳累计 tick 1704、账本 151 行、取到 29 次点、
失败 45 次（全是超时），页面算出 tick 覆盖率 76% 并给「异常」，可轨迹一个点都没丢。

原因是取证节拍不是独立的一秒滴答：`onTick` 发一次 `requestSingleUpdate` 之后，要等这一发落定
（`onFix`，或 1000 + 800 ms 的看门狗 `onFixWatchdog`）才 `scheduleNext(1000)`。一次循环的真实长度是
`1 秒 + 等回包的时间`。熄屏、GPS 冷的时候等回包常常就吃掉那 800 ms：74 次请求 × 平均 2.1 秒 ≈ 157 秒，
正好铺满跨度；页面按「应有 158 拍」去算，就凭空少了 38 拍。这些「空秒」里进程在等回包，不是没被调度。

网页侧已经改掉这个误判（`lab 10-26.10`）：额外算「发了多少次请求、平均几秒一次」和「空秒分成几处、
最长连几秒」，2.5 秒以上的空档一处都没有就判「没睡（是尺子不匹配）」，不再报异常。

壳侧待办（下一个 tune）：取证模式下让 `tick` 走独立的一秒链（每个整秒点 `postDelayed` 到下一整秒），
上一发没落定就写 `skip{why:'pending-fix'}`，落定才写 `fix`/`fail`——这样覆盖率才真的等于
「进程活着的时间占比」，熄屏取点慢只会体现为 skip 变多，而不是把覆盖率数字带崩。

## 十一、被杀之后还能自己回来（0.3.4-tune：自启与接链）

前面四件事管的是「服务起来了别再被打断」，这一节管另一种死法：**进程已经没了**。
进程没了以后，壳自己什么都做不了——能敲门的只剩系统还记着的东西。所以这轮一次排了四张网，
每张盖一种死法，代码集中在 `RecoveryArm`（排网）、`RecoveryReceiver`（广播）、
`RecoveryJobService`（周期任务）三处，服务只多了一条 `onRecovery(why)` 入口。

| 死法 | 谁能救 | 到点后的 reason（账本 `why` 字段） |
| --- | --- | --- |
| 系统回收内存 / 服务被单独干掉，进程还在 | `START_STICKY` 重建 + 60 秒心跳闹钟（原有） | `system-restart` / `wake-resume` |
| 划掉最近任务（多数 ROM 等同 force-stop） | `onTaskRemoved` 临走的遗言闹钟，30 秒后 | `task-removed` |
| 进程死了且没人再打开壳 | 12 分钟 `setRepeating` 兜底网（有精确权限时另加一条准点的） | `sweep` / `sweep-exact` |
| 整机重启 | `BOOT_COMPLETED`（含厂商 `QUICKBOOT_POWERON`）+ 15 分钟 `setPersisted` 任务 | `boot` / `job` |
| 被清网之后（force-stop 把闹钟任务全清了） | 用户下一次亮屏 / 解锁 / 覆盖安装 —— 静态广播 | `screen-on` / `unlock` / `replaced` |

四张网全都要过同一道门 `RecoveryArm.gate()`：持久化的 `enabled` 还开着 **且** 精确定位权限还在 **且**
服务当前没在跑。用户关掉了自动打点，接收器与任务反而负责**撤网**（`cancelAll`），不会「以为关过的东西
开机又活了」；只是从页面点「停止」（`enabled` 还开着）时不撤网——那是暂停，不是关门。

### 三条实现上的坑（写错就白排）

- **`PendingIntent` 的匹配不看 extras**。`filterEquals` 只比 action / data / type / class / categories，
  七张网要是共用一个 `requestCode`，后排的顶掉先排的，reason 也会串。所以 reason 编进 `requestCode`
  （`7702 + 序号`，和 60 秒心跳的 7701 隔开），extras 只是给人看的副本。
- **广播里不直接起前台服务**。Android 12+ 后台起 FGS 会被 `ForegroundServiceStartNotAllowedException`
  拒，接收器那 10 秒窗口在很多 ROM 上不算「前台」；拒一次就把这张网白烧一回。规矩是排一个 8 秒后的
  闹钟，让「起服务」那一跳落在闹钟上（闹钟那一跳有豁免，而且失败还能被账本看到）。`RecoveryArm.fromBroadcast`
  里保留了一次直接尝试，纯当对照实验，失败不影响闹钟。
- **周期任务的下限就是 15 分钟**（API 24+），所以它当不了唯一的网；`setPersisted(true)` 又要求清单里有
  `RECEIVE_BOOT_COMPLETED`，两条是绑在一起的。

### 记账

- 新行 `recover{why, stage, enabled, running, exact, job}`：`stage` 取 `broadcast`（接收器，服务还没起来）、
  `task-removed`（遗言）、`job`（周期任务）、`arm`（`onDestroy` 抢前台）、`service`（敲门后落到服务）。
  **不加 diag 门槛**——自启这件事平时也要留痕，不然出事那一次恰好没记。
- 服务还没起来时没有实例可写账，所以多了 `LocationTrackingService.journalStatic(ctx, kind, extra)`：
  直接往同一本 `bg_journal.jsonl` 追加，账本超限时宁可少一行也不复制裁剪逻辑。
- 被拒的两种情况各留一行 `fail`：`why=recover-blocked`（闹钟/广播敲门后起不来，带 `stage` 说明是哪张网）、
  `why=keep-foreground-failed`（`onDestroy` 里抢前台身份失败）。
- `location.status` 多一个 `recovery:{alarms:[…], job:bool, exact:bool}`：服务停着的时候用
  `FLAG_NO_CREATE` 问系统「这张网还排着吗」，`alarms` 为空就是现在一张闹钟网都没有。
- `onDestroy` 里多一手：如果这次销毁**不是**页面/用户要的（`mStopping` 为假）而 `enabled` 还开着，
  就补齐网并重新 `startForeground` 把前台身份抢回来——还挂着前台服务，普通 kill 就杀不掉进程。

### 怎么验（按破坏力从轻到重）

1. 开着自动打点，从最近任务划掉壳 → 等 1 分钟再亮屏：轨迹应当继续长，账本里出现
   `taskRemoved` →（30 秒后）`recover{why:"task-removed",stage:"service"}` → `start{why:"task-removed"}`。
2. 设置里「强行停止」（= force-stop，闹钟任务一起清） → 再重启手机：开机后**不用打开壳**，
   几秒内应出现 `recover{why:"boot"}` → `start{why:"boot"}`。这一步同时验掉「清网之后还剩广播」这条链。
3. `adb shell dumpsys alarm | grep geckoview_example` 看 `sweep` 那张网的下一次到点；
   `adb shell dumpsys jobscheduler | grep -A3 geckoview_example` 看 15 分钟任务在不在（等价于页面的 `recovery`）。
4. 关掉自动打点再重启：账本里**不该**有任何 `recover`/`start`——撤网必须真的撤干净。

### 还没底的

- 划掉任务在用户那台机器上到底等不等于 force-stop（等的话遗言闹钟也会被清，只剩广播）。
- 厂商「自启动」开关没放行时，`SCREEN_ON` / `USER_PRESENT` 是否投得进来；这决定第 5 行那张网能不能救回 force-stop。
- `sweep` 是 `setRepeating`（免权限但不准点），Doze 里可能拖到十几分钟；有 `SCHEDULE_EXACT_ALARM`
  时才有准点那条，用户可以在「特殊应用权限 → 闹钟和提醒」里单独关掉它，所以两条都得留着。

## 十二、main 上的自启线（本轮标注，方便退回检查）

**自启动是随 0.3.4-tune 并入 main 的，功能提交是 `6131522`**（从测试分支
`feat/location-tune` 的 `3a4eb35` 原样搬来，`git log --grep=四张网` 就能定位）。
这一节专为「以后要退回/核对」而写，判读内容本身在 §十一。

- **main 因此多了什么**：三个新类 `RecoveryArm` / `RecoveryReceiver` / `RecoveryJobService`；
  清单里的 `RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`、静态接收器（开机/亮屏/解锁/
  覆盖安装）和 `BIND_JOB_SERVICE` 周期任务服务；`LocationTrackingService` 的
  `onRecovery(why)`、`recoverExtra`、`journalStatic`、`onDestroy` 里抢回前台身份、
  `onTaskRemoved` 里的 30 秒遗言闹钟；`location.status` 新增 `recovery` 字段；
  版本号 `versionCode 7` / `versionName 0.3.4-tune`。
- **main 不包含什么**：分支专属的 `DEFAULT_URL`（main 仍是站点根
  `https://totooicu.github.io/DatingRecord/`，指向磨合台的那一条只在 `feat/location-tune` 上，
  提交 `ef185c0`）；`gradle.properties` 里的构建机 JDK 路径同样不进 main。
  所以从 main 打的包加载的是正式网页，测自启看的是账本里的 `recover`/`start{why}` 行。
- **怎么退回**：`git revert 6131522` 一条就干净——三个新类、清单两条权限与两个组件、
  状态字段、§十一 全在同一条提交里，退完 main 回到「只有前台服务 + 60 秒心跳」的状态。
  若连本节和时间戳一起退，再 `git revert HEAD`（本节的这条标注提交）。
- **怎么认出线上是哪一份**：页面版本条读 `getEnv()` 的 commit 与 builtAt；
  或者 `aapt dump badging` 里看有没有 `RECEIVE_BOOT_COMPLETED`——有就是带自启的那份。
