package com.example.karaokemaker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.example.karaokemaker.core.Gradients
import com.example.karaokemaker.core.StyleOptions
import com.example.karaokemaker.core.TimedLine
import java.io.File

/**
 * Khung XEM TRƯỚC tĩnh của Studio/Xuất (theo khung KaraStudio): nền gradient
 * 3 màu của mẫu đã chọn (hoặc ảnh/video nền), badge tỉ lệ + đồng hồ, chữ
 * karaoke 2 dòng ở đáy — phần đã hát tô màu highlight theo tiến độ phát,
 * dòng kế mờ bên dưới, thanh tiến trình trên cùng và chấm đếm ngược khi bật.
 * Vẽ bằng Canvas thuần, cập nhật theo vị trí phát của MediaPlayer.
 */
class KaraokePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var style: StyleOptions = StyleOptions()
    var bgKind: String = "demtim"          // mẫu gradient khi bg type = gradient
    var bgColorHex: String = "#0b0e1a"
    var bgBitmap: Bitmap? = null           // ảnh nền (type image) nếu nạp được
    var lines: List<TimedLine> = emptyList()
    var durationSec: Double = 0.0
    var positionSec: Double = 0.0
        set(value) {
            field = value
            invalidate()
        }
    var ratioLabel: String = "16:9"

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9C9CB2")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun setBgImageFile(file: File?) {
        bgBitmap = if (file != null && file.exists()) {
            try {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // --- nền ---
        val bmp = bgBitmap
        if (bmp != null) {
            val scale = maxOf(w / bmp.width, h / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            canvas.drawBitmap(bmp, (w - dw) / 2, (h - dh) / 2, null)
            canvas.drawColor(Color.parseColor("#33000000"))
        } else if (bgKind == "color") {
            canvas.drawColor(parseColorSafe(bgColorHex, Color.parseColor("#0b0e1a")))
        } else {
            val cols = Gradients.colorsOf(bgKind).map { parseColorSafe(it.replace("0x", "#"), Color.DKGRAY) }
            val shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(cols[0], cols[1], cols[2]),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val p = Paint().apply { this.shader = shader }
            canvas.drawRect(0f, 0f, w, h, p)
        }
        // bo góc nội dung theo thẻ (vẽ viền tối nhẹ ở đáy cho chữ dễ đọc)
        val shade = Paint().apply {
            shader = LinearGradient(
                0f, h * 0.45f, 0f, h,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#99000000")),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, h * 0.45f, w, h, shade)

        // --- thanh tiến trình ---
        if (style.progressBar && durationSec > 0) {
            val frac = (positionSec / durationSec).coerceIn(0.0, 1.0).toFloat()
            val barPaint = Paint().apply { color = parseColorSafe("#FFD400", Color.YELLOW) }
            canvas.drawRect(0f, 0f, w * frac, 8f, barPaint)
        }

        // --- badge ---
        val badge = "XEM TRƯỚC · $ratioLabel"
        val bw = badgeText.measureText(badge) + 28f
        canvas.drawRoundRect(RectF(14f, 14f, 14f + bw, 52f), 10f, 10f, badgePaint)
        canvas.drawText(badge, 28f, 42f, badgeText)
        val clock = "${fmtTime(positionSec)} / ${fmtTime(durationSec)}"
        val cw = badgeText.measureText(clock) + 28f
        canvas.drawRoundRect(RectF(w - 14f - cw, 14f, w - 14f, 52f), 10f, 10f, badgePaint)
        canvas.drawText(clock, w - cw, 42f, badgeText)

        // --- chữ karaoke ---
        val idx = currentLineIndex()
        val line = lines.getOrNull(idx)
        val next = lines.getOrNull(idx + 1)
        val textSizePx = (style.size.toFloat() / 1080f) * h * 1.9f
        textPaint.textSize = textSizePx.coerceIn(24f, 120f)
        val baseY = h - 64f
        if (line != null) {
            drawKaraokeLine(canvas, line, w, baseY)
        } else {
            // chưa có lời: hiện câu mẫu như khung thiết kế
            textPaint.color = parseColorSafe(style.color, Color.YELLOW)
            val sample = "Ngày mai em đi biển nhớ tên em gọi về"
            drawCentered(canvas, sample, w, baseY, textPaint)
        }
        if (style.twoLine && next != null) {
            dimPaint.textSize = textPaint.textSize * 0.62f
            drawCentered(canvas, next.text, w, baseY + textPaint.textSize * 0.85f, dimPaint)
        }
        // --- chấm đếm ngược ---
        if (style.countdown && line != null && idx >= 0) {
            val prevEnd = if (idx > 0) lines[idx - 1].end else 0.0
            val gap = line.start - prevEnd
            val remain = line.start - positionSec
            if (gap >= 1.6 && remain in 0.0..1.5) {
                val dots = when {
                    remain > 1.0 -> "●"
                    remain > 0.5 -> "● ●"
                    else -> "● ● ●"
                }
                val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    // this@KaraokePreviewView: trong apply của Paint, `style`
                    // sẽ trỏ Paint.style (Paint.Style) chứ không phải style chữ
                    color = parseColorSafe(this@KaraokePreviewView.style.color, Color.YELLOW)
                    textSize = 34f
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                }
                canvas.drawText(dots, w / 2, baseY - textPaint.textSize * 1.5f, dp)
            }
        }
    }

    private fun currentLineIndex(): Int {
        if (lines.isEmpty()) return -1
        var idx = -1
        for ((i, ln) in lines.withIndex()) {
            if (positionSec >= ln.start) idx = i else break
        }
        return if (idx >= 0) idx else 0
    }

    /** Vẽ 1 dòng: từng từ, phần đã hát tô màu highlight theo giờ từng từ
     *  (không có giờ từng từ thì tô theo tỉ lệ thời gian của cả dòng). */
    private fun drawKaraokeLine(canvas: Canvas, line: TimedLine, w: Float, baseline: Float) {
        val words = line.text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        val sungColor = parseColorSafe(style.color, Color.YELLOW)
        val baseColor = parseColorSafe(style.baseColor, Color.WHITE)
        val spaceW = textPaint.measureText(" ")
        val widths = words.map { textPaint.measureText(it) }
        val total = widths.sum() + spaceW * (words.size - 1)
        var x = (w - total) / 2
        if (x < 8f) x = 8f
        val lineDur = maxOf(0.05, line.end - line.start)
        val fracLine = ((positionSec - line.start) / lineDur).coerceIn(0.0, 1.0)
        for ((i, word) in words.withIndex()) {
            val sung = when {
                positionSec >= line.end -> true
                positionSec < line.start -> false
                line.words.size == words.size -> positionSec >= line.words[i].start
                else -> {
                    val wordFracStart = widths.take(i).sum() / maxOf(1f, total)
                    fracLine >= wordFracStart
                }
            }
            textPaint.color = if (sung) sungColor else baseColor
            textPaint.setShadowLayer(6f, 0f, 2f, Color.BLACK)
            canvas.drawText(word, x, baseline, textPaint)
            textPaint.clearShadowLayer()
            x += widths[i] + spaceW
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, w: Float, baseline: Float, paint: Paint) {
        val tw = paint.measureText(text)
        paint.setShadowLayer(6f, 0f, 2f, Color.BLACK)
        canvas.drawText(text, (w - tw) / 2, baseline, paint)
        paint.clearShadowLayer()
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        fallback
    }

    private fun fmtTime(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
