package com.example.karaokemaker.data

/**
 * Logic THUẦN của tính năng LÀM HÀNG LOẠT (v1.1.0) — tách khỏi Activity để
 * kiểm thử bằng test JVM: bóc link từ ô dán, chặn trần số bài, tính dung
 * lượng cần trước khi tạo mẻ, gom job theo đợt để màn Thư viện hiển thị.
 *
 * Thiết kế (theo bản thiết kế của Cổ Lực Na Trát): batch KHÔNG có bảng
 * riêng — chỉ là N project + N job pipeline dùng chung 1 `batchId`, chạy
 * trên hàng đợi tuần tự sẵn có của JobService. Lỗi 1 job không liên quan
 * gì tới các job còn lại trong sổ, nên "bài sau vẫn chạy" là tính chất tự
 * nhiên của hàng đợi, ở đây chỉ cần gom đúng để HIỂN THỊ.
 */
object BatchPlan {

    /** Trần bài mỗi đợt — đủ cho 1 buổi làm nội dung, chặn đầy máy. */
    const val MAX_ITEMS = 10

    /**
     * Ước lượng dung lượng làm việc cho 1 bài (song.wav + inst.wav +
     * vocal.wav + hear16k.wav + out.mp4 của bài 4–6 phút ≈ 150–250 MB).
     * Lấy mốc trên để pre-flight báo thiếu TRƯỚC khi tạo mẻ.
     */
    const val BYTES_PER_ITEM: Long = 250L * 1024 * 1024

    /**
     * Bóc các link YouTube từ ô dán nhiều dòng: mỗi dòng 1 link, bỏ dòng
     * trống/dòng không phải link YouTube, khử trùng giữ thứ tự nhập.
     */
    fun parseLinks(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.contains("youtu") }
            .distinct()
            .toList()

    /**
     * Lỗi chặn theo số lượng (null = hợp lệ). Trần áp cho cả mẻ link lẫn
     * mẻ file — vượt trần là CHẶN TOÀN BỘ (không tự cắt bớt) để user tự
     * quyết định bỏ link/file nào.
     */
    fun countError(count: Int): String? = when {
        count <= 0 -> "Chưa có bài nào để tạo đợt"
        count > MAX_ITEMS ->
            "Mỗi đợt tối đa $MAX_ITEMS bài — bạn đã chọn $count. " +
                "Bỏ bớt ${count - MAX_ITEMS} rồi thử lại."
        else -> null
    }

    /** Số byte còn THIẾU để làm [count] bài (0 = đủ). */
    fun shortageBytes(availableBytes: Long, count: Int): Long {
        val need = BYTES_PER_ITEM * count.coerceAtLeast(0)
        return if (availableBytes >= need) 0L else need - availableBytes
    }

    /** Định dạng dung lượng cho thông báo: "250 MB" / "1.5 GB". */
    fun fmtSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        } else {
            "${mb.toLong()} MB"
        }
    }

    /** Tóm tắt 1 đợt: các job của đợt (đã sắp theo thứ tự tạo) + đếm trạng thái. */
    data class BatchSummary(
        val batchId: String,
        val jobs: List<Job>,
        val total: Int,
        val done: Int,
        val error: Int,
        val active: Int,
        val other: Int,
        /** Mốc tạo sớm nhất của đợt — dùng sắp xếp đợt mới nhất lên đầu. */
        val firstCreatedAt: Long
    )

    /**
     * Gom toàn bộ sổ job thành các đợt (chỉ job có batchId). Đợt mới nhất
     * (theo job đầu tiên của đợt) lên trước; trong đợt, job giữ thứ tự tạo.
     */
    fun summarize(jobs: List<Job>): List<BatchSummary> =
        jobs.filter { !it.batchId.isNullOrEmpty() }
            .groupBy { it.batchId!! }
            .map { (batchId, group) ->
                val sorted = group.sortedBy { it.createdAt }
                BatchSummary(
                    batchId = batchId,
                    jobs = sorted,
                    total = sorted.size,
                    done = sorted.count { it.status == "done" },
                    error = sorted.count { it.status == "error" },
                    active = sorted.count { it.isActive() },
                    other = sorted.count {
                        it.status != "done" && it.status != "error" && !it.isActive()
                    },
                    firstCreatedAt = sorted.firstOrNull()?.createdAt ?: 0L
                )
            }
            .sortedByDescending { it.firstCreatedAt }
}
