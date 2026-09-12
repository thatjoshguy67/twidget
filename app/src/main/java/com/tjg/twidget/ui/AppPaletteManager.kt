package com.tjg.twidget.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.om.FabricatedOverlay
import android.content.om.OverlayManager
import android.content.om.OverlayManagerTransaction
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
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
    val overlayRegistered: Boolean,
    val overlayDetails: List<String>,
    val customSeed: Int,
    val lastError: String?,
)

/**
 * Owns Twidget's optional self-targeted runtime resource overlay.
 *
 * Samsung's theming metadata remains the source for [AppPaletteMode.SYSTEM].
 * Android 14's public fabricated-overlay API supplies the two app-owned modes
 * without modifying the device palette or any other package.
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
    private const val OVERLAYABLE_NAME = "TwidgetPalette"
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
                error = "Custom resource overlays require Android 14 or newer.",
            )
        }
        prefs(context).edit()
            .putString(KEY_MODE, mode.storedValue)
            .putInt(KEY_CUSTOM_SEED, customSeed.withOpaqueAlpha())
            .commit()
        return reconcile(context)
    }

    /** Ensures a persisted custom overlay survives process starts and updates. */
    fun reconcile(context: Context): PaletteApplyResult {
        if (!isSupported) return PaletteApplyResult(success = true, changed = false)
        val overlayResult = Api34.reconcile(
            context.applicationContext,
            mode(context),
            customSeed(context),
        )
        if (!overlayResult.success) return overlayResult
        val loaderResult = attachResources(context)
        return if (loaderResult.success) overlayResult else loaderResult.copy(changed = overlayResult.changed)
    }

    /** Adds the persisted self-overlay to this context's Resources instance. */
    fun attachResources(context: Context): PaletteApplyResult {
        if (!isSupported) return PaletteApplyResult(success = true, changed = false)
        return Api34.attachResources(context, mode(context))
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
        val overlayDetails = if (isSupported) Api34.overlayDescriptions(context) else emptyList()
        return PaletteDebugState(
            mode = mode(context),
            supported = isSupported,
            overlayRegistered = overlayDetails.isNotEmpty(),
            overlayDetails = overlayDetails,
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
        private data class LoadedOverlay(
            val fingerprint: String,
            val loader: ResourcesLoader,
            val provider: ResourcesProvider,
        )

        private val loadedOverlays = WeakHashMap<Resources, LoadedOverlay>()

        fun reconcile(
            context: Context,
            mode: AppPaletteMode,
            customSeed: Int,
        ): PaletteApplyResult {
            val manager = context.getSystemService(OverlayManager::class.java)
                ?: return failure(context, "Android's overlay manager is unavailable.")
            val registeredNames = registeredOverlayNames(manager, context.packageName)
            if (mode == AppPaletteMode.SYSTEM) {
                if (registeredNames.isEmpty()) {
                    clearAppliedState(context)
                    return PaletteApplyResult(success = true, changed = false)
                }
                return runCatching {
                    val transaction = OverlayManagerTransaction.newInstance()
                    registeredNames.forEach { name ->
                        transaction.unregisterFabricatedOverlay(overlayIdentifier(context, name))
                    }
                    markOverlayChangePending(context, null)
                    manager.commit(transaction)
                    PaletteApplyResult(success = true, changed = true)
                }.getOrElse { failure(context, it.message ?: it.javaClass.simpleName) }
            }

            val palette = if (mode == AppPaletteMode.TWIDGET_BLUE) {
                twidgetBluePalette()
            } else {
                paletteFromSeed(customSeed)
            }
            val fingerprint = listOf(
                mode.storedValue,
                palette.seed,
                palette.primaryLight,
                palette.primaryDark,
                palette.controlLight,
                palette.controlDark,
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode,
            ).joinToString(":")
            val applied = prefs(context).getString(KEY_APPLIED_FINGERPRINT, null)
            if (registeredNames.isNotEmpty() && applied == fingerprint) {
                return PaletteApplyResult(success = true, changed = false)
            }

            return runCatching {
                val overlay = buildOverlay(context, palette)
                val transaction = OverlayManagerTransaction.newInstance()
                registeredNames.filter { it != OVERLAY_NAME }.forEach { name ->
                    transaction.unregisterFabricatedOverlay(overlayIdentifier(context, name))
                }
                transaction.registerFabricatedOverlay(overlay)
                markOverlayChangePending(context, fingerprint)
                manager.commit(transaction)
                PaletteApplyResult(success = true, changed = true)
            }.getOrElse { failure(context, it.message ?: it.javaClass.simpleName) }
        }

        fun overlayDescriptions(context: Context): List<String> {
            val manager = context.getSystemService(OverlayManager::class.java) ?: return emptyList()
            return runCatching {
                manager.getOverlayInfosForTarget(context.packageName)
                    .filter { isOwnedOverlayName(it.overlayName) }
                    .map { it.toString() }
            }.getOrDefault(emptyList())
        }

        fun attachResources(context: Context, mode: AppPaletteMode): PaletteApplyResult {
            val resources = context.resources
            val appliedFingerprint = prefs(context).getString(KEY_APPLIED_FINGERPRINT, null)
            synchronized(loadedOverlays) {
                val current = loadedOverlays[resources]
                if (mode != AppPaletteMode.SYSTEM &&
                    appliedFingerprint != null &&
                    current?.fingerprint == appliedFingerprint
                ) {
                    return PaletteApplyResult(success = true, changed = false)
                }

                current?.let { loaded ->
                    resources.removeLoaders(loaded.loader)
                    runCatching { loaded.provider.close() }
                    loadedOverlays.remove(resources)
                }
                if (mode == AppPaletteMode.SYSTEM) {
                    clearError(context)
                    return PaletteApplyResult(success = true, changed = current != null)
                }

                val manager = context.getSystemService(OverlayManager::class.java)
                    ?: return loaderFailure(context, "Android's overlay manager is unavailable.")
                val info = manager.getOverlayInfosForTarget(context.packageName)
                    .firstOrNull { isOwnedOverlayName(it.overlayName) }
                    ?: return loaderFailure(context, "The custom palette overlay is not registered.")
                return runCatching {
                    val provider = ResourcesProvider.loadOverlay(info)
                    val loader = ResourcesLoader().apply { addProvider(provider) }
                    resources.addLoaders(loader)
                    loadedOverlays[resources] = LoadedOverlay(
                        fingerprint = appliedFingerprint.orEmpty(),
                        loader = loader,
                        provider = provider,
                    )
                    clearError(context)
                    PaletteApplyResult(success = true, changed = true)
                }.getOrElse { error ->
                    loaderFailure(context, error.message ?: error.javaClass.simpleName)
                }
            }
        }

        private fun registeredOverlayNames(manager: OverlayManager, packageName: String): List<String> =
            manager.getOverlayInfosForTarget(packageName)
                .mapNotNull { it.overlayName }
                .filter(::isOwnedOverlayName)

        private fun isOwnedOverlayName(name: String?): Boolean =
            name == OVERLAY_NAME || name?.startsWith("${OVERLAY_NAME}_") == true

        private fun buildOverlay(
            context: Context,
            palette: AppAccentPalette,
        ): FabricatedOverlay =
            FabricatedOverlay(OVERLAY_NAME, context.packageName).apply {
                setTargetOverlayable(OVERLAYABLE_NAME)
                addColor("sesl_blue_color_light", palette.primaryLight)
                addColor("sesl_blue_color_dark", palette.primaryDark)
                addColor("sesl_blue_dark_color_light", palette.controlLight)
                addColor("sesl_blue_dark_color_dark", palette.controlDark)
                addColor("sesl_primary_color_light", palette.primaryLight)
                addColor("sesl_primary_color_dark", palette.primaryDark)
                addColor("sesl_primary_dark_color_light", palette.controlLight)
                addColor("sesl_primary_dark_color_dark", palette.controlDark)
                addColor("sesl_control_activated_color", palette.controlLight)
                addColor("oui_des_floating_action_bar_selected_text_color", palette.primaryLight)
            }

        private fun FabricatedOverlay.addColor(name: String, color: Int) {
            setResourceValue(
                "color/$name",
                TypedValue.TYPE_INT_COLOR_ARGB8,
                color,
                null,
            )
        }

        private fun overlayIdentifier(context: Context, overlayName: String) =
            FabricatedOverlay(overlayName, context.packageName).identifier
    }

    private fun failure(context: Context, message: String): PaletteApplyResult {
        prefs(context).edit()
            .remove(KEY_APPLIED_FINGERPRINT)
            .remove(KEY_PENDING_WIDGET_REFRESH)
            .putString(KEY_LAST_ERROR, message)
            .commit()
        return PaletteApplyResult(success = false, changed = false, error = message)
    }

    private fun loaderFailure(context: Context, message: String): PaletteApplyResult {
        prefs(context).edit().putString(KEY_LAST_ERROR, message).apply()
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

    private fun clearAppliedState(context: Context) {
        prefs(context).edit().remove(KEY_APPLIED_FINGERPRINT).apply()
        clearError(context)
    }

    private fun clearError(context: Context) {
        prefs(context).edit().remove(KEY_LAST_ERROR).apply()
    }
}
