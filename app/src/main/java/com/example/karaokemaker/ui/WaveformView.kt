package com.example.karaokemaker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.karaokemaker.R
import kotlin.math.max

/**
 * Waveform kiểu khung KaraStudio: cột sóng xám tím, phần đã phát tô hồng,
 * đầu phát là vạch trắng/hồng, trên đầu các cột có chip mốc đoạn (D1, D2...)
 * tại vị trí bắt đầu từng câu lời. Chạm vào để tua (seek).
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D3D5C")
        style = Paint.Style.FILL
    }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A4B7A")
        style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_pink)
        style = Paint.Style.FILL
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E4636")
        style = Paint.Style.FILL
    }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#10101B")
        style = Paint.Style.FILL
    }

    var peaks: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    /** Phân số vị trí phát 0..1 theo mốc bắt đầu từng câu. */
    var lineMarks: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var durationSec: Double = 0.0
    var positionSec: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }

    var onSeek: ((Double) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 16f, 16f, bgPaint)
        if (peaks.isEmpty() || durationSec <= 0) {
            chipTextPaint.color = Color.parseColor("#62627A")
            canvas.drawText("Chưa có sóng nhạc", w / 2, h / 2 + 8, chipTextPaint)
            chipTextPaint.color = Color.parseColor("#34D399")
            return
        }
        val chipH = 26f
        val waveTop = chipH + 6f
        val waveH = h - waveTop - 4f
        val mid = waveTop + waveH / 2
        val n = peaks.size
        val barW = w / n
        val playedFrac = (positionSec / durationSec).coerceIn(0.0, 1.0)
        for (i in 0 until n) {
            val amp = max(0.04f, peaks[i]) * (waveH / 2)
            val x = i * barW
            val frac = i.toDouble() / n
            val paint = if (frac <= playedFrac) playedPaint else barPaint
            canvas.drawRoundRect(
                x + barW * 0.22f, mid - amp, x + barW * 0.78f, mid + amp, 3f, 3f, paint
            )
        }
        // chip mốc đoạn D1.. tại đầu mỗi câu
        lineMarks.forEachIndexed { idx, frac ->
            if (frac < 0 || frac > 1) return@forEachIndexed
            val cx = (frac * w).toFloat()
            val label = "D${idx + 1}"
            val tw = chipTextPaint.measureText(label) + 14f
            val left = (cx - tw / 2).coerceIn(0f, w - tw)
            canvas.drawRoundRect(left, 2f, left + tw, 2f + chipH, 8f, 8f, chipPaint)
            canvas.drawText(label, left + tw / 2, 2f + chipH - 7f, chipTextPaint)
        }
        // vạch vị trí phát
        val cx = (playedFrac * w).toFloat()
        canvas.drawRect(cx - 1.5f, waveTop - 2f, cx + 1.5f, h - 2f, cursorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            if (durationSec > 0) {
                val frac = (event.x / width).coerceIn(0f, 1f)
                onSeek?.invoke(frac * durationSec)
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
