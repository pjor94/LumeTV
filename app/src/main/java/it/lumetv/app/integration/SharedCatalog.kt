package it.lumetv.app.integration

import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.core.Channel

/** A metadata-only projection. Playback addresses, headers, EPG identifiers and DRM never enter it. */
internal data class SharedChannel(
    val id: String, val name: String, val number: Int?, val family: String?, val logo: String?,
    val favorite: Boolean, val radio: Boolean, val current: Programme?, val next: Programme?,
)

internal data class SharedCatalog(
    val channels: List<SharedChannel>, val catalogUpdatedAt: Long?, val guideUpdatedAt: Long?,
    val lastChannelId: String?, val lastPlayedAt: Long?,
) {
    companion object {
        fun project(channels: List<Channel>, favorites: Set<String>, programmes: Map<String, List<Programme>>,
                    catalogUpdatedAt: Long?, guideUpdatedAt: Long?, lastChannelId: String?, lastPlayedAt: Long?, now: Long): SharedCatalog {
            val rows = channels.distinctBy { it.id }.filter { !it.isPersonal && Regex("[a-f0-9]{24}").matches(it.id) }
                .mapNotNull { channel ->
                    val name = IntegrationContract.displayText(channel.name, 160) ?: return@mapNotNull null
                    val clean = programmes[channel.epgId].orEmpty().mapNotNull { programme ->
                        IntegrationContract.displayText(programme.title, 300)?.let {
                            Programme(it, programme.start, programme.end)
                        }
                    }
                    val window = ProgrammeWindow.at(clean, now)
                    SharedChannel(channel.id, name, channel.number.takeIf { it in 0..999_999 },
                        IntegrationContract.displayText(channel.family, 100), IntegrationContract.safeLogo(channel.logo),
                        channel.id in favorites, channel.isRadio, window.current, window.next)
                }
            return SharedCatalog(rows, catalogUpdatedAt, guideUpdatedAt, lastChannelId, lastPlayedAt)
        }
    }
}
