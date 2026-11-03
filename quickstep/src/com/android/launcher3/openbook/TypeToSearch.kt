/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.ExtendedEditText
import com.android.launcher3.Flags
import com.android.launcher3.LauncherState.ALL_APPS
import com.android.launcher3.LauncherState.NORMAL
import com.android.launcher3.R
import com.android.launcher3.taskbar.TaskbarActivityContext
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.views.ActivityContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Type to search (openbook/ROADMAP.md, M1 #7). On the desktop, with the home screen focused and nothing open over it,
 * typing a character opens All Apps with that character in its search field, and the keys after it go on into the
 * field. It opens the same All Apps as the taskbar's button (`Launcher.toggleAllApps`).
 *
 * AOSP already does this once All Apps is open (`AppsSearchContainerLayout.preDispatchKeyEvent`); on the home screen
 * the key fell through to `Activity`'s default key handling, a local search the launcher has no searchable for, so
 * nothing happened.
 *
 * Called from `QuickstepLauncher.onKeyDown`, which only sees keys no view took, so a focused widget or text field keeps
 * its keys. It takes a key that types a visible character, alone or with Shift, from a key press that isn't a repeat.
 * Other keys and Ctrl, Alt or Meta shortcuts are left to AOSP, as are keys in other states (All Apps, Overview, edit
 * mode, a drag), with a folder, menu, sheet or dialog open ([BLOCKING]), and every key on phones and tablets: only a
 * launcher whose resources say `bool/desktop_form_factor` (OpenBook's desktop overlay) does this. Hints don't block it:
 * the home screen's bounce hint (`DiscoveryBounce`) is an open floating view for 13 s after every return to the home
 * screen until All Apps has been opened once, so on a new install it would otherwise swallow the first keys. A dead key
 * (an accent waiting for its letter) isn't a character yet, so it doesn't open search either, as in AOSP's All Apps.
 *
 * Where All Apps is drawn:
 * - **The taskbar's overlay window** when the flag `all_apps_surface` is on (AOSP 17 on the desktop): the launcher's
 *   ALL_APPS state makes the taskbar show its All Apps, in its own window and on the taskbar's UI thread. Type to
 *   search queues the key and asks for that All Apps ready for typing ([takeOpenRequest]): the taskbar then focuses
 *   its search field as for its own search shortcut, and the queued keys go in ([typeInto]). Keys that edit text and
 *   still reach the launcher before the overlay window has keyboard focus join the queue, so fast typing loses
 *   nothing.
 * - **The launcher's own All Apps** otherwise: the key goes to its search field, cleared first if it still holds an old
 *   query.
 *
 * Either way a key is handed to the field as if the field had focus, so it types exactly what it would have typed
 * there. Nothing typed is logged.
 */
object TypeToSearch {

    private const val TAG = "OpenBookTypeToSearch"

    /** How long a request for All Apps ready for typing waits for the taskbar to open it. */
    private const val OPEN_REQUEST_MS = 2000L

    /**
     * Floating views a person is working in: AOSP's `TYPE_ACCESSIBLE` (folders, menus, sheets, dialogs; not hints,
     * onboarding animations or listeners), less snackbars, which a touch elsewhere closes too. Opening All Apps closes
     * whatever is left open.
     */
    private val BLOCKING = AbstractFloatingView.TYPE_ACCESSIBLE and AbstractFloatingView.TYPE_SNACKBAR.inv()

    /** Keys besides characters that edit the search field while it waits for the overlay window's focus. */
    private val EDIT_KEYS = setOf(
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
    )

    /** Keys for the taskbar's All Apps search, in the order typed, until its field takes them. */
    private val queued = ConcurrentLinkedQueue<KeyEvent>()

    /** When type to search last asked for the taskbar's All Apps ready for typing (uptime), or 0. */
    private val openRequestedAt = AtomicLong()

    /** Whether [event] was typed into All Apps search: the key that opened it, or one on its way to the overlay. */
    @JvmStatic
    fun onKeyDown(launcher: QuickstepLauncher, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !launcher.resources.getBoolean(R.bool.desktop_form_factor)) {
            return false
        }
        val overlay = Flags.allAppsSurface()
        if (overlay && launcher.isInState(ALL_APPS)) {
            // All Apps is open, but its window doesn't have keyboard focus yet: without this the key would be lost.
            if (!editsText(event)) return false
            val taskbar = launcher.taskbarInteractor ?: return false
            queued.add(KeyEvent(event))
            taskbar.typeIntoAllAppsSearch()
            return true
        }
        if (!opensSearch(event) || !launcher.isInState(NORMAL) || launcher.dragController.isDragging ||
            AbstractFloatingView.getTopOpenViewWithType(launcher, BLOCKING) != null
        ) {
            return false
        }
        if (overlay) {
            if (launcher.taskbarInteractor == null) return false
            queued.clear()
            queued.add(KeyEvent(event))
            openRequestedAt.set(SystemClock.uptimeMillis())
            launcher.toggleAllApps(/* focusSearch= */ false)
            Log.d(TAG, "a key on the desktop opened All Apps search (the taskbar's)")
        } else {
            val search = launcher.appsView.searchUiManager
            val field = search.editText ?: return false
            if (!search.isSearchQueryEmpty) search.resetSearch()
            launcher.toggleAllApps(/* focusSearch= */ true)
            field.requestFocusExplicitly()
            field.dispatchKeyEvent(event)
            Log.d(TAG, "a key on the desktop opened All Apps search (the launcher's)")
        }
        return true
    }

    /**
     * Taskbar UI thread, as the launcher's ALL_APPS state opens the taskbar's All Apps: whether type to search asked
     * for it ready for typing, with the search field focused. An opening it didn't ask for drops any keys left queued.
     */
    @JvmStatic
    fun takeOpenRequest(): Boolean {
        val at = openRequestedAt.getAndSet(0)
        val requested = at != 0L && SystemClock.uptimeMillis() - at < OPEN_REQUEST_MS
        if (!requested) queued.clear()
        return requested
    }

    /** Taskbar UI thread: types the queued keys into [field], the search field of the taskbar's All Apps, in order. */
    @JvmStatic
    fun typeInto(field: ExtendedEditText) {
        while (true) field.dispatchKeyEvent(queued.poll() ?: return)
    }

    /**
     * Taskbar UI thread: types the queued keys into the taskbar's All Apps search, focusing its field, once the field
     * is attached to its window. Before that, the taskbar's own opening types them ([typeInto]).
     */
    @JvmStatic
    fun typeIntoTaskbarAllApps(taskbarRoot: View) {
        val taskbar = ActivityContext.lookupContextNoThrow<TaskbarActivityContext>(taskbarRoot.context) ?: return
        val field = taskbar.controllers.taskbarAllAppsController.appsView?.searchUiManager?.editText ?: return
        if (!field.isAttachedToWindow) return
        if (!field.isFocused) field.requestFocusExplicitly()
        typeInto(field)
    }

    /** A first press of a key that types a visible character, with no modifier but Shift (or a lock key). */
    private fun opensSearch(event: KeyEvent): Boolean {
        if (event.repeatCount != 0) return false
        val c = typed(event)
        return c > 0 && !Character.isWhitespace(c) && !Character.isSpaceChar(c)
    }

    /** A key that types a character (spaces included, repeats too) or edits the text, with no modifier but Shift. */
    private fun editsText(event: KeyEvent): Boolean =
        event.keyCode in EDIT_KEYS && event.hasNoModifiers() || typed(event) > 0

    /** The character [event] types without Ctrl, Alt or Meta, or 0. A dead key types nothing yet (negative value). */
    private fun typed(event: KeyEvent): Int {
        if (!event.hasNoModifiers() && !event.hasModifiers(KeyEvent.META_SHIFT_ON)) return 0
        val c = event.unicodeChar
        return if (c > 0 && !Character.isISOControl(c)) c else 0
    }
}
