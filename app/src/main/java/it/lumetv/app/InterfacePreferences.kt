package it.lumetv.app

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import kotlin.math.min

enum class InterfaceMode(val label: String) {
    AUTOMATIC("Automatica"), SMARTPHONE("Smartphone"), TV("TV");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}

/** Device policy, independent of the current app window and its orientation. */
object InterfacePolicy {
    fun resolve(preference: InterfaceMode, television: Boolean, touchscreen: Boolean, displayShortSideDp: Int): InterfaceMode =
        when {
            preference != InterfaceMode.AUTOMATIC -> preference
            television -> InterfaceMode.TV
            touchscreen && displayShortSideDp in 1 until 600 -> InterfaceMode.SMARTPHONE
            else -> InterfaceMode.TV
        }
}

object InterfacePreferences {
    private fun preferences(context: Context) = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    fun selected(context: Context) = InterfaceMode.fromStored(preferences(context).getString("mode", null))

    @Suppress("DEPRECATION")
    fun resolve(context: Context): InterfaceMode {
        val preference = selected(context)
        if (preference != InterfaceMode.AUTOMATIC) return preference
        val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val television = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            manager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // Maximum display bounds keep a tablet in the same profile in split screen.
        val shortSide = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds.let { min(it.width(), it.height()) }
        } else {
            Point().also { windowManager.defaultDisplay.getRealSize(it) }.let { min(it.x, it.y) }
        }
        return InterfacePolicy.resolve(preference, television,
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            (shortSide / context.resources.displayMetrics.density).toInt())
    }

    fun choose(activity: Activity, changed: () -> Unit) {
        val previous = selected(activity)
        AlertDialog.Builder(activity).setTitle("Interfaccia")
            .setSingleChoiceItems(InterfaceMode.entries.map { it.label }.toTypedArray(), previous.ordinal) { dialog, index ->
                val next = InterfaceMode.entries[index]
                dialog.dismiss()
                if (next != previous) {
                    preferences(activity).edit().putString("mode", next.name).apply()
                    changed()
                }
            }.setNegativeButton("Annulla", null).show()
    }
}
