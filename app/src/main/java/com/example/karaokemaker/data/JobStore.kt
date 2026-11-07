package com.example.karaokemaker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Trạng thái mọi tác vụ dài, LƯU BỀN vào filesDir/jobs.json.
 *
 * Luật sống còn của bản Android (HyperOS giết app nền rất gắt): không một
 * tác vụ nào được "mất trắng" trong im lặng. Khi app/service khởi động lại,
 * job nào đang "running" trong sổ mà không có tiến trình thật chạy nó sẽ bị
 * đánh dấu "interrupted" (bị gián đoạn) kèm nút Chạy tiếp — các chặng đều
 * kiểm tra file đầu ra đã có thì bỏ qua, nên chạy tiếp không làm lại từ đầu.
 */
data class Job(
    val id: String,
    val projectId: String,
    /** download | separate | transcribe | render | pipeline | model */
    val type: String,
    var status: String = "queued", // queued | running | done | error | canceled | interrupted
    var progress: Double = 0.0,
    var label: String = "",
    var error: String? = null,
    /** JSON phụ: separate -> {"mode":"fast|ai"}; pipeline -> {"sepMode":"fast|ai","autoRender":true} */
    var payload: String = "{}",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun payloadJson(): JSONObject = try {
        JSONObject(payload)
    } catch (e: Exception) {
        JSONObject()
    }

    fun isActive(): Boolean = status == "queued" || status == "running"

    fun typeLabel(): String = when (type) {
        "download" -> "Tải bài"
        "separate" -> "Tách beat"
        "transcribe" -> "Chép lời AI"
        "render" -> "Xuất video"
        "pipeline" -> "Tự làm trọn gói"
        "model" -> "Tải model AI"
        else -> type
    }
}

object JobStore {

    private lateinit var file: File
    private val jobs = LinkedHashMap<String, Job>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(context: Context) {
        file = File(context.filesDir, "jobs.json")
        load()
    }

    @Synchronized
    private fun load() {
        jobs.clear()
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val j = Job(
                    id = o.getString("id"),
                    projectId = o.getString("projectId"),
                    type = o.getString("type"),
                    status = o.optString("status", "queued"),
                    progress = o.optDouble("progress", 0.0),
                    label = o.optString("label", ""),
                    error = if (o.isNull("error")) null else o.optString("error"),
                    payload = o.optString("payload", "{}"),
                    createdAt = o.optLong("createdAt", 0),
                    updatedAt = o.optLong("updatedAt", 0)
                )
                jobs[j.id] = j
            }
        } catch (e: Exception) {
            jobs.clear()
        }
    }

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        for (j in jobs.values) {
            val o = JSONObject()
            o.put("id", j.id); o.put("projectId", j.projectId); o.put("type", j.type)
            o.put("status", j.status); o.put("progress", j.progress)
            o.put("label", j.label); o.put("error", j.error ?: JSONObject.NULL)
            o.put("payload", j.payload)
            o.put("createdAt", j.createdAt); o.put("updatedAt", j.updatedAt)
            arr.put(o)
        }
        val tmp = File(file.parentFile, "jobs.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        tmp.renameTo(file)
        listeners.forEach { it() }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    @Synchronized
    fun enqueue(projectId: String, type: String, payload: String = "{}"): Job {
        val j = Job(
            id = UUID.randomUUID().toString().take(12),
            projectId = projectId, type = type, payload = payload
        )
        jobs[j.id] = j
        persist()
        return j
    }

    @Synchronized
    fun get(id: String): Job? = jobs[id]

    @Synchronized
    fun all(): List<Job> = jobs.values.sortedByDescending { it.updatedAt }

    @Synchronized
    fun forProject(projectId: String): List<Job> =
        jobs.values.filter { it.projectId == projectId }.sortedByDescending { it.updatedAt }

    /** Job đang chờ/chạy đầu tiên (hàng đợi chạy lần lượt 1 job). */
    @Synchronized
    fun nextQueued(): Job? = jobs.values
        .filter { it.status == "queued" }
        .minByOrNull { it.createdAt }

    @Synchronized
    fun update(id: String, block: (Job) -> Unit) {
        val j = jobs[id] ?: return
        block(j)
        j.updatedAt = System.currentTimeMillis()
        persist()
    }

    @Volatile
    private var lastProgressPersist = 0L

    /**
     * Cập nhật tiến độ có TIẾT LƯU: callback của ffmpeg/engine có thể gọi
     * hàng chục lần/giây — chỉ ghi đĩa khi % đổi >= 1, nhãn đổi, về đích
     * 100, hoặc đã qua 1 giây. Trạng thái trong RAM luôn mới nhất.
     */
    @Synchronized
    fun progress(id: String, pct: Double, label: String) {
        val j = jobs[id] ?: return
        val newPct = pct.coerceIn(0.0, 100.0)
        val labelChanged = label.isNotEmpty() && label != j.label
        j.progress = newPct
        if (label.isNotEmpty()) j.label = label
        val now = System.currentTimeMillis()
        val shouldPersist = labelChanged || newPct >= 100.0 ||
            kotlin.math.abs(newPct - lastPersistedPct(j)) >= 1.0 ||
            now - lastProgressPersist >= 1000
        if (shouldPersist) {
            j.updatedAt = now
            lastProgressPersist = now
            lastPersistedPctMap[j.id] = newPct
            persist()
        }
    }

    private val lastPersistedPctMap = HashMap<String, Double>()
    private fun lastPersistedPct(j: Job): Double = lastPersistedPctMap[j.id] ?: -100.0

    /**
     * Gọi khi service khởi động: job "running" trong sổ nhưng service vừa
     * lên (chưa chạy job nào) = tiến trình cũ đã bị giết giữa chừng.
     * Đánh dấu interrupted để UI báo rõ + cho chạy tiếp. Job "queued" giữ
     * nguyên để service nhặt chạy tiếp.
     */
    @Synchronized
    fun sweepInterruptedOnServiceStart() {
        var changed = false
        for (j in jobs.values) {
            if (j.status == "running") {
                j.status = "interrupted"
                j.error = "Bị gián đoạn (app bị đóng hoặc máy tắt tiến trình nền). Bấm Chạy tiếp để làm tiếp — phần nào xong rồi sẽ không làm lại."
                changed = true
            }
        }
        if (changed) persist()
    }

    /** Dọn job đã xong/lỗi quá cũ (giữ sổ nhẹ) — chỉ dọn khi không còn active. */
    @Synchronized
    fun pruneOld(keepPerProject: Int = 20) {
        val done = jobs.values.filter { !it.isActive() }
            .groupBy { it.projectId }
        var changed = false
        for ((_, list) in done) {
            list.sortedByDescending { it.updatedAt }
                .drop(keepPerProject)
                .forEach { jobs.remove(it.id); changed = true }
        }
        if (changed) persist()
    }
}
