package com.tjg.twidget.ui

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.RenderNode
import android.graphics.RenderEffect
import android.graphics.Path
import android.graphics.RectF
import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import com.tjg.twidget.R

/**
 * Rendering portion of Samsung Keyboard's NowNudgeEffectView.
 * The two AGSL programs and light-map pixels are unmodified from PDI 7.4.02.1
 * (sesl-visualeffect INTERNAL 2.3.2). NowNudgeEffectView selects
 * NUDGE_LIGHT/NUDGE_DARK and overrides outline thickness to 20 pixels. The original Samsung tint colours are retained while validating visual parity. No platform-private Samsung classes are loaded at runtime.
 */
@RequiresApi(33)
internal class SamsungGuidingLightRenderer(resources: Resources, theme: Resources.Theme?, night: Boolean) {
    private val effect = RuntimeShader(resources.openRawResource(R.raw.samsung_guiding_light).bufferedReader().use { it.readText() })
    private val tint = RuntimeShader(resources.openRawResource(R.raw.samsung_radial_light).bufferedReader().use { it.readText() })
    private val node = RenderNode("SamsungNowNudgeInfill").apply { setClipToBounds(false) }
    private val clip = Path()
    private var layerWidth = 0
    private var layerHeight = 0

    init {
        val map = BitmapFactory.decodeResource(resources, R.drawable.samsung_guiding_light_map,
            BitmapFactory.Options().apply { inScaled = false })
        val mapShader = BitmapShader(map, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
        val transparent = RuntimeShader("half4 main(float2 p) { return half4(0); }")
        tint.setInputShader("inputShader", transparent)
        tint.setInputBuffer("spotLightMapShader", mapShader)
        tint.setFloatUniform("uLightMapSize", map.width.toFloat(), map.height.toFloat())
        tint.setFloatUniform("uSize", 256f, 256f)
        tint.setFloatUniform("uTime", 0f)
        val baseAlpha = 1f
        tint.setFloatUniform("uBaseColor", baseAlpha, baseAlpha, baseAlpha, baseAlpha)
        tint.setIntUniform("uSpotCount", 4)
        tint.setFloatUniform("uSpotEnabled", floatArrayOf(1f, 1f, 1f, 1f, 0f))
        // PresetEffectColor.State.Nudge, in SpotIndexManager's A,C,D,B render order.
        tint.setFloatUniform("uSpotScales", floatArrayOf(1.87f, 1.48f, 1.32f, 2.15f, 1f))
        tint.setFloatUniform("uSpotPositions", floatArrayOf(.08f, .62f, .55f, .65f, 1.17f, .49f, 1.7f, 1f, 0f, 0f))
        // Samsung's original Nudge colours, in the same A,C,D,B compositing order.
        val colours = intArrayOf(
            Color.parseColor("#C2B2FF"),
            Color.parseColor("#8BBDFF"),
            Color.parseColor("#88E6E3"),
            Color.parseColor("#F0F488"),
            Color.TRANSPARENT,
        )
        tint.setFloatUniform("uSpotColors", colours.flatMap { colour ->
            listOf(Color.red(colour) / 255f, Color.green(colour) / 255f, Color.blue(colour) / 255f, Color.alpha(colour) / 255f)
        }.toFloatArray())

        effect.setInputShader("tintShader", tint)
        effect.setFloatUniform("uTintShaderSize", 256f, 256f)
        effect.setFloatUniform("uTintFlipDirection", 0f, 0f)
        effect.setInputBuffer("lightMapShader", mapShader)
        effect.setInputBuffer("lightMapGlowShader", mapShader)
        effect.setFloatUniform("uLightMapSize", map.width.toFloat(), map.height.toFloat())
        effect.setFloatUniform("uLightMapGlowSize", map.width.toFloat(), map.height.toFloat())
        effect.setFloatUniform("uViewCenter", .5f, .5f)
        effect.setFloatUniform("uViewAlpha", 1f)
        effect.setIntUniform("uRoundRectShape", 1)
        effect.setFloatUniform("uRoundRectDirection", 0f, 0f)
        effect.setFloatUniform("uBorderWidth", 0f, 0f)
        effect.setFloatUniform("uCircleRadius", 0f)
        effect.setFloatUniform("uLightPos", .5f, .5f)
        effect.setFloatUniform("uLightColor", 1f, 1f, 1f, 1f)
        effect.setFloatUniform("uProgress", 1f)
        effect.setFloatUniform("uDitherVariation", 0f)
        effect.setFloatUniform("uLightRadius", 3f)
        effect.setFloatUniform("uLightIntensity", if (night) .05f else .1f)
        effect.setFloatUniform("uGlowIntensity", if (night) .4f else .45f)
        effect.setFloatUniform("uGlowRadius", 6f)
        effect.setFloatUniform("uGlowSharpness", 20f)
        effect.setFloatUniform("uReflRadius", 1.85f)
        effect.setFloatUniform("uReflLightIntensity", .55f)
        effect.setFloatUniform("uReflAlbedo", .15f)
        effect.setFloatUniform("uGlobalLuminance", 1.1f)
        effect.setFloatUniform("uSaturation", 1f)
        effect.setFloatUniform("uOuterSaturation", 1f)
        effect.setFloatUniform("uStretch", 1f)
        effect.setFloatUniform("uStretchDirLit", 0f)
        effect.setFloatUniform("uOutlineThickness", 20f)
        effect.setFloatUniform("uBoundarySmoothWidth", 0f)
    }

    fun draw(canvas: android.graphics.Canvas, width: Int, height: Int, alpha: Int, filter: ColorFilter?) {
        if (!canvas.isHardwareAccelerated) return
        if (width != layerWidth || height != layerHeight) {
            layerWidth = width
            layerHeight = height
            effect.setFloatUniform("uSize", width.toFloat(), height.toFloat())
            val radius = minOf(width, height) / 2f
            effect.setFloatUniform("uCornerRadius", radius)
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(effect, "inputShader"))
            val recording = node.beginRecording(width, height)
            recording.drawColor(Color.TRANSPARENT, BlendMode.SRC)
            node.endRecording()
            clip.rewind()
            clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW)
        }
        node.alpha = alpha / 255f
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(save)
    }
}
