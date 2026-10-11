package com.example.batterytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCurrent: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastIntent: Intent? = null

    private val currentSamples = ArrayList<Long>()
    private val percentSamples = ArrayList<Pair<Long, Int>>()
    private var designCapacityUAh = 5_000_000L
    private var lastCharging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.let { lastIntent = it }
        }
    }

    // Tự cập nhật mỗi 1 giây → số nhảy realtime
    private val ticker = object : Runnable {
        override fun run() {
            lastIntent?.let { update(it) }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        tvStatus  = findViewById(R.id.tvStatus)
        tvPercent = findViewById(R.id.tvPercent)
        tvTime    = findViewById(R.id.tvTime)
        tvCurrent = findViewById(R.id.tvCurrent)
        designCapacityUAh = readDesignCapacity()
    }

    // Đọc dung lượng pin thiết kế từ hệ thống
    private fun readDesignCapacity(): Long {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/BAT0/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/battery/charge_full"
        )
        for (p in paths) {
            try {
                val f = File(p)
                if (f.canRead()) {
                    val v = f.readText().trim().toLongOrNull()
                    if (v != null && v > 100_000) return v
                }
            } catch (_: Exception) {}
        }
        return 5_000_000L // 5000 mAh default
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacks(ticker)
    }

    private fun update(intent: Intent) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (scale > 0) level * 100 / scale else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || isFull

        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        // Reset khi đổi trạng thái sạc
        if (isCharging != lastCharging) {
            currentSamples.clear()
            percentSamples.clear()
            lastCharging = isCharging
        }

        val absCurrent = if (rawCurrent < 0) -rawCurrent.toLong() else rawCurrent.toLong()

        // Lưu mẫu dòng (chỉ khi sạc và dòng hợp lý)
        if (isCharging && absCurrent > 10_000) {
            currentSamples.add(absCurrent)
            if (currentSamples.size > 8) currentSamples.removeAt(0)
        }

        // Theo dõi % theo thời gian
        val now = System.currentTimeMillis()
        if (percentSamples.isEmpty() || percentSamples.last().second != percent) {
            percentSamples.add(now to percent)
            if (percentSamples.size > 20) percentSamples.removeAt(0)
        }

        // Trung bình có loại nhiễu (bỏ 25% cao nhất & thấp nhất)
        val avgCurrent = if (currentSamples.isNotEmpty()) {
            val sorted = currentSamples.sorted()
            val trim = sorted.size / 4
            val trimmed = if (sorted.size > 4) sorted.subList(trim, sorted.size - trim) else sorted
            trimmed.average().toLong()
        } else absCurrent

        tvStatus.text = when {
            isFull -> "Đầy pin 🎉"
            isCharging -> "Đang sạc ⚡"
            else -> "Đang dùng pin 🔋"
        }
        tvPercent.text = "$percent%"

        tvTime.text = when {
            isFull || percent >= 100 -> "Pin đã đầy 🎉"
            !isCharging -> "Cắm sạc để xem thời gian đầy pin"
            else -> estimate(percent, chargeCounter, avgCurrent)
        }

        tvCurrent.text = String.format(Locale.US, "Dòng TB: %.0f mA (n=%d)",
            avgCurrent / 1000.0, currentSamples.size)
    }

    private fun estimate(percent: Int, chargeCounter: Int, avgCurrent: Long): String {
        if (percent in 1..99) {
            // TẦNG 1: charge_counter (chính xác nhất)
            if (chargeCounter > 100_000 && avgCurrent > 50_000) {
                val fullCap = chargeCounter.toLong() * 100L / percent
                val remaining = fullCap - chargeCounter
                if (remaining > 0) {
                    val mins = (remaining.toDouble() / avgCurrent * 60).toInt()
                    if (mins in 1..600) return formatTime(mins, "Còn")
                }
            }

            // TẦNG 2: dung lượng thiết kế
            if (avgCurrent > 50_000) {
                val full = designCapacityUAh
                val current = full * percent / 100
                val remaining = full - current
                if (remaining > 0) {
                    val mins = (remaining.toDouble() / avgCurrent * 60).toInt()
                    if (mins in 1..600) return formatTime(mins, "~Còn")
                }
            }
        }

        // TẦNG 3: đo tốc độ tăng %
        if (percentSamples.size >= 3 && percent in 1..99) {
            val first = percentSamples.first()
            val last = percentSamples.last()
            val dP = last.second - first.second
            val dT = (last.first - first.first) / 60_000.0
            if (dP > 0 && dT > 1.0) {
                val rate = dP / dT
                val mins = ((100 - percent) / rate).toInt()
                if (mins in 1..600) return formatTime(mins, "~Còn")
            }
        }

        return "Đang đo dòng sạc..."
    }

    private fun formatTime(mins: Int, prefix: String): String {
        val h = mins / 60
        val m = mins % 60
        return when {
            h > 0 -> "$prefix $h giờ $m phút nữa đầy"
            m > 0 -> "$prefix $m phút nữa đầy"
            else -> "Sắp đầy rồi!"
        }
    }
}
