package com.droiddock.companion.mirroring

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workerThread = HandlerThread("DroidLink capture")
    private lateinit var worker: Handler
    private val ending = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: ScreenEncoder? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var recorder: AudioRecord? = null
    private var sessionId = ""
    private var offer: MirrorOffer? = null
    private var audioEnabled = false
    private var width = 0
    private var height = 0
    private var metadata = ByteArray(0)
    private var codecConfig: ByteArray? = null
    private var waitingForKey = false
    private var lastControl = false
    private var lastHealth = 0L
    private var lastCaptureWidth = 0
    private var lastCaptureHeight = 0
    private val outgoing = ArrayBlockingQueue<ByteArray>(8)
    private val gson = Gson()

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        workerThread.start(); worker = Handler(workerThread.looper)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            if (intent.getStringExtra("sessionId") == sessionId) finishSharing("Screen sharing stopped on phone")
            return START_NOT_STICKY
        }
        if (projection != null || sessionId.isNotEmpty()) return START_NOT_STICKY
        sessionId = intent?.getStringExtra("sessionId") ?: run { stopSelf(); return START_NOT_STICKY }
        val request = MirrorCoordinator.activate(this, sessionId) ?: run { stopSelf(); return START_NOT_STICKY }
        offer = request.offer
        audioEnabled = intent.getBooleanExtra("audio", false)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("screen_sharing", "Active screen sharing", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction("stop").putExtra("sessionId", sessionId)
        val stop = PendingIntent.getService(this, sessionId.hashCode(), stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "screen_sharing").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sharing screen with Droid Link").setContentText("Your paired Mac can see your screen.")
            .setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stop).build()
        try {
            startForeground(4102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("resultData") ?: error("Capture permission missing")
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("resultCode", 0), data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked || !getSystemService(PowerManager::class.java).isInteractive
                    if (locked) finishSharing("Phone locked. Unlock it, click Resume on the Mac, and approve sharing again. Android ends screen capture when the phone locks.", "locked")
                    else finishSharing("Android stopped screen sharing. Start a new session to continue.")
                }
                override fun onCapturedContentResize(w: Int, h: Int) {
                    if (w > 0 && h > 0 && (w != lastCaptureWidth || h != lastCaptureHeight)) {
                        try { if (display != null) configureCapture(w, h) } catch (e: Exception) { fail(e) }
                    }
                }
            }, worker)
            scope.launch {
                try {
                    val connection = Socket()
                    socket = connection
                    connection.tcpNoDelay = true; connection.keepAlive = true; connection.soTimeout = 15_000
                    connection.connect(InetSocketAddress(request.host, request.offer.port), 5000)
                    val cipher = MirrorCipher(java.util.Base64.getDecoder().decode(request.offer.key), sessionId)
                    launch { writeMedia(connection, cipher) }
                    launch { readControl(connection, cipher) }
                    worker.post {
                        try {
                            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                            configureCapture(bounds.width(), bounds.height())
                            worker.post(drainLoop)
                            MirrorCoordinator.status(this@ScreenCaptureService, sessionId, "streaming", "Screen sharing active")
                            request.offer.packageName?.let { pkg ->
                                if (pkg.isNotBlank() && MirrorAccessibilityService.instance != null) {
                                    MirrorAccessibilityService.instance?.launchApp(sessionId, pkg)
                                } else if (pkg.isNotBlank()) packageManager.getLaunchIntentForPackage(pkg)?.let {
                                    // Accessibility's global actions remain optional; background launch may be declined by Android.
                                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    try { startActivity(it) } catch (_: Exception) {}
                                }
                            }
                        } catch (e: Exception) { fail(e) }
                    }
                } catch (e: Exception) { fail(e) }
            }
        } catch (e: Exception) { fail(e) }
        return START_NOT_STICKY
    }

    private fun configureCapture(captureWidth: Int, captureHeight: Int) {
        if (ending.get()) return
        val settings = offer ?: return
        val scale = minOf(1.0, settings.maxSize.toDouble() / maxOf(captureWidth, captureHeight))
        val w = maxOf(2, (captureWidth * scale).toInt() / 2 * 2)
        val h = maxOf(2, (captureHeight * scale).toInt() / 2 * 2)
        lastCaptureWidth = captureWidth; lastCaptureHeight = captureHeight
        if (w == width && h == height && encoder != null) return
        display?.surface = null
        encoder?.close(); encoder = null
        val next = ScreenEncoder(w, h, settings.bitrate, settings.fps)
        encoder = next; width = w; height = h; codecConfig = null; waitingForKey = false
        outgoing.clear()
        sendMetadata()
        val density = resources.displayMetrics.densityDpi
        if (display == null) {
            display = projection!!.createVirtualDisplay("Droid Link screen", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.inputSurface, null, worker)
            if (audioEnabled) startAudio()
        } else {
            display!!.resize(w, h, density)
            display!!.surface = next.inputSurface
        }
    }

    private fun sendMetadata(message: String? = null) {
        lastControl = MirrorAccessibilityService.instance != null
        metadata = byteArrayOf(2) + gson.toJson(MirrorMetadata(sessionId, width, height, lastControl, audioEnabled, message)).toByteArray()
        if (!outgoing.offer(metadata)) error("Media connection is congested")
    }

    private val drainLoop = object : Runnable {
        override fun run() {
            if (ending.get()) return
            try {
                encoder?.drain { packet ->
                    val bytes = byteArrayOf(1) + packet.encode()
                    if (packet.kind == VideoPacket.CONFIG) codecConfig = bytes
                    if (waitingForKey && packet.kind == VideoPacket.FRAME) return@drain
                    if (packet.kind == VideoPacket.KEY_FRAME) waitingForKey = false
                    if (!outgoing.offer(bytes)) {
                        // Discard the dependency chain, preserve configuration, and resume on an IDR.
                        outgoing.clear(); outgoing.offer(metadata); codecConfig?.let { outgoing.offer(it) }
                        waitingForKey = true
                        encoder?.requestKeyFrame()
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastHealth > 1000) {
                    lastHealth = now
                    if ((MirrorAccessibilityService.instance != null) != lastControl) sendMetadata()
                    if (Build.VERSION.SDK_INT < 34) {
                        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                        configureCapture(bounds.width(), bounds.height())
                    }
                }
                worker.postDelayed(this, 4)
            } catch (e: Exception) { fail(e) }
        }
    }

    private fun writeMedia(connection: Socket, cipher: MirrorCipher) {
        try {
            val output = DataOutputStream(connection.getOutputStream())
            var cryptoNanos = 0L; var cryptoPackets = 0; var peakNanos = 0L
            while (!ending.get()) {
                val message = outgoing.poll(2, TimeUnit.SECONDS) ?: continue
                val started = System.nanoTime()
                val record = cipher.seal(message)
                val elapsed = System.nanoTime() - started
                cryptoNanos += elapsed; peakNanos = maxOf(peakNanos, elapsed); cryptoPackets++
                if (cryptoPackets == 600) {
                    android.util.Log.i("DroidLinkMirror", "AES-GCM seal mean=${cryptoNanos / 600 / 1000}us peak=${peakNanos / 1000}us over 600 media records")
                    cryptoPackets = 0; cryptoNanos = 0; peakNanos = 0
                }
                output.writeInt(record.size); output.write(record); output.flush()
            }
        } catch (e: Exception) { if (!ending.get()) fail(e) }
    }

    private fun readControl(connection: Socket, cipher: MirrorCipher) {
        try {
            val input = DataInputStream(connection.getInputStream())
            while (!ending.get()) {
                val length = input.readInt()
                require(length in 25..65536)
                val record = ByteArray(length); input.readFully(record)
                val message = cipher.open(record)
                require(message.firstOrNull() == 3.toByte())
                val command = JSONObject(String(message, 1, message.size - 1, Charsets.UTF_8))
                when (command.optString("action")) {
                    "ping" -> if (outgoing.remainingCapacity() > 2) outgoing.offer(byteArrayOf(6))
                    "stop" -> finishSharing("Screen sharing stopped on Mac")
                    "keyframe" -> worker.post { encoder?.requestKeyFrame() }
                    "controlSettings" -> MirrorCoordinator.requestControlSettings(this)
                    "launch" -> {
                        val pkg = command.optString("packageName")
                        if (pkg.length in 1..255) MirrorAccessibilityService.instance?.launchApp(sessionId, pkg)
                    }
                    else -> MirrorAccessibilityService.instance?.input(sessionId, command)
                }
            }
        } catch (e: Exception) { if (!ending.get()) fail(e) }
    }

    @Suppress("MissingPermission")
    private fun startAudio() {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val format = AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val audio = AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(maxOf(minimum, 19200)).build()
            recorder = audio
            audio.startRecording()
            scope.launch {
                try {
                    val buffer = ByteArray(1920) // 10ms, 48kHz stereo PCM16 little-endian.
                    while (!ending.get()) {
                        val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) error("Audio capture ended")
                        if (outgoing.remainingCapacity() > 3) outgoing.offer(byteArrayOf(5) + buffer.copyOf(count))
                    }
                } catch (e: Exception) {
                    if (!ending.get()) worker.post { audioEnabled = false; sendMetadata("Phone audio capture stopped; video is still active") }
                }
            }
        } catch (e: Exception) {
            audioEnabled = false
            recorder?.release(); recorder = null
            sendMetadata("Audio is unavailable; video is still active")
        }
    }

    private fun fail(error: Exception) { finishSharing("Screen sharing stopped: ${error.message ?: "connection error"}") }
    private fun finishSharing(message: String, reason: String = "stopped") {
        if (!ending.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            MirrorCoordinator.stop(this, sessionId, message, reason)
            stopSelf()
        }
    }
    override fun onDestroy() {
        ending.set(true)
        try { socket?.close() } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) {}
        scope.cancel()
        worker.removeCallbacks(drainLoop)
        worker.post {
            try { display?.release() } catch (_: Exception) {}
            try { encoder?.close() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            display = null; encoder = null; recorder = null; projection = null
            workerThread.quitSafely()
        }
        MirrorCoordinator.stop(this, sessionId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
