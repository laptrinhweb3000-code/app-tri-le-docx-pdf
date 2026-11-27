package com.doccast.presenter.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * High-precision pinch-to-zoom RecyclerView (1x..5x) with accurate 2D focal-point tracking,
 * damped multi-touch scaling, double-tap zoom around tap point, and smooth 1:1 pan + vertical scroll.
 */
class ZoomableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var scaleFactor = 1f
    private val minScale = 1f
    private val maxScale = 5f
    private var panX = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val rawFactor = detector.scaleFactor
                if (!rawFactor.isFinite() || rawFactor <= 0f) return false

                // Dampen micro-jitter spikes from multi-touch digitizers
                val dampedFactor = rawFactor.coerceIn(0.86f, 1.16f)
                val prevScale = scaleFactor
                val nextScale = (prevScale * dampedFactor).coerceIn(minScale, maxScale)
                val actualRatio = nextScale / prevScale
                if (actualRatio == 1f) return true

                scaleFactor = nextScale

                // Lock horizontal focal point accurately under user's fingers
                val fx = detector.focusX.coerceIn(0f, width.toFloat())
                panX = fx - (fx - panX) * actualRatio
                clampPan()

                // Lock vertical focal point by adjusting RecyclerView scroll proportionally
                val fy = detector.focusY.coerceIn(0f, height.toFloat())
                val dy = (fy * (actualRatio - 1f)).roundToInt()
                if (dy != 0) {
                    scrollBy(0, dy)
                }

                invalidate()
                return true
            }
        }
    ).apply {
        isQuickScaleEnabled = false
    }

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.08f) {
                    resetZoom()
                } else {
                    val target = 2.4f
                    val ratio = target / scaleFactor
                    scaleFactor = target
                    panX = e.x - (e.x - panX) * ratio
                    clampPan()
                    val dy = (e.y * (ratio - 1f)).roundToInt()
                    if (dy != 0) scrollBy(0, dy)
                    invalidate()
                }
                return true
            }
        }
    )

    fun isZoomed(): Boolean = scaleFactor > 1.05f

    fun resetZoom() {
        scaleFactor = 1f
        panX = 0f
        invalidate()
    }

    private fun clampPan() {
        if (width <= 0) return
        val maxOverflow = (width * (scaleFactor - 1f)).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxOverflow, 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        doubleTapDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress || ev.pointerCount > 1) {
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        doubleTapDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = ev.getPointerId(0)
                lastTouchX = ev.x
                lastTouchY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && ev.pointerCount == 1 && scaleFactor > 1.05f) {
                    val idx = ev.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val curX = ev.getX(idx)
                        val curY = ev.getY(idx)
                        val dx = curX - lastTouchX
                        panX += dx
                        clampPan()
                        lastTouchX = curX
                        lastTouchY = curY
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Prevent jump when second finger touches down
                val idx = ev.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    lastTouchX = ev.getX(idx)
                    lastTouchY = ev.getY(idx)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = ev.actionIndex
                val pointerId = ev.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < ev.pointerCount) {
                        activePointerId = ev.getPointerId(newIndex)
                        lastTouchX = ev.getX(newIndex)
                        lastTouchY = ev.getY(newIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }

        if (scaleDetector.isInProgress || ev.pointerCount > 1) {
            return true
        }
        return super.onTouchEvent(ev)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (scaleFactor <= 1.001f) {
            super.dispatchDraw(canvas)
            return
        }
        canvas.save()
        canvas.translate(panX, 0f)
        canvas.scale(scaleFactor, scaleFactor)
        super.dispatchDraw(canvas)
        canvas.restore()
    }
}
