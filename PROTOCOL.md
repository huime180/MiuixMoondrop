# 协议参考（PROTOCOL）

> 本文描述水月雨（MOONDROP）耳机使用的 **Qualcomm GAIA（QTIL）业务协议**，以及本应用额外用到的
> **中科蓝讯 9ECA 私有协议** 与 **Classic Bluetooth RFCOMM/SPP 传输切帧**。
>
> 文中每个十六进制字节都可以在源码里找到出处：
> `core/Gaia.kt`（帧与命令）、`core/BatteryCodec.kt`（电量）、`core/GaiaFramer.kt`（RFCOMM 切帧）、
> `core/SrcProtocol.kt`（9ECA）、`pods/MoondropLink.kt`（读写接线）、
> 以及单测 `core/GaiaProtocolTest.kt` / `core/BatteryCodecTest.kt` 逐字节锁定的期望值。
> 上游来源见文末「来源与推导」。**未真机验证的条目已单独标注 ⚠。**

---

## 1. 传输层

| 传输 | 适用 | 服务 / UUID | payload 封装 |
|---|---|---|---|
| **GAIA V3 over BLE GATT** | 大多数 QCC / 蓝讯系水月雨耳机（EDGE、梦回2、太空漫游2 …） | Service `00001100-d102-11e1-9b23-00025b00a5a5`；Command `...1101`（写）；Response `...1102`（通知）；Data `...1103`（通知） | **裸帧**：无 SOF、无长度、无校验、无序列号（identity formatter） |
| **GAIA V4 over Classic BT RFCOMM/SPP** | 布丁 PUDDING（MD-TWS-056）等 | SPP `00001101-0000-1000-8000-00805f9b34fb` | 两种：官方 `TransportProtocol.Rfcomm` 传输帧（SOF `0xFF`）或裸帧（`00 1D` 开头），见第 7 节 |
| **中科蓝讯 9ECA 私有协议** | 蓝讯主控机型（猫饼、太空漫游2、音乐胶囊 …） | Service `9eca0000-7f3a-4f32-9a38-a91b2c6e0100`（`...0001` 写 / `...0002` 回 / `...0003` 通知 / `...0004` 能力 / `...0005` 固件） | 固定在同一个 GATT 连接内，与 GAIA 并存，见第 8 节 |

* BLE 的 CCCD 是标准 `00002902-0000-1000-8000-00805f9b34fb`，对 `1102` 与 `1103` 写 `0x0001` 打开通知。
* 上游 moondrop-link 记录 EDGE 的 BLE 命令特征**必须 write-with-response**；本应用发的是
  `WRITE_TYPE_NO_RESPONSE`（GAIA 帧本身无序列号，靠 feature 关联响应）。
* 9ECA 服务的 UUID 基址是 `7f3a-4f32-9a38-a91b2c6e0100`（与 `1100-d102-11e1-9b23-00025b00a5a5` 不同）。

---

## 2. GAIA 线格式

```
[vendor u16 BE][commandValue u16 BE][payload ...]
commandValue = (feature << 9) | (type << 7) | (command & 0x7F)
```

| 字段 | 位宽 | 说明 |
|---|---|---|
| `vendor` | 16 bit，**大端** | `0x001D` = Qualcomm QTIL V3（现代功能命令）；`0x000A` = QTIL V1/V2（仅版本探测包） |
| `commandValue` | 16 bit，**大端** | `bits 15..9` = feature(7b)；`bits 8..7` = type(2b)；`bits 6..0` = command(7b) |
| `type` | 2 bit | `0`=COMMAND、`1`=NOTIFICATION、`2`=RESPONSE、`3`=ERROR |
| `payload` | 余下 | 可以为空 |

多字节字段大端这一点有硬证据：官方 App 的 native `BytesUtils`（`libutils-lib.so`）按
`byte[0] << 8 | byte[1]` 组装 16 位字段（moondrop-link `constants.py` / `packet.py` 记录）。

**版本探测**用 V1/V2 包，vendor `0x000A`，cmd `0x0300`：

```
版本探测   TX 00 0A 03 00     （RX cmd 0x8300 = 0x0300 | ACK，payload [status][protocol][gaiaMajor][minor]）
```

探测确认设备 GAIA 版本为 3 之后，功能命令才走 vendor `0x001D`。
本应用 `MoondropLink.afterConnected()` **现在会先发这帧**（用 `runCatching` 包住，失败不阻塞后续），再探测能力。

---

## 3. 逐字节示例（全部已由源码/单测锁定）

约定：下表 `TX` 均为 **COMMAND**（type=0）帧。type 编码变化见每行说明。

| 操作 | Hex | 出处 / 校验 |
|---|---|---|
| 版本探测 | `00 0A 03 00` | `Gaia.getApiVersion()`；单测 `版本探测帧与注册通知帧` |
| BASIC 读能力位图 | `00 1D 00 01` | feature 0 / cmd 1 |
| BASIC 读能力位图（翻页） | `00 1D 00 02` | cmd 2 = GET_SUPPORTED_FEATURES_NEXT |
| BASIC 读应用版本 | `00 1D 00 05` | cmd 5 |
| BASIC 注册通知（ANC_V2） | `00 1D 00 07 20` | cmd 7 + payload `[feature]`；单测锁定 |
| BASIC 取消通知（ANC_V2） | `00 1D 00 08 20` | cmd 8 + `[feature]` |
| 电量：查询支持哪些电池 | `00 1D 1A 00` | feature `0x0D`/cmd 0；单测 `电量查询命令与协议文档字节一致` |
| 电量：查询左耳+右耳 | `00 1D 1A 01 01 02` | cmd 1 + payload `[1,2]`；单测锁定 |
| 电量：查询左右+盒 | `00 1D 1A 01 01 02 03` | 单测 `cmd1 带类型列表的查询帧与协议文档一致` |
| 电量：查询全部 4 类 | `00 1D 1A 01 00 01 02 03` | `FALLBACK_QUERY_IDS = [0,1,2,3]` |
| 电量：老固件查全部（无 payload） | `00 1D 1A 01` | `Gaia.batteryGetAll()` |
| 提示音：读配置 | `00 1D 1C 01` | feature `0x0E`/cmd 1；✅ 官方 App logcat 实机确认 |
| 提示音：写配置（开/音量 20/索引 1） | `00 1D 1C 02 01 14 01` | cmd 2 + `[enabled][volume][index]`；✅ 官方 App logcat 原样数据 `data=[1, 20, 1]` |
| 提示音：写配置（关/音量 82/索引 1） | `00 1D 1C 02 00 52 01` | ✅ 官方 App logcat 原样数据 `data=[0, 82, 1]` |
| 提示音：写配置（越界裁剪） | `00 1D 1C 02 01 64 00` | 音量 250 被裁剪为 100（`0x64`）、索引 0；单测锁定 |
| 增益：读 | `00 1D 1E 01` | feature `0x0F`/cmd 1 |
| 增益：写 0 | `00 1D 1E 02 00` | cmd 2 + 设备码 |
| LHDC：读 | `00 1D 20 05` | feature `0x10`/cmd 5；单测 `LHDC 编解码开关命令` |
| LHDC：开 | `00 1D 20 06 01` | cmd 6 + `01`；单测锁定 |
| LHDC：关 | `00 1D 20 06 00` | cmd 6 + `00` |
| LDAC：读 | `00 1D 20 02` | cmd 2（未接线） |
| LC3：读 | `00 1D 20 01` | cmd 1（未接线） |
| 指示灯：读 | `00 1D 26 01` | feature `0x13`/cmd 1 |
| 指示灯：开 | `00 1D 26 02 01` | cmd 2 + `01` |
| 双设备连接：读状态 | `00 1D 28 01` | feature `0x14`/cmd 1；单测锁定 |
| 双设备连接：开 | `00 1D 28 02 01` | cmd 2 + `01`；单测锁定 |
| 双设备连接：关 | `00 1D 28 02 00` | cmd 2 + `00` |
| 双设备连接：读超时 | `00 1D 28 03` | cmd 3（秒） |
| 双设备连接：写超时 | `00 1D 28 04 0A` | cmd 4 + 秒（示例 10 s） |
| 双设备连接：设备列表 | `00 1D 28 05` | cmd 5（首页）；单测锁定 |
| 双设备连接：设备列表翻页 | `00 1D 28 06` | cmd 6；单测锁定 |
| 双设备连接：断开某台 | `00 1D 28 07 01 AA BB CC DD EE FF 50 61 64` | cmd 7 + `[num:1][addr:6][name utf8]`（示例 num=1、`AA:BB:CC:DD:EE:FF`、名字 `Pad`）；单测 `断开已连接设备的 payload…` |
| ANC V1：读状态 | `00 1D 04 01` | feature 2/cmd 1 |
| ANC V1：写状态 | `00 1D 04 02 01` | cmd 2 + `0/1` |
| AudioCuration：读当前模式 | `00 1D 10 03` | feature 8/cmd 3 |
| AudioCuration：设为关 | `00 1D 10 04 01` | cmd 4 + **位掩码** `01` |
| AudioCuration：设为降噪 | `00 1D 10 04 02` | 位掩码 `02` |
| AudioCuration：设为通透 | `00 1D 10 04 04` | 位掩码 `04` |
| ANC V2：读当前模式 | `00 1D 40 03` | feature 32/cmd 3；单测 `ANC V2 读写与文档一致` |
| ANC V2：设模式 4 | `00 1D 40 04 04` | cmd 4 + 模式码 |
| ANC V2：读切换配置 | `00 1D 40 29` | cmd 41 |
| ANC V2：写切换配置 | `00 1D 40 2A 01 01 01 01 00` | cmd 42 + `[STATE][ANC_ON][ANC_OFF][TRANSPARENT][ORDER]` |
| 空间音频：读 | `00 1D 24 01` | feature 18/cmd 1（**已接线**；命令号与 payload 只来自本表，未真机验证） |
| 左右声道反转：读 | `00 1D 3C 01` | feature 30/cmd 1（未接线） |
| 关机 | `00 1D 30 01` | feature 24/cmd 1（未接线） |

### 回包 / 通知 / 错误帧的形态

| 方向 | 帧 | 说明 |
|---|---|---|
| 电量响应（分体） | `00 1D 1B 01 01 50 02 4B` | feature 13、type 2(RESPONSE)、cmd 1，payload `[1,80][2,75]`；单测 `parse 解析响应帧` 用 `00 1D 1B 01 02 01 50 02 4B` 同族数据 |
| 电量通知 | `00 1D 1A 81 00 3C` | feature 13、type 1(NOTIFICATION)、cmd 1，payload `[0,60]`（type 0 整机 60%） |
| ANC V2 响应 | `00 1D 41 03 02` | type 2、cmd 3，payload `[mode]` |
| ANC V2 通知 | `00 1D 40 83 02` | type 1、cmd 3，payload `[mode]` |
| PUDDING ANC 回包 | `00 1D 41 04 <mode>` | 与上面同族（cmd 4 的 RESPONSE） |
| 错误帧 | `00 1D 05 81 00` | type 3(ERROR)，`payload[0]` = 错误码 |

> ⚠ `Gaia.kt` 只定义了 `TYPE_COMMAND/TYPE_NOTIFICATION/TYPE_RESPONSE` 三个常量，**没有 `TYPE_ERROR` 常量**；
> `parse()` 会把 type 3 原样解析出来，客户端目前不专门处理 ERROR 帧。

### GAIA V3 错误码（`payload[0]`，来源 moondrop-link `constants.py`）

| 值 | 含义 |
|---|---|
| 0 | FEATURE_NOT_SUPPORTED |
| 1 | COMMAND_NOT_SUPPORTED |
| 2 | NOT_AUTHENTICATED |
| 3 | INSUFFICIENT_RESOURCES |
| 4 | AUTHENTICATING |
| 5 | INVALID_PARAMETER |
| 6 | INCORRECT_STATE |
| 7 | IN_PROGRESS |

---

## 4. Feature ID 表

`core/Gaia.kt` 里定义的全部 feature 常量（十进制 / 十六进制）：

| ID | 常量名 | 含义 | 本应用使用 |
|---:|---|---|---|
| 0 | `F_BASIC` | 基础（版本、能力、序列号、通知注册） | ✅ 能力探测 |
| 1 | `F_EARBUD` | 耳塞信息 | — |
| 2 | `F_ANC` | ANC V1 | ✅ 探测路径之一 |
| 3 | `F_VOICE_UI` | 语音 UI | — |
| 4 | `F_DEBUG` | 调试 | — |
| 5 | `F_MUSIC_PROCESSING` | 音乐处理 / EQ | — |
| 6 | `F_UPGRADE` | 固件升级（本项目明确不做 OTA） | — |
| 7 | `F_HANDSET_SERVICE` | 手柄 / 通话服务 | — |
| 8 | `F_AUDIO_CURATION` | AudioCuration（降噪/通透/风噪等） | ✅ 探测路径之一 |
| 9 | `F_EARBUD_FIT` | 佩戴检测 | — |
| 10 | `F_VOICE_PROCESSING` | 语音处理 | — |
| 11 | `F_GESTURE_CONFIGURATION` | 手势配置 | — |
| 12 | `F_STATISTICS` | 统计 | — |
| 13 | `0x0D` `F_BATTERY` | 电量 | ✅ |
| 14 | `0x0E` `F_VOICE` | 提示音（voice prompt） | ✅ 命令号与 payload 已由官方 App logcat 实机确认 |
| 15 | `0x0F` `F_DAC_GAIN` | 增益 | ✅ |
| 16 | `0x10` `F_CODEC_TYPE` | 编解码（LC3 / LDAC / LHDC） | ✅ LHDC（LC3/LDAC 未接线） |
| 17 | `F_LIGHT_SENSOR` | 光线传感器 | — |
| 18 | `F_SPATIAL_AUDIO` | 空间音频 | ✅ 已接线（读/写/回读都有实现：cmd 1 读、cmd 2 写，头动追踪 cmd 3/4）；⚠ 命令号与 payload 只来自本表，**未真机验证** |
| 19 | `0x13` `F_LED` | 指示灯 | ✅ |
| 20 | `0x14` `F_ONEBRINGTWO` | 双设备连接 / 一拖二 | ✅ |
| 21 | `F_BT_ADDRESS` | 蓝牙地址 | — |
| 22 | `F_TOUCHV2` | 触控 V2 | — |
| 23 | `F_AUDIO_RESOURCE` | 音频资源 | — |
| 24 | `F_POWER_CONTROL` | 电源控制（关机） | 已实现未接线 |
| 25 | `F_POWER_TIMEOUT` | 自动关机超时 | — |
| 26 | `F_TOUCHV3` | 触控 V3 | — |
| 27 | `0x1B` `F_DYBASS` | 动态低音 | — |
| 29 | `0x1D` `F_AUDIO_FILE_STORAGE` | 音频文件存储 | — |
| 30 | `0x1E` `F_LR_CHANNEL` | 左右声道反转 | 已实现未接线 |
| 32 | `0x20` `F_ANC_V2` | ANC V2 | ✅ 探测路径之一 |

> 28、31 在 `Gaia.kt` 中没有定义。上游 moondrop-link `constants.py` 只列出
> `BASIC(0x00) / EARBUD(0x01) / ANC(0x02) / AUDIO_CURATION(0x08) / BATTERY(0x0D) /
> ONEBRINGTWO(0x14) / DYBASS(0x1B) / AUDIO_FILE_STORAGE(0x1D) / LR_CHANNEL(0x1E) / ANC_V2(0x20)`，
> 与本表一致（其余常量来自官方 App 内嵌 `com.qualcomm.qti.gaiaclient` 的插件命名）。

### 能力位图（`GET_SUPPORTED_FEATURES`）

```
TX 00 1D 00 01                 （第 2 页用 cmd 2：00 1D 00 02）
RX 00 1D 00 81 <payload...>    （feature 0 / type 1? → 实际为 COMMAND 的 RESPONSE：00 1D 00 <0x81=0x01|0x80?>）
```

* 端侧分页标志：`payload[0] & 0x01` == 1 表示「还有下一页」（moondrop-link 与本项目一致）；
* **正文编码：代码两种都试**（初稿之后修正）。上游两派读法互斥：
  * 上游 moondrop-link `features.py::get_supported_features()`：正文是 **`[more][featureId][version]...` 字节对**；
  * FxxkMoondrop：正文是 **32-bit word 位图**（大端），word `i` 覆盖 feature `32*i .. 32*i+31。
  * 本项目 `Gaia.parseSupportedFeaturesSmart()`：**先按字节对解析**（`parseFeatureEntries()`；feature id 落在
    0..63 才认为条目合法，否则整体判定失败），失败再回退 `Gaia.parseSupportedFeatures()` 的位图解析。
    能力探测与通知型位图都走 `parseSupportedFeaturesSmart()`。
  * ⚠ **仍未真机抓包确认**具体固件用哪种编码，以及「字节对恰好也构成合法位图」导致误判的风险
    （误判会直接改变 ANC 路径选择与 UI 开关展示）。
* `Gaia.isFeaturePayloadTruncated()`（payload 长度非 4 倍数 → 末位 word 丢失）已实现但**未被调用**。

---

## 5. 电量协议（feature `0x0D` / 13）

### 5.1 两条命令

| 步骤 | TX | RX |
|---|---|---|
| ① 问「你有哪些电池」 | `00 1D 1A 00`（cmd 0，无 payload） | payload = type 列表，例如 `01 02 03` |
| ② 问这些电池的电量 | `00 1D 1A 01 <type...>`（cmd 1，payload = 要查的 type 列表） | payload = `[type, level]` 对，例如 `01 50 02 4B` |

老固件（GAIA V3，如 GA2）也可以用「cmd 1 + 无 payload」一次问全部：`00 1D 1A 01`。

### 5.2 电池 type

| type | 含义 |
|---:|---|
| 0 | **单设备（整机电量）** |
| 1 | 左耳 |
| 2 | 右耳 |
| 3 | 充电盒 |
| 255 | 固件表示「该组件无数据」（`BATTERY_LEVEL_UNKNOWN`） |

上游 moondrop-link `constants.py` 的命名对照：`BAT_SINGLE_DEVICE=0 / BAT_LEFT_DEVICE=1 /
BAT_RIGHT_DEVICE=2 / BAT_CHARGER_CASE=3 / BAT_LEVEL_UNKNOWN=255`，names 为
`device / left / right / case`。

### 5.3 关键实测事实

* **Moondrop EDGE 只回 type 0（单设备）**，不回 1/2 —— 上游 moondrop-link 在真机（60%）验证。
* 布丁 PUDDING 三路齐全：type 1=左耳、2=右耳、3=充电盒（PuddingPods 文档），查询用
  `00 1D 1A 00` 或 `00 1D 1A 01 01 02`，回包 `00 1D 1B ...`；
* 梦回2（GA2）实测**只有左右耳，没有充电盒电量**（FxxkMoondrop）。
* 官方 App 自己的 logcat（2026-09-14 真机）显示电量变化是以**通知帧**到达的：
  `BatteryPlugin: onNotification: packet = Packet{version=V3, vendor=001D, command=Command{type=NOTIFICATION, feature=000D, command=0001}}`
  —— 即 `feature=0x0D`、`command=0x01`、`type=NOTIFICATION`（`00 1D 1A 81 …` 形态），
  与本应用 `MoondropLink.dispatch()` 里对电量通知的处理一致（该处理路径因此得到旁证，但本应用自身仍未在真机跑过）。

### 5.4 解析规则（`BatteryCodec.parse`）

| payload 形态 | 处理 |
|---|---|
| `[type, level, type, level, …]` | 标准：按对读取 |
| `[count, type, level, …]` | 仅当 `(size-1) % 2 == 0`、`count == 剩余对数`、`count ∈ 1..8` 时跳过头字节 |
| `[level]` | 单值：仅当给了 `hintType`（且 0..100）才成对 |

* 合法 type 仅 `0..3`，合法电量仅 `0..100`；不合法/未知 type **只忽略，不位移**；
* `255`（无数据）被丢弃。

### 5.5 状态归并（`BatteryState`）

* `single`（type 0）/ `rawLeft`（1）/ `rawRight`（2）/ `rawCase`（3）各自保留最近一次有效值；
* 对外 `currentLeft = rawLeft.known ? rawLeft : single`，`currentRight = rawRight.known ? rawRight : single`
  → **只回 type 0 的机型左右耳都能显示**，显式分体值优先；
* `systemLevel()` 取左右耳较小者，供系统蓝牙栈使用；
* `fallbackFromSystem(level)` 系统单值兜底**同时写左右耳与整机**（只兜左耳就是「右耳不显示」的成因）；
* `usesSingleDeviceBattery()` = 有 type 0 且无任何显式 1/2 值。

完整根因分析见 [ADAPTATION.md](ADAPTATION.md) 第七节。

---

## 6. 命令目录（按 feature 分组）

### BASIC（0）

| 命令 | 值 | 用途 |
|---|---:|---|
| GET_GAIA_VERSION | 0 | — |
| GET_SUPPORTED_FEATURES | 1 | 能力位图首页 |
| GET_SUPPORTED_FEATURES_NEXT | 2 | 能力位图翻页 |
| GET_SERIAL_NUMBER | 3 | — |
| GET_VARIANT | 4 | — |
| GET_APPLICATION_VERSION | 5 | RX payload = 版本串 |
| REGISTER_NOTIFICATION | 7 | payload `[feature]`，例如 ANC_V2 → `00 1D 00 07 20` |
| CANCEL_NOTIFICATION | 8 | payload `[feature]` |
| GET_EARBUD_COLOR / LANG / SN_L / SN_R | 18 / 19 / 20 / 21 | — |
| GET_TWS_CONNECTION_STATUS | 22 | — |

### ANC V1（2）

| 命令 | 值 |
|---|---:|
| GET_ANC_STATE / SET_ANC_STATE | 1 / 2 |
| GET_NUM_ANC_MODES / GET_CURRENT_ANC_MODE / SET_ANC_MODE | 3 / 4 / 5 |
| GET_CONFIGURED_LEAKTHROUGH_GAIN / SET_LEAKTHROUGH_GAIN | 6 / 7 |

### AUDIO_CURATION（8）

命令 0..42，`Gaia.kt` 全部列出（`C_AC_*`）。本应用实际只用到 **3 GET_CURRENT_MODE** 与 **4 SET_MODE**；
上位机 moondrop-link 额外标注 `GET_TOGGLE_CONFIGURATION=8`、`GET_WIND_NOISE_DETECTION_STATE=23`、
`GET_CURRENT_ANC_SWITCH_CONF=41`、`SET_ANC_SWITCH_CONF=42`。

**EDGE 的 SET_MODE payload 是位掩码**（真机听感确认）：`0x01`=关闭降噪、`0x02`=ANC、`0x04`=通透；
而**读回是 0-based 索引** `0`=关 / `1`=ANC / `2`=通透。

### ANC V2（32）

| 命令 | 值 | 说明 |
|---|---:|---|
| GET_CURRENT_MODE | 3 | `00 1D 40 03` → payload `[mode]` |
| SET_CURRENT_MODE | 4 | `00 1D 40 04 <mode>` |
| GET_CURRENT_ANC_SWITCH_CONF | 41 | `00 1D 40 29` → 5 字节 |
| SET_ANC_SWITCH_CONF | 42 | `00 1D 40 2A` + `[STATE][ANC_ON][ANC_OFF][TRANSPARENT][ORDER]` |

ANC V2 模式枚举（官方 `AncV2Handler` / moondrop-link `constants.py` 一致）：

| 值 | 含义 |
|---:|---|
| 0 | OFF |
| 1 | ON（ANC） |
| 2 | TRANSPARENT（通透） |
| 3 | ANTI_WIND（抗风） |
| 4 | ADAPTIVE（自适应） |
| 5 | LIVE |

布丁 PUDDING 在 ANC V2 上用的也是**这张表**，没有专属枚举偏移
（PuddingPods 早期文档里的 `0/1/2/3/4 = 关/自适应/通透/抗风/基础降噪` 是错的，
它把「基础降噪」摆在了 `4`、「自适应」摆在了 `1`，实际正好相反）。
依据：MOONDROP Link 的 `AncV2Handler` 把 `MODE_ANC_OFF/ON/TRANSPARENT/ANTI_WIND/ADAPTIVE/LIVE`
硬编码为 `0..5`，`handleSetAncV2Mode` 仅校验 `0 <= mode < 6` 后原样下发。

### BATTERY（13）

见第 5 节。常量：`C_BATT_GET_BATTERY_LEVELS=1`、`C_BATT_GET_BATTERY_LEVELS_V4=0`、
`C_BATT_GET_SUPPORTED_BATTERIES=0`（后两者同值，语义分别是「V4 无 payload 查全部」与「查支持的 type」）。

### VOICE（14，提示音）✅ 已实机确认

> 证据：2026-09-14 在真机（Xiaomi Pad 8 Pro）上抓取**水月雨官方 App 自身 `com.qualcomm.qti.gaiaclient`
> 的 logcat**，它把收发帧直接打印了出来（原文）：
>
> ```
> V3VoicePlugin: fetchVoiceConf
> [V3VoicePlugin->onResponse] command=1, data=[1, 20, 1], size=3
> [V3VoicePlugin->onResponse] Using V2 format (size >= 3)
> VoiceRepositoryData: updateV2VoiceConf: enabled=true, volume=20, index=1
> ...
> V3VoicePlugin: setVoiceConf
> [V3VoicePlugin->onResponse] command=2, data=[0, 82, 1], size=3
> ```

| 命令 | 值 | 说明 |
|---|---:|---|
| `C_VOICE_GET_CONF` | **1** | 读整份提示音配置 |
| `C_VOICE_SET_CONF` | **2** | 写整份提示音配置 |

* **没有独立的音量命令**：旧文档里的「音量 GET=3 / SET=4」是**错的**，代码已废弃，
  `C_VOICE_GET_VOLUME` / `C_VOICE_SET_VOLUME` 现在只是 `C_VOICE_GET_CONF` / `C_VOICE_SET_CONF` 的**兼容别名**。
* **V2 payload = `[enabled(0/1)][volume(0..100)][index]`**（`size >= 3` 即 V2 格式）。
  * `volume` 是**百分比 0..100**（`Gaia.VOICE_VOLUME_MAX = 100`），**不是** 0..255 的原始字节，
    也不是滑条量程外的设备自定义值；
  * `index` 是提示音索引（语言/主题），**取值语义未确认**；
  * `parseVoiceConf()` 兼容 V2 三字节、以及只有开关位的 2 字节 / 1 字节短格式。
* ⚠ **写入必须一次给出完整三字节**：固件把 enabled/volume/index 当作**一份配置**，
  只发开关位会把音量与索引一起写坏（这正是「改开关把音量清零」类问题的来源）。
  `MoondropLink.setPromptTone()` 会保留当前音量与索引，`setPromptVolumeRaw()` 会保留开关与索引。
* 字节示例：`00 1D 1C 01`（读）；`00 1D 1C 02 01 14 01`（开/20/1，日志原样 `data=[1,20,1]`）；
  `00 1D 1C 02 00 52 01`（关/82/1，日志原样 `data=[0,82,1]`）。以上由单测逐字节锁定。
* 档案里仍保留 `cmdVoiceGetEnable` / `cmdVoiceSetEnable` / `cmdVoiceGetVolume` / `cmdVoiceSetVolume`
  覆盖字段（默认即上述已确认值），UI 是否展示由能力位图（feature 14）或档案开关决定。
* **仍未验证的部分**：`index` 的取值含义；以及本应用**自身**尚未在真机上跑通提示音读写
  （命令与 payload 由官方 App 的日志证实，不是本应用的实机结论）。

### DAC_GAIN（15）/ LED（19）/ SPATIAL_AUDIO（18）/ LR_CHANNEL（30）/ POWER_CONTROL（24）/ DYBASS（27）

| feature | GET / SET | payload |
|---|---|---|
| 15 增益 | 1 / 2 | 设备码（含义由机型 `gainMap` 决定） |
| 19 指示灯 | 1 / 2 | `0`=关 / `1`=开 |
| 18 空间音频 | 1 / 2（开关）；3 / 4（头动追踪） | `0/1` |
| 30 声道反转 | 1 / 2 | — |
| 24 电源 | SET_PWR_OFF = 1 | — |
| 27 动态低音 | 1 / 2 | — |

> 上表中 **18 空间音频已接线**（`MoondropLink.refreshSpatial` / `setSpatial` / `setHeadTracking`
> 三条读写路径 + 详情页两个开关），但命令号与 payload 只来自本表，**未真机验证**；
> 30 声道反转、24 电源、27 动态低音目前只有 `Gaia` 的帧构造函数，客户端没有读写路径。

### CODEC_TYPE（16）

| 命令 | 值 | 帧 |
|---|---:|---|
| GET_LC3_STATE / SET_LC3_STATE | 1 / 3 | `00 1D 20 01` |
| GET_LDAC_STATE / SET_LDAC_STATE | 2 / 4 | `00 1D 20 02` |
| GET_LHDC_STATE / SET_LHDC_STATE | 5 / 6 | `00 1D 20 05` / `00 1D 20 06 01` |

**LHDC 出厂默认关闭**：上游实测（连接中的耳机）主机侧广告 LHDCv5 / LHDC_V3 / LHDC_V2 / LDAC / aptX-adaptive，
而耳机实际活动编码是 **AAC**。所以「默认跑 AAC/SBC」是正常出厂状态，开关打开后能否稳定协商需真机验证。

官方 App 自己的 logcat（2026-09-14 真机）也把这条命令原样打了出来，可作为**帧格式**的旁证：

```
CodecRepositoryImpl setLhdcState
SetCodecRequest run: info=CODEC_LHDC, value=0
V3CodecPlugin setInfo CODEC_LHDC, value: 0
V3Plugin: sendPacket command = 6 data = 0
Plugin: send: packet = 0x00 0x1D 0x20 0x06 0x00
```

即「关闭 LHDC」= `00 1D 20 06 00`（feature `0x10`、cmd 6、payload `00`），与本项目 `Gaia.lhdcSet(false)` 一致。

### ONEBRINGTWO（20，双设备连接）

| 命令 | 值 | 帧 | 说明 |
|---|---:|---|---|
| GET_STATE | 1 | `00 1D 28 01` | 读开关 |
| SET_STATE | 2 | `00 1D 28 02 01` | 写开关，payload `[0\|1]` |
| GET_TIMEOUT | 3 | `00 1D 28 03` | 「暂停回连」超时秒数 |
| SET_TIMEOUT | 4 | `00 1D 28 04 <sec>` | |
| GET_DEVICES | 5 | `00 1D 28 05` | 首页 |
| GET_DEVICES_NEXT | 6 | `00 1D 28 06` | 翻页 |
| DISCONNECT | 7 | `00 1D 28 07 …` | payload `[num:1][addr:6][name utf8]` |

设备条目解析（`Gaia.parseLinkedDevice`）：`num = payload[0]`，
`addr = payload[1..6]` 按 `%02X:` 拼接，`name = payload[7..]` UTF-8；长度 < 7 返回 `null`。

> 上游 moondrop-link 的注意事项：**一个 GAIA 响应只带一个 DeviceInfo**；
> 设备列表读完时固件会**重复最后一条**，因此按「地址+名字」去重，重复即当作列表结束
> （`features.py::get_current_devices`：先 cmd 5，再 cmd 6 ×3）。本项目只提供了解析器与命令构造，
> 没有实现该去重循环。
> 真机验证状态（上游原文）：读取与开关已验证；**写入 / 断开单台需双机场景实测**。

---

## 7. RFCOMM / SPP 切帧（`core/GaiaFramer.kt`）

GAIA V4 设备在 Classic Bluetooth RFCOMM/SPP 上存在两种封装：

### 7.1 传输帧（官方 `TransportProtocol.Rfcomm`，SOF = `0xFF`）

```
FF | Version(1B) | Flags(1B) | Length(1B 或 2B) | PDU(Length+4) | [Checksum(1B)]
```

| 字段 | 说明 |
|---|---|
| SOF | `0xFF` |
| Version | `≥ 4` 时才允许长度扩展 |
| Flags | `bit0 = CHECKSUM(0x01)`：PDU 之后随附 1 字节校验和；`bit1 = LENGTH_EXTENSION(0x02)`：Length 为 2 字节 |
| Length | **PDU 的 payload 字节数**；PDU 总长 = `Length + 4`（vendor 2B + cmdValue 2B） |
| PDU | 就是第 2 节的裸 GAIA 帧 |
| Checksum | 仅当 Flags bit0 置位时存在 |

切帧：`total = headerLen + pduLen + (hasChecksum ? 1 : 0)`；缓冲不足则**保留半截帧等下一批字节**；
若算出的 `total < headerLen + 4`（明显非法）则丢 1 字节重新扫描。

### 7.2 裸 PDU（部分固件直发）

```
00 1D | cmdValue(2B) | payload...
```

无长度字段 → 只能靠流中**下一个帧起始**（`0xFF` 或 `00 1D`）或 **burst 结束**
（`inputStream.available() == 0`）来定界。

已知局限（源码注释里写明）：若 payload 内部恰好出现 `FF` 或 `00 1D`，会被误判为下一帧起始。
实测水月雨设备单 burst 单帧且 payload 很短，不受影响。其它垃圾字节按 1 字节步进跳过。

### 7.3 两种 SPP UUID（不要混）

| UUID | 用途 |
|---|---|
| `00001101-0000-1000-8000-00805f9b34fb` | **本项目使用**：PuddingPods 文档记录的 GAIA V4 over SPP |
| `00001107-d102-11e1-9b23-00025b00a5a5` | moondrop-link `constants.py` 标注的「legacy GAIA/SPP」；其 BLE 客户端不使用 |

本应用在 `createRfcommSocketToServiceRecord` 失败时会退到反射调用 `createRfcommSocket(1)`（channel 1）。

---

## 8. 中科蓝讯 9ECA 私有协议（`core/SrcProtocol.kt`）

服务 `9eca0000-7f3a-4f32-9a38-a91b2c6e0100`，与 GAIA 服务在**同一个 GATT 连接内并存**，
用于音源切换 / EQ / MIC 增益 / SN（FxxkMoondrop 记录）。

### 8.1 帧

```
[A5][01][frameType][commandId][seq][payloadLen][payload ≤ 14B]
```

| 字段 | 值 |
|---|---|
| MAGIC | `0xA5` |
| VERSION | `0x01` |
| frameType | 1=COMMAND、2=RESPONSE、3=NOTIFICATION |
| seq | 序列号（请求/回包匹配用） |
| payloadLen | ≤ 14（`MAX_PAYLOAD`） |
| payload | 变长 |

### 8.2 特征

| 用途 | UUID |
|---|---|
| Command（写） | `9eca0001-…` |
| Response（通知） | `9eca0002-…` |
| Notification（通知） | `9eca0003-…` |
| Capability（直读） | `9eca0004-…` |
| Firmware Info（直读） | `9eca0005-…` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

### 8.3 命令 / 通知 / 音源

| 命令 | 值 | | 通知 | 值 |
|---|---:|---|---|---:|
| GET_AUDIO_SOURCE | 1 | | NOTIF_AUDIO_SOURCE_CHANGED | 129 |
| SET_AUDIO_SOURCE | 2 | | NOTIF_SWITCH_STATE_CHANGED | 130 |
| GET_CAPABILITY | 3 | | NOTIF_CAPABILITY_CHANGED | 131 |
| GET_FW_VERSION | 4 | | NOTIF_VOLUME_CHANGED | 133 |
| GET_DEVICE_VOLUME | 5 | | NOTIF_PRESET_EQ_CHANGED | 134 |
| SET_DEVICE_VOLUME | 6 | | NOTIF_PEQ_COMMITTED | 135 |
| GET_PRESET_EQ | 7 | | NOTIF_MIC_GAIN_CHANGED | 136 |
| SET_PRESET_EQ | 8 | | | |
| GET_PEQ_CONFIG | 9 | | | |
| SET_PEQ_PREGAIN | 10 | | | |
| GET_PEQ_POINT | 11 | | | |
| SET_PEQ_POINT | 12 | | | |
| COMMIT_PEQ | 13 | | | |
| GET_MIC_GAIN | 14 | | | |
| SET_MIC_GAIN | 15 | | | |
| PING | 127 | | | |

音源 ID：`0` 蓝牙、`1` USB Audio、`2` 2.4G 无线、`3` AUX Line-In、`4` 光纤 SPDIF、`5` 同轴、
`6` HDMI ARC、`7` 本地播放、`127` 自动选择、`254` 无信号。
切换选项位掩码：`1` 持久化为默认、`4` 切换期间静音、`8` 不自动恢复蓝牙。

⚠ 本项目**只实现了帧构造/解析与少量高层构造器**（音源、能力页、固件版本、音量、预设 EQ、MIC 增益、PING），
**没有任何 UI 调用方，也没有真机验证**（上游 FxxkMoondrop 同样标注「9ECA 协议客户端已实现但未经实机验证」）。

---

## 9. ANC 路径选择

```
探测顺序（优先级由高到低）：
  AudioCuration(8)  →  ANC V2(32)  →  ANC V1(2)  →  未知
```

* 依据：`Gaia.ancPathFrom(features)`（位图命中即选）；
* 位图给不出结论时退回型号档案的 `anc.path`（例如 PUDDING 档案锁定 ANC_V2）；
* 读写命令按路径分派：ANC V2 用 cmd 3/4；AudioCuration 用 cmd 3/4（写位掩码）；ANC V1 用 cmd 1/2。

| 路径 | GET 帧 | SET 帧 | 读回值域 |
|---|---|---|---|
| AudioCuration(8) | `00 1D 10 03` | `00 1D 10 04 <位掩码>` | EDGE：0-based `0..2`；GA2/太空漫游2：`0..3`（0-based） |
| ANC V2(32) | `00 1D 40 03` | `00 1D 40 04 <mode>` | 官方 `0..5`；PUDDING `0..4`（不同枚举） |
| ANC V1(2) | `00 1D 04 01` | `00 1D 04 02 <0/1>` | `0`=关，非 0=开 |

> 上游 FxxkMoondrop 记录：`AudioCuration cmd 41`（GET_CURRENT_ANC_SWITCH_CONF）在 GA2 上**回包不稳定**，
> 曾导致 `ancPath` 卡死在 `-1`；他们的修复是回退到 cmd 3（GET_MODE）。本项目同样只用 cmd 3。

---

## 10. 「低延迟模式」不是一条 GAIA 命令

低延迟是 **HyperOS 系统侧功能**（系统蓝牙设备详情页上的「低延迟」开关），
由系统 A2DP 会话的低延迟配置能力决定，**没有**对应的 GAIA feature/命令。
本应用曾经把它当作系统侧状态透出（`PodCapabilities.hasLowLatency` 只看型号档案，
`PodSnapshot.lowLatencyOn` 由系统侧回调），**从不发送任何 GAIA 帧**。
**去模块化之后，本应用连这个开关也不做了**：详情页低延迟开关已移除，
`FeatureProfile.lowLatency` 字段仍在但不再门控任何 UI；系统设备详情页里的低延迟功能与本应用无关。
PuddingPods 文档也把它归类为 `BluetoothDeviceDetailsFragment` 提供的系统 Profile/厂商控制能力。

---

## 10.5 HyperOS 焦点通知 / 超级岛：`miui.focus.*`（**已用真机 dumpsys 原文核对**）

这一节不是 GAIA 协议，而是「耳机状态怎么显示在 HyperOS 上」的宿主侧契约。

> ⚠ **本节修正过一次。** 初版是反汇编 ROM APK 猜出来的，把 `miui.focus.param` 记成了
> `Bundle{ param_v2 = JSON }` —— **错的**。后来从同作者的另一个仓库
> （`huimeyc/HyperPods` 的 `dev` 分支，跑在同一台 HyperOS 4 平板上、插同一副水月雨耳机）
> 用 `dumpsys notification --noredact` 读到了系统里真实存在的那条通知，下面是原文。

### 10.5.1 通知 extra（原文）

```
miui.focus.param = String ( {"type":"com.xzakota.hyper.notification.focus
                                  .FocusNotification.FocusTemplateFactory.V3",
                              "param_v2":{ …见 10.5.2… } } )
miui.focus.pics   = Bundle
miui.focus.actions= Bundle
```

**`miui.focus.param` 是字符串**（不是 Bundle），值是 JSON：
- `type` = 焦点通知的**模板工厂类名**（`xzakota` 那套焦点通知库的 V3 模板），这是渲染器认的标识，
  不是协议字段；
- 内容全部在它下面的 `param_v2` 里。

### 10.5.2 `param_v2` 的 JSON 结构（原文，节选）

```json
{
  "ticker": "MOONDROP Pudding",
  "updatable": true,
  "enableFloat": true,
  "iconTextInfo": {
    "title": "MOONDROP Pudding",
    "content": "左0% 右91% ",
    "animIconInfo": { "type": 0, "src": "key_headset" }
  },
  "textButton": [
    { "action": "key_anc_cycle",  "actionTitle": "切换降噪" },
    { "action": "key_disconnect", "actionTitle": "断开连接" }
  ],
  "param_island": {
    "islandProperty": 1,
    "bigIslandArea": {
      "imageTextInfoLeft":  { "type": 1, "picInfo":  { "type": 1, "pic": "key_headset" } },
      "imageTextInfoRight": { "type": 2, "textInfo": { "title": "MOONDROP Pudding", "content": "左0% 右91% " } }
    }
  },
  "aodTitle": "L 0% | R 91%",
  "aodPic": "key_headset"
}
```

要点：
- **超级岛就是 `param_island` 这一块**（`islandProperty` / `bigIslandArea` / `imageTextInfoLeft/Right`），
  不需要另外发一条「强提示」广播 —— 对耳机这条通知来说，岛是焦点通知 JSON 的一部分。
- `textButton` 里的 `action` 是**系统侧认的 key**（`key_anc_cycle` / `key_disconnect`），
  不是我们自己的 Intent action。
- 图标用 `key_headset`（不是 `miui.focus.pic_*` 那种资源名）。


### 10.5.4 已排查：音乐 / 游戏模式不走 GAIA（2026-09 实测）

水月雨「布丁」有**音乐 / 游戏两种模式**，官方 APP 没有调控页，只能四击耳机背切换。
用真机抓包排查过一轮，结论是**它不通过 GAIA 暴露，也没有找到 APP 可读/可控的通道**，
记录在此免得重复投入：

- 抓法：root 抓 btsnoop（`/data/misc/bluetooth/logs/btsnoop_hci_*.log`，本 ROM 当前**不截断**，
  可完整解出 GAIA 帧）+ `tools/btsnoop_gaia.py`；时间轴用 `tools/gaia_mode_capture.sh`（MARK 走
  `log -t PODMARK`，别用 `>>` 追加到 logcat 输出文件 —— 会被 logcat 的文件 offset 覆盖）。
- 实测：在 APP 保持 GAIA 连接、并确认 A2DP/HFP 都连着的状态下四击，**整段窗口内 GAIA 一条帧
  都没有**（同期只有每 3s 一次的 `13/BATTERY` 轮询，与四击无关）；把 btsnoop 按 10 秒分桶统计，
  其它 ACL 链路（含当时被误认为「突发」的那条）只是 A2DP 媒体相关的**稳定**活动 ——
  断连时活动下降、重连后恢复，与四击**不对齐**。
- 结论：该模式是**耳机固件内部状态**（切换不需要主机参与、也不上报），目前没有 APP 侧可行路径。
  若要继续，只剩「模块形态 hook MIUI 自己的耳机服务」这一条不确定的路（dev 仓库已 hook
  `com.android.bluetooth.ble.app.IMiuiHeadsetService`），普通 APP 无解。
- 反面记录：曾把某帧里的字节对 `00 1d` 当作 GAIA vendor 0x001D，实为另一协议 24 位序列号的
  巧合命中 —— 判定 GAIA 帧必须校验完整 header（vendor + commandWord 的 feature/type/cmd 边界），
  不能只匹配两个字节。

### 10.5.3 当前实现

MiuixMoondrop 现在**自己就把上面这套 extra 写上**（`pods/PodFocusNotification.kt`），用的正是
本节记录的那个模板库：`com.xzakota.hyper.notification:focus-api`（Maven Central，与 HyperPods
dev 分支同库同版本）—— 手拼 `miui.focus.pics` / `miui.focus.actions` 两袋 Parcelable 只能靠
反汇编试错，所以直接复用那套模板的实现。

- 通知本体 = `pods/PodNotification.kt` 的状态通知，通道 `hyperpods_moondrop_app_status`。
  档位是 **`IMPORTANCE_DEFAULT` + `setSound(null)` + `enableVibration(false)`**（不响铃不震动）：
  用 `IMPORTANCE_LOW` 时 HyperOS **不会**走焦点通知那条渲染路径（dev 分支在同一台 HyperOS 4
  平板上验证过，它那条焦点通知用的就是 DEFAULT）。通道档位改过，[ensureChannel] 会自动删掉重建。
- 通知 **`setOngoing(true)` 常驻**：耳机连着时不可划掉（划掉后要等下次内容变化才回来，用户会
  以为「通知坏了」），断开连接时由 `cancelNow` 程序化撤掉。
- **超级岛挂在常驻通知上**（2026-09 起）：本应用**不再**单独发「连接 / 断开」的临时岛通知
  （原 `pods/PodIslandNotification.kt` 与设置页「超级岛」开关一并删除，见 CHANGELOG），岛跟着
  `pods/PodNotification.kt` 这条常驻焦点通知一起出现（连接、以及电量每 30s 变化的那一刻），
  几秒内自己收掉。踩过三次坑之后留下的结论，别再走回头路：
  1. **把 `island` 置空不管用**：库的 `Json` 配了 `explicitNulls = false`，null 值根本不会写进
     JSON（「显式置空」在代码上早已走到底）；而焦点通知在 HyperOS 上自带摘要态，不给
     `param_island` 时系统会用默认形态补一个**常驻摘要胶囊** —— 这就是最早「岛一直挂着」的来源；
  2. **不要写 `islandFirstFloat = false`**：该字段含义是「通知第一次出现时的档位」，`false` = **摘要态**
     —— 摘要态正是岛区小胶囊的地盘，常驻通知被强制成摘要态就会一直挂着；
  3. 岛模板**只写 `bigIslandArea`、不写 `smallIslandArea`**（摘要态那块只能放图片 `picInfo`）：
     摘要态没有自己的内容，岛不会长住（dev 仓库蓝牙常驻通知就是这个形态）；
  4. 再显式 **`dismissIsland = true`**（官方字段：摘要态是否消失，true = 消失）＋ **`islandTimeout = 5`**
     （**岛模板字段，单位是秒**；不是模板基类的 `timeout` —— 那个单位是**分钟**，早先误用
     `timeout = 5` 等于给岛留了 5 分钟寿命，是「岛不走」的帮凶）让它几秒内自己退场。
  真正的判定在设备侧：抓 `MiuixMoondropFocus` 的 `focus param:` 日志可以看到这段 JSON。
- 通知上挂一个动作按钮「断开连接」：**模板动作栏** `param_v2.actions` 里一条 `type = 2`
  （文字按钮；0 圆形 / 1 进度 / 2 文字）的 `ActionInfo`，点击目标用库的 `createAction` 放进
  `miui.focus.actions` 那一袋 Parcelable、再由 `ActionInfo.action` 用 key（`key_disconnect`）
  引用（官方文档「Action 数据参数」的做法）；普通通知（无焦点 extra）回落
  `Notification.Builder.addAction`。落点是 `pods/PodDisconnectReceiver.kt`（`exported="false"`，
  PendingIntent 以本应用身份投递），断的是**应用自己**那条链路（`MoondropLink.disconnect()`）；
  A2DP / HFP 音频链路归系统蓝牙管，普通应用没有 BLUETOOTH_PRIVILEGED，代不了用户断开它。
- 岛的布局：**左 = 机型图 + 设备名，右 = 内容**（电量）。右栏必须写 `textInfo.title`
  而不是 `content` —— 实测右栏只渲染 `title`，把电量放进 `content` 时岛上只看得到设备名。
- `updatable` 开（电量每 30s 变一次，要能原地更新）；`enableFloat` 恒为 **false** ——
  否则每次更新都会再展开一次岛。
- AOD 直接写模板基类自带的 **`aodTitle`** 字段（格式 `L 59% | R 64%`，没有读数的组件不写）；
  不需要像下面历史实现那样再往 `miui.focus.param` 的 JSON 里补 `param_v2.aodTitle`。
- 普通 ROM 会忽略这些 extra，通知照常显示，因此带上它们没有兼容性代价。

历史实现（模块时期，代码已删除）：

- 当时在已删除的 `hook/MiBluetoothToastHook.kt` 的 `focusExtras()`：按上面原文复刻，
  外层 `putString`，内层 `param_v2`；`param_island` 只在「超级岛提示」开关打开时写。
- 焦点通知与超级岛都只用真机核对过的字段；**没有**凭空构造官方那套 12 字符按键配置串之类的东西。
- 另有一条「强提示（strong toast）」通路（extra 键 `param` / `island_param` / `strong_toast_action` /
  `duration` / `strong_toast_category` 等已在 ROM 里核对到），但那条广播的 **Intent action 字符串
  在 `com.xiaomi.bluetooth` / `com.milink.service` / `com.android.settings` 三个 APK 的 dex 串池里
  都找不到**（推测在框架侧共享库），所以当时就**不猜也不发**这条广播。
- 真机上验证方式：`dumpsys notification --noredact | grep -A3 miui.focus`，以及 logcat 里
  每条通知都会打的 `focus=` / `island=` 两个值。

## 11. 来源与推导

| 结论 | 来源文件 |
|---|---|
| BLE GATT UUID 族、CCCD、裸帧格式、大端字节序（native `BytesUtils`） | `_refs/moondrop-link/pc/moondrop_link/gaia/constants.py`、`packet.py`、`docs/summary.md` |
| 版本探测 `00 0A 03 00`、vendor `0x000A`/`0x001D`、V3 command word 位布局、type 枚举 | 同上（`packet.py::v3_command_word`、`summary.md`「帧格式」） |
| BATTERY cmd 0/1 与 type 0..3、255 哨兵；**EDGE 只回 type 0** | `constants.py`、`features.py::BatteryFeature`、`summary.md`「命令目录」 |
| ONEBRINGTWO cmd 1..7 的真实语义与**设备列表重复即结束** | `features.py::OneBringTwoFeature`、`summary.md` |
| AudioCuration 命令表与 EDGE 位掩码 SET / 0-based GET | `features.py::AudioCurationFeature`（`set_state` 注释「live-verified」）、`summary.md` |
| ANC V2 cmd 3/4/41/42 与模式枚举 0..5 | `features.py::AncV2Feature`、`constants.py` |
| GAIA V3/V4、三条 ANC 路径与探测条件、9ECA 服务帧与命令表、GA2 双地址连接经验 | `_refs/FxxkMoondrop/ADAPTATION.md` |
| GA2 / 太空漫游2 的实测 SET/GET 映射与增益反向 | `_refs/FxxkMoondrop/src/com/fxxkmoondrop/secret/AncProfileLib.kt` |
| PUDDING：RFCOMM/SPP + GAIA V4、ANC V2 五档枚举、三路电量、增益、指示灯、Device ID `01010607` | `PuddingPods/PUDDING_ADAPTATION.md`（仓库根的 PuddingPods 克隆，本地参考目录 `_refs/pudding-docs/`） |
| 【历史】HyperOS 设置页伪装原生耳机（`HeadsetIDConstants`、`IMiuiHeadsetService$Stub$Proxy`） | `_refs/OppoPods/app/src/main/java/moe/chenxy/oppopods/hook/SettingsHeadsetHook.kt` —— 仅上游参考；本项目相关 hook 已删除 |
| 【历史】融合设备中心设备卡点击（`deviceType == "third_headset"`）、`AdapterService.setBatteryLevel` | `_refs/HyperPods/app/src/main/java/moe/chenxy/hyperpods/hook/DeviceCardHook.kt`、`_refs/HyperPods/app/src/main/java/moe/chenxy/hyperpods/pods/L2CAPController.kt` —— 仅上游参考；本项目相关 hook 已删除 |
| **提示音命令号与 payload**（GET=cmd1 / SET=cmd2、`[enabled,volume,index]`、音量 0..100）、**LHDC 关** `00 1D 20 06 00`、**电量以通知帧到达**（feature `0x0D` cmd `0x01` type NOTIFICATION） | 2026-09-14 真机（Xiaomi Pad 8 Pro）抓取的**水月雨官方 App 自身 `gaiaclient` logcat**（`V3VoicePlugin` / `V3CodecPlugin` / `BatteryPlugin`） |
| 逐字节期望值（回归锁定） | `app/src/test/java/moe/chenxy/hyperpods/core/GaiaProtocolTest.kt`、`BatteryCodecTest.kt` |

> 上游 moondrop-link 的 `constants.py` 里还写着一句「legacy GAIA/SPP `00001107-d102-…`
> （classic transport, not used by this BLE client）」；本项目 GAIA V4 用的是 PuddingPods 记录的
> 标准 SPP UUID `00001101-0000-1000-8000-00805f9b34fb`，两者不是同一个。

---

## 12. 仍未确定（写在这里避免被当成已确认）

1. **提示音**：命令号（GET=1 / SET=2）与 payload `[enabled, volume(0..100), index]` 已由官方 App logcat
   实机确认（见第 6 节 VOICE）；剩下未确认的是 **`index` 字段的取值语义**，以及**本应用自身**
   从未在真机上跑通提示音读写。
2. **能力正文编码**：代码现在**两种都试**（`parseSupportedFeaturesSmart()`：字节对优先、位图兜底），
   但**具体固件用哪种、是否会误判**仍未真机抓包裁决。
3. **ANC 读回值域**：EDGE / EDGE2 已按上游实测补 `getMap = [0,1,2]`（SET 位掩码 `1/2/4` ↔ GET 0-based）——
   参数本身待真机复核；`anc4Identity` 系列（`getMap=null`，`setMap.indexOf()` 反查）若读回是 0-based 仍会得到 `-1`。
4. **GAIA 版本探测**：`00 0A 03 00` 已在连接流程中发送；**探测结果的解析与用途**（是否需要据此切换包格式）未真机确认。
5. **LHDC 打开后的稳定性**、**双设备连接的写入/断开单台**（需双机）、**9ECA 全部功能**、
   **空间音频/头动追踪**（读/写/回读都已接线，**未真机验证**：命令号与 payload 只来自本表）、
   **充电位解析**：均未验证。
6. 上游 FxxkMoondrop 表中把三条 ANC 路径的探测条件写成「BASIC 特性位图含 bit1 / bit3 / bit5」，
   与 feature ID（2 / 8 / 32）不是同一套编号；本项目按 feature ID 在 32-bit word 位图中取位，
   即 `bit 2`、`bit 8`、`bit 32` 对应的位。**哪套读法正确同样取决于第 2 条。**
7. **跨进程链路已删除**：`pods/ControlBridge.kt` 现在只在**应用进程内**把状态转发给本应用自己的
   通知与连接弹窗（详见 `pods/ControlBridge.kt` 的文件头注释）。原先发往 `com.android.bluetooth` /
   `com.android.settings` / `com.xiaomi.bluetooth` / `com.milink.service` 的广播，以及 manifest 里
   那个跨进程接收器，全部随 hook 一起删除；低延迟链路（UI → `ControlBridge` → `com.android.bluetooth`
   的反射 / A2DP codec 兜底）也不再存在。因此**没有「未真机验证的系统集成层」可列**。
8. **真机结论的边界**：本文所有协议结论要么来自本仓库源码 + 单测，要么来自上游项目/官方 App 的真机记录
   （含 2026-09-14 抓取的官方 App gaiaclient logcat）；**布丁（PUDDING）**另有 2026-09-14/15 的本应用
   真机联调结论（记在 `core/MoondropModels.kt` 的 `note` 与 `pods/MoondropLink.kt`、
   `pods/ControlBridge.kt` 的注释里），其余机型没有本应用自己的真机结论。CI 已通过编译与单测并产出
   APK（见 [README.md](README.md) 构建状态），但不为未验证的机型背书。
