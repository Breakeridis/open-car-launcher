package com.minimal.carlauncher.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.minimal.carlauncher.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The ring around the map portal: tick marks plus N / E / S / W.
 *
 * [northDeg] is where north currently sits on screen, clockwise from the top - the same
 * number osmdroid uses as its map orientation, so the bezel and the map always agree.
 * Letters are drawn upright at their rotated positions, so they stay readable at any heading.
 */
class CompassBezelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var northDeg: Float = 0f
        set(value) {
            // Sub-degree changes are invisible on the ring; skipping them saves redraws.
            if (abs(value - field) < 0.5f) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.cockpit_surface)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.cockpit_divider)
    }
    private val minorTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.cockpit_text_dim)
    }
    private val majorTick = Paint(minorTick).apply {
        strokeWidth = 3f * density
        color = ContextCompat.getColor(context, R.color.cockpit_text_secondary)
    }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        color = ContextCompat.getColor(context, R.color.cockpit_text_primary)
    }
    private val northPaint = Paint(letterPaint).apply {
        color = ContextCompat.getColor(context, R.color.cockpit_accent)
    }

    /** Ring thickness; the map is inset by exactly this in the layout. */
    private val bezelWidth = resources.getDimension(R.dimen.map_bezel_width)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(cx, cy)
        val inner = outer - bezelWidth
        val mid = (outer + inner) / 2f

        canvas.drawCircle(cx, cy, outer, ringPaint)
        canvas.drawCircle(cx, cy, outer - edgePaint.strokeWidth / 2f, edgePaint)

        // Ticks every 10 degrees, but the cardinal positions are left for the letters.
        for (deg in 0 until 360 step 10) {
            if (deg % 90 == 0) continue
            val major = deg % 30 == 0
            val len = if (major) bezelWidth * 0.34f else bezelWidth * 0.2f
            val a = Math.toRadians((deg + northDeg).toDouble())
            val s = sin(a).toFloat()
            val c = cos(a).toFloat()
            canvas.drawLine(
                cx + (mid - len / 2f) * s, cy - (mid - len / 2f) * c,
                cx + (mid + len / 2f) * s, cy - (mid + len / 2f) * c,
                if (major) majorTick else minorTick
            )
        }

        letterPaint.textSize = bezelWidth * 0.55f
        northPaint.textSize = bezelWidth * 0.62f
        val baselineShift = -(letterPaint.ascent() + letterPaint.descent()) / 2f
        LETTERS.forEachIndexed { i, letter ->
            val a = Math.toRadians((i * 90f + northDeg).toDouble())
            val x = cx + mid * sin(a).toFloat()
            val y = cy - mid * cos(a).toFloat() + baselineShift
            canvas.drawText(letter, x, y, if (i == 0) northPaint else letterPaint)
        }
    }

    private companion object {
        val LETTERS = arrayOf("N", "E", "S", "W")
    }
}
