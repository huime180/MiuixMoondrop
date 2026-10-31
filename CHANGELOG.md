## 当前状态（去模块化之后）

> 本节描述**当前代码**，用来和下面按时间倒序的历史条目对齐：
> 历史条目写于 LSPosed 模块时期，其中的 `hook/`、作用域、`module.prop`、跨进程广播、
> 融合设备中心接管、设置页伪装等内容**已全部删除**，只作为变更记录保留。

* **形态**：MiuixMoondrop 现在是**普通 Android 应用**——协议栈（GAIA over GATT / RFCOMM）
  跑在应用进程内，**不需要 root，也不需要 Xposed / LSPosed**。整个 `hook/` 目录、
  `app/src/main/resources/META-INF/xposed/`（`module.prop` / `scope.list` / `java_init.list`）、
  `res/values/arrays.xml`（`xposedscope`）以及 `XposedServiceState` / `BluetoothStatus` /
  `HomePage` / `RestartScope` 等模块专属页面都已删除。
* **身份**：应用名 `MiuixMoondrop`，`applicationId = moe.huime.miuixmoondrop`，
  Gradle `rootProject.name = "MiuixMoondrop"`，CI 产物名 `MiuixMoondrop-apk`；
  `applicationId`、namespace 与 Kotlin 包名现在统一为 `moe.huime.miuixmoondrop`
  （2026-09 起源码包名/路径也从 `moe.chenxy.hyperpods` 迁移过来，仓库页
  <https://github.com/huime180/MiuixMoondrop>（旧地址会 301 重定向）。
* **进程唤醒**：唯一的外部入口是 `pods/BluetoothConnectReceiver.kt` —— manifest 静态接收器，
  监听系统 A2DP `CONNECTION_STATE_CHANGED` 广播，耳机连上时唤醒应用进程。
  `pods/ControlBridge.kt` **不再做任何跨进程转发**，只把进程内状态分发给应用自己的通知与连接弹窗。
* **通知**：`pods/PodNotification.kt` 是应用进程自己发的唯一通知来源，通道
  `hyperpods_moondrop_app_status`（`IMPORTANCE_DEFAULT` + 关声音/震动，即不响铃不震动）、
  tag `HyperPodsAppState`、id `10004`；`setOngoing(true)` **常驻**（耳机连着时不可划掉，
  断开连接时程序化撤掉），由设置页「通知栏显示」开关控制。
  这条通知同时带上 **HyperOS 焦点通知**的 extra（`pods/PodFocusNotification.kt`，
  用 `com.xzakota.hyper.notification:focus-api` 模板库，同 dev 分支），AOD 行是 `L 59% | R 64%`；
  **超级岛挂在它上面**（2026-09 起）：岛跟着这条常驻通知一起出现（连接、以及电量每 30s 变化的
  那一刻），几秒内自己收掉。岛模板只写 `bigIslandArea`、不写 `smallIslandArea`，再显式
  `dismissIsland = true` ＋ `islandTimeout = 5`（**秒**）—— 四条结论见 `PROTOCOL.md` §10.5.3
  （含「把 `island` 置空没用」「别写 `islandFirstFloat = false`」这两条负面结论）。
  同一版删掉了原来那条「连接 / 断开瞬时岛」通知（`pods/PodIslandNotification.kt`）与设置页的
  「超级岛」开关：岛既已由常驻通知自己带，就不需要第二条通知与第二个开关了；旧版遗留的通道
  `hyperpods_moondrop_island` 会在下次建通道时被程序化删除。其它 ROM 忽略这些 extra，通知照常显示。
  通知上还挂着一个动作按钮 **「断开连接」**（模板动作栏 `param_v2.actions`，`type = 2` 文字按钮；
  普通通知回落 `Notification.Builder.addAction`），落点是 `pods/PodDisconnectReceiver.kt` →
  `MoondropLink.disconnect()`：断掉的是**本应用**与耳机的那条链路（A2DP / HFP 音频链路归系统
  蓝牙管，普通应用没有 BLUETOOTH_PRIVILEGED，代不了用户断开它）。
* **弹窗只有一个**（2026-09 起）：耳机连上后**先刷新状态栏通知，再延后 600 ms 自动弹窗**
  （`ui/PopupActivity.kt` —— 与点通知唤出的是**同一个**界面：电量 / 降噪 / 增益 / 快捷控制）。
  原来是两条界面（连接时弹一个简单的三路电量小窗 `ConnectionPopupActivity` + 点通知弹这个），
  现已统一成一条，`ConnectionPopupActivity` 整个删除，连同它读的那组连接状态 / 电量 Bundle
  常量（`PODS_CONNECTED` / `PODS_DISCONNECTED` / `BATTERY_CHANGED` / `EXTRA_BATTERY` /
  `EXTRA_DEVICE_NAME`）与 `BatteryCodecWire` 编码器：弹窗直接从进程内快照取数。
  后台启动 Activity 可能被系统 BAL **静默**拦掉，因此最多**重试 3 次**（间隔 800 ms，用
  `PopupActivity.lastShownAt` / `visible` 确认是否真的显示）。自动弹出的那个不再自己关闭
  （旧小窗是 8s 自动关）—— 与点通知那个一致：点卡片外或「关闭」退出。
* **后台弹出权限**：为让应用在后台也能弹连接弹窗，声明了 `SYSTEM_ALERT_WINDOW`
  （「显示在其他应用上层」），并在设置页提供跳转入口；**HyperOS 上还需手动开启「后台弹出界面」**。
* **设置页收窄**：只剩「主题」+ 通知卡三项（通知栏显示 / 连接时自动唤出弹窗 /
  后台弹出弹窗权限入口）+ 关于；手势入口只留在详情页（耳机相关功能都在耳机页），
  原来的「模块」页签（LSPosed 状态卡、模块开关、重启作用域）整组删除。
* **降噪子排图标**：主排「通透 / 降噪 / 关闭」图标在上、文案在下；降噪生效时展开
  「自定义 / 抗风噪 / 基本」，每档 on/off 两态（自定义 `ic_adaptive_*`、抗风噪 `ic_anti_wind_*`
  为本项目自绘、基本复用 `ic_openanc_*`），idle 图标另有 `drawable-night` 变体。
* **手势**（`ui/GesturePage.kt`，TOUCHV2 / feature 22）：5 个字节每字节双耳（高 4 位左、低 4 位右），
  页面为 5 × 2 = **10 行**；**同侧**长按 1 秒与长按 3 秒互斥（只清同一只耳的另一档），
  单击/双击/三击之间没有互斥。
* **机型图自动导入**：连上后按设备名在 MOONDROP 官方产品目录里找对应机型，命中就下载那张
  **85% 透明的官方产品渲染**落盘（`pods/MoondropOfficialImages.kt` + `pods/PodImageStore.kt`；
  目录原始一百余条，按 `type = "BT"` 且拿得到机型图过滤后是 **49 款**）。认不出来就**不猜**，
  由用户在详情页「机型图片」选择框（`ui/components/OfficialImagePickerDialog.kt`，缩略图懒加载 + 采样解码）
  里手动选或恢复默认。
* **空间音频 / 头部追踪**（feature 18）：详情页两个开关，读（`refreshSpatial`，随 `refreshAll` 轮询）、
  写（`setSpatial` / `setHeadTracking`）与写完回读都已接线，状态落在
  `PodSnapshot.spatialEnabled` / `headTrackingOn`；同一 feature 上 cmd 1 与 cmd 3 两条读共用一把
  串行锁（`spatialReadLock`，因为 `responses` 只按 feature 建键，并发会让回包串台）。
  **命令号与 payload 未真机验证**，读不到就按「关」展示。
* **已移除**：低延迟开关（不是 GAIA 命令，交由系统蓝牙设备详情页）；详情页的「当前编码」行
  （数据源是蓝牙进程的 hook 广播，随去模块化消失、恒显示「未知」，已整行删除），
  连同它**整条死数据链**：`PodSnapshot.activeCodec`、`MoondropLink` 的 `activeCodec` 字段 /
  断开清空 / `onSystemCodecChanged()` 回调，以及无人注册的系统编码重放钩子
  （`systemCodecReprobe` / `setSystemCodecReprobe()` / `reprobeSystemCodec()` 与两个
  `CODEC_REPROBE_*` 常量）。
* **测试**：CI 跑 `:app:testDebugUnitTest`，共 **47** 例（`GaiaProtocolTest` 14 +
  `BatteryCodecTest` 15 + `TouchV2Test` 18）。

---

以下是**历史条目**（按时间倒序；「Unreleased」写于 LSPosed 模块时期）：

---

## Unreleased

### 修正：布丁 PUDDING 的降噪映射 `[0,4,2,3,1]` 是错的

`ancPudding()` 原来用 `intArrayOf(0,4,2,3,1)`，导致 UI 上选「降噪（基础）」实际下发 `4`（自适应）、选「自适应」实际下发 `1`（基础降噪）——**这两档在收发两个方向上都颠倒了**；关 / 通透 / 抗风噪碰巧是对的，所以不容易被发现。

根因是把 PuddingPods 早期文档里的错误枚举（`0/1/2/3/4 = 关/自适应/通透/抗风/基础降噪`）当成了 PUDDING 的专属偏移。实际上 PUDDING 用的就是标准 Qualcomm ANC V2 枚举：

| 值 | 含义（官方 `AncV2Handler` 常量） |
|---:|---|
| 0 | `MODE_ANC_OFF` |
| 1 | `MODE_ANC_ON`（基础降噪） |
| 2 | `MODE_TRANSPARENT` |
| 3 | `MODE_ANTI_WIND` |
| 4 | `MODE_ADAPTIVE` |
| 5 | `MODE_LIVE` |

依据：MOONDROP Link 的 `AncV2Handler` 把 `MODE_ANC_OFF/ON/TRANSPARENT/ANTI_WIND/ADAPTIVE/LIVE` 硬编码为 `0..5`，`handleSetAncV2Mode` 只校验 `0 <= mode < 6` 后原样透传给 `AncV2Plugin.setCurrentMode`；HyperEars 独立逆向得到的 `NoiseMode` 也是 `OFF(0x00)/ANC(0x01)/TRANSPARENCY(0x02)`。

* `MoondropModels.ancPudding()` 映射改为 `[0,1,2,3,4]`；
* 删除 `Gaia.kt` 里那套与标准枚举矛盾、且全仓库无调用方的 `PUDDING_ANC_*` 常量；
* `PROTOCOL.md` 删掉「PUDDING 使用不同枚举」的例外说明；
* `ADAPTATION.md` 三处表格同步，并把 PUDDING 的证据来源从「转抄上游文档」改为「官方 App 逆向 + 真机五档双向实测」。

### 通知三档：原生通知栏 / 焦点显示 / 超级岛提示（`hook/MiBluetoothToastHook.kt`）

用户反馈「设置-通知 始终是焦点显示通知」。根因是设置页那三个开关里，
`SHOW_STRONG_TOAST` / `SHOW_FOCUS_ISLAND` **在 hook 侧从来没被读过**（只有
`ui/ModuleSettings.kt` 在写），等于两个死开关；通知本身又只有一条固定形态。现在：

* **通知栏显示** —— 任何 ROM 都生效的原生状态栏通知。通道由 `IMPORTANCE_MIN` 改为
  `IMPORTANCE_LOW`；通道重要性创建后不可修改，所以发现重要性不对时先 `deleteNotificationChannel`
  再按 LOW 重建。这是「通知栏里看得到」的基础形态。
* **焦点显示**（仅 HyperOS）—— 追加 `miui.focus.param`（内含 `param_v2` JSON）与
  `miui.focus.pics`。
* **超级岛提示**（仅 HyperOS）—— 焦点 JSON 里再加 island 那一半字段
  （`islandProperty` / `islandTimeout` / `param_island` / `bigIslandArea` / `smallIslandArea` / `textInfo`）。
* 后两档由新增的 `hook/RomProfile.kt#isXiaomiRom` 门控：**严格**判定，只看 HyperOS 自报的
  `ro.mi.os.version.*`（SDK 启发式不算），所以 AOSP / 其它厂商 ROM 上不会写任何 `miui.*` extra。
  设置页那侧同时把两个开关置灰（`enabled = settings.enabled && isHyperOS`）。
* `SEND_STRONG_TOAST` 广播按「超级岛提示」开关门控，关掉时直接不落地。
* 焦点 JSON 的键名与常量**不是推测**：用新增的 `tools/dex_find_method.py` 反汇编本机
  HyperOS 4 的 `com.xiaomi.bluetooth.apk`（`MiuiBluetoothNotification` 里构造水月雨通知的
  那两个方法）逐条读出，键表与证据记在 [PROTOCOL.md](PROTOCOL.md) 第 10.5 节。
  少数由寄存器传入、反汇编看不到的值取 ROM 调用点那一档，并在 KDoc 里写明。
* 顺带把 `invokeStatusBar` 的诊断从「只打键名」加强为「键 + 值」，下一轮可按 ROM 自己
  构造的焦点 Bundle 校正 JSON。

### UI 收口（用户四项反馈）

1. 设置页「重启作用域」移除，只保留模块页（首页）顶栏那个动作。
2. 文案瘦身：`restart_scope_summary`、`gesture_note_body` 由整段说明改为一行。
3. 「关于」只在设置页：设备页（`ui/PodDetailPage.kt`）那张「关于」卡删除。
4. 设置页把「焦点显示 / 超级岛提示」两个 HyperOS 专属开关按 ROM 置灰，摘要里统一带「仅 HyperOS」。
   英文侧 `show_strong_toast_title` 由 `Strong toast` 改为 `Super Island`，与中文侧的「超级岛提示」对齐。

### 新增工具

* `tools/dex_find_method.py` —— 仅用标准库的 DEX 检索 / 反汇编小工具：按字符串定位引用它的方法、
  按代码顺序列出该方法用到的全部 `const-string`、可反汇编单个方法（含 `const` 数值、字段、方法引用）。
  本轮的 HyperOS 通知结论全部出自它。

## 1.0.0

首个版本。本版本包含**协议核心层**（纯 Kotlin，可单测）、**协议客户端**、
**libxposed API 102 系统集成层**（4 个作用域进程）与 **Compose / Miuix UI**。

> ⚠ **诚实声明**：CI 已通过单测与编译并产出 APK，且 `app-debug.apk` 已装机到目标平板
> （构建/装机状态见 [README.md](README.md) 的「构建状态（1.0.0）」小节）；但模块需在 LSPosed 中启用并重启后才生效，
> **本模块目前没有任何真机功能验证结论**。下文所有「已由上游真机验证」均指上游项目
> （FxxkMoondrop / moondrop-link-desktop / PuddingPods）的结论，**不是本模块的测试结论**；
> 凡是未验证的条目都在最后单独列出。

### 多机型适配（17 个型号档案 + 1 个兜底档案）

* 新增 `core/MoondropModels.kt`：以「能力优先于型号」为原则的档案库。
  * **有上游实测依据（`verified = true`，4 款）**：
    * 羽翼 EDGE —— GAIA v3 over BLE；电量只回 `type 0`（单设备）；ANC 走 AudioCuration 三态位掩码
      `1/2/4`；`ONEBRINGTWO(0x14)` 可用（moondrop-link-desktop 真机验证）；
    * 布丁 PUDDING（MD-TWS-056）—— GAIA v4 over RFCOMM/SPP；ANC V2 五档 `[0,1,2,3,4]`；
      三路电量（含充电盒）；增益恒等、指示灯（PuddingPods 协议文档 + FxxkMoondrop 实测）；
    * 梦回2 / Golden Ages 2 —— AudioCuration，SET `[1,2,4,3]` / GET `[0,1,2,3]`，增益 `[2,1,0]`，
      空间音频 + 头动追踪（FxxkMoondrop 2026-08-24/25 双向实测）；
    * 太空漫游2 / Space Travel 2 —— 中科蓝讯 BT8932F + 9ECA；ANC 同 GA2；增益反向 `[2,1,0]`
      （FxxkMoondrop 2026-09-01 真机实测）。
  * **芯片级推断（`verified = false`，13 款）**：梦回1979/梦回、猫咖、猫饼、音乐胶囊、超声波、
    知更鸟、爱丽丝、火花、旅行者、方糖、太空漫游（一代）、羽翼 EDGE2、太空漫游2 ULTRA。
  * 兜底档案 `FALLBACK`：设备名含 `MOONDROP` / `水月雨` 但未命中档案时使用，不假设任何能力，
    全部交给连接后的能力探测；不是水月雨设备则完全不接管。
  * 别名按长度降序 `contains` 匹配，避免 `EDGE` 抢在 `EDGE2` 前、`SPACE TRAVEL` 抢在 `SPACE TRAVEL 2` 前。

### 右耳电量不显示：根因与四段式修复（`core/BatteryCodec.kt`）

现象：左耳电量正常、**右耳空白**。根因是固件上报形态与解析器假设不一致
（实测水月雨 EDGE 只回 `type 0`「单设备」电量，而不是 `type 1/2` 分体值）。修复分四部分：

1. **先问设备支持哪些电池类型**（`BATTERY(0x0D) cmd 0`，`00 1D 1A 00`），再按该集合查询；
   设备不回 cmd 0 时退回 `[0,1,2,3]`，或老固件的 cmd 1 无 payload 查询 —— 不再写死「只查左耳」。
2. **严格按 `type` 归位、绝不按位置解析**：未知 type 直接忽略，不让后续字节错位
   （单测 `未知类型只忽略不位移`）。
3. **把 `type 0` 当作整机值同时充填左右耳**，显式分体值（type 1/2）优先于整机值
   —— 只上报 type 0 的机型左右耳都会显示（单测 `单设备电量型机型左右耳都要显示`）。
4. **每个组件保留最近一次有效值**，单包缺项不清零（单测 `只收到左耳时右耳不会被清空`、
   `缺少右耳的单包不会让右耳变成未知`）。

附带修复：系统广播的单值兜底 `fallbackFromSystem()` **同时写左右耳与整机**
（「只兜左耳」正是原 bug 的成因之一，单测 `系统广播兜底必须左右都给`）；
`systemLevel()` 取左右耳较小者，与 AOSP `AdapterService.setBatteryLevel(device, level, false)` 语义一致。

### 四个新功能

| 功能 | 实现 | 验证状态 |
|---|---|---|
| **提示音开关 + 音量滑条**（`VOICE 0x0E`） | ✅ **命令与 payload 已实机确认**（官方 App logcat）：GET=cmd 1 / SET=cmd 2，payload(V2)=`[enabled, volume, index]`，**音量是 0..100 百分比**、**没有独立音量命令**；`Gaia.voiceGetConf/voiceSetConf/parseVoiceConf/VoiceConf`、`MoondropLink.refreshPromptVoice/setPromptVoice`（写必给全三字节，改开关保音量、改音量保开关）；UI 行由能力位图（feature 14）或档案开关**硬门控**；滑条与设备同单位（恒等映射） | 命令与 payload ✅ 已确认（非本模块实机）；`index` 语义未知，本模块自身仍**未真机跑通** |
| **LHDC 开关**（`CODEC_TYPE 0x10`） | cmd 5 读 `00 1D 20 05` / cmd 6 写 `00 1D 20 06 01\|00`；单测逐字节锁定；同时保留 LC3（1/3）与 LDAC（2/4）构造器 | 帧格式已锁定；**开关实际效果未真机验证**。上游实测耳机出厂默认 LHDC 关（当前活动编码 AAC，主机侧广告 LHDCv5/LHDC_V3/LHDC_V2/LDAC/aptX-adaptive） |
| **双设备连接**（`ONEBRINGTWO 0x14`） | cmd 1/2 状态（`00 1D 28 01` / `00 1D 28 02 01`）、3/4 超时、5/6 设备列表、7 断开单台（payload `[num:1][addr:6][name utf8]`）；`parseLinkedDevice()` 解析条目 | 命令号由 moondrop-link 在 EDGE **真机确认**；上游原文注明「读取与开关已验证，**写入/断开单台需双机场景实测**」 |
| **低延迟模式** | **明确：这是 HyperOS 系统侧功能，不是 GAIA 命令**，无对应 feature；链路已接通：详情页 → `LOW_LATENCY_SELECT` → `ControlBridge`（乐观状态）→ `com.android.bluetooth`，由 `HeadsetStateDispatcher` 先反射厂商直通方法（`setLowLatencyMode` 等），否则走 A2DP codec 路径（`getCodecStatus` / `setCodecConfigPreference`，按 LHDC/LDAC/aptX-adaptive/LC3/AAC 挑候选，关闭时恢复原配置），最后回 `LOW_LATENCY_CHANGED` | 已实现，❌ **未在真机验证**（隐藏 API 可能不可用，不可用时回「保持原状态」并记日志） |

### HyperOS 系统集成层（libxposed API 102）

* `hook/XposedEntry.kt`：模块入口 `XposedModule`，`onPackageReady` 按包名分发 hook；
  实现 API 102 热重载（`onHotReloading` / `onHotReloaded`，稳定 hook id + 摘除失效 handle）；
  远程首选项组 `hyperpods_moondrop_settings`（取不到时用内存兜底）；**装载失败只记日志，不影响被注入进程**。
* `hook/HookContext.kt`：hook 基类（`hookBefore/After/ConstructorAfter`、`find*OrNull`、
  沿继承链的 `findAnyMethod`、反射助手），移植自 OppoPods 的通用封装风格。
* `hook/HeadsetStateDispatcher.kt`（`com.android.bluetooth`）：hook
  `A2dpService#handleConnectionStateChanged(int,int,int)` 感知 A2DP/HFP 连接；只处理
  `MoondropModels.match()` 命中的设备；维护状态栏 `wireless_headset` 图标；向应用进程广播
  `PODS_CONNECTED / PODS_DISCONNECTED`；应答 `GET_PODS_MAC`；接收 `UPDATE_SYSTEM_BATTERY` 并反射
  `mAdapterService.setBatteryLevel(device, level, false)` 写回系统蓝牙栈；
  **迟装兜底**：`A2dpService/ProfileService.onCreate` 后延迟 1.5 s 扫描已连接的 A2DP/HFP 设备补发一次连接事件。
* `hook/SystemUIPluginHook.kt` + `hook/DeviceCardHook.kt`（`com.android.systemui`）：
  hook `PluginInstance#loadPlugin`，在 `miui.systemui.plugin` 插件 ClassLoader 里挂
  `DeviceInfoWrapper#performClicked`，只接管 `deviceType == "third_headset"` 的卡片；
  MAC 握手在独立 `HandlerThread` 上完成，**点击路径不 sleep、不阻塞 SystemUI 主线程**
  （相比原 HyperPods 的 `Thread.sleep(50)` 忙等是明确的改进）；打开 UI 前用
  `MainPanelController#exitOrHide` 收起控制中心面板。
* `hook/MiBluetoothToastHook.kt`（`com.xiaomi.bluetooth`）：hook `MiuiBluetoothNotification`
  构造函数（两种签名 + 参数个数兜底）取进程 Context，展示耳机电量通知
  （id 10003 / tag `BTHeadset<addr>` / 通道 `hyperpods_moondrop_bt_headset`，`IMPORTANCE_MIN`），
  文案优先复用小米蓝牙本地化字符串；**不含任何 mp4 / base64 强提示视频资产**，
  `SEND_STRONG_TOAST` 降级为同一套普通通知。
* `hook/SettingsHeadsetHook.kt`（`com.android.settings`）：用 `FAKE_DEVICE_ID = "01010607"` /
  `FAKE_SUPPORT = "01010607,000000000000000010000000"` 伪装成小米原生四档 ANC 耳机；
  改写 `MiuiHeadsetActivity(Plugin)#onCreate` 的 intent extra；强制
  `HeadsetIDConstants` 的 `checkSupport` / `isTWS01Headset` / `isK77sHeadset` / `isBleMmaConnect`
  与 `getDeviceID/getSupport`；拦截 `IMiuiHeadsetService$Stub$Proxy`
  （`checkSupport / isMiTWS / checkIsMiTWS / getRingFindState / changeAncMode / connect /
  getDeviceConfig / getCommonConfig`）；用 MIUI payload 注入
  `MiuiHeadsetFragment#updateAtUiInfo / updateAncUi / refreshStatus`，页面存活时每 3 s 重新注入。
  所有分支都先确认是水月雨设备。
* `hook/SystemApisUtils.kt`：反射小工具（`isHyperOS`、`SystemProperties`、`setIconVisibility`、
  `notifyAsUser/cancelAsUser`、`currentApplication`）与**三路电量 extra 约定**
  （`EXTRA_BATTERY` 放 Bundle：`left/right/case` + 可选 `*_charging`；或 Parcelable；
  或 `EXTRA_LEVEL` 单路兜底；255 = 未知，`value or 128` = 充电中）。
* `MainActivity` + `ui/*`（Compose + Miuix + haze）：两页界面（设备详情 / 关于）；
  详情页含机型卡（型号 / 实测徽标 / 传输 / 当前编码）、三路电量（单设备机型显示「整机」行）、
  降噪档位、增益、能力硬门控的开关区（指示灯 / 提示音 / LHDC / 双设备连接 / 低延迟）、
  提示音音量滑条、手动刷新；应用进程注册 `PODS_*` / `UI_INIT` / `REQUEST_*` 广播触发器，
  从已配对设备里按型号档案挑出水月雨耳机并建立 GAIA 通道。默认英文字符串 + `values-zh-rCN` 中文。
* `pods/ControlBridge.kt` + manifest 声明的 `pods.ControlReceiver`：**应用侧跨进程控制桥**。
  因为是 manifest 声明的接收器 + 显式广播（`setPackage`），**App 未运行时也能被系统广播拉起**处理控制命令。
  它接收 `PODS_CONNECTED` / `PODS_DISCONNECTED`（用 `getRemoteDevice(mac)` 解析设备并连接）、
  全部 `*_SELECT` 控制命令（设置页 → `MoondropLink.setXxx()`）、`UI_INIT` / `REQUEST_CAPABILITIES` / `REQUEST_BATTERY`；
  并把状态转发出去：`UPDATE_SYSTEM_BATTERY` → `com.android.bluetooth`（电量写进系统蓝牙栈）、
  `ANC_CHANGED` / `BATTERY_CHANGED` → `com.android.settings`（被伪装耳机页显示）、
  `UPDATE_PODS_NOTIFICATION` / `SEND_STRONG_TOAST` / `CANCEL_PODS_NOTIFICATION` → `com.xiaomi.bluetooth`（通知）、
  `LOW_LATENCY_SELECT` → `com.android.bluetooth`（低延迟）。电量以 `Bundle` 传递
  （`left` / `right` / `case` + `*_charging`；`255 = 未知`、`value or 128 = 充电中`）。
  配套改动：`MoondropLink` 改为**监听者列表**（`addListener` / `removeListener` / `isInitialized` /
  `requestBatteryRefresh`），桥的转发器与 UI 监听者并存互不覆盖；`MainUI` 不再重复处理
  `*_SELECT` / `UI_INIT` / `REQUEST_*`，也不再自己连接/断开。

### 协议层与测试

* `core/Gaia.kt`：GAIA V3/V4 线格式、feature/命令常量、帧构造与解析、能力解析
  （`parseFeatureEntries` 字节对 + `parseSupportedFeaturesSmart` 两者都试）、
  ANC 路径推导（AudioCuration > ANC V2 > ANC V1）、AC 位掩码 `1/2/4`、`OnBringTwo` 设备条目解析、
  版本探测帧 `00 0A 03 00`（已接入连接流程，见上「协议修正」）。
* `core/GaiaFramer.kt`：RFCOMM/SPP 流式切帧状态机，支持官方传输帧（SOF `0xFF` + 可选校验和 /
  长度扩展）与裸 `00 1D` PDU 两种封装，半截帧保留不丢。
* `core/SrcProtocol.kt`：中科蓝讯 9ECA 私有协议帧构造与解析（音源 / EQ / MIC 增益 / 固件信息）。
* `pods/MoondropLink.kt`：BLE GATT 与 SPP 两条链路；按 feature 的请求/响应关联（GAIA 无序列号，
  `CompletableFuture` + 超时）；写操作单写者 `Mutex`；能力探测 → 全量刷新 → 30 s 电量轮询；
  BLE 上找不到 GAIA 服务时自动回退 SPP。
* 单元测试 **29 例**：`GaiaProtocolTest`（14）逐字节锁定所有帧与位图解析（含官方 App logcat 的提示音原样字节）；
  `BatteryCodecTest`（15）覆盖右耳电量修复的全部回归点。

### 协议修正（文档初稿之后）

* **`GET_SUPPORTED_FEATURES` 两种编码都接受**：新增 `Gaia.parseFeatureEntries()` 与
  `parseSupportedFeaturesSmart()` —— 先按上游 moondrop-link 的 `[more][featureId][version]...` 字节对解析，
  条目合法（feature id 落在 0..63）就采用；否则回退 FxxkMoondrop 的 32-bit word 位图解析。
  能力探测与通知型位图都改用该函数。
* **EDGE / EDGE2 的 ANC 读回修正**：`anc3Ac()` 现在带 `getMap = intArrayOf(0, 1, 2)`
  （SET 仍是 AudioCuration 位掩码 `1/2/4`，GET 是 0-based 索引 `0..2`），
  修掉了「设备回 0 时反查得到 `-1` / 状态未知」的问题。
* **GAIA 版本探测接线**：`afterConnected()` 现在先发 `00 0A 03 00`（`Gaia.getApiVersion()`）再探测能力。
* **提示音协议实机修正（2026-09-14，Xiaomi Pad 8 Pro 抓官方 App 自身 gaiaclient logcat）**：
  原先按惯例猜的「开关 GET=1/SET=2 + 音量 GET=3/SET=4」是**错的** —— 实际**只有 cmd 1（读）/ cmd 2（写）**，
  payload(V2, size>=3) = `[enabled(0/1)][volume(0..100)][index]`，音量是**百分比**而非 0..255 原始字节，
  且**写入必须一次给全三字节**（固件把三者当一份配置，只发开关位会写坏音量与索引）。
  对应改动：`Gaia` 新增 `C_VOICE_GET_CONF/C_VOICE_SET_CONF`、`voiceGetConf/voiceSetConf`、
  `VoiceConf`、`parseVoiceConf`（兼容 3/2/1 字节）、`VOICE_VOLUME_MAX = 100`（旧命令名保留为别名）；
  `MoondropLink` 改为一次读全部三字段、写入保留未改动的字段；`PodSnapshot.promptVolumeRaw` 改注为 0..100 百分比、
  新增 `promptIndex`、`promptVolumeMax` 默认 100；`FeatureProfile.promptVolumeMax` 默认改为 `Gaia.VOICE_VOLUME_MAX`；
  UI 滑条改为恒等映射（旧的 `ui * 255 / max` 基于错误单位）；单测断言官方 App logcat 的原样字节。
* **旁证**：同一次 logcat 还打印出「关闭 LHDC」`00 1D 20 06 00`（feature `0x10`/cmd 6），
  以及电量以**通知帧**到达（`type=NOTIFICATION, feature=000D, command=0001`），与本项目 LHDC 构造器、
  电量通知分发路径一致。
* **CI 反馈的编译修正**：`LazyColumn` / `HorizontalPager` 改用 `androidx.compose.foundation.lazy` /
  `androidx.compose.foundation.pager`（本构建解析到的 Miuix 制品不含 `basic.LazyColumn` / `basic.HorizontalPager`），
  底部导航改用原生 Compose（MiuixIcons 同样不存在）。

### 未验证 / 遗留问题（1.0.0）

1. **真机功能验证尚未开始**：CI 已通过 29 个单测与 `assembleDebug`/`assembleRelease`，APK 已产出并装机，
   但模块在 LSPosed 中启用+重启前不会生效 —— **本文档不声称任何功能已在真机上跑通**。
2. 提示音：命令与 payload 已由官方 App logcat 实机确认，但 **`index`（语言/主题）字段的取值语义未确认**，
   且**本模块自身**尚未在真机上跑通提示音读写。
3. `GET_SUPPORTED_FEATURES` 响应体究竟走哪种编码（代码两种都试，但**具体固件是否会被误判**未真机抓包确认）。
4. EDGE / EDGE2 的 `getMap = [0,1,2]` 是按上游实测补的，**参数本身仍未真机复核**。
5. GAIA 版本探测已接线，但**探测结果的解析与用途未真机确认**；EDGE 增益映射无实测依据
   （提示音音量量程已由官方 App logcat 确认为 0..100，见上「协议修正」）。
6. **低延迟链路已实现但未在真机验证**：`ControlBridge` → `com.android.bluetooth` 的反射桥
   （厂商方法优先，A2DP codec `getCodecStatus` / `setCodecConfigPreference` 兜底）→ `LOW_LATENCY_CHANGED`；
   隐藏 API 不可用时只回「保持原状态」并记日志。
7. 空间音频 / 头动追踪（`Gaia.spatialGet/Set`、`headTracking*`）仍未接线到客户端。**（后续已接线：见文首「当前状态」——读/写/回读都有实现，但同样没有真机验证）**
8. 9ECA 全部功能、LHDC 打开后的稳定性、双设备连接写入/断开、充电位解析均未验证。
9. `SettingsHeadsetHook` 的注入签名（`updateAtUiInfo / updateAncUi / refreshStatus`）需实机核对；
   `MiuiHeadsetBattery` 电量控件注入未实现。
10. 13 款「推断」机型未逐型验证；系统集成层（通知 / 设备卡 / 设置页伪装 / 跨进程控制桥）从未在真机上运行过。
11. **本文档修订过程未在本机构建**（环境无 JDK / Android SDK / 网络），构建与装机状态以
    [README.md](README.md) 的「构建状态（1.0.0）」小节为准；**本模块仍没有任何真机功能验证结论**。
