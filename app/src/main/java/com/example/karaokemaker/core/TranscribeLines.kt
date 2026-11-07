package com.example.karaokemaker.core

/**
 * Port của segments_to_lines() + _synth_words() trong karaoke/align.py (bản PC):
 * cắt các đoạn AI chép thành câu karaoke (mỗi đoạn một câu; đoạn dài ngắt ở
 * dấu câu), kèm giờ thật từng từ cho hiệu ứng chữ chạy.
 */
object TranscribeLines {

    private val PUNCT_END = setOf(',', '.', '?', '!', '…', ':', ';')
    private const val MAX_WORDS_PER_LINE = 12

    /**
     * Đoạn không có giờ từng từ (đường dự phòng khi engine không trả timestamp
     * từng token): chia đều khoảng hát của đoạn cho từng từ để chữ vẫn quét
     * theo nhịp gần đúng thay vì đứng im. — port của _synth_words().
     */
    fun synthWords(segments: List<HeardSegment>): List<HeardSegment> {
        return segments.map { seg ->
            if (seg.words.isNotEmpty()) return@map seg
            val words = seg.text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return@map seg.copy(words = emptyList())
            var t1 = seg.end
            val t0 = seg.start
            if (t1 <= t0) t1 = t0 + 0.3 * words.size
            val step = (t1 - t0) / words.size
            seg.copy(words = words.mapIndexed { i, w ->
                HeardWord(w, t0 + i * step, t0 + (i + 1) * step)
            })
        }
    }

    fun segmentsToLines(segments: List<HeardSegment>): List<TimedLine> {
        val lines = ArrayList<TimedLine>()
        for (seg in segments) {
            val words = seg.words
            val text = seg.text.trim()
            if (words.isEmpty() || text.isEmpty()) continue
            if ((text.startsWith("[") && text.endsWith("]")) ||
                (text.startsWith("(") && text.endsWith(")"))
            ) continue // [âm nhạc], (tiếng vỗ tay)... không phải lời hát
            if (Lyrics.isJunkLine(text)) continue // lời kêu gọi của kênh

            val chunks = ArrayList<MutableList<HeardWord>>()
            var cur = ArrayList<HeardWord>()
            for (wd in words) {
                cur.add(wd)
                val endsSentence = wd.w.trimEnd().lastOrNull() in PUNCT_END
                if (cur.size >= MAX_WORDS_PER_LINE || (endsSentence && cur.size >= 6)) {
                    chunks.add(cur)
                    cur = ArrayList()
                }
            }
            if (cur.isNotEmpty()) {
                if (chunks.isNotEmpty() && cur.size <= 3) {
                    chunks.last().addAll(cur) // đuôi quá ngắn: gộp vào câu trước
                } else {
                    chunks.add(cur)
                }
            }
            for (ch in chunks) {
                var txt = ch.joinToString(" ") { it.w }.trim()
                txt = txt.replace(Regex("""\s+([,.?!…:;])"""), "$1")
                if (txt.isEmpty()) continue
                lines.add(
                    TimedLine(
                        start = round2(ch.first().start),
                        end = round2(ch.last().end),
                        text = txt,
                        words = ch.map { WordTime(round2(it.start), round2(it.end)) },
                        matched = true
                    )
                )
            }
        }
        lines.sortBy { it.start }
        return lines
    }

    private fun round2(x: Double): Double = Math.round(x * 100) / 100.0
}
