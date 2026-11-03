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
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

    /** At a point of the drag layer: the menu opens down and right of it, flipping at the edges. */
    data class AtPoint(val x: Int, val y: Int) : Anchor

    /** Centred over [centerX], its bottom 8 dp above [barTop] (drag layer coordinates). */
    data class AboveBar(val centerX: Int, val barTop: Int) : Anchor

    /** In the middle of the drag layer (the menu key with nothing focused). */
    data object Centered : Anchor

    class Header(val icon: Bitmap?, val title: CharSequence)

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
            "$kind ${anchor} keyboard=$byKeyboard touch=$byTouch header=${header?.title} windows=${windows.size}"
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
    private fun openedByTouch(byKeyboard: Boolean) = !byKeyboard && downTouch

    /** The last press in [dragLayer]'s coordinates, if it was over [view] (or [view] is null), else null. */
    private fun lastPress(dragLayer: View, view: View?): IntArray? {
        if (downTime == 0L) return null
        val origin = IntArray(2).also { dragLayer.getLocationOnScreen(it) }
        val p = intArrayOf((downRawX - origin[0]).roundToInt(), (downRawY - origin[1]).roundToInt())
        if (view != null) {
            val r = Rect().also { view.getGlobalVisibleRect(it) }
            if (!r.contains(downRawX.roundToInt(), downRawY.roundToInt())) return null
        }
        return p
    }

    // ---- Specs ----

    /** The desktop's menu, opened at ([x], [y]) of the drag layer, or from the keyboard ([x] < 0) in the middle. */
    @JvmStatic
    fun desktop(x: Float, y: Float): Spec {
        val byKeyboard = x < 0 || y < 0 || openingFromKeyboard
        return Spec(
            kind = Kind.DESKTOP,
            anchor = if (byKeyboard) Centered else AtPoint(x.roundToInt(), y.roundToInt()),
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
    fun app(context: ActivityContext, icon: View): Spec {
        val byKeyboard = openingFromKeyboard
        return Spec(Kind.APP, anchorAtIcon(context, icon, byKeyboard), byKeyboard, openedByTouch(byKeyboard))
    }

    /** At the last press when it was over [icon]; otherwise (the keyboard) under [icon]'s bottom left. */
    @JvmStatic
    fun anchorAtIcon(context: ActivityContext, icon: View, byKeyboard: Boolean): Anchor {
        val dragLayer = context.dragLayer
        if (!byKeyboard) lastPress(dragLayer, icon)?.let { return AtPoint(it[0], it[1]) }
        val r = Rect()
        dragLayer.getDescendantRectRelativeToSelf(icon, r)
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

        /** Sizes and places the popup in [dragLayer] (its layout params set to a fixed position), and logs it. */
        fun place(dragLayer: ViewGroup, insets: Rect) {
            popup.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = popup.measuredWidth
            val h = popup.measuredHeight
            val left = insets.left + t.margin
            val top = insets.top + t.margin
            val right = dragLayer.width - insets.right - t.margin
            val bottom = dragLayer.height - insets.bottom - t.margin
            var x: Int
            var y: Int
            when (val a = spec.anchor) {
                is AtPoint -> {
                    x = a.x
                    y = a.y
                    pivotX = 0f
                    pivotY = 0f
                    if (x + w > right) {
                        x = a.x - w
                        pivotX = w.toFloat()
                    }
                    if (y + h > bottom) {
                        y = a.y - h
                        pivotY = h.toFloat()
                    }
                }
                is AboveBar -> {
                    x = a.centerX - w / 2
                    y = a.barTop - t.barGap - h
                    pivotY = h.toFloat()
                }
                Centered -> {
                    x = (dragLayer.width - w) / 2
                    y = (dragLayer.height - h) / 2
                    pivotY = h / 2f
                }
            }
            x = max(left, min(x, right - w))
            y = max(top, min(y, bottom - h))
            if (spec.anchor is AboveBar) pivotX = (spec.anchor.centerX - x).toFloat().coerceIn(0f, w.toFloat())
            if (spec.anchor == Centered) pivotX = w / 2f
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
            Log.d(TAG, "menu at [$x,$y][${x + w},${y + h}] in ${dragLayer.width}x${dragLayer.height}, " +
                "insets $insets, ${spec.kind}")
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

        /** The menu was opened from the keyboard: its first row takes the focus (posted, after the caller's focus). */
        fun focusFirstRow(content: View) {
            if (!spec.byKeyboard) return
            content.post {
                val ok = content.requestFocus(View.FOCUS_DOWN)
                Log.d(TAG, "keyboard: first row focused $ok")
            }
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
        val t = Tokens(context)
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
        styleViewRows(popup, Tokens(popup.context))
        return true
    }

    private fun styleViewRows(group: ViewGroup, t: Tokens) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is DeepShortcutView) {
                child.background = rowBackground(t)
                child.layoutParams?.let {
                    it.height = t.row
                    (it as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
                    child.layoutParams = it
                }
                child.bubbleText?.let { text ->
                    text.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.text)
                    text.typeface = t.typeface
                    text.setTextColor(t.onSurface)
                }
                child.iconView?.let { icon ->
                    icon.layoutParams?.let {
                        it.width = t.icon
                        it.height = t.icon
                        icon.layoutParams = it
                    }
                    icon.backgroundTintList = ColorStateList.valueOf(t.onSurfaceVariant)
                }
            } else if (child is ViewGroup) {
                child.background = null
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
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), layer(0.12f, false))
            addState(intArrayOf(android.R.attr.state_focused), layer(0f, true))
            addState(intArrayOf(android.R.attr.state_hovered), layer(0.08f, false))
            addState(intArrayOf(), layer(0f, false))
            setEnterFadeDuration(t.hoverInMs)
            setExitFadeDuration(t.hoverOutMs)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.TRANSPARENT), states, null)
    }

    /** The menu's tokens (res/values/openbook_tokens.xml) and the Material roles it uses. */
    internal class Tokens(c: Context) {
        private val res = c.resources
        val radius = res.getDimension(R.dimen.ob_mn_radius)
        val padding = res.getDimensionPixelSize(R.dimen.ob_mn_padding)
        val margin = res.getDimensionPixelSize(R.dimen.ob_mn_margin)
        val barGap = res.getDimensionPixelSize(R.dimen.ob_mn_bar_gap)
        val row = res.getDimensionPixelSize(R.dimen.ob_mn_row)
        val rowRadius = res.getDimension(R.dimen.ob_mn_row_radius)
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

    /** Google Sans Flex's body family (OpenBook's docs/googlebook-ui.md §2.1); the default sans-serif without it. */
    internal const val FAMILY = "variable-body-medium"
    internal const val FAMILY_SMALL = "variable-body-small"
}
