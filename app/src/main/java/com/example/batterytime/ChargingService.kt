package com.example.batterytime

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.Locale

class ChargingService : Service() {

    companion object {
        const val CHANNEL_ID = "charging_status"
        const val NOTIF_ID = 1001
    }

    private lateinit var bm: BatteryManager
    private var lastText = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent?.let { updateNotif(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        createChannel()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        startForeground(NOTIF_ID, buildNotif("Đang kiểm tra pin...", 0, false))
        return START_STICKY
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Trạng thái sạc pin",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiện thời gian sạc đầy trên màn hình khóa"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun updateNotif(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || isFull

        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val currentNow    = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val text = when {
            isFull || percent >= 100 -> "Pin đã đầy 🎉"
            !isCharging -> "Đang dùng pin • $percent%"
            chargeCounter > 0 && percent in 1..99 -> {
                val fullCap = chargeCounter.toLong() * 100L / percent
                val remaining = fullCap - chargeCounter
                val curAbs = kotlin.math.abs(currentNow).toLong()
                if (remaining > 0 && curAbs > 1000) {
                    val mins = (remaining.toDouble() / curAbs * 60).toInt()
                    val h = mins / 60; val m = mins % 60
                    if (h > 0) "Còn $h giờ $m phút nữa đầy • $percent%"
                    else if (m > 0) "Còn $m phút nữa đầy • $percent%"
                    else "Sắp đầy rồi • $percent%"
                } else "Đang sạc • $percent%"
            }
            else -> "Đang sạc • $percent%"
        }

        if (text != lastText) {
            lastText = text
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotif(text, percent, isCharging))
        }
    }

    private fun buildNotif(text: String, percent: Int, charging: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = if (charging) android.R.drawable.ic_lock_idle_charging
                      else android.R.drawable.ic_lock_idle_low_battery

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(if (charging) "⚡ Đang sạc pin" else "🔋 Pin")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)                                  // Không vuốt xoá được
            .setOnlyAlertOnce(true)                            // Không kêu mỗi lần cập nhật
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // ← Hiện trên lock screen
            .setShowWhen(false)
            .build()
    }
}
