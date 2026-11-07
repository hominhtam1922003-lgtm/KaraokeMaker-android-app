package com.example.karaokemaker.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Port của karaoke/align.py (bản PC): đối chiếu bản chép của AI (kèm giờ từng
 * từ) với lời người dùng dán vào bằng khớp chuỗi, suy ra giờ bắt đầu từng câu
 * và giờ hát thật của từng từ. Câu không khớp trực tiếp được nội suy từ hai
 * câu kề. Phần chạy engine nghe (faster-whisper ở PC) KHÔNG nằm ở đây — bản
 * Android dùng sherpa-onnx (xem audio/Whisper.kt), đầu vào [HeardWord] giữ
 * nguyên định dạng {'w','start','end'}.
 *
 * Kotlin/Java không có difflib.SequenceMatcher nên thuật toán khớp khối
 * (matching blocks kiểu Ratcliff–Obershelp, autojunk=False) được cài lại ở
 * cuối file, cho kết quả tương đương với các khối "equal" của bản gốc.
 */
object Align {

    fun stripDiacritics(s: String): String {
        val noD = s.replace("đ", "d").replace("Đ", "D")
        val nfd = Normalizer.normalize(noD, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (c in nfd) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
        }
        return sb.toString()
    }

    /** Chuẩn hoá một từ để đối chiếu: thường hoá, bỏ dấu câu; strip=true thì
     *  bỏ luôn dấu tiếng Việt (lời dán thiếu dấu vẫn khớp được bản nghe). */
    fun normWord(w: String, strip: Boolean = false): String {
        var x = Normalizer.normalize(w, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        x = x.filter { it.isLetterOrDigit() || it == '_' }
        if (strip) x = stripDiacritics(x)
        return x
    }

    private fun lineWords(text: String): List<String> =
        text.split(Regex("""\s+""")).map { normWord(it) }.filter { it.isNotEmpty() }

    /** Khớp hai dãy từ đã chuẩn hoá -> {chỉ số từ phía lời: chỉ số từ phía nghe}. */
    private fun matchPositions(heardNorm: List<String>, lyricNorm: List<String>): Map<Int, Int> {
        val pairs = heardNorm.withIndex().filter { it.value.isNotEmpty() }
        val sm = SeqMatcher(pairs.map { it.value }, lyricNorm)
        val mapping = HashMap<Int, Int>()
        for (op in sm.opcodes()) {
            if (op.tag == "equal") {
                for (k in 0 until (op.i2 - op.i1)) {
                    mapping[op.j1 + k] = pairs[op.i1 + k].index
                }
            }
        }
        return mapping
    }

    private data class Computed(
        val mapping: Map<Int, Int>,
        val ratio: Double,
        val lineWords: List<List<String>>
    )

    private fun computeMapping(heard: List<HeardWord>, lineTexts: List<String>): Computed {
        val lineWords = lineTexts.map { lineWords(it) }
        val lyricNorm = lineWords.flatten()
        require(lyricNorm.isNotEmpty()) { "Lời chưa có chữ nào để căn" }
        require(heard.isNotEmpty()) { "AI không nghe được lời nào trong bài này" }

        fun run(strip: Boolean): Map<Int, Int> {
            val heardNorm = heard.map { normWord(it.w, strip) }
            val lyr = if (strip) lyricNorm.map { stripDiacritics(it) } else lyricNorm
            return matchPositions(heardNorm, lyr)
        }

        var mapping = run(false)
        var ratio = mapping.size.toDouble() / lyricNorm.size
        if (ratio < 0.5) { // lời dán thiếu dấu / lệch nhiều: thử lượt bỏ dấu
            val m2 = run(true)
            if (m2.size > mapping.size) {
                mapping = m2
                ratio = m2.size.toDouble() / lyricNorm.size
            }
        }
        return Computed(mapping, ratio, lineWords)
    }

    /** Giờ bắt đầu từng câu từ mapping; câu không khớp thì nội suy. */
    private fun startsFromMapping(
        heard: List<HeardWord>,
        lineWords: List<List<String>>,
        mapping: Map<Int, Int>,
        duration: Double = 0.0
    ): List<Double> {
        val starts = arrayOfNulls<Double>(lineWords.size)
        var pos = 0
        for (li in lineWords.indices) {
            val ws = lineWords[li]
            val idxs = (pos until pos + ws.size).filter { it in mapping }.map { mapping.getValue(it) }
            if (idxs.isNotEmpty()) starts[li] = heard[idxs.min()].start
            pos += ws.size
        }
        val known = starts.indices.filter { starts[it] != null }
        require(known.isNotEmpty()) {
            "AI nghe được lời nhưng không khớp với lời đã dán — kiểm tra lại lời có đúng bài / đúng ngôn ngữ không"
        }
        for (i in starts.indices) {
            if (starts[i] != null) continue
            val prev = known.filter { it < i }.maxOrNull()
            val nxt = known.filter { it > i }.minOrNull()
            starts[i] = when {
                prev != null && nxt != null -> {
                    val frac = (i - prev).toDouble() / (nxt - prev)
                    starts[prev]!! + frac * (starts[nxt]!! - starts[prev]!!)
                }
                nxt != null -> max(0.0, starts[nxt]!! - 2.2 * (nxt - i))
                else -> {
                    // nxt == null mà vẫn còn câu chưa có giờ -> prev chắc chắn tồn tại
                    val pv = prev ?: known.max()
                    starts[pv]!! + 2.5 * (i - pv)
                }
            }
        }
        val out = ArrayList<Double>(starts.size)
        var last = 0.0
        for (s0 in starts) {
            var s = max(s0!!, 0.0)
            if (out.isNotEmpty()) s = max(s, last + 0.1)
            if (duration > 0) s = min(s, max(0.0, duration - 0.3))
            out.add(Math.round(s * 100) / 100.0)
            last = s
        }
        return out
    }

    data class AlignResult(
        val starts: List<Double>,
        val ratio: Double,
        /** Giờ từng từ theo từng câu — khớp vị trí với từ đã chuẩn hoá của câu. */
        val words: List<List<WordTime>>,
        /** Câu có ít nhất 1 từ khớp trực tiếp với bản nghe hay không. */
        val lineMatched: List<Boolean>
    )

    /** Như alignLines của bản PC nhưng kèm giờ hát thật của từng từ:
     *  từ không khớp trực tiếp được nội suy tuyến tính giữa hai từ khớp kề bên. */
    fun alignFull(heard: List<HeardWord>, lineTexts: List<String>, duration: Double = 0.0): AlignResult {
        val (mapping, ratio, lineWords) = computeMapping(heard, lineTexts)
        val starts = startsFromMapping(heard, lineWords, mapping, duration)
        val knownIdx = mapping.keys.sorted()

        fun wordTime(p: Int): Pair<Double, Double> {
            if (p in mapping) {
                val h = heard[mapping.getValue(p)]
                return h.start to h.end
            }
            val prev = knownIdx.filter { it < p }.maxOrNull()
            val nxt = knownIdx.filter { it > p }.minOrNull()
            if (prev != null && nxt != null) {
                val hp = heard[mapping.getValue(prev)]
                val hn = heard[mapping.getValue(nxt)]
                val frac = (p - prev).toDouble() / (nxt - prev)
                val s = hp.end + frac * (hn.start - hp.end)
                return s to s + 0.25
            }
            if (nxt != null) {
                val s = max(0.0, heard[mapping.getValue(nxt)].start - 0.3 * (nxt - p))
                return s to s + 0.25
            }
            val s = heard[mapping.getValue(prev!!)].end + 0.3 * (p - prev)
            return s to s + 0.25
        }

        val wordsOut = ArrayList<List<WordTime>>()
        val matchedOut = ArrayList<Boolean>()
        var pos = 0
        for (li in lineWords.indices) {
            val ws = lineWords[li]
            val row = ArrayList<WordTime>()
            var anyMatch = false
            for (k in ws.indices) {
                if (pos + k in mapping) anyMatch = true
                var (s, e) = wordTime(pos + k)
                s = max(s, starts[li])
                if (row.isNotEmpty()) s = max(s, row.last().end - 0.05)
                e = max(e, s + 0.05)
                row.add(WordTime(Math.round(s * 100) / 100.0, Math.round(e * 100) / 100.0))
            }
            wordsOut.add(row)
            matchedOut.add(anyMatch)
            pos += ws.size
        }
        return AlignResult(starts, Math.round(ratio * 1000) / 1000.0, wordsOut, matchedOut)
    }
}

/** Opcode kiểu difflib: tag ∈ {equal, replace, delete, insert}. */
data class Opcode(val tag: String, val i1: Int, val i2: Int, val j1: Int, val j2: Int)

/**
 * Cài lại difflib.SequenceMatcher (autojunk=False) của Python: tìm các khối
 * khớp dài nhất theo đệ quy rồi suy ra opcodes. Chỉ dùng các khối "equal"
 * nhưng cài đủ để hành vi khớp trung thực với bản PC.
 */
class SeqMatcher(private val a: List<String>, private val b: List<String>) {

    private fun findLongestMatch(alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
        val b2j = HashMap<String, MutableList<Int>>()
        for (j in blo until bhi) b2j.getOrPut(b[j]) { ArrayList() }.add(j)
        var bestI = alo; var bestJ = blo; var bestSize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            for (j in b2j[a[i]] ?: emptyList()) {
                if (j < blo || j >= bhi) continue
                val k = (j2len[j - 1] ?: 0) + 1
                newj2len[j] = k
                if (k > bestSize) {
                    bestI = i - k + 1; bestJ = j - k + 1; bestSize = k
                }
            }
            j2len = newj2len
        }
        return Triple(bestI, bestJ, bestSize)
    }

    fun opcodes(): List<Opcode> {
        val blocks = ArrayList<Triple<Int, Int, Int>>()
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(0, a.size, 0, b.size))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val (i, j, k) = findLongestMatch(alo, ahi, blo, bhi)
            if (k > 0) {
                blocks.add(Triple(i, j, k))
                if (alo < i && blo < j) queue.add(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.add(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        blocks.sortWith(compareBy({ it.first }, { it.second }))
        // Gộp các khối kề nhau (như difflib.get_matching_blocks)
        var i1 = 0; var j1 = 0; var k1 = 0
        val merged = ArrayList<Triple<Int, Int, Int>>()
        for ((ai, bj, size) in blocks) {
            if (i1 + k1 == ai && j1 + k1 == bj) {
                k1 += size
            } else {
                if (k1 > 0) merged.add(Triple(i1, j1, k1))
                i1 = ai; j1 = bj; k1 = size
            }
        }
        if (k1 > 0) merged.add(Triple(i1, j1, k1))
        merged.add(Triple(a.size, b.size, 0))

        val ops = ArrayList<Opcode>()
        var i = 0; var j = 0
        for ((ai, bj, size) in merged) {
            val tag = when {
                i < ai && j < bj -> "replace"
                i < ai -> "delete"
                j < bj -> "insert"
                else -> ""
            }
            if (tag.isNotEmpty()) ops.add(Opcode(tag, i, ai, j, bj))
            i = ai + size; j = bj + size
            if (size > 0) ops.add(Opcode("equal", ai, i, bj, j))
        }
        return ops
    }
}
