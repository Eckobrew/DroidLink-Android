package com.droiddock.companion.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/** One serialized GATT operation at a time, including CCCD setup and every write. */
@SuppressLint("MissingPermission") // Every entry checks Nearby Devices permission; revocation is handled below.
class BluetoothTransport(
    private val context: Context,
    private val onPacket: (ByteArray) -> Unit,
    private val onConnection: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("DF010001-B123-4ABC-9DEF-0123456789AB")
        val RX: UUID = UUID.fromString("DF010002-B123-4ABC-9DEF-0123456789AB")
        val TX: UUID = UUID.fromString("DF010003-B123-4ABC-9DEF-0123456789AB")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val thread = HandlerThread("DroidDock-BLE").apply { start() }
    private val handler = Handler(thread.looper)
    private val decoder = BleFrameDecoder()
    private var enabled = false
    private var scanning = false
    private var needsBondRepair = false
    private var serviceDiscoveryRetried = false
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtuPayload = 20
    private var stage = "idle"
    private var stageStarted = 0L
    @Volatile var lastActivity: Long = 0
        private set
    @Volatile var isConnected = false
        private set
    private data class Write(val bytes: ByteArray, val completion: (Exception?) -> Unit, var offset: Int = 0)
    private val writes = ArrayDeque<Write>()
    private var queuedBytes = 0
    private var inFlightBytes = 0
    private var writeStarted = 0L
    private val cooldown = mutableMapOf<String, Long>()
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private fun permitted() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    init {
        handler.post(object : Runnable {
            override fun run() {
                if (gatt != null && ((stage != "ready" && now() - stageStarted > 20_000) ||
                        (inFlightBytes > 0 && now() - writeStarted > 15_000))) {
                    closeLink(IllegalStateException("Bluetooth operation timed out"))
                }
                handler.postDelayed(this, 5000)
            }
        })
    }

    fun start() { handler.post { enabled = true; scan() } }
    fun retry() { handler.post { needsBondRepair = false; enabled = true; scan() } }
    fun stop() { handler.post { enabled = false; stopScan(); closeLink(IllegalStateException("Bluetooth stopped")) } }
    fun disconnect() { handler.post { closeLink(IllegalStateException("Bluetooth session closed")) } }

    private val retry = Runnable { scan() }
    private val scanTimeout = Runnable {
        stopScan()
        if (enabled && gatt == null) handler.postDelayed(retry, 4000)
    }
    private fun scan() {
        if (!enabled || gatt != null || scanning) return
        if (needsBondRepair) { onStatus("Bluetooth key lost: forget/re-pair this Mac in system Bluetooth settings, then tap Retry"); return }
        if (!permitted()) { onStatus("Grant Nearby Devices permission for Bluetooth sync"); return }
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isEnabled != true) {
                onStatus("Turn on Bluetooth for fallback sync")
                handler.removeCallbacks(retry); handler.postDelayed(retry, 5000)
                return
            }
            scanner = adapter.bluetoothLeScanner
            val current = scanner ?: return
            scanning = true
            onStatus("Looking for your Mac over Bluetooth")
            current.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanCallback)
            handler.removeCallbacks(scanTimeout); handler.postDelayed(scanTimeout, 10_000)
        } catch (e: SecurityException) { scanning = false; onStatus("Nearby Devices permission was revoked") }
        catch (e: Exception) { scanning = false; onStatus("Bluetooth scan failed"); handler.postDelayed(retry, 5000) }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) { try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) {} }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!enabled || gatt != null || !scanning || !permitted()) return@post
                try {
                    if ((cooldown[result.device.address] ?: 0) > now()) return@post
                    stopScan()
                    serviceDiscoveryRetried = false
                    stage = "connecting"; stageStarted = now()
                    onStatus("Connecting to Bluetooth sync service")
                    gatt = result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, handler)
                    if (gatt == null) closeLink(IllegalStateException("Could not open GATT"))
                } catch (e: Exception) { closeLink(e) }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post { scanning = false; onStatus("Bluetooth scan failed ($errorCode)"); handler.postDelayed(retry, 5000) }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(current: BluetoothGatt, status: Int, newState: Int) {
            if (current !== gatt) return
            try {
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION) {
                        needsBondRepair = true
                        closeLink(IllegalStateException("Bluetooth key lost: forget/re-pair this Mac in system Bluetooth settings, then tap Retry"))
                    } else { closeLink(IllegalStateException("GATT disconnected ($status)")) }
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stage = "mtu"; stageStarted = now()
                    if (!current.requestMtu(517)) discover(current)
                }
            } catch (e: Exception) { closeLink(e) }
        }
        override fun onMtuChanged(current: BluetoothGatt, mtu: Int, status: Int) {
            if (current !== gatt || stage != "mtu") return
            Log.d("DroidDockBLE", "Negotiated MTU=$mtu status=$status")
            mtuPayload = if (status == BluetoothGatt.GATT_SUCCESS) (mtu - 3).coerceIn(20, 512) else 20
            try { discover(current) } catch (e: Exception) { closeLink(e) }
        }
        override fun onServiceChanged(current: BluetoothGatt) {
            if (current !== gatt) return
            // Obsolete attribute handles must not be reused. Reconnecting discovers
            // services before subscribing and authenticates a fresh app session.
            closeLink(IllegalStateException("Mac Bluetooth services changed; rediscovering"))
        }
        override fun onServicesDiscovered(current: BluetoothGatt, status: Int) {
            if (current !== gatt || stage != "discover") return
            try {
                require(status == BluetoothGatt.GATT_SUCCESS) { "GATT service discovery failed" }
                val service = current.getService(SERVICE)
                if (service == null && !serviceDiscoveryRetried) {
                    serviceDiscoveryRetried = true
                    Log.d("DroidDockBLE", "Sync service absent; refreshing stale GATT cache")
                    refreshServiceCache(current)
                    stage = "discoveryRetry"; stageStarted = now()
                    onStatus("Mac sync service not ready; retrying discovery")
                    handler.postDelayed({
                        if (current === gatt && stage == "discoveryRetry") {
                            try { discover(current) } catch (e: Exception) { closeLink(e) }
                        }
                    }, 1000)
                    return
                }
                checkNotNull(service) { "Mac Bluetooth sync service is unavailable. Keep the Mac app open and tap Retry; USB diagnostics may be needed." }
                rx = service.getCharacteristic(RX) ?: error("Mac write characteristic is missing")
                val tx = service.getCharacteristic(TX) ?: error("Mac notification characteristic is missing")
                check(current.setCharacteristicNotification(tx, true)) { "Could not enable Bluetooth notifications" }
                val descriptor = tx.getDescriptor(CCCD) ?: error("Notification descriptor is missing")
                stage = "subscribe"; stageStarted = now()
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    current.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    current.writeDescriptor(descriptor)
                }
                check(started) { "Could not subscribe to Mac notifications" }
            } catch (e: Exception) { closeLink(e) }
        }
        override fun onDescriptorWrite(current: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (current !== gatt || stage != "subscribe" || descriptor.uuid != CCCD) return
            if (status != BluetoothGatt.GATT_SUCCESS) { closeLink(IllegalStateException("Notification subscription failed")); return }
            stage = "ready"; isConnected = true
            onStatus("Bluetooth link connected; verifying Mac")
            onConnection(true)
            drain()
        }
        override fun onCharacteristicChanged(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (current === gatt && characteristic.uuid == TX) receive(value)
        }
        @Deprecated("Legacy callback for Android 12")
        override fun onCharacteristicChanged(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && current === gatt && characteristic.uuid == TX) {
                @Suppress("DEPRECATION")
                receive(characteristic.value?.clone() ?: return)
            }
        }
        override fun onCharacteristicWrite(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (current !== gatt || characteristic.uuid != RX || inFlightBytes == 0) return
            if (status != BluetoothGatt.GATT_SUCCESS) { closeLink(IllegalStateException("Bluetooth write failed ($status)")); return }
            val first = writes.peekFirst() ?: return
            lastActivity = now()
            first.offset += inFlightBytes
            queuedBytes -= inFlightBytes
            inFlightBytes = 0
            if (first.offset == first.bytes.size) { writes.removeFirst(); first.completion(null) }
            drain()
        }
    }

    // Android exposes no public cache invalidation API. Use the optional stack
    // hook only after successful discovery omitted our advertised service.
    // Failure is harmless: one normal rediscovery still occurs, then reconnect.
    @android.annotation.SuppressLint("DiscouragedPrivateApi")
    private fun refreshServiceCache(current: BluetoothGatt): Boolean = try {
        val refreshed = current.javaClass.getMethod("refresh").invoke(current) == true
        Log.d("DroidDockBLE", "GATT cache refresh accepted=$refreshed")
        refreshed
    } catch (e: Exception) {
        Log.d("DroidDockBLE", "GATT cache refresh unsupported: ${e.javaClass.simpleName}")
        false
    }

    private fun discover(current: BluetoothGatt) {
        stage = "discover"; stageStarted = now()
        check(current.discoverServices()) { "Could not discover GATT services" }
    }
    private fun receive(value: ByteArray) {
        lastActivity = now()
        try { decoder.append(value).forEach(onPacket) }
        catch (e: Exception) { closeLink(e) }
    }

    fun send(data: ByteArray, completion: (Exception?) -> Unit) {
        handler.post {
            if (!isConnected) { completion(IllegalStateException("Bluetooth is disconnected")); return@post }
            try {
                val frame = BleFrameDecoder.encode(data)
                check(writes.size < 128 && queuedBytes + frame.size <= 16 * 1024 * 1024) { "Bluetooth send queue is full" }
                writes.addLast(Write(frame, completion)); queuedBytes += frame.size
                drain()
            } catch (e: Exception) { completion(e) }
        }
    }
    private fun drain() {
        val current = gatt ?: return
        val characteristic = rx ?: return
        if (!isConnected || inFlightBytes > 0) return
        val first = writes.peekFirst() ?: return
        try {
            val chunk = first.bytes.copyOfRange(first.offset, minOf(first.offset + mtuPayload, first.bytes.size))
            inFlightBytes = chunk.size; writeStarted = now()
            val started = if (Build.VERSION.SDK_INT >= 33) {
                current.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                current.writeCharacteristic(characteristic)
            }
            check(started) { "Bluetooth stack rejected write" }
        } catch (e: Exception) { closeLink(e) }
    }
    private fun closeLink(error: Exception) {
        val old = gatt
        gatt = null; rx = null; mtuPayload = 20; stage = "idle"
        val wasConnected = isConnected
        isConnected = false
        decoder.reset(); inFlightBytes = 0; queuedBytes = 0
        try { old?.device?.address?.let { cooldown[it] = now() + 10_000 }; old?.disconnect() } catch (_: SecurityException) {}
        try { old?.close() } catch (_: SecurityException) {}
        val pending = writes.toList(); writes.clear()
        if (wasConnected || old != null) onConnection(false)
        pending.forEach { it.completion(error) }
        if (enabled) {
            onStatus(error.message ?: "Bluetooth disconnected")
            handler.removeCallbacks(retry)
            if (!needsBondRepair) handler.postDelayed(retry, 3000)
        }
        Log.d("DroidDockBLE", error.message ?: "Disconnected")
    }
}
