/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.graphics.Rect
import android.util.Log
import android.view.View
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.popup.ui.PopupItem
import com.android.launcher3.taskbar.BaseTaskbarContext
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.launcher3.taskbar.TaskbarControllers
import com.android.launcher3.taskbar.TaskbarView
import com.android.launcher3.taskbar.overlay.TaskbarOverlayContext
import com.android.quickstep.SystemUiProxy
import com.android.wm.shell.shared.desktopmode.DesktopTaskToFrontReason

/**
 * The menus of the taskbar's icons and of All Apps' icons (openbook/ROADMAP.md M2 #11; OpenBook's
 * docs/launcher-design.md §4), as [OpenBookMenu.Spec]s for AOSP's `TaskbarPopupController`.
 *
 * **A taskbar icon's menu** is centred over the icon, 8 dp above the bar:
 * - a header: the app's icon and name;
 * - the app's windows (its running desktop tasks, minimized ones included), the one in front marked; a click brings
 *   that window to the front (WM Shell's `showDesktopApp`, as AOSP's Manage Windows does). They replace Manage Windows;
 * - a divider, then New window (with its "Middle-click" hint, M1 #4), Pin or Unpin, App info and anything else AOSP
 *   offers;
 * - a divider, then Close, which reads "Close n windows" with more than one.
 * Its items are AOSP 17's own; only their order, the header and the windows are ours.
 *
 * **An All Apps icon's menu** (the panel, in the taskbar's overlay window) opens at the pointer, or under the icon
 * from the keyboard, with AOSP's items: Close after a divider.
 */
object TaskbarMenu {
    private const val TAG = OpenBookMenu.TAG

    @JvmStatic
    fun spec(
        context: BaseTaskbarContext,
        controllers: TaskbarControllers,
        icon: BubbleTextView,
        item: ItemInfo,
    ): OpenBookMenu.Spec? {
        if (!OpenBookMenu.isEnabled(context)) return null
        val byKeyboard = OpenBookMenu.openingFromKeyboard
        if (context is TaskbarOverlayContext) {
            return OpenBookMenu.Spec(
                kind = OpenBookMenu.Kind.APP,
                anchor = OpenBookMenu.anchorAtIcon(context, icon, byKeyboard),
                byKeyboard = byKeyboard,
                byTouch = false,
                arrange = { items ->
                    val (close, rest) = items.partition { it.labelResId == R.string.close_app_taskbar }
                    listOf(rest, close)
                },
            )
        }
        if (context !is TaskbarActivityContext) return null
        if (!OpenBookTaskbar.isEnabled(context)) return null

        val dragLayer = context.dragLayer
        val r = Rect()
        dragLayer.getDescendantRectRelativeToSelf(icon, r)
        val bar = taskbarViewOf(icon)
        val barTop = if (bar != null) {
            val b = Rect()
            dragLayer.getDescendantRectRelativeToSelf(bar, b)
            b.top
        } else {
            r.top
        }

        val pkg = item.targetPackage
        val tasks = if (pkg != null) {
            controllers.taskbarRecentAppsController.openBookTasks(pkg, item.user.identifier)
        } else {
            emptyList()
        }
        val front = controllers.taskbarViewController.openBookTaskbar?.frontTask ?: -1
        val appName = (item.title ?: icon.text)?.toString() ?: ""
        val windows = tasks.map { task ->
            val title = task.taskDescription?.label?.takeIf { it.isNotBlank() }
                ?: task.title?.takeIf { it.isNotBlank() }
                ?: appName
            val id = task.key.id
            OpenBookMenu.Window(title, front == id) {
                Log.d(TAG, "window $id to the front")
                SystemUiProxy.INSTANCE[context].showDesktopApp(
                    id,
                    /* transition= */ null,
                    DesktopTaskToFrontReason.TASKBAR_MANAGE_WINDOW,
                )
            }
        }
        val header = OpenBookMenu.Header((item as? ItemInfoWithIcon)?.bitmap?.icon, appName)
        val windowCount = tasks.size
        return OpenBookMenu.Spec(
            kind = OpenBookMenu.Kind.TASKBAR,
            anchor = OpenBookMenu.AboveBar(r.centerX(), barTop),
            byKeyboard = byKeyboard,
            byTouch = false,
            header = header,
            windows = windows,
            arrange = { items -> arrange(items, windows.isNotEmpty()) },
            label = { p ->
                if (p.labelResId == R.string.close_app_taskbar && windowCount > 1) {
                    context.getString(R.string.ob_mn_close_windows, windowCount)
                } else {
                    null
                }
            },
            hint = { p ->
                if (p.labelResId == R.string.new_window_option_taskbar) {
                    context.getString(R.string.ob_mn_hint_middle_click)
                } else {
                    null
                }
            },
        )
    }

    /** New window, Pin or Unpin, App info, then the rest in AOSP's order; Close on its own. */
    private fun arrange(items: List<PopupItem>, windowsShown: Boolean): List<List<PopupItem>> {
        val order = listOf(
            R.string.new_window_option_taskbar,
            R.string.add_to_taskbar,
            R.string.remove_from_taskbar,
            R.string.app_info_drop_target_label,
        )
        val shown = items.filter { !(windowsShown && it.labelResId == R.string.manage_windows_option_taskbar) }
        val (close, rest) = shown.partition { it.labelResId == R.string.close_app_taskbar }
        val first = order.mapNotNull { id -> rest.firstOrNull { it.labelResId == id } }
        return listOf(first + rest.filter { it !in first }, close)
    }

    private fun taskbarViewOf(view: View): View? {
        var v = view.parent
        while (v is View) {
            if (v is TaskbarView) return v
            v = v.parent
        }
        return null
    }
}
