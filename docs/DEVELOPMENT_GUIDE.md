# 长期定位记录 · 原生兜底服务开发文档

> 对应版本：v1.1.1+（WebView 壳）
> 功能：网页后台/锁屏期间由原生前台服务持续记录定位，与网页自身 `locationAuto.ts`
> 的算法语义严格对齐（连续打点策略 + appendPoint 去重）。

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
                                               │ 连续打点策略（定时 / 同点 2^n 退避）+ appendPoint
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
| `location.getHistory` | 页面→原生 | `{points:[{ts,lat,lng,accuracy,rev,...}], rev}` |
| `location.clearHistory` | 页面→原生 | 清空记录 |
| `location.getFix` | 页面→原生 | 一次性定位（手动「我的位置 / 打点」用）：与连续打点同一个原生 GPS 源，绕开 WebView 拦截的 `navigator.geolocation`（那条在壳里常『申请了定位却回不来坐标』）；成功回 `{lat, lng, accuracy}`（WGS-84，米，页面换算 GCJ-02），失败回 `ok:false`（如定位权限被拒、信号不可用）；缺定位权限时先弹系统权限申请，拒绝回 `ok:false`；不要静默不回，否则页面卡到超时 |
| `location.point` | 原生→页面(事件) | 每记录一点实时推送 `{ts,lat,lng,accuracy,rev}` |

页面端用法：

```js
// 推送配置（页面 localStorage 变更时也调用，保证热同步）
await bridge('location.setConfig', {
  enabled: true,
  thresholdM: 5,
  strategy: 'backoff', // 'fixed' = 定时；'backoff' = 同点 2^n 退避
  intervalBaseMs: 1000,
  intervalMaxMs: 64000,
});

// 开启 / 状态 / 历史
await bridge('location.start');
const st = await bridge('location.status');
const hist = await bridge('location.getHistory');
hist.points.forEach(p => appendPoint(p)); // 复用网页去重逻辑合并

// 手动一次性定位（「我的位置 / 打点」优先走这条，与连续打点同 GPS 源）
const fix = await bridge('location.getFix');
// fix = { lat, lng, accuracy } —— WGS-84，页面换算成 GCJ-02
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
| `thresholdM` | 5 | 去重阈值（米）；非数字/负数按 0（不去重，每次取点都落点） |
| `strategy` | `backoff` | 连续打点策略：`fixed` 定时；`backoff` 同点 2^n 退避 |
| `intervalBaseMs` | 1000 | 基准间隔：fixed 的固定周期 / backoff 的 2^0 档 |
| `intervalMaxMs` | 64000 | backoff 封顶；默认 64s（n=6 起不再翻倍） |
| `moveThresholdM` | 10 | **旧字段（壳 ≤1.0.1）**：驱动 1s/30s/60s 三档；新壳只解析不再使用 |

> 旧字段保留解析，是为了「新页面 + 旧壳」和「旧页面 + 新壳」两种组合都不报错；
> 新壳的调度完全由 `strategy` / `intervalBaseMs` / `intervalMaxMs` 决定。

## 4. 连续打点策略（原生实现）

页面与壳共用同一份规则（网页 `locationAuto.ts` 的 `nextSampleMs()`）：

- **fixed**：每次等 `intervalBaseMs`（默认 1s）。
- **backoff**：设 `n` = 「连续落在同一个点的次数」（去重命中即 +1，新增点归零），
  下一次等待 `min(intervalBaseMs × 2^n, intervalMaxMs)`。
  默认参数下序列是 1, 1, 1, 2, 4, 8, 16, 32, 64, 64, 64… 秒。
  一旦位移超过 `thresholdM` 产生新点，`n` 立刻归零，回到 1s —— 走动时轨迹是密的，
  只有真的不动才逐步放慢。

实现：`Handler` 定时器按 `nextInterval()` 调度；每次 tick `requestSingleUpdate` 取一次
定位；收到 fix 后 `appendPoint()` 返回是否新增 → 更新 `mSameCount` → 排下一次。定位请求
带看门狗（interval + 800ms 无回调则跳过），GPS 信号差不会卡死循环。

> v1.1.0 起移除了加速度计与 1s/30s/60s 三档：频率只由「是否还在同一个点」决定，
> 网页/壳看到的行为完全一致。

## 5. appendPoint 原生复刻（与网页语义一一对应）

| 网页规则 | 原生实现（LocationTrackingService.appendPoint） |
|---|---|
| 只与 rows 最后一条比 | 只与内存 `mLastPoint`（含启动时从文件尾恢复）比 |
| haversine 球面距离 | `haversineMeters()`，同公式 |
| 阈值内：更新 ts + accuracy，保留坐标/note，不新增，rev 不变 | 命中 → 改写 `mLastPoint` 的 ts/accuracy，不写新行 |
| 阈值外：新增点，rev+1，立即写盘 | 未命中 → `addPoint`：rev+1、追加 JSONL 一行 |
| 仅时间刷新：最多 15s 落一次盘（FLUSH_MS=15000） | `maybePersistTimeRefresh()`：距上次落盘 ≥15s 才重写末行 |
| 手动打点每次都 persist | （网页手动打点发生在页面可见时，由网页自己处理） |
| 阈值 0 = 不去重 | `thresholdM<=0` 直接走新增分支 |
| 比较原始坐标，offset 只影响显示 | 原生只存原始坐标 |

## 6. 落盘格式

`filesDir/location_log.jsonl`，每行一个点（JSON）：

```json
{"ts":1729750000000,"lat":30.123,"lng":120.456,"accuracy":8.5,"rev":12}
```

- `ts`：毫秒时间戳（location.getTime()）。
- 新增点：文件追加一行。
- 时间刷新命中：删除末行后重写（文件小，开销可接受），保证读回语义与网页 rows 一致。
- 网页合并时逐点 `appendPoint` 会再次去重，两边语义幂等。

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
