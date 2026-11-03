/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.graphics.ColorUtils
import com.android.app.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.app.animation.Interpolators.LINEAR
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.PopupCategory
import com.android.launcher3.popup.PopupContainer
import com.android.launcher3.popup.PopupControllerForAppIcon
import com.android.launcher3.popup.PopupData
import com.android.launcher3.popup.ui.PopupItem
import com.android.launcher3.shortcuts.DeepShortcutView
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One glass menu for the desktop's right-click, a taskbar icon's right-click and an All Apps icon's right-click
 * (openbook/ROADMAP.md M2 #11; OpenBook's docs/launcher-design.md §4).
 *
 * AOSP 17 draws all three with its Compose popup (`PopupContainer.showComposePopup`, `expandable_long_press_menu`). A
 * caller that wants OpenBook's menu gives the popup a [Spec] before showing it: what kind of menu it is, where it is
 * anchored, its header and window rows, how its items group, and labels or hints to replace. The popup then draws
 * [OpenBookMenuContent] in place of AOSP's popup, on [Glass], placed by [Menu.place] and animated by [Menu]. Without a
 * spec, or where [isEnabled] is false, AOSP's popup is unchanged.
 *
 * - **Material:** glass (§6.3) with 16 dp corners, 6 dp padding and a menu's lighter shadow. The platform's blur is a
 *   hidden API, so the glass comes from the activity context ([GlassHost], in quickstep); without one, the 96 % tint.
 * - **Rows:** 36 dp for pointer and keyboard, 48 dp when opened by touch; 20 dp icons, labels at 14/20, shortcut hints
 *   at 12/16 that turn to ink on a hovered row; dividers between groups.
 * - **Placement:** at the pointer, down and right, flipping at the display's edges; a taskbar icon's menu is centred
 *   over the icon, 8 dp above the bar. No arrow.
 * - **Keyboard:** Shift+F10 or the menu key opens the focused item's menu ([isMenuKey]); ↑ ↓ (and Tab) move, wrapping;
 *   Enter activates; Esc closes.
 * - **Motion:** opens from 0.94 about the anchor's corner with a fade, 150 ms emphasised decelerate; closes with a
 *   100 ms fade. Remove animations makes both cuts.
 *
 * [styleViewPopup] gives AOSP's view popups (`ArrowPopup`: a taskbar folder's menu, for one) the same glass, rows and
 * no arrow.
 */
object OpenBookMenu {
    const val TAG = "OpenBookMenu"

    /** The glass under a menu. [surfaceAlpha] fades its blur region, which a view's alpha doesn't reach. */
    interface Glass {
        val drawable: Drawable
        var radius: Float
        var surfaceAlpha: Float
        val blurring: Boolean
    }

    /** An activity context that can lay glass under a view of its window (quickstep: the platform's blur). */
    interface GlassHost {
        fun createMenuGlass(view: View): Glass?
    }

    enum class Kind { DESKTOP, TASKBAR, APP }

    sealed interface Anchor

    /**
     * At a point on the screen: the menu opens down and right of it, flipping at the edges. Anchors are in screen
     * coordinates, because a menu's window can change size (and so its origin) as the menu opens: the taskbar's
     * window grows to the full screen for a menu.
     */
    data class AtPoint(val x: Int, val y: Int) : Anchor

    /** Centred over [centerX], its bottom 8 dp above [barTop] (screen coordinates). */
    data class AboveBar(val centerX: Int, val barTop: Int) : Anchor

    /** In the middle of the drag layer (the menu key with nothing focused). */
    data object Centered : Anchor

    /** The taskbar menu's header: the app's icon as the taskbar draws it, and its name. */
    class Header(val icon: Drawable?, val title: CharSequence)

    /** One of an app's windows: its title, whether it is the window in front, and what a click does. */
    class Window(val title: CharSequence, val front: Boolean, val onClick: () -> Unit)

    class Spec(
        val kind: Kind,
        val anchor: Anchor,
        val byKeyboard: Boolean,
        val byTouch: Boolean,
        val header: Header? = null,
        val windows: List<Window> = emptyList(),
        /** The system items in groups, in order; a divider goes between groups. Items left out aren't shown. */
        val arrange: (List<PopupItem>) -> List<List<PopupItem>> = { listOf(it) },
        /** A label in place of the item's own (e.g. "Close 2 windows"), or null. */
        val label: (PopupItem) -> String? = { null },
        /** A shortcut hint at the end of the row (e.g. "Middle-click"), or null. */
        val hint: (PopupItem) -> String? = { null },
    ) {
        override fun toString() =
            "$kind $anchor keyboard=$byKeyboard touch=$byTouch header=${header?.title} " +
                "windows=${windows.size}${windows.joinToString(",", " [", "]") { (if (it.front) "*" else "") + it.title }}"
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean = context.resources.getBoolean(R.bool.desktop_form_factor)

    /** The activity context [context] belongs to (a launcher, taskbar or overlay window), or null. */
    private fun hostOf(context: Context): ActivityContext? {
        var c: Context? = context
        while (c != null) {
            if (c is ActivityContext) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    // ---- Input: where the last press was, and whether a menu is being opened from the keyboard ----

    @Volatile private var downRawX = 0f
    @Volatile private var downRawY = 0f
    @Volatile private var downTime = 0L
    @Volatile private var downTouch = false
    private val keyboard = ThreadLocal<Boolean>()

    /** Every press in a launcher window (`BaseDragLayer.dispatchTouchEvent`): a right-click's menu opens there. */
    @JvmStatic
    fun onTouchEvent(ev: MotionEvent) {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return
        downRawX = ev.rawX
        downRawY = ev.rawY
        downTime = ev.eventTime
        downTouch = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
    }

    /** Shift+F10 or the menu key (on its own). */
    @JvmStatic
    fun isMenuKey(event: KeyEvent): Boolean =
        (event.keyCode == KeyEvent.KEYCODE_MENU && event.hasNoModifiers()) ||
            (event.keyCode == KeyEvent.KEYCODE_F10 && event.hasModifiers(KeyEvent.META_SHIFT_ON))

    /** Runs [open] as a menu opened from the keyboard: it is placed by the focused item and its first row focused. */
    @JvmStatic
    fun <T> openFromKeyboard(open: () -> T): T {
        keyboard.set(true)
        try {
            return open()
        } finally {
            keyboard.remove()
        }
    }

    @JvmStatic
    val openingFromKeyboard: Boolean
        get() = keyboard.get() == true

    /**
     * The launcher's keys no view took: Shift+F10 or the menu key opens the focused icon's menu, or with nothing
     * focused the desktop's menu, from the keyboard. Replaces AOSP's menu key, which opened the desktop's menu on key
     * up ([isMenuKeyUp] swallows that).
     */
    @JvmStatic
    fun onLauncherKeyDown(launcher: Launcher, event: KeyEvent): Boolean {
        if (!isEnabled(launcher) || !isMenuKey(event)) return false
        if (event.repeatCount > 0) return true
        if (!launcher.isInState(LauncherState.NORMAL) || launcher.dragController.isDragging ||
            launcher.workspace.isSwitchingState
        ) return true
        val focused = launcher.currentFocus
        if (focused is BubbleTextView && focused.tag is ItemInfo && focused.isShown) {
            if (PopupContainer.getOpen(launcher) == null) {
                openFromKeyboard { PopupControllerForAppIcon<Launcher>().show(focused) }
                Log.d(TAG, "menu key: ${focused.text}")
            }
            return true
        }
        launcher.closeOpenViews()
        openFromKeyboard { launcher.showDefaultOptions(-1f, -1f) }
        Log.d(TAG, "menu key: the desktop")
        return true
    }

    /** AOSP's menu key acted on key up; OpenBook's opened on key down, so the launcher drops the up. */
    @JvmStatic
    fun isMenuKeyUp(context: Context, keyCode: Int): Boolean =
        isEnabled(context) && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_F10)

    /** Whether the press that opens a menu now was a finger (48 dp rows), not a mouse, touchpad or pen. */
    @JvmStatic
    fun openedByTouch(byKeyboard: Boolean) = !byKeyboard && downTouch

    /** The last press on the screen, if it was over [view], else null. */
    private fun lastPress(view: View): AtPoint? {
        if (downTime == 0L) return null
        val p = AtPoint(downRawX.roundToInt(), downRawY.roundToInt())
        return if (screenRect(view).contains(p.x, p.y)) p else null
    }

    /** [view]'s bounds on the screen. */
    @JvmStatic
    fun screenRect(view: View): Rect {
        val o = IntArray(2).also { view.getLocationOnScreen(it) }
        return Rect(o[0], o[1], o[0] + view.width, o[1] + view.height)
    }

    // ---- Specs ----

    /**
     * The desktop's menu, opened at ([x], [y]) of [launcher]'s drag layer, or from the keyboard ([x] < 0) in the
     * middle.
     */
    @JvmStatic
    fun desktop(launcher: ActivityContext, x: Float, y: Float): Spec {
        val byKeyboard = x < 0 || y < 0 || openingFromKeyboard
        val o = IntArray(2).also { launcher.dragLayer.getLocationOnScreen(it) }
        return Spec(
            kind = Kind.DESKTOP,
            anchor = if (byKeyboard) Centered else AtPoint(o[0] + x.roundToInt(), o[1] + y.roundToInt()),
            byKeyboard = byKeyboard,
            byTouch = openedByTouch(byKeyboard),
            arrange = { items ->
                val (first, rest) = items.partition { it.labelResId in DESKTOP_FIRST_GROUP }
                listOf(first, rest)
            },
        )
    }

    /**
     * The desktop's items in design §4's order: Wallpaper and style, Widgets (and Edit home screen where AOSP offers
     * it), a divider, Display settings, Home screen settings, then whatever else AOSP adds (a new folder). The All apps
     * item is left out: the taskbar's apps pill and Meta open All Apps on a desk.
     */
    @JvmStatic
    fun desktopItems(aosp: List<PopupData>): List<PopupData> {
        val display = PopupData(R.drawable.ob_ic_display, R.string.ob_mn_display_settings, PopupCategory.SYSTEM_SHORTCUT)
        { ac, _, _ ->
            ac.asContext().startActivity(
                Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        val kept = aosp.filter { it.labelResId != R.string.all_apps_button_label }
        val first = kept.filter { it.labelResId in DESKTOP_FIRST_GROUP }
        val settings = kept.filter { it.labelResId == R.string.settings_button_text }
        val others = kept.filter { it.labelResId !in DESKTOP_FIRST_GROUP && it.labelResId != R.string.settings_button_text }
        return first + display + settings + others
    }

    private val DESKTOP_FIRST_GROUP =
        setOf(R.string.styles_wallpaper_button_text, R.string.widget_button_text, R.string.edit_home_screen)

    /**
     * An app icon's menu in a launcher window (All Apps, the desktop): at the pointer when right-clicked, under the
     * icon when opened from the keyboard.
     */
    @JvmStatic
    fun app(icon: View): Spec {
        val byKeyboard = openingFromKeyboard
        return Spec(Kind.APP, anchorAtIcon(icon, byKeyboard), byKeyboard, openedByTouch(byKeyboard))
    }

    /** At the last press when it was over [icon]; otherwise (the keyboard) under [icon]'s bottom left. */
    @JvmStatic
    fun anchorAtIcon(icon: View, byKeyboard: Boolean): Anchor {
        if (!byKeyboard) lastPress(icon)?.let { return it }
        val r = screenRect(icon)
        return AtPoint(r.left, r.bottom)
    }

    // ---- A menu on screen ----

    /** The runtime side of a menu shown by [popup] (AOSP's `PopupContainer`): glass, placement and motion. */
    class Menu(private val popup: ViewGroup, val spec: Spec) {
        private val context = popup.context
        private val res = context.resources
        private val t = Tokens(context)
        val glass: Glass? =
            (hostOf(context) as? GlassHost)?.createMenuGlass(popup)
        private var pivotX = 0f
        private var pivotY = 0f
        private var dragLayer: BaseDragLayer<*>? = null
        private var pendingOpen: (() -> Unit)? = null
        /** Shown in whatever window it has, after waiting [WAIT_MS] for that window to grow. */
        private var forced = false
        private val giveUpWaiting = Runnable {
            val open = pendingOpen ?: return@Runnable
            forced = true
            Log.w(TAG, "menu: its window didn't grow in $WAIT_MS ms; shown in the window it has")
            if (place()) {
                pendingOpen = null
                open()
            }
        }
        private val relayout = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l == or - ol && b - t == ob - ot) return@OnLayoutChangeListener
            // The window changed size (the taskbar's grows for a menu): place the menu again, and open it if it waited.
            popup.post {
                val fits = place()
                val open = pendingOpen
                if (fits && open != null) {
                    pendingOpen = null
                    open()
                }
            }
        }

        init {
            if (glass != null) {
                glass.radius = t.radius
                popup.background = glass.drawable
            } else {
                popup.background = GradientDrawable().apply {
                    cornerRadius = t.radius
                    setColor(ColorUtils.setAlphaComponent(t.surface, (255 * t.solidAlpha).roundToInt()))
                }
            }
            popup.setPadding(t.padding, t.padding, t.padding, t.padding)
            popup.elevation = 0f
            popup.clipChildren = false
            popup.clipToPadding = false
            Log.d(TAG, "menu: $spec, glass ${if (glass == null) "none (tint)" else "on"}")
        }

        /**
         * Places the menu in [layer] and runs [open]. A menu opened from the keyboard on the taskbar comes before the
         * taskbar's window has grown to the full screen: it waits (invisible) for that layout, at most [WAIT_MS].
         */
        fun show(layer: BaseDragLayer<*>, open: () -> Unit) {
            dragLayer = layer
            layer.addOnLayoutChangeListener(relayout)
            if (place()) {
                open()
            } else {
                popup.visibility = View.INVISIBLE
                pendingOpen = open
                popup.postDelayed(giveUpWaiting, WAIT_MS)
                Log.d(TAG, "menu waits for its window (${layer.width}x${layer.height})")
            }
        }

        /** The menu closed: stop following its window. */
        fun detach() {
            Log.d(TAG, "menu closed")
            dragLayer?.removeOnLayoutChangeListener(relayout)
            popup.removeCallbacks(giveUpWaiting)
            dragLayer = null
            pendingOpen = null
        }

        /**
         * Sizes and places the popup in its drag layer (its layout params set to a fixed position) and logs it. The
         * menu is never taller than the room it has (above the bar for a taskbar icon's, between the bars
         * otherwise): beyond that its rows scroll. Returns false, placing nothing, while its window is smaller than
         * the screen and too small for it (the taskbar's, before it grows for the menu).
         */
        private fun place(): Boolean {
            val layer = dragLayer ?: return false
            // The drag layer's insets, or the window's bars where larger: a desk's launcher window lays out under the
            // taskbar (its drag layer's bottom inset is 0), and a menu must not slide under the bar.
            val insets = Rect(layer.insets)
            layer.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.tappableElement())
                ?.let {
                    insets.set(
                        max(insets.left, it.left), max(insets.top, it.top),
                        max(insets.right, it.right), max(insets.bottom, it.bottom),
                    )
                }
            val left = insets.left + t.margin
            val top = insets.top + t.margin
            val right = layer.width - insets.right - t.margin
            val bottom = layer.height - insets.bottom - t.margin
            val o = IntArray(2).also { layer.getLocationOnScreen(it) }
            val anchor = spec.anchor
            val maxW = right - left
            val maxH = if (anchor is AboveBar) anchor.barTop - o[1] - t.barGap - top else bottom - top
            if (maxW <= 0 || maxH <= 0) return false
            popup.measure(
                View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST),
            )
            val w = popup.measuredWidth
            val h = popup.measuredHeight
            val screen = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds?.height()
                ?: layer.height
            if (!forced && h >= maxH && layer.height < screen) return false
            val scrolls = h >= maxH
            var x: Int
            var y: Int
            when (val a = spec.anchor) {
                is AtPoint -> {
                    val ax = a.x - o[0]
                    val ay = a.y - o[1]
                    x = ax
                    y = ay
                    pivotX = 0f
                    pivotY = 0f
                    if (x + w > right) {
                        x = ax - w
                        pivotX = w.toFloat()
                    }
                    if (y + h > bottom) {
                        y = ay - h
                        pivotY = h.toFloat()
                    }
                }
                is AboveBar -> {
                    x = a.centerX - o[0] - w / 2
                    y = a.barTop - o[1] - t.barGap - h
                    pivotY = h.toFloat()
                }
                Centered -> {
                    x = (layer.width - w) / 2
                    y = (layer.height - h) / 2
                    pivotY = h / 2f
                }
            }
            x = max(left, min(x, right - w))
            y = max(top, min(y, bottom - h))
            val a = spec.anchor
            if (a is AboveBar) pivotX = (a.centerX - o[0] - x).toFloat().coerceIn(0f, w.toFloat())
            if (a == Centered) pivotX = w / 2f
            val lp = popup.layoutParams
            if (lp is BaseDragLayer.LayoutParams) {
                lp.customPosition = true
                lp.ignoreInsets = true
                lp.x = x
                lp.y = y
                lp.width = w
                lp.height = h
                popup.layoutParams = lp
            } else {
                popup.x = x.toFloat()
                popup.y = y.toFloat()
            }
            popup.translationX = 0f
            popup.translationY = 0f
            Log.d(TAG, "menu at [${x + o[0]},${y + o[1]}][${x + o[0] + w},${y + o[1] + h}] on screen, " +
                "${w}x$h, in a ${layer.width}x${layer.height} window, insets $insets, ${spec.kind}" +
                if (scrolls) ", its rows scroll (at most $maxH px tall)" else "")
            return true
        }

        /** Opening: 0.94 → 1 about the anchor's corner and a fade, 150 ms emphasised decelerate. */
        fun openAnimator(): Animator {
            popup.pivotX = pivotX
            popup.pivotY = pivotY
            val fade = ValueAnimator.ofFloat(0f, 1f).apply { addUpdateListener { setAlpha(it.animatedValue as Float) } }
            fade.interpolator = LINEAR
            val scaleX = ObjectAnimator.ofFloat(popup, View.SCALE_X, t.openScale, 1f)
            val scaleY = ObjectAnimator.ofFloat(popup, View.SCALE_Y, t.openScale, 1f)
            scaleX.interpolator = EMPHASIZED_DECELERATE
            scaleY.interpolator = EMPHASIZED_DECELERATE
            setAlpha(0f)
            return AnimatorSet().apply {
                playTogether(fade, scaleX, scaleY)
                duration = t.openMs
            }
        }

        /** Logs how long the open or close took, from [startMs] (`SystemClock.uptimeMillis`): Remove animations cuts. */
        fun logMotion(what: String, startMs: Long) {
            Log.d(TAG, "menu $what: ${android.os.SystemClock.uptimeMillis() - startMs} ms")
        }

        /** Closing: a 100 ms fade. */
        fun closeAnimator(): Animator {
            val from = popup.alpha
            return ValueAnimator.ofFloat(from, 0f).apply {
                addUpdateListener { setAlpha(it.animatedValue as Float) }
                interpolator = LINEAR
                duration = t.closeMs
            }
        }

        private fun setAlpha(a: Float) {
            popup.alpha = a
            glass?.surfaceAlpha = a
        }

        /**
         * Bumped when the menu, opened from the keyboard, is shown: [OpenBookMenuContent] then gives its first row the
         * focus (a FocusRequester, after the caller's own `requestFocus` on the popup).
         */
        val focusSignal = mutableIntStateOf(0)

        /** The menu is visible: a menu opened from the keyboard focuses its first row. */
        fun focusFirstRow() {
            if (spec.byKeyboard) focusSignal.intValue++
        }
    }

    // ---- AOSP's view popups (ArrowPopup) ----

    /**
     * Gives an `ArrowPopup` of view rows the menu's tokens before it is placed: glass, 16 dp corners, 6 dp padding,
     * 36 dp rows with 20 dp icons and 14/20 labels, the hover layer, and no arrow. Returns whether it did.
     */
    @JvmStatic
    fun styleViewPopup(popup: ViewGroup): Boolean {
        val context = popup.context
        if (!isEnabled(context)) return false
        val t = Tokens(context, openedByTouch(false))
        val glass = (hostOf(context) as? GlassHost)?.createMenuGlass(popup)
        if (glass != null) {
            glass.radius = t.radius
            popup.background = glass.drawable
        } else {
            popup.background = GradientDrawable().apply {
                cornerRadius = t.radius
                setColor(ColorUtils.setAlphaComponent(t.surface, (255 * t.solidAlpha).roundToInt()))
            }
        }
        popup.setPadding(t.padding, t.padding, t.padding, t.padding)
        popup.elevation = 0f
        styleViewRows(popup, t)
        Log.d(TAG, "view popup ${popup.javaClass.simpleName}: glass ${if (glass == null) "none (tint)" else "on"}")
        return true
    }

    /** Re-applies the rows' backgrounds after `ArrowPopup.assignMarginsAndBackgrounds`. Returns whether it did. */
    @JvmStatic
    fun styleViewRows(popup: ViewGroup): Boolean {
        if (!isEnabled(popup.context)) return false
        styleViewRows(popup, Tokens(popup.context, openedByTouch(false)))
        return true
    }

    private fun styleViewRows(group: ViewGroup, t: Tokens) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is DeepShortcutView) {
                // The row itself draws nothing (DeepShortcutView reads a GradientDrawable or AOSP's blur layers as its
                // background); its clickable text view, which gets hover and press, carries the state layer.
                child.background = null
                child.elevation = 0f
                child.minimumHeight = t.row
                child.layoutParams?.let {
                    it.height = t.row
                    (it as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
                    child.layoutParams = it
                }
                child.bubbleText?.let { text ->
                    text.background = rowBackground(t)
                    text.minHeight = t.row
                    // 12 dp side, the 20 dp icon and a 12 dp gap before the label, as in the Compose menu.
                    text.setPaddingRelative(t.rowPadding + t.icon + t.iconGap, text.paddingTop, t.rowPadding,
                        text.paddingBottom)
                    text.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.text)
                    text.typeface = t.typeface
                    text.setTextColor(t.onSurface)
                }
                child.iconView?.let { icon ->
                    icon.layoutParams?.let {
                        it.width = t.icon
                        it.height = t.icon
                        (it as? ViewGroup.MarginLayoutParams)?.marginStart = t.rowPadding
                        icon.layoutParams = it
                    }
                    icon.backgroundTintList = ColorStateList.valueOf(t.onSurfaceVariant)
                }
            } else if (child is ViewGroup) {
                child.background = null
                child.elevation = 0f
                styleViewRows(child, t)
            }
        }
    }

    /** A row's state layer: `onSurface` at 8 % hovered, 12 % pressed, and the 2 dp ring while focused. */
    private fun rowBackground(t: Tokens): Drawable {
        fun layer(alpha: Float, ring: Boolean) = GradientDrawable().apply {
            cornerRadius = t.rowRadius
            setColor(ColorUtils.setAlphaComponent(t.onSurface, (255 * alpha).roundToInt()))
            if (ring) setStroke(t.ring, t.primary)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), layer(0.12f, false))
            addState(intArrayOf(android.R.attr.state_focused), layer(0f, true))
            addState(intArrayOf(android.R.attr.state_hovered), layer(0.08f, false))
            addState(intArrayOf(), layer(0f, false))
            setEnterFadeDuration(t.hoverInMs)
            setExitFadeDuration(t.hoverOutMs)
        }
    }

    /** The menu's tokens (res/values/openbook_tokens.xml) and the Material roles it uses. */
    internal class Tokens(c: Context, touch: Boolean = false) {
        private val res = c.resources
        val radius = res.getDimension(R.dimen.ob_mn_radius)
        val padding = res.getDimensionPixelSize(R.dimen.ob_mn_padding)
        val margin = res.getDimensionPixelSize(R.dimen.ob_mn_margin)
        val barGap = res.getDimensionPixelSize(R.dimen.ob_mn_bar_gap)
        val row = res.getDimensionPixelSize(if (touch) R.dimen.ob_mn_row_touch else R.dimen.ob_mn_row)
        val rowRadius = res.getDimension(R.dimen.ob_mn_row_radius)
        val rowPadding = res.getDimensionPixelSize(R.dimen.ob_mn_row_padding)
        val iconGap = res.getDimensionPixelSize(R.dimen.ob_mn_icon_gap)
        val icon = res.getDimensionPixelSize(R.dimen.ob_mn_icon)
        val text = res.getDimension(R.dimen.ob_mn_text)
        val ring = res.getDimensionPixelSize(R.dimen.ob_focus_ring)
        val openScale = res.getFloat(R.dimen.ob_mn_open_scale)
        val openMs = res.getInteger(R.integer.ob_mn_open_ms).toLong()
        val closeMs = res.getInteger(R.integer.ob_mn_close_ms).toLong()
        val hoverInMs = res.getInteger(R.integer.ob_duration_hover_in_ms)
        val hoverOutMs = res.getInteger(R.integer.ob_duration_hover_out_ms)
        val solidAlpha = res.getFloat(R.dimen.ob_glass_solid_alpha)
        val typeface: Typeface =
            Typeface.create(Typeface.create(FAMILY, Typeface.NORMAL), res.getInteger(R.integer.ob_mn_weight), false)
        private val dark = Utilities.isDarkTheme(c)
        val surface = c.getColor(if (dark) R.color.materialColorSurfaceContainer else R.color.materialColorSurface)
        val onSurface = c.getColor(R.color.materialColorOnSurface)
        val onSurfaceVariant = c.getColor(R.color.materialColorOnSurfaceVariant)
        val primary = c.getColor(R.color.materialColorPrimary)
    }

    /** How long a menu waits for its window to grow before it is shown in the window it has. */
    private const val WAIT_MS = 1000L

    /** Google Sans Flex's body family (OpenBook's docs/googlebook-ui.md §2.1); the default sans-serif without it. */
    internal const val FAMILY = "variable-body-medium"
    internal const val FAMILY_SMALL = "variable-body-small"
}
