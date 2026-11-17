package godau.fynn.usagedirectplus.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import godau.fynn.usagedirectplus.R
import godau.fynn.usagedirectplus.persistence.UsageInterval

/**
 * A single 24 hour strip for one day: the full day as a track with every
 * foreground session drawn as a filled segment. Reads visually faster than a
 * long list of session rows and needs no scrolling or gesture handling.
 */
class UsageDayStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_stroke)
        style = Paint.Style.FILL
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_text_secondary)
        textSize = 9f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()
    private val segmentRect = RectF()
    private var dayStart = 0L
    private var dayEnd = 1L
    private val segments = ArrayList<FloatArray>()

    fun setDay(startMillis: Long, endMillis: Long, intervals: List<UsageInterval>) {
        dayStart = startMillis
        dayEnd = maxOf(endMillis, startMillis + 1L)
        segments.clear()
        for (interval in intervals) {
            val from = (maxOf(interval.beginTime, dayStart) - dayStart).toFloat() / (dayEnd - dayStart)
            val to = (minOf(interval.endTime, dayEnd) - dayStart).toFloat() / (dayEnd - dayStart)
            if (to > from) {
                segments.add(floatArrayOf(from, to))
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val barHeight = 10f * resources.displayMetrics.density
        val barTop = barHeight * 0.6f
        val labelBaseline = barTop + barHeight + 14f * resources.displayMetrics.density
        val horizontalInset = 2f * resources.displayMetrics.density

        trackRect.set(horizontalInset, barTop, width - horizontalInset, barTop + barHeight)
        canvas.drawRoundRect(trackRect, barHeight / 2f, barHeight / 2f, trackPaint)

        val usableWidth = trackRect.width()
        for (segment in segments) {
            segmentRect.set(
                trackRect.left + segment[0] * usableWidth,
                barTop,
                trackRect.left + segment[1] * usableWidth,
                barTop + barHeight
            )
            canvas.drawRoundRect(segmentRect, barHeight / 2f, barHeight / 2f, segmentPaint)
        }

        val labels = arrayOf("00", "06", "12", "18", "24")
        val step = usableWidth / 4f
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(labels[0], horizontalInset, labelBaseline, labelPaint)
        labelPaint.textAlign = Paint.Align.CENTER
        for (index in 1..3) {
            canvas.drawText(labels[index], trackRect.left + step * index, labelBaseline, labelPaint)
        }
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(labels[4], width - horizontalInset, labelBaseline, labelPaint)
    }
}
