package com.example.karaokemaker.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Đọc/ghi WAV s16 (định dạng làm việc chung của pipeline, như bản PC) và
 * tính đỉnh sóng (peaks) cho WaveformView ở Studio.
 */
object AudioUtil {

    data class WavData(
        val channels: Int,
        val sampleRate: Int,
        /** Mẫu float [-1,1] đã tách kênh: [kênh][mẫu]. */
        val samples: Array<FloatArray>
    ) {
        val frames: Int get() = if (samples.isEmpty()) 0 else samples[0].size
        val durationSec: Double get() = if (sampleRate == 0) 0.0 else frames.toDouble() / sampleRate
    }

    /** Đọc WAV PCM s16 (hỗ trợ mono/stereo). File khác định dạng -> ném lỗi rõ ràng. */
    fun readWavS16(file: File): WavData {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "File WAV quá ngắn / hỏng" }
        require(
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) { "Không phải file WAV" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLen = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    val audioFormat = buf.getShort(pos + 8).toInt()
                    require(audioFormat == 1) { "WAV không phải PCM (format=$audioFormat)" }
                    channels = buf.getShort(pos + 10).toInt()
                    sampleRate = buf.getInt(pos + 12)
                    bitsPerSample = buf.getShort(pos + 22).toInt()
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataLen = minOf(size, bytes.size - dataOffset)
                    break
                }
            }
            pos += 8 + size + (size % 2)
        }
        require(dataOffset >= 0 && channels in 1..2 && bitsPerSample == 16) {
            "WAV phải là PCM 16-bit mono/stereo"
        }
        val frames = dataLen / (2 * channels)
        val samples = Array(channels) { FloatArray(frames) }
        var p = dataOffset
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                samples[c][i] = buf.getShort(p) / 32768f
                p += 2
            }
        }
        return WavData(channels, sampleRate, samples)
    }

    /** Ghi WAV s16 stereo 44.1kHz (định dạng làm việc chuẩn của pipeline). */
    fun writeWavS16Stereo(file: File, left: FloatArray, right: FloatArray, sampleRate: Int = 44100) {
        val frames = minOf(left.size, right.size)
        val dataLen = frames * 4
        val out = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + dataLen)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(1) // PCM
        out.putShort(2) // stereo
        out.putInt(sampleRate)
        out.putInt(sampleRate * 4)
        out.putShort(4)
        out.putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(dataLen)
        for (i in 0 until frames) {
            out.putShort((left[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.putShort((right[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        file.writeBytes(out.array())
    }

    /** Đọc WAV s16 mono 16kHz (đầu ra giải mã cho engine chép lời) -> FloatArray. */
    fun readMono16k(file: File): FloatArray {
        val w = readWavS16(file)
        require(w.sampleRate == 16000) { "WAV cho AI nghe phải là 16kHz (đang là ${w.sampleRate})" }
        if (w.channels == 1) return w.samples[0]
        val n = w.frames
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (w.samples[0][i] + w.samples[1][i]) * 0.5f
        return out
    }

    /**
     * Tính peaks cho waveform: [columns] giá trị 0..1 (max tuyệt đối mỗi cột,
     * gộp 2 kênh). Đọc trực tiếp từ file WAV s16 theo từng khối, không nạp cả
     * bài vào RAM.
     */
    fun computePeaks(file: File, columns: Int = 240): FloatArray {
        val w = readWavS16(file)
        val peaks = FloatArray(columns)
        val frames = w.frames
        if (frames == 0) return peaks
        val per = maxOf(1, frames / columns)
        for (col in 0 until columns) {
            val start = col * per
            val end = minOf(frames, start + per)
            var mx = 0f
            for (i in start until end) {
                for (c in 0 until w.channels) {
                    val v = abs(w.samples[c][i])
                    if (v > mx) mx = v
                }
            }
            peaks[col] = mx
        }
        return peaks
    }

    fun savePeaks(file: File, peaks: FloatArray) {
        val buf = ByteBuffer.allocate(peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        peaks.forEach { buf.putFloat(it) }
        file.writeBytes(buf.array())
    }

    fun loadPeaks(file: File): FloatArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size % 4 != 0 || bytes.isEmpty()) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat(it * 4) }
    }
}
