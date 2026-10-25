# 长期定位记录 · 原生兜底服务开发文档

> 对应版本：v0.2.1+（WebView 壳）
> 功能：网页后台/锁屏期间由原生前台服务持续记录定位，与网页自身 `locationAuto.ts`
> 的算法语义严格对齐（频率状态机 + appendPoint 去重）。

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
                                               │ SensorManager（手机动检测）
                                               │ LocationManager（定位）
                                               │ 频率状态机 + appendPoint
                                               ▼
                                        filesDir/location_log.jsonl
```

## 2. 桥接口（location.*）

| action | 方向 | 参数 / 返回 |
|---|---|---|
| `location.setConfig` | 页面→原生 | `{enabled, thresholdM, moveThresholdM, intervalFastMs?, intervalMidMs?, intervalSlowMs?}`；按 `enabled` 自动启停服务 |
| `location.start` | 页面→原生 | 申请定位权限（缺省时）→ 启动服务；返回 status + `alreadyRunning` |
| `location.stop` | 页面→原生 | 停止服务 |
| `location.status` | 页面→原生 | `{tracking, enabled, thresholdM, moveThresholdM, startedAt, points, rev, lastPoint{ts,lat,lng,accuracy,note}}` |
| `location.getHistory` | 页面→原生 | `{points:[{ts,lat,lng,accuracy,rev,...}], rev}` |
| `location.clearHistory` | 页面→原生 | 清空记录 |
| `location.point` | 原生→页面(事件) | 每记录一点实时推送 `{ts,lat,lng,accuracy,rev}` |

页面端用法：

```js
// 推送配置（页面 localStorage 变更时也调用，保证热同步）
await bridge('location.setConfig', {
  enabled: true,
  thresholdM: 5,
  moveThresholdM: 10,
});

// 开启 / 状态 / 历史
await bridge('location.start');
const st = await bridge('location.status');
const hist = await bridge('location.getHistory');
hist.points.forEach(p => appendPoint(p)); // 复用网页去重逻辑合并
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
| `enabled` | false | 是否开启长期记录；true 且有定位权限时自动启动服务 |
| `thresholdM` | 5 | 去重阈值（米）；非数字/负数按 0（不去重，每秒落点） |
| `moveThresholdM` | 10 | 「定位动」判定位移阈值（米），驱动 1s 快速档 |
| `intervalFastMs` | 1000 | 定位动：1s |
| `intervalMidMs` | 30000 | 手机动+定位不动：30s |
| `intervalSlowMs` | 60000 | 手机不动+定位不动：60s |

## 4. 频率状态机（原生实现）

- **定位动**：本次定位与上一次原始定位的 haversine 距离 `> moveThresholdM` → 1s 档。
- **手机动**：加速度计三轴变化幅度 `> 0.4 m/s²` 记为动，动窗口 1.5s → 30s 档（叠加在
  「定位不动」之上）。
- **都不动** → 60s 档。

实现：`Handler` 定时器按当前间隔调度；每次 tick `requestSingleUpdate` 取一次定位；
收到 fix 后评估状态 → `scheduleNext(nextInterval())`。定位请求带看门狗
（interval + 800ms 无回调则跳过），GPS 信号差不会卡死循环。
传感器用 `TYPE_ACCELEROMETER`（SENSOR_DELAY_NORMAL），作为陀螺仪的功耗近似；
要更准可换成 `TYPE_GYROSCOPE`，判定逻辑不变。

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
- `enabled=false` 或 `location.stop` → `stopTracking()` 注销传感器、停定时器、`stopForeground`。

## 8. 测试要点

| 场景 | 预期 |
|---|---|
| 页面加载后（dr.autoLocation.enabled=true 且已授权） | 通知出现「正在记录位置」，`location.status` tracking=true |
| 静止场景 | 日志间隔接近 60s；点之间 haversine < 阈值则只刷新 ts/accuracy |
| 走动场景 | 间隔切到 1s；位移超过 moveThresholdM 的每次 fix 都新增点 |
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
| `jsbridge/LocationTrackingService.java` | 前台服务：状态机、传感器、appendPoint、JSONL 落盘 |
| `jsbridge/LocationBridge.java` | location.* 桥路由 + 权限申请 + 后台权限引导 |
| `jsbridge/JsBridge.java` | 路由接入（`location.` 前缀）、PERM_LOCATION |
| `WebViewActivity.java` | onPageFinished 拉取 dr.autoLocation 同步原生 |
| `AndroidManifest.xml` | 后台定位/前台服务类型权限 + service 声明 |
