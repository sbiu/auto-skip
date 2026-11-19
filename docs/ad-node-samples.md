# 开屏广告节点采样（真机）

- 设备：一台 Android 16 真机（SDK 36），HyperOS 3.0（V816），1440×3200 @ 560dpi
- 工具：无线 ADB（shell）+ `uiautomator dump` + `screencap` + Auto Skip 诊断快照
- 采集时间：2026-11-02 23:24–23:43

## 知乎（com.zhihu.android）

| 版本/时间 | “跳过”节点 | 处理 |
|---|---|---|
| 旧模板（v1.1 时期） | `text="跳过5"`，`id=com.zhihu.android:id/btn_skip`，clickable | v1 即可命中 |
| 2026-11-02 23:01 抓到的模板 | `Button text="4 关闭"`，右上角，旁边有 `广告` 文本和 `id_container` | **v1 完全未命中**（词表只有“跳过/关闭广告/关闭开屏/skip”，且要求前缀），这就是“装完能用、后来不跳”的直接原因 |
| 2026-11-02 23:37 复测 | `text="跳过5"` `id=btn_skip` bounds=[1196,144][1387,233] | v1.2 命中并 `skipped com.zhihu.android` |

结论：同一 App 的开屏素材由服务端下发，文案/控件会变；匹配必须按“标签形态 + 位置”判，不能写死一个词。

## 微博（com.sina.weibo）

- 广告画面：全屏海报，“跳过”在**右下角**（约 y=92% 屏高，x=88%），圆形/胶囊形。
- `dumpsys window` 里开屏期间只有 `com.sina.weibo/com.sina.weibo.MainTabActivity` 一个应用窗口；
  广告消失后 `uiautomator dump` 抓到的是 `flSplash`（`image_slogan`/`image_logo`）→ 正文页。
- v1 只读 `getRootInActiveWindow()`，在 ad 显示期间扫到的仍是主界面节点（Auto Skip 诊断快照里是
  `main_tab_view_pager`/`tabhost`），所以未命中。
- v1.2 改为 `getWindows()` 遍历所有可交互窗口 + 右下角规则 + 广告窗口兜底。
  2026-11-02 23:31 的 6 次冷启动里 5 次出现广告，跳过计数从 58 涨到 67；
  之后微博广告进入频次冷却（连续 10+ 次冷启动都不再展示），未能再抓到 HIT 日志逐条确认。

## 哔哩哔哩（tv.danmaku.bili）

- 冷启动入口 `tv.danmaku.bili/.MainActivityV2`。
- 2026-11-02 23:26–23:43 共 7 次冷启动，均未展示开屏广告（直接进首页），未取得新样本。
- 旧记录（README）：`text="跳过 3"`，`id=tv.danmaku.bili:id/count_down`，clickable；v1.2 的
  “跳过”标签 + `count_down` viewId 规则可覆盖。

## 脉脉（com.taou.maimai）

- 冷启动入口 `com.taou.maimai/.MainActivity`。
- 2026-11-02 23:29–23:43 共 6 次冷启动，均直接进入信息流，未展示开屏广告，未取得样本。
- 通用规则（跳过/关闭 + 右上/右下角）先兜底；下次抓到广告时看 Auto Skip 的“未命中快照”补规则。

## 调试时发现的其它事实

- Auto Skip 无障碍服务在系统里一直是 **enabled + bound** 状态，累计事件 13 万+，
  说明“后来不跳”不是服务被关掉，而是**匹配不到新模板**。
- HyperOS 在安装/替换 APK 后会延迟重新绑定无障碍服务，且偶发 20–30 秒一次的静默重连；
  重连窗口内的事件会漏掉。这属于系统行为，诊断界面能看到“事件数”停止增长。
- 桌面的 `miui.systemui.plugin`（灵动岛）会单独发窗口事件；v1.2 已把它和 `com.miui.home`、
  `com.miui.securitycenter` 一起加入忽略列表，避免在系统浮层上误开窗口。

## 哔哩哔哩（tv.danmaku.bili）—— 2026-11-03 另一台真机反馈

- 开屏广告：全屏海报，右上角“广告”角标，右下角胶囊按钮 **`跳过 4`**（截图 1178×2560）。
- 该文案会被 v1.2/v1.3 的匹配器命中（“跳过”标签 + 右下角），所以问题不在文案。
- 另一台机器上反馈的规律：
  - 杀进程再打开：**一次跳过一次不跳过**；
  - 不杀进程、B 站一直在后台：从最近任务回来**永远不跳过**。
- 对照代码可确定是 v1.2 窗口状态机的问题：
  1. `arm()` 的“刚跳过 8 秒内不再开窗口”把快速重开的下一次启动整段封锁 → 交替；
  2. 内容事件分支要求 `!pkg.equals(lastEventPackage)`，热启动/回前台没有新 Activity、只有内容事件时永远不开窗口 → 后台回来不跳。
- v1.3 改为前台会话模型后，这两条路径分别由“包名/事件间隔 >1.2 秒开新会话”和“删除 lastEventPackage 门槛”覆盖。
