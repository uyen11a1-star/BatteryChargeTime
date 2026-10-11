package com.example.batterytime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvPercent: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvCurrent: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { update(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus  = findViewById(R.id.tvStatus)
        tvPercent = findViewById(R.id.tvPercent)
        tvTime    = findViewById(R.id.tvTime)
        tvCurrent = findViewById(R.id.tvCurrent)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
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
        val currentNow    = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        tvStatus.text = when {
            isFull -> "Đầy pin 🎉"
            isCharging -> "Đang sạc ⚡"
            else -> "Đang dùng pin 🔋"
        }
        tvPercent.text = "$percent%"

        if (isCharging && percent in 1..99 && chargeCounter > 0) {
            val fullCapacity = chargeCounter.toLong() * 100L / percent
            val remainingUAh = fullCapacity - chargeCounter
            val currentAbs = kotlin.math.abs(currentNow).toLong()

            if (remainingUAh > 0 && currentAbs > 1000) {
                val hours = remainingUAh.toDouble() / currentAbs.toDouble()
                val totalMin = (hours * 60).toInt()
                val h = totalMin / 60
                val m = totalMin % 60
                tvTime.text = when {
                    h > 0 -> "Còn khoảng ${h} giờ ${m} phút nữa đầy"
                    m > 0 -> "Còn khoảng ${m} phút nữa đầy"
                    else -> "Sắp đầy rồi!"
                }
            } else {
                tvTime.text = "Đang tính toán..."
            }
        } else if (isFull || percent >= 100) {
            tvTime.text = "Pin đã đầy 🎉"
        } else {
            tvTime.text = "Cắm sạc để xem thời gian đầy pin"
        }

        tvCurrent.text = String.format(Locale.US, "Dòng: %.0f mA", currentNow / 1000.0)
    }
}
