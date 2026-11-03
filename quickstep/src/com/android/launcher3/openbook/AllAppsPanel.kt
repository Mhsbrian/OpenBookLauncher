/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.CrossWindowBlurListeners
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.Interpolator
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.app.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.app.animation.Interpolators.STANDARD
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.taskbar.overlay.TaskbarOverlayContext
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * All Apps from the taskbar as a centred glass panel (openbook/ROADMAP.md M2 #9; OpenBook's docs/launcher-design.md
 * §3), in place of AOSP 17's full-width bottom sheet with its handle and scrim.
 *
 * - **Geometry.** 720 dp wide (6 columns of 112 dp and 24 dp each side), centred, its bottom 12 dp above the taskbar,
 *   28 dp corners. Its height fits the apps (84 dp header, 104 dp rows, 28 dp bottom), at most 640 dp and at most the
 *   space above the taskbar less 24 dp. It is set once per opening, so a search never resizes it.
 * - **Material.** The panel's own background blur, 34 dp, behind its own bounds only ([BackgroundBlurDrawable] on the
 *   panel, a blur region of the overlay window), under the surface colour at 80 % (96 % while the platform's blur is
 *   off), with a lit top edge, a 1 dp outline and a soft shadow. No scrim and no blur of the desktop: the overlay
 *   window's own blur (`TaskbarOverlayController.setBackgroundBlurRadius`) stays 0.
 * - **The field.** A 48 dp pill of paper inset 24 dp, a 20 dp search icon, "Search apps" in soft ink, text 16/24, and
 *   a 2 dp `primary` ring and caret while it has focus. Focus starts in it, however the panel opens (the apps pill,
 *   Meta, typing on the desktop).
 * - **Motion.** Opening, the panel rises 16 dp and scales from 0.96 about its bottom centre on `spring.panel`, fading
 *   in on `duration.fade`; the grid fades in 40 ms later. Closing takes `duration.exit` (150 ms, emphasised
 *   accelerate) to 0.98, an 8 dp fall and no opacity. Remove animations makes both cuts.
 *
 * The grid's cells, the top hit and the no-results row are [AllAppsPanelCells]. It applies to the persistent taskbar
 * of a launcher whose resources say `bool/desktop_form_factor` (OpenBook's desktop overlay), on the primary display;
 * the transient taskbar, other displays and other form factors keep AOSP's sheet.
 */
class AllAppsPanel private constructor(
    private val sheet: ViewGroup,
    private val appsView: ActivityAllAppsContainerView<*>,
) {
    private val context = appsView.context
    private val res = context.resources

    private val width = res.getDimensionPixelSize(R.dimen.ob_aa_column) * res.getInteger(R.integer.ob_aa_columns) +
        2 * res.getDimensionPixelSize(R.dimen.ob_aa_inset)
    private val inset = res.getDimensionPixelSize(R.dimen.ob_aa_inset)
    private val header = res.getDimensionPixelSize(R.dimen.ob_aa_header)
    private val bottom = res.getDimensionPixelSize(R.dimen.ob_aa_bottom)
    private val row = res.getDimensionPixelSize(R.dimen.ob_aa_cell_height)
    private val columns = res.getInteger(R.integer.ob_aa_columns)
    private val maxHeight = res.getDimensionPixelSize(R.dimen.ob_aa_max_height)
    private val screenMargin = res.getDimensionPixelSize(R.dimen.ob_aa_screen_margin)
    private val gap = res.getDimensionPixelSize(R.dimen.ob_aa_gap)

    private val openScale = res.getFloat(R.dimen.ob_aa_open_scale)
    private val rise = res.getDimension(R.dimen.ob_aa_rise)
    private val closeScale = res.getFloat(R.dimen.ob_aa_close_scale)
    private val fall = res.getDimension(R.dimen.ob_aa_fall)
    private val panelStiffness = res.getFloat(R.dimen.ob_spring_panel_stiffness)
    private val panelDamping = res.getFloat(R.dimen.ob_spring_panel_damping)
    private val fadeMs = res.getInteger(R.integer.ob_duration_fade_ms).toLong()
    private val gridDelayMs = res.getInteger(R.integer.ob_aa_grid_delay_ms).toLong()

    /** Closing: `duration.exit` on emphasised accelerate. */
    val closeDurationMs = res.getInteger(R.integer.ob_duration_exit_ms)
    val closeInterpolator: Interpolator = EMPHASIZED_ACCELERATE

    private val glass = PanelGlass(appsView)
    private val insets = Rect()
    private var panelHeight = 0
    private var heightLocked = false

    private var openSpring: SpringAnimation? = null
    private var fade: ValueAnimator? = null
    private var gridFade: ValueAnimator? = null
    private var closing = false
    private var escapeDown = false
    private var closeStart = 0L
    private var closeFrames = FrameLog()
    private var closeFrom = floatArrayOf(1f, 1f, 0f) // alpha, scale, translationY when the close began

    init {
        // The header holds no rows here (no predictions, no tabs); without its paddings it clips nothing.
        appsView.floatingHeaderView.setPadding(0, 0, 0, 0)
        appsView.findViewById<View>(R.id.bottom_sheet_handle)?.visibility = View.GONE
        appsView.findViewById<View>(R.id.bottom_sheet_handle_area)?.visibility = View.GONE
        glass.radius = res.getDimension(R.dimen.ob_aa_radius)
        appsView.background = glass
        appsView.clipToOutline = false
        (sheet as? LinearLayout)?.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        sheet.clipChildren = false
        sheet.clipToPadding = false
        styleField()
        Log.d(TAG, "All Apps is the panel")
    }

    /**
     * The overlay window's insets reached the sheet: places the panel above the taskbar ([insets] bottom) and returns
     * the insets its content works with (none, and the 28 dp bottom as its bottom).
     */
    fun onInsets(insets: Rect): Rect {
        this.insets.set(insets)
        layout()
        return Rect(0, 0, 0, bottom)
    }

    /** Sets the panel's size and place. The height follows the apps until the opening locks it. */
    private fun layout() {
        val lp = appsView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (!heightLocked) panelHeight = fitHeight()
        lp.width = width
        lp.height = panelHeight
        lp.leftMargin = 0
        lp.rightMargin = 0
        lp.topMargin = 0
        lp.bottomMargin = insets.bottom + gap
        appsView.layoutParams = lp
    }

    /** Header, rows of apps and bottom; at most [maxHeight] and the space above the taskbar less [screenMargin]. */
    private fun fitHeight(): Int {
        val apps = appsView.appsStore.apps.size
        val window = sheet.height.takeIf { it > 0 } ?: res.displayMetrics.heightPixels
        val avail = window - insets.top - insets.bottom - screenMargin
        val fit = if (apps == 0) maxHeight else header + ceil(apps / columns.toFloat()).toInt() * row + bottom
        return min(min(fit, maxHeight), avail)
    }

    /** The field: paper, the icon, the hint, the ring and caret (design §3.1). */
    private fun styleField() {
        val field = appsView.searchUiManager.editText ?: return
        val primary = context.getColor(R.color.materialColorPrimary)
        val soft = context.getColor(R.color.materialColorOnSurfaceVariant)
        field.background = FieldDrawable(context)
        (field.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.height = res.getDimensionPixelSize(R.dimen.ob_aa_field_height)
            field.layoutParams = it
        }
        field.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val pad = res.getDimensionPixelSize(R.dimen.ob_aa_field_padding)
        field.setPaddingRelative(pad, 0, pad, 0)
        field.hint = res.getString(R.string.all_apps_search_bar_hint) // AOSP prefixes its own icon to the hint
        field.setHintTextColor(soft)
        field.setTextColor(context.getColor(R.color.materialColorOnSurface))
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.ob_aa_field_text))
        field.setLineHeight(res.getDimensionPixelSize(R.dimen.ob_aa_field_line))
        field.typeface = Typeface.create(field.typeface, 400, false)
        val icon = context.getDrawable(R.drawable.ic_allapps_search)?.mutate()
        if (icon != null) {
            val s = res.getDimensionPixelSize(R.dimen.ob_aa_field_icon)
            icon.setBounds(0, 0, s, s)
            icon.setTintList(ColorStateList.valueOf(soft))
        }
        field.setCompoundDrawablesRelative(icon, null, null, null)
        field.compoundDrawablePadding = res.getDimensionPixelSize(R.dimen.ob_aa_field_icon_gap)
        field.textCursorDrawable = GradientDrawable().apply {
            setColor(primary)
            setSize(res.getDimensionPixelSize(R.dimen.ob_focus_ring), 1)
        }
        field.highlightColor = ColorUtils.setAlphaComponent(primary, 0x40)
        // ↓ goes into the grid (Tab already does); the field otherwise keeps the arrows for its text.
        field.setOnKeyListener { _, code, event ->
            code == KeyEvent.KEYCODE_DPAD_DOWN && event.hasNoModifiers() &&
                (event.action != KeyEvent.ACTION_DOWN || focusGrid())
        }
    }

    /** Focus on the first cell of the list showing (the A-Z grid or the results). */
    private fun focusGrid(): Boolean {
        val rv = appsView.activeRecyclerView ?: return false
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            if (v is BubbleTextView && v.isFocusable) return v.requestFocus()
        }
        return false
    }

    /** The sheet is attached and opening: focus the field and run the panel's own opening. */
    fun onOpen(animate: Boolean) {
        closing = false
        layout()
        lockHeight()
        appsView.searchUiManager.editText?.let { if (!it.isFocused) it.requestFocusExplicitly() }
        val grid = appsView.appsRecyclerViewContainer
        clipToPanel(grid)
        clipToPanel(appsView.searchRecyclerView)
        appsView.pivotX = width / 2f
        appsView.pivotY = panelHeight.toFloat()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            apply(1f, 1f, 0f)
            grid.alpha = 1f
            Log.d(TAG, "open: a cut, ${panelHeight}px tall")
            return
        }
        apply(0f, openScale, rise)
        openSpring?.cancel()
        val start = SystemClock.uptimeMillis()
        val frames = FrameLog()
        openSpring = SpringAnimation(FloatValueHolder(0f)).apply {
            spring = SpringForce(1f).setStiffness(panelStiffness).setDampingRatio(panelDamping)
            setMinimumVisibleChange(SpringAnimation.MIN_VISIBLE_CHANGE_SCALE)
            addUpdateListener { _, p, _ ->
                frames.frame()
                if (!closing) {
                    appsView.scaleX = openScale + (1f - openScale) * p
                    appsView.scaleY = appsView.scaleX
                    appsView.translationY = rise * (1f - p)
                }
            }
            addEndListener { _, canceled, _, _ ->
                Log.d(TAG, "open: spring.panel ($panelStiffness, $panelDamping) ${if (canceled) "cut short" else
                    "settled"} ${frames.span()} ms from its first frame over ${frames.count} frames (longest " +
                    "${frames.longest} ms), ${SystemClock.uptimeMillis() - start} ms in all, ${panelHeight}px tall")
            }
            start()
        }
        fade?.cancel()
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = fadeMs
            interpolator = STANDARD
            addUpdateListener { if (!closing) setAlpha(it.animatedValue as Float) }
            start()
        }
        grid.alpha = 0f
        gridFade?.cancel()
        gridFade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = fadeMs
            startDelay = gridDelayMs
            interpolator = STANDARD
            addUpdateListener { grid.alpha = it.animatedValue as Float }
            start()
        }
    }

    /** Clips [v], which reaches the panel's bottom, to the panel's bottom corners (AOSP clips it to a rectangle). */
    private fun clipToPanel(v: View) {
        val r = res.getDimension(R.dimen.ob_aa_radius)
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, -r.toInt(), view.width, view.height, r)
            }
        }
        v.clipToOutline = true
    }

    /**
     * Esc, from the field or the grid, before the IME sees it: with text in the field it clears the text and puts
     * focus back in the field for typing; with none it closes the panel. AOSP's Esc went through the IME and Back,
     * whose reset of the search also took the field's focus away.
     */
    fun onKeyPreIme(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ESCAPE || !event.hasNoModifiers()) return false
        if (event.action == KeyEvent.ACTION_UP) return escapeDown.also { escapeDown = false }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        escapeDown = true
        if (event.repeatCount > 0) return true
        val field = appsView.searchUiManager.editText
        if (field != null && !field.text.isNullOrEmpty()) {
            field.setText("")
            field.requestFocusExplicitly()
            Log.d(TAG, "Esc cleared the search")
        } else {
            (sheet as? AbstractFloatingView)?.close(true)
            Log.d(TAG, "Esc closed the panel")
        }
        return true
    }

    /** Fixes the height for this opening, once there are apps to fit. */
    private fun lockHeight() {
        val store = appsView.appsStore
        if (store.apps.isNotEmpty()) {
            heightLocked = true
            return
        }
        store.addUpdateListener(object : AllAppsStore.OnUpdateListener {
            override fun onAppsUpdated() {
                if (store.apps.isEmpty()) return
                store.removeUpdateListener(this)
                layout()
                heightLocked = true
                appsView.pivotY = panelHeight.toFloat()
            }
        })
    }

    /**
     * The close began: it starts from wherever the opening got to. [swipeIdle] is whether AOSP's swipe detector was
     * idle: otherwise AOSP would close on its scroll duration (300 ms) instead of ours, so the sheet resets it.
     */
    fun onCloseStart(swipeIdle: Boolean) {
        if (!swipeIdle) Log.d(TAG, "close: the swipe detector wasn't idle")
        if (closing) return
        closing = true
        closeStart = SystemClock.uptimeMillis()
        closeFrames = FrameLog()
        openSpring?.cancel()
        fade?.cancel()
        closeFrom = floatArrayOf(appsView.alpha, appsView.scaleX, appsView.translationY)
    }

    /**
     * AOSP's open/close progress (0 open, 1 closed). Opening is the panel's own spring; closing follows this progress,
     * which runs [closeDurationMs] on [closeInterpolator].
     */
    fun onShift(shift: Float, toClosed: Boolean) {
        if (!toClosed || !closing) return
        val (a, s, y) = closeFrom
        apply(a * (1f - shift), s + (closeScale - s) * shift, y + (fall - y) * shift)
        closeFrames.frame()
        if (shift >= 1f && closeStart != 0L) {
            Log.d(TAG, "close: ${closeFrames.span()} ms from its first frame over ${closeFrames.count} frames " +
                "(longest ${closeFrames.longest} ms; $closeDurationMs ms set), " +
                "${SystemClock.uptimeMillis() - closeStart} ms in all")
            closeStart = 0L
        }
    }

    /** The sheet closed and is leaving its window. */
    fun onClosed() {
        if (closeStart != 0L) Log.d(TAG, "close: a cut") // Remove animations, or closed without animating
        closeStart = 0L
    }

    private fun apply(alpha: Float, scale: Float, translationY: Float) {
        setAlpha(alpha)
        appsView.scaleX = scale
        appsView.scaleY = scale
        appsView.translationY = translationY
    }

    private fun setAlpha(alpha: Float) {
        appsView.alpha = alpha
        glass.panelAlpha = alpha // the blur region doesn't follow the view's alpha
    }

    /** When an animation's frames came: how many, over how long, and the longest gap (for the log). */
    private class FrameLog {
        var count = 0
        var longest = 0L
        private var first = 0L
        private var last = 0L

        fun frame() {
            val now = SystemClock.uptimeMillis()
            if (count == 0) first = now else longest = maxOf(longest, now - last)
            last = now
            count++
        }

        fun span() = last - first
    }

    /** The glass: shadow, the platform's blur behind the panel, the tint, the outline and the lit top edge. */
    private class PanelGlass(private val view: View) : Drawable() {
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
        private val keyShadow = if (dark) Color.argb(140, 0, 0, 0) else Color.argb(56, 0x1B, 0x1B, 0x21) // 55 %, 22 %
        private val ambientShadow = if (dark) Color.argb(77, 0, 0, 0) else Color.argb(20, 0x1B, 0x1B, 0x21) // 30, 8
        private val keyY = res.getDimension(R.dimen.ob_shadow_key_y)
        private val keyBlur = res.getDimension(R.dimen.ob_shadow_key_blur)
        private val ambientY = res.getDimension(R.dimen.ob_shadow_ambient_y)
        private val ambientBlur = res.getDimension(R.dimen.ob_shadow_ambient_blur)

        var radius = 0f
        var panelAlpha = 1f
            set(v) {
                field = v
                invalidateSelf()
            }

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
                Log.d(TAG, "platform blur ${if (enabled) "on" else "off"}")
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
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            rect.set(b)
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)

            // Shadows outside the panel only, so they don't darken the glass.
            canvas.save()
            canvas.clipOutPath(path)
            shadow(canvas, ambientY, ambientBlur, ambientShadow)
            shadow(canvas, keyY, keyBlur, keyShadow)
            canvas.restore()

            val blurDrawable = if (blurEnabled) blurDrawable() else null
            if (blurDrawable != null) {
                blurDrawable.setBounds(b)
                blurDrawable.alpha = (255 * panelAlpha).roundToInt()
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

        /** A CSS-style box shadow (offset [dy], blur [blur]) of the panel's shape. */
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
                Log.w(TAG, "no background blur for the All Apps window: tint only")
                return null
            }
            b.setBlurRadius(blurRadius)
            b.setCornerRadius(radius)
            blur = b
            Log.d(TAG, "panel glass: background blur $blurRadius px")
            return b
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** The field's paper pill, with the 2 dp `primary` ring while it has focus. */
    private class FieldDrawable(c: Context) : Drawable() {
        private val dark = Utilities.isDarkTheme(c)
        private val paper = ColorUtils.setAlphaComponent(
            c.getColor(
                if (dark) R.color.materialColorSurfaceContainerHighest
                else R.color.materialColorSurfaceContainerLowest
            ),
            (255 * c.resources.getFloat(
                if (dark) R.dimen.ob_aa_paper_alpha_dark else R.dimen.ob_aa_paper_alpha_light
            )).roundToInt(),
        )
        private val primary = c.getColor(R.color.materialColorPrimary)
        private val ring = c.resources.getDimension(R.dimen.ob_focus_ring)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var focused = false

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            val r = rect.height() / 2f
            paint.style = Paint.Style.FILL
            paint.color = paper
            canvas.drawRoundRect(rect, r, r, paint)
            if (focused) {
                rect.inset(ring / 2f, ring / 2f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ring
                paint.color = primary
                canvas.drawRoundRect(rect, r - ring / 2f, r - ring / 2f, paint)
            }
        }

        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val f = state.contains(android.R.attr.state_focused)
            if (f == focused) return false
            focused = f
            invalidateSelf()
            return true
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val TAG = "OpenBookAllApps"

        /** The last panel device profile and the launcher device profile it was made from (taskbar UI thread). */
        private var profileFrom: DeviceProfile? = null
        private var profile: DeviceProfile? = null

        /** Whether [context]'s All Apps is the panel: the persistent taskbar of a desktop form factor, primary display. */
        @JvmStatic
        fun isEnabled(context: TaskbarOverlayContext): Boolean =
            context.resources.getBoolean(R.bool.desktop_form_factor) && context.isPrimaryDisplay &&
                !context.isTransientTaskbar

        /** The panel for the taskbar's All Apps [sheet] and its [appsView], or null where All Apps stays AOSP's. */
        @JvmStatic
        fun create(context: TaskbarOverlayContext, sheet: ViewGroup, appsView: ActivityAllAppsContainerView<*>) =
            if (isEnabled(context)) AllAppsPanel(sheet, appsView) else null

        /**
         * The device profile for the overlay window: the launcher's ([dp]), with the panel's grid as its All Apps
         * profile (design §3.2): 6 columns of 112 × 104 dp cells, no border space, 24 dp each side, a 52 dp icon, 13 sp
         * labels on two lines. Made once per launcher profile.
         */
        @JvmStatic
        fun deviceProfile(context: TaskbarOverlayContext, dp: DeviceProfile?): DeviceProfile? {
            if (dp == null || !isEnabled(context)) return dp
            if (dp === profileFrom) profile?.let { return it }
            val res = context.resources
            val inset = res.getDimensionPixelSize(R.dimen.ob_aa_inset)
            val p = dp.copy()
            p.allAppsProfile = dp.allAppsProfile.copy(
                borderSpacePx = Point(0, 0),
                cellHeightPx = res.getDimensionPixelSize(R.dimen.ob_aa_cell_height),
                iconSizePx = res.getDimensionPixelSize(R.dimen.ob_aa_icon),
                iconTextSizePx = res.getDimension(R.dimen.ob_aa_label_text),
                iconDrawablePaddingPx = res.getDimensionPixelSize(R.dimen.ob_aa_label_gap),
                maxAllAppsTextLineCount = 2,
                cellWidthPx = res.getDimensionPixelSize(R.dimen.ob_aa_column),
                closeDuration = res.getInteger(R.integer.ob_duration_exit_ms),
                leftRightMargin = 0,
                padding = Rect(inset, inset, inset, 0),
                numShownAllAppsColumns = res.getInteger(R.integer.ob_aa_columns),
            )
            profileFrom = dp
            profile = p
            return p
        }
    }
}
