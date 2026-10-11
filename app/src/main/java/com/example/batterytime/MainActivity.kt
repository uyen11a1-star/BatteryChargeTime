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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCurrent: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastIntent: Intent? = null

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

        tvStatus.text = when {
            isFull -> "Đầy pin 🎉"
            isCharging -> "Đang sạc ⚡"
            else -> "Đang dùng pin 🔋"
        }
        tvPercent.text = "$percent%"

        // Ưu tiên sử dụng API chính thức của Android (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val timeRemaining = bm.computeChargeTimeRemaining()

            if (timeRemaining > 0) {
                val minutes = (timeRemaining / 60000).toInt()
                tvTime.text = formatTime(minutes)
                tvCurrent.text = "Ước tính bởi hệ thống Android"
            } else {
                // API trả về -1: chưa đủ dữ liệu hoặc pin đang xả
                if (!isCharging) {
                    tvTime.text = "Cắm sạc để xem thời gian đầy pin"
                } else {
                    tvTime.text = "Đang lấy dữ liệu từ hệ thống..."
                }
                tvCurrent.text = ""
            }
        } else {
            // Với các máy Android cũ hơn, không có API này
            tvTime.text = "Thiết bị không hỗ trợ ước tính thời gian sạc"
            tvCurrent.text = ""
        }
    }

    private fun formatTime(minutes: Int): String {
        if (minutes < 0 || minutes > 1440) {
            return "Đang tính toán..."
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
