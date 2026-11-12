package it.lumetv.core

/** Source and group filters are independent from translated navigation labels. */
object CatalogSelection {
    fun channels(channels: List<Channel>, sourceId: String, group: String? = null): List<Channel> = channels.filter {
        (sourceId == "all" || sourceId == "builtin" && !it.isPersonal || it.sourceId == sourceId) &&
            (group == null || group in it.groups)
    }
}
