/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.popup.ui.PopupItem
import com.android.launcher3.util.compose.testTag
import com.android.launcher3.util.compose.testTagContainer
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

/**
 * OpenBook's menu (design §4), drawn in AOSP's popup in place of its `ComposePopup`; see [OpenBookMenu].
 *
 * In order: the taskbar menu's header and the app's windows, the app's shortcuts ([deepShortcuts]; null while they
 * load), then [items] grouped by the spec, with a divider between groups. [onShown] runs once, after the first
 * composition, so the popup can be measured and placed; the popup bounds its height to the room it has, and the rows
 * scroll beyond that. [focusSignal] rises when a menu opened from the keyboard is shown:
 * its first row takes the focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpenBookMenuContent(
    spec: OpenBookMenu.Spec,
    focusSignal: State<Int>,
    focusLast: State<Boolean>,
    items: List<PopupItem>,
    deepShortcuts: List<ItemInfoWithIcon?>,
    onItem: (PopupItem) -> Unit,
    onDeepShortcut: (ItemInfoWithIcon, Rect) -> Unit,
    onDeepShortcutLongPress: (ItemInfoWithIcon, Offset) -> Unit,
    onWindow: (OpenBookMenu.Window) -> Unit,
    onShown: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val t = remember(context) { UiTokens(context, density.density, density.fontScale, spec.byTouch) }

    // Every focusable row, in order, for ↑ ↓ and Tab (wrapping).
    val requesters = remember { mutableListOf<FocusRequester>() }
    requesters.clear()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    fun next(): FocusRequester {
        val r = FocusRequester()
        requesters.add(r)
        return r
    }

    val groups = spec.arrange(items).filter { it.isNotEmpty() }
    var sections = 0
    @Composable
    fun divider() {
        if (sections++ > 0) {
            Box(
                Modifier.fillMaxWidth()
                    .padding(horizontal = t.rowPadding, vertical = t.dividerMargin)
                    .height(t.divider)
                    .background(t.outlineVariant.copy(alpha = t.dividerAlpha))
            )
        }
    }

    Column(
        modifier = Modifier.width(IntrinsicSize.Max)
            .widthIn(min = t.minWidth, max = t.maxWidth)
            .testTagContainer()
            .testTag(TEST_TAG)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || requesters.isEmpty()) return@onPreviewKeyEvent false
                val step = when (e.key) {
                    Key.DirectionDown -> 1
                    Key.DirectionUp -> -1
                    Key.Tab -> if (e.isShiftPressed) -1 else 1
                    Key.MoveHome -> Int.MIN_VALUE
                    Key.MoveEnd -> Int.MAX_VALUE
                    else -> return@onPreviewKeyEvent false
                }
                val n = requesters.size
                val target = when (step) {
                    Int.MIN_VALUE -> 0
                    Int.MAX_VALUE -> n - 1
                    else -> if (focusedIndex < 0) (if (step > 0) 0 else n - 1) else (focusedIndex + step + n) % n
                }
                requesters[target].requestFocus()
                true
            }
            // A menu taller than its room (many windows on a small screen) is bounded by its popup and scrolls; a
            // focused row brings itself into view.
            .verticalScroll(rememberScrollState())
    ) {
        if (spec.header != null || spec.windows.isNotEmpty()) {
            divider()
            spec.header?.let { Header(it, t) }
            val titles = windowTitles(spec.windows)
            spec.windows.forEachIndexed { i, w ->
                val index = requesters.size
                MenuRow(
                    t = t,
                    focusRequester = next(),
                    onFocused = { focusedIndex = index },
                    label = titles[i],
                    strong = w.front,
                    icon = { Thumbnail(t, w.front) },
                    onClick = { onWindow(w) },
                )
            }
        }
        if (deepShortcuts.isNotEmpty()) {
            divider()
            deepShortcuts.forEach { shortcut ->
                val index = requesters.size
                var bounds by remember { mutableStateOf(Rect()) }
                MenuRow(
                    t = t,
                    focusRequester = next(),
                    onFocused = { focusedIndex = index },
                    label = shortcut?.title?.toString() ?: "",
                    icon = {
                        val bitmap = shortcut?.bitmap?.icon
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(t.icon).onGloballyPositioned { c ->
                                    bounds = c.windowRect()
                                },
                            )
                        } else {
                            Spacer(Modifier.size(t.icon))
                        }
                    },
                    onClick = { shortcut?.let { onDeepShortcut(it, bounds) } },
                    onLongClick = shortcut?.let { s -> { at: Offset -> onDeepShortcutLongPress(s, at) } },
                )
            }
        }
        groups.forEach { group ->
            divider()
            group.forEach { item ->
                val index = requesters.size
                MenuRow(
                    t = t,
                    focusRequester = next(),
                    onFocused = { focusedIndex = index },
                    label = spec.label(item) ?: stringResource(item.labelResId),
                    hint = spec.hint(item),
                    icon = {
                        Icon(
                            painter = painterResource(item.iconResId),
                            contentDescription = null,
                            tint = t.onSurfaceVariant,
                            modifier = Modifier.size(t.icon),
                        )
                    },
                    onClick = { onItem(item) },
                )
            }
        }
    }

    // Opened from the keyboard: the first row takes the focus once the menu is shown (design §4).
    val signal = focusSignal.value
    LaunchedEffect(signal) {
        if (signal > 0) {
            // Shift+F10 and the menu key aren't navigation keys, so the window can still be in touch mode, where
            // rows don't take focus: leave it, as the keyboard would.
            if (view.isInTouchMode) view.requestFocusFromTouch()
            val first = if (focusLast.value) requesters.lastOrNull() else requesters.firstOrNull()
            first?.requestFocus()
            withFrameNanos {}
            android.util.Log.d(
                OpenBookMenu.TAG,
                "keyboard: ${if (focusLast.value) "last" else "first"} row focus requested ${first != null}, row $focusedIndex focused, view focus " +
                    "${view.hasFocus()} (window focus ${view.hasWindowFocus()}, touch mode ${view.isInTouchMode}, " +
                    "focused ${view.rootView.findFocus()?.javaClass?.simpleName})",
            )
        }
    }

    val shown = remember { booleanArrayOf(false) }
    SideEffect {
        if (!shown[0]) {
            shown[0] = true
            onShown()
        }
    }
}

/** Window titles; when several are the same (an app that names every window after itself), numbered. */
private fun windowTitles(windows: List<OpenBookMenu.Window>): List<String> {
    val titles = windows.map { it.title.toString() }
    val counts = titles.groupingBy { it }.eachCount()
    val seen = HashMap<String, Int>()
    return titles.map { title ->
        if ((counts[title] ?: 0) < 2) title
        else "$title (${(seen[title] ?: 0) + 1})".also { seen[title] = (seen[title] ?: 0) + 1 }
    }
}

@Composable
private fun Header(header: OpenBookMenu.Header, t: UiTokens) {
    Row(
        modifier = Modifier.fillMaxWidth().height(t.header).padding(horizontal = t.rowPadding)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = header.icon
        // Drawn by the icon's own drawable on Compose's canvas: its shape, and hardware bitmaps, as on the taskbar.
        Box(
            Modifier.size(t.headerIcon).drawBehind {
                if (icon != null) {
                    drawIntoCanvas { c ->
                        icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        icon.draw(c.nativeCanvas)
                    }
                }
            }
        )
        Spacer(Modifier.width(t.headerGap))
        Text(
            text = header.title.toString(),
            style = t.strong,
            color = t.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A window's 20 × 14 dp outline; the front window's is `primary` at 1.5 dp. */
@Composable
private fun Thumbnail(t: UiTokens, front: Boolean) {
    val shape = RoundedCornerShape(t.thumbRadius)
    Box(
        Modifier.size(t.icon)
            .padding(top = (t.icon - t.thumbHeight) / 2, bottom = (t.icon - t.thumbHeight) / 2),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(t.thumbWidth, t.thumbHeight)
                .clip(shape)
                .background(t.surfaceContainerHighest)
                .border(
                    if (front) t.thumbStrokeFront else t.thumbStroke,
                    if (front) t.primary else t.outlineVariant,
                    shape,
                )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuRow(
    t: UiTokens,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    hint: String? = null,
    strong: Boolean = false,
    onLongClick: ((Offset) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val target = when {
        pressed -> t.statePressed
        hovered -> t.stateHover
        else -> 0f
    }
    // duration.hover: in 100 ms, out 150 ms, standard.
    var last by remember { mutableStateOf(0f) }
    val layer by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (target >= last) t.hoverInMs else t.hoverOutMs, easing = t.standard),
        label = "menuRowLayer",
    )
    last = target
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth()
            .height(t.row)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onGloballyPositioned { coords = it }
            .clip(RoundedCornerShape(t.rowRadius))
            .drawBehind {
                val r = CornerRadius(t.rowRadius.toPx())
                if (layer > 0f) drawRoundRect(t.onSurface.copy(alpha = layer), cornerRadius = r)
                if (focused) {
                    val w = t.ring.toPx()
                    drawRoundRect(
                        color = t.primary,
                        topLeft = Offset(w / 2, w / 2),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(r.x - w / 2),
                        style = Stroke(w),
                    )
                }
            }
            .hoverable(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick?.let { cb ->
                    {
                        val c = coords
                        cb(
                            if (c == null) Offset.Zero
                            else c.positionInWindow() + Offset(c.size.width / 2f, c.size.height / 2f)
                        )
                    }
                },
            )
            .semantics { role = Role.Button }
            .testTag(label)
            .padding(horizontal = t.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(t.iconGap))
        Text(
            text = label,
            style = if (strong) t.strong else t.body,
            color = t.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Spacer(Modifier.width(t.hintGap))
            // Soft ink over the 8 % hover layer is 4.2:1 (design §6.4), so a hovered row's hint is ink.
            Text(text = hint, style = t.hint, color = if (hovered) t.onSurface else t.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun LayoutCoordinates.windowRect(): Rect {
    val p = positionInWindow()
    return Rect(p.x.toInt(), p.y.toInt(), (p.x + size.width).toInt(), (p.y + size.height).toInt())
}

/** The menu's tokens in Compose units, read once per menu. */
private class UiTokens(
    c: android.content.Context,
    private val density: Float,
    private val fontScale: Float,
    touch: Boolean,
) {
    private val res = c.resources
    private fun dp(id: Int) = Dp(res.getDimension(id) / density)
    private fun sp(id: Int) = (res.getDimension(id) / density / fontScale).sp

    val minWidth = Dp(dp(R.dimen.ob_mn_min_width).value - 2 * dp(R.dimen.ob_mn_padding).value)
    val maxWidth = Dp(dp(R.dimen.ob_mn_max_width).value - 2 * dp(R.dimen.ob_mn_padding).value)
    val row = dp(if (touch) R.dimen.ob_mn_row_touch else R.dimen.ob_mn_row)
    val rowRadius = dp(R.dimen.ob_mn_row_radius)
    val rowPadding = dp(R.dimen.ob_mn_row_padding)
    val icon = dp(R.dimen.ob_mn_icon)
    val iconGap = dp(R.dimen.ob_mn_icon_gap)
    val hintGap = dp(R.dimen.ob_mn_hint_gap)
    val divider = dp(R.dimen.ob_mn_divider)
    val dividerMargin = dp(R.dimen.ob_mn_divider_margin)
    val dividerAlpha = res.getFloat(R.dimen.ob_mn_divider_alpha)
    val header = dp(R.dimen.ob_mn_header)
    val headerIcon = dp(R.dimen.ob_mn_header_icon)
    val headerGap = dp(R.dimen.ob_mn_header_gap)
    val thumbWidth = dp(R.dimen.ob_mn_thumb_width)
    val thumbHeight = dp(R.dimen.ob_mn_thumb_height)
    val thumbRadius = dp(R.dimen.ob_mn_thumb_radius)
    val thumbStroke = dp(R.dimen.ob_mn_thumb_stroke)
    val thumbStrokeFront = dp(R.dimen.ob_mn_thumb_stroke_front)
    val ring = dp(R.dimen.ob_focus_ring)
    val stateHover = res.getFloat(R.dimen.ob_state_hover)
    val statePressed = res.getFloat(R.dimen.ob_state_pressed)
    val hoverInMs = res.getInteger(R.integer.ob_duration_hover_in_ms)
    val hoverOutMs = res.getInteger(R.integer.ob_duration_hover_out_ms)
    val standard = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

    val onSurface = Color(c.getColor(R.color.materialColorOnSurface))
    val onSurfaceVariant = Color(c.getColor(R.color.materialColorOnSurfaceVariant))
    val outlineVariant = Color(c.getColor(R.color.materialColorOutlineVariant))
    val surfaceContainerHighest = Color(c.getColor(R.color.materialColorSurfaceContainerHighest))
    val primary = Color(c.getColor(R.color.materialColorPrimary))

    private fun family(name: String, weight: Int) =
        FontFamily(ComposeTypeface(Typeface.create(Typeface.create(name, Typeface.NORMAL), weight, false)))

    val body = TextStyle(
        fontFamily = family(OpenBookMenu.FAMILY, res.getInteger(R.integer.ob_mn_weight)),
        fontSize = sp(R.dimen.ob_mn_text),
        lineHeight = sp(R.dimen.ob_mn_line),
    )
    val strong = body.copy(fontFamily = family(OpenBookMenu.FAMILY, res.getInteger(R.integer.ob_mn_weight_strong)))
    val hint = TextStyle(
        fontFamily = family(OpenBookMenu.FAMILY_SMALL, 400),
        fontSize = sp(R.dimen.ob_mn_hint_text),
        lineHeight = sp(R.dimen.ob_mn_hint_line),
    )
}

/** The menu's test tag (uiautomator's resource id). */
const val TEST_TAG = "openbook_menu"
