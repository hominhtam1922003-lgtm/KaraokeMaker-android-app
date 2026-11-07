package com.example.karaokemaker

import android.app.Application
import com.example.karaokemaker.data.JobStore
import com.example.karaokemaker.data.ProjectStore
import com.example.karaokemaker.download.Youtube
import kotlin.concurrent.thread

/**
 * Điểm vào của app: nạp kho dự án + sổ job, khởi tạo bộ tải YouTube và tự
 * cập nhật yt-dlp nightly ở nền (điểm sống còn của khâu tải — xem Youtube.kt).
 * Lỗi cập nhật ở đây KHÔNG chặn app: lần tải thật sẽ thử lại và báo rõ.
 */
class KaraokeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ProjectStore.init(this)
        JobStore.init(this)
        thread(name = "ytdlp-init", isDaemon = true) {
            try {
                Youtube.init(this@KaraokeApp)
                Youtube.updateNightly(this@KaraokeApp)
            } catch (e: Exception) {
                // bỏ qua — sẽ thử lại ở chặng tải bài, lỗi khi đó hiện rõ cho user
            }
        }
    }
}
