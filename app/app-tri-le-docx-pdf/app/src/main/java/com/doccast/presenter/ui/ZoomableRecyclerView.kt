package com.doccast.presenter.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView with smooth 2-finger pinch-to-zoom (1x..4x), double-tap zoom,
 * and horizontal pan while keeping native vertical scrolling across all PDF pages.
 */
class ZoomableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var scaleFactor = 1f
    private val minScale = 1f
    private val maxScale = 4f
    private var panX = 0f
    private var lastTouchX = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
                val ratio = scaleFactor / prevScale
                // Keep horizontal focus centered under fingers
                panX = detector.focusX - (detector.focusX - panX) * ratio
                clampPan()
                invalidate()
                return true
            }
        }
    )

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.05f) {
                    resetZoom()
                } else {
                    scaleFactor = 2.2f
                    panX = e.x - e.x * scaleFactor
                    clampPan()
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
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && scaleFactor > 1.05f) {
                    val idx = ev.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val curX = ev.getX(idx)
                        val dx = curX - lastTouchX
                        panX += dx
                        clampPan()
                        lastTouchX = curX
                        invalidate()
                    }
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
