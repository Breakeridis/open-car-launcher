package com.minimal.carlauncher.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * The car locator: an arrow at the vehicle position pointing along its heading, or a dot
 * when no heading is known.
 *
 * Drawn as a map overlay (not a view pinned to the portal centre) so it stays on the car
 * while the driver pans away. The canvas osmdroid hands to overlays is already rotated by the
 * map orientation, so rotating by the true heading here is correct in both heading-up and
 * north-up modes - the same approach as osmdroid's own MyLocationNewOverlay.
 */
class VehicleOverlay(fillColor: Int, outlineColor: Int, private val sizePx: Float) : Overlay() {

    var position: GeoPoint? = null
    var headingDeg: Float? = null

    private val screen = Point()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = sizePx * 0.12f
        color = outlineColor
    }

    /** Navigation-style chevron pointing up, centred on the origin. */
    private val arrow = Path().apply {
        val h = sizePx / 2f
        moveTo(0f, -h)
        lineTo(h * 0.8f, h)
        lineTo(0f, h * 0.45f)
        lineTo(-h * 0.8f, h)
        close()
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val p = position ?: return
        mapView.projection.toPixels(p, screen)

        canvas.save()
        canvas.translate(screen.x.toFloat(), screen.y.toFloat())
        val heading = headingDeg
        if (heading != null) {
            canvas.rotate(heading)
            canvas.drawPath(arrow, outline)
            canvas.drawPath(arrow, fill)
        } else {
            canvas.drawCircle(0f, 0f, sizePx * 0.32f, outline)
            canvas.drawCircle(0f, 0f, sizePx * 0.32f, fill)
        }
        canvas.restore()
    }
}
