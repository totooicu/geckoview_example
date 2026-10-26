# 后台续记：壳在后台/熄屏继续收集定位

> 分支 `feat/location-tune`（定位磨合）的探索记录。
> 结论先写前面，实现对照在中间，测试步骤和已知边界在最后。

## 一、要解决的问题

网页端的自动打点是 `setInterval` + `getCurrentPosition`。WebView 一旦退到后台，
Chromium 会把页面的 JS 定时器冻住，打点就断了；熄屏更彻底——整个 App 进程被
系统降级调度。所以「连续定位」不能靠页面，必须由原生侧承担。

本分支把网页端定位功能搬到一个精简的在线测试页（定位磨合台，网页仓库
`public/location-lab/`），壳固定加载它，专门磨「后台还记不记得」。页面暂不并进
网页 main，直接挂在 gh-pages 的 `assets/` 下（部署只清根目录、保留 `assets/`），
线上地址：<https://totooicu.github.io/DatingRecord/assets/location-lab/>。

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
| `AndroidManifest.xml` | `ACCESS_BACKGROUND_LOCATION`、`FOREGROUND_SERVICE_LOCATION`、`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；声明 `LocationTrackingService`（`foregroundServiceType="location"`） |
| `LocationTrackingService` | 前台服务 + 唤醒锁；`START_STICKY` + 只认 enabled 的恢复语义；`onTaskRemoved` 不自杀；JSONL 落盘；通知栏活体证据；诊断字段 |
| `LocationBridge` | `location.*` 全部动作：`setConfig / start / stop / status / getHistory / clearHistory`，新增 `requestPermissions`、`batteryOptimization`、`requestBatteryExemption`；事件 `location.point`、`location.state` |
| `WebViewActivity` | `DEFAULT_URL` 指向定位磨合台；`onPageFinished` 后 `pullLocationConfig()` 主动读页面的 `dr.autoLocation`（页面一次桥都没调也不怕） |
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

- **开机自启**：没有加 `BOOT_COMPLETED` 接收器。要加的话应该做成可选开关，
  免得用户以为关过的东西开机又活了。
- **国产 ROM 的自启动/省电策略**：壳只能引导（电池优化弹窗、设置页），
  「应用启动管理」这类厂商入口必须用户手动放行，任何 App 都绕不过。
- **Android 11+ 的后台定位权限**：只能跳系统设置由用户选「始终允许」，
  不能再靠一次运行时弹窗拿到。
- **兜底调度**：没接 WorkManager/AlarmManager 兜底；如果系统把前台服务也干掉，
  目前只靠 `START_STICKY` 重建（重建时机由系统定，不保证准点）。
- **轨迹质量**：只有阈值去重，没有速度/漂移过滤；后台期间 GPS 冷启动的第一个点
  可能偏差较大（看 `accuracy`）。