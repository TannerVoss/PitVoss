package com.pitvoss.app

import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.net.wifi.WifiManager
import android.widget.FrameLayout
import android.view.WindowInsets
import android.view.WindowManager
import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import android.bluetooth.BluetoothProfile
import android.net.ConnectivityManager
import android.net.LinkAddress
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.location.LocationManager
import org.json.JSONArray
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.webkit.WebSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledFuture

class MainActivity : Activity() {
    @Volatile private var transportMode = "automatic"
    private lateinit var web: WebView
    private val bleHandler = Handler(Looper.getMainLooper())
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanning = false
    private var bleGeneration = 0
    private var bleCallback: ScanCallback? = null
    private val bleResults = mutableSetOf<String>()
    private var bleCallbackCount = 0
    private var bleParseErrors = 0

    private fun bleEvent(kind: String, payload: String) {
        runOnUiThread {
            if (::web.isInitialized) web.evaluateJavascript(
                "window.receiveBle(${JSONObject.quote(kind)},${JSONObject.quote(payload)})", null
            )
        }
    }

    private fun hasBlePermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopBleScan(reportFinished: Boolean = true) {
        val wasScanning = bleScanning
        bleScanning = false
        bleGeneration++
        val callback = bleCallback
        bleCallback = null
        if (callback != null) {
            try { bleScanner?.stopScan(callback) } catch (_: SecurityException) { } catch (_: Exception) { }
        }
        if (wasScanning && reportFinished) bleEvent("finished", JSONObject().put("devices",bleResults.size).put("callbacks",bleCallbackCount).put("parseErrors",bleParseErrors).toString())
    }

    private fun beginBleScan() {
        stopBleScan(false)
        if (!hasBlePermission()) {
            val permissions = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            bleEvent("status", "Requesting Bluetooth permissions…")
            requestPermissions(permissions, 731)
            return
        }
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) { bleEvent("error", "This phone has no Bluetooth adapter"); return }
        if (!adapter.isEnabled) { bleEvent("error", "Bluetooth is turned off. Enable it and scan again."); return }
        if (Build.VERSION.SDK_INT < 31) {
            val location = getSystemService(LOCATION_SERVICE) as LocationManager
            if (!location.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                bleEvent("error", "Enable Location services to scan for BLE devices on this Android version.")
                return
            }
        }
        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) { bleEvent("error", "Android BLE scanner is unavailable"); return }
            bleScanner = scanner
            bleResults.clear()
            bleCallbackCount = 0
            bleParseErrors = 0
            val generation = bleGeneration
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!bleScanning || generation != bleGeneration) return
                    bleCallbackCount++
                    if (bleCallbackCount == 1) bleEvent("diagnostic", "Android delivered first BLE advertisement")
                    try {
                        val record = result.scanRecord
                        // Advertising name takes priority over cached Bluetooth device name.
                        // Do not access BluetoothDevice.name; that getter can throw SecurityException
                        // even when the advertisement itself is readable.
                        val name = record?.deviceName ?: ""
                        val address = result.device.address
                        bleResults.add(address)
                        val data = JSONObject()
                            .put("address", address)
                            .put("name", name)
                            .put("rssi", result.rssi)
                            .put("connectable", if (Build.VERSION.SDK_INT >= 26) result.isConnectable else true)
                        val services = JSONArray()
                        record?.serviceUuids?.forEach { services.put(it.uuid.toString()) }
                        data.put("services", services)
                        bleEvent("device", data.toString())
                    } catch (ex: Exception) {
                        bleParseErrors++
                        bleEvent("diagnostic", "Advertisement #$bleCallbackCount could not be parsed: ${ex.javaClass.simpleName}: ${ex.message}")
                    }
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }
                override fun onScanFailed(errorCode: Int) {
                    if (generation != bleGeneration) return
                    stopBleScan(false)
                    val reason = when (errorCode) {
                        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android BLE scanner registration failed"
                        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Android BLE internal error"
                        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning feature unsupported"
                        else -> "Android BLE error code $errorCode"
                    }
                    bleEvent("error", reason)
                }
            }
            bleCallback = callback
            bleScanning = true
            // No ScanFilter: scan all advertisers, not just this user's Lockhart.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build()
            bleEvent("diagnostic", "Android API ${Build.VERSION.SDK_INT}; Bluetooth enabled; BLE permissions granted; location enabled; starting unfiltered scan")
            scanner.startScan(null, settings, callback)
            bleEvent("started", "12")
            bleHandler.postDelayed({
                if (bleScanning && generation == bleGeneration) stopBleScan(true)
            }, 12000L)
        } catch (_: SecurityException) {
            stopBleScan(false); bleEvent("error", "Bluetooth permission was removed. Allow Nearby devices and try again.")
        } catch (ex: Exception) {
            stopBleScan(false)
            bleEvent("error", "Cannot start BLE scan: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }
    // Mongoose OS RPC-over-GATT transport (read-only GetCurrentIP).
    // Uses the documented RPC service, tx_ctl, data and rx_ctl characteristics.
    private val rpcServiceId = UUID.fromString("5f6d4f53-5f52-5043-5f53-56435f49445f")
    private val rpcDataId = UUID.fromString("5f6d4f53-5f52-5043-5f64-6174615f5f5f")
    private val rpcTxId = UUID.fromString("5f6d4f53-5f52-5043-5f74-785f63746c5f")
    private val rpcRxId = UUID.fromString("5f6d4f53-5f52-5043-5f72-785f63746c5f")
    // PytBoss ble.py: _mOS_DBG_SVC_ID_ and 0mOS_DBG_log___0.
    private val debugServiceId = UUID.fromString("5f6d4f53-5f44-4247-5f53-56435f49445f")
    private val debugLogId = UUID.fromString("306d4f53-5f44-4247-5f6c-6f675f5f5f30")
    private val cccdId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    @Volatile private var bleRpcReady = false
    private var provisionConnectionPending = false
    private var selectedGatt: BluetoothGatt? = null
    private var bleDebugSubscribed = false
    private var bleDebugFrameCount = 0
    private var bleConnectAttempt = 0
    private var bleRpcGeneration = 0
    private var rpcData: BluetoothGattCharacteristic? = null
    private var rpcTx: BluetoothGattCharacteristic? = null
    private var rpcRx: BluetoothGattCharacteristic? = null
    private var rpcPayload = ByteArray(0)
    private var rpcOffset = 0
    private var rpcMtu = 23
    private var rpcExpected = 0
    private val rpcReply = ByteArrayOutputStream()
    private var rpcCompleted = false
    private var rpcReadAttempts = 0
    // pmWifiProvisionV1
    private var wifiProvisioning = false
    private var wifiProvisionStep = ""
    private var wifiProvisionId = 910

    private var bleCommand = "GetCurrentIP"
    private var bleCommandId = 0
    private var bleStateTestStarted = false
    private var rpcPollCount = 0
    private var rpcReadInFlight = false
    private var rpcPollScheduled = false
    private var bleTraceStage = "idle"
    private fun bleTrace(message: String) {
        bleTraceStage = message
        setupEvent("ble_trace", message)
    }
    private val lookupRunning = AtomicBoolean(false)
    private fun setupEvent(kind: String, message: String) {
        // Config replies and credential payloads must never reach logs.
        Log.i("PitMasterControllerSetup", "$kind: $message")
        bleEvent("setup_" + kind, message)
    }

    private fun closeGattSafely(gatt: BluetoothGatt?) {
        try { gatt?.close() } catch (_: SecurityException) { }
    }
    private fun closeSelectedGatt() {
        try { selectedGatt?.disconnect() } catch (_: SecurityException) { }
        closeGattSafely(selectedGatt); selectedGatt = null
    }
    private fun selectedGattAddress(): String? = try { selectedGatt?.device?.address } catch (_: SecurityException) { null }
    private inline fun gattOperation(operation: () -> Boolean): Boolean = try { operation() }
        catch (_: SecurityException) { failBleIp("Bluetooth permission was removed. Allow Nearby devices and reconnect."); false }

    private fun gattWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        Log.d("PitMasterControllerSetup", "$bleCommand GATT write uuid=${characteristic.uuid} bytes=${bytes.size}")
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        run { characteristic.value = bytes; return try { gatt.writeCharacteristic(characteristic) } catch (_: SecurityException) { false } }
    }
    private fun failBleIp(message: String) {
        Log.w("PitMasterControllerSetup", "BLE failure method=$bleCommand stage=$wifiProvisionStep: $message")
        if (wifiProvisioning) {
            wifiProvisioning = false
            rpcPayload = ByteArray(0)
            rpcReply.reset()
            if (wifiProvisionStep == "save") {
                wifiProvisionStep = ""
                setupEvent("wifi_saved", "Controller disconnected during save. Checking whether it joined Wi-Fi…")
            } else setupEvent("wifi_error", message)
        }
        if (rpcCompleted) return
        rpcCompleted = true
        rpcReadInFlight = false
        if (bleCommand == "GetState") {
            setupEvent("ble_state_error", "$message [last BLE stage: $bleTraceStage]")
            // An unsuccessful BLE telemetry request must not block IP discovery.
            val generation = bleRpcGeneration
            bleHandler.postDelayed({
                if (generation == bleRpcGeneration && rpcCompleted) startBleIpLookup()
            }, 450L)
        } else {
            setupEvent("error", message + " Manual IP entry is still available.")
        }
    }
    private fun nextRpcChunk(gatt: BluetoothGatt) {
        val data = rpcData ?: return failBleIp("BLE RPC data characteristic missing")
        if (rpcOffset >= rpcPayload.size) {
            bleTrace("$bleCommand: all ${rpcPayload.size} request bytes written; checking rx_ctl")
            rpcPollCount = 0
            scheduleRxControlPoll(gatt)
            setupEvent("status", "$bleCommand sent over Bluetooth; waiting for reply…")
            val command = bleCommand
            val requestId = bleCommandId
            bleHandler.postDelayed({ if (!rpcCompleted && bleCommand == command && bleCommandId == requestId) failBleIp("$command timed out") }, 12000)
            return
        }
        val end = minOf(rpcPayload.size, rpcOffset + maxOf(1, rpcMtu - 3))
        val chunk = rpcPayload.copyOfRange(rpcOffset, end)
        rpcOffset = end
        bleTrace("$bleCommand: sending data chunk ${rpcOffset}/${rpcPayload.size} bytes")
        if (!gattWrite(gatt, data, chunk)) failBleIp("Unable to send BLE RPC payload")
    }
    // The Mongoose OS rx_ctl characteristic is readable even when the controller
    // does not emit a notification. Android permits only one GATT operation at a time.
    private fun scheduleRxControlPoll(gatt: BluetoothGatt) {
        if (rpcPollScheduled || rpcCompleted || rpcExpected > 0) return
        val generation = bleRpcGeneration
        val commandId = bleCommandId
        rpcPollScheduled = true
        bleHandler.postDelayed({
            rpcPollScheduled = false
            if (generation != bleRpcGeneration || commandId != bleCommandId || rpcCompleted || rpcExpected > 0) return@postDelayed
            if (rpcReadInFlight) { scheduleRxControlPoll(gatt); return@postDelayed }
            val rx = rpcRx ?: return@postDelayed
            rpcPollCount++
            if (rpcPollCount > 35) { failBleIp("$bleCommand: no response length after 35 rx_ctl polls"); return@postDelayed }
            rpcReadInFlight = true
            @Suppress("DEPRECATION")
            val started = try { gatt.readCharacteristic(rx) } catch (_: SecurityException) { failBleIp("Bluetooth permission was removed"); false }
            if (!started) {
                rpcReadInFlight = false
                bleTrace("$bleCommand: rx_ctl read busy (poll $rpcPollCount)")
                scheduleRxControlPoll(gatt)
            }
        }, 350L)
    }
    private fun acceptRxLength(gatt: BluetoothGatt, bytes: ByteArray, source: String) {
        if (bytes.size < 4) { bleTrace("$bleCommand: $source rx_ctl short read (${bytes.size} bytes)"); scheduleRxControlPoll(gatt); return }
        val length = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
        bleTrace("$bleCommand: $source rx_ctl hex=${bytes.joinToString("") { "%02x".format(it) }.take(256)} length=$length")
        if (length == 0) { scheduleRxControlPoll(gatt); return }
        if (length < 0 || length > 65536) { failBleIp("Invalid BLE RPC response length: $length"); return }
        if (rpcExpected > 0) return
        rpcExpected = length
        rpcReply.reset()
        rpcReadAttempts = 0
        val data = rpcData ?: return failBleIp("BLE data characteristic missing")
        scheduleRpcRead(gatt, data)
    }
    private fun scheduleRpcRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val generation = bleRpcGeneration
        bleHandler.postDelayed({
            if (generation != bleRpcGeneration || rpcCompleted) return@postDelayed
            val properties = characteristic.properties
            if ((properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                failBleIp("BLE RPC data characteristic is not readable (properties=0x${properties.toString(16)}). Please send this diagnostic.")
                return@postDelayed
            }
            if (rpcReadInFlight) { scheduleRpcRead(gatt, characteristic); return@postDelayed }
            rpcReadInFlight = true
            @Suppress("DEPRECATION")
            val started = try { gatt.readCharacteristic(characteristic) } catch (_: SecurityException) { failBleIp("Bluetooth permission was removed"); false }
            if (!started) {
                rpcReadInFlight = false
                rpcReadAttempts++
                if (rpcReadAttempts < 6) {
                    setupEvent("status", "BLE response read busy; retrying ($rpcReadAttempts/6)…")
                    scheduleRpcRead(gatt, characteristic)
                } else failBleIp("Android rejected BLE RPC response read after 6 retries (properties=0x${properties.toString(16)}).")
            }
        }, if (rpcReadAttempts == 0) 180L else 300L)
    }
    private fun handleRpcReply(bytes: ByteArray) {
        val raw = bytes.toString(Charsets.UTF_8).trim()
        if (bleCommand.startsWith("Config.")) {
            bleTrace("$bleCommand: received ${bytes.size} response bytes")
            handleWifiProvisionReply(raw)
            return
        }
        bleTrace("$bleCommand: received ${bytes.size} response bytes; payload=${raw.take(700)}")
        if (bleCommand == "GetState") {
            rpcCompleted = true
            val parsed = try { JSONObject(raw) } catch (_: Exception) { null }
            val valid = parsed != null && !parsed.has("error") && (parsed.has("result") || parsed.has("grillTemp"))
            setupEvent(if (valid) "ble_state" else "ble_state_error", raw.take(1200))
            val generation = bleRpcGeneration
            bleHandler.postDelayed({
                if (generation == bleRpcGeneration && rpcCompleted) startBleIpLookup()
            }, 450L)
            return
        }
        setupEvent("status", "GetCurrentIP response received; checking IP…")
        val candidate = try {
            val obj = JSONObject(raw)
            val result = obj.opt("result")
            val error = obj.opt("error")
            val fields = listOf(result, error, obj)
            val ips = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
            fields.asSequence().mapNotNull { field ->
                val value = when (field) {
                    is JSONObject -> listOf("ip", "IP", "currentIP", "address", "message", "result").mapNotNull { field.optString(it).takeIf(String::isNotBlank) }.joinToString(" ")
                    else -> field?.toString() ?: ""
                }
                ips.find(value)?.value
            }.firstOrNull()
        } catch (_: Exception) { Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}").find(raw)?.value }
        if (candidate != null && LocalNetwork.isAllowedHost(candidate) && candidate.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 } && candidate != "0.0.0.0") {
            rpcCompleted = true
            setupEvent("ip", candidate)
            // BLE telemetry was tested independently before this IP request.
        } else failBleIp("GetCurrentIP did not return a usable IPv4 address: " + raw.take(160))
    }
    private fun sendWifiRpc(method: String, params: JSONObject): Boolean {
        val gatt = selectedGatt
        val tx = rpcTx
        if (gatt == null || tx == null || rpcData == null || rpcRx == null) {
            setupEvent("wifi_error", "Bluetooth RPC is not connected. Select the grill again.")
            return false
        }
        if (!rpcCompleted) {
            setupEvent("wifi_error", "Bluetooth is busy. Wait for the current request to finish.")
            return false
        }
        bleCommand = method
        bleCommandId++
        rpcCompleted = false
        rpcExpected = 0
        rpcReadInFlight = false
        rpcPollScheduled = false
        rpcPollCount = 0
        rpcReadAttempts = 0
        rpcReply.reset()
        wifiProvisionId++
        rpcPayload = JSONObject()
            .put("id", wifiProvisionId)
            .put("method", method)
            .put("params", params)
            .toString().toByteArray(Charsets.UTF_8)
        rpcOffset = 0
        val length = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(rpcPayload.size).array()
        val generation = bleRpcGeneration
        val commandId = bleCommandId
        Log.i("PitMasterControllerSetup", "RPC start method=$method id=$wifiProvisionId bytes=${rpcPayload.size}")
        // Includes missing write callbacks, not just the response-read phase.
        bleHandler.postDelayed({
            if (generation == bleRpcGeneration && commandId == bleCommandId && !rpcCompleted)
                failBleIp("$method timed out before a complete response")
        }, 20000L)
        if (!gattWrite(gatt, tx, length)) {
            rpcCompleted = true
            setupEvent("wifi_error", "Could not start $method over Bluetooth.")
            return false
        }
        return true
    }

    private fun startWifiProvision(ssid: String, password: String) {
        if (!safetyAcknowledged()) { setupEvent("wifi_error", "Read and accept the safety information before setting up your grill"); return }
        if (priming) { setupEvent("wifi_error", "Stop priming before changing Wi-Fi settings"); return }
        if (ssid.isBlank() || password.isBlank()) {
            setupEvent("wifi_error", "Enter both a Wi-Fi network name and password.")
            return
        }
        if (wifiProvisioning) {
            setupEvent("wifi_error", "Wi-Fi setup is already running.")
            return
        }
        wifiProvisioning = true
        wifiProvisionStep = "set"
        val params = JSONObject().put(
            "config",
            JSONObject().put(
                "wifi",
                JSONObject().put(
                    "sta",
                    JSONObject()
                        .put("enable", true)
                        .put("ssid", ssid)
                        .put("pass", password)
                )
            )
        )
        setupEvent("wifi_status", "Sending Wi-Fi settings over Bluetooth…")
        if (!sendWifiRpc("Config.Set", params)) {
            wifiProvisioning = false
        }
    }

    private fun handleWifiProvisionReply(raw: String) {
        if (!wifiProvisioning) return
        val parsed = try { JSONObject(raw) } catch (_: Exception) { null }
        if (parsed == null) { failBleIp("Invalid $bleCommand response (contents suppressed)"); return }
        if (parsed.optInt("id", -1) != wifiProvisionId) {
            failBleIp("Unexpected $bleCommand response id; setup outcome unknown")
            return
        }
        val error = parsed.optJSONObject("error")
        Log.i("PitMasterControllerSetup", "RPC reply method=$bleCommand id=$wifiProvisionId errorCode=${error?.optInt("code", -1)} hasResult=${parsed.has("result")}")
        if (error != null || !parsed.has("result")) {
            // Some firmware echoes credentials in error.message: never show it.
            failBleIp("Controller did not acknowledge $bleCommand (error code ${error?.optInt("code", -1)}; details suppressed)")
            return
        }
        rpcCompleted = true
        rpcReadInFlight = false
        rpcPayload = ByteArray(0)
        if (wifiProvisionStep == "set") {
            wifiProvisionStep = "save"
            setupEvent("wifi_status", "Settings accepted. Saving configuration and rebooting controller…")
            rpcCompleted = true
            if (!sendWifiRpc("Config.Save", JSONObject().put("reboot", true))) {
                wifiProvisioning = false
            }
        } else if (wifiProvisionStep == "save") {
            wifiProvisioning = false
            wifiProvisionStep = ""
            setupEvent("wifi_saved", "Configuration acknowledged. Checking Wi-Fi connection…")
        }
    }

    private fun startBleIpLookup() {
        val gatt = selectedGatt
        val tx = rpcTx
        if (gatt == null || tx == null || rpcData == null || rpcRx == null) {
            setupEvent("error", "Bluetooth IP lookup unavailable; manual IP entry is still available.")
            return
        }
        if (!rpcCompleted) return
        bleCommand = "GetCurrentIP"
        bleCommandId++
        rpcCompleted = false
        rpcExpected = 0
        rpcReadInFlight = false
        rpcPollScheduled = false
        rpcPollCount = 0
        rpcReadAttempts = 0
        rpcReply.reset()
        rpcPayload = JSONObject().put("id", 901).put("method", "GetCurrentIP").put("params", JSONObject()).toString().toByteArray(Charsets.UTF_8)
        rpcOffset = 0
        setupEvent("status", "Bluetooth notifications enabled; retrieving Wi-Fi IP over BLE…")
        val length = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(rpcPayload.size).array()
        if (!gattWrite(gatt, tx, length)) failBleIp("Could not start Bluetooth IP request")
    }
    private fun testBleGetState() {
        val gatt = selectedGatt
        val tx = rpcTx
        if (gatt == null || tx == null || rpcData == null || rpcRx == null) {
            setupEvent("ble_state_error", "Select and connect to a smoker over Bluetooth first.")
            return
        }
        if (!rpcCompleted) {
            setupEvent("ble_state_error", "Bluetooth RPC is still busy; wait for IP discovery to finish.")
            return
        }
        bleCommand = "GetState"
        bleCommandId++
        rpcCompleted = false
        rpcExpected = 0
        rpcReadInFlight = false
        rpcPollScheduled = false
        rpcPollCount = 0
        rpcReadAttempts = 0
        rpcReply.reset()
        rpcPayload = JSONObject().put("id", 902).put("method", "GetState").put("params", JSONObject()).toString().toByteArray(Charsets.UTF_8)
        rpcOffset = 0
        setupEvent("ble_test", "Bluetooth connected · requesting GetState without Wi-Fi…")
        val length = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(rpcPayload.size).array()
        if (!gattWrite(gatt, tx, length)) failBleIp("Could not start Bluetooth GetState frame")
    }
    // Permission checked on entry; asynchronous GATT calls also handle revocation.
    @SuppressLint("MissingPermission")
    private fun selectBleAndFindIp(address: String, retry: Boolean = false) {
        if (!hasBlePermission()) { setupEvent("error", "Bluetooth permissions missing. Scan again and grant access."); return }
        bleRpcReady = false
        // Prevent scanner traffic and competing local GATT operations during setup.
        stopBleScan(false)
        bleRpcGeneration++
        val generation = bleRpcGeneration
        rpcCompleted = false
        bleDebugSubscribed = false
        bleDebugFrameCount = 0
        bleConnectAttempt = if (retry) 2 else 1
        bleCommand = "GetCurrentIP"
        bleCommandId++
        bleStateTestStarted = false
        rpcMtu = 23
        rpcExpected = 0
        rpcReadInFlight = false
        rpcPollScheduled = false
        rpcPollCount = 0
        rpcReadAttempts = 0
        rpcReply.reset()
        rpcData = null; rpcTx = null; rpcRx = null
        try {
            closeSelectedGatt()
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device = adapter.getRemoteDevice(address)
            setupEvent("status", "Connecting to selected grill over Bluetooth (attempt $bleConnectAttempt)…")
            selectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (generation != bleRpcGeneration) { closeGattSafely(gatt); return }
                    bleTrace("GATT connection status=$status newState=$newState attempt=$bleConnectAttempt")
                    if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        bleRpcReady = false
                        closeGattSafely(gatt)
                        if (selectedGatt === gatt) selectedGatt = null
                        rpcData = null; rpcTx = null; rpcRx = null
                        setupEvent("ble_link", "disconnected")
                        // Android status 133 can be transient. Retry once only during initial
                        // setup, not after an established session or on deliberate disconnect.
                        if (status == 133 && rpcData == null && bleConnectAttempt == 1) {
                            setupEvent("status", "Android Bluetooth error 133; retrying connection once…")
                            bleHandler.postDelayed({
                                if (generation == bleRpcGeneration) selectBleAndFindIp(address, true)
                            }, 1200L)
                        } else {
                            failBleIp("Bluetooth connection closed (status $status)")
                        }
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        setupEvent("status", "Bluetooth connected; discovering RPC service…")
                        setupEvent("ble_link", "connecting")
                        if (!gattOperation { gatt.discoverServices() }) failBleIp("Could not discover GATT services")
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (generation != bleRpcGeneration || rpcCompleted) return
                    bleTrace("GATT discovery status=$status services=" + gatt.services.joinToString { it.uuid.toString() })
                    val service = gatt.getService(rpcServiceId)
                    if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                        failBleIp("This grill does not expose the Mongoose OS BLE RPC service")
                        return
                    }
                    bleTrace("RPC service found; characteristics: " + service.characteristics.joinToString { "${it.uuid} props=0x${it.properties.toString(16)}" })
                    rpcData = service.getCharacteristic(rpcDataId)
                    rpcTx = service.getCharacteristic(rpcTxId)
                    rpcRx = service.getCharacteristic(rpcRxId)
                    if (rpcData == null || rpcTx == null || rpcRx == null) {
                        failBleIp("BLE RPC service is missing required characteristics")
                        return
                    }
                    setupEvent("status", "RPC service found; enabling notifications…")
                    val rx = rpcRx!!
                    if (!gattOperation { gatt.setCharacteristicNotification(rx, true) }) {
                        failBleIp("Could not enable BLE RPC notifications"); return
                    }
                    val descriptor = rx.getDescriptor(cccdId)
                    if (descriptor == null) { failBleIp("BLE RPC notification descriptor missing"); return }
                    @Suppress("DEPRECATION")
                    run {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!gattOperation { gatt.writeDescriptor(descriptor) }) failBleIp("Could not subscribe to BLE RPC responses")
                    }
                }
                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (generation != bleRpcGeneration || rpcCompleted) return
                    if (status != BluetoothGatt.GATT_SUCCESS) { failBleIp("BLE RPC subscription rejected: $status"); return }
                    if (descriptor.characteristic.uuid == rpcRxId) {
                        bleTrace("Subscribed to rx_ctl; checking PytBoss debug-log service")
                        val debug = gatt.getService(debugServiceId)?.getCharacteristic(debugLogId)
                        if (debug != null && gattOperation { gatt.setCharacteristicNotification(debug, true) }) {
                            val cccd = debug.getDescriptor(cccdId)
                            if (cccd != null) {
                                @Suppress("DEPRECATION")
                                run {
                                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    if (gattOperation { gatt.writeDescriptor(cccd) }) return
                                }
                            }
                        }
                        bleTrace("PytBoss debug-log characteristic unavailable or subscribe failed; trying IP RPC")
                    } else if (descriptor.characteristic.uuid == debugLogId) {
                        bleDebugSubscribed = true
                        setupEvent("ble_debug", "Subscribed to PytBoss BLE debug stream; awaiting FE0B/FE0C frames")
                        bleTrace("Subscribed to Mongoose debug-log notifications")
                    } else return
                    bleRpcReady = true
                    // The debug channel is independent of RPC. GetCurrentIP is a separate test.
                    bleCommand = "GetCurrentIP"
                    bleCommandId++
                    rpcCompleted = true
                    setupEvent("ble_link", "connected")
                    startBleIpLookup()
                }
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (generation != bleRpcGeneration || rpcCompleted) return
                    if (status != BluetoothGatt.GATT_SUCCESS) { failBleIp("BLE write failed: $status"); return }
                    bleTrace("$bleCommand: write confirmed for ${characteristic.uuid}")
                    if (characteristic.uuid == rpcTxId || characteristic.uuid == rpcDataId) nextRpcChunk(gatt)
                }
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (generation != bleRpcGeneration) return
                    val bytes = characteristic.value ?: return
                    if (characteristic.uuid == debugLogId) {
                        // Firmware debug output can echo provisioning credentials.
                        if (wifiProvisioning || bleCommand.startsWith("Config.")) return
                        val line = bytes.toString(Charsets.UTF_8).trim()
                        // PytBoss: <==PB: FE0B... (status), FE0C... (temperatures).
                        if (line.startsWith("<==PB:")) {
                            val body = line.substringAfter("<==PB:").trim()
                            val lengthMatch = Regex("""^(.*?)\s*\[(\d+)]$""").matchEntire(body)
                            val payload = lengthMatch?.groupValues?.get(1)?.trim() ?: body
                            val lengthValid = lengthMatch == null || payload.length == lengthMatch.groupValues[2].toIntOrNull()
                            if (lengthValid && (payload.startsWith("FE0B") || payload.startsWith("FE0C"))) {
                                bleDebugFrameCount++
                                setupEvent("ble_frame", "${payload.take(300)}")
                                if (bleDebugFrameCount <= 5 || bleDebugFrameCount % 20 == 0) bleTrace("PytBoss ${payload.take(4)} frame #$bleDebugFrameCount (${payload.length} chars)")
                            }
                        } else if (bleDebugFrameCount == 0) {
                            bleTrace("Debug notification received (${bytes.size} bytes; contents omitted)")
                        }
                        return
                    }
                    if (rpcCompleted || characteristic.uuid != rpcRxId) return
                    bleTrace("$bleCommand: rx_ctl notification (${bytes.size} bytes): ${bytes.joinToString("") { "%02x".format(it) }.take(64)}")
                    acceptRxLength(gatt, bytes, "notification")
                }
                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (generation != bleRpcGeneration || rpcCompleted) return
                    rpcReadInFlight = false
                    if (characteristic.uuid == rpcRxId) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            acceptRxLength(gatt, characteristic.value ?: ByteArray(0), "read")
                        } else {
                            bleTrace("$bleCommand: rx_ctl read status=$status; retrying")
                            scheduleRxControlPoll(gatt)
                        }
                        return
                    }
                    if (characteristic.uuid != rpcDataId) return
                    if (status != BluetoothGatt.GATT_SUCCESS) { failBleIp("BLE RPC response read failed: $status"); return }
                    bleTrace("$bleCommand: data read status=$status len=${(characteristic.value ?: ByteArray(0)).size} ${if (bleCommand.startsWith("Config.")) "contents suppressed" else "data received"}")
                    rpcReadAttempts = 0
                    val bytes = characteristic.value ?: ByteArray(0)
                    if (bytes.isEmpty()) { failBleIp("BLE RPC returned an empty response chunk"); return }
                    val remaining = rpcExpected - rpcReply.size()
                    rpcReply.write(bytes, 0, minOf(remaining, bytes.size))
                    if (rpcReply.size() >= rpcExpected) handleRpcReply(rpcReply.toByteArray())
                    else {
                        rpcReadAttempts = 0
                        scheduleRpcRead(gatt, characteristic)
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
            bleHandler.postDelayed({ if (generation == bleRpcGeneration && !bleDebugSubscribed && !rpcCompleted) failBleIp("Bluetooth setup timed out") }, 25000)
        } catch (ex: Exception) { failBleIp("Bluetooth connection failed: ${ex.message ?: ex.javaClass.simpleName}") }
    }
    private fun findControllerOnLan() {
        if (!lookupRunning.compareAndSet(false, true)) { setupEvent("status", "IP search already running…"); return }
        Thread {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val properties = cm.getLinkProperties(cm.activeNetwork)
                val addresses = properties?.linkAddresses?.filter { it.address is Inet4Address && it.prefixLength >= 24 && !it.address.isLoopbackAddress } ?: emptyList()
                if (addresses.isEmpty()) { setupEvent("error", "No /24 local IPv4 network found. Connect your phone to the smoker's Wi-Fi network."); return@Thread }
                val local = addresses.first().address.address.map { it.toInt() and 255 }
                val prefix = "${local[0]}.${local[1]}.${local[2]}."
                setupEvent("status", "Searching $prefix* for compatible Pit Boss controllers…")
                val executor = Executors.newFixedThreadPool(20)
                val found = AtomicBoolean(false)
                val futures = (1..254).filter { it != local[3] }.map { i -> executor.submit {
                    if (found.get()) return@submit
                    val candidate = prefix + i
                    if (!LocalNetwork.isAllowedHost(candidate)) return@submit
                    try {
                        val conn = URL("http://$candidate/rpc").openConnection() as HttpURLConnection
                        try {
                            conn.requestMethod = "POST"; conn.connectTimeout = 450; conn.readTimeout = 650
                            conn.doOutput = true; conn.instanceFollowRedirects = false; conn.setRequestProperty("Content-Type", "application/json")
                            conn.outputStream.use { it.write("{\"id\":1,\"method\":\"GetState\",\"params\":{}}".toByteArray()) }
                            val body = conn.inputStream.use { LocalNetwork.readBounded(it) }
                            if (body.contains("grillTemp") || body.contains("smokerActTemp") || body.contains("grillSetTemp")) {
                                if (found.compareAndSet(false, true)) setupEvent("ip", candidate)
                            }
                        } finally { conn.disconnect() }
                    } catch (_: Exception) {}
                } }
                executor.shutdown()
                executor.awaitTermination(22, TimeUnit.SECONDS)
                if (!found.get()) setupEvent("error", "No compatible controller answered GetState on this Wi-Fi subnet. You can still enter its IP manually.")
            } catch (ex: Exception) { setupEvent("error", "Network discovery failed: ${ex.message ?: ex.javaClass.simpleName}") }
            finally { lookupRunning.set(false) }
        }.start()
    }
    private val pool = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var priming = false
    private var stopTask: ScheduledFuture<*>? = null
    private var grillIp = ""
    private val ipPattern = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")

    private var wifiScanReceiver: BroadcastReceiver? = null
    private var wifiScanTimeout: Runnable? = null
    private fun finishWifiScan(message: String) {
        wifiScanTimeout?.let { bleHandler.removeCallbacks(it) }; wifiScanTimeout = null
        wifiScanReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        wifiScanReceiver = null
        val results = JSONArray()
        try {
            val manager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val networks = manager.scanResults.filter { it.SSID.isNotBlank() }
                .sortedWith(compareBy({ if (it.frequency in 2400..2500) 0 else 1 }, { -it.level }))
                .distinctBy { it.SSID }
            networks.forEach { result ->
                results.put(JSONObject().put("ssid", result.SSID)
                    .put("band", if (result.frequency in 2400..2500) "2.4 GHz" else if (result.frequency >= 5925) "6 GHz" else "5 GHz"))
            }
        } catch (_: SecurityException) { } catch (_: Exception) { }
        val args = "${JSONObject.quote(results.toString())},${JSONObject.quote(message)}"
        web.evaluateJavascript("window.receiveWifiNetworks && window.receiveWifiNetworks($args)", null)
    }
    private fun scanWifiNetworks() {
        if (wifiScanReceiver != null) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 733)
            return
        }
        val location = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!location.isProviderEnabled(LocationManager.GPS_PROVIDER) && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            finishWifiScan("Turn on Location to find nearby networks, or enter your network name."); return
        }
        val manager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (!manager.isWifiEnabled) { finishWifiScan("Turn on Wi-Fi to find nearby networks."); return }
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                        finishWifiScan(if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                            "Choose your home network. Use 2.4 GHz for your grill."
                            else "Showing recent networks. You can refresh or enter a network name.")
                    }
                }
            }
            wifiScanReceiver = receiver
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            @Suppress("DEPRECATION")
            val started = manager.startScan()
            if (!started) { finishWifiScan("Showing recent networks. You can refresh or enter a network name."); return }
            val timeout = Runnable { finishWifiScan("Showing available results. If your network is missing, enter its name.") }
            wifiScanTimeout = timeout
            bleHandler.postDelayed(timeout, 10000L)
        } catch (_: SecurityException) { finishWifiScan("Location permission was removed. You can enter a network name instead.") }
        catch (_: Exception) { finishWifiScan("Couldn’t search for networks. You can enter your network name instead.") }
    }

    private var lastDashboardBack = 0L
    private fun handleAppBack() {
        if (!::web.isInitialized) return
        web.evaluateJavascript("window.pmHandleBack ? window.pmHandleBack() : true") { handled ->
            if (handled == "true") { lastDashboardBack = 0L; return@evaluateJavascript }
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastDashboardBack != 0L && now - lastDashboardBack < 2500L) {
                lastDashboardBack = 0L
                finish()
            } else {
                lastDashboardBack = now
                web.evaluateJavascript("window.toast && window.toast('Hit back again to exit',true)", null)
            }
        }
    }
    // API 26-32 fallback; API 33+ uses OnBackInvokedDispatcher registered in onCreate.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { handleAppBack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleAppBack() }
        }
        web = WebView(this)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !request.url.toString().startsWith("file:///android_asset/")
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.toString().startsWith("file:///android_asset/")) return null
                return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
        }
        // Enables the page's confirm()/alert(); without this confirm returns false.
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                // Log location and severity only; JS exception text may contain secrets.
                Log.i("PitMasterControllerSetup", "WebView console level=${message.messageLevel()} line=${message.lineNumber()}")
                return true
            }
        }
        Log.i("PitMasterControllerSetup", "Provisioning diagnostics v2; loading app page")
        web.addJavascriptInterface(Bridge(), "LockhartNative")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val frame = FrameLayout(this)
        frame.setBackgroundColor(android.graphics.Color.rgb(10, 16, 22))
        frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            frame.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard.bottom))
                WindowInsets.CONSUMED
            }
        } else frame.fitsSystemWindows = true
        setContentView(frame)
        frame.requestApplyInsets()
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun updateMonitorService(enabled: Boolean) {
        if (transportMode == "bluetooth" && enabled) return
        val intent = Intent(this, PitMonitorService::class.java)
        if (enabled) {
            try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent) }
            catch (ex: Exception) {
                getSharedPreferences("pitvoss_monitor", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
                web.evaluateJavascript("window.monitoringResult(false,${JSONObject.quote("Background service failed: ${ex.message}")})", null)
            }
        } else stopService(intent)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 733) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) scanWifiNetworks()
            else finishWifiScan("Allow precise Location to list nearby networks, or enter the network name yourself.")
        }
        if (requestCode == 731) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                bleEvent("status", "Bluetooth permission granted. Starting scan…")
                beginBleScan()
            } else bleEvent("error", "Nearby devices permission denied. Allow it in Android Settings → Apps → Pit Master Controller → Permissions.")
        }
        if (requestCode == 732) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                getSharedPreferences("pitvoss_monitor", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
                web.evaluateJavascript("window.monitoringResult(false,${JSONObject.quote("Notification permission required")})", null)
            } else updateMonitorService(true)
        }
    }

    @Volatile private var activeGrillModel = "PB2180LK"
    @Volatile private var activeGrillBoard = "PBG"
    private val betaCatalog by lazy { JSONObject(assets.open("model-catalog.json").bufferedReader().use { it.readText() }) }
    private val stateMethods = java.util.concurrent.ConcurrentHashMap<String, String>()
    private class MissingRpcMethod(message: String) : IllegalStateException(message)
    private fun readGrillState(ip: String, id: Int): String {
        val first = stateMethods[ip] ?: "GetState"
        return try { send(ip, first, JSONObject(), id).also { stateMethods[ip] = first } }
        catch (_: MissingRpcMethod) {
            val alternative = if (first == "GetState") "PB.GetState" else "GetState"
            send(ip, alternative, JSONObject(), id).also { stateMethods[ip] = alternative }
        }
    }
    private fun modelFrame(action: String, value: Int = 0, fahrenheit: Boolean = true): String? {
        val model = betaCatalog.getJSONObject("models").optJSONObject(activeGrillModel)
        val board = betaCatalog.getJSONObject("boards").optJSONObject(activeGrillBoard)
        if (action == "setTemp") {
            val unit = if (fahrenheit) "F" else "C"
            val direct = model?.optJSONObject("temperatureCommands")?.optJSONObject(unit)?.optString(value.toString())
            if (!direct.isNullOrBlank()) return direct
            // Unknown model with an advertised, known board: reuse that board's encoding,
            // without claiming the model accepts the chosen value.
            if (model == null && activeGrillBoard.isNotBlank()) {
                val models = betaCatalog.getJSONObject("models")
                for (key in models.keys()) {
                    val candidate = models.getJSONObject(key)
                    if (candidate.optString("board") != activeGrillBoard) continue
                    val frame = candidate.optJSONObject("temperatureCommands")?.optJSONObject(unit)?.optString(value.toString())
                    if (!frame.isNullOrBlank()) return frame
                }
            }
            return null
        }
        return board?.optJSONObject("commands")?.optString(action)?.takeIf { it.isNotBlank() }
    }

    private fun sendModelControl(ip: String, action: String, value: Int, id: Int, fahrenheit: Boolean = true): String {
        val legacyMethod = when (action) { "setTemp" -> "SetTemperature"; "lightOn", "lightOff", "primeStart", "primeStop" -> "SendGenericCommand"; else -> "SetPowerState" }
        val frame = modelFrame(action, value, fahrenheit)
        val legacyParams = when (action) {
            "setTemp" -> JSONObject().put("target", "grill").put("amount", value)
            "powerOn", "powerOff" -> JSONObject().put("desiredState", if (action == "powerOn") "On" else "Off")
            else -> {
                val fallback = when (action) { "lightOn" -> "FE0201FF"; "lightOff" -> "FE0200FF"; "primeStart" -> "FE0801FF"; else -> "FE0800FF" }
                val encoded = frame ?: fallback
                JSONObject().put("command", encoded.chunked(2).joinToString(" ") { it.toInt(16).toString().padStart(3, '0') })
            }
        }
        Log.i("PitMasterControllerSetup", "Beta control model=$activeGrillModel board=$activeGrillBoard action=$action id=$id")
        fun modern(): String {
            val command = frame ?: throw IllegalStateException("No command definition for this model yet; include this in your beta report")
            return send(ip, "PB.SendMCUCommand", JSONObject().put("command", command), id)
        }
        return if (stateMethods[ip] == "PB.GetState") modern()
        else try { send(ip, legacyMethod, legacyParams, id) } catch (_: MissingRpcMethod) { modern() }
    }
    private fun normalizedControlState(raw: String): JSONObject {
        val outer = JSONObject(raw)
        val state = outer.optJSONObject("result")
            ?: outer.optJSONObject("error")?.optString("message")?.let { JSONObject(it) } ?: outer
        if (state.has("sc_11") || state.has("sc_12")) {
            val board = betaCatalog.getJSONObject("boards").optJSONObject(activeGrillBoard)
                ?: throw IllegalStateException("Choose a model so its readings can be decoded")
            fun byte(frame: String, index: Int): Int? = if (index >= 0 && frame.length >= index * 2 + 2)
                frame.substring(index * 2, index * 2 + 2).toIntOrNull(16) else null
            val power = byte(state.optString("sc_11"), board.optInt("powerIndex", -1))
                ?: throw IllegalStateException("Waiting for a fresh grill power reading")
            val unit = byte(state.optString("sc_12"), board.optInt("unitIndex", -1))
                ?: throw IllegalStateException("Waiting for a fresh temperature unit reading")
            return JSONObject().put("moduleIsOn", power == 1).put("isFahrenheit", unit == 1)
        }
        return state
    }

    private fun send(ip: String, method: String, params: JSONObject, id: Int): String {
        LocalNetwork.requireAllowed(ip)
        if (transportMode == "bluetooth") throw IllegalStateException("BLOCKED: Wi-Fi RPC in Bluetooth Only mode")
        val conn = URL("http://$ip/rpc").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put("id", id).put("method", method).put("params", params).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val body = conn.inputStream.use { LocalNetwork.readBounded(it) }
            val reply = JSONObject(body)
            if (reply.has("id") && reply.optInt("id", -1) != id) throw IllegalStateException("Unexpected response identifier")
            val error = reply.optJSONObject("error")
            if (error != null) {
                if (error.optInt("code", -1) != 0) {
                    if (error.optInt("code") in listOf(404, -32601)) throw MissingRpcMethod("RPC method unavailable")
                    throw IllegalStateException(error.optString("message", "RPC error"))
                }
                if (method != "GetState" && error.optString("message").contains("failed", ignoreCase = true))
                    throw IllegalStateException("Controller rejected command: ${error.optString("message")}")
            }
            return body
        } finally { conn.disconnect() }
    }

    private var pendingLocalBackup: String? = null
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 8401 && requestCode != 8402) return
        if (resultCode != RESULT_OK || data?.data == null) { pendingLocalBackup = null; return }
        try {
            val uri = data.data!!
            if (requestCode == 8401) {
                val body = pendingLocalBackup ?: throw IllegalStateException("Try exporting the backup again")
                contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Could not open the backup destination")
                pendingLocalBackup = null
                web.evaluateJavascript("window.toast('Backup saved. Keep it private.',true)", null)
            } else {
                val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; require(out.size() + n <= 8388608); out.write(buffer, 0, n) }
                    out.toByteArray()
                } ?: throw IllegalStateException("Could not read the selected backup")
                val raw = String(bytes, Charsets.UTF_8)
                JSONObject(raw)
                web.evaluateJavascript("window.restoreAppBackup(${JSONObject.quote(raw)})", null)
            }
        } catch (_: Exception) {
            pendingLocalBackup = null
            web.evaluateJavascript("window.toast('Could not process this backup. Choose an original backup file and try again.',true)", null)
        }
    }

    private fun safetyAcknowledged(): Boolean = getSharedPreferences("pitmaster_release", MODE_PRIVATE)
        .getBoolean("safety_acknowledged_v1", false)

    inner class Bridge {
        @JavascriptInterface fun openPolicy(kind: String) {
            val page = when (kind) {
                "privacy" -> "privacy-policy.html"
                "terms" -> "terms.html"
                "licenses" -> "third-party-notices.html"
                else -> return
            }
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        "https://tannervoss.github.io/Pit_Master_Controller/$page"
                    )).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: android.content.ActivityNotFoundException) {
                    web.evaluateJavascript("window.toast('No browser available. The complete policy is shown here.',true)", null)
                }
            }
        }

        @JavascriptInterface fun exportLocalBackup(raw: String) {
            if (raw.toByteArray(Charsets.UTF_8).size > 8388608) {
                runOnUiThread { web.evaluateJavascript("window.toast('This backup is too large. Export cook history separately.',true)", null) }
                return
            }
            runOnUiThread {
                pendingLocalBackup = raw
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "Pit-Master-Controller-backup.json")
                }, 8401)
            }
        }
        @JavascriptInterface fun chooseLocalBackup() {
            runOnUiThread {
                if (priming) return@runOnUiThread
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                }, 8402)
            }
        }

        @JavascriptInterface fun getRuntimeInfo(): String = JSONObject()
            .put("version", BuildConfig.VERSION_NAME).put("debug", BuildConfig.DEBUG)
            .put("experimentalPriming", BuildConfig.EXPERIMENTAL_PRIMING)
            .put("safetyAcknowledged", safetyAcknowledged()).toString()
        @JavascriptInterface fun acknowledgeSafety() {
            getSharedPreferences("pitmaster_release", MODE_PRIVATE).edit().putBoolean("safety_acknowledged_v1", true).apply()
        }
        @JavascriptInterface fun clearLocalData() {
            runOnUiThread {
                if (priming) {
                    android.widget.Toast.makeText(this@MainActivity, "Stop priming before deleting app data", android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                stopService(Intent(this@MainActivity, PitMonitorService::class.java))
                getSharedPreferences("pitvoss_monitor", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("pitmaster_release", MODE_PRIVATE).edit().clear().apply()
                stopBleScan(false); bleRpcGeneration++; bleRpcReady = false
                closeSelectedGatt()
                selectedGatt = null; stateMethods.clear()
                web.clearCache(true); web.evaluateJavascript("localStorage.clear();window.location.reload()", null)
            }
        }

        @JavascriptInterface fun configureGrillProfile(model: String, board: String) {
            activeGrillModel = model.take(100)
            activeGrillBoard = board.takeIf { betaCatalog.getJSONObject("boards").has(it) } ?: ""
        }
        @JavascriptInterface fun resetExitGuard() { runOnUiThread { lastDashboardBack = 0L } }
        @JavascriptInterface fun setTransportMode(mode: String) {
            if (mode !in listOf("automatic", "bluetooth", "wifi")) return
            transportMode = mode
            setupEvent("transport", "Mode=$mode; native HTTP RPC ${if (mode == "bluetooth") "BLOCKED" else "permitted"}")
            if (mode == "bluetooth") {
                getSharedPreferences("pitvoss_monitor", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
                runOnUiThread { updateMonitorService(false) }
            }
        }
        @JavascriptInterface fun getTransportMode(): String = transportMode

        @JavascriptInterface fun copyBleLog(log: String) {
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Pit Master Controller BLE Log", log.takeLast(300000)))
                android.widget.Toast.makeText(this@MainActivity, "BLE log copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface fun exportBleLog(log: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Pit Master Controller BLE Diagnostic Log")
                    putExtra(Intent.EXTRA_TEXT, log.takeLast(200000))
                }
                startActivity(Intent.createChooser(intent, "Share BLE diagnostic log"))
            }
        }

        @JavascriptInterface fun provisionWifiForGrill(address: String, ssid: String, password: String) {
            runOnUiThread {
                if (provisionConnectionPending || wifiProvisioning) {
                    setupEvent("wifi_error", "Wi-Fi setup is already running.")
                    return@runOnUiThread
                }
                if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                    setupEvent("wifi_error", "This grill has no saved Bluetooth address. Find Grills and select it first.")
                    return@runOnUiThread
                }
                if (!hasBlePermission()) {
                    setupEvent("wifi_error", "Bluetooth permission is missing. Tap Find Grills and allow Nearby devices.")
                    return@runOnUiThread
                }
                provisionConnectionPending = true
                try {
                    if (selectedGattAddress() != address || !bleRpcReady) selectBleAndFindIp(address)
                } catch (_: Exception) {
                    provisionConnectionPending = false
                    setupEvent("wifi_error", "Could not connect to the saved grill over Bluetooth.")
                    return@runOnUiThread
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 35000L
                setupEvent("wifi_status", "Connecting to saved grill over Bluetooth before sending Wi-Fi settings…")
                bleHandler.post(object : Runnable {
                    override fun run() {
                        if (!provisionConnectionPending || isFinishing || isDestroyed) return
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                            provisionConnectionPending = false
                            setupEvent("wifi_error", "Could not establish Bluetooth RPC with the saved grill. Nothing sent. Check grill power and Bluetooth range, then retry.")
                            return
                        }
                        if (selectedGattAddress() == address && bleRpcReady && rpcCompleted) {
                            provisionConnectionPending = false
                            try { startWifiProvision(ssid, password) }
                            catch (_: Exception) { failBleIp("Could not send Wi-Fi settings over Bluetooth") }
                        } else bleHandler.postDelayed(this, 250L)
                    }
                })
            }
        }

        @JavascriptInterface fun provisionWifi(ssid: String, password: String) {
            Log.i("PitMasterControllerSetup", "Bridge provisionWifi called (credentials omitted)")
            runOnUiThread {
                try { startWifiProvision(ssid, password) }
                catch (_: Exception) { failBleIp("Android could not start Wi-Fi setup; reconnect Bluetooth and retry") }
            }
        }
        @JavascriptInterface fun disconnectSelectedGrill() {
            runOnUiThread {
                bleRpcGeneration++
                bleRpcReady = false
                rpcCompleted = true
                rpcData = null; rpcTx = null; rpcRx = null
                closeSelectedGatt()
                selectedGatt = null
                setupEvent("ble_link", "disconnected")
            }
        }
        @JavascriptInterface fun scanWifiNetworks() { runOnUiThread { this@MainActivity.scanWifiNetworks() } }
        @JavascriptInterface fun discoverWifiGrill() { findControllerOnLan() }
        @JavascriptInterface fun testBleState() { runOnUiThread { testBleGetState() } }
        @JavascriptInterface fun scanBle() { runOnUiThread { beginBleScan() } }
        @JavascriptInterface fun selectBleGrill(address: String) { runOnUiThread { selectBleAndFindIp(address) } }
        @JavascriptInterface fun configureMonitoring(enabled: Boolean, ip: String, rulesJson: String, timersJson: String) {
            if (transportMode == "bluetooth") {
                runOnUiThread { updateMonitorService(false); web.evaluateJavascript("window.monitoringResult(false,${JSONObject.quote("Background Wi-Fi monitoring disabled in Bluetooth Only mode")})", null) }
                return
            }
            if (enabled && !ipPattern.matches(ip)) {
                runOnUiThread { web.evaluateJavascript("window.monitoringResult(false,${JSONObject.quote("Connect to your smoker first")})", null) }
                return
            }
            val prefs = getSharedPreferences("pitvoss_monitor", MODE_PRIVATE)
            prefs.edit().putBoolean("enabled", enabled).putString("ip", ip)
                .putString("rules", rulesJson).putString("timers", timersJson)
                .putBoolean("rules_canonical_f", try { JSONObject(rulesJson).optBoolean("__canonicalF", false) } catch (_: Exception) { false })
                .putString("display_unit", try { JSONObject(rulesJson).optString("__displayUnit", "F") } catch (_: Exception) { "F" }).apply()
            runOnUiThread {
                if (enabled && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 732)
                } else {
                    updateMonitorService(enabled)
                }
            }
        }


        @JavascriptInterface fun rpc(ip: String, method: String, params: String, requestId: Int) {
            if (transportMode == "bluetooth") { deliver(requestId, "", "BLOCKED: Wi-Fi GetState in Bluetooth Only mode"); return }
            if (method != "GetState" || !ipPattern.matches(ip)) {
                deliver(requestId, "", "Unsupported RPC or invalid IP"); return
            }
            pool.execute {
                try { deliver(requestId, readGrillState(ip, requestId), "") }
                catch (ex: Exception) { deliver(requestId, "", ex.message ?: "Network request failed") }
            }
        }

        @JavascriptInterface fun setTemperature(ip: String, value: Int, fahrenheit: Boolean, requestId: Int) {
            if (!safetyAcknowledged()) { deliverControl(requestId, "setTemp", "", "Read and accept the safety information first"); return }
            val profile = betaCatalog.getJSONObject("models").optJSONObject(activeGrillModel)
            val values = profile?.optJSONArray(if (fahrenheit) "setpointsF" else "setpointsC")
            val accepted = if (values != null) (0 until values.length()).map { values.getInt(it) } else (70..1000).toList()
            if (transportMode == "bluetooth" || !ipPattern.matches(ip) || value !in accepted) {
                deliverControl(requestId, "setTemp", "", "Temperature or connection is unavailable")
                return
            }
            pool.execute {
                try {
                    // Read-only preflight: never send a temperature using a stale unit or power state.
                    val state = normalizedControlState(readGrillState(ip, requestId))
                    val poweredOn = state.optBoolean("moduleIsOn", false) || state.optInt("moduleIsOn", 0) == 1
                    if (!poweredOn || state.optBoolean("isFahrenheit", true) != fahrenheit)
                        throw IllegalStateException("Grill power or temperature unit changed; check the grill before retrying")
                    // Legacy Lockhart SetTemperature emits the same FE0501 digit bytes as PytBoss PBG.
                    val frame = "FE0501" + "%02X%02X%02X".format(value / 100, (value / 10) % 10, value % 10) + "FF"
                    Log.i("PitMasterControllerSetup", "Temperature request id=$requestId value=$value fahrenheit=$fahrenheit frame=$frame")
                    val body = sendModelControl(ip, "setTemp", value, requestId, fahrenheit)
                    val outer = JSONObject(body)
                    val ack = outer.optJSONObject("error")?.optString("message") ?: outer.opt("result")?.toString().orEmpty()
                    val rejected = listOf("failed", "expected", "bad request", "wrong password").any { ack.contains(it, true) }
                    if (rejected) throw IllegalStateException("Controller rejected temperature request")
                    Log.i("PitMasterControllerSetup", "Temperature acknowledged id=$requestId value=$value; awaiting reported target")
                    deliverControl(requestId, "setTemp", body, "")
                } catch (ex: Exception) {
                    Log.w("PitMasterControllerSetup", "Temperature request failed id=$requestId type=${ex.javaClass.simpleName}")
                    deliverControl(requestId, "setTemp", "", ex.message ?: "Temperature request failed")
                }
            }
        }

        @JavascriptInterface fun control(ip: String, action: String, value: Int, requestId: Int) {
            if (!safetyAcknowledged()) { deliverControl(requestId, action, "", "Read and accept the safety information first"); return }
            if (transportMode == "bluetooth") { deliverControl(requestId, action, "", "BLOCKED: Wi-Fi control in Bluetooth Only mode"); return }
            if (!ipPattern.matches(ip) || action !in listOf("powerOn", "powerOff", "lightOn", "lightOff")) {
                deliverControl(requestId, action, "", "Invalid control request"); return
            }
            if (action == "setTemp" && (value !in 70..500)) {
                deliverControl(requestId, action, "", "Setpoint must be within the controller supported range"); return
            }
            pool.execute {
                try {
                    val body = sendModelControl(ip, action, value, requestId)
                    deliverControl(requestId, action, body, "")
                } catch (ex: Exception) {
                    deliverControl(requestId, action, "", ex.message ?: "Control command failed")
                }
            }
        }

        @JavascriptInterface fun prime(ip: String, action: String, seconds: Int, requestId: Int) {
            if (!BuildConfig.EXPERIMENTAL_PRIMING || !safetyAcknowledged()) {
                deliverPrime(requestId, action, "", "Remote priming is unavailable in this release. Use the grill panel.")
                return
            }
            if (transportMode == "bluetooth") { deliverPrime(requestId, action, "", "BLOCKED: Wi-Fi prime in Bluetooth Only mode"); return }
            if (!ipPattern.matches(ip) || action !in listOf("start", "stop") || (action == "start" && seconds !in 0..120)) {
                deliverPrime(requestId, action, "", "Invalid prime request"); return
            }
            pool.execute {
                try {
                    if (action == "start") {
                        if (priming) throw IllegalStateException("Prime already active")
                        // Candidate bytes, not verified on the user's firmware.
                        val body = sendModelControl(ip, "primeStart", 0, requestId)
                        priming = true
                        grillIp = ip
                        stopTask?.cancel(false)
                        // Even held prime has a 120s watchdog; release/page exit stops sooner.
                        stopTask = pool.schedule({ stopPrimeInternal() }, (if (seconds == 0) 120 else seconds).toLong(), TimeUnit.SECONDS)
                        deliverPrime(requestId, action, body, "")
                    } else {
                        stopTask?.cancel(false)
                        val body = sendModelControl(ip, "primeStop", 0, requestId)
                        priming = false
                        deliverPrime(requestId, action, body, "")
                    }
                } catch (ex: Exception) {
                    deliverPrime(requestId, action, "", ex.message ?: "Prime RPC failed")
                }
            }
        }
    }

    private fun stopPrimeInternal() {
        if (!priming || grillIp.isEmpty()) return
        try {
            sendModelControl(grillIp, "primeStop", 0, 999999)
            priming = false
            deliverPrime(999999, "stop", "", "")
        } catch (ex: Exception) { deliverPrime(999999, "stop", "", "Automatic stop FAILED: ${ex.message}") }
    }

    private fun deliver(id: Int, body: String, error: String) {
        val args = "${id},${JSONObject.quote(body)},${JSONObject.quote(error)}"
        runOnUiThread { web.evaluateJavascript("window.receiveRpc($args)", null) }
    }
    private fun deliverControl(id: Int, action: String, body: String, error: String) {
        val args = "${id},${JSONObject.quote(action)},${JSONObject.quote(body)},${JSONObject.quote(error)}"
        runOnUiThread { web.evaluateJavascript("window.receiveControl($args)", null) }
    }
    private fun deliverPrime(id: Int, action: String, body: String, error: String) {
        val args = "${id},${JSONObject.quote(action)},${JSONObject.quote(body)},${JSONObject.quote(error)}"
        runOnUiThread { web.evaluateJavascript("window.receivePrime($args)", null) }
    }
    override fun onPause() {
        stopBleScan()
        // A held prime should never continue intentionally after leaving the app.
        if (priming) pool.execute { stopTask?.cancel(false); stopPrimeInternal() }
        super.onPause()
    }
    override fun onDestroy() {
        if (priming) {
            try { send(grillIp, "SendGenericCommand", JSONObject().put("command", "254 008 000 255"), 999998) } catch (_: Exception) {}
        }
        closeSelectedGatt()
        provisionConnectionPending = false
        bleHandler.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        wifiScanReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        wifiScanReceiver = null
        web.destroy()
        super.onDestroy()
    }
}
