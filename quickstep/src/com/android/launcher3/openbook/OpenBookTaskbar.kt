/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.ActivityManager.RunningTaskInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.android.app.animation.Interpolators.STANDARD
import com.android.internal.dynamicanimation.animation.DynamicAnimation
import com.android.internal.dynamicanimation.animation.FloatValueHolder
import com.android.internal.dynamicanimation.animation.SpringAnimation
import com.android.internal.dynamicanimation.animation.SpringForce
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.BubbleTextView
import com.android.launcher3.BubbleTextView.RunningAppState
import com.android.launcher3.Flags
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.model.data.TaskItemInfo
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.launcher3.taskbar.TaskbarControllers
import com.android.launcher3.taskbar.TaskbarView
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR
import com.android.launcher3.util.Executors.getTaskbarUiThread
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.ArrowTipView
import com.android.quickstep.FocusState
import com.android.quickstep.SystemUiProxy
import com.android.quickstep.util.SingleTask
import com.android.systemui.shared.system.ActivityManagerWrapper
import java.io.PrintWriter

/**
 * OpenBook's taskbar (openbook/ROADMAP.md M2 #8; the design is OpenBook's docs/launcher-design.md §2): 48 dp targets on
 * a 4 dp grid with the row 2 dp above centre (`TaskbarView`), our own apps pill, a quiet divider, and one line of state
 * under each app's target, on the bar 1 dp under it:
 * - running: a 6 × 3 dp line in `onSurfaceVariant`;
 * - in front (it has the focused window): a 16 × 3 dp line in `primary`, and a 6 % `onSurface` tile on the target;
 * - more than one window: the line and a 3 dp dot;
 * - every window minimized: a hollow 8 × 3 dp line.
 * The line's width springs (`spring.settle`) and its colour fades (120 ms). Targets get a hover layer (8 %, 12 % on the
 * front tile), a press scale (0.92 on `spring.tick`, layer 12 %) and a 2 dp `primary` keyboard focus ring 2 dp outside.
 * The tooltip is restyled ([styleTooltip]) and the bar is glass ([TaskbarGlass]). The bar stays 56 dp, so window insets
 * don't move.
 *
 * AOSP 17's `RunningAppState` has no "in front" state and no window count. In front is the task WM Shell reports as
 * globally focused (`FocusState`, the focus-transition listener; read once from the running task at start). The window
 * count is the app's running desktop tasks, minimized ones included (`TaskbarRecentAppsController.openBookTaskIds`).
 *
 * Keyboard: Tab on the empty desktop ([onLauncherKeyDown]) puts keyboard focus on the apps pill (Shift+Tab: the last
 * app); Tab, Shift+Tab and the arrow keys walk the targets, Enter or Space opens one, Esc gives focus back. The taskbar
 * window is focusable only meanwhile, as AOSP makes it while one of its menus is open.
 *
 * Applies to the persistent taskbar of a launcher whose resources say `bool/desktop_form_factor` (OpenBook's desktop
 * overlay). The transient taskbar, the overflow's icons and other form factors keep AOSP's look.
 */
class OpenBookTaskbar(
    private val context: TaskbarActivityContext,
    private val controllers: TaskbarControllers,
    private val taskbarView: TaskbarView,
) : FocusState.FocusChangeListener {

    private val t = Tokens(context)
    private val appDecor = AppDecor()
    private val pillDecor = PillDecor()
    private val dividerDecor = DividerDecor()
    private var frontTaskId = INVALID_TASK
    private var allAppsOpen = false
    private var keyboardFocus = false
    private var destroyed = false

    init {
        taskbarView.allAppsButtonContainer.taskbarIconDecor = pillDecor
        taskbarView.taskbarDividerViewContainer?.taskbarIconDecor = dividerDecor
        MAIN_EXECUTOR.execute { SystemUiProxy.INSTANCE[context].focusState.addListener(this) }
        val displayId = context.displayId
        UI_HELPER_EXECUTOR.execute {
            val top = ActivityManagerWrapper.getInstance().getRunningTask(/* filterOnlyVisibleRecents= */ false)
            val id = if (top != null && top.displayId == displayId && top.isVisible) top.taskId else INVALID_TASK
            getTaskbarUiThread().execute { if (frontTaskId == INVALID_TASK) setFrontTask(id) }
        }
    }

    fun onDestroy() {
        destroyed = true
        MAIN_EXECUTOR.execute { SystemUiProxy.INSTANCE[context].focusState.removeListener(this) }
    }

    /** Main thread (FocusState): the globally focused task changed. */
    override fun onFocusedTaskChanged(focusedTaskInfo: RunningTaskInfo) {
        val id = if (focusedTaskInfo.displayId == context.displayId) focusedTaskInfo.taskId else INVALID_TASK
        getTaskbarUiThread().execute { setFrontTask(id) }
    }

    private fun setFrontTask(id: Int) {
        if (destroyed || id == frontTaskId) return
        frontTaskId = id
        Log.d(TAG, "front task $id")
        // A window came to the front (opened from the bar, or otherwise): it gets the keyboard, not the bar.
        if (keyboardFocus) leaveKeyboardFocus("a window came to the front")
        controllers.taskbarViewController.updateIconViewsRunningStates()
    }

    /**
     * Taskbar UI thread, after AOSP set [btv]'s running state: its line of state, front tile and window dot. Icons
     * outside the bar (the overflow's) keep AOSP's line.
     */
    fun update(btv: BubbleTextView, animate: Boolean) {
        // The pill and the divider are BubbleTextViews too, with their own decor.
        if (btv === taskbarView.allAppsButtonContainer || btv === taskbarView.taskbarDividerViewContainer) return
        if (!isOnBar(btv)) {
            if (btv.taskbarIconDecor === appDecor) btv.taskbarIconDecor = null
            return
        }
        if (btv.taskbarIconDecor !== appDecor) btv.taskbarIconDecor = appDecor
        val running = btv.runningAppState ?: RunningAppState.NOT_RUNNING
        val tasks = if (running == RunningAppState.NOT_RUNNING) emptyList() else taskIds(btv.tag)
        val front = running == RunningAppState.RUNNING && frontTaskId != INVALID_TASK && frontTaskId in tasks &&
            controllers.taskbarRecentAppsController.getRunningAppState(frontTaskId) == RunningAppState.RUNNING
        state(btv).set(running, front, multi = tasks.size > 1, animate = animate && btv.isAttachedToWindow)
    }

    /** Taskbar UI thread: All Apps (the taskbar's) starts opening or closing; the pill shows it. */
    fun setAllAppsOpen(open: Boolean) {
        if (open == allAppsOpen) return
        allAppsOpen = open
        val pill = taskbarView.allAppsButtonContainer
        state(pill).open.ease(if (open) 1f else 0f, t.fadeMs, pill.isAttachedToWindow)
    }

    /** Taskbar UI thread: keyboard focus into the taskbar, on the apps pill or, [forward] false, the last app. */
    fun focusFromKeyboard(forward: Boolean) {
        val target: View = if (forward) taskbarView.allAppsButtonContainer else lastTarget()
        keyboardFocus = true
        context.setTaskbarWindowFocusable(/* focusable= */ true, /* imeFocusable= */ false)
        val focused = target.requestFocus()
        Log.d(TAG, "keyboard focus into the taskbar (${if (forward) "first" else "last"} target, focused $focused)")
    }

    private fun onKeyEvent(event: KeyEvent): Boolean {
        if (!keyboardFocus || event.action != KeyEvent.ACTION_DOWN || !event.hasNoModifiers()) return false
        if (event.keyCode != KeyEvent.KEYCODE_ESCAPE && event.keyCode != KeyEvent.KEYCODE_BACK) return false
        leaveKeyboardFocus("Esc")
        return true
    }

    private fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus && keyboardFocus) leaveKeyboardFocus("focus left the taskbar")
    }

    /** Gives keyboard focus back: the taskbar window is no longer focusable, unless one of its menus is open. */
    private fun leaveKeyboardFocus(why: String) {
        keyboardFocus = false
        if (AbstractFloatingView.getTopOpenView(context) == null) {
            context.setTaskbarWindowFocusable(/* focusable= */ false, /* imeFocusable= */ false)
        }
        Log.d(TAG, "keyboard focus out of the taskbar ($why)")
    }

    private fun lastTarget(): View {
        var last: View = taskbarView.allAppsButtonContainer
        fun walk(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is BubbleTextView && child.visibility == View.VISIBLE && child.isFocusable &&
                    child !== taskbarView.taskbarDividerViewContainer) last = child
                else if (child is ViewGroup && child !is BubbleTextView) walk(child)
            }
        }
        walk(taskbarView)
        return last
    }

    /** `dumpsys activity com.android.launcher3`: what each target shows (the VM test reads it). */
    fun dump(prefix: String, pw: PrintWriter) {
        pw.println("${prefix}OpenBookTaskbar: frontTask=$frontTaskId allAppsOpen=$allAppsOpen keyboardFocus=$keyboardFocus")
        fun line(v: BubbleTextView) {
            val label = v.contentDescription ?: v.text
            val d = when (v.taskbarIconDecor) {
                appDecor -> "app"
                pillDecor -> "pill"
                dividerDecor -> "divider"
                null -> "none"
                else -> "other"
            }
            val s = v.getTag(R.id.openbook_taskbar_icon_state) as? IconState
            pw.println("$prefix\ttarget \"$label\" decor=$d alpha=${v.alpha} visible=${v.isShown} " +
                "bounds=[${v.left},${v.top}][${v.right},${v.bottom}] scroll=${v.scrollX},${v.scrollY} " +
                "focused=${v.isFocused}" +
                (s?.let { " running=${it.running} front=${it.front} line=${it.width.value} dot=${it.dot.value} " +
                    "fill=${it.fill.value} layer=${it.layer.value} scale=${it.scale.value} open=${it.open.value}"
                } ?: ""))
        }
        fun walk(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is BubbleTextView) line(child) else if (child is ViewGroup) walk(child)
            }
        }
        walk(taskbarView)
    }

    private fun isOnBar(v: View) = v.parent === taskbarView || v.parent === taskbarView.taskbarHotseatIconsContainer

    /** The running desktop tasks an app target stands for: one per window. */
    private fun taskIds(tag: Any?): List<Int> {
        val recents = controllers.taskbarRecentAppsController
        return when (tag) {
            is SingleTask ->
                if (Flags.enableMultiInstanceMenuTaskbar()) {
                    recents.openBookTaskIds(tag.task.key.packageName, tag.task.key.userId)
                } else {
                    listOf(tag.task.key.id) // one target per window
                }
            is TaskItemInfo ->
                tag.targetPackage?.let { recents.openBookTaskIds(it, tag.user.identifier) } ?: listOf(tag.taskId)
            else -> emptyList()
        }
    }

    private fun state(v: BubbleTextView): IconState =
        v.getTag(R.id.openbook_taskbar_icon_state) as? IconState
            ?: IconState(v).also { v.setTag(R.id.openbook_taskbar_icon_state, it) }

    /** One target's animated state. */
    private inner class IconState(private val view: BubbleTextView) {
        var running = RunningAppState.NOT_RUNNING
        var front = false
        /** A finger or the mouse's button is down on the target (see [TaskbarIconDecor.onTouchEvent]). */
        var pressed = false
        val width = Anim(view, 0f)
        val color = ColorAnim(view, t.onSurfaceVariant)
        val fill = Anim(view, 1f)
        val dot = Anim(view, 0f)
        val layer = Anim(view, 0f)
        val scale = Anim(view, 1f)
        val open = Anim(view, 0f)

        fun set(running: RunningAppState, front: Boolean, multi: Boolean, animate: Boolean) {
            this.running = running
            this.front = front
            val w = when {
                running == RunningAppState.NOT_RUNNING -> 0f
                running == RunningAppState.MINIMIZED -> t.indMin
                front -> t.indFront
                else -> t.indRun
            }
            width.spring(w, t.settleStiffness, t.settleDamping, DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS, animate)
            color.ease(if (front) t.primary else t.onSurfaceVariant, t.fadeMs, animate)
            fill.ease(if (running == RunningAppState.MINIMIZED) 0f else 1f, t.fadeMs, animate)
            dot.ease(if (multi && running != RunningAppState.NOT_RUNNING) 1f else 0f, t.fadeMs, animate)
            updateLayer(animate)
        }

        /** The state layer and press scale from the view's pressed and hovered state. */
        fun updateLayer(animate: Boolean) {
            val isPressed = pressed || view.isPressed
            val target = when {
                isPressed -> t.statePressed
                view.isHovered -> if (front) t.stateFrontHover else t.stateHover
                front -> t.stateFront
                else -> 0f
            }
            layer.ease(target, if (target > layer.value) t.hoverInMs else t.hoverOutMs, animate)
            scale.spring(if (isPressed) t.pressScale else 1f, t.tickStiffness, t.tickDamping,
                DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE, animate)
        }

        /** Tracks the press from the view's touch events. */
        fun onTouchEvent(event: MotionEvent) {
            val down = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    !event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) ||
                        event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
                else -> return
            }
            if (down == pressed) return
            pressed = down
            updateLayer(view.isAttachedToWindow)
            view.invalidate()
        }
    }

    /** App targets: the state layer, press scale, line of state and focus ring. */
    private inner class AppDecor : TaskbarIconDecor {
        private val rect = RectF()

        override fun drawBehind(view: BubbleTextView, canvas: Canvas) {
            val s = state(view)
            if (s.layer.value <= 0f) return
            rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
            t.paint.style = Paint.Style.FILL
            t.paint.color = withAlpha(t.onSurface, s.layer.value)
            canvas.drawRoundRect(rect, t.radius, t.radius, t.paint)
        }

        override fun iconScale(view: BubbleTextView) = state(view).scale.value

        override fun drawOver(view: BubbleTextView, canvas: Canvas) {
            val s = state(view)
            val w = s.width.value
            if (w >= 0.5f) {
                val dot = s.dot.value
                val color = s.color.value
                val group = w + dot * (t.dotGap + t.dot)
                val left = view.width / 2f - group / 2f
                val top = view.height + t.indOffset
                val r = t.indHeight / 2f
                val fill = s.fill.value
                t.paint.style = Paint.Style.FILL
                if (fill > 0f) {
                    t.paint.color = withAlpha(color, fill)
                    canvas.drawRoundRect(left, top, left + w, top + t.indHeight, r, r, t.paint)
                }
                if (fill < 1f) {
                    val h = t.indStroke / 2f
                    t.paint.style = Paint.Style.STROKE
                    t.paint.strokeWidth = t.indStroke
                    t.paint.color = withAlpha(color, 1f - fill)
                    canvas.drawRoundRect(left + h, top + h, left + w - h, top + t.indHeight - h, r - h, r - h, t.paint)
                    t.paint.style = Paint.Style.FILL
                }
                if (dot > 0f) {
                    t.paint.color = withAlpha(color, dot)
                    canvas.drawCircle(left + w + t.dotGap + t.dot / 2f, top + t.indHeight / 2f, t.dot / 2f, t.paint)
                }
            }
            rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
            drawFocusRing(view, canvas, rect, t.radius)
        }

        override fun onStateChanged(view: BubbleTextView) {
            state(view).updateLayer(view.isAttachedToWindow)
            view.invalidate()
        }

        override fun onTouchEvent(view: BubbleTextView, event: MotionEvent) = state(view).onTouchEvent(event)
    }

    /** The apps pill: 56 × 40 dp, `secondaryContainer`, `primary` while All Apps is open; our own glyph. */
    private inner class PillDecor : TaskbarIconDecor {
        private val rect = RectF()

        override fun drawBehind(view: BubbleTextView, canvas: Canvas) {}

        override fun iconScale(view: BubbleTextView) = 1f

        override fun drawOver(view: BubbleTextView, canvas: Canvas) {
            val s = state(view)
            val open = s.open.value
            val cx = view.width / 2f
            val cy = view.height / 2f
            rect.set(0f, cy - t.pillHeight / 2f, view.width.toFloat(), cy + t.pillHeight / 2f)
            val r = t.pillHeight / 2f
            val count = canvas.save()
            val scale = s.scale.value
            canvas.scale(scale, scale, cx, cy)
            t.paint.style = Paint.Style.FILL
            t.paint.color = ARGB.evaluate(open, t.secondaryContainer, t.primary) as Int
            canvas.drawRoundRect(rect, r, r, t.paint)
            if (s.layer.value > 0f) {
                t.paint.color = withAlpha(t.onSurface, s.layer.value)
                canvas.drawRoundRect(rect, r, r, t.paint)
            }
            t.paint.color = ARGB.evaluate(open, t.onSecondaryContainer, t.onPrimary) as Int
            drawGlyph(canvas, cx, cy)
            canvas.restoreToCount(count)
            drawFocusRing(view, canvas, rect, r)
        }

        /** Four 6.5 dp rounded squares on a 20 dp grid, the lower right one turned 45° (design §2.2). */
        private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float) {
            val count = canvas.save()
            canvas.translate(cx - t.glyph / 2f, cy - t.glyph / 2f)
            canvas.scale(t.glyph / 20f, t.glyph / 20f)
            canvas.drawRoundRect(2f, 2f, 8.5f, 8.5f, 2f, 2f, t.paint)
            canvas.drawRoundRect(11.5f, 2f, 18f, 8.5f, 2f, 2f, t.paint)
            canvas.drawRoundRect(2f, 11.5f, 8.5f, 18f, 2f, 2f, t.paint)
            canvas.rotate(45f, 14.5f, 14.5f)
            canvas.drawRoundRect(11.4f, 11.4f, 17.6f, 17.6f, 2f, 2f, t.paint)
            canvas.restoreToCount(count)
        }

        override fun onStateChanged(view: BubbleTextView) {
            state(view).updateLayer(view.isAttachedToWindow)
            view.invalidate() // focus and window focus change the ring without changing a layer
        }

        override fun onTouchEvent(view: BubbleTextView, event: MotionEvent) = state(view).onTouchEvent(event)
    }

    /** The divider: 1 × 24 dp of `outlineVariant` at 70 %, centred in its 17 dp slot. Decorative. */
    private inner class DividerDecor : TaskbarIconDecor {
        override fun drawBehind(view: BubbleTextView, canvas: Canvas) {}

        override fun iconScale(view: BubbleTextView) = 1f

        override fun drawOver(view: BubbleTextView, canvas: Canvas) {
            val cx = view.width / 2f
            val cy = view.height / 2f
            t.paint.style = Paint.Style.FILL
            t.paint.color = withAlpha(t.outlineVariant, t.dividerAlpha)
            canvas.drawRect(cx - t.dividerWidth / 2f, cy - t.dividerHeight / 2f, cx + t.dividerWidth / 2f,
                cy + t.dividerHeight / 2f, t.paint)
        }

        override fun onStateChanged(view: BubbleTextView) {}

        override fun onTouchEvent(view: BubbleTextView, event: MotionEvent) {}
    }

    /** Quire's ring: 2 dp of `primary`, 2 dp outside [target], while the view has keyboard focus. */
    private fun drawFocusRing(view: View, canvas: Canvas, target: RectF, radius: Float) {
        if (!view.isFocused || !view.hasWindowFocus() || view.isInTouchMode) return
        val inset = t.ringOffset + t.ring / 2f
        t.ringRect.set(target)
        t.ringRect.inset(-inset, -inset)
        t.paint.style = Paint.Style.STROKE
        t.paint.strokeWidth = t.ring
        t.paint.color = t.primary
        canvas.drawRoundRect(t.ringRect, radius + inset, radius + inset, t.paint)
        t.paint.style = Paint.Style.FILL
    }

    /** The design's tokens (res/values/openbook_tokens.xml) and Material roles from the taskbar's theme. */
    private class Tokens(c: Context) {
        private val res = c.resources
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ringRect = RectF()
        val radius = res.getDimension(R.dimen.ob_tb_radius)
        val pillHeight = res.getDimension(R.dimen.ob_tb_pill_height)
        val glyph = res.getDimension(R.dimen.ob_tb_glyph)
        val dividerWidth = res.getDimension(R.dimen.ob_tb_divider_width)
        val dividerHeight = res.getDimension(R.dimen.ob_tb_divider_height)
        val dividerAlpha = res.getFloat(R.dimen.ob_tb_divider_alpha)
        val indOffset = res.getDimension(R.dimen.ob_ind_offset)
        val indHeight = res.getDimension(R.dimen.ob_ind_height)
        val indRun = res.getDimension(R.dimen.ob_ind_run)
        val indFront = res.getDimension(R.dimen.ob_ind_front)
        val indMin = res.getDimension(R.dimen.ob_ind_min)
        val indStroke = res.getDimension(R.dimen.ob_ind_stroke)
        val dot = res.getDimension(R.dimen.ob_ind_dot)
        val dotGap = res.getDimension(R.dimen.ob_ind_dot_gap)
        val stateHover = res.getFloat(R.dimen.ob_state_hover)
        val stateFront = res.getFloat(R.dimen.ob_state_front)
        val stateFrontHover = res.getFloat(R.dimen.ob_state_front_hover)
        val statePressed = res.getFloat(R.dimen.ob_state_pressed)
        val pressScale = res.getFloat(R.dimen.ob_press_scale)
        val ring = res.getDimension(R.dimen.ob_focus_ring)
        val ringOffset = res.getDimension(R.dimen.ob_focus_ring_offset)
        val settleStiffness = res.getFloat(R.dimen.ob_spring_settle_stiffness)
        val settleDamping = res.getFloat(R.dimen.ob_spring_settle_damping)
        val tickStiffness = res.getFloat(R.dimen.ob_spring_tick_stiffness)
        val tickDamping = res.getFloat(R.dimen.ob_spring_tick_damping)
        val fadeMs = res.getInteger(R.integer.ob_duration_fade_ms).toLong()
        val hoverInMs = res.getInteger(R.integer.ob_duration_hover_in_ms).toLong()
        val hoverOutMs = res.getInteger(R.integer.ob_duration_hover_out_ms).toLong()
        val primary = c.getColor(R.color.materialColorPrimary)
        val onPrimary = c.getColor(R.color.materialColorOnPrimary)
        val secondaryContainer = c.getColor(R.color.materialColorSecondaryContainer)
        val onSecondaryContainer = c.getColor(R.color.materialColorOnSecondaryContainer)
        val onSurface = c.getColor(R.color.materialColorOnSurface)
        val onSurfaceVariant = c.getColor(R.color.materialColorOnSurfaceVariant)
        val outlineVariant = c.getColor(R.color.materialColorOutlineVariant)
    }

    /** A value that eases to its target in a set time or springs to it, redrawing [view]; a cut without animations. */
    private class Anim(private val view: View, initial: Float) {
        var value = initial
            private set
        private var target = initial
        private var animator: ValueAnimator? = null
        private var spring: SpringAnimation? = null

        fun snap(v: Float) {
            animator?.cancel()
            spring?.cancel()
            value = v
            target = v
            view.invalidate()
        }

        fun ease(to: Float, durationMs: Long, animate: Boolean) {
            if (to == target) return
            target = to
            if (!animate || !ValueAnimator.areAnimatorsEnabled()) return snap(to)
            spring?.cancel()
            animator?.cancel()
            animator = ValueAnimator.ofFloat(value, to).apply {
                duration = durationMs
                interpolator = STANDARD
                addUpdateListener {
                    value = it.animatedValue as Float
                    view.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) animator = null
                    }
                })
                start()
            }
        }

        /** Springs to [to]; a spring in flight is retargeted and keeps its velocity. */
        fun spring(to: Float, stiffness: Float, damping: Float, minVisibleChange: Float, animate: Boolean) {
            if (to == target) return
            target = to
            if (!animate || !ValueAnimator.areAnimatorsEnabled()) return snap(to)
            animator?.cancel()
            animator = null
            val s = spring ?: SpringAnimation(FloatValueHolder()).also { s ->
                s.spring = SpringForce(to).setStiffness(stiffness).setDampingRatio(damping)
                s.setMinimumVisibleChange(minVisibleChange)
                s.addUpdateListener { _, v, _ ->
                    value = v
                    view.invalidate()
                }
                spring = s
            }
            if (!s.isRunning) s.setStartValue(value)
            s.animateToFinalPosition(to)
        }
    }

    /** A colour that fades to its target. */
    private class ColorAnim(view: View, initial: Int) {
        private var from = initial
        private var to = initial
        private val fraction = Anim(view, 1f)

        val value: Int
            get() = if (fraction.value >= 1f) to else ARGB.evaluate(fraction.value, from, to) as Int

        fun ease(color: Int, durationMs: Long, animate: Boolean) {
            if (color == to) return
            from = value
            to = color
            fraction.snap(0f)
            fraction.ease(1f, durationMs, animate)
        }
    }

    companion object {
        private const val TAG = "OpenBookTaskbar"
        private const val INVALID_TASK = -1
        private val ARGB = ArgbEvaluator()

        /** Floating views a person is working in (as type to search): Tab leaves the desktop alone with one open. */
        private val BLOCKING = AbstractFloatingView.TYPE_ACCESSIBLE and AbstractFloatingView.TYPE_SNACKBAR.inv()

        /** Whether [context]'s taskbar is OpenBook's: the persistent taskbar of a desktop form factor launcher. */
        @JvmStatic
        fun isEnabled(context: TaskbarActivityContext): Boolean =
            context.resources.getBoolean(R.bool.desktop_form_factor) && !context.isTransientTaskbar

        private fun of(context: TaskbarActivityContext): OpenBookTaskbar? =
            context.controllers?.taskbarViewController?.openBookTaskbar

        /** Taskbar UI thread: the taskbar's All Apps starts opening ([open]) or closing. */
        @JvmStatic
        fun onAllAppsTransition(controllers: TaskbarControllers, open: Boolean) {
            controllers.taskbarViewController.openBookTaskbar?.setAllAppsOpen(open)
        }

        /** The taskbar window's keys while it is focusable; true if taken (Esc gives keyboard focus back). */
        @JvmStatic
        fun onTaskbarKeyEvent(context: TaskbarActivityContext, event: KeyEvent): Boolean =
            of(context)?.onKeyEvent(event) ?: false

        @JvmStatic
        fun onTaskbarWindowFocusChanged(context: TaskbarActivityContext, hasFocus: Boolean) {
            of(context)?.onWindowFocusChanged(hasFocus)
        }

        /** Taskbar UI thread, from the launcher's Tab ([onLauncherKeyDown]). */
        @JvmStatic
        fun focusFromKeyboard(taskbarRoot: View, forward: Boolean) {
            val c = ActivityContext.lookupContextNoThrow<TaskbarActivityContext>(taskbarRoot.context) ?: return
            of(c)?.focusFromKeyboard(forward)
        }

        /**
         * The launcher's keys no view took (`QuickstepLauncher.onKeyDown`): Tab, or Shift+Tab, on the desktop with
         * nothing open and nothing in the launcher's own focus order (the desktop's icons come first) moves keyboard
         * focus into the taskbar.
         */
        @JvmStatic
        fun onLauncherKeyDown(launcher: QuickstepLauncher, event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_TAB ||
                event.repeatCount != 0
            ) return false
            val forward = event.hasNoModifiers()
            if (!forward && !event.hasModifiers(KeyEvent.META_SHIFT_ON)) return false
            if (!launcher.resources.getBoolean(R.bool.desktop_form_factor) ||
                !launcher.isInState(LauncherState.NORMAL) || launcher.dragController.isDragging ||
                AbstractFloatingView.getTopOpenViewWithType(launcher, BLOCKING) != null
            ) return false
            val root = launcher.window?.decorView as? ViewGroup ?: return false
            val next = FocusFinder.getInstance()
                .findNextFocus(root, root.findFocus(), if (forward) View.FOCUS_FORWARD else View.FOCUS_BACKWARD)
            if (next != null) {
                Log.d(TAG, "Tab stays on the desktop: ${next.javaClass.simpleName}")
                return false
            }
            val taskbar = launcher.taskbarInteractor ?: return false
            taskbar.focusTaskbar(forward)
            return true
        }

        /**
         * The taskbar's tooltip, restyled (design §2.4): a 28 dp pill of `inverseSurface` with the name in
         * `inverseOnSurface`, 12/16 at weight 500, no arrow. AOSP's controller still decides when it shows.
         */
        @JvmStatic
        fun styleTooltip(tip: ArrowTipView) {
            val c = tip.context
            val res = c.resources
            tip.findViewById<View>(R.id.arrow)?.visibility = View.GONE
            val text = tip.findViewById<TextView>(R.id.text) ?: return
            val height = res.getDimensionPixelSize(R.dimen.ob_tip_height)
            text.background = GradientDrawable().apply {
                cornerRadius = height / 2f
                setColor(c.getColor(R.color.materialColorInverseSurface))
            }
            text.setTextColor(c.getColor(R.color.materialColorInverseOnSurface))
            text.typeface = Typeface.create("variable-label-medium", Typeface.NORMAL)
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text.setLineHeight(TypedValue.COMPLEX_UNIT_SP, 16f)
            text.includeFontPadding = false
            text.gravity = Gravity.CENTER
            text.minHeight = height
            val h = res.getDimensionPixelSize(R.dimen.ob_tip_padding_h)
            text.setPadding(h, 0, h, 0)
        }

        @JvmStatic
        fun tooltipGap(context: Context): Int = context.resources.getDimensionPixelSize(R.dimen.ob_tip_gap)

        @JvmStatic
        fun tooltipDelayMs(context: Context): Long = context.resources.getInteger(R.integer.ob_tip_delay_ms).toLong()

        @JvmStatic
        fun tooltipFadeMs(context: Context): Long =
            context.resources.getInteger(R.integer.ob_duration_fade_ms).toLong()

        private fun withAlpha(color: Int, fraction: Float): Int {
            val a = (android.graphics.Color.alpha(color) * fraction.coerceIn(0f, 1f)).toInt()
            return (color and 0x00FFFFFF) or (a shl 24)
        }
    }
}
