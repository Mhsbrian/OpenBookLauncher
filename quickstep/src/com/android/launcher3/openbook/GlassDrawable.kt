/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
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
 * OpenBook's glass for a surface laid on the desk (OpenBook's docs/launcher-design.md §6.3): All Apps' panel (§3.1) and
 * the menus (§4). Drawn as [view]'s background:
 * - the platform's background blur, 34 dp, behind the surface's own bounds only ([BackgroundBlurDrawable], a blur
 *   region of [view]'s window), so nothing else on the screen blurs;
 * - the surface colour at 80 % (`surface` light, `surfaceContainer` dark), or at 96 % while the platform's blur is off
 *   (battery saver, thermal, Developer options: `CrossWindowBlurListeners`) or can't be created;
 * - a 1 dp outline inside the edge, a lit edge along the top and a soft [shadow] outside it.
 *
 * [name] and [tag] only label the log ("panel glass: background blur 51 px").
 */
class GlassDrawable(
    private val view: View,
    private val shadow: Shadow,
    private val name: String,
    private val tag: String,
) : Drawable(), OpenBookMenu.Glass {

    /** A CSS-style two-layer box shadow: a key light (offset, blur) and an ambient one, light and dark. */
    class Shadow(
        val keyY: Int, val keyBlur: Int, val keyLight: Int, val keyDark: Int,
        val ambientY: Int, val ambientBlur: Int, val ambientLight: Int, val ambientDark: Int,
    ) {
        companion object {
            /** Panels (§6.3): 0 24 48 at 22 % plus 0 2 6 at 8 % (dark: 55 % and 30 % black). */
            val PANEL = Shadow(
                R.dimen.ob_shadow_key_y, R.dimen.ob_shadow_key_blur,
                Color.argb(56, 0x1B, 0x1B, 0x21), Color.argb(140, 0, 0, 0),
                R.dimen.ob_shadow_ambient_y, R.dimen.ob_shadow_ambient_blur,
                Color.argb(20, 0x1B, 0x1B, 0x21), Color.argb(77, 0, 0, 0),
            )
            /** Menus (§4), lighter than panels: 0 12 32 at 20 % plus 0 2 6 at 8 % (dark: 50 % and 30 % black). */
            val MENU = Shadow(
                R.dimen.ob_mn_shadow_key_y, R.dimen.ob_mn_shadow_key_blur,
                Color.argb(51, 0x1B, 0x1B, 0x21), Color.argb(128, 0, 0, 0),
                R.dimen.ob_shadow_ambient_y, R.dimen.ob_shadow_ambient_blur,
                Color.argb(20, 0x1B, 0x1B, 0x21), Color.argb(77, 0, 0, 0),
            )
        }
    }

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
    private val outline = ColorUtils.setAlphaComponent(Color.WHITE, if (dark) 36 else 140) // 14 %, 55 %
    private val keyShadow = if (dark) shadow.keyDark else shadow.keyLight
    private val ambientShadow = if (dark) shadow.ambientDark else shadow.ambientLight
    private val keyY = res.getDimension(shadow.keyY)
    private val keyBlur = res.getDimension(shadow.keyBlur)
    private val ambientY = res.getDimension(shadow.ambientY)
    private val ambientBlur = res.getDimension(shadow.ambientBlur)

    override var radius = 0f
        set(v) {
            field = v
            blur?.setCornerRadius(v)
            invalidateSelf()
        }

    /**
     * The blur region's opacity, 0..1. A view's alpha fades what the view draws, its tint included, but not its blur
     * region, so a fade sets this as well.
     */
    override var surfaceAlpha = 1f
        set(v) {
            field = v
            invalidateSelf()
        }

    override val drawable: Drawable get() = this

    /** Whether the platform's blur is on and this window has a blur region (the VM test reads it from the log). */
    override val blurring: Boolean get() = blurEnabled && blur != null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private var blurEnabled = CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled
    private var blur: BackgroundBlurDrawable? = null
    private var blurUnavailable = false
    private val onView = Executor { r -> view.post(r) }
    private val blurListener = Consumer<Boolean> { enabled ->
        if (enabled != blurEnabled) {
            blurEnabled = enabled
            Log.d(tag, "platform blur ${if (enabled) "on" else "off"}")
            invalidateSelf()
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
        if (view.isAttachedToWindow) CrossWindowBlurListeners.getInstance().addListener(onView, blurListener)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)

        // Shadows outside the surface only, so they don't darken the glass.
        canvas.save()
        canvas.clipOutPath(path)
        shadow(canvas, ambientY, ambientBlur, ambientShadow)
        shadow(canvas, keyY, keyBlur, keyShadow)
        canvas.restore()

        val blurDrawable = if (blurEnabled) blurDrawable() else null
        if (blurDrawable != null) {
            blurDrawable.setBounds(b)
            blurDrawable.alpha = (255 * surfaceAlpha).roundToInt()
            blurDrawable.draw(canvas)
        }
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(
            tint, (255 * if (blurDrawable != null) glassAlpha else solidAlpha).roundToInt()
        )
        canvas.drawPath(path, paint)

        // A 1 dp outline inside the edge, and the lit edge along the top.
        canvas.save()
        canvas.clipPath(path)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = edge * 2 // half of it falls outside the clip
        paint.color = outline
        canvas.drawPath(path, paint)
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + radius)
        paint.color = litEdge
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    /** A CSS-style box shadow (offset [dy], blur [blur]) of the surface's shape. */
    private fun shadow(canvas: Canvas, dy: Float, blur: Float, color: Int) {
        // Skia's sigma for a radius r is 0.57735 r + 0.5; a CSS blur b has sigma b / 2.
        val r = ((blur / 2f) - 0.5f) / 0.57735f
        shadowPaint.color = Color.BLACK
        shadowPaint.setShadowLayer(r, 0f, dy, color)
        canvas.drawPath(path, shadowPaint)
    }

    private fun blurDrawable(): BackgroundBlurDrawable? {
        blur?.let { return it }
        if (blurUnavailable) return null
        val vri = view.viewRootImpl ?: return null
        val b = vri.createBackgroundBlurDrawable()
        if (b == null) {
            blurUnavailable = true
            Log.w(tag, "no background blur for the $name window: tint only")
            return null
        }
        b.setBlurRadius(blurRadius)
        b.setCornerRadius(radius)
        blur = b
        Log.d(tag, "$name glass: background blur $blurRadius px")
        return b
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** A menu's glass under [view] (design §4), for [OpenBookMenu.GlassHost]. */
        @JvmStatic
        fun forMenu(view: View): OpenBookMenu.Glass = GlassDrawable(view, Shadow.MENU, "menu", OpenBookMenu.TAG)
    }
}
