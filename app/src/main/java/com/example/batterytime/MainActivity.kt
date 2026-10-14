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
    private val percentHistory = ArrayList<Pair<Long, Int>>()
    private var designCapacityUAh = 5_000_000L
    private var lastPercent = -1

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

    // ─── Đọc dung lượng pin thiết kế từ sysfs ───
    private fun readDesignCapacity(): Long {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/BAT0/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/battery/energy_full"
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

    // ─── TẦNG 3: Đọc dòng sạc trực tiếp từ sysfs ───
    private fun readCurrentFromSysfs(): Long {
        val paths = listOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/battery/current_avg",
            "/sys/class/power_supply/battery/BatteryAverageCurrent",
            "/sys/class/power_supply/BAT0/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/max170xx_battery/current_now",
            "/sys/class/power_supply/smb135x_battery/current_now"
        )
        for (p in paths) {
            try {
                val f = File(p)
                if (!f.canRead()) continue
                val raw = f.readText().trim().toLongOrNull() ?: continue
                val abs = if (raw < 0) -raw else raw
                // Chuẩn hoá về µA
                if (abs in 100_000L..15_000_000L) return abs       // đã là µA
                if (abs in 100L..15_000L) return abs * 1000L       // mA → µA
                val div = abs / 1000L
                if (div in 100_000L..15_000_000L) return div       // chia 1000
            } catch (_: Exception) {}
        }
        return 0L
    }

    // ─── TẦNG 4: Ước tính dòng sạc dựa trên công suất củ sạc ───
    private fun estimateCurrentFromCharger(percent: Int): Long {
        // Thử đọc công suất củ sạc từ sysfs
        val pairs = listOf(
            Pair("/sys/class/power_supply/usb/current_max", "/sys/class/power_supply/usb/voltage_max"),
            Pair("/sys/class/power_supply/ac/current_max", "/sys/class/power_supply/ac/voltage_max"),
            Pair("/sys/class/power_supply/usb/current_max", "/sys/class/power_supply/usb/voltage_now"),
            Pair("/sys/class/power_supply/main/current_max", "/sys/class/power_supply/main/voltage_max")
        )
        var watts = 0.0
        for ((cp, vp) in pairs) {
            try {
                val cRaw = File(cp).readText().trim().toLongOrNull() ?: continue
                val vRaw = File(vp).readText().trim().toLongOrNull() ?: continue
                // current_max thường ở µA, voltage ở µV
                val currentA = when {
                    cRaw > 1_000_000 -> cRaw / 1_000_000.0  // µA → A
                    cRaw > 1_000     -> cRaw / 1_000.0      // mA → A
                    else             -> cRaw.toDouble()
                }
                val voltageV = when {
                    vRaw > 1_000_000 -> vRaw / 1_000_000.0
                    vRaw > 1_000     -> vRaw / 1_000.0
                    else             -> vRaw.toDouble()
                }
                val w = currentA * voltageV
                if (w in 2.0..200.0) { watts = w; break }
            } catch (_: Exception) {}
        }

        // Nếu không đọc được → dùng ước tính theo % pin (sạc nhanh giảm dần)
        val estimatedWatts = if (watts > 0) watts else when {
            percent < 20 -> 18.0   // sạc nhanh
            percent < 50 -> 15.0
            percent < 80 -> 10.0
            else         -> 6.0    // gần đầy, dòng tụt
        }

        // Hiệu suất sạc ~85%, điện áp pin ~3.85V
        val efficiency = 0.85
        val batteryVoltage = 3.85
        val ua = (estimatedWatts * efficiency / batteryVoltage * 1_000_000).toLong()
        return ua
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

        // Ghi lịch sử % để tinh chỉnh
        val now = System.currentTimeMillis()
        if (percent != lastPercent) {
            percentHistory.add(now to percent)
            lastPercent = percent
            if (percentHistory.size > 30) percentHistory.removeAt(0)
        }

        // ─── TẦNG 1: API chính thức Android ───
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val t = bm.computeChargeTimeRemaining()
            if (t > 0) {
                tvTime.text = formatTime((t / 60000).toInt())
                tvCurrent.text = "⚡ Ước tính bởi hệ thống Android"
                return
            }
        }

        // ─── TẦNG 2: BatteryManager properties ───
        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val avgUa = normalizeToUa(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
        val nowUa = normalizeToUa(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))

        var effectiveUa = if (avgUa > 100_000L) avgUa else nowUa
        var source = "hệ thống"

        // ─── TẦNG 3: sysfs ───
        if (effectiveUa < 100_000L) {
            val sysUa = readCurrentFromSysfs()
            if (sysUa > 100_000L) { effectiveUa = sysUa; source = "sysfs" }
        }

        // Lưu mẫu để làm mượt
        if (effectiveUa in 100_000L..8_000_000L) {
            currentSamples.add(effectiveUa)
            if (currentSamples.size > 8) currentSamples.removeAt(0)
            val sorted = currentSamples.sorted()
            val trim = sorted.size / 4
            val trimmed = if (sorted.size > 4) sorted.subList(trim, sorted.size - trim) else sorted
            effectiveUa = trimmed.average().toLong()
        }

        // ─── TẦNG 4: Ước tính từ công suất củ sạc ───
        if (effectiveUa < 100_000L) {
            effectiveUa = estimateCurrentFromCharger(percent)
            source = "ước tính"
        }

        if (percent !in 1..99) {
            tvTime.text = "Đang cập nhật..."
            tvCurrent.text = ""
            return
        }

        // Tính thời gian còn lại
        val remainingUah = if (chargeCounter > 100_000) {
            chargeCounter.toLong() * (100L - percent) / percent
        } else {
            designCapacityUAh * (100L - percent) / 100L
        }

        var mins = (remainingUah.toDouble() / effectiveUa.toDouble() * 60.0).toInt()

        // Tinh chỉnh bằng tốc độ % tăng (nếu đã có đủ mẫu)
        if (percentHistory.size >= 3) {
            val first = percentHistory.first()
            val last = percentHistory.last()
            val dP = last.second - first.second
            val dT = (last.first - first.first) / 60_000.0
            if (dP >= 2 && dT >= 0.5) {
                val rate = dP / dT // %/phút
                val refined = ((100 - percent) / rate).toInt()
                if (refined in 1..900) mins = refined
            }
        }

        tvTime.text = formatTime(mins)

        tvCurrent.text = when (source) {
            "hệ thống" -> String.format(Locale.US, "Dòng TB: %.2f A (hệ thống)", effectiveUa / 1_000_000.0)
            "sysfs"    -> String.format(Locale.US, "Dòng TB: %.2f A (sysfs)", effectiveUa / 1_000_000.0)
            else       -> String.format(Locale.US, "Dòng ~%.2f A (ước tính)", effectiveUa / 1_000_000.0)
        }
    }

    private fun normalizeToUa(raw: Int): Long {
        if (raw <= 0) return 0L
        val abs = raw.toLong()
        if (abs in 100_000L..15_000_000L) return abs
        if (abs in 100L..15_000L) return abs * 1000L
        val div = abs / 1000L
        if (div in 100_000L..15_000_000L) return div
        return 0L
    }

    private fun formatTime(minutes: Int): String {
        if (minutes < 0) return "Đang tính toán..."
        if (minutes == 0) return "Còn dưới 1 phút nữa"
        if (minutes > 1440) return "Đang tính toán..."
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "Còn khoảng $h giờ $m phút nữa đầy"
            h > 0 -> "Còn khoảng $h giờ nữa đầy"
            else -> "Còn khoảng $m phút nữa đầy"
        }
    }
}
