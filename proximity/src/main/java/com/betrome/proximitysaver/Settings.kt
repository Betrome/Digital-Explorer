package com.betrome.proximitysaver

import android.content.Context

/** Persisted user options. The service re-reads them on every sensor event, so changes apply immediately. */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("proximity_saver", Context.MODE_PRIVATE)

    var enabled: Boolean get() = sp.getBoolean("enabled", false); set(v) = sp.edit().putBoolean("enabled", v).apply()
    // Sensitivity: 1..100 % of the largest usable threshold. 100 = same as Android's own call logic.
    var sensitivity: Int get() = sp.getInt("sensitivity", 100); set(v) = sp.edit().putInt("sensitivity", v).apply()
    var delayMs: Int get() = sp.getInt("delay_ms", 1500); set(v) = sp.edit().putInt("delay_ms", v).apply()

    /** Readings below this many cm count as covered. Never above Android's own threshold (5 cm or the sensor max),
     *  or we'd hold the wake lock while the system itself thinks the sensor is clear and nothing would happen. */
    fun thresholdCm(maxRange: Float) = maxThresholdCm(maxRange) * sensitivity / 100f

    companion object {
        fun maxThresholdCm(maxRange: Float) = minOf(maxRange, 5f)
    }
}
