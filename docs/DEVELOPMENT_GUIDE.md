# 长期定位记录 · 原生兜底服务（已下线）

> **状态：已下线（壳 `main` 的 `chore/shell-remove-location` 起移除）。**
> 本文不再描述现行实现，只作为「为什么做过、为什么删掉、想恢复去哪儿找」的留档，
> 按铁律七（写过的文档不许删）保留。
> 现行定位方式：壳**完全不参与定位**，网页用 `navigator.geolocation` 自己取，
> 壳只保留 WebView 的标准定位授权通路（`WebChromeClient.onGeolocationPermissionsShowPrompt`）。

---

## 0. 为什么删掉

| 时间 | 事件 | 结论 |
|---|---|---|
| v1.0.x | 壳里只有老实采样，没有策略 | 定位正常，用户没有抱怨 |
| v1.1.0 / v1.1.1 | 壳里加了「连续打点策略」（同点 2^n 退避）、`appendPoint` 去重、`location.getFix`、传感器判动 | 刷新前正常、**刷新后卡 8 秒**；页面刷新重置了网页侧的 streak，却不会重启壳的服务，`mSameCount` 不归零，采样间隔一路翻到 8s；`getFix` 还自带 8s 超时 |
| 之后 | 壳端继续「优化」策略 | 用户明确结论：**壳端不要设计任何针对定位的策略，越设计越烂** |

根因一句话：**壳里的定位策略越多，壳和网页两套状态机就越容易互相拉扯**——
网页刷新会重置自己那一半，壳那一半不重置，两边就错拍。删掉策略后仍然留着
「壳也在记、页面也在记」的双写通道，于是最终决定整条通道一起删。

> 失败分支（保留不改）：远端分支
> `壳端定位策略与getFix：引入8秒阻塞、刷新后卡顿`（空提交 `d5c27d3` 记录复盘），
> 完整实现在 `18ad673` 及其之前的历史里。要恢复就回那儿捞，别照本文重写一遍。

## 1. 删掉了什么

| 文件 / 配置 | 处理 |
|---|---|
| `jsbridge/LocationBridge.java` | 删（`location.*` 路由 + 权限申请 + 后台权限引导） |
| `jsbridge/LocationConfig.java` | 删（配置模型 + SharedPreferences 镜像） |
| `jsbridge/LocationTrackingService.java` | 删（前台服务：连续打点、appendPoint、JSONL 落盘） |
| `jsbridge/JsBridge.java` | 去掉 `location.` 路由、`mLocation` 字段、`location()` 访问器、`PERM_LOCATION` |
| `WebViewActivity.java` | 去掉 `onPageFinished` 里的 `pullLocationConfig` 与 `cfgToJson` |
| `AndroidManifest.xml` | 去掉 `LocationTrackingService` service 声明、`ACCESS_BACKGROUND_LOCATION`、`FOREGROUND_SERVICE_LOCATION` |
| `values/strings.xml` | 去掉 `request_geolocation` |

**保留**：`ACCESS_COARSE/FINE_LOCATION`（蓝牙扫描要用，网页 `navigator.geolocation` 也要用）、
`onGeolocationPermissionsShowPrompt` 与 `handleGeolocation`（网页定位授权弹窗）。

## 2. 网页侧怎么适配

网页不再假设「壳里一定有 `location.*`」：开始打点前先探一次 `location.status`，
探不到就直接走页面定时器（`locationAuto.ts` / `locationNative.ts`）。这样
**新壳（没定位）与老壳（有定位）都能配对**，不用升 `proto`——升 `proto` 只会把已经
发出去的 1.1.1 老壳标成「协议不一致」，收益为负。

代价要说清：**退到后台 / 熄屏后 WebView 的 JS 定时器会被冻住，长期记录会断**。
这是已知取舍，用户确认接受（「变成浏览器本体直接获取定位信息」）。

---

## 3. 原设计存档（仅供恢复时参考）

### 3.1 架构

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

### 3.2 桥接口（location.*，已全部删除）

| action | 方向 | 参数 / 返回 |
|---|---|---|
| `location.setConfig` | 页面→原生 | `{enabled, thresholdM, strategy, intervalBaseMs, intervalMaxMs}`；按 `enabled` 自动启停服务 |
| `location.start` | 页面→原生 | 申请定位权限（缺省时）→ 启动服务；返回 status + `alreadyRunning` |
| `location.stop` | 页面→原生 | 停止服务 |
| `location.status` | 页面→原生 | `{tracking, enabled, thresholdM, strategy, intervalBaseMs, intervalMaxMs, startedAt, points, rev, lastPoint}` |
| `location.getHistory` | 页面→原生 | `{points:[...], rev}`（新壳支持 `since` 游标） |
| `location.clearHistory` | 页面→原生 | 清空记录 |
| `location.point` | 原生→页面(事件) | 每记录一点实时推送 |
| `location.getFix` | 页面→原生 | **曾短暂存在过，是 8 秒卡顿的元凶之一，先于整条通道被删** |

### 3.3 配置（dr.autoLocation）

权威在网页 `localStorage['dr.autoLocation']`；壳在 `onPageFinished` 里
`evaluateJavascript` 读一次（已随 `pullLocationConfig` 一起删）。

| 字段 | 默认 | 语义 |
|---|---|---|
| `enabled` | false | 是否开启长期记录 |
| `thresholdM` | 5 | 去重阈值（米）；0 = 不去重 |
| `strategy` | `backoff` | `fixed` 定时 / `backoff` 同点 2^n 退避 |
| `intervalBaseMs` | 1000 | 基准间隔 |
| `intervalMaxMs` | 64000 | backoff 封顶 |
| `moveThresholdM` | 10 | 旧字段（壳 ≤1.0.1）：驱动 1s/30s/60s 三档 |

### 3.4 连续打点策略（出问题的那一份）

- **fixed**：每次等 `intervalBaseMs`。
- **backoff**：`n` = 连续落在同一个点的次数 → 等 `min(intervalBaseMs × 2^n, intervalMaxMs)`，
  默认序列 1, 2, 4, 8, 16, 32, 64… 秒。

**致命点**：`n` 只活在壳的服务进程里。页面刷新 = 网页侧的连击计数归零，
壳的服务没重启（`startTracking()` 开头 `if (sRunning) return`）= 壳的 `n` 不归零，
于是「刷新前 1s 一个点、刷新后 8s 一个点」。

### 3.5 appendPoint 原生复刻

| 网页规则 | 原生实现 |
|---|---|
| 只与 rows 最后一条比 | 只与内存 `mLastPoint` 比（启动时从文件尾恢复） |
| haversine 球面距离 | `haversineMeters()`，同公式 |
| 阈值内：更新 ts + accuracy，不新增 | 命中 → 改写 `mLastPoint` 的 ts/accuracy |
| 阈值外：新增点，rev+1，立即写盘 | 未命中 → `addPoint`：rev+1、追加 JSONL 一行 |
| 仅时间刷新：最多 15s 落一次盘 | `maybePersistTimeRefresh()`（FLUSH_MS=15000） |

> 网页侧的写策略后来改成「新点跟**倒数第二个**点比，比阈值近就整体顶替末点」，
> 与这里存档的「只跟末点比」已经不是同一套——恢复时必须以网页 `locationAuto.ts` 为准重对齐。

### 3.6 落盘格式

`filesDir/location_log.jsonl`，每行一个点：

```json
{"ts":1729750000000,"lat":30.123,"lng":120.456,"accuracy":8.5,"rev":12}
```

### 3.7 权限与生命周期（已随服务一起删）

`ACCESS_BACKGROUND_LOCATION`（Android 10+ 后台定位，只能从设置页授予）、
`FOREGROUND_SERVICE_LOCATION`（Android 14+ 前台服务类型）、
service `LocationTrackingService`（`foregroundServiceType="location"`）、
`START_STICKY`（被杀后重启并从文件尾恢复）、常驻通知渠道 `location_tracking`。
