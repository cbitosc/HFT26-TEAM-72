package org.offgrid.mesh

/**
 * Adaptive power and battery duty-cycle optimizer for disaster-resilient off-grid communications.
 * Dynamically modulates radio scanning cadence and peer polling based on remaining battery level,
 * power charging status, and outbox emergency urgency.
 */
object PowerProfileManager {
    const val DEFAULT_SCAN_INTERVAL_MS = 60_000L
    const val RELAY_SCAN_INTERVAL_MS = 30_000L
    const val EMERGENCY_SCAN_INTERVAL_MS = 15_000L
    const val CRITICAL_BATTERY_SCAN_INTERVAL_MS = 120_000L

    const val BATTERY_CRITICAL_THRESHOLD = 15
    const val BATTERY_LOW_THRESHOLD = 30

    /**
     * Determines the optimal radio discovery scan interval (in milliseconds)
     * based on current device conditions.
     */
    fun computeScanInterval(
        batteryPercent: Int,
        isCharging: Boolean,
        powerMode: String,
        hasEmergencyOutbox: Boolean
    ): Long {
        // High priority SOS or Medical alerts always force urgent scanning
        if (hasEmergencyOutbox) {
            return EMERGENCY_SCAN_INTERVAL_MS
        }

        // When plugged into power source, prioritize fastest discovery
        if (isCharging) {
            return if (powerMode == RadioMeshManager.POWER_NORMAL) RELAY_SCAN_INTERVAL_MS else EMERGENCY_SCAN_INTERVAL_MS
        }

        // Critical battery conservation (< 15%)
        if (batteryPercent in 1..BATTERY_CRITICAL_THRESHOLD) {
            return CRITICAL_BATTERY_SCAN_INTERVAL_MS
        }

        // Low battery conservation (15% - 30%)
        if (batteryPercent in (BATTERY_CRITICAL_THRESHOLD + 1)..BATTERY_LOW_THRESHOLD) {
            return when (powerMode) {
                RadioMeshManager.POWER_EMERGENCY -> RELAY_SCAN_INTERVAL_MS
                else -> DEFAULT_SCAN_INTERVAL_MS
            }
        }

        // Normal battery operation (> 30%)
        return when (powerMode) {
            RadioMeshManager.POWER_EMERGENCY -> EMERGENCY_SCAN_INTERVAL_MS
            RadioMeshManager.POWER_RELAY -> RELAY_SCAN_INTERVAL_MS
            else -> DEFAULT_SCAN_INTERVAL_MS
        }
    }

    /**
     * Suggests a power profile based on current battery status.
     */
    fun recommendedPowerMode(batteryPercent: Int, isCharging: Boolean): String {
        if (isCharging || batteryPercent > 60) return RadioMeshManager.POWER_RELAY
        if (batteryPercent in (BATTERY_LOW_THRESHOLD + 1)..60) return RadioMeshManager.POWER_NORMAL
        return RadioMeshManager.POWER_NORMAL
    }

    /**
     * Returns true if device is in a low-power warning state.
     */
    fun isLowPowerState(batteryPercent: Int, isCharging: Boolean): Boolean {
        return !isCharging && batteryPercent in 1..BATTERY_LOW_THRESHOLD
    }
}
