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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCurrent: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastIntent: Intent? = null

    // Lịch sử đo tốc độ sạc: lưu cặp (thời điểm, phần trăm pin)
    private val percentHistory = ArrayList<Pair<Long, Int>>()
    private var lastPercent = -1
    private var lastCharging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.let { lastIntent = it }
        }
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
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (scale > 0) level * 100 / scale else -1

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || isFull

        // Reset khi bắt đầu một phiên sạc mới
        if (isCharging && !lastCharging) {
            percentHistory.clear()
            lastPercent = -1
        }
        lastCharging = isCharging

        tvStatus.text = when {
            isFull -> "Đầy pin 🎉"
            isCharging -> "Đang sạc ⚡"
            else -> "Đang dùng pin 🔋"
        }
        tvPercent.text = "$percent%"

        // Tính toán khi đang sạc và pin chưa đầy
        if (isCharging && percent in 1..99) {
            val now = System.currentTimeMillis()

            // Ghi lại lịch sử khi phần trăm thay đổi
            if (percent != lastPercent) {
                percentHistory.add(now to percent)
                lastPercent = percent

                // Giới hạn bộ nhớ đệm (giữ 50 bản ghi gần nhất)
                if (percentHistory.size > 50) {
                    percentHistory.removeAt(0)
                }
            }

            // Ước lượng thời gian dựa trên tốc độ sạc đo được
            val estimate = estimateChargingTime(percent)
            tvTime.text = estimate

            // Hiển thị tốc độ sạc thực tế đã đo được (nếu có)
            val rateText = getChargingRateText()
            tvCurrent.text = rateText

        } else if (isFull || percent >= 100) {
            tvTime.text = "Pin đã đầy 🎉"
            tvCurrent.text = ""
        } else {
            tvTime.text = "Cắm sạc để xem thời gian đầy pin"
            tvCurrent.text = ""
        }
    }

    /**
     * Ước lượng thời gian sạc còn lại dựa trên tốc độ sạc đo được từ lịch sử.
     */
    private fun estimateChargingTime(currentPercent: Int): String {
        // Cần ít nhất 2 điểm dữ liệu để tính tốc độ
        if (percentHistory.size < 2) {
            return "Đang đo tốc độ sạc..."
        }

        // Lấy điểm đầu và điểm cuối trong lịch sử (bỏ qua các điểm nhiễu)
        val validHistory = percentHistory.filter { it.second in 1..99 }
        if (validHistory.size < 2) {
            return "Đang đo tốc độ sạc..."
        }

        val first = validHistory.first()
        val last = validHistory.last()

        val deltaPercent = last.second - first.second
        val deltaTimeMs = last.first - first.first

        // Cần ít nhất 2% pin tăng và 30 giây thời gian để có kết quả đáng tin cậy
        if (deltaPercent < 2 || deltaTimeMs < 30_000) {
            return "Đang đo tốc độ sạc..."
        }

        // Tính tốc độ sạc: % pin mỗi phút
        val ratePerMinute = deltaPercent.toDouble() / (deltaTimeMs / 60_000.0)

        if (ratePerMinute <= 0.01) { // Tốc độ quá chậm, có thể đang gần đầy
            return "Pin đang sạc rất chậm (gần đầy)"
        }

        // Tính thời gian còn lại
        val remainingPercent = 100 - currentPercent
        val remainingMinutes = (remainingPercent / ratePerMinute).toInt()

        return formatTime(remainingMinutes)
    }

    /**
     * Lấy text hiển thị tốc độ sạc thực tế đã đo được.
     */
    private fun getChargingRateText(): String {
        val validHistory = percentHistory.filter { it.second in 1..99 }
        if (validHistory.size < 2) {
            return "Đang đo tốc độ sạc..."
        }

        val first = validHistory.first()
        val last = validHistory.last()
        val deltaPercent = last.second - first.second
        val deltaTimeMs = last.first - first.first

        if (deltaPercent < 1 || deltaTimeMs < 15_000) {
            return "Đang đo tốc độ sạc..."
        }

        val ratePerMinute = deltaPercent.toDouble() / (deltaTimeMs / 60_000.0)

        return String.format(Locale.US, "Tốc độ: %.2f%%/phút", ratePerMinute)
    }

    private fun formatTime(minutes: Int): String {
        if (minutes < 0 || minutes > 1440) { // > 24 giờ thì vô lý
            return "Đang đo tốc độ sạc..."
        }

        val h = minutes / 60
        val m = minutes % 60

        return when {
            h > 0 && m > 0 -> "Còn khoảng $h giờ $m phút nữa đầy"
            h > 0 -> "Còn khoảng $h giờ nữa đầy"
            m > 0 -> "Còn khoảng $m phút nữa đầy"
            else -> "Sắp đầy rồi!"
        }
    }
}
