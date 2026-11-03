/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.android.launcher3.Utilities
import com.android.launcher3.deviceprofile.AllAppsProfile

/**
 * Two-line app names in All Apps (openbook/ROADMAP.md, M1 #6). AOSP 17 has the code: the stored preference
 * `pref_enable_two_line_toggle` (true when the resource `bool/pref_enable_two_line_toggle_default` says so, and
 * OpenBook's desktop overlay does) together with the read-only flag `enable_twoline_toggle`, which is on. The flag
 * `enable_twoline_allapps` has no reader in AOSP 17. Two changes make it right on the desktop:
 * - **Long names keep both lines.** `BubbleTextView.modifyTitleToSupportMultiLine` breaks a name after the words that
 *   fit the first line. AOSP then measured the whole rest, so a name longer than two lines failed the height check
 *   and fell back to one line ending in "…". [fitsTwoLines] measures what the label shows instead: two lines at most,
 *   the second ending in "…".
 * - **The desktop grid keeps its size.** AOSP grows every All Apps cell by one line of text when two lines are on.
 *   The desktop grid's cells already have room for a second line, so there [cellFitsTwoLines] keeps their height.
 *
 * Unchanged from AOSP: two lines for English only, names break between words, and a first word wider than the cell
 * stays on one line ending in "…".
 */
object TwoLineLabels {

    /**
     * Whether [text], a name already broken after its first line, fits [height] as the label shows it: wrapped to
     * [width], at most two lines, the second ending in "…". Exactly [height] fits (the All Apps panel's cells, M2 #9,
     * leave the label two 16 dp lines and nothing more).
     */
    @JvmStatic
    fun fitsTwoLines(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        height: Int,
        spacingMultiplier: Float,
        spacingExtra: Float,
    ): Boolean =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(spacingExtra, spacingMultiplier)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(2)
            .build()
            .height <= height

    /**
     * Whether an All Apps cell has room for a second line of its name without growing. `BubbleTextView` centres the
     * icon and one line of text in the cell, so the label gets that line plus half of the height left over: two lines
     * need the icon, its padding and three lines of text.
     */
    @JvmStatic
    fun cellFitsTwoLines(p: AllAppsProfile): Boolean =
        p.cellHeightPx - p.iconSizePx - p.iconDrawablePaddingPx >=
            3 * Utilities.calculateTextHeight(p.iconTextSizePx)
}
