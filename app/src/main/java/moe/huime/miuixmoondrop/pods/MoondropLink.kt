/*
 * MiuixMoondrop — 协议客户端
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 与水月雨耳机建立一条控制通道并维护状态。两条传输：
 *   · GAIA v3 over BLE GATT  —— 大多数机型（服务 00001100-d102-…）
 *   · GAIA v4 over RFCOMM/SPP —— 布丁 PUDDING 等（UUID 00001101-0000-1000-8000-00805f9b34fb）
 *
 * 设计要点（吸取参考实现的经验）：
 *   1. **请求/响应关联**：GAIA 没有序列号，按 (feature, command) 建 pending 表，
 *      带超时。参考项目（OppoPods）没有这层，只靠 delay 排序，容易出现状态错乱。
 *   2. **单写者 Mutex**：所有写操作串行化，避免并发交叠。
 *   3. **能力优先**：连接后先探测 GET_SUPPORTED_FEATURES 位图，用真实能力决定 UI 展示，
 *      型号档案只提供差异点（映射表等）。
 *   4. **电量修复**：先 cmd0 问出设备支持的电池类型，再按该集合查询；解析按 type 归位；
 *      type 0（单设备）同时充填左右耳。见 BatteryCodec 的根因说明。
 */
package moe.huime.miuixmoondrop.pods

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.huime.miuixmoondrop.core.AncMode
import moe.huime.miuixmoondrop.core.AncPathKind
import moe.huime.miuixmoondrop.core.BATTERY_UNKNOWN
import moe.huime.miuixmoondrop.core.BatteryCodec
import moe.huime.miuixmoondrop.core.BatteryState
import moe.huime.miuixmoondrop.core.Gaia
import moe.huime.miuixmoondrop.core.GaiaFramer
import moe.huime.miuixmoondrop.core.MoondropModel
import moe.huime.miuixmoondrop.core.MoondropModels
import moe.huime.miuixmoondrop.core.MoondropCatalog
import moe.huime.miuixmoondrop.core.PodTransport
import moe.huime.miuixmoondrop.core.SrcProtocol
import java.util.UUID

private const val TAG = "MoondropLink"

interface PodListener {
    fun onEvent(event: PodEvent)
}

object MoondropLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    @Volatile private var appContext: Context? = null
    /** 允许多个监听者：应用 UI 与「跨进程状态转发器」同时消费事件 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<PodListener>()

    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var model: MoondropModel = MoondropModels.FALLBACK
    @Volatile private var useRfcomm = false
    /**
     * RFCOMM 上是否给 GAIA 帧套官方传输头（`FF 04 00 <len>`）。
     * 官方 App 实测是套的，FxxkMoondrop 发裸 PDU 也能工作，故连接时探测并记忆。
     */
    @Volatile private var rfcommUsesHeader = true
    @Volatile private var rfcommFramingProbed = false

    // BLE
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null

    // SPP
    private var socket: BluetoothSocket? = null
    /** 本次 RFCOMM 建链成功的时刻（elapsedRealtime）；只用于「会话太短」的判定。 */
    @Volatile private var sessionStartedAt = 0L
    private var readerJob: Job? = null
    private var pollJob: Job? = null

    private val framer = GaiaFramer()
    private val batteryState = BatteryState()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 状态 ────────────────────────────────────────────────────────────────
    @Volatile private var connected = false
    @Volatile private var ancIndex = -1
    @Volatile private var ancModes: List<AncMode> = emptyList()
    @Volatile private var gainIndex = -1
    @Volatile private var ledOn: Boolean? = null
    @Volatile private var promptToneOn: Boolean? = null
    /** 提示音音量：**0..100 百分比**（官方 App 日志实测单位就是百分比） */
    @Volatile private var promptVolumeRaw = -1
    /** 提示音索引（语言/主题），随同一份配置读写 */
    @Volatile private var promptIndex = 0
    @Volatile private var lhdcOn: Boolean? = null
    /**
     * LHDC **最后一次被设备读回确认**的值 —— 乐观写入（见 [setLhdc]）失败时唯一的回退目标。
     * 只由真实回包（回读 / 主动通知，见 [applyLhdc]）更新，不被乐观值污染。
     */
    @Volatile private var lhdcConfirmed: Boolean? = null
    @Volatile private var dualConnectionOn: Boolean? = null
    /**
     * 空间音频（feature 18）开关；null = 还没读到。
     *
     * ⚠ 协议语义核实于 PROTOCOL.md 第 8 节：GET/SET = 1/2、payload `0/1`（与指示灯同一口径，
     * 见 [Gaia.spatialSet] 的写法）；读/写/回读都已接线，但**本应用从未在支持空间音频的真机上
     * 验证过**（PROTOCOL.md 把这一节标为「已接线、未真机验证」）。所以 UI 只按读回值展示，
     * 读不到就不猜。
     */
    @Volatile private var spatialEnabled: Boolean? = null
    /** 头部追踪（同一 feature 18 的 cmd 3/4：GET/SET）；null = 还没读到。同样待真机确认。 */
    @Volatile private var headTrackingOn: Boolean? = null
    @Volatile private var lowLatencyOn: Boolean? = null
    /**
     * 手势配置（TOUCHV2）的 5 个字节（顺序见 [Gaia.GestureSlot]）；null = 还没读到过。
     *
     * ⚠ 每个字节**打包双耳**：高 4 位 = 左耳动作 id，低 4 位 = 右耳动作 id
     * （字节码 `TouchNewInfo` 的 per-gesture L/R 字段，见 Gaia 的 TOUCHV2 段落）。
     * 内部存 IntArray（与线格式字节一一对应），对外只经 [snapshot] 暴露不可变的
     * [Gaia.GestureConf]。是否支持由能力位图判定（见 [probeCapabilities] 的 hasGestures）。
     */
    @Volatile private var gestureConf: IntArray? = null
    @Volatile private var capabilities = PodCapabilities()

    // 每个 feature 只保留一个等待者，避免并发请求互相覆盖（GAIA 无序列号）
    private val responses = HashMap<Int, java.util.concurrent.CompletableFuture<ByteArray>>()

    /** 版本探测响应的伪 feature key（真实 feature 非负，不会冲突） */
    private const val PROBE_FEATURE_KEY = -1
    private const val PREF_RFCOMM_FRAMING = "rfcomm_framing"

    private val ANC_TIMEOUT_MS = 1500L
    private val CMD_TIMEOUT_MS = 2000L

    /**
     * 会话健康线（ms）：RFCOMM 建链后**活过**这么久就算这次建链是成功的。
     * 反过来说，短于它就被对端关掉 = 这次建链没成功（耳机侧 SPP 服务还没起来 /
     * 蓝牙栈刚重启），值得重试。
     *
     * 真机依据（布丁，2026-09-15，关开蓝牙触发的重连）：
     *   00:10:42.785 `GAIA SPP ready` → 00:10:42.798 `SPP reader stopped: bt socket closed`
     *   —— 只有 13 ms。此后能力探测/电量读取全部超时（features=[]、batteryTypes=[]），
     *   应用停在「已连接但读不到任何数据」的死状态：状态通知被撤、连接弹窗也永远不会弹，
     *   直到用户自己再打开一次应用（走冷启动兜底）或再来一条 A2DP 广播。
     */
    private const val SESSION_MIN_HEALTHY_MS = 1500L

    /**
     * 「刚建链就被关掉」的**有界**重连次数与间隔。
     * 有界是关键：绝不无限重连（对端真不在时就老实退回未连接，等下一次 A2DP 广播或用户打开应用）。
     */
    private const val SESSION_RETRY_LIMIT = 3
    private const val SESSION_RETRY_DELAY_MS = 1500L

    /**
     * LHDC 写后确认：首次回读超时后再重试的次数与间隔。
     * **有界** —— 只有全部失败才回退到 [lhdcConfirmed]，绝不无限重试。
     */
    private const val LHDC_CONFIRM_RETRIES = 2
    private const val LHDC_CONFIRM_RETRY_DELAY_MS = 700L

    fun init(context: Context, listener: PodListener) {
        appContext = context.applicationContext
        addListener(listener)
    }

    /** 注册监听者（幂等：同一个实例重复注册只会存在一份） */
    fun addListener(l: PodListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: PodListener) {
        listeners.remove(l)
    }

    fun isInitialized(): Boolean = appContext != null

    fun sniffModel(deviceName: String?): MoondropModel? = MoondropModels.match(deviceName)

    val currentModel: MoondropModel get() = model

    fun snapshot(): PodSnapshot = PodSnapshot(
        deviceName = device?.name ?: "",
        deviceAddress = device?.address ?: "",
        modelId = model.id,
        modelName = model.nameZh,
        modelVerified = model.verified,
        connected = connected,
        transport = if (useRfcomm) "GAIA v4 / RFCOMM" else "GAIA v3 / BLE",
        battery = BatterySnapshot(
            left = batteryState.currentLeft.level,
            right = batteryState.currentRight.level,
            case = batteryState.currentCase.level,
            leftCharging = batteryState.currentLeft.charging,
            rightCharging = batteryState.currentRight.charging,
            caseCharging = batteryState.currentCase.charging,
            singleDevice = batteryState.usesSingleDeviceBattery(),
        ),
        ancIndex = ancIndex,
        ancModes = ancModes,
        gainIndex = gainIndex,
        gainLabels = model.dc.gainLabels,
        ledOn = ledOn,
        promptToneOn = promptToneOn,
        promptVolumeRaw = promptVolumeRaw,
        promptVolumeMax = model.features.promptVolumeMax,
        promptIndex = promptIndex,
        lhdcOn = lhdcOn,
        dualConnectionOn = dualConnectionOn,
        spatialEnabled = spatialEnabled,
        headTrackingOn = headTrackingOn,
        lowLatencyOn = lowLatencyOn,
        // 手势：拷贝一份，避免把内部可变数组暴露给监听者
        gestureConf = gestureConf?.let { Gaia.GestureConf(it.copyOf()) },
        capabilities = capabilities,
    )

    private fun emit(e: PodEvent) {
        if (listeners.isEmpty()) return
        mainHandler.post { for (l in listeners) runCatching { l.onEvent(e) } }
    }

    private fun emitState() {
        if (listeners.isEmpty()) return
        mainHandler.post {
            val snap = snapshot()
            for (l in listeners) runCatching { l.onEvent(PodEvent.Connected(snap)) }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 连接
    // ══════════════════════════════════════════════════════════════════════

    fun connect(btDevice: BluetoothDevice, preferRfcomm: Boolean = false) =
        connectInternal(btDevice, preferRfcomm, retry = 0)

    /**
     * 连接本体。
     *
     * @param retry 会话级重试序号（只有 [startReader] 在「刚建链就被对端关掉」时会递增）。
     *              作为参数而不是成员变量：重试是这次连接的一个属性，不需要在别处复位，
     *              也就不会出现「上一次连接留下计数、下一次连接少重试几次」的串味。
     */
    private fun connectInternal(btDevice: BluetoothDevice, preferRfcomm: Boolean, retry: Int) {
        if (connected && device?.address == btDevice.address) return
        disconnectInternal(notify = false)
        device = btDevice
        // 先给一个安全默认值，真正的型号/线路在云端目录就绪后再定（见下面的 coroutine）
        model = MoondropModels.match(btDevice.name) ?: MoondropModels.FALLBACK
        framer.reset()
        batteryState.reset()
        capabilities = PodCapabilities()
        scope.launch {
            // ⚠ 必须先拿官方云端配置（芯片族）再决定线路，否则第一次连接会退化成
            //   FALLBACK 的 BLE 优先（布丁 → GATT status=147 → 只能靠回落）。
            MoondropCatalog.ensureLoaded()
            model = MoondropModels.match(btDevice.name) ?: MoondropModels.FALLBACK
            useRfcomm = preferRfcomm || model.transports.firstOrNull() == PodTransport.RFCOMM_GAIA
            Log.i(
                TAG,
                "connect ${btDevice.address}: model=${model.nameZh} " +
                    "transports=${model.transports} useRfcomm=$useRfcomm",
            )
            if (useRfcomm) connectRfcomm(btDevice, retry) else connectGattInternal(btDevice)
        }
    }

    fun disconnect() = disconnectInternal(notify = true)

    private fun disconnectInternal(notify: Boolean) {
        connected = false
        pollJob?.cancel(); pollJob = null
        readerJob?.cancel(); readerJob = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Throwable) {}
        gatt = null; cmdChar = null
        device = null
        responses.clear()
        // 乐观更新的回退基线同样只在本会话内有效
        lhdcConfirmed = null
        if (notify) emit(PodEvent.Disconnected)
    }

    // ── BLE GATT ───────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected status=$status")
                val gone = device ?: g.device
                if (connected) disconnectInternal(notify = true)
                maybeFallbackToRfcomm(status, gone)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "discover failed $status"); return }
            val svc = g.getService(UUID.fromString(Gaia.GATT_SERVICE)) ?: run {
                Log.w(TAG, "GAIA service not found; falling back to RFCOMM")
                // 双模设备：BLE 上没有 GAIA 服务时回退 SPP
                val d = device
                if (d != null) { useRfcomm = true; scope.launch { connectRfcomm(d) } }
                return
            }
            cmdChar = svc.getCharacteristic(UUID.fromString(Gaia.GATT_COMMAND))
            enableNotify(g, svc.getCharacteristic(UUID.fromString(Gaia.GATT_RESPONSE)))
            enableNotify(g, svc.getCharacteristic(UUID.fromString(Gaia.GATT_DATA)))
            if (cmdChar == null) { Log.w(TAG, "command characteristic missing"); return }
            connected = true
            Log.i(TAG, "GAIA GATT ready: ${device?.name} model=${model.nameZh}")
            scope.launch { afterConnected() }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onBytes(value)
        }

        @Deprecated("kept for API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { onBytes(it) }
        }
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic?) {
        ch ?: return
        try {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(UUID.fromString(Gaia.CCCD)) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enableNotify failed", t)
        }
    }

    private fun connectGattInternal(btDevice: BluetoothDevice) {
        val ctx = appContext ?: return
        try {
            gatt = if (Build.VERSION.SDK_INT >= 26) {
                btDevice.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                btDevice.connectGatt(ctx, false, gattCallback)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "connectGatt failed", t)
        }
    }

    // ── RFCOMM / SPP ───────────────────────────────────────────────────────

    /** 每个会话只尝试一次 RFCOMM 回落（避免失败机型来回重连）。 */
    private var rfcommFallbackTried = false

    /**
     * GATT **连接失败**时也回落到 RFCOMM/SPP（未收录型号走 FALLBACK，BLE 优先，
     * 而像布丁这种设备在 BLE 上直接回 status=147；原实现只在服务发现阶段回落）。
     */
    private fun maybeFallbackToRfcomm(status: Int, target: BluetoothDevice?) {
        if (rfcommFallbackTried || useRfcomm || target == null) return
        if (model.transports.none { it == PodTransport.RFCOMM_GAIA }) return
        rfcommFallbackTried = true
        useRfcomm = true
        Log.i(TAG, "GATT 失败(status=$status)，回落到 RFCOMM/SPP: ${target.address}")
        scope.launch { connectRfcomm(target) }
    }

    private fun connectRfcomm(btDevice: BluetoothDevice, retry: Int = 0) {
        try {
            val uuid = UUID.fromString(Gaia.SPP_UUID)
            val s = try {
                btDevice.createRfcommSocketToServiceRecord(uuid)
            } catch (t: Throwable) {
                Log.w(TAG, "createRfcommSocketToServiceRecord failed, trying channel 1", t)
                @Suppress("DiscouragedPrivateApi")
                btDevice.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(btDevice, 1) as BluetoothSocket
            }
            s.connect()
            socket = s
            connected = true
            // 记下建链时刻：startReader 用它判断这次会话是不是「刚连上就被对端关掉」
            sessionStartedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "GAIA SPP ready: ${btDevice.name} model=${model.nameZh}")
            startReader(s, retry)
            scope.launch { afterConnected() }
        } catch (t: Throwable) {
            Log.e(TAG, "RFCOMM connect failed", t)
            emit(PodEvent.Frame("ERR", "", "RFCOMM 连接失败: ${t.message}"))
        }
    }

    private fun startReader(s: BluetoothSocket, retry: Int = 0) {
        readerJob = scope.launch {
            val buf = ByteArray(1024)
            try {
                val input = s.inputStream
                while (connected && s.isConnected) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val burstEnd = try { input.available() == 0 } catch (_: Throwable) { true }
                    val chunk = buf.copyOf(n)
                    for (pdu in framer.feed(chunk, burstEnd)) onPdu(pdu)
                }
            } catch (t: Throwable) {
                if (connected) Log.w(TAG, "SPP reader stopped: ${t.message}")
            }
            if (connected) {
                // 先量这次会话活了多久：够久 = 健康会话（对端正常关机/断开），
                // 太短 = 建链其实没成功（见 SESSION_MIN_HEALTHY_MS 的真机依据）。
                val sessionMs = SystemClock.elapsedRealtime() - sessionStartedAt
                val target = device
                val healthy = sessionMs >= SESSION_MIN_HEALTHY_MS
                disconnectInternal(notify = true)
                // 有界重连：**只**重试「刚建链就被关掉」这一种，且最多 SESSION_RETRY_LIMIT 次。
                // 用户真正把耳机收进盒子/关掉时，会话早活过健康线，这里不会白白重连。
                if (!healthy && target != null && retry < SESSION_RETRY_LIMIT) {
                    Log.i(
                        TAG,
                        "session died after ${sessionMs}ms; retry ${retry + 1}/$SESSION_RETRY_LIMIT",
                    )
                    scope.launch {
                        delay(SESSION_RETRY_DELAY_MS)
                        // 这次会话走的就是 RFCOMM，重试沿用同一条传输（不回头再试 BLE）
                        connectInternal(target, preferRfcomm = true, retry = retry + 1)
                    }
                } else if (!healthy && target != null) {
                    Log.w(TAG, "session keeps dying early; retries exhausted, staying disconnected")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 写 / 收
    // ══════════════════════════════════════════════════════════════════════

    private fun write(pkt: ByteArray) {
        scope.launch {
            writeLock.withLock {
                try {
                    if (useRfcomm) {
                        val onWire = if (rfcommUsesHeader) Gaia.wrapRfcomm(pkt) else pkt
                        socket?.outputStream?.apply { write(onWire); flush() }
                    } else {
                        val g = gatt ?: return@withLock
                        val c = cmdChar ?: return@withLock
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeCharacteristic(c, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            c.value = pkt
                            @Suppress("DEPRECATION")
                            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(c)
                        }
                    }
                    emit(PodEvent.Frame("TX", Gaia.hex(pkt), decode(pkt)))
                } catch (t: Throwable) {
                    Log.w(TAG, "write failed", t)
                }
            }
        }
    }

    /**
     * 发送并等待匹配的响应；超时返回 null。
     *
     * 注意：这里必须用 `CompletableFuture.get(timeout)`（阻塞带超时），
     * 而不是 `withTimeoutOrNull { fut.get() }` —— 后者无法中断阻塞中的 get()。
     */
    private suspend fun request(pkt: ByteArray, timeoutMs: Long = CMD_TIMEOUT_MS): ByteArray? {
        // ⚠ 版本探测包走 vendor 0x000A，Gaia.parse 只认 0x001D 会返回 null，
        //   所以必须先判探测包，再走常规解析，否则探测请求根本发不出去。
        val isVersionProbe = pkt.size >= 2 &&
            (pkt[0].toInt() and 0xFF) == 0x00 && (pkt[1].toInt() and 0xFF) == 0x0A
        val key = if (isVersionProbe) PROBE_FEATURE_KEY else (Gaia.parse(pkt)?.feature ?: return null)
        val fut = java.util.concurrent.CompletableFuture<ByteArray>()
        responses[key] = fut
        write(pkt)
        return try {
            withContext(Dispatchers.IO) { fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        } catch (_: Throwable) {
            null
        } finally {
            responses.remove(key)
        }
    }

    private fun onBytes(value: ByteArray) {
        for (pdu in framer.feed(value, true)) onPdu(pdu)
    }

    private fun onPdu(pdu: ByteArray) {
        // GAIA 版本探测用的是 V1/V2 包（vendor 0x000A），Gaia.parse 只认 0x001D，
        // 因此这里单独识别，用于 RFCOMM 封装的探测。
        if (pdu.size >= 4) {
            val vendor = ((pdu[0].toInt() and 0xFF) shl 8) or (pdu[1].toInt() and 0xFF)
            if (vendor == Gaia.VENDOR_CSR) {
                emit(PodEvent.Frame("RX", Gaia.hex(pdu), "GAIA version response (vendor 0x000A)"))
                responses.remove(PROBE_FEATURE_KEY)?.complete(pdu)
                return
            }
        }
        val f = Gaia.parse(pdu) ?: return
        emit(PodEvent.Frame("RX", Gaia.hex(pdu), "feature=${f.feature} type=${f.type} cmd=${f.command}"))
        // 唤醒等待者
        responses.remove(f.feature)?.complete(f.payload)
        dispatch(f)
    }

    private fun decode(pkt: ByteArray): String {
        val f = Gaia.parse(pkt) ?: return ""
        return "feature=${f.feature} cmd=${f.command} payload=${Gaia.hex(f.payload)}"
    }

    // ══════════════════════════════════════════════════════════════════════
    // 连接后的初始化：探测能力 → 读取全量状态 → 起轮询
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun afterConnected() {
        emitState()
        // 先把 RFCOMM 封装和 GAIA 版本一起探测掉（版本探测帧同时用作封装探针）。
        runCatching { probeRfcommFraming() }
        probeCapabilities()
        refreshAll()
        startPolling()
        emitState()
    }

    /**
     * 探测 RFCOMM 上是否需要官方传输头（`FF 04 00 <len>`）。
     *
     * 官方 App 实测是套头的；另有实现对布丁直接发裸 PDU 也能工作。两者都试一遍，
     * 结果写入 prefs，后续连接直接复用，避免每次连接都多花一次超时。
     * 探测用的 `00 0A 03 00` 同时就是 GAIA 版本探测帧（设备 GAIA v3 才走 vendor 0x001D）。
     */
    private suspend fun probeRfcommFraming() {
        val ctx = appContext
        val pref = ctx?.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val saved = pref?.getInt(PREF_RFCOMM_FRAMING, -1) ?: -1
        if (!useRfcomm) {
            // BLE：无封装问题，仅发版本探测
            runCatching { request(Gaia.getApiVersion(), ANC_TIMEOUT_MS) }
            return
        }
        if (saved == 0 || saved == 1) {
            rfcommUsesHeader = saved == 1
            rfcommFramingProbed = true
            request(Gaia.getApiVersion(), ANC_TIMEOUT_MS)
            Log.i(TAG, "RFCOMM framing (cached): header=$rfcommUsesHeader")
            return
        }
        rfcommUsesHeader = true
        if (request(Gaia.getApiVersion(), 1200) != null) {
            rfcommFramingProbed = true
            pref?.edit()?.putInt(PREF_RFCOMM_FRAMING, 1)?.apply()
            Log.i(TAG, "RFCOMM framing probed: WITH FF 04 00 header")
            return
        }
        rfcommUsesHeader = false
        if (request(Gaia.getApiVersion(), 1200) != null) {
            rfcommFramingProbed = true
            pref?.edit()?.putInt(PREF_RFCOMM_FRAMING, 0)?.apply()
            Log.i(TAG, "RFCOMM framing probed: BARE PDU")
        } else {
            // 都没回应：保持官方形态，后续靠正常请求自愈
            rfcommUsesHeader = true
            Log.w(TAG, "RFCOMM framing probe inconclusive; keeping FF header")
        }
    }

    private suspend fun probeCapabilities() {
        val feats = HashSet<Int>()
        var cmd = Gaia.C_BASIC_GET_SUPPORTED_FEATURES
        var guard = 0
        while (guard++ < 4) {
            val p = request(Gaia.command(Gaia.F_BASIC, cmd), ANC_TIMEOUT_MS) ?: break
            // 实测格式（Pudding 真机，官方 App logcat）：
            //   payload = [moreFlag:1][featureId:1][version:1]...
            //   例：00 | 00 02 | 01 01 | 05 01 | 0D 01 | 0E 01 | 0F 01 | 10 01 | 13 01 | 14 01 | 16 01 | 20 01
            //   → features {0,1,5,13,14,15,16,19,20,22,32}
            // ⚠ 注意 moreFlag 属于**同一份 payload**，不能再在别处剥一次，
            //   否则 feature/version 会整体错位一格（曾经的能力探测错误）。
            val (more, entries) = Gaia.parseFeatureEntries(p)
            if (entries.isNotEmpty()) {
                feats.addAll(entries.map { it.feature })
                if (!more) break
                cmd = Gaia.C_BASIC_GET_SUPPORTED_FEATURES_NEXT
            } else {
                // 回退：老固件的 32-bit 位图形式（无分页标志）
                feats.addAll(Gaia.parseSupportedFeatures(p))
                break
            }
        }

        // 设备上报的电池类型（修复「右耳不显示」的第一步）
        val supported = request(Gaia.batteryGetAllV4(), ANC_TIMEOUT_MS)?.let { BatteryCodec.parseSupported(it) }

        val path = Gaia.ancPathFrom(feats).let {
            if (it != Gaia.ANC_PATH_UNKNOWN) it else (model.anc?.path?.feature ?: Gaia.ANC_PATH_UNKNOWN)
        }

        capabilities = PodCapabilities(
            features = feats,
            ancPath = path,
            batteryTypes = supported?.toList() ?: emptyList(),
            hasGain = model.dc.hasGain || Gaia.F_DAC_GAIN in feats,
            hasLed = model.dc.hasLed || Gaia.F_LED in feats,
            hasSpatial = model.dc.hasSpatial || Gaia.F_SPATIAL_AUDIO in feats,
            hasHeadTracking = model.dc.hasHeadTracking,
            hasPromptTone = model.features.promptTone || Gaia.F_VOICE in feats,
            hasPromptVolume = model.features.promptVolume || Gaia.F_VOICE in feats,
            hasLhdc = model.features.lhdc || Gaia.F_CODEC_TYPE in feats,
            hasDualConnection = model.features.dualConnection || Gaia.F_ONEBRINGTWO in feats,
            // 手势：**只看能力位图**（Pudding 真机位图里确有 feature 22）；型号档案不参与判定
            hasGestures = Gaia.F_TOUCHV2 in feats,
            hasLowLatency = model.features.lowLatency,
            probed = true,
        )
        ancModes = model.anc?.modes ?: emptyList()
        emit(PodEvent.CapabilitiesChanged(capabilities))
        Log.i(TAG, "capabilities: $capabilities")
    }

    /** 非挂起入口：供 BroadcastReceiver 之类的同步上下文触发一次电量刷新。 */
    fun requestBatteryRefresh() {
        scope.launch { runCatching { refreshBattery() } }
    }

    /** 读取全量状态。 */
    fun refreshAll() {
        scope.launch {
            refreshBattery()
            refreshAnc()
            if (capabilities.hasGain) refreshGain()
            if (capabilities.hasLed) refreshLed()
            // 提示音开关与音量是**同一份配置**，读一次即可
            if (capabilities.hasPromptTone || capabilities.hasPromptVolume) refreshPromptVoice()
            if (capabilities.hasLhdc) refreshLhdc()
            if (capabilities.hasDualConnection) refreshDualConnection()
            // 空间音频（feature 18）：只在能力位为真时发 —— 老机型不支持这个 feature，
            // 发了就是一条无人应答的未知命令（还会白等一次超时）。
            // 头部追踪是同一 feature 的另一组命令，自己单独门控（refreshSpatial 内部再判一次）。
            if (capabilities.hasSpatial || capabilities.hasHeadTracking) refreshSpatial()
            // 手势：一次读回 5 个槽位的整份配置（能力位图门控，与其它功能同一套写法）
            if (capabilities.hasGestures) refreshGestures()
            emitState()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (connected) {
                delay(30_000L)
                refreshBattery()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 各功能：读
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 电量：先按设备上报的支持类型查询；设备不说就退回「全部类型」。
     * 这是「右耳不显示」的第一处修复——必须把要查的类型列全。
     */
    suspend fun refreshBattery() {
        val ids = capabilities.batteryTypes.takeIf { it.isNotEmpty() }?.toIntArray()
            ?: BatteryCodec.FALLBACK_QUERY_IDS
        val p = request(Gaia.batteryGet(ids), ANC_TIMEOUT_MS)
            ?: request(Gaia.batteryGetAll(), ANC_TIMEOUT_MS)
            ?: return
        applyBatteryPayload(p)
    }

    private fun applyBatteryPayload(payload: ByteArray) {
        val pairs = BatteryCodec.parse(payload)
        // 兜底：payload 只有单值且我们知道在查哪一类时，用第一个类型当 hint
        val effective = if (pairs.isEmpty() && payload.size == 1) {
            val hint = capabilities.batteryTypes.firstOrNull() ?: -1
            BatteryCodec.parse(payload, hint)
        } else pairs
        if (batteryState.applyPairs(effective)) {
            BatteryStateAccess.systemLevel = batteryState.systemLevel()
            emit(PodEvent.BatteryChanged(snapshot().battery))
        }
    }

    suspend fun refreshAnc() {
        val path = capabilities.ancPath
        val pkt = when (path) {
            Gaia.ANC_PATH_ANC_V2 -> Gaia.ancV2GetMode()
            Gaia.ANC_PATH_AUDIO_CURATION -> Gaia.audioCurationGetMode()
            Gaia.ANC_PATH_ANC_V1 -> Gaia.ancV1GetState()
            else -> return
        }
        val p = request(pkt, ANC_TIMEOUT_MS) ?: return
        applyAncPayload(p)
    }

    private fun applyAncPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        val dev = payload[0].toInt() and 0xFF
        val prof = model.anc
        ancIndex = when (capabilities.ancPath) {
            Gaia.ANC_PATH_ANC_V2 -> if (prof != null) prof.deviceToUi(dev) else dev
            Gaia.ANC_PATH_AUDIO_CURATION -> prof?.deviceToUi(dev) ?: (dev - 1)
            Gaia.ANC_PATH_ANC_V1 -> if (dev == 0) 0 else 1
            else -> -1
        }
        if (ancIndex >= 0) {
            val mode = ancModes.getOrNull(ancIndex)
            if (mode != null) emit(PodEvent.AncChanged(ancIndex, mode))
        }
    }

    suspend fun refreshGain() {
        val p = request(Gaia.gainGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        gainIndex = model.dc.gainDeviceToUi(p[0].toInt() and 0xFF)
        emit(PodEvent.GainChanged(gainIndex))
    }

    suspend fun refreshLed() {
        val p = request(Gaia.ledGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        ledOn = (p[0].toInt() and 0xFF) == 1
        emit(PodEvent.LedChanged(ledOn!!))
    }

    /**
     * 读提示音配置（cmd 1）。
     *
     * 官方 App 日志实测：回包 payload = `[enabled, volume, index]`，
     * `VoiceRepositoryData: updateV2VoiceConf: enabled=true, volume=20, index=1`。
     * 开关与音量来自**同一次读**。
     */
    suspend fun refreshPromptVoice() {
        val p = request(Gaia.voiceGetConf(model.features.cmdVoiceGetEnable), ANC_TIMEOUT_MS) ?: return
        val conf = Gaia.parseVoiceConf(p) ?: return
        applyVoiceConf(conf)
    }

    private fun applyVoiceConf(conf: Gaia.VoiceConf) {
        promptToneOn = conf.enabled
        promptVolumeRaw = conf.volume
        promptIndex = conf.index
        emit(PodEvent.PromptToneChanged(conf.enabled))
        emit(PodEvent.PromptVolumeChanged(conf.volume))
    }

    /**
     * 读 LHDC 开关。
     *
     * @return 读到并已落地 = true；超时 / 空包 = false 且**不改动** [lhdcOn]
     *   （[setLhdc] 依赖这一点：读不到时保留乐观值，而不是悄悄弹回旧值）。
     */
    suspend fun refreshLhdc(): Boolean {
        val p = request(Gaia.lhdcGet(), ANC_TIMEOUT_MS) ?: return false
        if (p.isEmpty()) return false
        applyLhdc((p[0].toInt() and 0xFF) == 1)
        return true
    }

    /** 落地一个**已被设备确认**的 LHDC 值（回读结果或主动通知），并刷新回退基线。 */
    private fun applyLhdc(on: Boolean) {
        lhdcConfirmed = on
        lhdcOn = on
        emit(PodEvent.LhdcChanged(on))
    }

    suspend fun refreshDualConnection() {
        val p = request(Gaia.dualConnectionGet(), ANC_TIMEOUT_MS) ?: return
        if (p.isEmpty()) return
        dualConnectionOn = (p[0].toInt() and 0xFF) == 1
        emit(PodEvent.DualConnectionChanged(dualConnectionOn!!))
    }

    /**
     * 读空间音频开关（feature 18 / cmd 1），能力位为真时才发。
     *
     * 取到的 payload 按与指示灯同一套口径落地：`0`=关 / `1`=开。写在同一个函数里的头部追踪是
     * 同一 feature 的 cmd 3，只有 [PodCapabilities.hasHeadTracking] 为真才读。
     *
     * ⚠ 待真机确认：这两条命令的**命令号与 payload 语义**核实于 PROTOCOL.md 第 8 节
     * （feature 18：GET/SET = 1/2、payload `0/1`；头动追踪 3/4），但本应用从未在支持空间音频的
     * 机型上跑通，读不到就保持 null（UI 不猜）。
     */
    suspend fun refreshSpatial() = spatialReadLock.withLock {
        // 两个读各自按自己的能力位门控：不支持那一条就不发（老机型不发未知命令）
        if (capabilities.hasSpatial) {
            val sp = request(Gaia.spatialGet(), ANC_TIMEOUT_MS)
            if (sp != null && sp.isNotEmpty()) spatialEnabled = (sp[0].toInt() and 0xFF) == 1
        }
        if (!capabilities.hasHeadTracking) return@withLock
        val ht = request(Gaia.headTrackingGet(), ANC_TIMEOUT_MS)
        if (ht != null && ht.isNotEmpty()) headTrackingOn = (ht[0].toInt() and 0xFF) == 1
    }

    /**
     * feature 18 两条读命令的串行锁。
     *
     * 为什么需要：[responses] 的等待表**只按 feature 建键**（见 [request]），而 feature 18 上
     * 同时有 cmd 1（空间音频）与 cmd 3（头部追踪）两条读命令。[refreshSpatial] 有三个调用点
     * （refreshAll 的轮询、[setSpatial]、[setHeadTracking] 写完后的回读），完全可能并发：
     * 那时一条命令的回包会唤醒**另一条**命令的等待者，把空间音频的值当成头部追踪写进状态。
     * 加锁只串行化这两条读，不动 [responses] 的键结构（那会牵动其它功能）。
     */
    private val spatialReadLock = Mutex()

    /**
     * 读手势配置（TOUCHV2 cmd 2）。
     *
     * 固件只提供「整份 5 字节配置」的读写 —— 既没有单槽位读、也没有按耳读：
     * 所以一次全读，UI 的 10 行（5 手势 × 2 耳）都取自这一份快照（[PodSnapshot.gestureConf]）。
     */
    suspend fun refreshGestures() {
        if (!capabilities.hasGestures) return
        val slots = fetchGestureConf() ?: return
        applyGestureConf(slots)
    }

    /** 读回 5 个槽位（不落状态）；超时/回包过短返回 null。 */
    private suspend fun fetchGestureConf(): IntArray? {
        val p = request(Gaia.touchV2GetConf(), ANC_TIMEOUT_MS) ?: return null
        return Gaia.parseGestureConf(p)?.slots
    }

    private fun applyGestureConf(slots: IntArray) {
        gestureConf = slots
        emit(PodEvent.GestureChanged(Gaia.GestureConf(slots.copyOf())))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 各功能：写（乐观更新 + 读回确认）
    // ══════════════════════════════════════════════════════════════════════

    fun setAnc(uiIndex: Int) {
        val prof = model.anc ?: return
        val dev = prof.uiToDevice(uiIndex)
        if (dev < 0) return
        scope.launch {
            when (capabilities.ancPath) {
                Gaia.ANC_PATH_ANC_V2 -> write(Gaia.ancV2SetMode(dev))
                Gaia.ANC_PATH_AUDIO_CURATION -> {
                    // EDGE 真机确认：AC 的 SET_MODE payload 是位掩码 1/2/4
                    val bit = Gaia.AC_SET_PAYLOAD.getOrNull(uiIndex) ?: dev
                    write(Gaia.audioCurationSetMode(bit))
                }
                Gaia.ANC_PATH_ANC_V1 -> write(Gaia.ancV1SetState(if (dev == 0) 0 else 1))
            }
            delay(300)
            refreshAnc()
            emitState()
        }
    }

    fun setGain(uiIndex: Int) {
        val dev = model.dc.gainUiToDevice(uiIndex)
        if (dev < 0) return
        scope.launch { write(Gaia.gainSet(dev)); delay(300); refreshGain(); emitState() }
    }

    fun setLed(on: Boolean) {
        scope.launch { write(Gaia.ledSet(if (on) 1 else 0)); delay(300); refreshLed(); emitState() }
    }

    /**
     * 写空间音频开关（feature 18 / cmd 2）。
     *
     * payload 取值沿用 [Gaia.spatialSet] 里唯一的一种写法：`0`=关 / `1`=开（与 [setLed] 同口径，
     * 与 PROTOCOL.md 第 8 节「payload `0/1`」一致）；这里**不发明映射表**。
     * 写完按仓库既有写法回读一次再 emitState，读到什么就是什么。
     *
     * ⚠ 待真机确认：本应用从未在支持空间音频的机型上验证过这条写入。若真机写不动，
     * 先看 [refreshSpatial] 的回读值，再回看 PROTOCOL.md 的第 8 节 / 第 8 条未验证清单。
     */
    fun setSpatial(enabled: Boolean) {
        if (!capabilities.hasSpatial) return
        scope.launch {
            write(Gaia.spatialSet(if (enabled) 1 else 0))
            delay(300)
            refreshSpatial()
            emitState()
        }
    }

    /**
     * 写头部追踪开关（同一 feature 18 的 cmd 4），payload 同样 `0/1`（[Gaia.headTrackingSet]）。
     * ⚠ 待真机确认，理由同 [setSpatial]。
     */
    fun setHeadTracking(enabled: Boolean) {
        if (!capabilities.hasHeadTracking) return
        scope.launch {
            write(Gaia.headTrackingSet(if (enabled) 1 else 0))
            delay(300)
            refreshSpatial()
            emitState()
        }
    }

    /**
     * 写提示音配置（cmd 2）。
     *
     * ⚠ 必须下发**完整三字节** `[enabled, volume, index]`：固件把三者当作一份配置，
     * 只发开关会把音量/索引写坏（反之亦然）。
     *
     * @param volumePercent 0..100
     */
    private fun setPromptVoice(enabled: Boolean, volumePercent: Int, index: Int) {
        scope.launch {
            write(
                Gaia.voiceSetConf(
                    enabled = enabled,
                    volumePercent = volumePercent,
                    index = index,
                    cmdSet = model.features.cmdVoiceSetEnable,
                )
            )
            delay(300)
            refreshPromptVoice()
            emitState()
        }
    }

    fun setPromptTone(on: Boolean) {
        // 保留当前音量与索引，只改开关
        setPromptVoice(on, promptVolumeRaw.takeIf { it in 0..100 } ?: 50, promptIndex)
    }

    /** @param raw 提示音音量百分比 0..100（与官方 App 同一单位） */
    fun setPromptVolumeRaw(raw: Int) {
        setPromptVoice(promptToneOn ?: true, raw.coerceIn(0, Gaia.VOICE_VOLUME_MAX), promptIndex)
    }

    /**
     * LHDC 开关。关掉 LHDC 后耳机回到基础编码（AAC / SBC / LDAC），
     * 这正是「默认 AAC」的表现：出厂默认 LHDC 关闭。
     *
     * 与 [setAnc]/[setGain] 同构，但多做两步，因为「开关不生效」的真因在这里：
     *   ① **乐观更新**：立刻置位并广播，UI 不等耳机回包；
     *   ② `write` 后 500ms 回读确认，读到即以设备为准；
     *   ③ 回读**超时**：保留乐观值，隔 [LHDC_CONFIRM_RETRY_DELAY_MS] 有界重试
     *      [LHDC_CONFIRM_RETRIES] 次（旧实现直接 return，[lhdcOn] 留在旧值，
     *      UI 就把开关弹回去 —— 用户看到的「打开 LHDC 没反应」）；
     *   ④ 只有全部重试都失败才回退到最后一次被设备确认的值（[lhdcConfirmed]，
     *      可能为 null = 未知），并打日志 —— 绝不留一个永远错误的值。
     */
    fun setLhdc(on: Boolean) {
        val lastConfirmed = lhdcConfirmed
        lhdcOn = on
        Log.i(TAG, "setLhdc($on): optimistic; lastConfirmed=$lastConfirmed")
        emit(PodEvent.LhdcChanged(on))
        emitState()
        scope.launch {
            write(Gaia.lhdcSet(on))
            delay(500)
            val settled = if (refreshLhdc()) {
                Log.i(TAG, "setLhdc($on): confirmed by read-back lhdcOn=$lhdcOn")
                true
            } else {
                Log.w(
                    TAG,
                    "setLhdc($on): read-back timed out; keeping optimistic value, " +
                        "retrying ${LHDC_CONFIRM_RETRIES}x",
                )
                retryLhdcConfirm(on)
            }
            if (!settled) {
                Log.w(
                    TAG,
                    "setLhdc($on): unconfirmed after ${LHDC_CONFIRM_RETRIES} retries; " +
                        "falling back to lastConfirmed=$lastConfirmed",
                )
                lhdcOn = lastConfirmed
                lastConfirmed?.let { emit(PodEvent.LhdcChanged(it)) }
            }
            emitState()
        }
    }

    /** 回读确认的有界重试；成功时 [refreshLhdc] 已把真实值落地。 */
    private suspend fun retryLhdcConfirm(on: Boolean): Boolean {
        repeat(LHDC_CONFIRM_RETRIES) { i ->
            delay(LHDC_CONFIRM_RETRY_DELAY_MS)
            if (refreshLhdc()) {
                Log.i(TAG, "setLhdc($on): confirmed on retry #${i + 1} lhdcOn=$lhdcOn")
                return true
            }
        }
        return false
    }

    fun setDualConnection(on: Boolean) {
        scope.launch { write(Gaia.dualConnectionSet(on)); delay(500); refreshDualConnection(); emitState() }
    }

    /**
     * 写「某只手势的某只耳朵」的动作 —— **只改该字节里的那一个半字节**。
     *
     * ⚠ **必须下发完整 5 字节**（见 [Gaia.touchV2SetConf]）：固件把 5 个字节当作一份
     * 配置，只发一部分会把其余字节写坏 —— 与 [setPromptVoice] 是同一个坑。
     * 因此这里：若还没读到过配置，先读一次再写；**读不到就放弃本次写入**，
     * 绝不用 0 补齐（那等于把用户其余手势 / 另一只耳朵悄悄清成「无」）。
     * [Gaia.GestureConf.with] 负责半字节读改写：另一只耳朵与其余 4 个字节原样保留。
     *
     * 写完按既有模式 delay 后回读，保证 UI 显示的是固件真实接受了的值。
     *
     * @param slot     手势种类（单击 / 双击 / 三击 / 长按1秒 / 长按3秒）
     * @param ear      哪只耳朵（左 = 高 4 位，右 = 低 4 位）
     * @param actionId 动作 id（半字节 0..15；不在 [Gaia.TouchActions] 里的值也原样下发）
     */
    fun setGesture(slot: Gaia.GestureSlot, ear: Gaia.Ear, actionId: Int) {
        scope.launch {
            val current = gestureConf ?: fetchGestureConf()
            if (current == null) {
                Log.w(TAG, "setGesture($slot/$ear) skipped: gesture config unknown")
                return@launch
            }
            var next = Gaia.GestureConf(current.copyOf()).with(slot, ear, actionId)
            // 长按 1 秒 / 3 秒 **同侧互斥**：同一只耳朵上两档不能共存（按住 3 秒必然也满足
            // 1 秒的触发条件，两条同时配时设备行为不确定），所以把某一个设成非「无」时，
            // 只把**这只耳**的另一档清成「无」—— 另一只耳的长按配置不受影响。
            // 与 dev 侧 pods/moondrop/MoondropController.setGesture 的同一条规则一致。
            // 只有长按这一对有互斥关系；单击/双击/三击之间没有，别顺手扩大。
            val counterpart = when (slot) {
                Gaia.GestureSlot.LONG_PRESS_1S -> Gaia.GestureSlot.LONG_PRESS_3S
                Gaia.GestureSlot.LONG_PRESS_3S -> Gaia.GestureSlot.LONG_PRESS_1S
                else -> null
            }
            if (counterpart != null && actionId != Gaia.TOUCH_ACTION_NONE &&
                next.action(counterpart, ear) != Gaia.TOUCH_ACTION_NONE
            ) {
                next = next.with(counterpart, ear, Gaia.TOUCH_ACTION_NONE)
                Log.i(TAG, "setGesture: 同侧互斥 -> ${ear.labelZh} 的 ${counterpart.labelZh} 自动置空")
            }
            Log.i(
                TAG,
                "setGesture ${slot.index}/${slot.labelZh}/${ear.labelZh} -> " +
                    "${Gaia.TouchActions.matchOrUnknown(actionId)} ($next)",
            )
            write(Gaia.touchV2SetConf(next.toPayload()))
            delay(300)
            refreshGestures()
            emitState()
        }
    }

    /** 由系统侧（低延迟开关）回调进来。 */
    fun onLowLatencyChanged(on: Boolean) {
        lowLatencyOn = on
        emit(PodEvent.LowLatencyChanged(on))
        emitState()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 响应分发（除了唤醒 request()，还要处理主动通知）
    // ══════════════════════════════════════════════════════════════════════

    private fun dispatch(f: Gaia.Frame) {
        when (f.feature) {
            Gaia.F_BATTERY -> if (f.command == Gaia.C_BATT_GET_BATTERY_LEVELS ||
                f.command == Gaia.C_BATT_GET_BATTERY_LEVELS_V4
            ) {
                applyBatteryPayload(f.payload)
            }
            Gaia.F_ANC_V2 -> if (f.command == Gaia.C_ANC2_GET_CURRENT_MODE ||
                f.command == Gaia.C_ANC2_SET_CURRENT_MODE
            ) {
                applyAncPayload(f.payload)
            }
            Gaia.F_AUDIO_CURATION -> if (f.command == Gaia.C_AC_GET_CURRENT_MODE) {
                applyAncPayload(f.payload)
            }
            Gaia.F_ANC -> if (f.command == Gaia.C_ANC1_GET_ANC_STATE) applyAncPayload(f.payload)
            Gaia.F_DAC_GAIN -> if (f.command == Gaia.C_DAC_GET_GAIN && f.payload.isNotEmpty()) {
                gainIndex = model.dc.gainDeviceToUi(f.payload[0].toInt() and 0xFF)
                emit(PodEvent.GainChanged(gainIndex))
            }
            Gaia.F_LED -> if (f.command == Gaia.C_LED_GET_STATE && f.payload.isNotEmpty()) {
                ledOn = (f.payload[0].toInt() and 0xFF) == 1
                emit(PodEvent.LedChanged(ledOn!!))
            }
            Gaia.F_CODEC_TYPE -> if (f.command == Gaia.C_CODEC_GET_LHDC_STATE && f.payload.isNotEmpty()) {
                // 主动/回读到的真实值都算「已确认」，统一走 applyLhdc 以刷新回退基线
                applyLhdc((f.payload[0].toInt() and 0xFF) == 1)
            }
            Gaia.F_ONEBRINGTWO -> if (f.command == Gaia.C_OBT_GET_STATE && f.payload.isNotEmpty()) {
                dualConnectionOn = (f.payload[0].toInt() and 0xFF) == 1
                emit(PodEvent.DualConnectionChanged(dualConnectionOn!!))
            }
            Gaia.F_SPATIAL_AUDIO -> if (f.payload.isNotEmpty()) {
                // GET(1)/SET(2) = 空间音频开关；GET(3)/SET(4) = 头部追踪（命令号见 Gaia.kt）。
                // 与其它 feature 一样，只处理 GET/SET 这两个命令号，payload 按 0/1 解读。
                when (f.command) {
                    Gaia.C_SPATIAL_GET_STATE, Gaia.C_SPATIAL_SET_STATE ->
                        spatialEnabled = (f.payload[0].toInt() and 0xFF) == 1
                    Gaia.C_SPATIAL_GET_HEAD_TRACKING, Gaia.C_SPATIAL_SET_HEAD_TRACKING ->
                        headTrackingOn = (f.payload[0].toInt() and 0xFF) == 1
                }
            }
            Gaia.F_VOICE -> if (f.command == Gaia.C_VOICE_GET_CONF ||
                f.command == Gaia.C_VOICE_SET_CONF
            ) {
                Gaia.parseVoiceConf(f.payload)?.let { applyVoiceConf(it) }
            }
            Gaia.F_TOUCHV2 -> if (f.command == Gaia.C_TOUCHV2_GET_ACTION_CONF ||
                f.command == Gaia.C_TOUCHV2_SET_ACTION_CONF
            ) {
                // 读回与写入回显都是同一份 5 字节配置
                Gaia.parseGestureConf(f.payload)?.let { applyGestureConf(it.slots) }
            }
            Gaia.F_BASIC -> if (f.command == Gaia.C_BASIC_GET_SUPPORTED_FEATURES) {
                // request() 已经消费；这里只做通知型位图的增量合并
                capabilities = capabilities.copy(
                    features = capabilities.features + Gaia.parseSupportedFeaturesSmart(f.payload),
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 9ECA 私有协议（中科蓝讯系：音源切换 / EQ / MIC）
    // ══════════════════════════════════════════════════════════════════════

    fun srcGetAudioSource(seq: Int) = write(SrcProtocol.command(SrcProtocol.CMD_GET_AUDIO_SOURCE, seq, null))
    fun srcSetAudioSource(seq: Int, sourceId: Int) =
        write(SrcProtocol.setAudioSource(seq, sourceId))

}

/**
 * 把系统蓝牙栈读到的电量放在这里，避免直接暴露 [MoondropLink] 的内部对象。
 * ⚠ 原读取方是蓝牙进程里的 hook（已随模块删除），因此现在是只写不读的兼容出口。
 */
object BatteryStateAccess {
    @Volatile var systemLevel: Int = BATTERY_UNKNOWN
}
