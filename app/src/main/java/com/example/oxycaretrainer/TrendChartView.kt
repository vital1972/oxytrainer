package com.example.oxycaretrainer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Линейный график значения по сеансам (или неделям) с подписью диапазона. */
class TrendChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var values: List<Double> = emptyList(); set(v) { field = v; invalidate() }
    var lineColor: Int = Color.rgb(33, 150, 243); set(v) { field = v; invalidate() }
    var asBars: Boolean = false; set(v) { field = v; invalidate() }
    /** Если задано, рисуется горизонтальная пунктирная линия-ориентир. */
    var reference: Double? = null; set(v) { field = v; invalidate() }
    var unit: String = ""; set(v) { field = v; invalidate() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 28f }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY; strokeWidth = 2f; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    override fun onMeasure(w: Int, h: Int) {
        val height = (160 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), resolveSize(height, h))
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val left = 90f; val right = width - 24f; val top = 24f; val bottom = height - 30f
        if (values.isEmpty()) { c.drawText("Нет данных", left, height / 2f, text); return }
        var lo = values.minOrNull()!!; var hi = values.maxOrNull()!!
        reference?.let { lo = minOf(lo, it); hi = maxOf(hi, it) }
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        if (asBars) lo = minOf(lo, 0.0)
        val pad = (hi - lo) * 0.08; lo -= if (asBars) 0.0 else pad; hi += pad
        fun y(v: Double) = (bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top))
        fun x(i: Int) = if (values.size == 1) (left + right) / 2 else left + i * (right - left) / (values.size - 1)
        c.drawText(fmt(hi), 4f, top + 10f, text); c.drawText(fmt(lo), 4f, bottom, text)
        reference?.let { c.drawLine(left, y(it), right, y(it), dash) }
        line.color = lineColor; fill.color = lineColor
        if (asBars) {
            val slot = (right - left) / values.size; val bw = slot * 0.6f
            values.forEachIndexed { i, v -> val cx = left + slot * (i + 0.5f); c.drawRect(cx - bw / 2, y(v), cx + bw / 2, y(0.0.coerceAtLeast(lo)), fill) }
        } else {
            val p = Path()
            values.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
            c.drawPath(p, line)
            values.forEachIndexed { i, v -> c.drawCircle(x(i), y(v), 7f, fill) }
        }
        c.drawText("${fmt(values.last())}$unit", right - 150f, height - 4f, text)
    }

    private fun fmt(v: Double) = if (Math.abs(v - Math.round(v)) < 0.05) "${Math.round(v)}" else "%.1f".format(v)
}
