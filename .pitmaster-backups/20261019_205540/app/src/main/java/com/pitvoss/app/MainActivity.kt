package com.pitvoss.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
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
import android.webkit.WebViewClient
import android.webkit.WebSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledFuture

class MainActivity : Activity() {
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
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
            try { bleScanner?.stopScan(callback) } catch (_: Exception) { }
        }
        if (wasScanning && reportFinished) bleEvent("finished", JSONObject().put("devices",bleResults.size).put("callbacks",bleCallbackCount).put("parseErrors",bleParseErrors).toString())
    }

    private fun beginBleScan() {
        stopBleScan(false)
        if (!hasBlePermission()) {
            val permissions = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            bleEvent("status", "Requesting Bluetooth permissions…")
            requestPermissions(permissions, 731)
            return
        }
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) { bleEvent("error", "This phone has no Bluetooth adapter"); return }
        if (!adapter.isEnabled) { bleEvent("error", "Bluetooth is turned off. Enable it and scan again."); return }
        if (true) {
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
    private val cccdId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var selectedGatt: BluetoothGatt? = null
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
    private var bleReady = false
    private var bleRequestKind = "ip"
    private var bleRequestId = 0
    private var bleRequestAction = ""
    private var bleRequestSequence = 901
    private var bleBusy = false
    private var blePending: Triple<String, JSONObject, Int>? = null
    private var rpcReadAttempts = 0
    private val lookupRunning = AtomicBoolean(false)
    private fun setupEvent(kind: String, message: String) = bleEvent("setup_" + kind, message)

    private fun gattWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        run { characteristic.value = bytes; return gatt.writeCharacteristic(characteristic) }
    }
    private fun failBleIp(message: String) {
        if (rpcCompleted) return
        rpcCompleted = true
        bleBusy = false
        if (bleRequestKind == "state") deliver(bleRequestId, "", message)
        else if (bleRequestKind == "control") deliverControl(bleRequestId, bleRequestAction, "", message)
        else setupEvent("error", message + " Bluetooth may still be available for a retry.")
    }
    private fun nextRpcChunk(gatt: BluetoothGatt) {
        val data = rpcData ?: return failBleIp("BLE RPC data characteristic missing")
        if (rpcOffset >= rpcPayload.size) {
            if (bleRequestKind == "ip") setupEvent("status", "Retrieving Wi-Fi address over Bluetooth…")
            bleHandler.postDelayed({ if (!rpcCompleted) failBleIp("GetCurrentIP timed out") }, 12000)
            return
        }
        val end = minOf(rpcPayload.size, rpcOffset + maxOf(1, rpcMtu - 3))
        val chunk = rpcPayload.copyOfRange(rpcOffset, end)
        rpcOffset = end
        if (!gattWrite(gatt, data, chunk)) failBleIp("Unable to send BLE RPC payload")
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
            @Suppress("DEPRECATION")
            val started = gatt.readCharacteristic(characteristic)
            if (!started) {
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
        val kind = bleRequestKind
        rpcCompleted = true
        bleBusy = false
        if (kind == "state") { deliver(bleRequestId, raw, ""); return }
        if (kind == "control") { deliverControl(bleRequestId, bleRequestAction, raw, ""); return }
        val candidate = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}").find(raw)?.value
        if (candidate != null && candidate.split('.').all { (it.toIntOrNull() ?: 256) in 0..255 } && candidate != "0.0.0.0") {
            setupEvent("ip", candidate)
        } else setupEvent("status", "Bluetooth connected; Wi-Fi address unavailable. Bluetooth control can still work.")
        setupEvent("ble_ready", "Bluetooth RPC ready")
    }
    private fun startBleRpc(method: String, params: JSONObject, requestId: Int, kind: String, action: String = "") {
        val gatt = selectedGatt
        if (!bleReady || gatt == null || rpcTx == null || rpcData == null) {
            if (kind == "state") deliver(requestId, "", "Bluetooth not ready")
            else if (kind == "control") deliverControl(requestId, action, "", "Bluetooth not ready")
            return
        }
        if (bleBusy) {
            if (kind == "state") deliver(requestId, "", "Bluetooth command in progress")
            else if (kind == "control") deliverControl(requestId, action, "", "Bluetooth command in progress")
            return
        }
        bleBusy = true
        rpcCompleted = false
        bleRequestKind = kind
        bleRequestId = requestId
        bleRequestAction = action
        rpcExpected = 0
        rpcReply.reset()
        rpcReadAttempts = 0
        rpcOffset = 0
        rpcPayload = JSONObject().put("id", ++bleRequestSequence).put("method", method).put("params", params).toString().toByteArray(Charsets.UTF_8)
        val length = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(rpcPayload.size).array()
        if (!gattWrite(gatt, rpcTx!!, length)) failBleIp("Could not start BLE RPC frame")
        val seq = bleRequestSequence
        bleHandler.postDelayed({ if (bleBusy && seq == bleRequestSequence) failBleIp("BLE command timed out") }, 14000)
    }
    private fun selectBleAndFindIp(address: String) {
        if (!hasBlePermission()) { setupEvent("error", "Bluetooth permissions missing. Scan again and grant access."); return }
        bleRpcGeneration++
        val generation = bleRpcGeneration
        rpcCompleted = false
        bleReady = false
        bleBusy = false
        bleRequestKind = "ip"
        rpcMtu = 23
        rpcExpected = 0
        rpcReadAttempts = 0
        rpcReply.reset()
        rpcData = null; rpcTx = null; rpcRx = null
        try {
            selectedGatt?.disconnect(); selectedGatt?.close(); selectedGatt = null
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device = adapter.getRemoteDevice(address)
            setupEvent("status", "Connecting to selected grill over Bluetooth…")
            selectedGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (generation != bleRpcGeneration) { gatt.close(); return }
                    if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                        bleReady = false
                        setupEvent("ble_disconnected", "Bluetooth disconnected")
                        failBleIp("Bluetooth connection closed (status $status)")
                        try { gatt.close() } catch (_: Exception) {}
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        setupEvent("status", "Bluetooth connected; discovering RPC service…")
                        if (!gatt.discoverServices()) failBleIp("Could not discover GATT services")
                    }
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (generation != bleRpcGeneration || rpcCompleted) return
                    val service = gatt.getService(rpcServiceId)
                    if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                        failBleIp("This grill does not expose the Mongoose OS BLE RPC service")
                        return
                    }
                    rpcData = service.getCharacteristic(rpcDataId)
                    rpcTx = service.getCharacteristic(rpcTxId)
                    rpcRx = service.getCharacteristic(rpcRxId)
                    if (rpcData == null || rpcTx == null || rpcRx == null) {
                        failBleIp("BLE RPC service is missing required characteristics")
                        return
                    }
                    setupEvent("status", "RPC service found; enabling response notifications…")
                    val rx = rpcRx!!
                    if (!gatt.setCharacteristicNotification(rx, true)) {
                        failBleIp("Could not enable BLE RPC notifications"); return
                    }
                    val descriptor = rx.getDescriptor(cccdId)
                    if (descriptor == null) { failBleIp("BLE RPC notification descriptor missing"); return }
                    @Suppress("DEPRECATION")
                    run {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!gatt.writeDescriptor(descriptor)) failBleIp("Could not subscribe to BLE RPC responses")
                    }
                }
                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    if (generation != bleRpcGeneration || rpcCompleted) return
                    if (status != BluetoothGatt.GATT_SUCCESS) { failBleIp("BLE RPC subscription rejected: $status"); return }
                    bleReady = true
                    setupEvent("ble_connected", "Bluetooth connected; requesting Wi-Fi address…")
                    startBleRpc("GetCurrentIP", JSONObject(), 0, "ip")
                }
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (generation != bleRpcGeneration || rpcCompleted) return
                    if (status != BluetoothGatt.GATT_SUCCESS) { failBleIp("BLE write failed: $status"); return }
                    if (characteristic.uuid == rpcTxId || characteristic.uuid == rpcDataId) nextRpcChunk(gatt)
                }
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (generation != bleRpcGeneration || rpcCompleted || characteristic.uuid != rpcRxId) return
                    val bytes = characteristic.value ?: return
                    if (bytes.size < 4) return
                    rpcExpected = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
                    if (rpcExpected <= 0 || rpcExpected > 65536) { failBleIp("Invalid BLE RPC response length: $rpcExpected"); return }
                    rpcReply.reset()
                    val data = rpcData ?: return
                    // Android can reject an immediate read from a notification callback while
                    // the previous ATT operation is still settling. Retry on the main handler.
                    rpcReadAttempts = 0
                    scheduleRpcRead(gatt, data)
                }
                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    if (generation != bleRpcGeneration || rpcCompleted || characteristic.uuid != rpcDataId) return
                    if (status != BluetoothGatt.GATT_SUCCESS) { failBleIp("BLE RPC response read failed: $status"); return }
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
            bleHandler.postDelayed({ if (generation == bleRpcGeneration && !bleReady) failBleIp("Bluetooth setup timed out") }, 25000)
        } catch (ex: Exception) { failBleIp("Bluetooth IP lookup failed: ${ex.message ?: ex.javaClass.simpleName}") }
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
                    try {
                        val conn = URL("http://$candidate/rpc").openConnection() as HttpURLConnection
                        try {
                            conn.requestMethod = "POST"; conn.connectTimeout = 450; conn.readTimeout = 650
                            conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                            conn.outputStream.use { it.write("{\"id\":1,\"method\":\"GetState\",\"params\":{}}".toByteArray()) }
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "LockhartNative")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun updateMonitorService(enabled: Boolean) {
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

    private fun send(ip: String, method: String, params: JSONObject, id: Int): String {
        val conn = URL("http://$ip/rpc").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put("id", id).put("method", method).put("params", params).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val reply = JSONObject(body)
            val error = reply.optJSONObject("error")
            if (error != null) {
                if (error.optInt("code", -1) != 0) throw IllegalStateException(error.optString("message", "RPC error"))
                if (method != "GetState" && error.optString("message").contains("failed", ignoreCase = true))
                    throw IllegalStateException("Controller rejected command: ${error.optString("message")}")
            }
            return body
        } finally { conn.disconnect() }
    }

    inner class Bridge {
        @JavascriptInterface fun scanBle() { runOnUiThread { beginBleScan() } }
        @JavascriptInterface fun selectBleGrill(address: String) { runOnUiThread { selectBleAndFindIp(address) } }
        @JavascriptInterface fun bleState(requestId: Int) { runOnUiThread { startBleRpc("GetState", JSONObject(), requestId, "state") } }
        @JavascriptInterface fun bleControl(action: String, value: Int, requestId: Int) {
            runOnUiThread {
                if (action !in listOf("powerOn", "powerOff", "setTemp", "lightOn", "lightOff") || (action == "setTemp" && value !in 70..500)) {
                    deliverControl(requestId, action, "", "Unsupported BLE control or temperature"); return@runOnUiThread
                }
                val method = when(action) { "setTemp" -> "SetTemperature"; "lightOn", "lightOff" -> "SendGenericCommand"; else -> "SetPowerState" }
                val params = when(action) {
                    "setTemp" -> JSONObject().put("target", "grill").put("amount", value)
                    "lightOn" -> JSONObject().put("command", "254 002 001 255")
                    "lightOff" -> JSONObject().put("command", "254 002 000 255")
                    else -> JSONObject().put("desiredState", if(action == "powerOn") "On" else "Off")
                }
                startBleRpc(method, params, requestId, "control", action)
            }
        }
        @JavascriptInterface fun configureMonitoring(enabled: Boolean, ip: String, rulesJson: String, timersJson: String) {
            if (enabled && !ipPattern.matches(ip)) {
                runOnUiThread { web.evaluateJavascript("window.monitoringResult(false,${JSONObject.quote("Connect to your smoker first")})", null) }
                return
            }
            val prefs = getSharedPreferences("pitvoss_monitor", MODE_PRIVATE)
            prefs.edit().putBoolean("enabled", enabled).putString("ip", ip)
                .putString("rules", rulesJson).putString("timers", timersJson).apply()
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
            if (method != "GetState" || !ipPattern.matches(ip)) {
                deliver(requestId, "", "Unsupported RPC or invalid IP"); return
            }
            pool.execute {
                try { deliver(requestId, send(ip, method, JSONObject(params), requestId), "") }
                catch (ex: Exception) { deliver(requestId, "", ex.message ?: "Network request failed") }
            }
        }

        @JavascriptInterface fun control(ip: String, action: String, value: Int, requestId: Int) {
            if (!ipPattern.matches(ip) || action !in listOf("powerOn", "powerOff", "setTemp", "lightOn", "lightOff")) {
                deliverControl(requestId, action, "", "Invalid control request"); return
            }
            if (action == "setTemp" && (value !in 70..500)) {
                deliverControl(requestId, action, "", "Setpoint must be within the controller supported range"); return
            }
            pool.execute {
                try {
                    val method = when (action) { "setTemp" -> "SetTemperature"; "lightOn", "lightOff" -> "SendGenericCommand"; else -> "SetPowerState" }
                    val params = when (action) {
                        "setTemp" -> JSONObject().put("target", "grill").put("amount", value)
                        "lightOn" -> JSONObject().put("command", "254 002 001 255")
                        "lightOff" -> JSONObject().put("command", "254 002 000 255")
                        else -> JSONObject().put("desiredState", if (action == "powerOn") "On" else "Off")
                    }
                    val body = send(ip, method, params, requestId)
                    deliverControl(requestId, action, body, "")
                } catch (ex: Exception) {
                    deliverControl(requestId, action, "", ex.message ?: "Control command failed")
                }
            }
        }

        @JavascriptInterface fun prime(ip: String, action: String, seconds: Int, requestId: Int) {
            if (!ipPattern.matches(ip) || action !in listOf("start", "stop") || (action == "start" && seconds !in 0..120)) {
                deliverPrime(requestId, action, "", "Invalid prime request"); return
            }
            pool.execute {
                try {
                    if (action == "start") {
                        if (priming) throw IllegalStateException("Prime already active")
                        // Candidate bytes, not verified on the user's firmware.
                        val body = send(ip, "SendGenericCommand", JSONObject().put("command", "254 008 001 255"), requestId)
                        priming = true
                        grillIp = ip
                        stopTask?.cancel(false)
                        // Even held prime has a 120s watchdog; release/page exit stops sooner.
                        stopTask = pool.schedule({ stopPrimeInternal() }, (if (seconds == 0) 120 else seconds).toLong(), TimeUnit.SECONDS)
                        deliverPrime(requestId, action, body, "")
                    } else {
                        stopTask?.cancel(false)
                        val body = send(ip, "SendGenericCommand", JSONObject().put("command", "254 008 000 255"), requestId)
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
            send(grillIp, "SendGenericCommand", JSONObject().put("command", "254 008 000 255"), 999999)
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
        try { selectedGatt?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        web.destroy()
        super.onDestroy()
    }
}
