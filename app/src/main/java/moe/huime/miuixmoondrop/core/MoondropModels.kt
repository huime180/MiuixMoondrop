/*
 * MiuixMoondrop — 水月雨耳机型号档案库
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 目标：一套代码适配水月雨（MOONDROP）全系 TWS / 颈挂。
 *
 * 数据来源（按可信度排序）：
 *   A. 真机实测（标注 verified = true）
 *      · 水月雨 EDGE —— MegaSuite/moondrop-link-desktop 真机验证：
 *        GAIA v3 over BLE、电量走 BATTERY(0x0D) cmd0/cmd1 且**只上报 type 0 单设备**、
 *        ANC 走 AUDIO_CURATION(0x08) 三态位掩码、ONEBRINGTWO(0x14) 可用。
 *      · 布丁 PUDDING MD-TWS-056 —— PuddingPods 协议文档 + FxxkMoondrop 实测：
 *        GAIA v4 over RFCOMM/SPP、ANC_V2(0x20) 五档、三路电量、增益、指示灯。
 *      · 梦回2 / Golden Ages 2 —— FxxkMoondrop 真机双向实测：
 *        AudioCuration 路径，SET 映射 [1,2,4,3]，GET 为 0-based [0,1,2,3]。
 *      · 太空漫游2 / Space Travel 2 —— FxxkMoondrop 真机实测：
 *        中科蓝讯 BT8932F、9ECA 私有协议、ANC 映射同 GA2、增益反向 [2,1,0]。
 *   B. 芯片级推断（标注 verified = false）—— 主控确认、协议可自动识别，但未逐一实机跑通。
 *
 * 设计原则：
 *   1. **能力优先于型号**。型号档案只提供「这台设备的差异点」，凡是不确定的字段一律
 *      由连接后的能力探测（GET_SUPPORTED_FEATURES 位图 + 实际回包）裁决。
 *   2. 未命中档案的水月雨设备回退 [fallback]，以「可探测 + 保守」策略工作，不会因为
 *      型号不认识就完全不可用。
 *   3. 新增机型 = 在 MODELS 里加一条数据，不需要改任何控制逻辑。
 */
package moe.huime.miuixmoondrop.core

/** 传输层：决定用哪条链路与耳机通信。 */
enum class PodTransport {
    /** GAIA v3 over BLE GATT（大多数 QCC/蓝讯系水月雨耳机） */
    BLE_GAIA,
    /** GAIA v4 over Classic Bluetooth RFCOMM / SPP（布丁 PUDDING 等） */
    RFCOMM_GAIA,
    /** 中科蓝讯私有 BleSourceSwitch（9ECA0000），可与 GAIA 并存 */
    BLE_SRC_9ECA,
}

/** ANC 使用的 feature 路径。 */
enum class AncPathKind(val feature: Int) {
    ANC_V1(Gaia.F_ANC),
    AUDIO_CURATION(Gaia.F_AUDIO_CURATION),
    ANC_V2(Gaia.F_ANC_V2);

    companion object {
        fun fromPath(path: Int): AncPathKind? = when (path) {
            Gaia.ANC_PATH_ANC_V1 -> ANC_V1
            Gaia.ANC_PATH_AUDIO_CURATION -> AUDIO_CURATION
            Gaia.ANC_PATH_ANC_V2 -> ANC_V2
            else -> null
        }
    }
}

/** UI 上的降噪档位。顺序即 UI 顺序。 */
enum class AncMode(val id: String) {
    OFF("off"),
    NOISE_CANCELLATION("anc"),
    TRANSPARENCY("transparent"),
    ANTI_WIND("anti_wind"),
    ADAPTIVE("adaptive"),
    LIVE("live"),
}

/**
 * 降噪档案。
 *
 * @param modes  UI 上应展示的档位（顺序即 UI 顺序）
 * @param setMap UI 下标 → 设备码
 * @param getMap 设备码 → UI 下标；null 表示与 setMap 互逆
 * @param path   固定路径；null 表示由能力位图自动探测
 */
data class AncProfile(
    val modes: List<AncMode>,
    val setMap: IntArray,
    val getMap: IntArray?,
    val path: AncPathKind? = null,
) {
    fun uiToDevice(uiIndex: Int): Int =
        if (uiIndex in setMap.indices) setMap[uiIndex] else -1

    fun deviceToUi(deviceCode: Int): Int {
        getMap?.let { g -> return if (deviceCode in g.indices) g[deviceCode] else -1 }
        return setMap.indexOf(deviceCode)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is AncProfile) return false
        return modes == other.modes && setMap.contentEquals(other.setMap) &&
            (getMap?.contentEquals(other.getMap ?: IntArray(0)) ?: (other.getMap == null)) &&
            path == other.path
    }

    override fun hashCode(): Int = modes.hashCode() * 31 + setMap.contentHashCode()
}

/** 设备控制（DC）档案：增益 / 指示灯 / 空间音频。 */
data class DcProfile(
    val hasGain: Boolean = false,
    /** UI 下标（低,中,高…）→ 设备码 */
    val gainMap: IntArray = intArrayOf(0, 1, 2),
    val gainLabels: List<String> = listOf("低", "中", "高"),
    val hasLed: Boolean = false,
    val hasSpatial: Boolean = false,
    val hasHeadTracking: Boolean = false,
) {
    fun gainUiToDevice(ui: Int): Int = if (ui in gainMap.indices) gainMap[ui] else -1

    fun gainDeviceToUi(dev: Int): Int = gainMap.indexOf(dev)

    override fun equals(other: Any?): Boolean =
        other is DcProfile && hasGain == other.hasGain && gainMap.contentEquals(other.gainMap) &&
            hasLed == other.hasLed && hasSpatial == other.hasSpatial &&
            hasHeadTracking == other.hasHeadTracking

    override fun hashCode(): Int = gainMap.contentHashCode() * 31 + (if (hasGain) 1 else 0)
}

/**
 * 新功能档案。
 *
 * 提示音（feature 14 VOICE）的命令号来自官方 App 内嵌 gaiaclient 的插件命名约定
 * （与水月雨自家其它开关一致的 GET=1/SET=2），**尚未真机确认**，因此在设置页可逐设备覆盖。
 * 双设备连接（feature 20 ONEBRINGTWO）的命令号已由 moondrop-link 真机确认。
 */
data class FeatureProfile(
    /** 是否展示「提示音开关」 */
    val promptTone: Boolean = false,
    /** 是否展示「提示音音量」滑条 */
    val promptVolume: Boolean = false,
    /** 提示音音量上限（官方 App 日志实测音量是 0..100 的百分比） */
    val promptVolumeMax: Int = Gaia.VOICE_VOLUME_MAX,
    /** 是否展示「LHDC 开关」 */
    val lhdc: Boolean = false,
    /** 是否展示「双设备连接」 */
    val dualConnection: Boolean = false,
    /** 是否展示「低延迟模式」 */
    val lowLatency: Boolean = false,
    // 可覆盖的命令号（默认 = Gaia 中已确认/约定的值）
    val cmdVoiceGetEnable: Int = Gaia.C_VOICE_GET_ENABLE,
    val cmdVoiceSetEnable: Int = Gaia.C_VOICE_SET_ENABLE,
    /** 提示音没有独立音量命令，这里与开关指向同一对（仅保留以便覆盖） */
    val cmdVoiceGetVolume: Int = Gaia.C_VOICE_GET_CONF,
    val cmdVoiceSetVolume: Int = Gaia.C_VOICE_SET_CONF,
)

/**
 * 一台水月雨耳机的档案。
 *
 * @param aliases 设备名匹配关键字（大写、contains）。**必须包含 MOONDROP** 时用 id 兜底。
 */
data class MoondropModel(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val aliases: List<String>,
    val chipset: String,
    val transports: List<PodTransport>,
    val verified: Boolean,
    val anc: AncProfile?,
    val dc: DcProfile,
    val features: FeatureProfile,
    /** 已知会以「单设备电量(type 0)」上报，需要在 UI 上说明 */
    val singleDeviceBattery: Boolean = false,
    val note: String = "",
)

object MoondropModels {

    // ── 常用映射表 ─────────────────────────────────────────────────────────

    /** FxxkMoondrop 真机实测：设备码 1=关 2=降噪 3=抗风 4=透传（SET 方向）。 */
    private val GA2_SET = intArrayOf(1, 2, 4, 3)
    /** 同型号 GET 方向：固件读回 0-based 直传 0=关 1=降噪 2=透传 3=抗风。 */
    private val GA2_GET = intArrayOf(0, 1, 2, 3)

    private val ANC_4 = listOf(
        AncMode.OFF, AncMode.NOISE_CANCELLATION, AncMode.TRANSPARENCY, AncMode.ANTI_WIND,
    )
    private val ANC_5 = listOf(
        AncMode.OFF, AncMode.NOISE_CANCELLATION, AncMode.TRANSPARENCY,
        AncMode.ANTI_WIND, AncMode.ADAPTIVE,
    )
    private val ANC_3 = listOf(
        AncMode.OFF, AncMode.NOISE_CANCELLATION, AncMode.TRANSPARENCY,
    )

    /** 4 档（关/降/透/抗），设备码按 GA2 实测映射，走 AudioCuration。 */
    private fun anc4Ga2(path: AncPathKind = AncPathKind.AUDIO_CURATION) =
        AncProfile(ANC_4, GA2_SET, GA2_GET, path)

    /** 4 档，恒等映射（AC 名义编码 1=关 2=降噪 3=透传 4=抗风），未知型号保守默认。 */
    private fun anc4Identity() =
        AncProfile(ANC_4, intArrayOf(1, 2, 3, 4), null, AncPathKind.AUDIO_CURATION)

    /**
     * 3 档（关/降/透），EDGE 真机确认。
     * SET 侧走 AudioCuration **位掩码** 1=关 / 2=降噪 / 4=通透；
     * GET 侧固件回读的是 **0-based 索引** 0..2，因此必须给出独立的 getMap，
     * 否则 deviceToUi(0) 反查失败会得到 -1（降噪状态显示未知）。
     */
    private fun anc3Ac() = AncProfile(
        ANC_3,
        intArrayOf(1, 2, 4),
        intArrayOf(0, 1, 2),
        AncPathKind.AUDIO_CURATION,
    )

    /**
     * 知更鸟 ROBIN：只有两档（降噪 / 透传）。
     *
     * 设备码：UI[降噪, 透传] → dev [1, 2]；回读 dev 1→0 / 2→1（其余返回 -1 = 未知）。
     * 来源：FxxkMoondrop issue #12 真机日志实测；路径固定走 AudioCuration。
     */
    private fun ancRobin() = AncProfile(
        listOf(AncMode.NOISE_CANCELLATION, AncMode.TRANSPARENCY),
        intArrayOf(1, 2),
        intArrayOf(-1, 0, 1),
        AncPathKind.AUDIO_CURATION,
    )

    /** 布丁 PUDDING 五档：UI[关,降,透,抗,自适应] → dev [0,4,2,3,1]，走 ANC_V2 恒等读回。 */
    private fun ancPudding() = AncProfile(
        ANC_5,
        intArrayOf(0, 4, 2, 3, 1),
        intArrayOf(0, 4, 2, 3, 1),
        AncPathKind.ANC_V2,
    )

    /** ANC_V2 六档恒等映射（官方 AncV2Handler 0..5 直传）。 */
    private fun ancV2Identity() = AncProfile(
        listOf(
            AncMode.OFF, AncMode.NOISE_CANCELLATION, AncMode.TRANSPARENCY,
            AncMode.ANTI_WIND, AncMode.ADAPTIVE, AncMode.LIVE,
        ),
        intArrayOf(0, 1, 2, 3, 4, 5),
        intArrayOf(0, 1, 2, 3, 4, 5),
        AncPathKind.ANC_V2,
    )

    private val GAIN_IDENTITY = DcProfile(hasGain = true, gainMap = intArrayOf(0, 1, 2))
    private val GAIN_REVERSED = DcProfile(hasGain = true, gainMap = intArrayOf(2, 1, 0))
    private val NONE = DcProfile()

    // ── 型号表 ─────────────────────────────────────────────────────────────
    //
    // 顺序无关；匹配时按「别名长度降序」优先，避免 "EDGE" 抢在 "EDGE2" 前面。

    val MODELS: List<MoondropModel> = listOf(

        // ── 已实测 ────────────────────────────────────────────────────────
        MoondropModel(
            id = "edge",
            nameZh = "羽翼 EDGE",
            nameEn = "EDGE",
            aliases = listOf("MOONDROP EDGE", "EDGE"),
            chipset = "Qualcomm QCC（GAIA v3 / vendor 0x001D）",
            transports = listOf(PodTransport.BLE_GAIA),
            verified = true,
            anc = anc3Ac(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(
                promptTone = true, promptVolume = true,
                lhdc = true, dualConnection = true, lowLatency = true,
            ),
            singleDeviceBattery = true,
            note = "moondrop-link-desktop 真机验证：电量只回 type 0（单设备）；" +
                "ANC 走 AUDIO_CURATION 三态位掩码 1/2/4；ONEBRINGTWO(0x14) 可用。",
        ),

        MoondropModel(
            id = "pudding",
            nameZh = "布丁",
            nameEn = "PUDDING",
            aliases = listOf("PUDDING", "MD-TWS-056", "布丁"),
            chipset = "国产 SoC（GAIA v4 over RFCOMM/SPP）",
            transports = listOf(PodTransport.RFCOMM_GAIA),
            verified = true,
            anc = ancPudding(),
            // ⚠ 真机实测修正（用户反馈「增益低和高反了」）：设备码 0=高 / 1=中 / 2=低，
            //   因此 UI 下标[低,中,高] -> 设备码必须是 [2,1,0]。
            //   （FxxkMoondrop 的 PUDDING 档案写的 [0,1,2] 是错的，太空漫游2 实测也是反向。）
            dc = DcProfile(
                hasGain = true, gainMap = intArrayOf(2, 1, 0),
                gainLabels = listOf("低", "中", "高"), hasLed = true,
            ),
            features = FeatureProfile(
                lhdc = true, promptTone = true, promptVolume = true,
                dualConnection = true, lowLatency = true,
            ),
            note = "✅ 2026-09-14 真机抓包确认（84:D9:27:18:6F:2A, FW 3.5.6）：" +
                "走 RFCOMM/SPP over BR/EDR（**不是** BLE GATT），GAIA 帧前有 4 字节 SPP 头 " +
                "`FF 04 00 <n>`；受支持能力 = {0,1,5,13,14,15,16,19,20,22,32}；" +
                "电量类型仅 {1=左,2=右}（无 type 0，充电盒报 0xFF=离线）；" +
                "ANC_V2 cmd3 回 00；增益 feature15 回 00；指示灯 feature19 回 01；" +
                "feature20 双设备连接可用（cmd1 回 00）；feature14 提示音可用；" +
                "feature16 LHDC 可用（`00 1D 20 06 00` 实测关闭后编码由 LHDCv5 变 AAC）。" +
                "兼容 HyperOS Device ID 01010607。",
        ),

        MoondropModel(
            id = "golden_ages_2",
            nameZh = "梦回2",
            nameEn = "GOLDEN AGES 2",
            aliases = listOf("GOLDEN AGES 2", "GOLDENAGES2", "GOLDEN AGES2", "梦回2", "GA2"),
            chipset = "TWS-01 定制 SoC（GAIA）",
            transports = listOf(PodTransport.BLE_GAIA),
            verified = true,
            anc = anc4Ga2(),
            dc = DcProfile(hasGain = true, gainMap = intArrayOf(2, 1, 0), hasSpatial = true, hasHeadTracking = true),
            features = FeatureProfile(lhdc = true),
            note = "FxxkMoondrop 真机双向实测：SET 映射 [1,2,4,3]、GET 为 0-based [0,1,2,3]" +
                "（SET 与 GET 枚举不同，这是 GA2 固件特性）。无充电盒电量、无指示灯。",
        ),

        MoondropModel(
            id = "space_travel_2",
            nameZh = "太空漫游2",
            nameEn = "SPACE TRAVEL 2",
            aliases = listOf("SPACE TRAVEL 2", "SPACETRAVEL2", "太空漫游2", "ST2"),
            chipset = "中科蓝讯 BT8932F（9ECA + GAIA）",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA, PodTransport.RFCOMM_GAIA),
            verified = true,
            anc = anc4Ga2(),
            dc = GAIN_REVERSED,
            features = FeatureProfile(lhdc = false),
            note = "FxxkMoondrop 真机实测：ANC 走 AudioCuration，映射同 GA2；" +
                "增益设备码 0=高/1=中/2=低（与恒等相反，故 gainMap=[2,1,0]）。",
        ),

        // ── 芯片级推断（待实测） ───────────────────────────────────────────
        MoondropModel(
            id = "golden_ages",
            nameZh = "梦回1979 / 梦回",
            nameEn = "GOLDEN AGES",
            aliases = listOf("GOLDEN AGES", "GOLDENAGES", "梦回"),
            chipset = "与梦回2同平台同款主控（GAIA）",
            transports = listOf(PodTransport.BLE_GAIA),
            verified = false,
            anc = anc4Ga2(),
            dc = DcProfile(hasGain = true, gainMap = intArrayOf(2, 1, 0), hasSpatial = true),
            features = FeatureProfile(lhdc = true),
            note = "按与梦回2同平台推断。",
        ),
        MoondropModel(
            id = "moca",
            nameZh = "猫咖",
            nameEn = "MOCA",
            aliases = listOf("MOCA", "猫咖"),
            chipset = "中科蓝讯（dual-mode，需 RFCOMM 兜底）",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.RFCOMM_GAIA),
            verified = false,
            anc = anc4Identity(),
            dc = DcProfile(hasGain = true, gainMap = intArrayOf(0, 1, 2), hasLed = true),
            features = FeatureProfile(),
            note = "无空间音频；三档增益；有指示灯；支持抗风噪。FxxkMoondrop 真机日志确认存在。",
        ),
        MoondropModel(
            id = "nekocake",
            nameZh = "猫饼",
            nameEn = "NEKOCAKE",
            aliases = listOf("NEKOCAKE", "NEKO CAKE", "猫饼"),
            chipset = "中科蓝讯 BT8922E（9ECA）",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA),
            verified = false,
            anc = anc4Identity(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(),
            note = "9ECA BleSourceSwitch 蓝讯主控；增益恒等。",
        ),
        MoondropModel(
            id = "pill",
            nameZh = "音乐胶囊",
            nameEn = "PILL",
            aliases = listOf("MUSIC PILL", "PILL", "音乐胶囊"),
            chipset = "中科蓝讯 BT8932F（9ECA）",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA),
            verified = false,
            anc = anc4Identity(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(),
        ),
        MoondropModel(
            id = "ultrasonic",
            nameZh = "超声波",
            nameEn = "ULTRASONIC",
            aliases = listOf("ULTRASONIC", "超声波"),
            chipset = "中科蓝讯 BT8952F（9ECA）",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA),
            verified = false,
            anc = anc4Identity(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(),
        ),
        MoondropModel(
            id = "robin",
            nameZh = "知更鸟",
            nameEn = "ROBIN",
            aliases = listOf("ROBIN", "知更鸟"),
            chipset = "中科蓝讯 BT8952F（9ECA）",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA),
            verified = false,
            // ⚠ FxxkMoondrop issue #12 真机实测：只有降噪/透传两档（之前 4 档会多出死按钮）。
            anc = ancRobin(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(),
        ),
        MoondropModel(
            id = "ucts",
            nameZh = "U.C.T.S（开放式）",
            nameEn = "U.C.T.S",
            aliases = listOf("U.C.T.S", "U.C.T.S.", "UCTS", "MD-OWS-014"),
            chipset = "高通 QCC（GAIA）",
            transports = listOf(PodTransport.BLE_GAIA),
            verified = true,
            // 硬件本身不含降噪：不给档案（交给能力探测），避免出现点了没反应的降噪按钮。
            anc = null,
            dc = NONE,
            features = FeatureProfile(),
            note = "✅ 实机验证（FxxkMoondrop issue #9）：识别 / 控制 / 左右耳电量 / 弹窗均正常；" +
                "**硬件本身没有降噪**，降噪类功能不可用属预期而非缺陷。",
        ),
        MoondropModel(
            id = "alice",
            nameZh = "爱丽丝",
            nameEn = "ALICE",
            aliases = listOf("ALICE", "爱丽丝"),
            chipset = "Qualcomm QCC5151（GAIA）",
            transports = listOf(PodTransport.BLE_GAIA),
            verified = false,
            anc = anc4Identity(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(lhdc = true),
        ),
        MoondropModel(
            id = "sparks",
            nameZh = "火花",
            nameEn = "SPARKS",
            aliases = listOf("SPARKS", "火花"),
            chipset = "Qualcomm QCC3040（GAIA）",
            transports = listOf(PodTransport.BLE_GAIA),
            verified = false,
            anc = anc4Identity(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(lhdc = true),
        ),
        MoondropModel(
            id = "voyager",
            nameZh = "旅行者",
            nameEn = "VOYAGER",
            aliases = listOf("VOYAGER", "旅行者"),
            chipset = "Qualcomm QCC5144（GAIA，颈挂）",
            transports = listOf(PodTransport.BLE_GAIA),
            verified = false,
            anc = anc4Identity(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(lhdc = true),
        ),
        MoondropModel(
            id = "block",
            nameZh = "方糖",
            nameEn = "BLOCK",
            aliases = listOf("BLOCK", "方糖"),
            chipset = "疑似中科蓝讯 BT8922 系",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA),
            verified = false,
            anc = anc4Identity(),
            dc = NONE,
            features = FeatureProfile(),
        ),
        MoondropModel(
            id = "space_travel",
            nameZh = "太空漫游（一代）",
            nameEn = "SPACE TRAVEL",
            aliases = listOf("SPACE TRAVEL", "SPACETRAVEL", "太空漫游"),
            chipset = "疑似中科蓝讯（型号未确认）",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA),
            verified = false,
            anc = anc4Identity(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(),
        ),
        MoondropModel(
            id = "edge2",
            nameZh = "羽翼 EDGE2",
            nameEn = "EDGE2",
            aliases = listOf("MOONDROP EDGE2", "EDGE2", "EDGE 2"),
            chipset = "国产 SoC（型号未公开）",
            transports = listOf(PodTransport.BLE_GAIA),
            verified = false,
            anc = anc3Ac(),
            dc = GAIN_IDENTITY,
            features = FeatureProfile(lhdc = true, dualConnection = true, lowLatency = true),
        ),
        MoondropModel(
            id = "space_travel_2_ultra",
            nameZh = "太空漫游2 ULTRA",
            nameEn = "SPACE TRAVEL 2 ULTRA",
            aliases = listOf("SPACE TRAVEL 2 ULTRA", "太空漫游2 ULTRA", "ST2 ULTRA"),
            chipset = "国产 SoC（型号未公开）",
            transports = listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA),
            verified = false,
            anc = anc4Ga2(),
            dc = GAIN_REVERSED,
            features = FeatureProfile(),
        ),
    )

    /**
     * 未命中档案时的兜底：**不假设任何型号能力**，全部交给连接后的能力探测裁决。
     * ANC 用 AC 名义编码（可探测），增益/指示灯/新功能一律不展示。
     */
    val FALLBACK: MoondropModel = MoondropModel(
        id = "fallback",
        nameZh = "未知水月雨设备",
        nameEn = "Unknown Moondrop",
        aliases = emptyList(),
        chipset = "未知",
        transports = listOf(
            PodTransport.BLE_GAIA, PodTransport.RFCOMM_GAIA, PodTransport.BLE_SRC_9ECA,
        ),
        verified = false,
        anc = anc4Identity(),
        dc = NONE,
        features = FeatureProfile(),
        note = "能力由连接后的 GET_SUPPORTED_FEATURES 位图与真实回包决定；" +
            "增益/指示灯等未探测到就不展示。",
    )

    /** 按别名长度降序，保证 "SPACE TRAVEL 2" 优先于 "SPACE TRAVEL"。 */
    private val BY_ALIAS: List<Pair<String, MoondropModel>> = MODELS
        .flatMap { m -> m.aliases.map { it.uppercase() to m } }
        .sortedByDescending { it.first.length }

    fun byId(id: String): MoondropModel? = MODELS.firstOrNull { it.id == id }

    /**
     * 按蓝牙设备名匹配型号。
     * 命中顺序：别名长度降序的 contains 匹配。
     * 未命中但名字里含 MOONDROP / 水月雨 时返回 [FALLBACK]（仍是水月雨设备）；
     * 否则返回 null（不是水月雨设备，本应用不接管）。
     */
    fun match(deviceName: String?): MoondropModel? {
        val n = deviceName?.uppercase()?.trim()
        if (n.isNullOrEmpty()) return null
        for ((alias, model) in BY_ALIAS) {
            if (n.contains(alias)) return model
        }
        if (n.contains("MOONDROP") || n.contains("水月雨")) return FALLBACK
        return null
    }

    /** 是否为本应用接管的设备。 */
    fun isMoondrop(deviceName: String?): Boolean = match(deviceName) != null
}
