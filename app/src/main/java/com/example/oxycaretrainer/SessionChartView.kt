package com.example.oxycaretrainer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SessionChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var samples: List<TrainingSample> = emptyList(); set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 30f }
    override fun onDraw(c: Canvas) {
        super.onDraw(c); c.drawColor(Color.WHITE)
        val left = 70f; val top = 25f; val right = width - 20f; val bottom = height - 45f
        c.drawText("SpO₂", 5f, 45f, textPaint); c.drawText("Пульс", 5f, 80f, textPaint)
        if (samples.size < 2) { c.drawText("Недостаточно данных", left, height / 2f, textPaint); return }
        val maxT = samples.last().elapsedSeconds.coerceAtLeast(1).toFloat()
        fun x(s: TrainingSample) = left + s.elapsedSeconds / maxT * (right - left)
        fun draw(values: (TrainingSample) -> Int, min: Int, max: Int, color: Int) {
            paint.color = color
            val path = android.graphics.Path()
            samples.forEachIndexed { i, s ->
                val y = bottom - ((values(s) - min).toFloat() / (max - min).coerceAtLeast(1)) * (bottom - top)
                if (i == 0) path.moveTo(x(s), y) else path.lineTo(x(s), y)
            }; c.drawPath(path, paint)
        }
        draw({ it.spo2 }, 50, 100, Color.rgb(33, 150, 243))
        val minP = samples.minOf { it.pulse }.coerceAtMost(60); val maxP = samples.maxOf { it.pulse }.coerceAtLeast(120)
        draw({ it.pulse }, minP, maxP, Color.rgb(244, 67, 54))
        c.drawText("0", left, height - 8f, textPaint); c.drawText("${maxT.toInt()} сек", right - 110f, height - 8f, textPaint)
    }
}
