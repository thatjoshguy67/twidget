package com.tjg.twidget.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.Preference
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetTheme
import dev.oneuiproject.oneui.R as OneUiIconR

/**
 * Preference row that opens a dialog with accent swatches, matching the
 * Active source pattern used elsewhere in Settings.
 */
class AccentColorPreference(context: Context) : Preference(context) {
    private var accentColor = TwidgetStore.appAccentColor(context)

    init {
        isIconSpaceReserved = false
        updateSummary()
    }

    override fun onClick() {
        showPickerDialog()
    }

    fun refreshFromStore() {
        accentColor = TwidgetStore.appAccentColor(context)
        updateSummary()
    }

    private fun updateSummary() {
        summary = TwidgetTheme.accentLabel(context, accentColor)
    }

    private fun showPickerDialog() {
        val dialogView = AccentPickerView(context, accentColor) { selected ->
            if (selected == accentColor) return@AccentPickerView
            accentColor = selected
            TwidgetStore.saveAppAccentColor(context, selected)
            updateSummary()
            TwidgetTheme.publishChange(context.applicationContext)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.accent_color_section)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class AccentPickerView(
        context: Context,
        initialAccent: String,
        private val onSelected: (String) -> Unit,
    ) : HorizontalScrollView(context) {
        private var selectedAccent = initialAccent
        private val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            rebuild()
        }

        private fun rebuild() {
            row.removeAllViews()
            TwidgetTheme.ACCENT_OPTIONS.forEach { accent ->
                row.addView(
                    accentChip(accent),
                    LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(8)
                    },
                )
            }
        }

        private fun accentChip(accent: String): LinearLayout {
            val selected = selectedAccent == accent
            val swatchColor = TwidgetTheme.ACCENT_HEX[accent] ?: TwidgetTheme.accent(context)
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(if (selected) TwidgetTheme.cardBackground(context) else 0)
                }
                setOnClickListener {
                    if (selectedAccent == accent) return@setOnClickListener
                    selectedAccent = accent
                    rebuild()
                    onSelected(accent)
                }

                val swatchContainer = FrameLayout(context).apply {
                    background = ShapeDrawable(OvalShape()).apply { paint.color = swatchColor }
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    if (selected) {
                        ContextCompat.getDrawable(context, OneUiIconR.drawable.ic_oui_checkbox_checked)?.mutate()?.let { check ->
                            DrawableCompat.setTint(check, 0xFFFFFFFF.toInt())
                            addView(ImageView(context).apply {
                                setImageDrawable(check)
                                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply {
                                    gravity = Gravity.CENTER
                                }
                            })
                        }
                    }
                }
                addView(swatchContainer)

                addView(TextView(context).apply {
                    text = TwidgetTheme.accentLabel(context, accent)
                    textSize = 14f
                    setTextColor(TwidgetTheme.textPrimary(context))
                    setPadding(0, dp(8), 0, 0)
                    gravity = Gravity.CENTER
                })
            }
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()
    }
}
