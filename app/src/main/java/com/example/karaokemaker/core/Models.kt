package com.example.karaokemaker.core

/**
 * Các kiểu dữ liệu lõi của pipeline karaoke — ánh xạ 1-1 với bản PC:
 *  - [TimedLine]  ~ dict {'start','end','text','words'} trong lyrics.py/align.py
 *  - [HeardWord]  ~ dict {'w','start','end'} của bản chép Whisper
 *  - [StyleOptions] ~ dict opts của build_ass() + bg trong media.render_video()
 */

/** Một từ kèm mốc giờ hát thật (giây). */
data class WordTime(val start: Double, val end: Double)

/** Một câu lời đã có mốc giờ; [words] có thể rỗng (chưa có giờ từng từ). */
data class TimedLine(
    val start: Double,
    val end: Double,
    val text: String,
    val words: List<WordTime> = emptyList(),
    /** Giọng của dòng theo khung KaraStudio: 0 = mặc định (xen kẽ theo song ca),
     *  1 = Nữ (màu chính), 2 = Nam (màu song ca). Phần mở rộng riêng của bản
     *  Android — bản PC chỉ có xen kẽ; giá trị 0 giữ đúng hành vi bản PC. */
    val voice: Int = 0,
    /** Dòng này đã khớp nhịp chắc chắn chưa (false = nội suy/dự đoán -> Studio
     *  hiện nhãn "Chưa khớp" màu cam như khung thiết kế). */
    val matched: Boolean = true
)

/** Một từ AI nghe được (bản chép thô trước khi đối chiếu/cắt câu). */
data class HeardWord(val w: String, val start: Double, val end: Double)

/** Một đoạn AI chép được (sau khi cắt theo khoảng lặng ở tầng engine). */
data class HeardSegment(
    val text: String,
    val start: Double,
    val end: Double,
    val words: List<HeardWord>
)

/**
 * Phong cách chữ + nền khi dựng ASS và render. Mặc định = mẫu "Vàng Cổ Điển"
 * của bản PC. Tên trường bám theo opts của build_ass() bản PC.
 */
data class StyleOptions(
    var color: String = "#FFD400",        // màu đã hát (highlight)
    var baseColor: String = "#FFFFFF",    // màu chưa hát
    var outlineColor: String = "#000000",
    var size: Int = 64,
    var font: String = "Roboto",
    var effect: String = "sweep",         // sweep | pop | zoom | type
    var fade: Boolean = true,
    var twoLine: Boolean = true,
    var countdown: Boolean = false,
    var progressBar: Boolean = false,
    var duet: Boolean = false,            // song ca 2 màu xen kẽ
    var duetColor: String = "#00E5FF",
    var ratio: String = "16:9",           // 16:9 | 9:16 | 1:1
    var quality: String = "720p",         // 720p (mặc định) | 1080p
    var guidePct: Int = 0                 // giọng dẫn 0..40%
) {
    /** Kích thước render (rộng, cao) theo tỉ lệ + chất lượng. */
    fun renderSize(): Pair<Int, Int> = when (ratio) {
        "9:16" -> if (quality == "1080p") (1080 to 1920) else (720 to 1280)
        "1:1" -> if (quality == "1080p") (1080 to 1080) else (720 to 720)
        else -> if (quality == "1080p") (1920 to 1080) else (1280 to 720)
    }

    /** Bitrate video cho h264_mediacodec theo cỡ khung (bản full LGPL không có x264). */
    fun videoBitrate(): String {
        val (w, h) = renderSize()
        val px = w * h
        return when {
            px >= 1920 * 1080 -> "10000k"
            px >= 1280 * 720 -> "6000k"
            else -> "4000k"
        }
    }
}

/** Nền video: gradient tĩnh theo mẫu, màu trơn, ảnh hoặc video của người dùng. */
data class BgSpec(
    var type: String = "gradient",        // gradient | color | image | video
    var value: String = "demtim"          // tên mẫu gradient / mã màu / đường dẫn file
)

/** Mẫu nền gradient: key -> (tên hiển thị, 3 mã màu 0x..) — bê nguyên từ media.py bản PC. */
object Gradients {
    val ALL: LinkedHashMap<String, Pair<String, List<String>>> = linkedMapOf(
        "demtim" to ("Đêm tím" to listOf("0x0d0518", "0x3d1470", "0x8b3fbf")),
        "hoanghon" to ("Hoàng hôn" to listOf("0x1d0b38", "0xb7416b", "0xf7b733")),
        "biensau" to ("Biển sâu" to listOf("0x02101f", "0x0a4d68", "0x088f8f")),
        "rungdem" to ("Rừng đêm" to listOf("0x04140a", "0x14532d", "0x3f9d4e")),
        "neon" to ("Hồng neon" to listOf("0x0f0219", "0x7b1e6e", "0xff2e88")),
        "thanhpho" to ("Đêm đô thị" to listOf("0x050914", "0x1b2a6b", "0x3d5afe")),
        "vangkim" to ("Vàng kim" to listOf("0x140b02", "0x6e4410", "0xd9a441")),
        "hongdao" to ("Hồng đào" to listOf("0x1c0714", "0x83325c", "0xf78fb3")),
        "xanhmint" to ("Xanh mint" to listOf("0x02120e", "0x0b5563", "0x2ec4b6")),
        "domlua" to ("Đốm lửa" to listOf("0x120202", "0x7a1f0d", "0xf46036"))
    )

    fun colorsOf(kind: String): List<String> = ALL[kind]?.second ?: ALL.getValue("demtim").second
    fun labelOf(kind: String): String = ALL[kind]?.first ?: "Đêm tím"
}

/** Mẫu chữ có sẵn — bê nguyên từ PRESETS của giao diện PC. */
data class StylePreset(
    val name: String, val color: String, val base: String,
    val font: String, val size: Int
)

object StylePresets {
    val ALL = listOf(
        StylePreset("Vàng Cổ Điển", "#FFD400", "#FFFFFF", "Roboto", 64),
        StylePreset("Neon Hồng", "#FF2E88", "#E8E8FF", "Roboto", 68),
        StylePreset("Ngọc Biển", "#00E5FF", "#FFFFFF", "Roboto", 64),
        StylePreset("Tím Mộng", "#B388FF", "#F3E5F5", "Roboto", 66),
        StylePreset("Đỏ Rượu", "#FF5252", "#FFE0B2", "Roboto", 68),
        StylePreset("Trắng Tối Giản", "#FFFFFF", "#9E9E9E", "Roboto", 60),
        StylePreset("Xanh Lá Tươi", "#69F0AE", "#FFFFFF", "Roboto", 64),
        StylePreset("Cam Hoàng Hôn", "#FF9100", "#FFF3E0", "Roboto", 66),
        StylePreset("Hồng Phấn", "#FF80AB", "#FFFFFF", "Roboto", 66),
        StylePreset("Xanh Đêm", "#40C4FF", "#E1F5FE", "Roboto", 62)
    )

    /** Font dùng được trong libass trên Android (font hệ thống). Tên giữ gần
     *  bản PC nhưng đổi sang font có thật trên máy Android. */
    val FONTS = listOf("Roboto", "Noto Sans", "Noto Serif", "Sans Serif", "Serif", "Monospace")
}
