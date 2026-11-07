package com.example.oxycaretrainer

import android.content.Context

data class AppSettings(val totalMinutes: Int, val phaseMinutes: Int, val lowSpo2: Int) {
    companion object {
        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences("training_settings", Context.MODE_PRIVATE)
            return AppSettings(p.getInt("total", 60), p.getInt("phase", 5), p.getInt("low", 85))
        }
        fun save(context: Context, value: AppSettings) {
            context.getSharedPreferences("training_settings", Context.MODE_PRIVATE).edit()
                .putInt("total", value.totalMinutes).putInt("phase", value.phaseMinutes)
                .putInt("low", value.lowSpo2).apply()
        }
    }
}
