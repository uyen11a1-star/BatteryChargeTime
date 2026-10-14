package com.example.batterytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
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
    private var designCapacityUAh = 5_000_000L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { i?.let { lastIntent = it } }
    }

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
        return 5_000_000L
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

        tvStatus.text = when {
            isFull -> "Đầy pin 🎉"
            isCharging -> "Đang sạc ⚡"
            else -> "Đang dùng pin 🔋"
        }
        tvPercent.text = "$percent%"

        if (isFull || percent >= 100) {
            tvTime.text = "Pin đã đầy 🎉"
            tvCurrent.text = ""
            return
        }

        if (!isCharging) {
            tvTime.text = "Cắm sạc để xem thời gian đầy pin"
            tvCurrent.text = ""
            return
        }

        // ─── TẦNG 1: API chính thức Android ───
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val timeRemaining = bm.computeChargeTimeRemaining()
            if (timeRemaining > 0) {
                val mins = (timeRemaining / 60000).toInt()
                tvTime.text = formatTime(mins)
                tvCurrent.text = "⚡ Ước tính bởi hệ thống Android"
                return
            }
        }

        // ─── TẦNG 2: Dự phòng tức thì ───
        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val avgCurrentUa  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val nowCurrentUa  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val avgUa = normalizeToUa(avgCurrentUa)
        val nowUa = normalizeToUa(nowCurrentUa)

        var effectiveUa = if (avgUa > 100_000L) avgUa else nowUa

        if (effectiveUa < 100_000L) {
            if (nowUa in 100_000L..8_000_000L) {
                currentSamples.add(nowUa)
                if (currentSamples.size > 8) currentSamples.removeAt(0)
            }
            if (currentSamples.isNotEmpty()) {
                val sorted = currentSamples.sorted()
                val trim = sorted.size / 4
                val trimmed = if (sorted.size > 4) sorted.subList(trim, sorted.size - trim) else sorted
                effectiveUa = trimmed.average().toLong()
            }
        }

        if (effectiveUa < 100_000L || percent !in 1..99) {
            tvTime.text = "Đang lấy dữ liệu..."
            tvCurrent.text = if (avgUa > 0 || nowUa > 0)
                String.format(Locale.US, "Dòng: %.2f A", (if (avgUa > 0) avgUa else nowUa) / 1_000_000.0)
            else "Máy không hỗ trợ đọc dòng sạc"
            return
        }

        val remainingUah = if (chargeCounter > 100_000) {
            chargeCounter.toLong() * (100L - percent) / percent
        } else {
            designCapacityUAh * (100L - percent) / 100L
        }

        val mins = (remainingUah.toDouble() / effectiveUa.toDouble() * 60.0).toInt()
        tvTime.text = formatTime(mins)

        tvCurrent.text = when {
            avgUa > 100_000L -> String.format(Locale.US, "Dòng TB: %.2f A (hệ thống)", avgUa / 1_000_000.0)
            currentSamples.isNotEmpty() -> String.format(Locale.US, "Dòng TB: %.2f A (n=%d)", effectiveUa / 1_000_000.0, currentSamples.size)
            else -> String.format(Locale.US, "Dòng: %.2f A", effectiveUa / 1_000_000.0)
        }
    }

    // Hàm nhận diện đơn vị dòng điện thông minh
    private fun normalizeToUa(raw: Int): Long {
        if (raw <= 0) return 0L
        val abs = raw.toLong()
        // Dải µA hợp lệ (từ 0.1A đến 15A)
        if (abs in 100_000L..15_000_000L) return abs
        // Dải mA hợp lệ (từ 0.1A đến 15A) -> chuyển sang µA
        if (abs in 100L..15_000L) return abs * 1000L
        // Giá trị lạ (như 2147.480.000) -> thử chia 1000 xem có về dải µA không
        val divided = abs / 1000L
        if (divided in 100_000L..15_000_000L) return divided
        return 0L // Bó tay, trả về 0
    }

    private fun formatTime(minutes: Int): String {
        if (minutes < 0 || minutes > 1440) return "Đang tính toán..."
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "Còn khoảng $h giờ $m phút nữa đầy"
            h > 0 -> "Còn khoảng $h giờ nữa đầy"
            m > 0 -> "Còn khoảng $m phút nữa đầy"
            else -> "Còn dưới 1 phút nữa" // Sửa thành câu này cho đỡ "sắp đầy rồi"
        }
    }
}
