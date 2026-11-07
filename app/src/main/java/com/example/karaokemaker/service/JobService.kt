package com.example.karaokemaker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.karaokemaker.R
import com.example.karaokemaker.data.Job
import com.example.karaokemaker.data.JobStore
import com.example.karaokemaker.data.ProjectStore
import com.example.karaokemaker.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service chạy MỌI tác vụ dài (tải model / tải bài / tách beat /
 * chép lời / xuất video / trọn gói), lần lượt từng job theo hàng đợi.
 *
 * Chống chết im lặng (bài học lớn nhất của hệ Karaoke Maker):
 *  - Có thông báo thường trực: tiến độ % + nhãn chặng + nút Huỷ.
 *  - JobStore lưu bền từng bước; service khởi động lại sẽ quét job "running"
 *    mồ côi -> "interrupted" để người dùng bấm Chạy tiếp (Pipeline bỏ qua
 *    chặng đã có file đầu ra).
 *  - Lỗi bất kỳ -> job "error" + thông báo lỗi rõ ràng, service vẫn sống và
 *    chạy job kế tiếp.
 * Loại FGS: mediaProcessing (khai trong Manifest) — đúng cho tác vụ media
 * dài trên Android 15; startForeground dùng dạng 2 tham số để hệ thống lấy
 * loại từ Manifest, tránh lệch type giữa các đời Android.
 */
class JobService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val pumping = AtomicBoolean(false)
    @Volatile
    private var currentJobId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var lastNotifUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ProjectStore.init(applicationContext)
        JobStore.init(applicationContext)
        JobStore.sweepInterruptedOnServiceStart()
        createChannel()
        JobStore.addListener { onStoreChanged() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_JOB_ID)?.let { cancelJob(it) }
            else -> pump()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            wakeLock?.release()
        } catch (e: Exception) {
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---------------- hàng đợi ----------------

    private fun pump() {
        if (!pumping.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    val job = JobStore.nextQueued() ?: break
                    runOne(job)
                }
            } finally {
                pumping.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun runOne(job: Job) {
        currentJobId = job.id
        JobStore.update(job.id) {
            it.status = "running"
            it.error = null
            if (it.label.isEmpty()) it.label = "đang bắt đầu"
        }
        acquireWakeLock()
        showProgressNotification(job.id)
        try {
            Pipeline.runJob(applicationContext, job)
            val now = JobStore.get(job.id)
            if (now?.status == "canceled") {
                // giữ nguyên trạng thái huỷ do người dùng đặt
            } else {
                JobStore.update(job.id) {
                    it.status = "done"
                    it.progress = 100.0
                    it.label = "xong"
                }
                notifyFinished(job, error = null)
            }
        } catch (e: Pipeline.Cancelled) {
            JobStore.update(job.id) {
                it.status = "canceled"
                it.label = "đã huỷ"
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            JobStore.update(job.id) {
                if (it.status != "canceled") {
                    it.status = "error"
                    it.error = msg
                    it.label = "lỗi"
                }
            }
            if (JobStore.get(job.id)?.status == "error") notifyFinished(job, error = msg)
        } finally {
            currentJobId = null
        }
    }

    private fun cancelJob(jobId: String) {
        val j = JobStore.get(jobId) ?: return
        if (!j.isActive()) return
        JobStore.update(jobId) {
            it.status = "canceled"
            it.label = "đã huỷ"
        }
        if (jobId == currentJobId) {
            // Cắt ngay các chặng có thể cắt: ffmpeg đang chạy; các chặng khác
            // tự dừng ở điểm kiểm tra trạng thái kế tiếp (MDX/Whisper/Pipeline).
            com.example.karaokemaker.audio.Ffmpeg.cancelCurrent()
        }
        pump() // nếu còn job queued khác thì chạy tiếp
    }

    // ---------------- thông báo ----------------

    private fun onStoreChanged() {
        val id = currentJobId ?: return
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate < 600) return
        lastNotifUpdate = now
        showProgressNotification(id)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_jobs),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = getString(R.string.notif_channel_desc)
            mgr.createNotificationChannel(ch)
        }
    }

    private fun projectTitle(job: Job): String =
        ProjectStore.get(job.projectId)?.title ?: job.typeLabel()

    private fun buildProgressNotification(job: Job): Notification {
        val pct = job.progress.toInt()
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, job.id.hashCode(),
            Intent(this, JobService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, job.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("${job.typeLabel()} · ${projectTitle(job)}")
            .setContentText("${job.label} — $pct%")
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Huỷ", cancelIntent)
            .build()
    }

    private fun showProgressNotification(jobId: String) {
        val job = JobStore.get(jobId) ?: return
        val notif = buildProgressNotification(job)
        try {
            startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            // Một số máy/ROM gắt với FGS: vẫn tiếp tục chạy job, UI trong app
            // vẫn hiện tiến độ từ JobStore — không để lỗi thông báo giết job.
        }
    }

    private fun notifyFinished(job: Job, error: String?) {
        val mgr = getSystemService(NotificationManager::class.java)
        val openIntent = PendingIntent.getActivity(
            this, job.id.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (error == null) {
            "✔ ${job.typeLabel()} xong: ${projectTitle(job)}"
        } else {
            "✖ ${job.typeLabel()} lỗi: ${projectTitle(job)}"
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(error ?: "Mở app để xem kết quả")
            .setStyle(NotificationCompat.BigTextStyle().bigText(error ?: "Mở app để xem kết quả"))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        mgr.notify(job.id.hashCode(), notif)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "KaraokeMaker:job"
            ).apply { acquire(60 * 60 * 1000L) } // tối đa 1 giờ / lượt, job sau tự gia hạn khi bắt đầu
        } catch (e: Exception) {
            // không có WakeLock vẫn chạy được — bỏ qua
        }
    }

    companion object {
        const val CHANNEL_ID = "km_jobs"
        const val NOTIF_ID = 1001
        const val ACTION_CANCEL = "com.example.karaokemaker.CANCEL_JOB"
        const val EXTRA_JOB_ID = "job_id"

        /** Tạo job mới + đánh thức service. Trả về job đã tạo. */
        fun enqueue(context: Context, projectId: String, type: String, payload: String = "{}"): Job {
            val job = JobStore.enqueue(projectId, type, payload)
            start(context)
            return job
        }

        fun start(context: Context) {
            val intent = Intent(context, JobService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, jobId: String) {
            val intent = Intent(context, JobService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Chạy tiếp job bị gián đoạn/lỗi: đặt lại queued + đánh thức service. */
        fun resume(context: Context, jobId: String) {
            JobStore.update(jobId) {
                it.status = "queued"
                it.error = null
                it.label = "đang chờ chạy tiếp"
            }
            start(context)
        }
    }
}
