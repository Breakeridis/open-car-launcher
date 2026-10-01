package com.minimal.carlauncher.ui.view

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * Clips its children (the map and its HUD) to a circle.
 *
 * Done with an oval outline rather than Canvas.clipPath: outline clipping is applied by the
 * RenderThread and stays anti-aliased and cheap, whereas clipPath on a constantly redrawing
 * map would force software layers - noticeable on the Mali-G31 / PowerVR GE8300 class GPUs
 * in Allwinner T507 / A133 head units.
 */
class CircleClipLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }
}
