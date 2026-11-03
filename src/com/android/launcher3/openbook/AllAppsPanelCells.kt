/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.model.data.ItemInfo
import kotlin.math.roundToInt

/**
 * The cells of OpenBook's All Apps panel (openbook/ROADMAP.md M2 #9; OpenBook's docs/launcher-design.md §3.2, §3.3).
 * The panel itself is `AllAppsPanel` in quickstep; its overlay window's context is a [Host] and hands out one of these
 * while its All Apps is the panel. Everything here is a no-op for any other All Apps.
 *
 * - **Cells** are 112 × 104 dp (the panel's `AllAppsProfile`): the 52 dp icon 12 dp from the top, the label 8 dp under
 *   it, 100 dp wide, at 13/16 and weight 450, two lines at most (M1 #6).
 * - **State layers** of `onSurface` with 16 dp corners: hover 8 %, the top hit 10 %, pressed 12 %; the icon presses to
 *   0.96 on `spring.tick`. The focused cell has Quire's 2 dp `primary` ring inside its corners while the keyboard
 *   drives (AOSP's focus highlight is not drawn in the panel).
 * - **The top hit** is the first app a search finds. It has the 10 % tile, and Enter in the field opens it. AOSP 17's
 *   default search never marked one (`DefaultSearchAdapterProvider` records only views it creates itself), so Enter
 *   in the taskbar's All Apps opened nothing.
 * - **No results** reads "No apps match “xyz”", 14/20 in soft ink, centred.
 */
class AllAppsPanelCells(context: Context) {

    /** An activity context whose All Apps may be the panel (the taskbar's overlay window, in quickstep). */
    interface Host {
        /** The panel's cells while this context's All Apps is the panel, else null. */
        fun getAllAppsPanelCells(): AllAppsPanelCells?
    }

    private val t = Tokens(context)

    /** The first app of the current search, or null with no search or no app found. */
    var topHit: ItemInfo? = null
        private set

    /** Styles an All Apps cell as it is created. */
    fun styleCell(icon: BubbleTextView) {
        icon.setCenterVertically(false)
        val side = ((t.column - t.labelWidth) / 2f).roundToInt()
        icon.setPadding(side, t.iconTop, side, 0)
        icon.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.labelText)
        // The label's two 16 dp lines fill the cell to its bottom: no extra font padding above the first.
        icon.includeFontPadding = false
        icon.setLineHeight(t.labelLine)
        icon.typeface = Typeface.create(icon.typeface, t.labelWeight, false)
        icon.setTaskbarIconDecor(decor)
    }

    /** Search results came in ([items]), or the search ended (null). */
    fun onSearchResults(appsView: ActivityAllAppsContainerView<*>, items: List<AdapterItem>?) {
        val hit = items?.firstOrNull { it.viewType == BaseAllAppsAdapter.VIEW_TYPE_ICON && it.itemInfo != null }
            ?.itemInfo
        if (hit === topHit) return
        topHit = hit
        redraw(appsView.searchRecyclerView)
    }

    /** Enter in the field: opens the top hit as a click on it would. Whether there was one. */
    fun launchTopHit(appsView: ActivityAllAppsContainerView<*>): Boolean {
        val hit = topHit ?: return false
        val rv = appsView.searchRecyclerView
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            if (v is BubbleTextView && v.tag === hit) {
                // The field reports the IME's search action twice for one Enter: open it once.
                topHit = null
                Log.d(TAG, "Enter opens the top hit")
                return v.performClick()
            }
        }
        return false
    }

    /** Binds the no-results row for [query]. */
    fun bindNoResults(view: TextView, query: CharSequence) {
        view.text = view.context.getString(R.string.ob_aa_no_results, query)
        view.gravity = Gravity.CENTER_HORIZONTAL
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.emptyText)
        view.setLineHeight(t.emptyLine)
        view.typeface = Typeface.create(Typeface.SANS_SERIF, 400, false)
        view.setTextColor(t.onSurfaceVariant)
        view.setPadding(t.inset, t.emptyTop, t.inset, 0)
    }

    private fun redraw(group: ViewGroup?) {
        if (group == null) return
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            if (v is BubbleTextView) v.invalidate()
        }
    }

    private fun state(v: BubbleTextView): CellState =
        v.getTag(R.id.openbook_allapps_cell_state) as? CellState
            ?: CellState(v).also { v.setTag(R.id.openbook_allapps_cell_state, it) }

    /** One cell's animated state: its layer's opacity and its icon's scale. */
    private inner class CellState(private val view: BubbleTextView) {
        val layer = OpenBookMotion.Value(0f) { view.invalidate() }
        val scale = OpenBookMotion.Value(1f) { view.invalidate() }

        fun update(animate: Boolean) {
            val pressed = view.isPressed
            val to = when {
                pressed -> t.statePressed
                view.tag != null && view.tag === topHit -> t.stateTopHit
                view.isHovered -> t.stateHover
                else -> 0f
            }
            layer.ease(to, OpenBookMotion.hover(layer.value, to), animate)
            scale.spring(if (pressed) t.pressScale else 1f, OpenBookMotion.TICK, animate = animate)
        }
    }

    private val decor = object : TaskbarIconDecor {
        override fun drawBehind(view: BubbleTextView, canvas: Canvas) {
            val s = state(view)
            // A recycled cell may now show another app, which may or may not be the top hit.
            s.update(animate = true)
            if (s.layer.value <= 0f) return
            t.rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
            t.paint.style = Paint.Style.FILL
            t.paint.color = ColorUtils.setAlphaComponent(t.onSurface, (255 * s.layer.value).roundToInt())
            canvas.drawRoundRect(t.rect, t.cellRadius, t.cellRadius, t.paint)
        }

        override fun iconScale(view: BubbleTextView) = state(view).scale.value

        override fun drawOver(view: BubbleTextView, canvas: Canvas) {
            if (!view.isFocused || view.isInTouchMode || !view.hasWindowFocus()) return
            val half = t.ring / 2f
            t.rect.set(half, half, view.width - half, view.height - half)
            t.paint.style = Paint.Style.STROKE
            t.paint.strokeWidth = t.ring
            t.paint.color = t.primary
            canvas.drawRoundRect(t.rect, t.cellRadius - half, t.cellRadius - half, t.paint)
            t.paint.style = Paint.Style.FILL
        }

        override fun onStateChanged(view: BubbleTextView) {
            state(view).update(animate = view.isAttachedToWindow)
            view.invalidate()
        }

        override fun onTouchEvent(view: BubbleTextView, event: MotionEvent) {}
    }

    /** The design's tokens (res/values/openbook_tokens.xml) and Material roles from the overlay's theme. */
    private class Tokens(c: Context) {
        private val res = c.resources
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        val column = res.getDimension(R.dimen.ob_aa_column)
        val inset = res.getDimensionPixelSize(R.dimen.ob_aa_inset)
        val fieldHeight = res.getDimensionPixelSize(R.dimen.ob_aa_field_height)
        val gridTop = res.getDimensionPixelSize(R.dimen.ob_aa_header) - inset - fieldHeight
        val iconTop = res.getDimensionPixelSize(R.dimen.ob_aa_icon_top)
        val labelWidth = res.getDimension(R.dimen.ob_aa_label_width)
        val labelText = res.getDimension(R.dimen.ob_aa_label_text)
        val labelLine = res.getDimensionPixelSize(R.dimen.ob_aa_label_line)
        val labelWeight = res.getInteger(R.integer.ob_aa_label_weight)
        val cellRadius = res.getDimension(R.dimen.ob_aa_cell_radius)
        val emptyText = res.getDimension(R.dimen.ob_aa_empty_text)
        val emptyLine = res.getDimensionPixelSize(R.dimen.ob_aa_empty_line)
        val emptyTop = res.getDimensionPixelSize(R.dimen.ob_aa_empty_top)
        val stateHover = res.getFloat(R.dimen.ob_state_hover)
        val stateTopHit = res.getFloat(R.dimen.ob_state_top_hit)
        val statePressed = res.getFloat(R.dimen.ob_state_pressed)
        val pressScale = res.getFloat(R.dimen.ob_aa_press_scale)
        val ring = res.getDimension(R.dimen.ob_focus_ring)
        val primary = c.getColor(R.color.materialColorPrimary)
        val onSurface = c.getColor(R.color.materialColorOnSurface)
        val onSurfaceVariant = c.getColor(R.color.materialColorOnSurfaceVariant)
    }

    companion object {
        private const val TAG = "OpenBookAllApps"

        /** The panel's cells if [context] is a [Host] whose All Apps is the panel. */
        @JvmStatic
        fun of(context: Any?): AllAppsPanelCells? = (context as? Host)?.getAllAppsPanelCells()

        /** The grid's top padding under the field: 12 dp in the panel (84 dp header), else [aospPadding]. */
        @JvmStatic
        fun gridTop(context: Any?, aospPadding: Int): Int = of(context)?.t?.gridTop ?: aospPadding

        /** How far under the field's top the lists start: the field's height in the panel, else [aospMargin]. */
        @JvmStatic
        fun belowField(context: Any?, aospMargin: Int): Int = of(context)?.t?.fieldHeight ?: aospMargin

        /** Whether [context]'s All Apps is the panel. */
        @JvmStatic
        fun isPanel(context: Any?): Boolean = of(context) != null

        @JvmStatic
        fun styleCell(context: Any?, icon: BubbleTextView) {
            of(context)?.styleCell(icon)
        }

        @JvmStatic
        fun onSearchResults(context: Any?, appsView: ActivityAllAppsContainerView<*>, items: List<AdapterItem>?) {
            of(context)?.onSearchResults(appsView, items)
        }

        @JvmStatic
        fun launchTopHit(context: Any?, appsView: ActivityAllAppsContainerView<*>): Boolean =
            of(context)?.launchTopHit(appsView) ?: false

        /** Binds the no-results row in the panel; false elsewhere (AOSP's text). */
        @JvmStatic
        fun bindNoResults(context: Any?, view: TextView, query: CharSequence): Boolean {
            val cells = of(context) ?: return false
            cells.bindNoResults(view, query)
            return true
        }
    }
}
