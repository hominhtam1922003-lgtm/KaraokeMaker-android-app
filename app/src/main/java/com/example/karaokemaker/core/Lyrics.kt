package com.example.karaokemaker.core

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port TRUNG THỰC của karaoke/lyrics.py (bản PC): parse LRC, lọc dòng rác,
 * dựng file ASS karaoke với thẻ \k/\kf, chốt giờ kết thúc từng câu.
 * Mọi hằng số và thuật toán giữ nguyên; chỉ khác kiểu dữ liệu (dict -> data class)
 * và thêm 2 mở rộng có ghi chú rõ (giọng từng dòng + cờ matched cho UI Studio).
 */
object Lyrics {

    // ---------------- parse LRC ----------------

    private val LRC_TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""\[(ar|ti|al|by|offset|re|ve):""", RegexOption.IGNORE_CASE)
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    /** Câu LRC thô: chỉ mới có giờ bắt đầu. */
    data class LrcLine(val start: Double, val text: String)

    fun parseLrc(text: String): List<LrcLine> {
        val mo = OFFSET_TAG.find(text)
        val shift = if (mo != null) mo.groupValues[1].toInt() / 1000.0 else 0.0
        val out = ArrayList<LrcLine>()
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            val line = raw.trim()
            if (line.isEmpty() || META_TAG.containsMatchIn(line.take(12))) continue
            val tags = LRC_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            val lyric = LRC_TAG.replace(line, "").trim()
            if (lyric.isEmpty()) continue
            for (m in tags) {
                val mm = m.groupValues[1].toInt()
                val ss = m.groupValues[2].toInt()
                val fracStr = m.groupValues[3]
                val frac = if (fracStr.isNotEmpty() && fracStr.length <= 3) "0.$fracStr".toDouble() else 0.0
                val start = max(0.0, mm * 60 + ss + frac + shift)
                out.add(LrcLine(start, lyric))
            }
        }
        out.sortBy { it.start }
        return out
    }

    // ---------------- lọc dòng rác ----------------

    private val SECTION = Regex(
        """(lyrics?|verse|chorus|hook|bridge|intro|outro|pre[-\s]?chorus|post[-\s]?chorus|refrain|điệp khúc|lời|phần|đoạn)\b""",
        RegexOption.IGNORE_CASE
    )
    private val SEPARATOR = Regex("""^[-=*_~.·•\s]+$""")
    private val BRACKETED = Regex("""^[\[(].{0,40}[\])]$""")

    /** Một dòng lời dán vào -> câu hát sạch, hoặc null nếu là dòng rác. */
    fun cleanLine(line: String?): String? {
        var s = (line ?: "").trim()
        if (s.isEmpty() || SEPARATOR.matches(s)) return null
        if (BRACKETED.matches(s) && s.split(Regex("""\s+""")).filter { it.isNotEmpty() }.size <= 6) return null
        if (s.endsWith(":") && s.length <= 30 && SECTION.containsMatchIn(s)) return null
        if (s.startsWith("--:--")) {
            val rest = s.substring(5).trim()
            if (rest.isEmpty() || SECTION.containsMatchIn(rest) ||
                (rest.length <= 30 && rest.endsWith(":"))
            ) return null
            s = rest
        }
        return s.ifEmpty { null }
    }

    private val JUNK_PATTERNS = listOf(
        Regex("""subscribe""", RegexOption.IGNORE_CASE),
        Regex("""đăng\s*ký\s*kênh|nhấn\s*(chuông|đăng\s*ký)|bấm\s*(chuông|đăng\s*ký)""", RegexOption.IGNORE_CASE),
        Regex("""để\s*không\s*bỏ\s*lỡ|không\s*bỏ\s*lỡ\s*.*video""", RegexOption.IGNORE_CASE),
        Regex("""like\s*(và|&|and)\s*share|ủng\s*hộ\s*kênh""", RegexOption.IGNORE_CASE),
        Regex("""cảm\s*ơn\s*(các\s*bạn|mọi\s*người|quý\s*vị)\s*đã""", RegexOption.IGNORE_CASE),
        Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)
    )

    /**
     * True nếu dòng KHÔNG phải lời hát mà là lời kêu gọi của kênh/người đăng
     * (subscribe, nhấn chuông, cảm ơn đã xem...). Mẫu bắt chặt theo ngữ cảnh
     * kênh để tránh giết nhầm lời hát thật — giữ nguyên bản PC.
     */
    fun isJunkLine(text: String?): Boolean {
        val t = (text ?: "").trim()
        if (t.isEmpty()) return true
        return JUNK_PATTERNS.any { it.containsMatchIn(t) }
    }

    /** Lời dán tay: mỗi dòng một câu, bỏ dòng trống và dòng rác. */
    fun parsePlain(text: String): List<String> {
        val out = ArrayList<String>()
        for (ln in text.replace("\r\n", "\n").split("\n")) {
            val s = cleanLine(ln)
            if (s != null) out.add(s)
        }
        return out
    }

    /** Tách câu thành token theo khoảng trắng (tiếng Việt: mỗi token ~ 1 âm tiết). */
    fun splitWords(line: String): List<String> =
        line.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }

    // ---------------- dựng ASS ----------------

    private fun wordWeight(word: String): Int {
        // Trọng số theo số ký tự chữ/số, tối thiểu 1 — từ dài hát lâu hơn.
        val n = word.count { it.isLetterOrDigit() || it == '_' }
        return max(1, n)
    }

    fun assTime(sec: Double): String {
        val s0 = if (sec < 0) 0.0 else sec
        val h = (s0 / 3600).toInt()
        val m = ((s0 % 3600) / 60).toInt()
        val s = s0 % 60
        return String.format(Locale.US, "%d:%02d:%05.2f", h, m, s)
    }

    /** #RRGGBB -> &H00BBGGRR (màu ASS). Màu rác -> dùng fallback. */
    fun assColor(hexRgb: String?, fallback: String = "#FFD400"): String {
        var h = (hexRgb ?: "").removePrefix("#")
        if (!Regex("""[0-9A-Fa-f]{6}""").matches(h)) h = fallback.removePrefix("#")
        val r = h.substring(0, 2); val g = h.substring(2, 4); val b = h.substring(4, 6)
        return "&H00$b$g$r".uppercase()
    }

    private fun safeInt(value: Int, lo: Int, hi: Int): Int =
        max(lo, min(hi, value))

    /**
     * Dựng toàn văn file ASS. [title] = thẻ tên bài mở đầu (rỗng = không có).
     * [playX]/[playY] = PlayRes theo tỉ lệ khung render.
     */
    fun buildAss(
        timedLines: List<TimedLine>,
        style: StyleOptions,
        title: String = "",
        playX: Int = 1280,
        playY: Int = 720
    ): String {
        val sung = assColor(style.color)
        val base = assColor(style.baseColor, "#FFFFFF")
        val outline = assColor(style.outlineColor, "#000000")
        val size = safeInt(style.size, 10, 200)
        var font = style.font
        if (!Regex("""[\w\s\-]{1,40}""").matches(font)) font = "Roboto"
        val marginV = 70
        val align = 2
        val outlineW = 3
        var effect = style.effect
        if (effect !in listOf("sweep", "pop", "zoom", "type")) effect = "sweep"
        val tag = if (effect == "sweep") "kf" else "k"
        val fade = style.fade
        val twoLine = style.twoLine
        val countdown = style.countdown
        val duet = if (style.duet) assColor(style.duetColor) else null

        val sb = StringBuilder()
        sb.append("[V4+ Styles]\n")
        sb.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        sb.append("Style: Kara,$font,$size,$sung,$base,$outline,&H64000000,-1,0,0,0,100,100,0,0,1,$outlineW,1,$align,40,40,$marginV,1\n")
        if (twoLine) {
            val dim = "&H59" + base.substring(4) // màu câu kế: mờ ~65%
            sb.append("Style: KaraNext,$font,${max(20, (size * 0.72).toInt())},$dim,$dim,$outline,&H64000000,-1,0,0,0,100,100,0,0,1,2,1,$align,40,40,16,1\n")
        }
        if (title.isNotBlank()) {
            sb.append("Style: KaraTitle,$font,${(size * 1.25).toInt()},$sung,$base,$outline,&H64000000,-1,0,0,0,100,100,0,0,1,3,2,5,40,40,0,1\n")
        }
        if (countdown) {
            sb.append("Style: Dots,$font,${max(24, (size * 0.55).toInt())},$sung,$sung,$outline,&H64000000,-1,0,0,0,100,100,0,0,1,2,0,$align,40,40,${marginV + size + 56},1\n")
        }
        val styleFmt = sb.toString()

        val header = "[Script Info]\n" +
            "Title: Karaoke\n" +
            "ScriptType: v4.00+\n" +
            "PlayResX: $playX\n" +
            "PlayResY: $playY\n" +
            "WrapStyle: 0\n" +
            "ScaledBorderAndShadow: yes\n\n" +
            styleFmt + "\n" +
            "[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"

        fun safeText(t: String): String =
            t.replace("\\", "/").replace("{", "(").replace("}", ")")

        // Chuẩn hoá: bỏ câu không có chữ, câu có end <= start thì nới +0.5s.
        data class Norm(val start: Double, val end: Double, val text: String,
                        val words: List<String>, val wtimes: List<WordTime>?, val voice: Int)
        val norm = ArrayList<Norm>()
        for (ln in timedLines) {
            var end = ln.end
            if (end <= ln.start) end = ln.start + 0.5
            val words = splitWords(ln.text)
            if (words.isNotEmpty()) {
                norm.add(Norm(ln.start, end, ln.text.trim(), words,
                    if (ln.words.size == words.size && ln.words.isNotEmpty()) ln.words else null,
                    ln.voice))
            }
        }

        val events = ArrayList<String>()
        if (title.isNotBlank() && norm.isNotEmpty()) {
            val tEnd = if (norm[0].start > 1.5) min(4.0, norm[0].start) else 3.0
            events.add("Dialogue: 2,0:00:00.00,${assTime(tEnd)},KaraTitle,,0,0,0,,{\\fad(300,400)}${safeText(title.trim())}")
        }
        for ((idx, n) in norm.withIndex()) {
            val (start, end, _, words, wtimes) = n
            val totalCs = max(words.size, ((end - start) * 100).roundToInt())
            // slots: (từ, thời lượng cs, mốc bắt đầu cs tính từ đầu câu)
            val slots = ArrayList<Triple<String, Int, Int>>()
            var lead = 0
            if (wtimes != null) {
                val pts = ArrayList<Double>()
                for (wt in wtimes) {
                    var p = max(start, min(end, wt.start))
                    if (pts.isNotEmpty()) p = max(p, pts.last() + 0.01)
                    pts.add(p)
                }
                lead = if (pts[0] > start + 0.02) ((pts[0] - start) * 100).roundToInt() else 0
                lead = max(0, min(lead, totalCs - words.size))
                var used = lead
                for (i in words.indices) {
                    val dur = if (i == words.size - 1) max(1, totalCs - used)
                    else max(1, ((pts[i + 1] - pts[i]) * 100).roundToInt())
                    slots.add(Triple(words[i], dur, used))
                    used += dur
                }
            } else {
                val weights = words.map { wordWeight(it) }
                val wsum = max(1, weights.sum())
                var used = 0
                for (i in words.indices) {
                    val dur = if (i == words.size - 1) max(1, totalCs - used)
                    else max(1, (totalCs * weights[i].toDouble() / wsum).roundToInt())
                    slots.add(Triple(words[i], dur, used))
                    used += dur
                }
            }
            val parts = ArrayList<String>()
            if (lead > 0) parts.add("{\\k$lead}")
            for ((w, dur, off) in slots) {
                val sw = safeText(w)
                when {
                    effect == "zoom" -> {
                        val durMs = dur * 10; val offMs = off * 10
                        if (durMs >= 60) {
                            val up = max(1, min(120, durMs / 2))
                            parts.add(
                                "{\\k$dur" +
                                    "\\t($offMs,${offMs + up},\\fscx120\\fscy120)" +
                                    "\\t(${offMs + durMs - up},${offMs + durMs},\\fscx100\\fscy100)}" + sw
                            )
                        } else {
                            parts.add("{\\k$dur}$sw")
                        }
                    }
                    effect == "type" && sw.length > 1 -> {
                        val nch = sw.length
                        var prev = 0
                        val seg = StringBuilder()
                        for (j in sw.indices) {
                            val upto = (dur * (j + 1).toDouble() / nch).roundToInt()
                            seg.append("{\\k${upto - prev}}").append(sw[j])
                            prev = upto
                        }
                        parts.add(seg.toString())
                    }
                    else -> parts.add("{\\$tag$dur}$sw")
                }
            }
            var body = parts.joinToString(" ")
            if (fade) body = "{\\fad(120,180)}" + body
            // Song ca: câu lẻ tô màu người hát 2. Mở rộng bản Android: giọng
            // từng dòng (voice) ghi đè luật xen kẽ — 2 = Nam (màu 2), 1 = Nữ (màu chính).
            val lineIsDuet = when (n.voice) {
                2 -> duet != null
                1 -> false
                else -> duet != null && idx % 2 == 1
            }
            if (duet != null && lineIsDuet) {
                body = "{\\1c$duet}" + body
            }
            events.add("Dialogue: 1,${assTime(start)},${assTime(end)},Kara,,0,0,0,,$body")
            if (countdown) {
                val prevEnd = if (idx > 0) norm[idx - 1].end else 0.0
                if (start - prevEnd >= 1.6) {
                    val dotsList = listOf("●", "● ●", "● ● ●")
                    for (k in dotsList.indices) {
                        val d0 = start - 1.5 + 0.5 * k
                        events.add("Dialogue: 0,${assTime(d0)},${assTime(d0 + 0.5)},Dots,,0,0,0,,${dotsList[k]}")
                    }
                }
            }
            if (twoLine && idx + 1 < norm.size) {
                val ntext = safeText(norm[idx + 1].text)
                events.add("Dialogue: 0,${assTime(start)},${assTime(end)},KaraNext,,0,0,0,,{\\fad(150,150)}$ntext")
            }
        }
        return header + events.joinToString("\n") + "\n"
    }

    /**
     * Chốt 'end' cho các câu: ưu tiên 'end' có sẵn nếu hợp lệ, không thì lấy
     * start của câu sau; câu cuối kết thúc ở min(start + last_extra*2, hết bài)
     * nhưng tối thiểu start+1.5. Giữ nguyên 'words'. — port của assign_ends().
     */
    fun assignEnds(lines: List<TimedLine>, songDuration: Double, lastExtra: Double = 4.0): List<TimedLine> {
        val norm = lines.map {
            it.copy(start = max(0.0, it.start))
        }.sortedBy { it.start }
        val out = ArrayList<TimedLine>(norm.size)
        for (i in norm.indices) {
            val ln = norm[i]
            val start = ln.start
            val nxt = if (i + 1 < norm.size) norm[i + 1].start else songDuration
            val given = ln.end
            var end: Double
            if (start + 0.05 < given && given > 0) {
                end = min(given, max(nxt, start + 0.05))
            } else if (i + 1 < norm.size) {
                end = nxt
            } else {
                end = min(songDuration, start + max(3.0, lastExtra))
                end = max(end, start + 1.5)
            }
            end = max(end, start + 0.05)
            out.add(ln.copy(start = start, end = end))
        }
        return out
    }
}
