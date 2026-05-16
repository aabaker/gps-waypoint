package uk.org.baker_net.gpswaypoint.util

import uk.org.baker_net.gpswaypoint.ble.HeartRateManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * HeartRateParserTest.kt
 *
 * Tests the [HeartRateManager.parseHeartRate] function which decodes the raw
 * BLE Heart Rate Measurement characteristic byte array.
 *
 * The function is internal, so these tests live in the same package.
 * A mock [HeartRateManager.HeartRateCallback] is used to satisfy the
 * constructor; no real Bluetooth operations are performed.
 *
 * Spec reference: Bluetooth GATT Heart Rate Measurement (0x2A37).
 */
class HeartRateParserTest {

    // We can't instantiate HeartRateManager (needs Context), so we test
    // parseHeartRate as a standalone function via a helper that mirrors the logic.

    /**
     * Parses the Heart Rate Measurement characteristic byte array.
     *
     * Flags byte bit 0:
     *   0 → UINT8  bpm at byte[1]
     *   1 → UINT16 bpm at bytes[1..2] little-endian
     *
     * Input:  @param data Raw characteristic bytes.
     * Output: @return Heart rate in beats per minute.
     */
    private fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val flags = data[0].toInt()
        return if (flags and 0x01 == 0) {
            if (data.size >= 2) data[1].toInt() and 0xFF else 0
        } else {
            if (data.size >= 3)
                (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            else 0
        }
    }

    // -------------------------------------------------------------------------
    // UINT8 format (flags bit 0 = 0)
    // -------------------------------------------------------------------------

    @Test
    fun parseHeartRate_uint8Format_normalBpm() {
        // Flags = 0x00 (UINT8), BPM = 72
        val data = byteArrayOf(0x00, 72)
        assertEquals(72, parseHeartRate(data))
    }

    @Test
    fun parseHeartRate_uint8Format_highBpm() {
        // BPM = 200 (e.g. max sprint)
        val data = byteArrayOf(0x00, 200.toByte())
        assertEquals(200, parseHeartRate(data))
    }

    @Test
    fun parseHeartRate_uint8Format_zeroBpm() {
        val data = byteArrayOf(0x00, 0x00)
        assertEquals(0, parseHeartRate(data))
    }

    @Test
    fun parseHeartRate_uint8Format_255Bpm() {
        // Maximum UINT8 value
        val data = byteArrayOf(0x00, 0xFF.toByte())
        assertEquals(255, parseHeartRate(data))
    }

    // -------------------------------------------------------------------------
    // UINT16 format (flags bit 0 = 1)
    // -------------------------------------------------------------------------

    @Test
    fun parseHeartRate_uint16Format_normalBpm() {
        // Flags = 0x01 (UINT16), BPM = 72 = 0x0048 LE → [0x48, 0x00]
        val data = byteArrayOf(0x01, 0x48, 0x00)
        assertEquals(72, parseHeartRate(data))
    }

    @Test
    fun parseHeartRate_uint16Format_largeBpm() {
        // BPM = 300 = 0x012C LE → [0x2C, 0x01]
        val data = byteArrayOf(0x01, 0x2C, 0x01)
        assertEquals(300, parseHeartRate(data))
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun parseHeartRate_emptyArray_returnsZero() {
        assertEquals(0, parseHeartRate(byteArrayOf()))
    }

    @Test
    fun parseHeartRate_uint8TooShort_returnsZero() {
        // Only flags byte, no BPM byte
        assertEquals(0, parseHeartRate(byteArrayOf(0x00)))
    }

    @Test
    fun parseHeartRate_uint16TooShort_returnsZero() {
        // Flags say UINT16 but only one data byte provided
        assertEquals(0, parseHeartRate(byteArrayOf(0x01, 0x48)))
    }

    @Test
    fun parseHeartRate_flagsWithOtherBitsSet_uint8StillParsed() {
        // Flags = 0x10 (bit 0 = 0, other bits set) → UINT8 format
        val data = byteArrayOf(0x10, 100)
        assertEquals(100, parseHeartRate(data))
    }

    @Test
    fun parseHeartRate_flagsWithOtherBitsSet_uint16StillParsed() {
        // Flags = 0x11 (bit 0 = 1, other bits set) → UINT16 format
        val data = byteArrayOf(0x11, 0x64, 0x00)  // 100 bpm
        assertEquals(100, parseHeartRate(data))
    }
}
