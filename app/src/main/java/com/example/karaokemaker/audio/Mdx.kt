package com.example.karaokemaker.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tách beat bằng model MDX-Net qua ONNX Runtime — port của karaoke/mdx.py
 * (bản PC). Giữ nguyên 100% thông số và thuật toán: STFT n_fft=6144,
 * hop=1024, đoạn model 256 khung, chồng lấp bước 192, cửa sổ Hann theo thời
 * gian, zero 3 bin thấp nhất, chuẩn hoá đỉnh 0.9, thứ tự kênh "A"
 * (reL, imL, reR, imR).
 *
 * MODEL (v1.1.0): Kim Vocal 2 — hợp đồng tensor giống hệt model cũ
 * (input [batch,4,3072,256] -> output cùng shape, đã xác minh bằng cách
 * tải file thật về đọc graph + chạy thử) NHƯNG chiều stem NGƯỢC với
 * UVR-MDX-NET-Inst_HQ_3 cũ: Kim dự đoán THẲNG stem GIỌNG, nên
 * instrumental = bản gốc − giọng. Chiều này do [ModelManager] khai qua
 * `predictsVocal` và phép tách stem nằm ở [StemMath] (thuần, test được);
 * đầu ra bên ngoài KHÔNG đổi: inst.wav = nhạc nền, vocal.wav = giọng.
 *
 * Độ lệch CÓ CHỦ ĐÍCH so với bản PC (ghi rõ để đối chiếu): bản PC giữ phổ
 * của CẢ bài trong RAM (>1 GB — điện thoại không chịu nổi) nên bản Android
 * xử lý theo KHỐI 16 giây lõi + 3 giây chồng lấp mỗi bên; trong mỗi khối
 * thuật toán y hệt bản PC, khi nối chỉ lấy phần lõi nên vùng chồng lấp (nơi
 * biên khối có thể lệch) bị bỏ đi. FFT 6144 điểm (= 2^11 · 3, không phải luỹ
 * thừa 2) được cài bằng Cooley–Tukey mixed-radix ở cuối file.
 */
object Mdx {

    const val SR = 44100
    private const val N_FFT = 6144
    private const val HOP = 1024
    private const val DIM_F = 3072
    private const val DIM_T = 256
    private const val CHUNK_STEP = 192

    private const val BLOCK_CORE_SEC = 16
    private const val BLOCK_OVERLAP_SEC = 3

    class CancelledException : Exception("Đã huỷ tách beat")

    fun interface Progress {
        fun onProgress(pct: Double, label: String)
    }

    private fun hannPeriodic(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) w[i] = (0.5 - 0.5 * cos(2.0 * PI * i / n)).toFloat()
        return w
    }

    /** STFT một kênh: x -> phổ phức [bins=3073][frames] (re/im tách mảng). */
    private class Spec(val re: Array<FloatArray>, val im: Array<FloatArray>, val frames: Int)

    private fun stft(x: FloatArray): Spec {
        val win = hannPeriodic(N_FFT)
        val pad = N_FFT / 2
        val xp = reflectPad(x, pad)
        val nFrames = 1 + (xp.size - N_FFT) / HOP
        val bins = N_FFT / 2 + 1
        val re = Array(bins) { FloatArray(nFrames) }
        val im = Array(bins) { FloatArray(nFrames) }
        val fre = DoubleArray(N_FFT)
        val fim = DoubleArray(N_FFT)
        for (f in 0 until nFrames) {
            val off = f * HOP
            for (i in 0 until N_FFT) {
                fre[i] = (xp[off + i] * win[i]).toDouble()
                fim[i] = 0.0
            }
            Fft.fftInPlace(fre, fim, inverse = false)
            for (b in 0 until bins) {
                re[b][f] = fre[b].toFloat()
                im[b][f] = fim[b].toFloat()
            }
        }
        return Spec(re, im, nFrames)
    }

    private fun reflectPad(x: FloatArray, pad: Int): FloatArray {
        val n = x.size
        if (n == 1) return FloatArray(n + 2 * pad) { x[0] }
        val out = FloatArray(n + 2 * pad)
        val period = 2 * (n - 1)
        for (i in -pad until n + pad) {
            var j = ((i % period) + period) % period
            if (j >= n) j = period - j
            out[i + pad] = x[j]
        }
        return out
    }

    /** iSTFT: phổ [3073][frames] -> sóng, cắt pad 2 đầu, chốt độ dài [length]. */
    private fun istft(specRe: Array<FloatArray>, specIm: Array<FloatArray>, length: Int): FloatArray {
        val win = hannPeriodic(N_FFT)
        val nFrames = specRe[0].size
        val outLen = N_FFT + HOP * (nFrames - 1)
        val out = DoubleArray(outLen)
        val wsum = DoubleArray(outLen)
        val fre = DoubleArray(N_FFT)
        val fim = DoubleArray(N_FFT)
        for (f in 0 until nFrames) {
            // Dựng phổ Hermitian đầy đủ từ nửa dưới (bin 3072 = Nyquist = 0 như bản PC)
            for (b in 0..N_FFT / 2) {
                fre[b] = specRe[b][f].toDouble()
                fim[b] = specIm[b][f].toDouble()
            }
            for (b in 1 until N_FFT / 2) {
                fre[N_FFT - b] = specRe[b][f].toDouble()
                fim[N_FFT - b] = -specIm[b][f].toDouble()
            }
            Fft.fftInPlace(fre, fim, inverse = true)
            val s = f * HOP
            for (i in 0 until N_FFT) {
                out[s + i] = out[s + i] + fre[i] * win[i]
                val w = win[i].toDouble()
                wsum[s + i] = wsum[s + i] + w * w
            }
        }
        val res = DoubleArray(outLen)
        for (i in 0 until outLen) {
            res[i] = if (wsum[i] > 1e-10) out[i] / wsum[i] else out[i]
        }
        val pad = N_FFT / 2
        val trimmed = res.copyOfRange(pad, max(pad, outLen - pad))
        val outF = FloatArray(length)
        val cp = min(length, trimmed.size)
        for (i in 0 until cp) outF[i] = trimmed[i].toFloat()
        return outF
    }

    /**
     * Số học tách stem từ đầu ra của model MDX — THUẦN, không phụ thuộc
     * Android/ONNX để kiểm thử bằng test JVM.
     *
     * Model trả stem nó dự đoán ([predicted], đã đưa về biên độ gốc):
     * - predictsVocal = true (Kim Vocal 2): vocal = predicted,
     *   instrumental = orig − predicted.
     * - predictsVocal = false (Inst_HQ cũ): instrumental = predicted,
     *   vocal = orig − predicted.
     * Stem SUY RA (phép trừ) được kẹp [-1, 1] như hành vi cũ của vocal;
     * stem model dự đoán giữ nguyên (ghi WAV sẽ tự kẹp khi lượng tử s16).
     * Khi không chạm ngưỡng kẹp: instrumental + vocal == orig (kiểm ở test).
     *
     * @return (instrumental, vocal)
     */
    fun splitStem(orig: Float, predicted: Float, predictsVocal: Boolean): Pair<Float, Float> {
        val derived = (orig - predicted).coerceIn(-1f, 1f)
        return if (predictsVocal) derived to predicted else predicted to derived
    }

    /**
     * Tách instrumental từ [inWav] (WAV s16 stereo 44.1k) ghi ra [outWav];
     * nếu [vocalOut] != null: ghi thêm stem giọng. [modelPredictsVocal]
     * quyết định chiều stem (xem [splitStem]).
     * [modelFile] là file ONNX đã tải + đã đối chiếu sha256 (xem ModelManager).
     */
    fun separate(
        inWav: File,
        outWav: File,
        vocalOut: File?,
        modelFile: File,
        modelPredictsVocal: Boolean,
        progress: Progress?,
        isCancelled: () -> Boolean = { false }
    ) {
        require(modelFile.exists()) { "Không thấy model AI tách beat: ${modelFile.path}" }
        val wav = AudioUtil.readWavS16(inWav)
        require(wav.sampleRate == SR && wav.channels == 2) {
            "WAV đầu vào phải là stereo 44.1kHz (dùng Ffmpeg.extractAudio trước)"
        }
        val left = wav.samples[0]
        val right = wav.samples[1]
        val nSamples = left.size
        require(nSamples > 0) { "File nhạc rỗng (0 mẫu âm thanh)" }

        // Chuẩn hoá đỉnh như implementation tham chiếu (audio-separator)
        var peak = 0f
        for (i in 0 until nSamples) {
            val a = max(absf(left[i]), absf(right[i]))
            if (a > peak) peak = a
        }
        if (peak == 0f) peak = 1f
        val scale = if (peak > 0.9f) 0.9f / peak else 1f
        val inL = FloatArray(nSamples) { left[it] * scale }
        val inR = FloatArray(nSamples) { right[it] * scale }

        val outL = FloatArray(nSamples)
        val outR = FloatArray(nSamples)
        val vocL = if (vocalOut != null) FloatArray(nSamples) else null
        val vocR = if (vocalOut != null) FloatArray(nSamples) else null

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        val session = env.createSession(modelFile.absolutePath, opts)
        try {
            val inputName = session.inputNames.first()
            val coreSamples = BLOCK_CORE_SEC * SR
            val ovSamples = BLOCK_OVERLAP_SEC * SR
            var blockStart = 0
            var doneSamples = 0
            while (blockStart < nSamples) {
                if (isCancelled()) throw CancelledException()
                val blockEnd = min(nSamples, blockStart + coreSamples)
                val extStart = max(0, blockStart - ovSamples)
                val extEnd = min(nSamples, blockEnd + ovSamples)
                val segL = inL.copyOfRange(extStart, extEnd)
                val segR = inR.copyOfRange(extStart, extEnd)
                // predL/predR = stem model DỰ ĐOÁN (giọng nếu Kim Vocal 2,
                // nhạc nền nếu Inst_HQ cũ) — chiều do modelPredictsVocal.
                val (predL, predR) = separateSegment(
                    session, inputName, segL, segR
                ) { frac ->
                    val done = doneSamples + (blockEnd - blockStart) * frac
                    progress?.onProgress(done / nSamples * 100.0, "tách beat AI")
                }
                // Chỉ lấy phần lõi [blockStart, blockEnd) của khối mở rộng
                val coreOff = blockStart - extStart
                val coreLen = blockEnd - blockStart
                for (i in 0 until coreLen) {
                    val pl = predL[coreOff + i] / scale
                    val pr = predR[coreOff + i] / scale
                    val (il, vl) = splitStem(left[blockStart + i], pl, modelPredictsVocal)
                    val (ir, vr) = splitStem(right[blockStart + i], pr, modelPredictsVocal)
                    outL[blockStart + i] = il
                    outR[blockStart + i] = ir
                    if (vocL != null && vocR != null) {
                        vocL[blockStart + i] = vl
                        vocR[blockStart + i] = vr
                    }
                }
                doneSamples += coreLen
                blockStart = blockEnd
            }
        } finally {
            session.close()
            opts.close()
        }
        AudioUtil.writeWavS16Stereo(outWav, outL, outR, SR)
        if (vocalOut != null && vocL != null && vocR != null) {
            AudioUtil.writeWavS16Stereo(vocalOut, vocL, vocR, SR)
        }
        progress?.onProgress(100.0, "tách beat AI")
    }

    /** Chạy model trên một đoạn mở rộng (gồm chồng lấp) — thuật toán y hệt bản PC. */
    private fun separateSegment(
        session: OrtSession,
        inputName: String,
        segL: FloatArray,
        segR: FloatArray,
        progressFrac: (Double) -> Unit
    ): Pair<FloatArray, FloatArray> {
        val env = OrtEnvironment.getEnvironment()
        val specL = stft(segL)
        val specR = stft(segR)
        val totalT = specL.frames
        // Gộp phổ 2 kênh thành mảng [2][DIM_F][T], zero 3 bin thấp nhất (như bản PC)
        val specRe = Array(2) { Array(DIM_F) { FloatArray(totalT) } }
        val specIm = Array(2) { Array(DIM_F) { FloatArray(totalT) } }
        for (ch in 0..1) {
            val src = if (ch == 0) specL else specR
            for (b in 0 until DIM_F) {
                for (t in 0 until totalT) {
                    if (b >= 3) {
                        specRe[ch][b][t] = src.re[b][t]
                        specIm[ch][b][t] = src.im[b][t]
                    }
                }
            }
        }
        val accRe = Array(2) { Array(DIM_F) { FloatArray(totalT) } }
        val accIm = Array(2) { Array(DIM_F) { FloatArray(totalT) } }
        val weight = FloatArray(totalT)
        val winT = hannPeriodic(DIM_T)

        val starts = ArrayList<Int>()
        var s0 = 0
        val lastStart = max(1, totalT - DIM_T + 1)
        while (s0 < lastStart) {
            starts.add(s0)
            s0 += CHUNK_STEP
        }
        if (starts.isEmpty() || starts.last() + DIM_T < totalT) {
            starts.add(max(0, totalT - DIM_T))
        }
        val chunkStarts = starts.distinct().sorted()

        val inputBuf = FloatBuffer.allocate(4 * DIM_F * DIM_T)
        for ((idx, s) in chunkStarts.withIndex()) {
            // Dựng input (1,4,DIM_F,DIM_T) thứ tự kênh "A": reL, imL, reR, imR
            inputBuf.clear()
            for (chDef in 0..3) {
                val ch = chDef / 2
                val isRe = chDef % 2 == 0
                val srcArr = if (isRe) specRe[ch] else specIm[ch]
                for (b in 0 until DIM_F) {
                    for (t in 0 until DIM_T) {
                        val tt = s + t
                        inputBuf.put(if (tt < totalT) srcArr[b][tt] else 0f)
                    }
                }
            }
            inputBuf.rewind()
            val tensor = OnnxTensor.createTensor(
                env, inputBuf, longArrayOf(1, 4, DIM_F.toLong(), DIM_T.toLong())
            )
            val result = session.run(mapOf(inputName to tensor))
            @Suppress("UNCHECKED_CAST")
            val out = (result[0].value as Array<Array<Array<FloatArray>>>)[0]
            result.close()
            tensor.close()
            val valid = min(DIM_T, totalT - s)
            for (ch in 0..1) {
                val reOut = out[ch * 2]
                val imOut = out[ch * 2 + 1]
                for (b in 0 until DIM_F) {
                    for (t in 0 until valid) {
                        val w = winT[t]
                        accRe[ch][b][s + t] = accRe[ch][b][s + t] + reOut[b][t] * w
                        accIm[ch][b][s + t] = accIm[ch][b][s + t] + imOut[b][t] * w
                    }
                }
            }
            for (t in 0 until valid) weight[s + t] = weight[s + t] + winT[t]
            progressFrac((idx + 1).toDouble() / chunkStarts.size)
        }
        for (ch in 0..1) {
            for (b in 0 until DIM_F) {
                for (t in 0 until totalT) {
                    val w = max(weight[t], 1e-8f)
                    accRe[ch][b][t] = accRe[ch][b][t] / w
                    accIm[ch][b][t] = accIm[ch][b][t] / w
                }
            }
        }
        // iSTFT từng kênh với phổ đủ 3073 bin (bin trên DIM_F = 0 như bản PC)
        fun toFullSpec(ch: Int): Pair<Array<FloatArray>, Array<FloatArray>> {
            val bins = N_FFT / 2 + 1
            val re = Array(bins) { FloatArray(totalT) }
            val im = Array(bins) { FloatArray(totalT) }
            for (b in 0 until DIM_F) {
                re[b] = accRe[ch][b]
                im[b] = accIm[ch][b]
            }
            return re to im
        }
        val (reL, imL) = toFullSpec(0)
        val (reR, imR) = toFullSpec(1)
        // Sóng của stem model dự đoán (chưa phân biệt giọng/nhạc nền —
        // chiều stem được quyết ở splitStem tại nơi gọi).
        val predL = istft(reL, imL, segL.size)
        val predR = istft(reR, imR, segR.size)
        return predL to predR
    }

    private fun absf(x: Float): Float = if (x < 0) -x else x
}

/**
 * FFT cho độ dài hợp tuỳ ý (Cooley–Tukey mixed-radix đệ quy), tại chỗ trên
 * mảng Double. inverse=true cho IFFT không chuẩn hoá (người gọi tự chia n
 * nếu cần — ở đây istft dùng cửa sổ + wsum như bản PC nên giữ nguyên
 * quy ước của numpy irfft: IFFT đã gồm hệ số 1/n, xem ghi chú trong code).
 */
object Fft {

    fun fftInPlace(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        if (n <= 1) return
        fftRec(re, im, inverse)
        if (inverse) {
            for (i in 0 until n) {
                re[i] = re[i] / n
                im[i] = im[i] / n
            }
        }
    }

    private fun smallestPrimeFactor(n: Int): Int {
        var p = 2
        while (p * p <= n) {
            if (n % p == 0) return p
            p += if (p == 2) 1 else 2
        }
        return n
    }

    private fun fftRec(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        if (n <= 1) return
        val p = smallestPrimeFactor(n)
        if (p == n) {
            // n nguyên tố: DFT trực tiếp O(n^2) (chỉ gặp ở tầng lá nhỏ, vd 3)
            dftDirect(re, im, inverse)
            return
        }
        val m = n / p
        val subRe = Array(p) { DoubleArray(m) }
        val subIm = Array(p) { DoubleArray(m) }
        for (k in 0 until p) {
            for (j in 0 until m) {
                subRe[k][j] = re[p * j + k]
                subIm[k][j] = im[p * j + k]
            }
        }
        for (k in 0 until p) fftRec(subRe[k], subIm[k], inverse)
        val sign = if (inverse) 1.0 else -1.0
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (t in 0 until m) {
            for (r in 0 until p) {
                var sr = 0.0
                var si = 0.0
                for (k in 0 until p) {
                    // W_n^(k*t) * W_p^(k*r)
                    val ang = sign * 2.0 * PI * (k * t.toDouble() / n + k * r.toDouble() / p)
                    val c = cos(ang)
                    val s = sin(ang)
                    val vr = subRe[k][t]
                    val vi = subIm[k][t]
                    sr += vr * c - vi * s
                    si += vr * s + vi * c
                }
                outRe[m * r + t] = sr
                outIm[m * r + t] = si
            }
        }
        System.arraycopy(outRe, 0, re, 0, n)
        System.arraycopy(outIm, 0, im, 0, n)
    }

    private fun dftDirect(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        val sign = if (inverse) 1.0 else -1.0
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (q in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (i in 0 until n) {
                val ang = sign * 2.0 * PI * i * q / n
                val c = cos(ang)
                val s = sin(ang)
                sr += re[i] * c - im[i] * s
                si += re[i] * s + im[i] * c
            }
            outRe[q] = sr
            outIm[q] = si
        }
        System.arraycopy(outRe, 0, re, 0, n)
        System.arraycopy(outIm, 0, im, 0, n)
    }
}
