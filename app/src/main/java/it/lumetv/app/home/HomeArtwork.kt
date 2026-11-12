package it.lumetv.app.home

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.tv.TvContract
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import it.lumetv.app.R
import it.lumetv.app.integration.SharedChannel
import it.lumetv.app.ui.TvStyle
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Offline title cards use the incumbent palette and, when already cached, the channel's real logo. */
internal object HomeArtwork {
    const val AUTHORITY = "it.lumetv.app.home.artwork"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun directory(context: Context) = File(context.filesDir, "home-artwork").apply { mkdirs() }
    fun key(channel: SharedChannel) = "${channel.id}-${hash(channel.name + "\u0000" + channel.number + "\u0000" + channel.logo).take(16)}.png"

    fun poster(context: Context, channel: SharedChannel): Uri {
        val file = AtomicFile(File(directory(context), key(channel)))
        if (!file.baseFile.exists()) {
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(TvStyle.background)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = TvStyle.accent
            canvas.drawRect(36f, 38f, 76f, 42f, paint)
            val brand = context.getDrawable(R.drawable.lume_wordmark)
            brand?.setBounds(36, 64, 164, 110); brand?.draw(canvas)
            paint.color = TvStyle.muted; paint.textSize = 25f
            canvas.drawText("IN DIRETTA", 36f, 154f, paint)
            val cachedLogo = channel.logo?.let { File(context.filesDir, "channel-artwork/${hash(it)}.image") }
            val logo = cachedLogo?.takeIf { it.isFile && it.length() <= 2 * 1024 * 1024 }?.let {
                runCatching { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull()
            }
            if (logo != null) {
                val scale = minOf(100f / logo.width, 64f / logo.height)
                val width = logo.width * scale; val height = logo.height * scale
                canvas.drawBitmap(logo, null, RectF(640 - 36f - width, 60f, 604f, 60f + height), null)
                logo.recycle()
            }
            paint.color = TvStyle.white; paint.textSize = 42f; paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            val layout = android.text.StaticLayout.Builder.obtain(channel.name, 0, channel.name.length,
                android.text.TextPaint(paint), 568).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            canvas.save(); canvas.translate(36f, 202f); layout.draw(canvas); canvas.restore()
            val output = file.startWrite()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
            finally { bitmap.recycle() }
        }
        return Uri.parse("content://$AUTHORITY/poster/${file.baseFile.name}")
    }

    fun rowLogo(context: Context, id: Long) {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(TvStyle.background)
        context.getDrawable(R.drawable.ic_lume)?.apply { setBounds(24, 24, 136, 136); draw(canvas) }
        try {
            context.contentResolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } ?: error("Home logo unavailable")
        } finally { bitmap.recycle() }
    }

    fun prune(context: Context, retained: Set<String>) {
        directory(context).listFiles().orEmpty().filter { it.name !in retained }.forEach { it.delete() }
    }

    fun file(context: Context, name: String) = File(directory(context), name)
}

/** The only publicly readable files are the currently consented, generated Home posters. */
class HomeArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String { name(uri); return "image/png" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = synchronized(HomeChannels.lock) {
        val app = requireNotNull(context)
        val name = name(uri)
        if (mode != "r" || !HomeChannels.configuration(app).enabled || name !in HomeChannels.artworkNames(app))
            throw FileNotFoundException("Home artwork unavailable")
        ParcelFileDescriptor.open(HomeArtwork.file(app, name), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun name(uri: Uri): String {
        val segments = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != HomeArtwork.AUTHORITY || uri.query != null || uri.fragment != null ||
            segments.size != 2 || segments[0] != "poster" || !Regex("[a-f0-9]{24}-[a-f0-9]{16}\\.png").matches(segments[1]))
            throw FileNotFoundException("Home artwork unavailable")
        return segments[1]
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = denied()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = denied()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = denied()
    private fun denied(): Nothing = throw UnsupportedOperationException("Read-only Home artwork")
}
