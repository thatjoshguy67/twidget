package com.tjg.twidget.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.om.FabricatedOverlay
import android.content.om.OverlayManager
import android.content.om.OverlayManagerTransaction
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.TwidgetPaletteLoader
import java.util.WeakHashMap

enum class AppPaletteMode(val storedValue: String) {
    SYSTEM("system"),
    TWIDGET_BLUE("twidget_blue"),
    CUSTOM("custom");

    companion object {
        fun fromStored(value: String?): AppPaletteMode =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

data class AppAccentPalette(
    val seed: Int,
    val primaryLight: Int,
    val primaryDark: Int,
    val controlLight: Int,
    val controlDark: Int,
)

data class PaletteApplyResult(
    val success: Boolean,
    val changed: Boolean,
    val error: String? = null,
)

data class PaletteDebugState(
    val mode: AppPaletteMode,
    val supported: Boolean,
    val customPaletteApplied: Boolean,
    val loaderDetails: List<String>,
    val customSeed: Int,
    val lastError: String?,
)

/**
 * Leaves Samsung's system palette overlays in control in SYSTEM mode. Custom
 * accents use an in-process resource table, without declaring overlayable groups:
 * a named overlayable group prevents Samsung's unnamed SESL overlay from loading.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
object AppPaletteManager {
    private const val PREFS = "twidget_app_palette"
    private const val KEY_MODE = "mode"
    private const val KEY_CUSTOM_SEED = "custom_seed"
    private const val KEY_APPLIED_FINGERPRINT = "applied_fingerprint"
    private const val KEY_PENDING_WIDGET_REFRESH = "pending_widget_refresh"
    private const val KEY_LAST_ERROR = "last_error"
    private const val OVERLAY_NAME = "twidget_custom_palette"
    private const val DEFAULT_SEED = 0xFF387AFF.toInt()

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun mode(context: Context): AppPaletteMode =
        AppPaletteMode.fromStored(prefs(context).getString(KEY_MODE, null))

    fun customSeed(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_SEED, DEFAULT_SEED).withOpaqueAlpha()

    fun setCustomSeed(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_CUSTOM_SEED, color.withOpaqueAlpha()).apply()
    }

    fun applySelection(
        context: Context,
        mode: AppPaletteMode,
        customSeed: Int = customSeed(context),
    ): PaletteApplyResult {
        if (mode != AppPaletteMode.SYSTEM && !isSupported) {
            return PaletteApplyResult(
                success = false,
                changed = false,
                error = "Custom palettes require Android 14 or newer.",
            )
        }
        prefs(context).edit()
            .putString(KEY_MODE, mode.storedValue)
            .putInt(KEY_CUSTOM_SEED, customSeed.withOpaqueAlpha())
            .commit()
        return reconcile(context)
    }

    /** Migrates old self-overlays, then applies the saved app-local palette. */
    fun reconcile(context: Context): PaletteApplyResult {
        if (!isSupported) return PaletteApplyResult(success = true, changed = false)
        return runCatching {
            Api34.removeLegacyOverlays(context.applicationContext)
            attachResources(context)
        }.getOrElse { failure(context, it.message ?: it.javaClass.simpleName) }
    }

    /** Attach the custom table to application and activity resources, or remove it for SYSTEM. */
    fun attachResources(context: Context): PaletteApplyResult {
        if (!isSupported) return PaletteApplyResult(success = true, changed = false)
        return runCatching { Api34.attachResources(context, mode(context)) }
            .getOrElse { failure(context, it.message ?: it.javaClass.simpleName) }
    }

    /** Returns true once after an overlay change, including after a process restart. */
    fun consumePendingWidgetRefresh(context: Context): Boolean {
        val store = prefs(context)
        if (!store.getBoolean(KEY_PENDING_WIDGET_REFRESH, false)) return false
        store.edit().remove(KEY_PENDING_WIDGET_REFRESH).commit()
        return true
    }

    fun debugState(context: Context): PaletteDebugState {
        val store = prefs(context)
        val loaderDetails = if (isSupported) Api34.loaderDescriptions() else emptyList()
        return PaletteDebugState(
            mode = mode(context),
            supported = isSupported,
            customPaletteApplied = loaderDetails.isNotEmpty(),
            loaderDetails = loaderDetails,
            customSeed = customSeed(context),
            lastError = store.getString(KEY_LAST_ERROR, null),
        )
    }

    fun generatedPalette(context: Context): AppAccentPalette = when (mode(context)) {
        AppPaletteMode.TWIDGET_BLUE -> twidgetBluePalette()
        AppPaletteMode.CUSTOM -> paletteFromSeed(customSeed(context))
        AppPaletteMode.SYSTEM -> resolvedSystemPalette(context)
    }

    fun resolvedColors(context: Context): List<Pair<String, Int>> = listOf(
        "Twidget accent" to resolveColor(context, "oneui_accent"),
        "SESL primary · light" to resolveColor(context, "sesl_primary_color_light"),
        "SESL primary · dark" to resolveColor(context, "sesl_primary_color_dark"),
        "SESL control · light" to resolveColor(context, "sesl_primary_dark_color_light"),
        "SESL control · dark" to resolveColor(context, "sesl_primary_dark_color_dark"),
        "SESL blue · light" to resolveColor(context, "sesl_blue_color_light"),
        "SESL blue · dark" to resolveColor(context, "sesl_blue_color_dark"),
        "One UI selected text" to resolveColor(context, "oui_des_floating_action_bar_selected_text_color"),
    ).filter { it.second != Color.TRANSPARENT }

    fun colorHex(color: Int): String = String.format("#%08X", color)

    internal fun paletteFromSeed(seed: Int): AppAccentPalette {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seed.withOpaqueAlpha(), hsl)
        val primaryLightness = hsl[2].coerceIn(0.42f, 0.68f)
        val primaryLight = hsl.copyOf().also { it[2] = primaryLightness }.toColor()
        val primaryDark = hsl.copyOf().also {
            it[2] = (primaryLightness + 0.07f).coerceIn(0.58f, 0.76f)
        }.toColor()
        val controlLight = hsl.copyOf().also {
            it[2] = (primaryLightness - 0.08f).coerceIn(0.34f, 0.58f)
        }.toColor()
        val controlDark = hsl.copyOf().also {
            it[2] = (primaryLightness + 0.12f).coerceIn(0.62f, 0.80f)
        }.toColor()
        return AppAccentPalette(
            seed = seed.withOpaqueAlpha(),
            primaryLight = primaryLight,
            primaryDark = primaryDark,
            controlLight = controlLight,
            controlDark = controlDark,
        )
    }

    private fun twidgetBluePalette() = AppAccentPalette(
        seed = DEFAULT_SEED,
        primaryLight = 0xFF387AFF.toInt(),
        primaryDark = 0xFF4DA2FF.toInt(),
        controlLight = 0xFF376FDE.toInt(),
        controlDark = 0xFF598FFF.toInt(),
    )

    private fun resolvedSystemPalette(context: Context) = AppAccentPalette(
        seed = resolveColor(context, "sesl_primary_color_light"),
        primaryLight = resolveColor(context, "sesl_primary_color_light"),
        primaryDark = resolveColor(context, "sesl_primary_color_dark"),
        controlLight = resolveColor(context, "sesl_primary_dark_color_light"),
        controlDark = resolveColor(context, "sesl_primary_dark_color_dark"),
    )

    @Suppress("DiscouragedApi")
    private fun resolveColor(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "color", context.packageName)
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(Color.TRANSPARENT)
        else Color.TRANSPARENT
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Int.withOpaqueAlpha(): Int = this or 0xFF000000.toInt()

    private fun FloatArray.toColor(): Int = ColorUtils.HSLToColor(this).withOpaqueAlpha()

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        private data class LoadedPalette(val fingerprint: String, val loader: ResourcesLoader)
        private var active: LoadedPalette? = null
        private val attachedResources = WeakHashMap<Resources, Boolean>()

        fun removeLegacyOverlays(context: Context) {
            val manager = context.getSystemService(OverlayManager::class.java) ?: return
            val names = manager.getOverlayInfosForTarget(context.packageName)
                .mapNotNull { it.overlayName }
                .filter { it == OVERLAY_NAME || it.startsWith("${OVERLAY_NAME}_") }
            if (names.isEmpty()) return
            val transaction = OverlayManagerTransaction.newInstance()
            names.forEach { name ->
                transaction.unregisterFabricatedOverlay(FabricatedOverlay(name, context.packageName).identifier)
            }
            manager.commit(transaction)
        }

        @Synchronized
        fun loaderDescriptions(): List<String> = active?.let {
            listOf("App-local colour table: ${it.fingerprint}")
        } ?: emptyList()

        @Synchronized
        fun attachResources(context: Context, mode: AppPaletteMode): PaletteApplyResult {
            val palette = when (mode) {
                AppPaletteMode.SYSTEM -> null
                AppPaletteMode.TWIDGET_BLUE -> twidgetBluePalette()
                AppPaletteMode.CUSTOM -> paletteFromSeed(customSeed(context))
            }
            val fingerprint = palette?.let { "local-v1:${mode.storedValue}:${it.seed}" }
            val changed = prefs(context).getString(KEY_APPLIED_FINGERPRINT, null) != fingerprint
            if (active?.fingerprint != fingerprint) {
                // Create the replacement before removing the currently working table.
                val replacement = palette?.let {
                    LoadedPalette(requireNotNull(fingerprint),
                        checkNotNull(TwidgetPaletteLoader.create(context, colorOverrides(context, it))) {
                            "Unable to create the custom palette resource table."
                        })
                }
                active?.loader?.let { loader ->
                    attachedResources.keys.toList().forEach { resources ->
                        resources.removeLoaders(loader)
                    }
                    // Also clears overrides in any configuration context that inherited this loader.
                    val providers = loader.providers.toList()
                    loader.clearProviders()
                    providers.forEach { it.close() }
                }
                attachedResources.clear()
                active = replacement
            }
            active?.loader?.let { loader ->
                listOf(context.applicationContext.resources, context.resources).distinct().forEach { resources ->
                    // addLoaders ignores loaders already attached (including inherited ones).
                    resources.addLoaders(loader)
                    attachedResources[resources] = true
                }
            }
            if (changed) markOverlayChangePending(context, fingerprint) else clearError(context)
            return PaletteApplyResult(success = true, changed = changed)
        }

        @Suppress("DiscouragedApi")
        private fun colorOverrides(context: Context, palette: AppAccentPalette): Map<Int, Int> =
            mapOf(
                "sesl_blue_color_light" to palette.primaryLight,
                "sesl_blue_color_dark" to palette.primaryDark,
                "sesl_blue_dark_color_light" to palette.controlLight,
                "sesl_blue_dark_color_dark" to palette.controlDark,
                "sesl_primary_color_light" to palette.primaryLight,
                "sesl_primary_color_dark" to palette.primaryDark,
                "sesl_primary_dark_color_light" to palette.controlLight,
                "sesl_primary_dark_color_dark" to palette.controlDark,
                "sesl_control_activated_color" to palette.controlLight,
                "oui_des_floating_action_bar_selected_text_color" to palette.primaryLight,
            ).map { (name, color) ->
                val id = context.resources.getIdentifier(name, "color", context.packageName)
                check(id != 0) { "Missing palette colour resource: $name" }
                id to color
            }.toMap()
    }

    private fun failure(context: Context, message: String): PaletteApplyResult {
        prefs(context).edit()
            .remove(KEY_APPLIED_FINGERPRINT)
            .remove(KEY_PENDING_WIDGET_REFRESH)
            .putString(KEY_LAST_ERROR, message)
            .commit()
        return PaletteApplyResult(success = false, changed = false, error = message)
    }

    private fun markOverlayChangePending(context: Context, fingerprint: String?) {
        val editor = prefs(context).edit()
        if (fingerprint == null) editor.remove(KEY_APPLIED_FINGERPRINT)
        else editor.putString(KEY_APPLIED_FINGERPRINT, fingerprint)
        editor
            .putBoolean(KEY_PENDING_WIDGET_REFRESH, true)
            .remove(KEY_LAST_ERROR)
            .commit()
    }

    private fun clearError(context: Context) {
        prefs(context).edit().remove(KEY_LAST_ERROR).apply()
    }
}
