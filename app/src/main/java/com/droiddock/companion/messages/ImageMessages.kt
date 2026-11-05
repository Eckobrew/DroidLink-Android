package com.droiddock.companion.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.droiddock.companion.network.OutgoingMessageRequest
import java.io.ByteArrayOutputStream
import java.io.File

object ImageMessages {
    private const val MAX_BYTES = 3_000_000

    fun readPreview(context: Context, url: String): String {
        if (url.startsWith("notification-image:")) {
            val key = url.removePrefix("notification-image:")
            require(Regex("[a-f0-9]{64}").matches(key))
            val file = File(File(context.filesDir, "received_images"), "$key.jpg")
            require(file.length() in 1..MAX_BYTES.toLong()) { "Notification image expired" }
            return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }
        val uri = Uri.parse(url)
        require(uri.scheme == "content" && uri.authority == "mms" && Regex("/part/[0-9]+").matches(uri.path ?: ""))
        return previewFromUri(context, uri)
    }

    fun captureNotificationImage(context: Context, uri: Uri, id: String): String {
        require(uri.scheme == "content")
        val preview = previewFromUri(context, uri)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "received_images").apply { mkdirs() }
        File(dir, "$key.jpg").writeBytes(Base64.decode(preview, Base64.NO_WRAP))
        return "notification-image:$key"
    }

    fun captureNotificationBitmap(context: Context, bitmap: Bitmap, id: String): String {
        val scale = minOf(1.0, 1600.0 / maxOf(bitmap.width, bitmap.height))
        val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        val out = ByteArrayOutputStream()
        try { resized.compress(Bitmap.CompressFormat.JPEG, 82, out) }
        finally { if (resized !== bitmap) resized.recycle() }
        require(out.size() <= MAX_BYTES)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "received_images").apply { mkdirs() }
        File(dir, "$key.jpg").writeBytes(out.toByteArray())
        return "notification-image:$key"
    }

    private fun previewFromUri(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytesBounded(12_000_000)
        } ?: error("Image is unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Unable to decode image")
        val out = ByteArrayOutputStream()
        try { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) } finally { bitmap.recycle() }
        require(out.size() <= MAX_BYTES) { "Image is too large" }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun prepareComposer(context: Context, request: OutgoingMessageRequest) {
        require(request.recipient.isNotBlank())
        val encoded = request.imageBase64 ?: error("Image is missing")
        require(encoded.length <= MAX_BYTES * 4 / 3 + 4)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size <= MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..1600 && bounds.outHeight in 1..1600) { "Invalid image dimensions" }
        val dir = File(context.filesDir, "outgoing_images").apply { mkdirs() }
        // Request IDs are wire input, not filesystem paths.
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(request.id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(dir, "$name.jpg").writeBytes(bytes)
        context.getSharedPreferences("image_composer", Context.MODE_PRIVATE).edit()
            .putString("$name.file", "$name.jpg").putString("$name.recipient", request.recipient)
            .putString("$name.text", request.text).apply()
        context.getSharedPreferences("image_composer", Context.MODE_PRIVATE).edit().putString("latest", name).apply()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel("image_composer", "Image messages", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(context, 0, Intent(context, ImageComposeActivity::class.java).apply {
            data = Uri.parse("droiddock://image-composer/$name")
            putExtra("imageId", name)
        },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            try {
                notificationManager.notify(name.hashCode(), NotificationCompat.Builder(context, "image_composer")
                    .setSmallIcon(android.R.drawable.ic_menu_send).setContentTitle("Image ready to send")
                    .setContentText("Tap to finish sending in your messaging app.").setContentIntent(pending)
                    .setAutoCancel(true).build())
            } catch (_: SecurityException) { /* The dashboard also exposes the pending composer. */ }
        }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(out.size() + count <= limit) { "Image is too large" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}

class ImageComposeActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("imageId") ?: getSharedPreferences("image_composer", MODE_PRIVATE).getString("latest", null)
        if (id == null || !Regex("[a-f0-9]{64}").matches(id)) { finish(); return }
        val prefs = getSharedPreferences("image_composer", MODE_PRIVATE)
        val filename = prefs.getString("$id.file", null)
        if (filename == null) { finish(); return }
        val file = File(File(filesDir, "outgoing_images"), filename)
        if (!file.isFile) { finish(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.images", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra("address", prefs.getString("$id.recipient", ""))
            putExtra("sms_body", prefs.getString("$id.text", ""))
            putExtra(Intent.EXTRA_TEXT, prefs.getString("$id.text", ""))
            clipData = android.content.ClipData.newRawUri("Image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            android.provider.Telephony.Sms.getDefaultSmsPackage(this@ImageComposeActivity)?.let { setPackage(it) }
        }
        try { startActivity(send) } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent.createChooser(send.setPackage(null), "Send image"))
        }
        finish()
    }
}
