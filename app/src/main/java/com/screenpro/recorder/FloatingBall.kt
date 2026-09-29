package com.screenpro.recorder

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating timer ball. Tap it to expand into Stop / Pause / Screenshot / Pen.
 * It collapses back to the ball on its own so it stays out of your video.
 */
class FloatingBall(
    private val context: Context,
    private val wm: WindowManager,
    private val overlayType: Int,
    private val onStop: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onScreenshot: () -> Unit,
    private val onBrush: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: TextView? = null
    private var pauseBtn: TextView? = null
    private var pulse: ObjectAnimator? = null

    private var expanded = false
    private var paused = false
    private var brushOn = false
    private var timeText = "00:00"

    private val red = Color.parseColor("#E5202E")
    private val orange = Color.parseColor("#FF6B00")
    private val grey = Color.parseColor("#6B7280")

    private val collapseRunnable = Runnable {
        if (expanded) {
            expanded = false
            rebuild(true)
        }
    }

    fun show() {
        if (root != null) return

        val r = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val dm = context.resources.displayMetrics

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - dp(64)
            y = dp(160)
        }

        root = r
        params = p
        rebuild(false)

        try {
            wm.addView(r, p)
        } catch (e: Exception) {
            e.printStackTrace()
            root = null
        }
    }

    fun remove() {
        handler.removeCallbacks(collapseRunnable)
        pulse?.cancel()
        pulse = null

        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        root = null
        ball = null
        pauseBtn = null
        params = null
        expanded = false
    }

    fun update(time: String, isPaused: Boolean) {
        timeText = time
        ball?.text = time
        ball?.textSize = if (time.length > 5) 10f else 13f

        if (isPaused != paused) {
            paused = isPaused
            pauseBtn?.text = if (paused) "▶" else "❚❚"
            applyBallStyle()
        }
    }

    fun setBrushActive(active: Boolean) {
        brushOn = active
        if (expanded) rebuild(true)
    }

    fun hideTemporarily() {
        root?.visibility = View.INVISIBLE
    }

    fun showAgain() {
        root?.visibility = View.VISIBLE
    }

    fun bringToFront() {
        val r = root ?: return

        try {
            wm.removeView(r)
            wm.addView(r, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // BUILDING

    private fun rebuild(update: Boolean) {
        val r = root ?: return

        pulse?.cancel()
        pulse = null
        r.removeAllViews()

        val b = makeBall()
        ball = b
        r.addView(b, LinearLayout.LayoutParams(dp(54), dp(54)))

        pauseBtn = null

        if (expanded) {
            addButton(r, "■", red, Color.WHITE) { onStop() }

            pauseBtn = addButton(
                r,
                if (paused) "▶" else "❚❚",
                Color.WHITE,
                red
            ) { onTogglePause() }

            addButton(r, "📷", Color.WHITE, orange) { onScreenshot() }

            addButton(
                r,
                "🖌",
                if (brushOn) orange else Color.WHITE,
                if (brushOn) Color.WHITE else orange
            ) { onBrush() }

            scheduleCollapse()
        }

        applyBallStyle()

        if (update) {
            try {
                wm.updateViewLayout(r, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun makeBall(): TextView {
        return TextView(context).apply {
            text = timeText
            textSize = if (timeText.length > 5) 10f else 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnTouchListener(dragListener())
        }
    }

    private fun addButton(
        parent: LinearLayout,
        glyph: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit
    ): TextView {
        val v = TextView(context).apply {
            text = glyph
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = circle(bg)
            setOnClickListener {
                scheduleCollapse()
                onClick()
            }
        }

        parent.addView(
            v,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                topMargin = dp(8)
            }
        )

        return v
    }

    private fun applyBallStyle() {
        val b = ball ?: return

        b.background = circle(if (paused) grey else orange)
        pulse?.cancel()
        pulse = null
        b.scaleX = 1f
        b.scaleY = 1f

        if (!paused) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                b,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f)
            ).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun scheduleCollapse() {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, 3500)
    }

    private fun dragListener(): View.OnTouchListener {
        return object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var downX = 0f
            var downY = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = params ?: return false
                val r = root ?: return false

                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = p.x
                        startY = p.y
                        downX = e.rawX
                        downY = e.rawY
                        moved = false
                        handler.removeCallbacks(collapseRunnable)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY

                        if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                            moved = true
                        }

                        if (moved) {
                            p.x = startX + dx.toInt()
                            p.y = startY + dy.toInt()

                            try {
                                wm.updateViewLayout(r, p)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            expanded = !expanded
                            rebuild(true)
                        } else {
                            snapToEdge()
                            if (expanded) scheduleCollapse()
                        }
                    }
                }

                return true
            }
        }
    }

    private fun snapToEdge() {
        val p = params ?: return
        val r = root ?: return
        val dm = context.resources.displayMetrics
        val width = if (r.width > 0) r.width else dp(54)
        val height = if (r.height > 0) r.height else dp(54)

        p.x = if (p.x + width / 2 < dm.widthPixels / 2) {
            dp(6)
        } else {
            dm.widthPixels - width - dp(6)
        }

        p.y = p.y.coerceIn(dp(24), (dm.heightPixels - height - dp(24)).coerceAtLeast(dp(24)))

        try {
            wm.updateViewLayout(r, p)
        } catch (_: Exception) {
        }
    }

    private fun circle(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
