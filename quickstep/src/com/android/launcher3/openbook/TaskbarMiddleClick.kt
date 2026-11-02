/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.annotation.SuppressLint
import android.os.UserHandle
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.android.launcher3.Flags
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.launcher3.taskbar.TaskbarControllers
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.ShortcutUtil
import com.android.quickstep.util.SingleTask
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/**
 * Middle-click a taskbar icon (openbook/ROADMAP.md, M1 #4). On the desktop, a middle click opens a new window of the
 * app if it supports multiple instances, through the taskbar menu's own "New window" item: Launcher3's
 * `supportsMultiInstance()` decides, and `NewWindowTaskbarShortcut` launches. For any other app it does what a click
 * does, except that it never minimizes (click to minimize, M1 #1): it opens the app, or brings its window to front.
 *
 * Outside the desktop, over Overview and during split selection, a middle click is an ordinary click, as in AOSP.
 *
 * Installed as the touch listener of every taskbar icon (running apps in TaskbarView, pinned apps in
 * TaskbarPinnedAppIconContainer, and the pinned-apps overflow), so it sees a mouse's ACTION_DOWN before the icon's own
 * touch handling. It takes a press of the middle button alone and every event after it up to ACTION_UP or
 * ACTION_CANCEL, so no click, long press or drag starts underneath. It returns false for everything else.
 */
class TaskbarMiddleClick(
    private val context: TaskbarActivityContext,
    private val inOverview: BooleanSupplier,
    private val aospClick: Consumer<View>,
) : View.OnTouchListener {

    private var pressed: View? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!event.isFromSource(InputDevice.SOURCE_MOUSE) || event.buttonState != MotionEvent.BUTTON_TERTIARY) {
                return false
            }
            pressed = v
            v.isPressed = true
            return true
        }
        if (pressed !== v) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                release(v)
                if (event.x >= 0 && event.y >= 0 && event.x < v.width && event.y < v.height) onMiddleClick(v)
            }
            MotionEvent.ACTION_CANCEL -> release(v)
        }
        return true
    }

    private fun release(v: View) {
        pressed = null
        v.isPressed = false
    }

    private fun onMiddleClick(v: View) {
        val controllers = context.controllers
        val onDesktop = context.isTaskbarShowingDesktopTasks && !inOverview.asBoolean &&
            controllers.uiController.recentsViewInteractor?.isSplitSelectionActive() != true
        if (!onDesktop) {
            v.performClick()
            return
        }
        val info = itemInfo(v, controllers)
        val newWindow = if (info != null && Flags.enableMultiInstanceMenuTaskbar()) {
            controllers.taskbarPopupController.createNewWindowShortcutFactory().getShortcut(context, info, v)
        } else {
            null
        }
        if (newWindow != null) {
            Log.d(TAG, "new window: ${info?.targetPackage}")
            newWindow.onClick(v)
        } else {
            Log.d(TAG, "no new window for ${info?.targetPackage ?: v.tag}: opening or focusing it")
            aospClick.accept(v)
        }
    }

    /** The item the taskbar's menu would show for [v], resolved as in `TaskbarPopupController.show`; or null. */
    private fun itemInfo(v: View, controllers: TaskbarControllers): ItemInfo? =
        when (val tag = v.tag) {
            is ItemInfo -> tag.takeIf { ShortcutUtil.supportsShortcuts(it) }
            is SingleTask -> {
                val key = tag.task.key
                controllers.taskbarPopupController
                    .getApp(ComponentKey(key.component, UserHandle.of(key.userId)))
                    ?.let { SingleTask.createTaskItemInfo(tag, it.makeWorkspaceItem(v.context)) }
            }
            else -> null
        }

    private companion object {
        const val TAG = "OpenBookMiddleClick"
    }
}
