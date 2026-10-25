# 长期定位记录 · 原生兜底服务开发文档

> 对应版本：v1.1.1+（WebView 壳）
> 功能：网页后台/锁屏期间由原生前台服务持续记录定位。
> **壳不做任何定位策略**：按 `intervalBaseMs` 固定间隔取一次定位，取到就存一条、
> 推一条事件；去重 / 顶替 / 退避全部归网页 `locationAuto.ts`。

---

## 1. 功能与架构

「长期活动」需要 App 在网页不可见（切后台、锁屏）时继续工作，而网页 JS 在后台会被
系统冻结。因此采用**混合方案**：

| 阶段 | 谁来记 | 说明 |
|---|---|---|
| App 前台、页面可见 | 网页 `watchPosition` 自己记 | 实时轨迹、UI 展示 |
| App 后台 / 锁屏 | 原生 `LocationTrackingService` 兜底 | 前台服务常驻，继续采点落盘 |
| 页面重新打开 | 网页 `location.getHistory` 拉取 | 逐点走网页自己的 `appendPoint` 合并 |

```
页面 JS ──postMessage──> ADAPTER_JS ──> JsBridge ──> LocationBridge
                                                       │ 权限申请 / 配置同步
                                                       ▼
                                              LocationTrackingService（前台服务）
                                               │ LocationManager（定位）
                                               │ 固定间隔 intervalBaseMs 取一次，取到就存/推
                                               │（不去重、不退避，策略全在网页）
                                               ▼
                                        filesDir/location_log.jsonl
```

## 2. 桥接口（location.*）

| action | 方向 | 参数 / 返回 |
|---|---|---|
| `location.setConfig` | 页面→原生 | `{enabled, thresholdM, strategy, intervalBaseMs, intervalMaxMs}`；按 `enabled` 自动启停服务；`enabled` 但缺定位权限时先弹系统权限申请（同 `location.start`），拒绝回 `ok:false`（不静默跳过） |
| `location.start` | 页面→原生 | 申请定位权限（缺省时）→ 启动服务；返回 status + `alreadyRunning` |
| `location.stop` | 页面→原生 | 停止服务 |
| `location.status` | 页面→原生 | `{tracking, enabled, thresholdM, strategy, intervalBaseMs, intervalMaxMs, startedAt, points, rev, lastPoint{ts,lat,lng,accuracy,note}}` |
| `location.getHistory` | 页面→原生 | `{since?: number}` → `{points:[{ts,lat,lng,accuracy,rev,...}], rev}`；传 `since` 只回 `ts > since` 的点（网页传已合并到的游标，跑久了也不用回传全量） |
| `location.clearHistory` | 页面→原生 | 清空记录 |
| `location.point` | 原生→页面(事件) | 每取到一个点实时推送 `{ts,lat,lng,accuracy,rev}` |

> `location.getFix`（一次性定位）已在 v1.1.2 移除：手动「我的位置 / 打点」现在直接走
> WebView 的 `navigator.geolocation`（`WebSettings.setGeolocationEnabled(true)` 已开），
> 不再需要壳转发。它当年带的 8s 超时正是「刷新后打点要等 8 秒」的来源之一。

页面端用法：

```js
// 推送配置（页面 localStorage 变更时也调用，保证热同步）
await bridge('location.setConfig', {
  enabled: true,
  thresholdM: 5,      // 壳照收但不用：去重归网页
  strategy: 'backoff',// 壳照收但不用：退避归网页
  intervalBaseMs: 1000, // ← 壳只看这一个：固定采样间隔
  intervalMaxMs: 64000, // 壳照收但不用
});

// 开启 / 状态 / 历史
await bridge('location.start');
const st = await bridge('location.status');
const hist = await bridge('location.getHistory', { since: lastMergedTs });
hist.points.forEach(p => appendPoint(p)); // 去重 / 顶替都在网页这一层
```

## 3. 配置（dr.autoLocation）

**配置的权威来源是网页的浏览器存储**，key 为 `dr.autoLocation`。原生无法直接读
localStorage，因此有两路同步：

1. **页面加载完成**：`WebViewActivity.onPageFinished` 主动 `evaluateJavascript`
   读取 `localStorage.getItem('dr.autoLocation')` → 解析 → `location.setConfig`。
2. **运行中变更**：页面保存配置后主动调 `location.setConfig`（若网页不调，原生只在
   每次 App 打开时拉到最新值）。

字段结构：

| 字段 | 默认 | 语义 |
|---|---|---|
| `enabled` | false | 是否开启长期记录；true 且有定位权限时自动启动服务；true 但缺权限时弹系统权限申请，用户拒绝则 `ok:false` 由网页降级 |
| `intervalBaseMs` | 1000 | **壳唯一使用的字段**：固定采样间隔，每这么久取一次定位 |
| `thresholdM` | 5 | 旧字段：去重阈值；**壳照收但不使用**（去重归网页） |
| `strategy` | `backoff` | 旧字段：连续打点策略；**壳照收但不使用**（退避归网页） |
| `intervalMaxMs` | 64000 | 旧字段：backoff 封顶；**壳照收但不使用** |
| `moveThresholdM` | 10 | 旧字段（壳 ≤1.0.1）驱动 1s/30s/60s 三档；只解析不使用 |

> 旧字段保留解析，是为了「新页面 + 旧壳」「旧页面 + 新壳」两种组合都不报错。
> v1.1.2 起壳的调度只由 `intervalBaseMs` 决定。

## 4. 采样：固定间隔，不含策略

`Handler` 定时器每 `intervalBaseMs` 触发一次 tick，`requestSingleUpdate` 取一次定位，
**取到就存一条 JSONL、就推一条 `location.point` 事件**，然后排下一次。不去重、不退避、
不看位移、不看传感器。定位请求带看门狗（interval + 800ms 无回调则跳过），
GPS 信号差不会卡死循环。

> 曾经壳里复刻了网页的「同点 2^n 退避 + 阈值去重」，已删除。两份实现状态必然不同步：
> 页面刷新会把自己退避计数清零，而壳的服务跨刷新活着、计数不清零——表现就是
> 「刷新前 1s 一个点，刷新后变成 8s 一个点」。策略只留一份，放在网页。

## 5. 去重归网页，壳不复刻

| 网页（`locationAuto.ts`） | 壳 |
|---|---|
| `appendPoint()`：跟倒数第二个点比距离，近了就顶掉末点 | 不做判断，取到就 `addPoint()` |
| `nextSampleMs()`：fixed / 同点退避 | 不用，恒定 `intervalBaseMs` |
| 写入节流：原地顶替最多 15s 落一次盘 | 每条立即追加一行 |
| 比较 / 存原始坐标，offset 只影响显示 | 同：只存原始坐标（WGS-84） |

壳的历史因此可能比网页的行数多（原地点也会记），这是预期的：
网页合并时逐点走 `appendPoint`，多出来的点会被顶替掉，行数不会涨。

## 6. 落盘格式

`filesDir/location_log.jsonl`，每行一个点（JSON）：

```json
{"ts":1729750000000,"lat":30.123,"lng":120.456,"accuracy":8.5,"rev":12}
```

- `ts`：毫秒时间戳（location.getTime()）。
- 每取到一个点就追加一行，不改写旧行（壳不去重，也就没有「刷新末行」这回事）。
- 网页合并时逐点走 `appendPoint`，多出来的原地点会被顶替掉，行数不会涨。

## 7. 权限与生命周期

新增权限（Manifest）：

| 权限 | 说明 |
|---|---|
| `ACCESS_BACKGROUND_LOCATION` | Android 10+ 后台定位专用；只能从系统设置页授予 |
| `FOREGROUND_SERVICE_LOCATION` | Android 14+ 前台服务类型声明（必需） |
| service `LocationTrackingService` | `android:foregroundServiceType="location"` |

启动流程（`location.start`）：

1. 检查 `ACCESS_FINE_LOCATION` → 缺则运行时弹窗申请（PERM_LOCATION=1003）。
2. 授权后检查 `ACCESS_BACKGROUND_LOCATION` → 未授予则 Toast 提示并打开 App 系统设置页，
   引导开启「始终允许使用位置」；服务仍先启动（前台期间可正常记录）。
3. `startForegroundService` → 服务内先 `startForeground`（API 29+ 三参带
   `FOREGROUND_SERVICE_TYPE_LOCATION`，**必须先于定位工作**，同录屏规则）→ 恢复尾部数据 → 采集。

生命周期要点：

- `START_STICKY`：服务被系统杀后自动重启；`onStartCommand(intent==null)` 走恢复分支，
  从文件尾部恢复 lastPoint/rev/points 后继续。
- 常驻通知（渠道 `location_tracking`，含「停止」动作）。
- `enabled=false` 或 `location.stop` → `stopTracking()` 停定时器、`stopForeground`。

## 8. 测试要点

| 场景 | 预期 |
|---|---|
| 页面加载后（dr.autoLocation.enabled=true 且已授权） | 通知出现「正在记录位置」，`location.status` tracking=true |
| 静止场景 | 间隔按 1→2→4→…→64s 递增并在 64s 停住；点之间 haversine < 阈值则只刷新 ts/accuracy |
| 走动场景 | 每次位移产生新点，间隔回到 1s；轨迹点密 |
| 切后台 / 锁屏 5 分钟 | 服务仍在采集（通知常驻），重开页面 `getHistory` 能补齐 |
| 杀掉 App 再开 | START_STICKY 重启续记；末行恢复不重复 |
| `clearHistory` | 文件清空、rev=0、points=0 |

## 9. 已知取舍（与网页一致）

1. 只与最后一点比较：绕一圈回原地会另记新点（网页同样如此）。
2. 不看 accuracy 参与去重：GPS 抖动到阈值外会多打点，调大 `thresholdM` 即可。
3. 手动打点命中阈值时备注不覆盖（原生侧不涉及，网页语义如此）。
4. 原生实现同样只需改 `appendPoint` 一处，即可升级为「和最近 N 个点比」或「按精度加权」。

## 10. 文件清单

| 文件 | 职责 |
|---|---|
| `jsbridge/LocationConfig.java` | 配置模型 + SharedPreferences 镜像 + JSON 规范化 |
| `jsbridge/LocationTrackingService.java` | 前台服务：连续打点调度、appendPoint、JSONL 落盘 |
| `jsbridge/LocationBridge.java` | location.* 桥路由 + 权限申请 + 后台权限引导 |
| `jsbridge/JsBridge.java` | 路由接入（`location.` 前缀）、PERM_LOCATION |
| `WebViewActivity.java` | onPageFinished 拉取 dr.autoLocation 同步原生 |
| `AndroidManifest.xml` | 后台定位/前台服务类型权限 + service 声明 |
