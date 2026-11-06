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
 *   3. **不维护任何机型清单**（2026-11 决定）：识别完全交给官方云端目录
 *      （MoondropCatalog，与官方 APP 同源），功能完全交给连接后的能力探测，未命中时
 *      用 FALLBACK 的多链路 + 保守默认。实测校准数据暂时不启用 —— 将来若某型号的
 *      设备码语义确实需要覆盖，再按 verified = true + 实测来源的形式单独加回。
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




        // ── 芯片级推断（待实测） ───────────────────────────────────────────
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
        // 官方云端目录兜底（与官方 APP 同源）：官方命名/拼写变体（UTCS、SP2…）与新机型
        // 都能认出来。目录没拉到或没命中时返回 null，行为与以前完全一致。
        MoondropCatalog.resolve(deviceName)?.let { return catalogModel(it) }
        if (n.contains("MOONDROP") || n.contains("水月雨")) return FALLBACK
        return null
    }

    /**
     * 官方云端芯片族 → 传输链路。（appResourceRoot 就是官方标注的芯片族）
     *
     *  - `/jieli`（杰理，如布丁）：GAIA V4 over **RFCOMM/SPP**，BLE 上直接回 status=147；
     *  - `/qualcomm`（QCC）：GAIA V3 over BLE GATT；
     *  - `/bluetrum`（中科蓝讯）等：BLE GAIA + 私有 9ECA；
     *  - 未知：沿用 FALLBACK 的多链路顺序。
     */
    private fun transportsForChip(chipRoot: String?): List<PodTransport> = when {
        chipRoot == null -> FALLBACK.transports
        chipRoot.startsWith("/jieli") ->
            listOf(PodTransport.RFCOMM_GAIA, PodTransport.BLE_GAIA)
        chipRoot.startsWith("/qualcomm") ->
            listOf(PodTransport.BLE_GAIA, PodTransport.RFCOMM_GAIA)
        chipRoot.startsWith("/bluetrum") || chipRoot.startsWith("/spv") ||
            chipRoot.startsWith("/comture") || chipRoot.startsWith("/synopsys") ||
            chipRoot.startsWith("/airoha") || chipRoot.startsWith("/jiu") ->
            listOf(PodTransport.BLE_GAIA, PodTransport.BLE_SRC_9ECA, PodTransport.RFCOMM_GAIA)
        else -> FALLBACK.transports
    }

    /** 云端目录命中时构造的「官方认识、本地无档案」的型号：功能全靠能力探测。 */
    private val CATALOG_CACHE = HashMap<String, MoondropModel>()

    private fun catalogModel(entry: MoondropCatalog.Entry): MoondropModel =
        CATALOG_CACHE.getOrPut(entry.key) {
            MoondropModel(
                id = "catalog_" + entry.key.lowercase().replace(' ', '_'),
                nameZh = entry.name,
                nameEn = entry.name,
                aliases = listOf(entry.key),
                chipset = "官方云端目录（能力探测）",
                transports = transportsForChip(entry.chipRoot),
                verified = false,
                anc = null,
                dc = NONE,
                features = FeatureProfile(),
                note = "官方云端机型目录命中（products/all / ota-configs/all）；" +
                    "功能一律按连接后的能力探测决定，不做型号级猜测。",
            )
        }

    /** 是否为本应用接管的设备。 */
    fun isMoondrop(deviceName: String?): Boolean = match(deviceName) != null
}
