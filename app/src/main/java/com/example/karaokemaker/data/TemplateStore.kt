package com.example.karaokemaker.data

import android.content.Context
import com.example.karaokemaker.core.BgSpec
import com.example.karaokemaker.core.StyleOptions
import org.json.JSONObject

/**
 * Mẫu cấu hình phong cách ("Lưu cấu hình làm mẫu" ở màn Xuất — theo khung
 * KaraStudio): lưu style + nền của một dự án thành mẫu mặc định, dự án mới
 * tạo ra sẽ kế thừa mẫu này.
 */
object TemplateStore {

    private const val PREFS = "km_template"
    private const val KEY = "style_template_json"

    fun save(context: Context, style: StyleOptions, bg: BgSpec) {
        val o = JSONObject()
        o.put("color", style.color); o.put("baseColor", style.baseColor)
        o.put("outlineColor", style.outlineColor); o.put("size", style.size)
        o.put("font", style.font); o.put("effect", style.effect)
        o.put("fade", style.fade); o.put("twoLine", style.twoLine)
        o.put("countdown", style.countdown); o.put("progressBar", style.progressBar)
        o.put("duet", style.duet); o.put("duetColor", style.duetColor)
        o.put("ratio", style.ratio); o.put("quality", style.quality)
        o.put("guidePct", style.guidePct)
        o.put("bgType", bg.type); o.put("bgValue", bg.value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, o.toString()).apply()
    }

    /** Trả về (style, bg) của mẫu đã lưu, hoặc null nếu chưa lưu mẫu nào. */
    fun load(context: Context): Pair<StyleOptions, BgSpec>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val st = StyleOptions()
            st.color = o.optString("color", st.color)
            st.baseColor = o.optString("baseColor", st.baseColor)
            st.outlineColor = o.optString("outlineColor", st.outlineColor)
            st.size = o.optInt("size", st.size)
            st.font = o.optString("font", st.font)
            st.effect = o.optString("effect", st.effect)
            st.fade = o.optBoolean("fade", st.fade)
            st.twoLine = o.optBoolean("twoLine", st.twoLine)
            st.countdown = o.optBoolean("countdown", st.countdown)
            st.progressBar = o.optBoolean("progressBar", st.progressBar)
            st.duet = o.optBoolean("duet", st.duet)
            st.duetColor = o.optString("duetColor", st.duetColor)
            st.ratio = o.optString("ratio", st.ratio)
            st.quality = o.optString("quality", st.quality)
            st.guidePct = o.optInt("guidePct", st.guidePct)
            val bg = BgSpec(
                o.optString("bgType", "gradient"),
                o.optString("bgValue", "demtim")
            )
            st to bg
        } catch (e: Exception) {
            null
        }
    }

    /** Áp mẫu đã lưu (nếu có) lên 1 dự án mới. */
    fun applyTo(context: Context, p: Project) {
        val t = load(context) ?: return
        p.style = t.first
        p.bg = t.second
    }
}
