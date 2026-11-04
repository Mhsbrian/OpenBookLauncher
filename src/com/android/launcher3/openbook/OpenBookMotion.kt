/*
 * Copyright (C) 2026 The OpenBook Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.openbook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.util.FloatProperty
import android.view.animation.Interpolator
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.app.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.app.animation.Interpolators.STANDARD
import com.android.launcher3.anim.SpringAnimationBuilder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenBook's one motion system (openbook/ROADMAP.md M2 #12; OpenBook's docs/launcher-design.md §7): three springs and
 * three durations, the same as Quire's (OpenBook's docs/quire-design.md §5.1, `dev.openbook.quire.ui.Motion`). Every
 * animation the launcher's OpenBook code runs takes its numbers from here; nothing else holds a stiffness or a
 * duration.
 *
 * - **Geometry on springs, opacity and colour on durations.** Leaving is faster than arriving and never overshoots.
 * - **A spring in flight is retargeted, not restarted** ([Value.spring]), so a quick reverse continues from where it
 *   is, with its velocity.
 * - **Remove animations** (`ValueAnimator.areAnimatorsEnabled()` false) makes every token a cut. Everything here checks
 *   [enabled] itself, because a spring doesn't read the animator duration scale.
 *
 * Springs are stiffness and damping ratio with mass 1, as in `SpringForce` and Compose's `spring()`.
 */
object OpenBookMotion {

    /** A spring token. */
    class Spring(val name: String, val stiffness: Float, val damping: Float) {
        fun force(to: Float): SpringForce = SpringForce(to).setStiffness(stiffness).setDampingRatio(damping)

        fun <T> compose(): SpringSpec<T> = spring(dampingRatio = damping, stiffness = stiffness)

        /**
         * An animator that follows this spring's exact path from [from] to [to] (`SpringAnimationBuilder`), for code
         * that plays animators in a set. It lasts until the spring is within [minVisibleChange] of rest, and calls
         * [update] with the spring's value on every frame. It can't be retargeted: use [Value] for state that can turn
         * around.
         */
        fun animator(
            context: Context,
            from: Float,
            to: Float,
            minVisibleChange: Float = SpringAnimation.MIN_VISIBLE_CHANGE_SCALE,
            update: (Float) -> Unit,
        ): ValueAnimator =
            builder(context, from, to, minVisibleChange).build(
                Unit,
                object : FloatProperty<Unit>(name) {
                    override fun setValue(target: Unit, value: Float) = update(value)

                    override fun get(target: Unit): Float = 0f
                },
            )

        /**
         * How long this spring, let go at rest at [from], takes to come to rest at [to] as a `SpringAnimation` judges
         * it: within 0.75 × [minVisibleChange] of [to] and slower than 62.5 times that per second (`SpringForce`'s
         * thresholds). `spring.panel` from 0 to 1 at the scale threshold: 275 ms. Underdamped springs only, as all
         * three tokens are.
         */
        fun settleMs(
            from: Float = 0f,
            to: Float = 1f,
            minVisibleChange: Float = SpringAnimation.MIN_VISIBLE_CHANGE_SCALE,
        ): Long {
            val threshold = minVisibleChange * 0.75
            val sigma = damping * sqrt(stiffness.toDouble())
            val omega = sqrt(stiffness.toDouble()) * sqrt(1.0 - damping * damping)
            val a = (from - to).toDouble()
            val b = sigma * a / omega
            for (ms in 0..MAX_SETTLE_MS) {
                val t = ms / 1000.0
                val e = exp(-sigma * t)
                val x = e * (a * cos(omega * t) + b * sin(omega * t))
                val v = -e * (sigma * b + omega * a) * sin(omega * t)
                if (abs(x) < threshold && abs(v) < threshold * 62.5) return ms.toLong()
            }
            return MAX_SETTLE_MS.toLong()
        }

        private fun builder(context: Context, from: Float, to: Float, minVisibleChange: Float) =
            SpringAnimationBuilder(context)
                .setStiffness(stiffness)
                .setDampingRatio(damping)
                .setMinimumVisibleChange(minVisibleChange)
                .setStartValue(from)
                .setEndValue(to)

        override fun toString() = "$name ($stiffness, $damping)"

        private companion object {
            const val MAX_SETTLE_MS = 5000
        }
    }

    /** A duration token: a time and a curve. */
    class Duration(val name: String, val ms: Long, val curve: Interpolator) {
        val easing = Easing { curve.getInterpolation(it) }

        fun <T> compose(): TweenSpec<T> = tween(ms.toInt(), easing = easing)

        /** A [ValueAnimator] from [from] to [to] in this time on this curve, calling [update] every frame. */
        fun animator(from: Float, to: Float, update: (Float) -> Unit): ValueAnimator =
            ValueAnimator.ofFloat(from, to).also {
                it.duration = ms
                it.interpolator = curve
                it.addUpdateListener { a -> update(a.animatedValue as Float) }
            }

        override fun toString() = "$name ($ms ms)"
    }

    /** `spring.panel`: All Apps opening, Overview's tiles, a menu's scale. Settles in ≈ 280 ms, 0.3 % overshoot. */
    @JvmField val PANEL = Spring("spring.panel", 500f, 0.90f)

    /** `spring.desk`: a desk sliding the display's width as desks switch (four-finger swipe). Settles in ≈ 380 ms. */
    @JvmField val DESK = Spring("spring.desk", 300f, 0.95f)

    /** `spring.settle` (Quire's): a line of state's width, Overview's hover lift, reflow. Settles in ≈ 170 ms. */
    @JvmField val SETTLE = Spring("spring.settle", 700f, 0.90f)

    /** `spring.tick` (Quire's): the press on icons and the pill. Settles in ≈ 150 ms. */
    @JvmField val TICK = Spring("spring.tick", 1400f, 0.70f)

    /** `duration.fade` (Quire's): colour changes, the grid's fade-in, the tooltip, a surface's opacity arriving. */
    @JvmField val FADE = Duration("duration.fade", 120, STANDARD)

    /** `duration.hover`, in: a state layer appearing or deepening. */
    @JvmField val HOVER_IN = Duration("duration.hover", 100, STANDARD)

    /** `duration.hover`, out: a state layer going or lightening. */
    @JvmField val HOVER_OUT = Duration("duration.hover", 150, STANDARD)

    /** `duration.exit` (Quire's `duration.unwrite` curve): panels closing. */
    @JvmField val EXIT = Duration("duration.exit", 150, EMPHASIZED_ACCELERATE)

    /** `duration.exit` for a menu, which fades out in 100 ms on the same curve (§7). */
    @JvmField val EXIT_MENU = Duration("duration.exit", 100, EMPHASIZED_ACCELERATE)

    /** False when the person turned on Remove animations: every token is then a cut. */
    @JvmStatic
    val enabled: Boolean
        get() = ValueAnimator.areAnimatorsEnabled()

    /** `duration.hover` for a state layer going from [from] to [to]: in when it deepens, out when it lightens. */
    @JvmStatic
    fun hover(from: Float, to: Float): Duration = if (to > from) HOVER_IN else HOVER_OUT

    /**
     * A value that eases to its target on a [Duration] or springs to it on a [Spring], calling [onChange] with every
     * new value; a cut with animations off or `animate` false. A spring in flight is retargeted and keeps its
     * velocity. Use it on one thread (the view's).
     */
    class Value(initial: Float, private val onChange: (Float) -> Unit) {
        var value = initial
            private set
        var target = initial
            private set
        private var animator: ValueAnimator? = null
        private var spring: SpringAnimation? = null

        fun snap(to: Float) {
            animator?.cancel()
            animator = null
            spring?.cancel()
            value = to
            target = to
            onChange(to)
        }

        fun ease(to: Float, duration: Duration, animate: Boolean = true) {
            if (to == target) return
            target = to
            if (!animate || !enabled) return snap(to)
            spring?.cancel()
            animator?.cancel()
            animator = duration.animator(value, to) {
                value = it
                onChange(it)
            }.also { a ->
                a.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) animator = null
                    }
                })
                a.start()
            }
        }

        /** Springs to [to]; [minVisibleChange] is the spring's rest threshold in this value's units. */
        fun spring(
            to: Float,
            spring: Spring,
            minVisibleChange: Float = SpringAnimation.MIN_VISIBLE_CHANGE_SCALE,
            animate: Boolean = true,
        ) {
            if (to == target) return
            target = to
            if (!animate || !enabled) return snap(to)
            animator?.cancel()
            animator = null
            val s = this.spring ?: SpringAnimation(FloatValueHolder()).also { s ->
                s.addUpdateListener { _, v, _ ->
                    value = v
                    onChange(v)
                }
                this.spring = s
            }
            s.setMinimumVisibleChange(minVisibleChange)
            if (s.isRunning) {
                s.spring.setStiffness(spring.stiffness).setDampingRatio(spring.damping)
            } else {
                s.spring = spring.force(to)
                s.setStartValue(value)
            }
            s.animateToFinalPosition(to)
        }
    }

    /** A colour that fades to its target on a [Duration], calling [onChange] on every frame. */
    class ColorValue(initial: Int, private val onChange: () -> Unit) {
        private var from = initial
        private var to = initial
        private val fraction = Value(1f) { onChange() }

        val value: Int
            get() = if (fraction.value >= 1f) to else ARGB.evaluate(fraction.value, from, to) as Int

        fun ease(color: Int, duration: Duration, animate: Boolean = true) {
            if (color == to) return
            from = value
            to = color
            fraction.snap(0f)
            fraction.ease(1f, duration, animate)
        }
    }

    private val ARGB = ArgbEvaluator()
}
