package org.offgrid.mesh

import org.junit.Assert.*
import org.junit.Test

class PowerProfileManagerTest {

    @Test
    fun testEmergencyOutboxForcesUrgentScan() {
        val interval = PowerProfileManager.computeScanInterval(
            batteryPercent = 10, // Even on low battery
            isCharging = false,
            powerMode = RadioMeshManager.POWER_NORMAL,
            hasEmergencyOutbox = true
        )
        assertEquals(PowerProfileManager.EMERGENCY_SCAN_INTERVAL_MS, interval)
    }

    @Test
    fun testChargingPrioritizesFastDiscovery() {
        val interval = PowerProfileManager.computeScanInterval(
            batteryPercent = 50,
            isCharging = true,
            powerMode = RadioMeshManager.POWER_NORMAL,
            hasEmergencyOutbox = false
        )
        assertEquals(PowerProfileManager.RELAY_SCAN_INTERVAL_MS, interval)
    }

    @Test
    fun testCriticalBatteryConservation() {
        val interval = PowerProfileManager.computeScanInterval(
            batteryPercent = 12,
            isCharging = false,
            powerMode = RadioMeshManager.POWER_NORMAL,
            hasEmergencyOutbox = false
        )
        assertEquals(PowerProfileManager.CRITICAL_BATTERY_SCAN_INTERVAL_MS, interval)
    }

    @Test
    fun testNormalBatteryOperation() {
        val normalInterval = PowerProfileManager.computeScanInterval(
            batteryPercent = 85,
            isCharging = false,
            powerMode = RadioMeshManager.POWER_NORMAL,
            hasEmergencyOutbox = false
        )
        assertEquals(PowerProfileManager.DEFAULT_SCAN_INTERVAL_MS, normalInterval)

        val relayInterval = PowerProfileManager.computeScanInterval(
            batteryPercent = 85,
            isCharging = false,
            powerMode = RadioMeshManager.POWER_RELAY,
            hasEmergencyOutbox = false
        )
        assertEquals(PowerProfileManager.RELAY_SCAN_INTERVAL_MS, relayInterval)
    }

    @Test
    fun testLowPowerStateDetection() {
        assertTrue(PowerProfileManager.isLowPowerState(20, false))
        assertFalse(PowerProfileManager.isLowPowerState(20, true)) // Charging is not low power danger
        assertFalse(PowerProfileManager.isLowPowerState(80, false))
    }
}
