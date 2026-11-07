package com.example.karaokemaker.download

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Tải model AI lần đầu vào filesDir/models (KHÔNG nhét vào APK):
 *  - MDX tách beat: Kim_Vocal_2.onnx từ TRvlvr/model_repo (v1.1.0 — thay
 *    UVR-MDX-NET-Inst_HQ_3: SDR vocal đo được 10,18 so với 9,38, cùng
 *    66.759.214 byte, cùng hợp đồng tensor [batch,4,3072,256]; KHÁC ở
 *    chiều stem: Kim dự đoán THẲNG GIỌNG nên instrumental = gốc − giọng,
 *    xem [ModelFile.predictsVocal] + Mdx.splitStem) — BẮT BUỘC đối chiếu
 *    sha256 sau khi tải (chuẩn đã chốt ở file deps).
 *  - Whisper small: bản export CÓ attention (clairemcw) — bản small chuẩn
 *    không có attention output thì timestamp từng từ sẽ rỗng. Đối chiếu
 *    dung lượng từng file (repo cộng đồng không công bố sha256).
 * Tải dở để lại file .part; tải lại từ đầu cho chắc (model vài trăm MB,
 * máy user thường tải bằng Wi-Fi — đổi lại sự đơn giản/ổn định).
 */
object ModelManager {

    const val MDX_NAME = "Kim_Vocal_2.onnx"
    const val WHISPER_ENCODER = "small-encoder.int8.onnx"
    const val WHISPER_DECODER = "small-decoder.int8.onnx"
    const val WHISPER_TOKENS = "small-tokens.txt"

    data class ModelFile(
        val name: String,
        val url: String,
        val expectedSize: Long,
        val sha256: String? = null,
        /**
         * Chỉ dùng cho model MDX: true = model dự đoán stem GIỌNG
         * (Kim Vocal 2), false = dự đoán stem NHẠC NỀN (Inst_HQ cũ).
         * Quyết định chiều trừ stem trong Mdx.separate.
         */
        val predictsVocal: Boolean = false
    )

    // Kim Vocal 2 — xác minh THẬT ngày 07/11/2026 trước khi nối (v1.1.0):
    // tải file từ URL dưới về đo tại chỗ: đúng 66.759.214 byte, sha256 như
    // dưới; đọc graph bằng ONNX Runtime: input "input" [batch,4,3072,256]
    // float32 -> output "output" cùng shape (khớp hệt cách Mdx.kt chạy:
    // 4 kênh "A", DIM_F 3072, DIM_T 256); chạy thử 1 lượt suy luận cho ra
    // số hữu hạn. KHÔNG đoán số — sai 1 ký tự sha là app tự xoá + báo lỗi.
    val MDX = ModelFile(
        name = MDX_NAME,
        url = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/Kim_Vocal_2.onnx",
        expectedSize = 66_759_214L,
        sha256 = "ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b",
        predictsVocal = true
    )

    private const val WHISPER_BASE =
        "https://huggingface.co/clairemcw/sherpa-onnx-whisper-small-attention/resolve/main"

    val WHISPER_FILES = listOf(
        ModelFile(WHISPER_ENCODER, "$WHISPER_BASE/$WHISPER_ENCODER", 112_445_386L),
        ModelFile(WHISPER_DECODER, "$WHISPER_BASE/$WHISPER_DECODER", 262_482_583L),
        ModelFile(WHISPER_TOKENS, "$WHISPER_BASE/$WHISPER_TOKENS", 816_730L)
    )

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun fileOf(context: Context, name: String): File = File(modelsDir(context), name)

    fun isDownloaded(context: Context, spec: ModelFile): Boolean {
        val f = fileOf(context, spec.name)
        return f.exists() && f.length() == spec.expectedSize
    }

    fun mdxReady(context: Context): Boolean = isDownloaded(context, MDX)

    fun whisperReady(context: Context): Boolean = WHISPER_FILES.all { isDownloaded(context, it) }

    fun interface Progress {
        /** pct tổng của cả gói đang tải; fileName = file hiện tại. */
        fun onProgress(pct: Double, fileName: String)
    }

    class ModelException(message: String) : Exception(message)

    /**
     * Tải một gói model (1 hoặc nhiều file) nối tiếp, báo tiến độ tổng.
     * File đã đủ size thì bỏ qua. Sai size/sha sau tải -> xoá + ném lỗi rõ.
     */
    fun download(
        context: Context,
        specs: List<ModelFile>,
        progress: Progress?,
        isCancelled: () -> Boolean = { false }
    ) {
        val dir = modelsDir(context)
        val totalBytes = specs.filter { !isDownloaded(context, it) }.sumOf { it.expectedSize }
        var doneBytes = 0L
        for (spec in specs) {
            if (isDownloaded(context, spec)) continue
            if (isCancelled()) throw ModelException("Đã huỷ tải model")
            val target = File(dir, spec.name)
            val part = File(dir, spec.name + ".part")
            downloadFile(spec.url, part, spec.expectedSize) { partBytes ->
                if (isCancelled()) throw ModelException("Đã huỷ tải model")
                if (totalBytes > 0) {
                    val pct = (doneBytes + partBytes).toDouble() / totalBytes * 100.0
                    progress?.onProgress(pct.coerceIn(0.0, 99.0), spec.name)
                }
            }
            if (part.length() != spec.expectedSize) {
                part.delete()
                throw ModelException(
                    "Tải ${spec.name} không đủ dung lượng (${part.length()} / ${spec.expectedSize} byte) — kiểm tra mạng rồi thử lại"
                )
            }
            if (spec.sha256 != null) {
                val actual = sha256(part)
                if (!actual.equals(spec.sha256, ignoreCase = true)) {
                    part.delete()
                    throw ModelException(
                        "File ${spec.name} sai mã kiểm tra (sha256 không khớp) — đã xoá, hãy tải lại"
                    )
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            doneBytes += spec.expectedSize
        }
        progress?.onProgress(100.0, "")
    }

    private fun downloadFile(
        urlStr: String,
        dest: File,
        expectedSize: Long,
        onBytes: (Long) -> Unit
    ) {
        var url = URL(urlStr)
        var redirects = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "KaraokeMaker-Android")
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) throw ModelException("Máy chủ chuyển hướng không hợp lệ")
                url = URL(url, loc)
                redirects++
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw ModelException("Tải model thất bại (HTTP $code) — kiểm tra mạng rồi thử lại")
            }
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total - lastReport >= 1_000_000) {
                            lastReport = total
                            onBytes(total)
                        }
                    }
                    out.flush()
                    onBytes(total)
                    if (expectedSize > 0 && total != expectedSize) {
                        // để tầng trên báo lỗi theo size kỳ vọng thống nhất
                    }
                }
            }
            return
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
