package com.pitvoss.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.bluetooth.BluetoothDevice
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val bleHandler = Handler(Looper.getMainLooper())
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanning = false
    private var classicScanning = false
    private var scanGeneration = 0
    private val seenDevices = mutableSetOf<String>()
    private var bleCount = 0
    private var classicCount = 0
    private var classicReceiverRegistered = false
    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                try {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) {
                        val name = device.name ?: ""
                        val address = device.address
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        emitFound(address,name,rssi,"classic")
                    }
                } catch (e: Exception) { bleEvent("status", "Classic discovery error: ${e.message}") }
            }
        }
    }
    private fun emitFound(address: String, name: String, rssi: Int, transport: String, services: org.json.JSONArray = org.json.JSONArray()) {
        if (seenDevices.add(address)) {
            if (transport == "ble") bleCount++ else classicCount++
        }
        val data = JSONObject().put("address",address).put("name",name).put("rssi",rssi).put("transport",transport).put("services",services)
        bleEvent("device",data.toString())
        bleEvent("status", "Scanning… ${seenDevices.size} devices found (BLE $bleCount, classic $classicCount)")
    }
    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val services = org.json.JSONArray()
                result.scanRecord?.serviceUuids?.forEach { services.put(it.uuid.toString()) }
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                emitFound(result.device.address,name,result.rssi,"ble",services)
            } catch (e: Exception) { bleEvent("status", "BLE result error: ${e.message}") }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,it) }
        }
        override fun onScanFailed(errorCode: Int) {
            bleScanning = false
            bleEvent("status", "BLE scan failed ($errorCode); classic scan may still find devices")
        }
    }
    private fun hasBlePermission(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun bleEvent(kind: String, payload: String) {
        runOnUiThread { web.evaluateJavascript("window.receiveBle(${JSONObject.quote(kind)},${JSONObject.quote(payload)})", null) }
    }
    private fun stopBleScan() {
        scanGeneration++
        val wasScanning = bleScanning || classicScanning
        bleScanning = false
        classicScanning = false
        try { bleScanner?.stopScan(bleCallback) } catch (_: Exception) {}
        try { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.cancelDiscovery() } catch (_: Exception) {}
        if (wasScanning) bleEvent("status", "Scan finished: ${seenDevices.size} devices (BLE $bleCount, classic $classicCount)")
    }
    private fun beginBleScan() {
        if (!hasBlePermission()) {
            val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                              else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(permissions, 731)
            bleEvent("status", "Grant Nearby Devices permission to start discovery")
            return
        }
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) { bleEvent("status", "This phone has no Bluetooth adapter");return }
        if (!adapter.isEnabled) { bleEvent("status", "Bluetooth is off; turn it on and scan again");return }
        stopBleScan()
        seenDevices.clear();bleCount=0;classicCount=0
        val generation = scanGeneration
        if (!classicReceiverRegistered) {
            try { registerReceiver(classicReceiver,IntentFilter(BluetoothDevice.ACTION_FOUND));classicReceiverRegistered=true }
            catch (e: Exception) { bleEvent("status", "Cannot register classic discovery: ${e.message}") }
        }
        var bleError = ""
        try {
            bleScanner = adapter.bluetoothLeScanner
            if (bleScanner == null) bleError="BLE scanner unavailable"
            else {
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0).build()
                bleScanner!!.startScan(null,settings,bleCallback)
                bleScanning=true
            }
        } catch (e: Exception) { bleError="BLE scan error: ${e.message}" }
        var classicError=""
        try { classicScanning=adapter.startDiscovery();if(!classicScanning)classicError="Classic discovery could not start" }
        catch (e: Exception) {classicError="Classic scan error: ${e.message}"}
        val location = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationEnabled = try {location.isLocationEnabled}catch (_:Exception){true}
        val notes=listOf(bleError,classicError,if(!locationEnabled)"Location is off (may hide BLE advertisements)" else "").filter {it.isNotEmpty()}
        bleEvent("status", "Scanning BLE + classic Bluetooth for 20 seconds"+(if(notes.isNotEmpty())" · "+notes.joinToString(" · ") else ""))
        bleHandler.postDelayed({if(scanGeneration==generation)stopBleScan()},20000)
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
                bleEvent("status", "Bluetooth permission granted. Scanning…")
                beginBleScan()
            } else bleEvent("status", "Nearby devices permission denied. Enable it in Android Settings → Apps → Pit Master Controller → Permissions, then scan again.")
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
        pool.shutdownNow()
        if (classicReceiverRegistered) {
            try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
            classicReceiverRegistered=false
        }
        web.destroy()
        super.onDestroy()
    }
}
