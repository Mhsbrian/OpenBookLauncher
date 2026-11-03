/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.graphics.Canvas
import android.view.MotionEvent
import com.android.launcher3.BubbleTextView

/**
 * Draws a taskbar target's state for OpenBook's taskbar (openbook/ROADMAP.md M2 #8; OpenBook's
 * docs/launcher-design.md §2). The taskbar sets one on its own targets only (`OpenBookTaskbar` in quickstep). A
 * [BubbleTextView] with one calls it from its draw and drawable-state changes and no longer draws AOSP's running-app
 * line.
 */
interface TaskbarIconDecor {

    /** Behind the icon: the target's state layer (hover, front, pressed). */
    fun drawBehind(view: BubbleTextView, canvas: Canvas)

    /** The icon's scale about its centre (pressed: 0.92). */
    fun iconScale(view: BubbleTextView): Float

    /** Over the icon: the state line and the keyboard focus ring. */
    fun drawOver(view: BubbleTextView, canvas: Canvas)

    /** The view's pressed, hovered, focused or window-focus state changed. */
    fun onStateChanged(view: BubbleTextView)

    /**
     * A touch or mouse event on the view, before its own handling. The taskbar's cursor-driven gesture handling
     * (`CustomEventsTouchHandler`) never sets the view's pressed state, so the decor tracks the press itself.
     */
    fun onTouchEvent(view: BubbleTextView, event: MotionEvent)
}
