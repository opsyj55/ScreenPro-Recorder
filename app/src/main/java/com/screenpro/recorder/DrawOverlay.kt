package com.screenpro.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen pen overlay so you can draw on top of whatever you're recording. */
class DrawOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val overlayType: Int,
    private val onClosed: () -> Unit
) {
    private var root: FrameLayout? = null
    private var drawView: DrawView? = null
    private var toolbar: LinearLayout? = null
    private val dots = mutableListOf<Pair<View, Int>>()

    private val palette = intArrayOf(
        Color.parseColor("#FF3B47"),
        Color.parseColor("#FFD60A"),
        Color.parseColor("#30D158"),
        Color.parseColor("#0A84FF"),
        Color.WHITE
    )

    val isShowing: Boolean
        get() = root != null

    fun show() {
        if (root != null) return

        val r = FrameLayout(context)
        val dv = DrawView(context)
        r.addView(
            dv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val bar = buildToolbar(dv)
        r.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(96)
            }
        )

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(r, p)
            root = r
            drawView = dv
            toolbar = bar
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }

        root = null
        drawView = null
        toolbar = null
        dots.clear()
    }

    fun setToolbarVisible(visible: Boolean) {
        toolbar?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun buildToolbar(dv: DrawView): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE151B2B"))
                cornerRadius = dp(28).toFloat()
            }
        }

        for (color in palette) {
            val dot = View(context)

            dot.setOnClickListener {
                dv.color = color
                refreshDots(dv)
            }

            bar.addView(
                dot,
                LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    marginEnd = dp(8)
                }
            )

            dots.add(Pair(dot, color))
        }

        refreshDots(dv)

        bar.addView(textButton("↶") { dv.undo() })
        bar.addView(textButton("🗑") { dv.clear() })

        bar.addView(
            textButton("✕ Done") {
                hide()
                onClosed()
            }
        )

        return bar
    }

    private fun refreshDots(dv: DrawView) {
        for ((view, color) in dots) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)

                if (color == dv.color) {
                    setStroke(dp(3), Color.parseColor("#FF8A00"))
                } else {
                    setStroke(dp(1), Color.parseColor("#55FFFFFF"))
                }
            }
        }
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private class Stroke(val path: Path, val paint: Paint)

    private inner class DrawView(ctx: Context) : View(ctx) {
        var color: Int = palette[0]

        private val strokes = mutableListOf<Stroke>()
        private var current: Path? = null
        private var lastX = 0f
        private var lastY = 0f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val path = Path().apply { moveTo(e.x, e.y) }
                    current = path
                    lastX = e.x
                    lastY = e.y

                    strokes.add(
                        Stroke(
                            path,
                            Paint().apply {
                                this.color = this@DrawView.color
                                style = Paint.Style.STROKE
                                strokeWidth = dp(5).toFloat()
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                                isAntiAlias = true
                            }
                        )
                    )
                }

                MotionEvent.ACTION_MOVE -> {
                    current?.quadTo(
                        lastX,
                        lastY,
                        (e.x + lastX) / 2,
                        (e.y + lastY) / 2
                    )
                    lastX = e.x
                    lastY = e.y
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    current?.lineTo(e.x, e.y)
                    current = null
                }
            }

            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            for (s in strokes) canvas.drawPath(s.path, s.paint)
        }

        fun undo() {
            if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
            invalidate()
        }

        fun clear() {
            strokes.clear()
            invalidate()
        }
    }
}
