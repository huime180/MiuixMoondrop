package moe.huime.miuixmoondrop.core

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 官方云端机型目录 —— 与官方 APP **同源**（它自己就是这么识别的）。
 *
 * 官方 APP 的“全机型适配”= 设备自报能力（GAIA feature 位图）+ 云端按型号下发的目录/配置。
 * 我们照抄前一半（能力探测，见 [MoondropModelRegistry] 的能力优先原则），这里补后一半：
 *
 *  - `products/all`      官方产品目录（含 `type = BT` 的蓝牙机型，官方命名 + 产品图）
 *  - `ota-configs/all`   官方按型号/区域下发的配置名（197 条，含 `UTCS`、`SP2`、
 *                        `Komet MOCA` 这类**官方拼写变体**）
 *
 * 作用只有一个：**把设备名映射到官方的规范机型名**（显示用），以及在本地档案未命中时
 * 提供一个“官方认识这台”的兜底型号。**功能一律仍由连接后的能力探测决定**，
 * 目录里没有的东西不会凭空变成可用功能。
 *
 * 失败策略：任何网络/解析失败都静默退回空目录（调用方继续用本地档案 + 能力探测），
 * 绝不因为拉不到目录影响连接和已有适配。
 */
object MoondropCatalog {
    private const val TAG = "MoondropCatalog"
    private const val PRODUCTS_URL = "https://cdn-service.moondroplab.tech/api/v1/products/all"
    private const val OTA_CONFIGS_URL = "https://cdn-service.moondroplab.tech/api/v1/ota-configs/all"
    private const val TIMEOUT_MS = 15_000

    /** 版本号（1.2.3 / 1.2.3.4 / 1.2）：前后都不限边界，字母紧邻也要剥掉（UTCS1.4.0）。 */
    private val VERSION_RE = Regex("""\d+(?:\.\d+){1,3}(?!\d)""")

    /** 一条官方目录项：官方规范名 + 归一化匹配键（大写、去版本/区域后缀）。 */
    data class Entry(val name: String, val key: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var lastRefreshMs = 0L

    /** 目录是否已加载（含缓存）。 */
    val loaded: Boolean get() = entries.isNotEmpty()

    /**
     * 拉取官方目录（24 小时内不重复拉）。
     *
     * 只应在后台线程调用；失败静默（保留上一次成功的结果）。
     */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && loaded && now - lastRefreshMs < 24 * 3600_000L) return
        runCatching { fetchAll() }
            .onSuccess { list ->
                if (list.isNotEmpty()) {
                    entries = list
                    lastRefreshMs = now
                    Log.i(TAG, "catalog loaded: ${list.size} entries")
                }
            }
            .onFailure { Log.w(TAG, "catalog refresh failed", it) }
    }

    /**
     * 把设备名解析成官方规范名；未命中返回 null。
     *
     * 匹配规则：两边都归一化后做 contains（取最长的命中键，避免 "EDGE" 抢 "EDGE2"）。
     */
    fun resolve(deviceName: String?): Entry? {
        val dev = normalize(deviceName ?: return null)
        if (dev.isEmpty() || entries.isEmpty()) return null
        return entries
            .filter { dev.contains(it.key) }
            .maxByOrNull { it.key.length }
    }

    // ---- 内部实现

    private fun fetchAll(): List<Entry> {
        val out = LinkedHashMap<String, Entry>()

        // 1) 产品目录：只取蓝牙机型（官方 type = BT），名称即官方机型名
        val products = runCatching {
            json.parseToJsonElement(httpGet(PRODUCTS_URL)?.decodeToString() ?: "{}")
                .jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
        }.getOrDefault(JsonArray(emptyList()))
        for (el in products) {
            val o = el as? JsonObject ?: continue
            val type = o["type"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            if (!type.equals("BT", ignoreCase = true)) continue
            addEntry(out, o.string("name") ?: o.string("productName"))
        }

        // 2) OTA 配置目录：名称里带版本/区域后缀，归一化后即官方机型名（含拼写变体）
        val configs = runCatching {
            json.parseToJsonElement(httpGet(OTA_CONFIGS_URL)?.decodeToString() ?: "{}")
                .jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
        }.getOrDefault(JsonArray(emptyList()))
        for (el in configs) {
            val o = el as? JsonObject ?: continue
            addEntry(out, o.string("name"))
        }
        return out.values.toList()
    }

    private val DIRTY_RE = Regex("""\b(?:JP|EN|CN|KO|KR|JA|SPECIAL|INIT|TEST\d?)\b""", RegexOption.IGNORE_CASE)

    /**
     * 同一匹配键只留一条，择优规则：
     *   1. 「干净」名字优先（不带版本号 / 区域词）—— 产品目录因此天然优先于 OTA 配置名；
     *   2. 都干净或都脏时，取更短的（去掉冗余后缀）。
     * 显示名统一去掉版本号（"Robin 1.3.0" → "Robin"、"U.C.T.S 1.3.0" → "U.C.T.S"）。
     */
    private fun addEntry(out: MutableMap<String, Entry>, raw: String?) {
        val name = raw?.trim().orEmpty()
        if (name.length < 2) return
        val key = normalize(name)
        if (key.length < 2) return
        val existing = out[key]
        if (existing == null || betterName(name, existing.name)) {
            val display = name.replace(VERSION_RE, " ").replace(Regex("""\s+"""), " ").trim()
            out[key] = Entry(display.ifEmpty { name }, key)
        }
    }

    private fun isDirty(name: String): Boolean =
        VERSION_RE.containsMatchIn(name) || DIRTY_RE.containsMatchIn(name)

    private fun betterName(a: String, b: String): Boolean {
        val da = isDirty(a)
        val db = isDirty(b)
        if (da != db) return !da
        return a.length < b.length
    }

    private fun JsonObject.string(k: String): String? =
        runCatching { this[k]?.jsonPrimitive?.content }.getOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    /**
     * 归一化：大写、去版本号、去区域/后缀词、去标点，压缩空白。
     *
     * 例：
     *  - `ROBIN JP 1.3.5 special` → `ROBIN`
     *  - `UTCS1.4.0`              → `UTCS`
     *  - `Moondrop U.C.T.S.`      → `MOONDROP UCTS`
     *  - `EDGE 2  1.2.0`          → `EDGE 2`
     */
    private fun normalize(raw: String): String {
        var s = raw.uppercase()
        // 版本号（1.2.3 / 1.2.3.4 / 1.2）整段去掉。
        // 不能用 \b 前缀：UTCS1.4.0 里 S 与 1 都是词字符、没有边界，会漏掉「1.4.0」；
        // 也不能用字母 lookbehind（同样挡掉紧邻字母的版本号）。只在后面限一个数字。
        s = s.replace(VERSION_RE, " ")
        // 品牌前缀不影响匹配（设备名里可能带、目录里可能不带）
        for brand in listOf("MOONDROP", "PANDAER", "MOONDROP X"):
            s = s.replace(brand, " ")
        // 单独的构建号（如 "160"）以及区域/状态词
        for (w in listOf(
            "JP", "EN", "CN", "KO", "KR", "JA", "ENGLISH", "JAPANESE", "KOREAN",
            "SPECIAL", "INIT", "TEST", "TEST2", "TO USER", "ORIGINAL VERSION", "ORIGINAL",
        )) {
            s = s.replace(Regex("""\b$w\b"""), " ")
        }
        // 标点 → 空格
        s = s.replace(Regex("""[^A-Z0-9\u4e00-\u9fff]+"""), " ")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun httpGet(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                null
            } else {
                connection.inputStream.use { it.readBytes() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GET failed: $url", t)
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
