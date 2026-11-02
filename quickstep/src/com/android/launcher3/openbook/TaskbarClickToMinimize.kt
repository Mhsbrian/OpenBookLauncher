/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.app.ActivityManager.RunningTaskInfo
import android.util.Log
import android.view.View
import androidx.annotation.WorkerThread
import com.android.launcher3.BubbleTextView.RunningAppState
import com.android.launcher3.model.data.TaskItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.launcher3.taskbar.TaskbarControllers
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR
import com.android.quickstep.SystemUiProxy
import com.android.quickstep.util.SingleTask
import com.android.systemui.shared.system.ActivityManagerWrapper

/**
 * Click to minimize (openbook/ROADMAP.md, M1 #1). On the desktop, clicking the taskbar icon of the window in front
 * minimizes that window, as on other desktop systems. Clicking it again restores it through the taskbar's own
 * path: a minimized window is no longer the visible front task, so the second click is not taken here.
 *
 * Applies to running apps on the taskbar (a task, or a pinned app with a running window), on the desktop, outside
 * Overview and split selection. Everything else goes straight to AOSP's click handling.
 */
class TaskbarClickToMinimize(
    private val context: TaskbarActivityContext,
    private val controllers: TaskbarControllers,
    private val activityManager: ActivityManagerWrapper,
) {

    /**
     * Takes the click if [view] stands for a running desktop app: then either minimizes the front window or runs
     * [aospClick] on the main thread, after one binder call off it. Returns false, without doing anything, for
     * every other click, which the caller handles itself. [inOverview]: the taskbar is showing over Overview.
     */
    fun handleClick(view: View, inOverview: Boolean, aospClick: Runnable): Boolean {
        if (!context.isTaskbarShowingDesktopTasks || inOverview) return false
        if (controllers.uiController.recentsViewInteractor?.isSplitSelectionActive() == true) return false
        val matches = frontTaskMatcher(view.tag) ?: return false
        val displayId = context.displayId
        UI_HELPER_EXECUTOR.execute {
            val front = frontTask(displayId)
            if (front != null && matches(front)) {
                Log.d(TAG, "minimizing task ${front.taskId} (${front.baseActivity?.packageName})")
                SystemUiProxy.INSTANCE[context].minimizeDesktopApp(front.taskId)
            } else {
                MAIN_EXECUTOR.execute(aospClick)
            }
        }
        return true
    }

    /** What the front task must be for a click on an icon with [tag] to minimize it; null: not a running app. */
    private fun frontTaskMatcher(tag: Any?): ((RunningTaskInfo) -> Boolean)? {
        val recentApps = controllers.taskbarRecentAppsController
        return when (tag) {
            is SingleTask -> {
                val id = tag.task.key.id
                if (recentApps.getRunningAppState(id) != RunningAppState.RUNNING) null else { t -> t.taskId == id }
            }
            is TaskItemInfo -> {
                val id = tag.taskId
                if (recentApps.getRunningAppState(id) != RunningAppState.RUNNING) null else { t -> t.taskId == id }
            }
            is WorkspaceItemInfo -> {
                val pkg = tag.targetPackage ?: return null
                val user = tag.user.identifier
                val state = recentApps.getTaskbarItemState(tag)
                if (state.runningAppState != RunningAppState.RUNNING) null
                else { t -> t.userId == user && (t.baseActivity?.packageName == pkg || t.topActivity?.packageName == pkg) }
            }
            else -> null
        }
    }

    /** The visible task in front on [displayId], or null. */
    @WorkerThread
    private fun frontTask(displayId: Int): RunningTaskInfo? {
        val top = activityManager.getRunningTask(/* filterOnlyVisibleRecents= */ false) ?: return null
        return top.takeIf { it.displayId == displayId && it.isVisible }
    }

    private companion object {
        const val TAG = "OpenBookClickToMinimize"
    }
}
