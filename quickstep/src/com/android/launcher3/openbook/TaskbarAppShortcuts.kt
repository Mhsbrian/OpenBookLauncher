/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.Manifest.permission.MANAGE_KEY_GESTURES
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.hardware.input.InputManager
import android.hardware.input.KeyGestureEvent
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.launcher3.Utilities
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.launcher3.util.SafeCloseable
import com.android.quickstep.util.GroupTask
import java.util.concurrent.Executor

/**
 * Meta+1…9 (openbook/ROADMAP.md, M1 #2). Meta+N does what a click on the Nth app on the taskbar does, counted in
 * reading order: an app that isn't running opens, a running app's front window comes forward, and the window that
 * is already in front minimizes (click to minimize, M1 #1). It works while an app has keyboard focus.
 *
 * The keys are system shortcuts in InputManagerService, added by OpenBook's frameworks/base patch "Meta+1…9 are
 * taskbar shortcuts". The system takes them before the focused app sees them and reports each press once, as
 * [KEY_GESTURE_TYPE_TASKBAR_APP] with the digit's key code. A held key's repeats never reach this class, so holding
 * Meta+N doesn't toggle a window again and again. Handling a key gesture needs MANAGE_KEY_GESTURES, which the
 * launcher holds through the recents role. Without it, or on a framework without the patch, nothing happens.
 *
 * [focusedTaskbar] runs on [executor], the taskbar's UI thread, and gives the taskbar of the focused display, or
 * null while the device is locked.
 */
class TaskbarAppShortcuts(
    context: Context,
    private val executor: Executor,
    private val focusedTaskbar: () -> TaskbarActivityContext?,
) : InputManager.KeyGestureEventHandler, SafeCloseable {

    private val inputManager: InputManager? =
        if (context.checkSelfPermission(MANAGE_KEY_GESTURES) == PERMISSION_GRANTED) {
            context.getSystemService(InputManager::class.java)
        } else {
            Log.w(TAG, "no MANAGE_KEY_GESTURES: Meta+1…9 stay off")
            null
        }
    private var registered = false

    init {
        try {
            inputManager?.registerKeyGestureEventHandler(listOf(KEY_GESTURE_TYPE_TASKBAR_APP), this)
            registered = inputManager != null
        } catch (e: Exception) {
            Log.e(TAG, "can't register Meta+1…9", e)
        }
    }

    override fun handleKeyGestureEvent(event: KeyGestureEvent, focusedToken: IBinder?) {
        if (event.keyGestureType != KEY_GESTURE_TYPE_TASKBAR_APP) return
        if (event.action != KeyGestureEvent.ACTION_GESTURE_COMPLETE || event.isCancelled) return
        val n = slotOf(event.keycodes) ?: return
        executor.execute { activate(n) }
    }

    private fun activate(n: Int) {
        val taskbar = focusedTaskbar()
        if (taskbar == null) {
            Log.d(TAG, "Meta+$n: no taskbar")
            return
        }
        val views = taskbar.controllers.taskbarViewController.iconViews
        val icons = appIcons(views, Utilities.isRtl(taskbar.resources))
        val icon = icons.getOrNull(n - 1)
        if (icon == null) {
            Log.d(TAG, "Meta+$n: the taskbar has ${icons.size} apps")
            return
        }
        Log.d(TAG, "Meta+$n: ${describe(icon.tag)}")
        icon.callOnClick()
    }

    override fun close() {
        if (!registered) return
        registered = false
        try {
            inputManager?.unregisterKeyGestureEventHandler(this)
        } catch (e: Exception) {
            Log.e(TAG, "can't unregister Meta+1…9", e)
        }
    }

    companion object {
        private const val TAG = "OpenBookMetaNumber"

        /**
         * OpenBook's key gesture type for Meta+1…9. It has to match `KEY_GESTURE_TYPE_OPENBOOK_TASKBAR_APP` in
         * OpenBook's InputGestureManager patch. AOSP's own types end at 90 in android17, so OpenBook's start at 10001.
         */
        const val KEY_GESTURE_TYPE_TASKBAR_APP = 10001

        /** N for Meta+N: 1…9 from the event's one key code, or null. */
        @VisibleForTesting
        fun slotOf(keycodes: IntArray): Int? =
            keycodes.singleOrNull()?.minus(KeyEvent.KEYCODE_0)?.takeIf { it in 1..9 }

        /**
         * The taskbar's apps in reading order: pinned apps, folders, app pairs and running apps. Not the All Apps
         * button, the divider, the overflow button, handoff suggestions or hidden icons.
         */
        @VisibleForTesting
        fun appIcons(views: Array<View>, rtl: Boolean): List<View> {
            val apps = views.filter { it.visibility == View.VISIBLE && (it.tag is ItemInfo || it.tag is GroupTask) }
            val x = IntArray(2)
            val left = apps.associateWith { it.getLocationOnScreen(x); x[0] }
            return if (rtl) apps.sortedByDescending { left[it] } else apps.sortedBy { left[it] }
        }

        private fun describe(tag: Any?): String =
            when (tag) {
                is ItemInfo -> "${tag.javaClass.simpleName} ${tag.targetPackage}"
                is GroupTask -> "${tag.javaClass.simpleName} ${tag.tasks.map { it.key.id }}"
                else -> tag.toString()
            }
    }
}
