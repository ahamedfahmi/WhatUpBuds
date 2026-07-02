package com.whatsupbuds

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws the "liquid battery" wave as a bitmap. Notifications use RemoteViews,
 * which can't host custom-drawing views, so we render to a Bitmap and hand it to
 * an ImageView via RemoteViews.setImageViewBitmap.
 *
 * The fill height encodes the battery level; the color encodes the level band
 * (green / amber / red) and brightens while charging.
 */
object WaveRenderer {

    fun render(
        widthPx: Int,
        heightPx: Int,
        percent: Int,
        charging: Boolean,
        isDark: Boolean,
        cornerRadiusPx: Float,
        compact: Boolean = false,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Keep the wave inside the pill's rounded corners.
        canvas.clipPath(
            Path().apply {
                addRoundRect(
                    RectF(0f, 0f, w.toFloat(), h.toFloat()),
                    cornerRadiusPx, cornerRadiusPx, Path.Direction.CW,
                )
            }
        )

        // Fill level → baseline Y. Headroom (8%..92%) so 100% still shows a wave
        // near the top and 0% still shows a sliver near the bottom.
        val level = percent.coerceIn(0, 100) / 100f
        val baseY = h * (1f - (0.08f + level * 0.84f))

        val palette = paletteFor(percent, charging, isDark)
        // Collapsed pills are small and behind the text — keep the wave subtle.
        val fillAlphaScale = if (compact) 0.55f else 1f
        val crestAlphaScale = if (compact) 0.7f else 1f
        val fillTop = scaleAlpha(palette.fillTop, fillAlphaScale)
        val fillBottom = scaleAlpha(palette.fillBottom, fillAlphaScale)
        val crest = scaleAlpha(palette.crest, crestAlphaScale)

        val amplitude = h * (if (compact) 0.035f else 0.05f)
        val wavelength = w * 0.85f
        val step = (w / 48f).coerceAtLeast(2f)

        fun waveY(x: Float): Float =
            baseY + amplitude * sin(2.0 * PI * (x / wavelength)).toFloat()

        // Filled body below the wave.
        val body = Path().apply {
            moveTo(0f, h.toFloat())
            lineTo(0f, baseY)
            var x = 0f
            while (x <= w) {
                lineTo(x, waveY(x))
                x += step
            }
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        canvas.drawPath(
            body,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, baseY - amplitude, 0f, h.toFloat(),
                    fillTop, fillBottom, Shader.TileMode.CLAMP,
                )
            },
        )

        // Bright crest line along the wave.
        val crestLine = Path().apply {
            moveTo(0f, waveY(0f))
            var x = 0f
            while (x <= w) {
                lineTo(x, waveY(x))
                x += step
            }
        }
        canvas.drawPath(
            crestLine,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (h * (if (compact) 0.015f else 0.02f)).coerceAtLeast(2f)
                strokeCap = Paint.Cap.ROUND
                color = crest
            },
        )

        return bitmap
    }

    private data class Palette(val fillTop: Int, val fillBottom: Int, val crest: Int)

    private fun paletteFor(percent: Int, charging: Boolean, isDark: Boolean): Palette {
        val base = when {
            percent in 0..15 -> if (isDark) 0xFF453A else 0xFF3B30   // red — critical
            percent in 16..30 -> if (isDark) 0xFFD60A else 0xFF9F0A  // amber — low
            else -> if (isDark) 0x9BA0A6 else 0x9AA0A6               // healthy → neutral (no green)
        }
        var color = 0xFF000000.toInt() or base
        if (charging) color = lighten(color, 0.24f) // charging → brighter tint

        // Healthy is deliberately faint (minimal); low-battery amber/red is bolder
        // so it draws the eye.
        val warning = percent in 0..30

        val crestAlpha = when {
            charging -> if (isDark) 0xFF else 0xCC
            warning -> if (isDark) 0xD0 else 0x99
            else -> if (isDark) 0x7A else 0x66 // healthy: soft crest
        }
        val topAlpha = when {
            charging -> 0x55
            warning -> if (isDark) 0x50 else 0x48
            else -> if (isDark) 0x2C else 0x26 // healthy: subtle fill
        }
        val bottomAlpha = if (isDark) 0x06 else 0x08

        return Palette(
            fillTop = withAlpha(color, topAlpha),
            fillBottom = withAlpha(color, bottomAlpha),
            crest = withAlpha(color, crestAlpha),
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0xFFFFFF)

    private fun scaleAlpha(color: Int, scale: Float): Int {
        val a = ((color ushr 24) and 0xFF)
        val na = (a * scale).toInt().coerceIn(0, 255)
        return (na shl 24) or (color and 0xFFFFFF)
    }

    private fun lighten(color: Int, fraction: Float): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val nr = (r + (255 - r) * fraction).toInt()
        val ng = (g + (255 - g) * fraction).toInt()
        val nb = (b + (255 - b) * fraction).toInt()
        return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
}
