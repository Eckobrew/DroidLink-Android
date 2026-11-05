package com.droiddock.companion.mirroring

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.Closeable

/** Surface encoder for a future MediaProjection VirtualDisplay. Owned by one worker thread. */
class ScreenEncoder(width: Int, height: Int, bitrate: Int = 4_000_000, fps: Int = 60) : Closeable {
    private val codec: MediaCodec
    val inputSurface: Surface
    private var closed = false

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(bitrate > 0 && fps in 1..60)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            try {
                codec.start()
            } catch (error: Exception) {
                inputSurface.release()
                throw error
            }
        } catch (error: Exception) {
            codec.release()
            throw error
        }
    }

    /** Poll on a worker thread. Consumer must enqueue promptly without blocking on network I/O. */
    fun drain(consume: (VideoPacket) -> Unit) {
        check(!closed)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index < 0) return
            try {
                if (info.size > 0) {
                    require(info.size <= VideoPacket.MAX_PAYLOAD)
                    val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val payload = ByteArray(info.size)
                    buffer.get(payload)
                    val kind = when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> VideoPacket.CONFIG
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> VideoPacket.KEY_FRAME
                        else -> VideoPacket.FRAME
                    }
                    consume(VideoPacket(kind, info.presentationTimeUs.coerceAtLeast(0), payload))
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { codec.stop() } finally {
            try { codec.release() } finally { inputSurface.release() }
        }
    }
}
