package com.doccast.presenter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Lightweight pinch-to-zoom, pan, and double-tap-to-zoom ImageView for PDF pages.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val imgMatrix = Matrix()
    private val lastTouch = PointF()

    private var currentScale = 1f
    private val minScale = 1f
    private val maxScale = 4.5f
    private var isDragging = false

    private var baseScale = 1f
    private var origWidth = 0f
    private var origHeight = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var factor = detector.scaleFactor
                val target = currentScale * factor
                if (target > maxScale) {
                    factor = maxScale / currentScale
                    currentScale = maxScale
                } else if (target < minScale) {
                    factor = minScale / currentScale
                    currentScale = minScale
                } else {
                    currentScale = target
                }
                imgMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                fixTranslation()
                imageMatrix = imgMatrix
                return true
            }
        }
    )

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.05f) {
                    resetZoom()
                } else {
                    val target = 2.2f
                    val factor = target / currentScale
                    currentScale = target
                    imgMatrix.postScale(factor, factor, e.x, e.y)
                    fixTranslation()
                    imageMatrix = imgMatrix
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    fun isZoomed(): Boolean = currentScale > 1.05f

    fun resetZoom() {
        currentScale = 1f
        fitImageToView()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        currentScale = 1f
        post { fitImageToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImageToView()
    }

    private fun fitImageToView() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val dw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val dh = d.intrinsicHeight.toFloat().coerceAtLeast(1f)

        baseScale = minOf(vw / dw, vh / dh)
        origWidth = dw * baseScale
        origHeight = dh * baseScale

        val dx = (vw - origWidth) / 2f
        val dy = (vh - origHeight) / 2f

        imgMatrix.reset()
        imgMatrix.postScale(baseScale, baseScale)
        imgMatrix.postTranslate(dx, dy)
        currentScale = 1f
        imageMatrix = imgMatrix
    }

    private fun fixTranslation() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        imgMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val scaledW = origWidth * currentScale
        val scaledH = origHeight * currentScale

        val fixX = computeFix(transX, vw, scaledW)
        val fixY = computeFix(transY, vh, scaledH)
        if (fixX != 0f || fixY != 0f) {
            imgMatrix.postTranslate(fixX, fixY)
        }
    }

    private fun computeFix(trans: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) {
            (viewSize - contentSize) / 2f - trans
        } else {
            val minTrans = viewSize - contentSize
            when {
                trans < minTrans -> minTrans - trans
                trans > 0f -> -trans
                else -> 0f
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        doubleTapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
                isDragging = currentScale > 1.05f
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress && event.pointerCount == 1 && currentScale > 1.05f) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    imgMatrix.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = imgMatrix
                    lastTouch.set(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }
}
