package it.lumetv.app.ui

import android.content.Context
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import it.lumetv.app.playback.*
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle
import androidx.media3.common.C

/** In-window playback settings. One surface and one service for touch, D-pad and radio. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@android.annotation.SuppressLint("ViewConstructor") // Created in the Activity; never inflated from XML.
class PlaybackOptionsPanel(context: Context, private val mobile: Boolean, private val player: TvPlayer,
    private val closed: () -> Unit = {}) : FrameLayout(context) {
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = TvStyle.rounded(TvStyle.surface, context.dp(16).toFloat())
        isClickable = true
        visibility = GONE
    }
    private val heading = TvStyle.text(context, "Opzioni riproduzione", 24f, bold = true).apply {
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(16), 0, context.dp(16), context.dp(16)) }
    private val scroll = ScrollView(context).apply { isSaveEnabled = false; clipToPadding = false; addView(body, LayoutParams(-1, -2)) }
    private val rows = linkedMapOf<String, OptionsRowView>()
    private val previousAccessibility = linkedMapOf<View, Int>()
    private var previousFocus: View? = null
    private var page: String? = null
    private var state = player.snapshot
    private var pageRevision = 0L
    private var liveAppearance = false
    var isShowing = false
        private set
    private val backButton = TvStyle.button(context, "Indietro") { back() }

    init {
        visibility = GONE
        isClickable = true
        setBackgroundColor(0x80000000.toInt())
        setOnClickListener { hide() }
        ViewCompat.setAccessibilityPaneTitle(this, "Opzioni riproduzione")
        val header = if (mobile) MobileStyle.pageHeader(context, heading, "Indietro") { back() }
        else LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(20), context.dp(12), context.dp(12), context.dp(12))
            addView(heading, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = context.dp(12) })
            addView(backButton, LinearLayout.LayoutParams(-2, -2))
        }
        card.addView(header, LinearLayout.LayoutParams(-1, -2))
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(card, LayoutParams(-1, -1, if (mobile) Gravity.BOTTOM else Gravity.END))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val margin = context.dp(if (liveAppearance) 24 else 16)
        (card.layoutParams as LayoutParams).apply {
            width = when {
                mobile -> -1
                liveAppearance -> minOf((w - margin * 2).coerceAtLeast(0), context.dp(414), maxOf(context.dp(310), (w * .36).toInt()))
                else -> minOf(context.dp(460), (w - margin * 2).coerceAtLeast(0))
            }
            height = when {
                mobile -> (h * if (resources.configuration.fontScale > 1.3f) .95f else .85f).toInt()
                liveAppearance -> minOf((h - margin * 2).coerceAtLeast(0), context.dp(418))
                else -> (h - margin * 2).coerceAtLeast(0)
            }
            gravity = if (mobile) Gravity.BOTTOM else Gravity.END or Gravity.CENTER_VERTICAL
            val gutter = if (mobile) 0 else margin
            setMargins(gutter, 0, gutter, 0)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun show(live: Boolean = false) {
        if (player.snapshot.channel == null) return
        val reopening = !isShowing
        if (reopening) {
            previousFocus = rootView.findFocus()
            (parent as? ViewGroup)?.let { parent -> for (index in 0 until parent.childCount) {
                val view = parent.getChildAt(index)
                if (view !== this) { previousAccessibility[view] = view.importantForAccessibility; view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            } }
        }
        val wasLive = liveAppearance
        liveAppearance = live && !mobile
        if (!mobile && wasLive != liveAppearance) applyAppearance()
        isShowing = true
        isClickable = true
        visibility = VISIBLE
        card.descendantFocusability = FOCUS_AFTER_DESCENDANTS
        if (liveAppearance) LiveOverlayStyle.reveal(card, fromX = context.dp(22))
        else {
            card.animate().cancel()
            card.visibility = VISIBLE
            card.alpha = 1f
            card.translationX = 0f
        }
        bringToFront()
        state = player.snapshot
        main()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        isClickable = false
        page = null
        card.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        card.clearFocus()
        LiveOverlayStyle.conceal(card, toX = context.dp(22), animated = liveAppearance) { visibility = GONE }
        previousAccessibility.forEach { (view, value) -> view.importantForAccessibility = value }
        previousAccessibility.clear()
        previousFocus?.takeIf { it.isShown }?.requestFocus(); previousFocus = null
        closed()
    }

    private fun applyAppearance() {
        setBackgroundColor(if (liveAppearance) 0x00000000 else 0x80000000.toInt())
        card.background = if (liveAppearance) LiveOverlayStyle.material(context)
            else TvStyle.rounded(TvStyle.surface, context.dp(16).toFloat())
        heading.textSize = if (liveAppearance) 21f else 24f
        backButton.background = if (liveAppearance) LiveOverlayStyle.focusBackground(context, radius = 20)
            else TvStyle.focusBackground(context)
        fun colorBack(focused: Boolean) = if (liveAppearance) TvStyle.white else if (focused) TvStyle.background else TvStyle.muted
        backButton.setTextColor(colorBack(backButton.hasFocus()))
        backButton.setOnFocusChangeListener { view, focused ->
            backButton.setTextColor(colorBack(focused))
            TvStyle.focusMotion(view, focused, if (liveAppearance) 1.015f else 1.035f)
        }
        val gutter = context.dp(if (liveAppearance) 12 else 16)
        body.setPadding(gutter, 0, gutter, gutter)
        body.clipChildren = !liveAppearance
        body.clipToPadding = !liveAppearance
    }

    fun back(): Boolean {
        if (!isShowing) return false
        if (page != null) { val from = page; main(from) } else hide()
        return true
    }

    override fun focusSearch(focused: View?, direction: Int): View? =
        if (isShowing) FocusFinder.getInstance().findNextFocus(card, focused, direction) ?: focused else super.focusSearch(focused, direction)

    fun render(value: PlaybackSnapshot) {
        state = value
        if (!isShowing) return
        if (value.channel == null) { hide(); return }
        if (page != null && pageRevision != value.options.revision) { main(page); return }
        if (page == null) bindMain()
    }

    private fun row(key: String, label: String, summary: String = "", action: () -> Unit): OptionsRowView =
        OptionsRowView(context, mobile, label, summary, action).also {
            it.tag = key
            if (liveAppearance) it.useLiveAppearance(hasSubmenu = page == null)
            body.addView(it, LinearLayout.LayoutParams(-1, -2).apply {
                if (liveAppearance) topMargin = context.dp(if (key == "audioLanguage") 7 else 3)
            })
            rows[key] = it
            if (mobile) body.addView(OptionsRowView.divider(context), LinearLayout.LayoutParams(-1, context.dp(1)))
        }

    private fun reset(title: String) { heading.text = title; ViewCompat.setAccessibilityPaneTitle(this, title); body.removeAllViews(); rows.clear(); scroll.scrollTo(0, 0) }

    private fun focus(key: String? = null) {
        val target = rows[key] ?: rows.values.firstOrNull()
        target?.post { if (isShowing && target.isAttachedToWindow) target.requestFocus() }
    }

    private fun main(focusKey: String? = null) {
        page = null
        reset("Opzioni riproduzione")
        row("audio", "Audio") { tracks("audio", C.TRACK_TYPE_AUDIO) }
        if (!state.channel!!.isRadio) {
            row("subtitles", "Sottotitoli") { tracks("subtitles", C.TRACK_TYPE_TEXT) }
            row("quality", "Qualità video") { tracks("quality", C.TRACK_TYPE_VIDEO) }
        }
        row("timer", "Timer di arresto") { timer() }
        row("audioLanguage", "Lingua audio preferita") { languages(false) }
        if (!state.channel!!.isRadio) row("subtitleLanguage", "Sottotitoli preferiti") { languages(true) }
        bindMain()
        focus(focusKey)
    }

    private fun bindMain() {
        val value = state.options
        fun bind(key: String, summary: String) { rows[key]?.let { if (it.summaryView.text.toString() != summary) it.bind(it.label, summary) } }
        bind("audio", value.audio.filter { it.selected }.joinToString(" · ") { it.label }.ifBlank { "Le tracce saranno disponibili dopo il caricamento" })
        bind("subtitles", if (value.subtitlesDisabled) "Disattivati" else value.subtitles.filter { it.selected }.joinToString(" · ") { it.label }.ifBlank { "Nessuna traccia attiva" })
        bind("quality", listOfNotNull(value.videoFormat.takeIf { it.isNotBlank() }?.let { "$it in riproduzione" },
            if (value.videoAutomatic) value.preferences.quality.label else "Scelta per questo contenuto").joinToString(" · "))
        bind("timer", sleepTimerLabel(value.sleepRemaining))
        bind("audioLanguage", playbackLanguageLabel(value.preferences.audioLanguage))
        bind("subtitleLanguage", when (value.preferences.subtitleMode) {
            SubtitleMode.SYSTEM -> "Come da sistema"
            SubtitleMode.OFF -> "Disattivati"
            SubtitleMode.LANGUAGE -> playbackLanguageLabel(value.preferences.subtitleLanguage)
        })
    }

    private data class Choice(val label: String, val summary: String = "", val action: () -> Unit)
    private fun choices(key: String, title: String, explanation: String, choices: List<Choice>) {
        page = key; pageRevision = state.options.revision
        reset(title)
        if (explanation.isNotBlank()) body.addView(TvStyle.text(context, explanation, 14f, TvStyle.muted),
            LinearLayout.LayoutParams(-1, -2).apply { leftMargin = context.dp(8); rightMargin = context.dp(8); bottomMargin = context.dp(12) })
        choices.forEachIndexed { index, choice -> row("choice-$index", choice.label, choice.summary) { choice.action(); state = player.snapshot; if (isShowing) main(key) } }
        focus()
    }

    private fun tracks(key: String, type: Int) {
        val value = state.options
        val tracks = when (type) { C.TRACK_TYPE_AUDIO -> value.audio; C.TRACK_TYPE_TEXT -> value.subtitles; else -> value.video }
        val title = when (type) { C.TRACK_TYPE_AUDIO -> "Audio"; C.TRACK_TYPE_TEXT -> "Sottotitoli"; else -> "Qualità video" }
        val options = mutableListOf<Choice>()
        options += Choice(if (type == C.TRACK_TYPE_VIDEO) "Automatica" else "Usa preferenza salvata", "Si adatta alle tracce disponibili") {
            player.automaticTrack(type)
            if (type == C.TRACK_TYPE_VIDEO) player.setPlaybackPreferences(player.snapshot.options.preferences.copy(quality = PlaybackQuality.AUTO))
        }
        if (type == C.TRACK_TYPE_TEXT) options += Choice("Disattivati", "Preferenza salvata per i prossimi contenuti") {
            player.automaticTrack(type)
            player.setPlaybackPreferences(player.snapshot.options.preferences.copy(subtitleMode = SubtitleMode.OFF))
        }
        if (type == C.TRACK_TYPE_VIDEO) options += Choice("Risparmio dati", "Preferenza salvata · Dipende dalle qualità offerte dalla sorgente") {
            player.automaticTrack(type)
            player.setPlaybackPreferences(player.snapshot.options.preferences.copy(quality = PlaybackQuality.DATA_SAVER))
        }
        options += tracks.map { track -> Choice(track.label, if (track.selected) "In uso · Solo per questo contenuto" else "Solo per questo contenuto") {
            player.chooseTrack(value.revision, type, track.key)
        } }
        choices(key, title, if (tracks.isEmpty()) "Questa sorgente non offre tracce selezionabili al momento." else
            "Le scelte specifiche valgono fino alla riapertura o al cambio di contenuto.", options)
    }

    private fun languages(subtitles: Boolean) {
        fun set(language: String, mode: SubtitleMode = SubtitleMode.LANGUAGE) {
            val old = player.snapshot.options.preferences
            player.automaticTrack(if (subtitles) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO)
            player.setPlaybackPreferences(if (subtitles) old.copy(subtitleMode = mode, subtitleLanguage = language) else old.copy(audioLanguage = language))
        }
        val options = mutableListOf(Choice("Come da sistema") { set("", SubtitleMode.SYSTEM) })
        if (subtitles) options += Choice("Disattivati") { set("", SubtitleMode.OFF) }
        options += listOf("it", "en", "fr", "de", "es", "pt", "ar", "ja", "zh", "ru").map { language ->
            Choice(playbackLanguageLabel(language)) { set(language) }
        }
        choices(if (subtitles) "subtitleLanguage" else "audioLanguage", if (subtitles) "Sottotitoli preferiti" else "Lingua audio preferita",
            "La preferenza resta salvata. Verrà usata quando il contenuto offre una traccia compatibile.", options)
    }

    private fun timer() {
        val options = mutableListOf(Choice("Disattivato") { player.setSleepTimer(0) })
        options += listOf(15, 30, 60, 90).map { minutes -> Choice("$minutes minuti") { player.setSleepTimer(minutes) } }
        choices("timer", "Timer di arresto", "Ferma la riproduzione anche a schermo spento. Il tempo continua durante pause e cambi di canale. Interrompi annulla il timer.", options)
    }
}
