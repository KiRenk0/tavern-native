package app.tavern.nativeclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicReference

internal class GenerationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_GENERATION -> {
                stopHandler.get()?.invoke()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE, null -> {
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Tavern Native 正在生成" }
                val status = intent?.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "正在后台处理生成任务…" }
                val kind = intent?.getStringExtra(EXTRA_TASK_KIND).orEmpty().ifBlank { KIND_LLM }
                val notification = buildNotification(title, status, kind)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台生成任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持长文本、图像或视频生成在后台稳定运行，并支持快速停止。"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, status: String, kind: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, GenerationForegroundService::class.java).apply {
            action = ACTION_STOP_GENERATION
        }
        val stopPending = PendingIntent.getService(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subText = when (kind) {
            KIND_IMAGE -> "图像生成"
            KIND_VIDEO -> "视频生成"
            else -> "对话生成"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSubText(subText)
            .setSmallIcon(R.drawable.ic_tavern)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(contentPending)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "停止生成",
                    stopPending,
                ).build(),
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "tavern_generation"
        const val NOTIFICATION_ID = 4108
        const val ACTION_START = "app.tavern.nativeclient.action.GENERATION_START"
        const val ACTION_UPDATE = "app.tavern.nativeclient.action.GENERATION_UPDATE"
        const val ACTION_STOP_SERVICE = "app.tavern.nativeclient.action.GENERATION_STOP_SERVICE"
        const val ACTION_STOP_GENERATION = "app.tavern.nativeclient.action.GENERATION_ABORT"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_TASK_KIND = "extra_task_kind"
        const val KIND_LLM = "llm"
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"

        private val stopHandler = AtomicReference<(() -> Unit)?>(null)

        fun registerStopHandler(handler: () -> Unit) {
            stopHandler.set(handler)
        }

        fun unregisterStopHandler() {
            stopHandler.set(null)
        }

        fun startOrUpdate(context: Context, title: String, status: String, kind: String = KIND_LLM) {
            runCatching {
                val intent = Intent(context, GenerationForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_STATUS, status)
                    putExtra(EXTRA_TASK_KIND, kind)
                }
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, GenerationForegroundService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            }
        }
    }
}
