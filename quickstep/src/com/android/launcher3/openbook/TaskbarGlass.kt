/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.CrossWindowBlurListeners
import android.view.View
import androidx.core.graphics.ColorUtils
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.launcher3.R
import com.android.launcher3.Utilities
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * The taskbar's glass (openbook/ROADMAP.md M2 #8; OpenBook's docs/launcher-design.md §2.1, §6.3), drawn by the taskbar
 * window's drag layer in place of AOSP's opaque bar:
 * - the platform's background blur, 34 dp, behind the bar's own region only ([BackgroundBlurDrawable], a blur region
 *   of the taskbar window, so a taskbar window made full screen for a menu or a drag doesn't blur the screen);
 * - the surface colour at 80 % (`surface` light, `surfaceContainer` dark), or at 96 % when the platform's blur is off
 *   (battery saver, thermal, Developer options: `CrossWindowBlurListeners`) or can't be created;
 * - a 1 dp lit edge inside the top, and a hairline above it.
 *
 * The bar keeps AOSP's height and alpha (the background alpha also fades the blur), so window insets don't change.
 */
class TaskbarGlass(private val view: View) {

    private val res = view.resources
    private val dark = Utilities.isDarkTheme(view.context)
    private val tint = view.context.getColor(
        if (dark) R.color.materialColorSurfaceContainer else R.color.materialColorSurface
    )
    private val glassAlpha = res.getFloat(R.dimen.ob_glass_alpha)
    private val solidAlpha = res.getFloat(R.dimen.ob_glass_solid_alpha)
    private val blurRadius = res.getDimensionPixelSize(R.dimen.ob_glass_blur)
    private val edge = res.getDimension(R.dimen.ob_glass_edge)
    private val litEdge = ColorUtils.setAlphaComponent(Color.WHITE, if (dark) 46 else 191) // 18 %, 75 %
    private val hairline =
        if (dark) ColorUtils.setAlphaComponent(Color.WHITE, 20) // 8 %
        else ColorUtils.setAlphaComponent(view.context.getColor(R.color.materialColorOutline), 56) // 22 %

    private val fill = Paint().apply { style = Paint.Style.FILL }

    private var blurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled
    private var blur: BackgroundBlurDrawable? = null
    private var blurUnavailable = false

    private val onView = Executor { r -> view.post(r) }
    private val blurListener = Consumer<Boolean> { enabled ->
        if (enabled != blurEnabled) {
            blurEnabled = enabled
            Log.d(TAG, "platform blur ${if (enabled) "on" else "off"}")
            view.invalidate()
        }
    }

    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                CrossWindowBlurListeners.getInstance().addListener(onView, blurListener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                CrossWindowBlurListeners.getInstance().removeListener(blurListener)
                blur = null
            }
        })
    }

    /** Draws the bar from [top], [height] tall, the full width of [canvas], at the background's [alpha] (0..255). */
    fun draw(canvas: Canvas, top: Float, height: Float, alpha: Int) {
        if (alpha <= 0 || height <= 0f) return
        val width = canvas.width.toFloat()
        val b = if (blurEnabled) blurDrawable() else null
        if (b != null) {
            b.setBounds(0, top.roundToInt(), width.roundToInt(), (top + height).roundToInt())
            b.alpha = alpha
            b.draw(canvas)
        }
        fill.color = tint
        fill.alpha = (255 * (if (b != null) glassAlpha else solidAlpha) * alpha / 255f).roundToInt()
        canvas.drawRect(0f, top, width, top + height, fill)
        fill.color = litEdge
        fill.alpha = Color.alpha(litEdge) * alpha / 255
        canvas.drawRect(0f, top, width, top + edge, fill)
        fill.color = hairline
        fill.alpha = Color.alpha(hairline) * alpha / 255
        canvas.drawRect(0f, top - edge, width, top, fill)
    }

    private fun blurDrawable(): BackgroundBlurDrawable? {
        blur?.let { return it }
        if (blurUnavailable) return null
        val vri = view.viewRootImpl ?: return null
        val b = vri.createBackgroundBlurDrawable()
        if (b == null) {
            blurUnavailable = true
            Log.w(TAG, "no background blur for the taskbar window: tint only")
            return null
        }
        b.setBlurRadius(blurRadius)
        b.setCornerRadius(0f)
        blur = b
        Log.d(TAG, "taskbar glass: background blur $blurRadius px")
        return b
    }

    private companion object {
        const val TAG = "OpenBookTaskbar"
    }
}
