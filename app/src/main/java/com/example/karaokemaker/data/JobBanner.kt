package com.example.karaokemaker.data

/**
 * Logic chọn job để HIỂN THỊ ở màn Thư viện — tách thuần khỏi Activity để
 * kiểm thử được bằng test JVM (v1.0.1).
 *
 * Bài học v1.0.0: Thư viện chỉ hiện job đang active; job LỖI không hiện ở
 * đâu cả nên user tưởng job còn chạy và đợi oan cả giờ. Từ v1.0.1 banner
 * của Thư viện luôn phản ánh đúng 1 trong 3 trạng thái: đang chạy / bị lỗi
 * gần nhất (kèm nội dung lỗi + nút Chạy tiếp) / không có gì để hiện.
 */
object JobBanner {

    sealed interface State {
        /** Không có job active, cũng không có job lỗi nào còn "hiệu lực". */
        object None : State

        /** Có job đang chờ/chạy — hiện thẻ tiến độ + nút Huỷ. */
        data class Active(val job: Job) : State

        /**
         * Không có job active nhưng job kết thúc gần nhất là lỗi/gián đoạn —
         * hiện thẻ lỗi đỏ + nút Chạy tiếp. "Hiệu lực" nghĩa là chưa có job
         * nào HOÀN THÀNH sau nó (job xong mới hơn = lỗi đã được xử lý).
         */
        data class Failed(val job: Job) : State
    }

    /** Chọn trạng thái banner từ toàn bộ sổ job. */
    fun pick(jobs: List<Job>): State {
        val sorted = jobs.sortedByDescending { it.updatedAt }
        sorted.firstOrNull { it.isActive() }?.let { return State.Active(it) }
        val failure = sorted.firstOrNull {
            it.status == "error" || it.status == "interrupted"
        } ?: return State.None
        if (sorted.any { it.status == "done" && it.updatedAt > failure.updatedAt }) {
            return State.None
        }
        return State.Failed(failure)
    }

    /**
     * Job mà dòng tóm tắt trên THẺ DỰ ÁN cần mô tả (null = ẩn dòng):
     * ưu tiên job đang active của dự án; không có thì lấy job MỚI NHẤT của
     * dự án nếu nó lỗi/gián đoạn. Job lỗi cũ nhưng dự án đã có job xong mới
     * hơn thì không hiện nữa (tránh in nhãn tiến độ cũ như v1.0.0).
     */
    fun projectLineJob(jobsOfProject: List<Job>): Job? {
        if (jobsOfProject.isEmpty()) return null
        jobsOfProject.filter { it.isActive() }
            .maxByOrNull { it.updatedAt }
            ?.let { return it }
        val latest = jobsOfProject.maxByOrNull { it.updatedAt } ?: return null
        return if (latest.status == "error" || latest.status == "interrupted") {
            latest
        } else {
            null
        }
    }
}
