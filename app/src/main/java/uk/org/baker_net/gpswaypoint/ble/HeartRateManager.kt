package uk.org.baker_net.gpswaypoint.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * HeartRateManager.kt
 *
 * Manages Bluetooth Low Energy scanning and connection to a heart-rate monitor
 * that implements the standard BLE Heart Rate Service (UUID 0x180D).
 *
 * Scanning begins automatically when [startScan] is called and stops after the
 * first compatible peripheral is found.  The connection is maintained until
 * [disconnect] is called or the object is garbage-collected.
 *
 * Heart-rate readings are delivered via [HeartRateCallback].
 *
 * Thread-safety: BLE callbacks arrive on the main thread (or a Binder thread
 * for scan callbacks); all public methods may be called from any thread.
 */
class HeartRateManager(
    private val context: Context,
    private val callback: HeartRateCallback
) {

    // -------------------------------------------------------------------------
    // BLE UUIDs for Heart Rate Service (0x180D) and Measurement Characteristic
    // (0x2A37) as defined in the Bluetooth GATT specification.
    // -------------------------------------------------------------------------
    companion object {
        private const val TAG = "HeartRateManager"

        /** Standard BLE Heart Rate Service UUID. */
        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")

        /** Standard BLE Heart Rate Measurement Characteristic UUID. */
        val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")

        /** Client Characteristic Configuration Descriptor – enables notifications. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** BLE scan duration in milliseconds (30 s). */
        const val SCAN_TIMEOUT_MS = 30_000L
    }

    /** Callback interface implemented by the caller to receive heart-rate events. */
    interface HeartRateCallback {
        /**
         * Called when a new heart-rate measurement is received.
         * @param bpm Heart rate in beats per minute.
         */
        fun onHeartRate(bpm: Int)

        /**
         * Called when the BLE peripheral connects or disconnects.
         * @param connected true if just connected, false if disconnected.
         */
        fun onConnectionStateChanged(connected: Boolean, name: String?)
    }

    /**
     * Callback interface implemented by the preferences screen to receive
     * devices as they are found during a non-connecting [startDiscovery] scan.
     */
    interface DiscoveryCallback {
        /**
         * Called once for each distinct BLE Heart Rate Service peripheral found.
         * @param address BLE MAC address of the device, used to identify it later.
         * @param name Advertised device name, or null if unavailable.
         */
        fun onDeviceFound(address: String, name: String?)
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var deviceName: String = ""

    private var discoveryScanner: BluetoothLeScanner? = null
    private var discoveryScanCallback: ScanCallback? = null
    private val discoveredAddresses = mutableSetOf<String>()

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    /**
     * Starts a BLE scan looking for peripherals advertising the Heart Rate Service.
     * Scanning stops automatically when a matching device is found or after
     * [SCAN_TIMEOUT_MS].
     *
     * Preconditions:
     *   - BLUETOOTH_SCAN permission must be granted (Android 12+).
     *   - Bluetooth must be enabled on the device.
     *
     * Input:  @param targetAddress If non-null, only a peripheral whose BLE MAC
     *         address matches exactly will be connected to, other devices found
     *         are ignored and scanning continues. If null, the first compatible peripheral
     *         found is used.
     * Output: none – results delivered via [HeartRateCallback].
     */
    fun startScan(targetAddress: String? = null) {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Missing BLE permission, scan aborted")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            return
        }

        scanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (targetAddress != null && result.device.address != targetAddress) {
                    // Not the monitor the user selected in preferences; keep scanning.
                    return
                }
                Log.d(TAG, "Found HR device: ${result.device.address}")
                deviceName = result.device.name
                stopScan()
                connectToDevice(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed, code=$errorCode")
            }
        }
        scanCallback = cb

        @Suppress("MissingPermission")
        scanner?.startScan(listOf(filter), settings, cb)
        Log.d(TAG, "BLE scan started" + (targetAddress?.let { " (target=$it)" } ?: ""))
    }

    /**
     * Stops an in-progress BLE scan.
     *
     * Input:  none
     * Output: none
     */
    fun stopScan() {
        if (!hasBluetoothPermission()) return
        @Suppress("MissingPermission")
        scanner?.stopScan(scanCallback)
        scanCallback = null
    }

    /**
     * Starts a continuous BLE scan for the preferences screen: every distinct
     * peripheral advertising the Heart Rate Service is reported via
     * [DiscoveryCallback.onDeviceFound] as it is found. Unlike [startScan],
     * this never connects to a device and never stops on its own – the caller
     * must call [stopDiscovery] (e.g. when the preferences screen closes).
     *
     * Preconditions:
     *   - BLUETOOTH_SCAN permission must be granted (Android 12+).
     *   - Bluetooth must be enabled on the device.
     *
     * Input:  @param callback Receives each newly discovered device.
     * Output: none – results delivered via [callback] as they arrive.
     */
    fun startDiscovery(callback: DiscoveryCallback) {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Missing BLE permission, discovery aborted")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            return
        }

        discoveredAddresses.clear()
        discoveryScanner = adapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            @Suppress("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                if (discoveredAddresses.add(address)) {
                    val name = result.device.name ?: result.scanRecord?.deviceName
                    Log.d(TAG, "Discovery found HR device: $address ($name)")
                    callback.onDeviceFound(address, name)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE discovery scan failed, code=$errorCode")
            }
        }
        discoveryScanCallback = cb

        @Suppress("MissingPermission")
        discoveryScanner?.startScan(listOf(filter), settings, cb)
        Log.d(TAG, "BLE discovery scan started")
    }

    /**
     * Stops an in-progress discovery scan started by [startDiscovery].
     *
     * Input:  none
     * Output: none
     */
    fun stopDiscovery() {
        if (!hasBluetoothPermission()) return
        @Suppress("MissingPermission")
        discoveryScanner?.stopScan(discoveryScanCallback)
        discoveryScanCallback = null
        Log.d(TAG, "BLE discovery scan stopped")
    }

    // -------------------------------------------------------------------------
    // GATT connection
    // -------------------------------------------------------------------------

    /**
     * Initiates a GATT connection to [device] and subscribes to HR notifications.
     *
     * Input:  @param device A [BluetoothDevice] advertising the Heart Rate Service.
     * Output: none – connection events and data delivered via [HeartRateCallback].
     */
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermission()) return
        @Suppress("MissingPermission")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d(TAG, "Connecting to GATT server on ${device.address}")
    }

    /**
     * Disconnects from the current BLE peripheral and releases GATT resources.
     *
     * Input:  none
     * Output: none
     */
    fun disconnect() {
        stopScan()
        if (!hasBluetoothPermission()) return
        @Suppress("MissingPermission")
        bluetoothGatt?.apply {
            disconnect()
            close()
        }
        bluetoothGatt = null
    }

    // -------------------------------------------------------------------------
    // GATT Callback
    // -------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        /**
         * Called when the GATT connection state changes.
         * On successful connection, service discovery is started immediately.
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, starting service discovery")
                    callback.onConnectionStateChanged(true, name = deviceName)
                    if (!hasBluetoothPermission()) return
                    @Suppress("MissingPermission")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    callback.onConnectionStateChanged(false, name = null)
                }
            }
        }

        /**
         * Called after service discovery completes.
         * Locates the HR Measurement characteristic and enables notifications.
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed, status=$status")
                return
            }
            val characteristic = gatt
                .getService(HR_SERVICE_UUID)
                ?.getCharacteristic(HR_MEASUREMENT_UUID)
                ?: run { Log.e(TAG, "HR characteristic not found"); return }

            if (!hasBluetoothPermission()) return
            @Suppress("MissingPermission")
            gatt.setCharacteristicNotification(characteristic, true)

            // Write 0x0001 to CCCD to enable server-side notifications
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("MissingPermission")
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("MissingPermission", "DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("MissingPermission", "DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            Log.d(TAG, "Subscribed to HR notifications")
        }

        /**
         * Called when a notification arrives for the HR Measurement characteristic.
         * Parses the BPM value according to the GATT Heart Rate Measurement spec:
         *   bit 0 of flags byte: 0 = UINT8 format, 1 = UINT16 format.
         */
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_UUID) {
                val bpm = parseHeartRate(characteristic.value)
                Log.d(TAG, "HR notification: $bpm bpm")
                callback.onHeartRate(bpm)
            }
        }

        // Android 13+ override (same logic)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_UUID) {
                val bpm = parseHeartRate(value)
                Log.d(TAG, "HR notification (API33): $bpm bpm")
                callback.onHeartRate(bpm)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the Heart Rate Measurement characteristic value byte array.
     *
     * The first byte is a flags field; bit 0 selects the BPM encoding:
     *   0 → UINT8  (byte[1])
     *   1 → UINT16 (bytes[1..2], little-endian)
     *
     * Input:  @param data Raw characteristic bytes from the peripheral.
     * Output: @return Heart rate in beats per minute.
     */
    internal fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val flags = data[0].toInt()
        return if (flags and 0x01 == 0) {
            // UINT8
            if (data.size >= 2) data[1].toInt() and 0xFF else 0
        } else {
            // UINT16 little-endian
            if (data.size >= 3)
                (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            else 0
        }
    }

    /**
     * Checks whether the necessary BLE permission is granted for the current API level.
     *
     * Input:  none
     * Output: @return true if the app has the required BLE permission.
     */
    private fun hasBluetoothPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(context, perm) ==
                PackageManager.PERMISSION_GRANTED
    }
}
