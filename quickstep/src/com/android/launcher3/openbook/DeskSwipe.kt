/*
 * Copyright (C) 2026 OpenBook
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.app.WallpaperManager
import android.app.WindowConfiguration
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.MotionEvent.AXIS_GESTURE_X_OFFSET
import android.view.MotionEvent.AXIS_GESTURE_Y_OFFSET
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import android.view.WindowManager
import android.window.IRemoteTransitionFinishedCallback
import android.window.RemoteTransition
import android.window.RemoteTransitionStub
import android.window.TransitionInfo
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import com.android.launcher3.R
import com.android.launcher3.statehandlers.DesktopVisibilityController
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR
import com.android.quickstep.GestureState
import com.android.quickstep.InputConsumer
import com.android.quickstep.OverviewComponentObserver
import com.android.quickstep.RecentsAnimationDeviceState
import com.android.quickstep.LauncherActivityInterface
import com.android.quickstep.SystemUiProxy
import com.android.systemui.shared.system.InputMonitorCompat
import com.android.wm.shell.shared.desktopmode.DesktopModeTransitionSource
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Four-finger desk switching, the way spaces work on a Mac (ANAL-354). With two or more desks, a four-finger swipe on
 * the touchpad moves to the desk beside the current one: fingers to the left go to the desk on the right, fingers to
 * the right to the desk on the left, in the desks' own order (the order WM Shell's Ctrl+Meta+arrow shortcuts use: the
 * order they were made). At either end nothing moves. From Home (no desk active) a swipe either way brings back the
 * last desk used.
 *
 * AOSP 17 treats a four-finger swipe as quick switch: it walks Recents in most-recent order, where desks and apps
 * take turns, and gets there through Home (the desk is deactivated, then the next one activated). So a swipe back
 * rarely returned to the desk you came from, and its windows looked gone. Here the swipe asks WM Shell to activate
 * the neighbouring desk directly, with [DeskSlide] as the animation: the desks slide past as whole spaces, wallpaper
 * and all, following the fingers, and settle on `spring.desk` when they lift. Windows keep their place and state;
 * nothing is minimized (`activateDesk` doesn't apply the task limit).
 *
 * Hook: one line at the end of `InputConsumerUtils.newConsumer`. AOSP chooses first, with all its checks; the desk
 * swipe replaces only an ordinary app or launcher gesture consumer, never the lock screen's, a system dialog's,
 * screen pinning's or a blocked task's ([replaceOrKeep]). It applies to launchers whose resources say
 * `bool/desktop_form_factor` (OpenBook's desktop overlay), in desktop mode outside Overview, with two or more desks
 * on the display. Everything else, three-finger gestures included, stays AOSP's.
 */
object DeskSwipe {
    private const val TAG = "OpenBookDeskSwipe"

    /** Horizontal travel, as a fraction of the touchpad's width, that commits to a switch (≈ 4 mm on the G14). */
    private const val COMMIT = 0.03f

    /** Travel that takes the desks the whole way across while the fingers are down (≈ 57 mm on the G14). */
    private const val FULL = 0.45f

    /** AOSP's consumers that this never replaces, whatever else holds: lock screen, system dialogs, screen pinning. */
    private const val GUARDED =
        InputConsumer.TYPE_DEVICE_LOCKED or InputConsumer.TYPE_SYSUI_OVERLAY or InputConsumer.TYPE_SCREEN_PINNED

    /**
     * Called with the consumer AOSP chose for a gesture, after all its checks and wrappers. Returns the desk swipe
     * instead for an eligible four-finger gesture, unless AOSP guards this moment: the lock screen showing (occluded
     * or not), a system dialog, screen pinning, or a task that blocks gestures. Otherwise AOSP's choice stands. On
     * Home AOSP's choice is a no-op, because the launcher's own window handles the swipe (quick switch): replacing it
     * here is what keeps a swipe from Home a desk switch.
     */
    @JvmStatic
    fun replaceOrKeep(
        context: Context,
        deviceState: RecentsAnimationDeviceState,
        gestureState: GestureState,
        inputMonitor: InputMonitorCompat?,
        chosen: InputConsumer,
        canStartSystemGesture: Boolean,
    ): InputConsumer {
        if (!gestureState.isFourFingerTrackpadGesture) return chosen
        val guard =
            when {
                !canStartSystemGesture -> "no system gesture now"
                chosen.type and GUARDED != 0 -> "AOSP's consumer type 0x${Integer.toHexString(chosen.type)}"
                deviceState.isKeyguardShowingOccluded -> "lock screen (occluded)"
                deviceState.isSystemUiDialogShowing -> "system dialog"
                deviceState.isScreenPinningActive -> "screen pinning"
                deviceState.isGestureBlockedTask(gestureState.runningTask) -> "gesture-blocked task"
                else -> null
            }
        val desk = if (guard == null) consumerOrNull(context, gestureState, inputMonitor) else null
        Log.d(TAG, "four fingers: ${if (desk != null) "desk swipe" else "AOSP (${guard ?: lastSkip})"}")
        return desk ?: chosen
    }

    /** Why [consumerOrNull] last declined, for the log. */
    private var lastSkip = ""

    /** The consumer for this gesture, or null to leave it to AOSP. */
    private fun consumerOrNull(
        context: Context,
        gestureState: GestureState,
        inputMonitor: InputMonitorCompat?,
    ): InputConsumer? {
        if (!gestureState.isFourFingerTrackpadGesture) return null.also { lastSkip = "not four fingers" }
        if (!context.resources.getBoolean(R.bool.desktop_form_factor)) return null.also { lastSkip = "not a desktop" }
        val displayId = gestureState.displayId
        val desktop = DesktopVisibilityController.INSTANCE.get(context)
        // Overview counts only while it is on screen: Home in front, or its live tile. The launcher can stay in its
        // Overview state behind a desk launched from Overview, which kept desk swipes off until the next state change.
        val running = gestureState.runningTask
        val overviewShowing = desktop.isInOverview(displayId) &&
            (running == null || running.isHomeTask ||
                OverviewComponentObserver.INSTANCE.get(context).getContainerInterface(displayId)?.isInLiveTileMode == true)
        if (overviewShowing) return null.also { lastSkip = "Overview" }
        val desks = desktop.getDeskIds(displayId)
        if (desks.size < 2) return null.also { lastSkip = "desks $desks" }
        watch(desktop)
        Backdrop.warm(context)
        val active = desktop.getActiveDeskId(displayId)
        // No desk active (Home, or the launcher just restarted): the swipe starts from the last desk used.
        val from = if (active in desks) active else lastActive.get(displayId, desks.first()).takeIf { it in desks }
            ?: desks.first()
        return Consumer(context, displayId, inputMonitor, desks, from, fromHome = active !in desks)
    }

    /** The last desk active on each display, for a swipe made while none is. */
    private val lastActive = android.util.SparseIntArray()
    private var watching = false

    private fun watch(desktop: DesktopVisibilityController) {
        if (watching) return
        watching = true
        desktop.registerDesktopVisibilityListener(
            object : DesktopVisibilityController.DesktopVisibilityListener {
                override fun onActiveDeskChanged(displayId: Int, newActiveDesk: Int, oldActiveDesk: Int) {
                    if (newActiveDesk >= 0) lastActive.put(displayId, newActiveDesk)
                    else if (oldActiveDesk >= 0) lastActive.put(displayId, oldActiveDesk)
                }
            }
        )
    }

    private class Consumer(
        private val context: Context,
        private val displayId: Int,
        private val inputMonitor: InputMonitorCompat?,
        private val desks: List<Int>,
        private val activeDesk: Int,
        private val fromHome: Boolean,
    ) : InputConsumer {
        private var travelX = 0f
        private var travelY = 0f
        private var slide: DeskSlide? = null
        private var decided = false
        private var lastTime = 0L
        private var velocity = 0f // fraction of the pad's width per second, in the swipe's direction

        override fun getType() = InputConsumer.TYPE_OTHER_ACTIVITY

        override fun getDisplayId() = displayId

        override fun allowInterceptByParent() = !decided

        override fun onMotionEvent(ev: MotionEvent) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    travelX = 0f
                    travelY = 0f
                    lastTime = ev.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    var dx = 0f
                    var dy = 0f
                    for (h in 0 until ev.historySize) {
                        dx += ev.getHistoricalAxisValue(AXIS_GESTURE_X_OFFSET, 0, h)
                        dy += ev.getHistoricalAxisValue(AXIS_GESTURE_Y_OFFSET, 0, h)
                    }
                    dx += ev.getAxisValue(AXIS_GESTURE_X_OFFSET)
                    dy += ev.getAxisValue(AXIS_GESTURE_Y_OFFSET)
                    travelX += dx
                    travelY += dy
                    val dt = (ev.eventTime - lastTime).coerceAtLeast(1)
                    lastTime = ev.eventTime
                    if (!decided) decide()
                    slide?.let {
                        val v = -it.direction * dx * 1000f / dt
                        velocity = 0.6f * velocity + 0.4f * v
                        it.track(-it.direction * travelX / FULL)
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> slide?.release(velocity / FULL)
            }
        }

        /** Once the fingers have moved far enough, mostly sideways, picks the neighbouring desk and starts. */
        private fun decide() {
            if (abs(travelX) < COMMIT || abs(travelX) < 2 * abs(travelY)) return
            decided = true
            inputMonitor?.pilferPointers()
            // Fingers to the left (travel < 0) bring the desk on the right in from the right.
            val direction = if (travelX < 0) 1 else -1
            val index = desks.indexOf(activeDesk)
            // From Home (no desk active) a swipe either way brings back the last desk used.
            val target = if (fromHome) activeDesk else desks.getOrNull(index + direction)
            if (index < 0 || target == null) {
                Log.d(TAG, "no desk ${if (direction > 0) "right" else "left"} of desk $activeDesk (${desks})")
                return
            }
            Log.d(TAG, "desk $activeDesk${if (fromHome) " (from Home)" else ""} -> $target " +
                "(${if (direction > 0) "right" else "left"}), desks $desks")
            // From Home there is no leaving desk: Home's own desktop slides out.
            val size = context.resources.displayMetrics
            val s = DeskSlide(context, if (fromHome) -1 else activeDesk, target, direction,
                Backdrop.make(context, size.widthPixels, size.heightPixels))
            slide = s
            SystemUiProxy.INSTANCE.get(context)
                .activateDesk(target, RemoteTransition(s, "OpenBookDeskSwipe"), null, DesktopModeTransitionSource.TASKBAR)
        }
    }

    /**
     * The switch's animation: spaces sliding past, as on a Mac. Each desk moves as a whole screen, its windows over its
     * own copy of the desktop (the wallpaper as the display shows it, and the launcher's desktop over it, [Backdrop]),
     * so the whole space slides rather than windows over a still wallpaper. The arriving desk comes in from the side the
     * fingers move away from. While the fingers are down it follows them; when they lift it settles on `spring.desk`
     * from where it is, with their speed, and ends as soon as it looks done. Arriving windows show from the first frame.
     */
    private class DeskSlide(
        private val context: Context,
        private val fromDesk: Int,
        private val toDesk: Int,
        val direction: Int, // +1: the arriving desk comes in from the right
        private val backdrop: HardwareBuffer?,
    ) : RemoteTransitionStub() {
        private var fingers = 0f // where the fingers would put the switch, 0..1
        private var shown = 0f // where it is on screen
        private var released = false
        private var releaseVelocity = 0f
        private var from: SurfaceControl? = null // what moves out: the leaving desk, or a holder for its backdrop
        private var to: SurfaceControl? = null
        private val added = ArrayList<SurfaceControl>() // our layers, removed at the end
        private var home: SurfaceControl? = null // Home's task, hidden while the desks slide
        private var width = 0
        private var height = 0
        private var finish: IRemoteTransitionFinishedCallback? = null
        private var settling: SpringAnimation? = null
        private var started = 0L
        private var frames = 0
        private val frame: Choreographer.FrameCallback =
            Choreographer.FrameCallback {
                if (finish == null || settling != null) return@FrameCallback
                if (released) {
                    settle()
                    return@FrameCallback
                }
                // Catch up with the fingers quickly but without a jump (the transition starts after they do).
                shown += (fingers.coerceIn(0f, 1f) - shown) * CATCH_UP
                place(shown)
                schedule()
            }

        fun track(progress: Float) {
            MAIN_EXECUTOR.execute { fingers = progress }
        }

        fun release(velocity: Float) {
            MAIN_EXECUTOR.execute {
                released = true
                releaseVelocity = velocity.coerceIn(0f, 8f)
                if (finish != null && settling == null) settle()
            }
        }

        override fun startAnimation(
            transition: IBinder,
            info: TransitionInfo,
            startT: Transaction,
            finishCB: IRemoteTransitionFinishedCallback,
        ) {
            MAIN_EXECUTOR.execute {
                started = System.nanoTime()
                var fromLeash: SurfaceControl? = null
                var toLeash: SurfaceControl? = null
                for (c in info.changes) {
                    // Shell starts a remote transition's opening windows transparent and leaves showing them to the
                    // animation: without this the arriving desk's windows appeared only when the switch ended.
                    startT.show(c.leash).setAlpha(c.leash, 1f)
                    when (c.taskInfo?.taskId) {
                        fromDesk -> fromLeash = c.leash
                        toDesk -> toLeash = c.leash
                    }
                    // Home sits between the desks while they switch; its widgets drew over the leaving desk. Each
                    // desk carries Home's desktop in its backdrop instead.
                    if (backdrop != null && c.taskInfo?.activityType == WindowConfiguration.ACTIVITY_TYPE_HOME) {
                        startT.setAlpha(c.leash, 0f)
                        home = c.leash
                    }
                }
                val bounds = info.changes.firstOrNull { it.taskInfo?.taskId == toDesk || it.taskInfo?.taskId == fromDesk }
                    ?.endAbsBounds
                width = bounds?.width()?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
                height = bounds?.height()?.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
                val root = if (info.rootCount > 0) info.getRoot(0).leash else null
                from = space(startT, fromLeash, root)
                to = space(startT, toLeash, root)
                Log.d(TAG, "slide: leaving desk ${if (fromLeash != null) "found" else "empty"}, arriving desk " +
                    "${if (toLeash != null) "found" else "empty"}, backdrop ${if (backdrop != null) "on" else "off"}, " +
                    "${info.changes.size} changes, ${width}x$height")
                position(startT, shown)
                startT.apply()
                finish = finishCB
                if (released) settle() else schedule()
            }
        }

        /**
         * The layer that moves for one desk: the desk itself with the backdrop beneath its windows, or, for a desk with
         * no windows (or Home), a holder of our own with just the backdrop, above Home.
         */
        private fun space(t: Transaction, desk: SurfaceControl?, root: SurfaceControl?): SurfaceControl? {
            val holder =
                desk
                    ?: root?.let {
                        SurfaceControl.Builder()
                            .setName("OpenBook desk space")
                            .setParent(it)
                            .setContainerLayer()
                            .setHidden(false)
                            .setCallsite("DeskSwipe.DeskSlide")
                            .build()
                            .also { h -> t.setLayer(h, Int.MAX_VALUE).show(h); added.add(h) }
                    }
                    ?: return null
            if (backdrop != null) {
                val layer =
                    SurfaceControl.Builder()
                        .setName("OpenBook desk backdrop")
                        .setParent(holder)
                        .setHidden(false)
                        .setCallsite("DeskSwipe.DeskSlide")
                        .build()
                t.setBuffer(layer, backdrop).setLayer(layer, Int.MIN_VALUE).setPosition(layer, 0f, 0f).show(layer)
                added.add(layer)
            }
            return holder
        }

        private fun schedule() {
            Choreographer.getInstance().postFrameCallback(frame)
        }

        private fun settle() {
            // Done when it looks done: the fingers often finish the switch themselves.
            if (!OpenBookMotion.enabled || shown >= 1f - SETTLED) {
                place(1f)
                return done()
            }
            settling =
                SpringAnimation(FloatValueHolder(shown)).apply {
                    spring = OpenBookMotion.DESK.force(1f)
                    setStartVelocity(releaseVelocity)
                    setMinimumVisibleChange(SETTLED)
                    addUpdateListener { _, value, _ -> place(value) }
                    addEndListener { _, _, _, _ -> done() }
                    start()
                }
        }

        private fun place(p: Float) {
            frames++
            val t = Transaction()
            position(t, p)
            t.apply()
        }

        private fun position(t: Transaction, p: Float) {
            val q = p.coerceIn(0f, 1f)
            from?.let { t.setPosition(it, -direction * width * q, 0f) }
            to?.let { t.setPosition(it, direction * width * (1f - q), 0f) }
        }

        private fun done() {
            val cb = finish ?: return
            finish = null
            Log.d(TAG, "switched to desk $toDesk in ${(System.nanoTime() - started) / 1_000_000} ms from the " +
                "transition's start, $frames frames")
            // Our layers go in the same transaction as WM Shell's end state, so no frame shows the desk without them.
            val t = Transaction()
            added.forEach { t.remove(it) }
            added.clear()
            home?.let { t.setAlpha(it, 1f) }
            try {
                cb.onTransitionFinished(null, t)
            } catch (e: RemoteException) {
                Log.w(TAG, "finishing the desk switch", e)
            }
        }

        companion object {
            /** Closer than this to the end (≈ 4 px of the G14's width) the switch is over. */
            const val SETTLED = 0.0015f

            /** Each frame the slide covers this share of its distance to the fingers: gentle when it starts late. */
            const val CATCH_UP = 0.35f
        }
    }

    /**
     * What a desk shows behind its windows: the wallpaper as the display shows it (centre-cropped to the display),
     * with the launcher's desktop (its widgets) over it. The wallpaper is decoded once at the display's size and again
     * when it changes; the launcher's desktop is drawn when a switch starts. Null for a live wallpaper or while the
     * first decode runs, and then the windows slide over the still desktop. Needs READ_WALLPAPER_INTERNAL (granted to
     * the launcher by OpenBook's image for Overview's desk tiles).
     */
    private object Backdrop {
        private var wallpaper: Bitmap? = null
        private var stale = true
        private var loading = false
        private var listening = false

        fun warm(context: Context) {
            val app = context.applicationContext
            if (!listening) {
                listening = true
                app.getSystemService(WallpaperManager::class.java).addOnColorsChangedListener(
                    { _, which -> if (which and WallpaperManager.FLAG_SYSTEM != 0) MAIN_EXECUTOR.execute { stale = true } },
                    MAIN_EXECUTOR.handler,
                )
            }
            if (loading || !(stale || wallpaper == null)) return
            loading = true
            stale = false
            val size = app.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            UI_HELPER_EXECUTOR.execute {
                val bmp = try {
                    decode(app, size.width(), size.height())
                } catch (e: Exception) {
                    Log.w(TAG, "backdrop: wallpaper not decoded", e)
                    null
                }
                MAIN_EXECUTOR.execute {
                    wallpaper = bmp
                    loading = false
                }
            }
        }

        /** The backdrop for a switch starting now, at [w] x [h], as a buffer for SurfaceControl. */
        fun make(context: Context, w: Int, h: Int): HardwareBuffer? {
            val wp = wallpaper ?: return null
            val start = System.nanoTime()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            // The desktop shows the wallpaper zoomed in by the platform's maximum wallpaper scale (1.10 by default),
            // the room the zoom-out into Overview uses; unzoomed, the copy jumped against the screen at both ends.
            val zoom = wallpaperZoom()
            val cw = w / zoom
            val ch = h / zoom
            canvas.drawBitmap(
                wp,
                Rect(((w - cw) / 2).roundToInt(), ((h - ch) / 2).roundToInt(), ((w + cw) / 2).roundToInt(),
                    ((h + ch) / 2).roundToInt()),
                Rect(0, 0, w, h),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            // The launcher's whole desktop, its scrims included: the workspace alone came out brighter than the screen.
            LauncherActivityInterface.INSTANCE.get(context).createdContainer?.dragLayer?.let { ws: android.view.View ->
                if (ws.isAttachedToWindow && ws.visibility == android.view.View.VISIBLE && ws.alpha > 0f) {
                    val loc = IntArray(2)
                    ws.getLocationOnScreen(loc)
                    canvas.save()
                    canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                    ws.draw(canvas)
                    canvas.restore()
                }
            }
            val hw = bmp.copy(Bitmap.Config.HARDWARE, false)
            bmp.recycle()
            Log.d(TAG, "backdrop: ${w}x$h in ${(System.nanoTime() - start) / 1_000_000} ms")
            return hw?.hardwareBuffer
        }

        /** The platform's `config_wallpaperMaxScale`: how far in the desktop shows the wallpaper. */
        private fun wallpaperZoom(): Float {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_wallpaperMaxScale", "dimen", "android")
            return if (id != 0) res.getFloat(id).coerceIn(1f, 2f) else 1.1f
        }

        private fun decode(context: Context, w: Int, h: Int): Bitmap? {
            val wm = context.getSystemService(WallpaperManager::class.java)
            if (wm.wallpaperInfo != null) return null // a live wallpaper: no image to copy
            val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
            val src: Bitmap =
                if (pfd == null) {
                    (wm.getBuiltInDrawable(w, h, true, 0.5f, 0.5f, WallpaperManager.FLAG_SYSTEM) as? BitmapDrawable)
                        ?.bitmap ?: return null
                } else {
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        // Large enough to cover the display, as the system scales it.
                        val s = info.size
                        val scale = max(w.toFloat() / s.width, h.toFloat() / s.height)
                        decoder.setTargetSize(
                            max(1, (s.width * scale).roundToInt()), max(1, (s.height * scale).roundToInt()))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
            // Centre crop to the display.
            val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.translate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
            c.scale(scale, scale)
            c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            Log.d(TAG, "backdrop: wallpaper ${src.width}x${src.height} -> ${w}x$h")
            return out
        }
    }
}
