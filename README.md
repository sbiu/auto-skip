# Auto Skip

一个只做一件事的 Android 小工具：**跳过各 App 的开屏广告**。
通过无障碍服务识别开屏广告上的“跳过 / 跳过广告 / 关闭广告 / skip”并点击，不联网、不采集、不常驻轮询。

> 目标机型：Android 7.0+（minSdk 24）。本仓库自带无 Gradle 的构建脚本，直接产出签名 APK。
>
> 本仓库只包含源码、资源、构建脚本和文档；构建产物（`dist/`）、截图（`shots/`）、签名密钥（`keystore/`）不入库。
> 仅个人学习用途，按现状提供，不附带任何担保。

## 交付物

| 文件 | 说明 |
|---|---|
| `dist/zhangtiaotiao-<版本>.apk` | 构建产物：已签名安装包（不入库） |
| `docs/ad-node-samples.md` | 2026-11-02 真机开屏节点采样与匹配失效分析 |
| `build.ps1` / `build-testapp.ps1` | Windows 构建脚本（aapt2 + javac + d8 + zipalign + apksigner） |
| `tools/build-linux.sh` | Linux/arm64 构建脚本（aapt + javac + d8 + zipalign + apksigner） |
| `tools/adtest.py` | 冷启动采样脚本（ADB + 截图饱和度判断是否出现开屏广告） |
| `testapp/` | 假开屏广告测试 App（验证点击/手势两条链路） |

## 构建

依赖：Android SDK build-tools 35.0.1、platforms/android-35、JDK 21（路径在脚本首部可改）。

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Linux / arm64（本机 Debug 环境，apt 装 aapt/zipalign/apksigner + openjdk-17）：

```bash
bash tools/build-linux.sh
# 产物：dist/zhangtiaotiao-1.9.apk（不入库）
```

签名使用自建 keystore：`keystore/autoskip.jks`（仅测试用途，口令固定为 `android`）。
该文件不入库；`tools/build-linux.sh` 首次运行会自动生成一个，`build.ps1` 也会。

## 安装与启用

```powershell
adb install -r dist/zhangtiaotiao-1.9.apk
```

1. 打开 Auto Skip → “去开启无障碍服务” → 找到 **Auto Skip 开屏跳过** 并打开；
2. 国产 ROM（MIUI/HyperOS/ColorOS 等）请在系统设置里把本应用的省电策略设为 **无限制**，
   否则系统可能在后台把它冻结/解绑，导致偶发失效（这是系统行为，不是应用逻辑）。
3. 之后正常打开任意 App，开屏广告上的“跳过”会在约 0.2–0.5 秒内被点掉。

> **产品名**：张跳跳（v1.5 起）。包名仍是 `com.autoskip.app`，覆盖安装即可升级、不丢无障碍授权。

## 实现结构

```
AndroidManifest.xml                 无 INTERNET 权限；service 受 BIND_ACCESSIBILITY_SERVICE 保护；
                                    MainActivity 带 excludeFromRecents（不在任务管理器出现）
res/xml/accessibility_service_config.xml   事件类型/超时/能力声明
java/com/autoskip/app/SkipService.java     事件调度、窗口期、节流、点击/手势、开关应用
java/com/autoskip/app/SkipMatcher.java     文案匹配 + 几何/可点击性打分
java/com/autoskip/app/SkipState.java       开关状态（关闭时 eventTypes=0）
java/com/autoskip/app/MainActivity.java    状态、开关、计数、跳转设置
```

### 开关

- 主界面“启用跳过”开关，默认开启；状态存 `SharedPreferences`，重启/重连后保持。
- 关：服务把 `eventTypes` 置 0，系统**不再派发任何事件**（`dumpsys accessibility` 显示 `eventTypes=` 为空），后台零开销；
- 开：恢复 3 种窗口事件并广播通知服务立即生效，无需重开无障碍服务。

### 不在任务管理器显示

`MainActivity` 设置了 `android:excludeFromRecents="true"`：打开过 Auto Skip 后按 Home，
最近任务（含 MIUI“上滑暂停”）里不会出现它的卡片，因此无法被上滑划掉误杀；
应用进程由系统绑定的无障碍服务维持。

### 自愈与诊断

- **服务重连自愈**：系统若在后台解绑过无障碍服务，重连时可能已经错过开屏广告的窗口事件。
  服务在 `onServiceConnected` 后会检查当前前台窗口，若存在可匹配节点立即补一次窗口期并点击。
- **丢失窗口事件的兜底**：如果 `TYPE_WINDOW_STATE_CHANGED` 没送达（服务刚重连、系统繁忙），
  内容变化事件里检测到“前台包切换”时会补一次 arm（必须与上一条事件来源不同，避免同一页面反复扫描）。
- **窗口过期后再次启动会重新开窗**：如果上一次启动没有广告（窗口期自然过期），
  同一个 App 再次进入前台时不会因为“包名相同”被忽略，而是重新 arm 一次
  （这是此前知乎偶发不跳的直接原因之一）。
- **不会因上次点击而漏掉下次**：是否“已经点过”只用本次窗口内的 `recorded / gestureAttempts` 判断，
  不再受上一条遗留时间戳影响（旧版本会导致同一 App 连续两次启动时第二次直接取消窗口期）。
- **扫描预算**：12 秒窗口内最多 80 次扫描、相邻 ≥120ms，避免广告晚出现时额度已被内容刷新耗光。
- **界面诊断**：主界面“诊断”一行显示累计事件数、最近事件来源，以及“最近扫描：<包名> 第 N 次扫描：命中/未命中/旧窗口”。
  没跳过时打开 App 看这一行即可定位：没有事件（服务未生效/被系统关闭）、未命中（窗口里没有可识别的“跳过”控件）、旧窗口（切换时序问题）。

### 判定流程

1. 任意无障碍事件到达时判断“前台会话”：包名变化，或距上一条应用事件 >1.2 秒（说明中间切出过/回后台/热启动）→ 开始一次 15 秒会话；
   冷启动、杀进程重开、从最近任务回来、热启动广告都走这一条路径；
2. 会话内收到内容变化 → 120ms 节流后扫描；一次会话最多成功跳过 1 次（避免广告关闭后的界面切换重复计数）；
3. 扫描：`getRootInActiveWindow()` + `getWindows()` 收集**当前前台包名下的所有窗口根节点**
   （很多开屏广告是浮在主界面之上的独立窗口，只读 active window 会一直拿到下面的正文页）→
   对每个窗口树做有界 BFS（深度 ≤14、节点 ≤400），按“标签形态 + 位置/尺寸/层级”打分取最优；
4. 点击：节点或 3 层内祖先可点击 → `ACTION_CLICK`；否则按节点 bounds 中心 `dispatchGesture` 轻点；
5. 手势完成后回查一次：广告消失即结束；仍在则再试（最多 3 次），每次启动只计 1 次；
6. 命中“跳过更新/跳过登录/跳过验证……”等非广告文案直接拒绝；`view id` 只匹配 `:id/` 之后的资源名；
7. 低置信的“关闭”只接受屏幕最上方 18% 的右上角区域（正文/卡片里的“关闭”“×”一律不点）；
8. 点过一次后本次会话结束，不再扫描正文页；切到桌面/别的 App 或间隔 >1.2 秒再回来会开新会话。

### 已知 App 的节点特征（真机采样）

| App | 开屏广告里的“跳过”节点 | 处理方式 |
|---|---|---|
| 知乎 | 旧模板 `text="跳过5"`（`btn_skip`）；2026-11 新模板 `text="4 关闭"`，右上角 | `ACTION_CLICK`（新模板走“倒计时+关闭”低置信规则） |
| B 站 | `text="跳过 3"`，`tv.danmaku.bili:id/count_down`，可点击 | `ACTION_CLICK` |
| 微博 | `text="跳过"`，无 resource-id，**不可点击且无 clickable 祖先**，位于右下角 | 手势点按文本中心 |
| 脉脉 | 暂未抓到开屏样本 | 通用规则兜底（跳过/关闭 + 角落） |

### 省电设计（重点）

| 措施 | 数值/做法 |
|---|---|
| 事件订阅 | 只订阅 3 种窗口类事件（不是 0xffffffff 全事件） |
| 事件合并 | `notificationTimeout=100ms` |
| 工作窗口 | 进入前台后的 15 秒会话；其余时间回调直接 return |
| 扫描节流 | 同一窗口期内相邻扫描 ≥120ms，最多 100 次（覆盖 15 秒窗口，避免内容刷新把额度耗光） |
| 点击冷却 | `ACTION_CLICK` 成功后 1.5 秒内不再扫描（只在同一会话内生效，不阻塞重开） |
| 手势重试 | 最多 3 次，且由手势回调驱动，不做轮询 |
| 场景屏蔽 | 桌面、SystemUI、自身、息屏（`isInteractive()`）直接跳过 |
| 常驻组件 | 无前台服务、无定时器、无 JobScheduler、无网络权限、无 WakeLock |

## 验证记录（真机 Android 16）

1. **可点击节点**：假开屏广告 `跳过 5`（clickable）→ `ACTION_CLICK`，测试 App 的 `onClick` 触发（logcat `TestAd: CLICKABLE_AD_CLICKED`）。
2. **不可点击节点**：`跳过广告`（仅 OnTouchListener）→ 手势兜底，测试 App 收到 `ACTION_UP`（`TestAd: GESTURE_AD_CLICKED`）。
3. **微博同构节点**：复刻微博结构（右下角不可点击 `跳过` + 父容器处理触摸）→ 手势点中文本，容器收到 `ACTION_UP`（`TestAd: WEIBO_STYLE_CLICKED`），服务同时计数。
4. **真实 App**：
   - 知乎：`LaunchAdActivity` 命中 `跳过5` 并点击（`AutoSkip: skipped com.zhihu.android`）；
   - B 站：冷启动命中 `跳过 3`（`tv.danmaku.bili:id/count_down`）并点击（`AutoSkip: skipped tv.danmaku.bili`）；
   - 微博：真机上抓到过开屏广告与 `跳过` 节点（右下角、不可点击、无 clickable 祖先），
     已用同构测试 App 验证该结构可被手势点掉；微博广告为频次投放，**未能按需复现**端到端跳过。
5. **开关**：关 → `eventTypes` 变空、假广告实拍仍在屏且无点击、无 skipped 日志；开 → 同一广告被点掉并计数。
6. **服务重连自愈**：广告已显示时把无障碍服务关掉再打开，重连后 0.3 秒内自动补点（`TestAd: CLICKABLE_AD_CLICKED` + `skipped`）。
7. **知乎复测**：修复后连续 5 次冷启动，5/5 命中并跳过（`AutoSkip: skipped com.zhihu.android`）。
8. **不在任务管理器**：打开 Auto Skip 后按 Home，最近任务界面无该应用卡片。
9. **误点防护**：窗口切换瞬间 a11y 根节点仍是旧应用时，包名校验阻止了在 Auto Skip 自身界面上误点（修复前曾把“本次安装已跳过 N 次”判为可点）。

### 2026-11-03 修复记录（B 站热启动不跳 / 冷启动“隔次失效”）

另一台真机复现：杀进程重开 B 站时一次跳一次不跳；不杀进程（B 站在后台）从最近任务回来则永远不跳。
对照代码定位到 v1.2 状态机的两个缺陷：

1. **`arm()` 里“刚跳过 8 秒内不再开窗口”**：快速杀进程重开时，第二次启动被整段跳过（`skippedPackage` 封锁），
   下一次超过 8 秒又正常 → 呈规律交替；
2. **内容事件分支要求 `!pkg.equals(lastEventPackage)` 才补开窗口**：热启动/回前台往往没有新的 Activity，
   只有广告倒计时带来的内容变化事件，而 `lastEventPackage` 早已是同一个包名 → 永远不开窗口；
3. 窗口过期后分支直接 `return`，不清状态也不重开，迟到广告同样错过。

v1.3 改为“前台会话”模型：只保留 `foregroundPackage / sessionStartedAt / lastAppEventAt / clickedThisSession`
四个状态；包名变化或事件间隔 >1.2 秒即开新会话；删除 8 秒封锁与 `lastEventPackage` 门槛；
系统浮层（MIUI 灵动岛/负一屏/搜索框）加入忽略列表；一次会话点过一次即停。

验证（本机 Android 16）：
- 前台停留 15 秒后才弹出“跳过 5”广告（只有内容事件）→ 新会话第 1 次扫描即命中并点击；
- 连续 3 次“杀进程→打开”测试 App，跳过计数 9→10→11→12 逐次递增，不再交替；
- 知乎冷启动命中 `跳过5` 正常；
- 说明：本机 MIUI 对第三方无障碍服务偶发静默断流（服务显示 bound 但收不到事件，DSH 自己的无障碍也会被系统关掉），
  期间无法稳定复测热启动端到端；核心的“无窗口事件、仅内容事件”路径已由上面的延迟广告用例覆盖。

### 2026-11-02 修复记录（知乎/B站/微博/脉脉不跳）

1. 真机（Android 16 / HyperOS 3.0）确认无障碍服务一直 enabled+bound、事件 13 万+，
   “后来不跳”不是服务被关，而是**广告模板变了**：知乎开屏从 `跳过5` 变成 `4 关闭`，旧词表完全匹配不到。
2. `SkipMatcher` 重写：有界 BFS + 标签形态判定（跳过/关闭/倒计时+关闭/skip）+ 位置/尺寸/层级约束；
3. `SkipService` 改为遍历 `getWindows()`（浮层广告）、运行时加 `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`
   与 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`，窗口期 12s→15s，未命中时保存窗口快照到诊断区；
4. 防止误点：低置信“关闭”只认右上角顶部区域，跳过成功后同一 App 8 秒内不再开窗口；
5. 实测：知乎新模板 `跳过5`/`4 关闭` 均命中（`skipped com.zhihu.android`，计数 72→73）；
   微博 6 次冷启动中 5 次出现广告，跳过计数 58→67，之后广告进入频次冷却未能继续复测；
   B 站/脉脉测试窗口内未投放开屏广告（详见 `docs/ad-node-samples.md`）。

### 2026-11-03 晚 修复记录（v1.4：几小时后彻底不跳）

现象：早上正常，中午以后所有 App 都不跳；重装/打开一次 Auto Skip 又恢复。
实测证据（MIUI 日志）：

```
SmartPower: com.autoskip.app(...): background->idle(3002ms) R(become background) adj=200
vendor.qti...servicetrackeraidl: destroyService is called for service : com.autoskip.app/.SkipService
vendor.qti...servicetrackeraidl: bindService is called for service : com.autoskip.app/.SkipService
```

根因：应用退到后台约 3 秒就被 MIUI SmartPower 打成 idle，无障碍服务被 destroy，系统不保证立刻 bind 回来；
v1.3 没有任何前台组件或看门狗，服务掉线后没人把它拉回来，只能等用户打开应用。

v1.4（学李跳跳的常驻方案）：
1. **GuardService 前台服务 + ongoing 常驻通知**（`FLAG_ONGOING_EVENT|NO_CLEAR`，用户滑不掉；
   每 60 秒检查通知是否被 ROM 清掉，被清就重新贴）；
2. `START_STICKY` + `stopWithTask=false` + **BootReceiver**：被杀/重启后自动回来；
3. **看门狗**：无障碍被系统关掉 → 通知提示重开；系统显示已连接但 `getRootInActiveWindow()`
   连续 3 次拿不到根节点（客户端假死）→ 杀进程让系统重新 bind（10 分钟冷却）；
4. 主界面新增“常驻通知守护”开关、“省电策略：设为无限制”“自启动设置（MIUI）”按钮；
5. 诊断区新增“服务连接：已连接/未连接（最近连接/断开时间）”。

验证：常驻通知 flags=ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE；
息屏 3 分钟后唤醒，服务仍 connected、事件 1–3 秒前刚有，开屏广告照常跳过（count 13→14→15）；
模拟系统关闭无障碍 → 出现“去重新开启”通知，重新开启后自动清除并恢复；
MIUI 仍会标记 idle，但进程 adj 从 200 提升到 50（前台服务级），服务不再被 destroy。

### 2026-11-04 v1.5（改名 + 换图标 + 通知计数实时刷新）

- 应用显示名改为 **张跳跳**（桌面、应用信息、无障碍服务名、守护通知标题同步改）；
- 图标换成 `final.zip` 的方案 A：自适应图标（mipmap 各密度 + anydpi-v26）、
  通知栏小图标用 `ic_launcher_monochrome`（白色剪影）、通知大图标用彩色 `ic_launcher`；
- 通知栏“已跳过 N 次”改为**事件驱动刷新**：`recordSkip()` 之后调 `GuardService.refresh()` 
  只重贴一次通知，不轮询；连接/断开、暂停/恢复也会同步刷新。

验证：应用信息页显示“张跳跳 / 版本 1.5 / 新图标”；通知栏为“张跳跳 守护中 · 无障碍已连接 · 已跳过 69 次”；
跳过一条广告后不打开 App，通知数字即时变 70。

### 2026-11-05 v1.6（通知栏开关 + 误跳治理）

1. **通知栏行末开关**：通知改成自定义 RemoteViews（应用图标 + 标题/状态 + 开关），
   点开关直接切换“启用跳过”，与主界面开关、通知文案（已跳过/已暂停）实时同步；
2. **不跳过名单**：主界面新增两块——
   - 不跳过名单：按应用排除（弹窗列出所有可启动应用，Android 11+ 已在 manifest 声明 LAUNCHER 查询可见性）；
   - 最近跳过：最近 20 条（时间/应用/命中文案），每条可「不再跳过此场景」或「不再跳过此应用」；
3. **内置小程序场景规则**：Activity 类名含 `AppBrand / MiniProgram / Nebula` 等（微信小程序、支付宝小程序/H5 容器）
   一律不跳，直接解决小程序里误点“关闭/跳过”的问题；
4. **通知里的「误跳？屏蔽」**：跳过一次后 12 秒内通知行出现该 chip，点一下就把当前“包名 + Activity”加入场景黑名单
   （不申请悬浮窗权限，用常驻通知承担“气泡”的作用）；
5. 记录前台 Activity 时只认 Activity 类名（过滤 FrameLayout 等 View 类），避免场景键记错；
6. 版本 1.6（versionCode 7）。

验证：通知开关点击后状态变“已暂停跳过”再点恢复；`AppBrandFakeActivity`（类名含 AppBrand、带“跳过 5”）不跳；
正常测试广告照常跳；把测试 App 加入不跳过名单后不跳、移除后恢复；场景屏蔽后同一页面不跳、移除后恢复。

### 2026-11-06 v1.7（深色模式 + 底部皮肤切换）

- 底部新增切换栏：**浅色 / 深色 / 跟随系统**，选择存 `ui_theme_mode`，点按 `recreate()` 立即生效；
- 三套主题：`AppTheme`（跟随系统，values-night 自动深色）、`AppThemeLight`、`AppThemeDark`；
- 全部界面颜色改为自定义主题属性（`?attr/appBg / textSecondary / textHint / textBody / textFooter / bar*`），
  主界面、按钮、规则列表、诊断文字、弹窗都跟随皮肤；
- 通知栏自定义布局与 chip 颜色改为 `@color/notif_*`，`values-night` 下自动切浅色文字（跟随系统深浅）；
- 版本 1.7（versionCode 8）。

验证：浅色/深色截图对比正常；「跟随系统」+ 系统深色自动变深、系统浅色自动变浅；深色下通知文字为白色、
开关与 chip 可见；跳过功能不受影响。

### 2026-11-06 v1.8（皮肤按钮位置 + 应用选择弹窗样式）

- 皮肤切换（浅色/深色/跟随系统）从屏幕底部移到「去开启无障碍服务」按钮**上方**，跟内容一起滚动；
- “选择要排除的应用”弹窗重做：每个应用一行 **图标 + 应用名 + 包名**，行间有分割线，列表按名称排序，
  弹窗使用纯色圆角背景（浅色/深色跟随当前皮肤，不再是半透明毛玻璃）；
- 版本 1.8（versionCode 9）。

### 2026-11-09 v1.9（通知脱出「更多通知」+ 卡片精简免展开）

- **不再被收进「更多通知」**：HyperOS 会把带自定义布局（`setCustomContentView` / RemoteViews）的
  前台服务通知折叠起来；改为系统标准通知后回到通知栏顶层。通道换新 id（`auto_skip_guard_v3`，
  importance 提到 HIGH，静音、无振动、不显示角标），旧通道 `auto_skip_guard` / `_v2` 自动删除；
- **收起状态看全信息，没有「展开」箭头**：卡片只有一行标题 + 一行副标——
  正常 `已跳过 N 次` / `守护中`，暂停 `已暂停跳过` / `已跳过 N 次`，断连时副标为 `等待无障碍连接`；
  跳过后 12 秒内副标临时变成 `刚跳过 <应用名>`；
- 通知里的行末开关与「误跳」改为 Notification Action（`暂停跳过` / `恢复跳过` / `误跳`）：
  HyperOS 对前台服务通知用紧凑样式、不渲染 Action，界面上只剩可点开的卡片；其他 ROM 上可见；
- 「常驻通知守护」开关下方加一行 11sp 说明：通知被收进「更多通知」时去系统设置提升重要性；
- 版本 1.9（versionCode 10）。

验证：`dumpsys notification` 显示 `auto_skip_guard_v3` importance=4、旧通道 `mDeleted=true`；
通知栏顶层出现「张跳跳 · 已跳过 N 次 · 守护中」，无「更多通知」分组、无「展开」按钮；
暂停后显示「已暂停跳过 · 已跳过 N 次」，恢复后回到「守护中」；
冷启动 `com.autoskip.testad/.WarmAdActivity` 计数 +1（logcat `skipped com.autoskip.testad count=402`），
副标短暂显示「刚跳过 AutoSkip…」（深、浅色外观均已实拍验证）。

## 已知限制

- **无每 App 规则库**：李跳跳的规则库在加固后的 DEX 内（腾讯乐固加固），无法静态取得；
  本实现用通用文案 + 几何规则，覆盖率取决于广告是否把“跳过”暴露成可访问节点/文案。
- **Canvas/图片型广告**：若“跳过”完全没有节点或文案（纯绘制），无法定位，需人工点击（不做全屏乱点，避免误触）。
- **倒计时锁定的广告**：部分广告前 3–5 秒点击无效；本实现单次窗口期最多 3 次手势尝试后会放弃。
- **ROM 冻结/重连**：HyperOS 在覆盖安装 APK 后可能延迟重绑无障碍服务，并偶发 20–30 秒一次的静默重连；
  重连窗口内的事件会漏掉。主界面“诊断：事件 N 次”停止增长即说明处于该状态，
  打开一次 Auto Skip 可触发重新绑定。系统设置里把省电策略设为“无限制”可减少发生。
- **广告频次**：微博/知乎等开屏广告有频次控制，短时间内反复冷启动会看不到广告，无法据此复测。
- **未做**：弹窗广告、应用内广告、规则订阅、坐标规则、开机自启、前台服务——按需求只保留开屏跳过。

## 隐私

- 无 `INTERNET` 权限，从权限层面无法联网；
- 只读取当前窗口的控件文案用于匹配，不落盘、不上传；
- 本地仅保存“跳过次数 / 最近包名”两个计数，用于界面展示。
