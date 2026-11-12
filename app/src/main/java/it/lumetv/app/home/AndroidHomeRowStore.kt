package it.lumetv.app.home

import android.annotation.TargetApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import it.lumetv.app.MainActivity
import it.lumetv.app.integration.SharedChannel

/** Uses the public platform API; no launcher package, accessibility service or stream URL is involved. */
@TargetApi(26)
internal class AndroidHomeRowStore(private val context: Context) : HomeRowStore {
    companion object { const val ROW_KEY = "lume.home.v1" }
    private val resolver = context.contentResolver
    override fun rows(): List<HomeRow> {
        val columns = arrayOf("_id", "package_name", "type", "internal_provider_id", "browsable")
        val cursor = resolver.query(TvContract.Channels.CONTENT_URI, columns, null, null, null)
            ?: error("Home provider unavailable")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    if (it.getString(1) == context.packageName && it.getString(2) == TvContract.Channels.TYPE_PREVIEW &&
                        it.getString(3) == ROW_KEY) add(HomeRow(it.getLong(0), it.getInt(4) == 1))
                }
            }
        }
    }

    override fun createRow(): HomeRow {
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Lume TV")
            put(TvContract.Channels.COLUMN_DESCRIPTION, "I tuoi canali in diretta, aperti in Lume TV")
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, ROW_KEY)
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, intent("catalog"))
        }
        val uri = requireNotNull(resolver.insert(TvContract.Channels.CONTENT_URI, values))
        val id = ContentUris.parseId(uri)
        return HomeRow(id, false)
    }

    override fun deleteRow(id: Long) { resolver.delete(TvContract.buildChannelUri(id), null, null) }

    override fun programs(rowId: Long): List<HomeProgram> {
        val columns = arrayOf("_id", "channel_id", "package_name", "internal_provider_id", "browsable", "internal_provider_data")
        val cursor = resolver.query(TvContract.buildPreviewProgramsUriForChannel(rowId), columns, null, null, null)
            ?: error("Home programs unavailable")
        return cursor.use {
            buildList {
                while (it.moveToNext()) if (it.getLong(1) == rowId && it.getString(2) == context.packageName) {
                    val bytes = it.getBlob(5)
                    add(HomeProgram(it.getLong(0), it.getString(3).orEmpty(), it.getInt(4) == 1,
                        bytes?.toString(Charsets.UTF_8).orEmpty()))
                }
            }
        }
    }

    override fun writeProgram(rowId: Long, existingId: Long?, channel: SharedChannel, position: Int, fingerprint: String) {
        val poster = HomeArtwork.poster(context, channel)
        val values = ContentValues().apply {
            put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, rowId)
            put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CHANNEL)
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, channel.id)
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_DATA, fingerprint.toByteArray(Charsets.UTF_8))
            put(TvContract.PreviewPrograms.COLUMN_TITLE, channel.name)
            put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, channel.current?.title?.let { "In onda: $it" } ?: "TV in diretta")
            put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, intent("channel/${channel.id}"))
            put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, poster.toString())
            put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9)
            put(TvContract.PreviewPrograms.COLUMN_LIVE, 1)
            put(TvContract.PreviewPrograms.COLUMN_WEIGHT, HomePublicationPlanner.LIMIT - position)
        }
        if (existingId == null) requireNotNull(resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values))
        else check(resolver.update(TvContract.buildPreviewProgramUri(existingId), values, null, null) == 1)
    }

    override fun deleteProgram(id: Long) { resolver.delete(TvContract.buildPreviewProgramUri(id), null, null) }

    private fun intent(destination: String): String = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://$destination"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).toUri(Intent.URI_INTENT_SCHEME)
}
