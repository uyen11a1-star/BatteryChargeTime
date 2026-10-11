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

    private val currentSamples = ArrayList<Long>()   // µA
    private val percentSamples = ArrayList<Pair<Long, Int>>()
    private var designCapacityUAh = 5_000_000L
    private var lastCharging = false
    private var unitDetected = "?"                   // "uA" hoặc "mA"

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

    // ── TỰ NHẬN DIỆN ĐƠN VỊ ─────────────────────────
    // Android chuẩn: µA. Nhiều ROM (Xiaomi/Samsung/OPPO): mA.
    // Dấu hiệu: sạc thật thường ≥ 500 mA. Nếu raw < 10000 → chắc chắn là mA.
    private fun normalizeCurrent(raw: Int): Long {
        val abs = if (raw < 0) -raw.toLong() else raw.toLong()
        return when {
            abs < 100L      -> 0L                    // giá trị rác
            abs < 10_000L   -> { unitDetected = "mA"; abs * 1000L }   // mA → µA
            else            -> { unitDetected = "µA"; abs }            // đã là µA
        }
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

        if (isCharging != lastCharging) {
            currentSamples.clear(); percentSamples.clear(); lastCharging = isCharging
        }

        val uA = normalizeCurrent(rawCurrent)

        // Lưu mẫu dòng khi sạc (chỉ nhận giá trị hợp lý: 100mA - 8A)
        if (isCharging && uA in 100_000L..8_000_000L) {
            currentSamples.add(uA)
            if (currentSamples.size > 8) currentSamples.removeAt(0)
        }

        // Theo dõi % tăng
        val now = System.currentTimeMillis()
        if (percentSamples.isEmpty() || percentSamples.last().second != percent) {
            percentSamples.add(now to percent)
            if (percentSamples.size > 30) percentSamples.removeAt(0)
        }

        val avgCurrent = if (currentSamples.isNotEmpty()) {
            val sorted = currentSamples.sorted()
            val trim = sorted.size / 4
            val trimmed = if (sorted.size > 4) sorted.subList(trim, sorted.size - trim) else sorted
            trimmed.average().toLong()
        } else 0L

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

        tvCurrent.text = if (avgCurrent > 0)
            String.format(Locale.US, "Dòng TB: %.2f A (n=%d, %s)",
                avgCurrent / 1_000_000.0, currentSamples.size, unitDetected)
        else
            String.format(Locale.US, "Đo được: %d %s (raw=%d)",
                if (unitDetected == "mA") uA / 1000 else uA,
                if (unitDetected == "mA") "mA" else "µA", rawCurrent)
    }

    private fun estimate(percent: Int, chargeCounter: Int, avgCurrent: Long): String {
        if (percent !in 1..99) return ""

        // TẦNG 1: có dòng thật → tính chuẩn
        if (avgCurrent > 100_000L) {
            val remaining = if (chargeCounter > 100_000) {
                chargeCounter.toLong() * (100L - percent) / percent
            } else {
                designCapacityUAh * (100L - percent) / 100L
            }
            val mins = (remaining.toDouble() / avgCurrent * 60).toInt()
            if (mins in 1..900) return formatTime(mins, "Còn")
        }

        // TẦNG 2: đo tốc độ tăng % theo thời gian
        if (percentSamples.size >= 2) {
            val first = percentSamples.first()
            val last = percentSamples.last()
            val dP = last.second - first.second
            val dT = (last.first - first.first) / 60_000.0
            if (dP >= 1 && dT >= 0.5) {
                val rate = dP / dT               // %/phút
                val mins = ((100 - percent) / rate).toInt()
                if (mins in 1..900) return formatTime(mins, "~Còn")
            }
        }

        // TẦNG 3: tạm tính theo sạc chuẩn 10W (~2000 mA)
        val remaining = designCapacityUAh * (100L - percent) / 100L
        val mins = (remaining.toDouble() / 2_000_000.0 * 60).toInt()
        return if (mins in 1..900) formatTime(mins, "~Còn (tạm tính)")
        else "Đang hiệu chỉnh..."
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
