package it.lumetv.app.data

import org.json.JSONObject
import java.io.Closeable
import java.io.File

internal class BackupPlaylist(val file:File,val stored:PlaylistStored) {
    override fun toString()="BackupPlaylist(count="+stored.source.count+")"
}
internal enum class BackupCategory { PLAYLISTS, PERSONAL, WATCH, PREFERENCES }
internal data class BackupSelection(val categories:Set<BackupCategory>,val playlistIds:Set<String>?=null) {
    operator fun contains(category:BackupCategory)=category in categories
    companion object {val ALL=BackupSelection(BackupCategory.entries.toSet())}
}
internal data class BackupWatch(val source:String,val target:it.lumetv.core.PlaybackTarget,val value:WatchPosition,val updated:Long)
internal class BackupPreview(
    val directory:File,val created:Long,val playlists:List<BackupPlaylist>,
    val personal:JSONObject,val watches:List<BackupWatch>,val preferences:JSONObject,private val cleaned:()->Unit={},
) : Closeable {
    var closed=false;private set
    private var readers=0
    private var disposed=false
    val favorites get()=personal.getJSONArray("favorites").length()
    val hidden get()=personal.getJSONObject("layout").getJSONArray("hidden").length()
    val savedSeries get()=personal.getJSONArray("series").length()
    @Synchronized fun acquire():Boolean {if(closed)return false;readers++;return true}
    @Synchronized fun release(){readers--;if(closed && readers==0)dispose()}
    @Synchronized override fun close(){closed=true;if(readers==0)dispose()}
    private fun dispose(){if(!disposed){disposed=true;directory.deleteRecursively();cleaned()}}
    override fun toString()="BackupPreview(playlists="+playlists.size+")"
}
internal data class BackupRestoreResult(val playlists:Int,val keptPlaylists:Int,val favorites:Int,val watch:Int,val skipped:Int,val preferences:Boolean)
internal data class BackupReference(val source:String,val kind:it.lumetv.core.MediaKind,val content:it.lumetv.core.PlaylistContentKind)
