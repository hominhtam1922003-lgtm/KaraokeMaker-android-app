package com.example.karaokemaker.data

import android.content.Context
import com.example.karaokemaker.core.BgSpec
import com.example.karaokemaker.core.StyleOptions
import com.example.karaokemaker.core.TimedLine
import com.example.karaokemaker.core.WordTime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Một bài/dự án karaoke trong Thư viện. Mỗi dự án có thư mục riêng
 * filesDir/projects/<id>/ chứa toàn bộ file làm việc (song.wav, inst.wav,
 * vocal.wav, lines.json, karaoke.ass, out.mp4) + project.json.
 * Đóng app mở lại: mọi trạng thái còn nguyên — đây là nền cho màn Thư viện
 * theo khung KaraStudio (chip beat/lời/đã xuất đọc từ đây).
 */
data class Project(
    val id: String,
    var title: String,
    /** youtube | file | mediastore */
    val sourceType: String,
    /** URL YouTube hoặc URI nội dung của file gốc. */
    var sourceRef: String,
    var durationSec: Double = 0.0,
    /** none | fast (cắt stereo) | ai (MDX) */
    var beatMode: String = "none",
    var beatReady: Boolean = false,
    /** none | editing (có lời, đang sửa/chưa khớp hết) | synced (đã đồng bộ) */
    var lyricsState: String = "none",
    var exportedUri: String? = null,
    var exportedName: String? = null,
    var style: StyleOptions = StyleOptions(),
    var bg: BgSpec = BgSpec(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    /** Chip trạng thái tổng hợp trên thẻ Thư viện (như khung: Đã xuất /
     *  Đang sửa lời / Chưa đồng bộ / Bản nháp). */
    fun statusLabel(): String = when {
        exportedUri != null -> "Đã xuất"
        lyricsState == "editing" -> "Đang sửa lời"
        lyricsState == "none" && beatReady -> "Chưa đồng bộ"
        lyricsState == "synced" -> "Chưa xuất"
        else -> "Bản nháp"
    }
}

object ProjectStore {

    private lateinit var rootDir: File
    private val cache = LinkedHashMap<String, Project>()

    fun init(context: Context) {
        rootDir = File(context.filesDir, "projects")
        rootDir.mkdirs()
        reload()
    }

    fun dirOf(id: String): File = File(rootDir, id).apply { mkdirs() }
    fun fileOf(id: String, name: String): File = File(dirOf(id), name)

    @Synchronized
    fun reload() {
        cache.clear()
        rootDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val f = File(dir, "project.json")
            if (f.exists()) {
                try {
                    val p = fromJson(JSONObject(f.readText(Charsets.UTF_8)))
                    cache[p.id] = p
                } catch (e: Exception) {
                    // project.json hỏng: bỏ qua dự án đó, không kéo sập cả thư viện
                }
            }
        }
    }

    @Synchronized
    fun all(): List<Project> = cache.values.sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(id: String): Project? = cache[id]

    @Synchronized
    fun create(title: String, sourceType: String, sourceRef: String): Project {
        val p = Project(
            id = UUID.randomUUID().toString().take(12),
            title = title, sourceType = sourceType, sourceRef = sourceRef
        )
        cache[p.id] = p
        save(p)
        return p
    }

    @Synchronized
    fun save(p: Project) {
        p.updatedAt = System.currentTimeMillis()
        cache[p.id] = p
        val f = fileOf(p.id, "project.json")
        val tmp = File(f.parentFile, "project.json.tmp")
        tmp.writeText(toJson(p).toString(), Charsets.UTF_8)
        tmp.renameTo(f)
    }

    @Synchronized
    fun delete(id: String) {
        cache.remove(id)
        dirOf(id).deleteRecursively()
    }

    // ---------------- dòng lời (lines.json) ----------------

    fun saveLines(projectId: String, lines: List<TimedLine>) {
        val arr = JSONArray()
        for (ln in lines) {
            val o = JSONObject()
            o.put("start", ln.start); o.put("end", ln.end); o.put("text", ln.text)
            o.put("voice", ln.voice); o.put("matched", ln.matched)
            val wa = JSONArray()
            for (w in ln.words) wa.put(JSONArray().put(w.start).put(w.end))
            o.put("words", wa)
            arr.put(o)
        }
        val f = fileOf(projectId, "lines.json")
        val tmp = File(f.parentFile, "lines.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        tmp.renameTo(f)
    }

    fun loadLines(projectId: String): List<TimedLine> {
        val f = fileOf(projectId, "lines.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<TimedLine>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val wa = o.optJSONArray("words") ?: JSONArray()
                val words = ArrayList<WordTime>(wa.length())
                for (j in 0 until wa.length()) {
                    val pair = wa.getJSONArray(j)
                    words.add(WordTime(pair.getDouble(0), pair.getDouble(1)))
                }
                out.add(
                    TimedLine(
                        o.getDouble("start"), o.getDouble("end"), o.getString("text"),
                        words, o.optInt("voice", 0), o.optBoolean("matched", true)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------- JSON ----------------

    private fun toJson(p: Project): JSONObject {
        val o = JSONObject()
        o.put("id", p.id); o.put("title", p.title)
        o.put("sourceType", p.sourceType); o.put("sourceRef", p.sourceRef)
        o.put("durationSec", p.durationSec)
        o.put("beatMode", p.beatMode); o.put("beatReady", p.beatReady)
        o.put("lyricsState", p.lyricsState)
        o.put("exportedUri", p.exportedUri ?: JSONObject.NULL)
        o.put("exportedName", p.exportedName ?: JSONObject.NULL)
        o.put("createdAt", p.createdAt); o.put("updatedAt", p.updatedAt)
        val s = JSONObject()
        val st = p.style
        s.put("color", st.color); s.put("baseColor", st.baseColor)
        s.put("outlineColor", st.outlineColor); s.put("size", st.size)
        s.put("font", st.font); s.put("effect", st.effect)
        s.put("fade", st.fade); s.put("twoLine", st.twoLine)
        s.put("countdown", st.countdown); s.put("progressBar", st.progressBar)
        s.put("duet", st.duet); s.put("duetColor", st.duetColor)
        s.put("ratio", st.ratio); s.put("quality", st.quality)
        s.put("guidePct", st.guidePct)
        o.put("style", s)
        val b = JSONObject()
        b.put("type", p.bg.type); b.put("value", p.bg.value)
        o.put("bg", b)
        return o
    }

    private fun fromJson(o: JSONObject): Project {
        val p = Project(
            id = o.getString("id"),
            title = o.optString("title", "Bài chưa đặt tên"),
            sourceType = o.optString("sourceType", "file"),
            sourceRef = o.optString("sourceRef", ""),
            durationSec = o.optDouble("durationSec", 0.0),
            beatMode = o.optString("beatMode", "none"),
            beatReady = o.optBoolean("beatReady", false),
            lyricsState = o.optString("lyricsState", "none"),
            exportedUri = if (o.isNull("exportedUri")) null else o.optString("exportedUri"),
            exportedName = if (o.isNull("exportedName")) null else o.optString("exportedName"),
            createdAt = o.optLong("createdAt", 0),
            updatedAt = o.optLong("updatedAt", 0)
        )
        o.optJSONObject("style")?.let { s ->
            val st = p.style
            st.color = s.optString("color", st.color)
            st.baseColor = s.optString("baseColor", st.baseColor)
            st.outlineColor = s.optString("outlineColor", st.outlineColor)
            st.size = s.optInt("size", st.size)
            st.font = s.optString("font", st.font)
            st.effect = s.optString("effect", st.effect)
            st.fade = s.optBoolean("fade", st.fade)
            st.twoLine = s.optBoolean("twoLine", st.twoLine)
            st.countdown = s.optBoolean("countdown", st.countdown)
            st.progressBar = s.optBoolean("progressBar", st.progressBar)
            st.duet = s.optBoolean("duet", st.duet)
            st.duetColor = s.optString("duetColor", st.duetColor)
            st.ratio = s.optString("ratio", st.ratio)
            st.quality = s.optString("quality", st.quality)
            st.guidePct = s.optInt("guidePct", st.guidePct)
        }
        o.optJSONObject("bg")?.let { b ->
            p.bg.type = b.optString("type", p.bg.type)
            p.bg.value = b.optString("value", p.bg.value)
        }
        return p
    }
}
