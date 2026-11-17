package godau.fynn.usagedirectplus.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import godau.fynn.usagedirectplus.R

/**
 * Minimal bar chart drawn directly on a canvas. No grid lines, no decoration:
 * a row of bars, the tallest one highlighted, small labels underneath.
 */
class RecapBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.primary)
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.glass_text_secondary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private var values: List<Long> = emptyList()
    private var labels: List<String> = emptyList()

    private val density = resources.displayMetrics.density
    private val labelSpace = 20f * density

    fun setData(values: List<Long>, labels: List<String>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (values.isEmpty()) return

        val usableHeight = height - labelSpace
        if (usableHeight <= 0f) return

        val maxValue = values.maxOrNull() ?: 0L
        val peakIndex = if (maxValue > 0L) values.indexOf(maxValue) else -1

        val slot = width.toFloat() / values.size
        val barWidth = minOf(slot * 0.58f, 22f * density)
        val radius = 4f * density

        for (index in values.indices) {
            val value = values[index]
            val centerX = slot * index + slot / 2f

            if (value > 0L && maxValue > 0L) {
                val barHeight = maxOf(3f * density, usableHeight * (value.toFloat() / maxValue.toFloat()))
                val left = centerX - barWidth / 2f
                val right = centerX + barWidth / 2f
                val top = usableHeight - barHeight
                val paint = if (index == peakIndex) peakPaint else barPaint
                canvas.drawRoundRect(RectF(left, top, right, usableHeight), radius, radius, paint)
            }

            val label = labels.getOrNull(index).orEmpty()
            if (label.isNotEmpty()) {
                canvas.drawText(label, centerX, height - 6f * density, labelPaint)
            }
        }
    }
}
