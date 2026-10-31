package com.tjg.twidget.widget.coloros

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.widget.WidgetConfigActivity
import com.tjg.twidget.widget.WidgetKind

/** ColorOS's fixed shortcut supplies no widget ID, unlike standard reconfiguration. */
class ColorOsWidgetSettingsActivity : EdgeToEdgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ColorOsWidgetSupport.available) { finish(); return }
        val manager = AppWidgetManager.getInstance(this)
        val bound = manager.getAppWidgetIds(ComponentName(this, ColorOsWidgetBlurProbe.PROVIDER)).sorted()
        // A cancelled/crashed configure flow can leave a bound but unplaced ID in
        // this launcher. Required configuration saves settings before placement.
        val prefs = getSharedPreferences(TwidgetStore.PREFS, MODE_PRIVATE)
        val ids = bound.filter { prefs.contains("widget_style_$it") }.ifEmpty { bound }
        fun open(id: Int?) {
            startActivity(Intent(this, WidgetConfigActivity::class.java).apply {
                if (id != null) putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                else putExtra(WidgetConfigActivity.EXTRA_DEFAULT_KIND, WidgetKind.FOLLOWERS.name)
            })
            finish()
        }
        if (ids.size <= 1) { open(ids.firstOrNull()); return }
        val labels = ids.map { id ->
            val settings = TwidgetStore.widgetSettings(this, id)
            val account = settings.accountUsername.ifBlank { TwidgetStore.settings(this).username }
            getString(R.string.coloros_widget_instance, id, account)
        }
        AlertDialog.Builder(this).setTitle(R.string.coloros_choose_widget)
            .setItems(labels.toTypedArray()) { _, index -> open(ids[index]) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
