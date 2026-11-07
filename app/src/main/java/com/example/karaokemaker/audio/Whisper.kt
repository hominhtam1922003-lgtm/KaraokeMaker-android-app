package com.example.karaokemaker.audio

import com.example.karaokemaker.core.HeardSegment
import com.example.karaokemaker.core.HeardWord
import com.example.karaokemaker.core.TranscribeLines
import com.example.karaokemaker.download.ModelManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import kotlin.math.sqrt

/**
 * Chép lời bằng sherpa-onnx (Whisper small int8, bản export CÓ attention để
 * lấy timestamp từng token qua DTW). Theo chuẩn đã chốt ở file deps:
 *  - language="vi" CỐ ĐỊNH (không auto-detect — nhạc dễ bị nhận nhầm);
 *  - chép trên stem giọng khi đã tách/cắt (bài học từ bản PC v3);
 *  - sherpa giải mã theo cửa sổ <= 30 giây nên audio được cắt thành đoạn
 *    <= 28 giây tại khoảng lặng gần biên nhất; mỗi đoạn là 1 stream riêng.
 *
 * Nếu timestamp từng token rỗng (model không có attention output / DTW
 * không chạy), tự lùi về giờ theo đoạn + nội suy giờ từng từ — đúng thang
 * dự phòng như bản PC, không để người dùng nhận kết quả trống.
 */
object Whisper {

    class TranscribeException(message: String) : Exception(message)

    fun interface Progress {
        fun onProgress(pct: Double, label: String)
    }

    private const val SAMPLE_RATE = 16000
    private const val MAX_CHUNK_SEC = 28.0
    private const val SILENCE_SEARCH_SEC = 6.0

    /**
     * Chép lời file WAV 16kHz mono. Trả về các đoạn kèm giờ từng từ (thật
     * hoặc nội suy nếu [usedFallback] được set qua callback kết quả).
     */
    fun transcribe(
        wav16k: File,
        modelsDir: File,
        progress: Progress?,
        isCancelled: () -> Boolean = { false }
    ): List<HeardSegment> {
        val enc = File(modelsDir, ModelManager.WHISPER_ENCODER)
        val dec = File(modelsDir, ModelManager.WHISPER_DECODER)
        val tokens = File(modelsDir, ModelManager.WHISPER_TOKENS)
        require(enc.exists() && dec.exists() && tokens.exists()) {
            "Chưa tải model AI nghe lời — hãy tải model trước (mục Model AI trong Thư viện)"
        }
        val samples = AudioUtil.readMono16k(wav16k)
        if (samples.isEmpty()) throw TranscribeException("File âm thanh rỗng")

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = enc.absolutePath,
                    decoder = dec.absolutePath,
                    language = "vi",
                    task = "transcribe",
                    tailPaddings = 1000,
                    enableTokenTimestamps = true,
                    enableSegmentTimestamps = true
                ),
                tokens = tokens.absolutePath,
                numThreads = 4,
                modelType = "whisper"
            ),
            decodingMethod = "greedy_search"
        )
        val recognizer = OfflineRecognizer(null, config)
        try {
            val chunks = splitBySilence(samples)
            val segments = ArrayList<HeardSegment>()
            var tokenTimestampsMissing = false
            for ((ci, chunk) in chunks.withIndex()) {
                if (isCancelled()) throw TranscribeException("Đã huỷ chép lời")
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(sampleRate = SAMPLE_RATE, samples = chunk.samples)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    val text = result.text.trim()
                    if (text.isNotEmpty()) {
                        val words = groupTokensToWords(
                            result.tokens, result.timestamps, result.durations, chunk.offsetSec
                        )
                        if (words.isEmpty()) tokenTimestampsMissing = true
                        // words đã mang offset tuyệt đối (groupTokensToWords cộng offsetSec)
                        segments.add(
                            HeardSegment(
                                text = text,
                                start = words.firstOrNull()?.start ?: chunk.offsetSec,
                                end = words.lastOrNull()?.end
                                    ?: (chunk.offsetSec + chunk.samples.size / SAMPLE_RATE.toDouble()),
                                words = words
                            )
                        )
                    }
                } finally {
                    stream.release()
                }
                progress?.onProgress(
                    (ci + 1).toDouble() / chunks.size * 100.0,
                    "đang nghe & chép lời"
                )
            }
            if (segments.isEmpty()) {
                throw TranscribeException(
                    "AI không nghe rõ lời nào trong bài này — thử căn tay bằng nút gắn nhịp trong Studio"
                )
            }
            // Đường dự phòng: không có giờ từng từ -> chia đều theo đoạn (như bản PC)
            return if (tokenTimestampsMissing || segments.all { it.words.isEmpty() }) {
                TranscribeLines.synthWords(segments.map { it.copy(words = emptyList()) })
            } else {
                segments
            }
        } finally {
            recognizer.release()
        }
    }

    private class Chunk(val samples: FloatArray, val offsetSec: Double)

    /**
     * Cắt audio thành đoạn <= 28s; điểm cắt chọn ở cửa sổ 100ms lặng nhất
     * trong 6 giây cuối của mỗi đoạn (tránh cắt giữa từ đang hát).
     */
    private fun splitBySilence(samples: FloatArray): List<Chunk> {
        val maxLen = (MAX_CHUNK_SEC * SAMPLE_RATE).toInt()
        if (samples.size <= maxLen) return listOf(Chunk(samples, 0.0))
        val chunks = ArrayList<Chunk>()
        var start = 0
        while (start < samples.size) {
            var end = minOf(samples.size, start + maxLen)
            if (end < samples.size) {
                val searchFrom = end - (SILENCE_SEARCH_SEC * SAMPLE_RATE).toInt()
                end = quietestPoint(samples, maxOf(start + maxLen / 2, searchFrom), end)
            }
            chunks.add(
                Chunk(
                    samples.copyOfRange(start, end),
                    start / SAMPLE_RATE.toDouble()
                )
            )
            start = end
        }
        return chunks
    }

    /** Vị trí giữa cửa sổ 100ms có năng lượng thấp nhất trong [from, to). */
    private fun quietestPoint(samples: FloatArray, from: Int, to: Int): Int {
        val win = SAMPLE_RATE / 10
        var bestPos = to
        var bestEnergy = Double.MAX_VALUE
        var i = from
        while (i + win <= to) {
            var e = 0.0
            var j = i
            while (j < i + win) {
                e += samples[j].toDouble() * samples[j]
                j += 10 // lấy mẫu thưa cho nhanh
            }
            val rms = sqrt(e)
            if (rms < bestEnergy) {
                bestEnergy = rms
                bestPos = i + win / 2
            }
            i += win / 2
        }
        return bestPos
    }

    /**
     * Gom token của Whisper thành từ: token bắt đầu bằng khoảng trắng (hoặc
     * ký hiệu Ġ của byte-level BPE) = bắt đầu từ mới. Giờ từ = giờ token đầu,
     * kết thúc = giờ + thời lượng của token cuối.
     * Trả về rỗng nếu engine không trả timestamp (để tầng trên lùi dự phòng).
     */
    private fun groupTokensToWords(
        tokens: Array<String>,
        timestamps: FloatArray,
        durations: FloatArray,
        offsetSec: Double
    ): List<HeardWord> {
        if (tokens.isEmpty() || timestamps.size < tokens.size) return emptyList()
        val words = ArrayList<HeardWord>()
        var curText = StringBuilder()
        var curStart = 0.0
        var curEnd = 0.0

        fun flush() {
            val t = curText.toString().trim()
            if (t.isNotEmpty()) {
                words.add(HeardWord(t, offsetSec + curStart, offsetSec + maxOf(curEnd, curStart + 0.05)))
            }
            curText = StringBuilder()
        }

        for (i in tokens.indices) {
            val raw = tokens[i]
            val startsNewWord = raw.startsWith(" ") || raw.startsWith("Ġ") || raw.startsWith("▁")
            val piece = raw.replace("Ġ", " ").replace("▁", " ").trim()
            val ts = timestamps[i].toDouble()
            val dur = if (i < durations.size) durations[i].toDouble() else 0.0
            if (startsNewWord && curText.isNotEmpty()) flush()
            if (curText.isEmpty()) curStart = ts
            if (piece.isNotEmpty()) {
                if (curText.isNotEmpty() && !startsNewWord) curText.append(piece)
                else {
                    if (curText.isNotEmpty()) curText.append(' ')
                    curText.append(piece)
                }
            }
            curEnd = maxOf(curEnd, ts + maxOf(dur, 0.02))
        }
        flush()
        return words
    }
}
