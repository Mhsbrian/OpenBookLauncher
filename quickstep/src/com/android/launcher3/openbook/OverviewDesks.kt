/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.internal.dynamicanimation.animation.FloatValueHolder
import com.android.internal.dynamicanimation.animation.SpringAnimation
import com.android.internal.dynamicanimation.animation.SpringForce
import com.android.launcher3.DeviceProfile
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.BaseDragLayer
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OpenBook's Overview for desks (OpenBook's docs/launcher-design.md §5.2). Neither of the roadmap's two Overview flags
 * changes the laptop's own display in AOSP 17 (§5.1), so this is our code, hooked into AOSP's Overview:
 * - **Backdrop:** the platform's background blur behind the launcher at 34 dp, under `surface` at 18 % (light) or 30 %
 *   (dark), instead of AOSP's scrim and wallpaper zoom ([blursBackdrop], [scrimColor], [blurRadius]). Only for the
 *   Overview states: All Apps keeps AOSP's rule (`LauncherDepthController`'s `all_apps_surface` gate).
 * - **Desk tiles** show the desk's wallpaper behind its windows ([deskBackground]), as the display shows it.
 * - **Tiles:** 20 dp corners for a desk and 16 dp for an app, a 1 dp lit outline and a soft shadow; hover lifts a tile
 *   by 4 dp with a deeper shadow on `spring.settle`; keyboard focus is a two-colour ring outside the edge, 2 dp each of
 *   `surfaceContainerLowest`, `onPrimaryContainer`, `surfaceContainerLowest` ([TileDecor]).
 * - **Clear all** is a 40 dp glass chip centred 40 dp above the taskbar ([ClearAllChip]): AOSP's button scrolls with
 *   the tiles and sits off screen next to a single desk.
 *
 * Applies to a launcher whose resources say `bool/desktop_form_factor` (OpenBook's desktop overlay); anywhere else
 * every hook is a no-op and AOSP's Overview is unchanged.
 */
object OverviewDesks {
    const val TAG = "OverviewDesks"

    /** Whether [context]'s launcher draws OpenBook's Overview for desks. */
    @JvmStatic
    fun isEnabled(context: Context): Boolean = context.resources.getBoolean(R.bool.desktop_form_factor)

    // ---- §5.2 Backdrop --------------------------------------------------------------------------------------------

    /**
     * Whether [launcher] is in Overview, or going to or from it, so its backdrop blurs. An app in front of the launcher
     * (`BACKGROUND_APP`, quick switch) doesn't count: on a desk the launcher's window is behind the desk's windows then.
     */
    @JvmStatic
    fun blursBackdrop(launcher: Launcher): Boolean {
        if (!isEnabled(launcher)) return false
        val states = launcher.stateManager
        return isOverview(states.state) || isOverview(states.targetState)
    }

    /** Whether [context]'s launcher is in Overview, with no state transition running. */
    @JvmStatic
    fun settledInOverview(context: Context): Boolean {
        val launcher = ActivityContext.lookupContextNoThrow<Launcher>(context) ?: return false
        val states = launcher.stateManager
        return states.state === LauncherState.OVERVIEW &&
            (states.targetState == null || states.targetState === LauncherState.OVERVIEW)
    }

    private fun isOverview(state: LauncherState?): Boolean =
        state === LauncherState.OVERVIEW || state === LauncherState.OVERVIEW_MODAL_TASK ||
            state === LauncherState.OVERVIEW_SPLIT_SELECT

    /** The backdrop's tint over the blur: `surface` at 18 % (light) or 30 % (dark). */
    @JvmStatic
    fun scrimColor(context: Context): Int {
        val alpha = context.resources.getFloat(
            if (Utilities.isDarkTheme(context)) R.dimen.ob_ov_scrim_dark else R.dimen.ob_ov_scrim_light
        )
        return ColorUtils.setAlphaComponent(context.getColor(R.color.materialColorSurface), (255 * alpha).roundToInt())
    }

    /** The backdrop's blur at its full depth: the glass blur, 34 dp (51 px at 240 dpi). */
    @JvmStatic
    fun blurRadius(context: Context): Int = context.resources.getDimensionPixelSize(R.dimen.ob_glass_blur)

    // ---- §5.2 Tile shape ------------------------------------------------------------------------------------------

    /** A desk tile's corner radius in Overview, 20 dp; null outside OpenBook's Overview. */
    @JvmStatic
    fun deskTileRadius(context: Context): Float? =
        if (isEnabled(context)) context.resources.getDimension(R.dimen.ob_ov_desk_radius) else null

    /** An app tile's corner radius in Overview, 16 dp; null outside OpenBook's Overview. */
    @JvmStatic
    fun appTileRadius(context: Context): Float? =
        if (isEnabled(context)) context.resources.getDimension(R.dimen.ob_ov_app_radius) else null

    /**
     * A desk tile's picture height for the display's shape, 16:10 on the G14 (design §5.2): AOSP sizes the tile
     * [taskHeight] to the windows' area, [areaHeight] px of the display above a persistent taskbar, and the picture
     * continues under the taskbar's strip. Null outside OpenBook's Overview or without that strip.
     */
    @JvmStatic
    fun deskPictureHeight(context: Context, taskHeight: Int, areaHeight: Int, dp: DeviceProfile): Int? {
        val display = dp.deviceProperties.heightPx
        if (!isEnabled(context) || !dp.deviceProperties.isLargeScreen || taskHeight <= 0 || areaHeight <= 0 || areaHeight >= display) {
            return null
        }
        val h = (taskHeight.toLong() * display / areaHeight).toInt()
        if (h != lastPictureHeight) {
            lastPictureHeight = h
            Log.d(TAG, "desk tile: picture ${taskHeight}->${h} px tall for the display's shape")
        }
        return h
    }

    private var lastPictureHeight = 0

    // ---- §5.2 The desk's wallpaper --------------------------------------------------------------------------------

    /**
     * Puts the wallpaper in a desk tile's [background] view, drawn as the display shows it: scaled to the tile's width
     * and top-aligned, like the desk's windows above it. [fallback] (AOSP's flat colour) shows until the wallpaper is
     * decoded, and for a live wallpaper. Returns false outside OpenBook's Overview.
     */
    @JvmStatic
    fun deskBackground(background: View, fallback: Int): Boolean {
        if (!isEnabled(background.context)) return false
        background.background = DeskWallpaper(background.context, fallback)
        return true
    }

    private class DeskWallpaper(private val context: Context, fallback: Int) : Drawable() {
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val fallbackPaint = Paint().apply { color = fallback }
        private val src = Rect()
        private val dst = RectF()

        init {
            Wallpapers.track(this)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val bitmap = Wallpapers.get(context, b.width())
            if (bitmap == null) {
                canvas.drawRect(b, fallbackPaint)
                return
            }
            val display = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            if (display.isEmpty) {
                canvas.drawRect(b, fallbackPaint)
                return
            }
            // The part of the image the display shows (centre crop to the display's shape) ...
            val aspect = display.width().toFloat() / display.height()
            if (bitmap.width.toFloat() / bitmap.height > aspect) {
                val w = (bitmap.height * aspect).roundToInt()
                src.set((bitmap.width - w) / 2, 0, (bitmap.width + w) / 2, bitmap.height)
            } else {
                val h = (bitmap.width / aspect).roundToInt()
                src.set(0, (bitmap.height - h) / 2, bitmap.width, (bitmap.height + h) / 2)
            }
            // ... scaled to the tile's width from the top, as AOSP places the desk's windows (DesktopTaskView).
            dst.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + b.width() / aspect)
            canvas.save()
            canvas.clipRect(b)
            canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            bitmapPaint.alpha = alpha
            fallbackPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /**
     * The system wallpaper, decoded once at the size desk tiles need (at most [MAX_WIDTH] px wide) and shared by every
     * tile. Reloaded when the wallpaper changes (its colours change with it). Needs READ_WALLPAPER_INTERNAL, which
     * OpenBook's image grants the launcher (privapp-permissions-launcher3-overview.xml). A live wallpaper has no image:
     * the tiles keep AOSP's flat colour.
     */
    private object Wallpapers {
        private var bitmap: Bitmap? = null
        private var decodedFor = 0
        private var stale = true
        private var loading = false
        private var unavailable = false
        private var listening = false
        private val drawables = ArrayList<WeakReference<Drawable>>()

        fun track(d: Drawable) {
            drawables.removeAll { it.get() == null }
            drawables.add(WeakReference(d))
        }

        fun get(context: Context, width: Int): Bitmap? {
            listen(context)
            val want = width.coerceIn(MIN_WIDTH, MAX_WIDTH)
            if (!loading && !unavailable && (stale || bitmap == null || want > decodedFor * 5 / 4)) load(context, want)
            return bitmap
        }

        private fun listen(context: Context) {
            if (listening) return
            listening = true
            context.getSystemService(WallpaperManager::class.java).addOnColorsChangedListener(
                { _, which ->
                    if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                        Log.d(TAG, "desk wallpaper: the wallpaper changed")
                        stale = true
                        unavailable = false
                        invalidateAll()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }

        private fun load(context: Context, width: Int) {
            loading = true
            val display = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val aspect = if (display.isEmpty) 1.6f else display.width().toFloat() / display.height()
            val app = context.applicationContext
            THREAD_POOL_EXECUTOR.execute {
                val result = try {
                    decode(app, width, aspect)
                } catch (e: Exception) {
                    Log.w(TAG, "desk wallpaper: can't read it, flat colour (${e.javaClass.simpleName}: ${e.message})")
                    null
                }
                MAIN_EXECUTOR.execute {
                    loading = false
                    stale = false
                    if (result == null) {
                        unavailable = true
                    } else {
                        bitmap = result
                        decodedFor = width
                    }
                    invalidateAll()
                }
            }
        }

        private fun decode(context: Context, width: Int, aspect: Float): Bitmap? {
            val wm = context.getSystemService(WallpaperManager::class.java)
            if (wm.wallpaperInfo != null) {
                Log.d(TAG, "desk wallpaper: a live wallpaper (${wm.wallpaperInfo?.component}), flat colour")
                return null
            }
            val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
            if (pfd == null) {
                // The built-in wallpaper (nobody has set one): no file, so the platform's decoder, at our size and
                // centre-cropped to the display's shape.
                val d = wm.getBuiltInDrawable(width, (width / aspect).roundToInt(), true, 0.5f, 0.5f,
                    WallpaperManager.FLAG_SYSTEM) as? BitmapDrawable
                val bmp = d?.bitmap
                Log.d(TAG, "desk wallpaper: built-in, " + (bmp?.let { "${it.width}x${it.height}" } ?: "none, flat colour"))
                return bmp
            }
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
            var from = ""
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val s = info.size
                val scale = min(1f, width.toFloat() / s.width)
                decoder.setTargetSize(max(1, (s.width * scale).roundToInt()), max(1, (s.height * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                from = "${s.width}x${s.height}"
            }
            Log.d(TAG, "desk wallpaper: ${bmp.width}x${bmp.height} from $from (id ${wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)})")
            return bmp
        }

        private fun invalidateAll() {
            drawables.removeAll { it.get() == null }
            drawables.forEach { it.get()?.invalidateSelf() }
        }

        private const val MIN_WIDTH = 320
        private const val MAX_WIDTH = 1600
    }

    // ---- §5.2 Tiles: shadow, outline, hover lift and the focus ring ------------------------------------------------

    /**
     * A tile's decoration for [view] (a TaskView), or null outside OpenBook's Overview (AOSP's borders stay).
     * [bounds] gives the tile's picture in [view]'s coordinates, [desk] whether it is a desk, [settled] how settled in
     * Overview it is (1 - fullscreen progress), and [onLift] applies [TileDecor.lift] to the view's translation.
     */
    @JvmStatic
    fun tileDecor(
        view: View,
        desk: () -> Boolean,
        bounds: (Rect) -> Unit,
        settled: () -> Float,
        onLift: () -> Unit,
    ): TileDecor? = if (isEnabled(view.context)) TileDecor(view, desk, bounds, settled, onLift) else null

    class TileDecor internal constructor(
        private val view: View,
        private val desk: () -> Boolean,
        private val bounds: (Rect) -> Unit,
        private val settled: () -> Float,
        private val onLift: () -> Unit,
    ) {
        private val res = view.resources
        private val dark = Utilities.isDarkTheme(view.context)
        private val liftBy = res.getDimension(R.dimen.ob_ov_lift)
        private val band = res.getDimension(R.dimen.ob_ov_ring_band)
        private val edge = res.getDimension(R.dimen.ob_glass_edge)
        private val restY = res.getDimension(R.dimen.ob_ov_shadow_y)
        private val restBlur = res.getDimension(R.dimen.ob_ov_shadow_blur)
        private val hoverY = res.getDimension(R.dimen.ob_ov_shadow_hover_y)
        private val hoverBlur = res.getDimension(R.dimen.ob_ov_shadow_hover_blur)
        private val restAlpha = res.getFloat(R.dimen.ob_ov_shadow_alpha)
        private val hoverAlpha = res.getFloat(R.dimen.ob_ov_shadow_hover_alpha)
        private val outline = ColorUtils.setAlphaComponent(Color.WHITE, if (dark) 36 else 140) // 14 %, 55 %
        private val ringOuter = view.context.getColor(R.color.materialColorSurfaceContainerLowest)
        private val ringMiddle = view.context.getColor(R.color.materialColorOnPrimaryContainer)
        private val stiffness = res.getFloat(R.dimen.ob_spring_settle_stiffness)
        private val damping = res.getFloat(R.dimen.ob_spring_settle_damping)

        private val rect = Rect()
        private val rectF = RectF()
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var spring: SpringAnimation? = null

        /** The hover lift, px (0 or up to -4 dp): TaskView adds it to its translationY. */
        var lift = 0f
            private set

        var hovered = false
            set(v) {
                if (field == v) return
                field = v
                Log.d(TAG, "tile ${if (v) "hover" else "unhover"}")
                springTo(if (v) -liftBy else 0f)
            }

        var focused = false
            set(v) {
                if (field == v) return
                field = v
                Log.d(TAG, "tile ${if (v) "focus" else "unfocus"}")
                view.invalidate()
            }

        val radius: Float
            get() = res.getDimension(if (desk()) R.dimen.ob_ov_desk_radius else R.dimen.ob_ov_app_radius)

        /** Down from the hover lift at once: the tile is leaving Overview (fullscreen progress), or recycled. */
        fun rest() {
            spring?.cancel()
            resting = true
            hovered = false
            resting = false
            if (lift != 0f) setLift(0f)
        }

        private var resting = false

        private fun springTo(to: Float) {
            if (resting || !ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow) {
                spring?.cancel()
                setLift(to)
                return
            }
            val s = spring ?: SpringAnimation(FloatValueHolder()).also { s ->
                s.spring = SpringForce(to).setStiffness(stiffness).setDampingRatio(damping)
                s.setMinimumVisibleChange(0.5f)
                s.addUpdateListener { _, v, _ -> setLift(v) }
                spring = s
            }
            if (!s.isRunning) s.setStartValue(lift)
            s.animateToFinalPosition(to)
        }

        private fun setLift(v: Float) {
            lift = v
            onLift()
            view.invalidate()
        }

        private fun tile(): Boolean {
            bounds(rect)
            if (rect.isEmpty) return false
            rectF.set(rect)
            path.reset()
            path.addRoundRect(rectF, radius, radius, Path.Direction.CW)
            return true
        }

        /** The shadow and the lit outline, under the tile's picture. */
        fun drawBehind(canvas: Canvas) {
            val a = settled().coerceIn(0f, 1f)
            if (a <= 0f || !tile()) return
            val t = if (liftBy > 0f) (-lift / liftBy).coerceIn(0f, 1f) else 0f
            val dy = restY + (hoverY - restY) * t
            val blur = restBlur + (hoverBlur - restBlur) * t
            val alpha = (restAlpha + (hoverAlpha - restAlpha) * t) * a
            canvas.save()
            canvas.clipOutPath(path)
            // Skia's sigma for a radius r is 0.57735 r + 0.5; a CSS blur b has sigma b / 2 (GlassDrawable's shadow).
            shadowPaint.color = Color.BLACK
            shadowPaint.setShadowLayer(max(1f, ((blur / 2f) - 0.5f) / 0.57735f), 0f, dy,
                Color.argb((255 * alpha).roundToInt(), 0, 0, 0))
            canvas.drawPath(path, shadowPaint)
            // A 1 dp outline just outside the edge (the glass outline).
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = edge
            paint.color = ColorUtils.setAlphaComponent(outline, (Color.alpha(outline) * a).roundToInt())
            rectF.inset(-edge / 2, -edge / 2)
            canvas.drawRoundRect(rectF, radius + edge / 2, radius + edge / 2, paint)
            canvas.restore()
        }

        /** The focus ring, over everything: three 2 dp bands outside the edge (WCAG technique C40). */
        fun drawOver(canvas: Canvas) {
            if (!focused || settled() < 1f || !tile()) return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = band
            for ((i, color) in listOf(ringOuter, ringMiddle, ringOuter).withIndex()) {
                val out = band * i + band / 2
                rectF.set(rect)
                rectF.inset(-out, -out)
                paint.color = color
                canvas.drawRoundRect(rectF, radius + out, radius + out, paint)
            }
        }
    }

    // ---- §5.2 Labels: a glass chip above each tile -----------------------------------------------------------------

    /** Overview's label chip style for an IconAppChipView in [context], or null outside OpenBook's Overview. */
    @JvmStatic
    fun label(context: Context): Label? = if (isEnabled(context)) Label(context) else null

    /**
     * Overview's label (design §5.2): a 28 dp glass chip 12 dp above its tile, the app's 20 dp icon or the desk glyph
     * 4 dp from the start, 8 dp before the name at 13/18 and weight 500, 12 dp after it. It sizes to its text, with no
     * arrow: the chip itself still opens AOSP's task menu.
     */
    class Label internal constructor(context: Context) {
        private val res = context.resources
        val height = res.getDimensionPixelSize(R.dimen.ob_ov_label_height)
        val icon = res.getDimensionPixelSize(R.dimen.ob_ov_label_icon)
        val paddingStart = res.getDimensionPixelSize(R.dimen.ob_ov_label_padding_start)
        val paddingEnd = res.getDimensionPixelSize(R.dimen.ob_ov_label_padding_end)
        val gap = res.getDimensionPixelSize(R.dimen.ob_ov_label_gap)
        val radius = height / 2
        private val above = res.getDimensionPixelSize(R.dimen.ob_ov_label_above)
        private val ink = context.getColor(R.color.materialColorOnSurface)

        /** The name's look: 13/18 at weight 500 in ink. */
        fun styleText(text: TextView) {
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.ob_ov_label_text))
            text.setLineHeight(res.getDimensionPixelSize(R.dimen.ob_ov_label_line))
            text.typeface = Typeface.create(text.typeface, res.getInteger(R.integer.ob_ov_label_weight), false)
            text.setTextColor(ink)
            text.includeFontPadding = false
        }

        /** The chip's width: its contents, at most [max]. */
        fun width(text: TextView?, max: Int): Int {
            val t = text?.text ?: ""
            val w = paddingStart + icon + gap + (text?.paint?.measureText(t, 0, t.length)?.let { kotlin.math.ceil(it).toInt() } ?: 0) + paddingEnd
            return min(w, max)
        }

        /** Above the tile: the chip's background ([backgroundMargin] inside the view) [above] over the tile's top. */
        fun place(params: FrameLayout.LayoutParams, backgroundMargin: Int) {
            params.gravity = Gravity.TOP or Gravity.START
            params.topMargin = -(height + above + backgroundMargin)
            params.marginStart = -backgroundMargin
            params.bottomMargin = 0
        }

        /** The glass, drawn over [area] of [view] (the chip's background, which grows when its menu opens). */
        fun glass(view: View, area: (Rect) -> Unit): Drawable = LabelGlass(view, area, radius.toFloat())
    }

    private class LabelGlass(view: View, private val area: (Rect) -> Unit, private val radius: Float) : Drawable() {
        private val glass = GlassDrawable(view, GlassDrawable.Shadow.MENU, "label", TAG)
        private val r = Rect()
        private val view = view
        private val loc = IntArray(2)
        private val logged = Rect()

        override fun draw(canvas: Canvas) {
            area(r)
            if (r.isEmpty) return
            // Where the label is on the screen, for the VM test (the chip lies outside its tile's bounds, so the
            // accessibility tree leaves it out, as AOSP's label is not important for accessibility anyway).
            view.getLocationOnScreen(loc)
            if (logged.left != r.left + loc[0] || logged.top != r.top + loc[1] || logged.width() != r.width()) {
                logged.set(r)
                logged.offset(loc[0], loc[1])
                Log.d(TAG, "label at [${logged.left},${logged.top}][${logged.right},${logged.bottom}]")
            }
            glass.bounds = r
            if (glass.radius != radius) glass.radius = radius
            glass.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            glass.surfaceAlpha = alpha / 255f
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * A desk's label: [name], then the apps of its open windows in soft ink at weight 400 ("Desktop · Files,
     * Settings"). Just [name] outside OpenBook's Overview or for an empty desk.
     */
    @JvmStatic
    fun deskLabel(context: Context, name: CharSequence, packages: List<String>): CharSequence {
        if (!isEnabled(context)) return name
        val pm = context.packageManager
        val apps = packages.distinct().mapNotNull { pkg ->
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                null
            }
        }
        Log.d(TAG, "desk label: $name · ${apps.joinToString(", ")}")
        if (apps.isEmpty()) return name
        val soft = context.getColor(R.color.materialColorOnSurfaceVariant)
        val out = android.text.SpannableStringBuilder(name)
        val start = out.length
        out.append("  ·  ").append(apps.joinToString(", "))
        val flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        out.setSpan(android.text.style.ForegroundColorSpan(soft), start, out.length, flags)
        out.setSpan(android.text.style.TypefaceSpan(Typeface.create(Typeface.DEFAULT, 400, false)), start, out.length, flags)
        return out
    }

    // ---- §5.2 Clear all ---------------------------------------------------------------------------------------------

    /**
     * Adds the glass Clear all chip for AOSP's [button] (attached to RecentsView) to the launcher's drag layer, once.
     * Returns the chip, or null outside OpenBook's Overview. The chip does what the button does ([View.performClick])
     * and takes its alpha (every alpha but the scroll's, [ClearAllChip.setButtonAlpha]); the button itself stays
     * invisible and out of the focus order.
     */
    @JvmStatic
    fun attachClearAll(button: View): ClearAllChip? {
        if (!isEnabled(button.context)) return null
        button.isFocusable = false
        val existing = button.getTag(R.id.openbook_clear_all_chip) as? ClearAllChip
        if (existing != null) {
            existing.place()
            return existing
        }
        val launcher = ActivityContext.lookupContextNoThrow<Launcher>(button.context) ?: return null
        val dragLayer = launcher.dragLayer ?: return null
        val chip = ClearAllChip(button.context, button, dragLayer)
        button.setTag(R.id.openbook_clear_all_chip, chip)
        chip.place()
        Log.d(TAG, "clear all: glass chip")
        return chip
    }

    /** The chip for AOSP's [button], if it has one. */
    @JvmStatic
    fun clearAllChip(button: View): ClearAllChip? = button.getTag(R.id.openbook_clear_all_chip) as? ClearAllChip

    /** A 40 dp glass chip with the clear-all glyph and "Clear all" (14/20 at 500), centred 40 dp above the taskbar. */
    @Suppress("ViewConstructor")
    class ClearAllChip internal constructor(
        context: Context,
        private val button: View,
        private val dragLayer: BaseDragLayer<*>,
    ) : TextView(context) {
        private val res = context.resources
        private val glass = GlassDrawable(this, GlassDrawable.Shadow.MENU, "clear all", TAG)
        private val onSurface = context.getColor(R.color.materialColorOnSurface)
        private val primary = context.getColor(R.color.materialColorPrimary)
        private val ring = res.getDimension(R.dimen.ob_focus_ring)
        private val ringOffset = res.getDimension(R.dimen.ob_focus_ring_offset)
        private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rectF = RectF()
        private var buttonAlpha = 1f

        init {
            id = R.id.openbook_clear_all_chip
            text = res.getString(R.string.recents_clear_all)
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.ob_ov_chip_text))
            setLineHeight(res.getDimensionPixelSize(R.dimen.ob_ov_chip_line))
            typeface = Typeface.create(typeface, res.getInteger(R.integer.ob_ov_chip_weight), false)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            includeFontPadding = false
            val icon = context.getDrawable(R.drawable.ob_ic_clear_all)?.mutate()
            val size = res.getDimensionPixelSize(R.dimen.ob_ov_chip_icon)
            icon?.setBounds(0, 0, size, size)
            icon?.setTint(onSurface)
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = res.getDimensionPixelSize(R.dimen.ob_ov_chip_gap)
            setPaddingRelative(res.getDimensionPixelSize(R.dimen.ob_ov_chip_padding_start), 0,
                res.getDimensionPixelSize(R.dimen.ob_ov_chip_padding_end), 0)
            glass.radius = res.getDimension(R.dimen.ob_ov_chip_height) / 2
            background = glass
            isFocusable = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            contentDescription = text
            setOnClickListener {
                Log.d(TAG, "clear all: chip clicked")
                button.performClick()
            }
        }

        /** In the drag layer, centred, its bottom 40 dp above the taskbar. */
        fun place() {
            val lp = (layoutParams as? BaseDragLayer.LayoutParams)
                ?: BaseDragLayer.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    res.getDimensionPixelSize(R.dimen.ob_ov_chip_height))
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.ignoreInsets = true
            val launcher = ActivityContext.lookupContextNoThrow<Launcher>(context)
            val taskbar = launcher?.deviceProfile?.taskbarProfile?.height ?: 0
            lp.bottomMargin = taskbar + res.getDimensionPixelSize(R.dimen.ob_ov_chip_above_bar)
            if (parent == null) dragLayer.addView(this, lp) else layoutParams = lp
            setButtonAlpha(buttonAlpha)
        }

        /** Takes AOSP's button's alpha, without its scroll alpha: the chip doesn't scroll. */
        fun setButtonAlpha(a: Float) {
            buttonAlpha = a
            alpha = a
            visibility = if (a > 0f && button.isAttachedToWindow) VISIBLE else GONE
            isClickable = a >= 1f
        }

        /** AOSP's button left RecentsView (no tasks): the chip goes with it. */
        fun detach() {
            visibility = GONE
        }

        override fun onHoverChanged(hovered: Boolean) {
            super.onHoverChanged(hovered)
            invalidate()
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val r = super.dispatchTouchEvent(event)
            invalidate()
            return r
        }

        override fun onDraw(canvas: Canvas) {
            // The state layer: onSurface at 8 % hovered, 12 % pressed (design §6.2).
            val layer = when {
                isPressed -> res.getFloat(R.dimen.ob_state_pressed)
                isHovered -> res.getFloat(R.dimen.ob_state_hover)
                else -> 0f
            }
            if (layer > 0f) {
                layerPaint.color = ColorUtils.setAlphaComponent(onSurface, (255 * layer).roundToInt())
                rectF.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(rectF, height / 2f, height / 2f, layerPaint)
            }
            super.onDraw(canvas)
        }

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            // The focus ring on glass: 2 dp primary, 2 dp outside (design §6.2).
            if (isFocused) {
                ringPaint.color = primary
                ringPaint.strokeWidth = ring
                val o = ringOffset + ring / 2
                rectF.set(-o, -o, width + o, height + o)
                canvas.drawRoundRect(rectF, height / 2f + o, height / 2f + o, ringPaint)
            }
        }
    }
}
