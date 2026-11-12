package it.lumetv.app.data

import it.lumetv.core.PlaylistEntry
import it.lumetv.core.PlaylistContentKind

internal class PersonalEntrySnapshot(val source:PersonalPlaylistSource,val settings:PlaylistSettings,val entry:PlaylistEntry,
    val context:PersonalRequestContext,val content:PlaylistContentKind) {
    override fun toString()="PersonalEntrySnapshot(content=$content)"
}
