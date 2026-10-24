package com.pitvoss.app

import android.app.*
import android.content.*
import android.os.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground Wi-Fi monitor. Remains active after the UI is closed while Android permits.
 * Not a safety-critical fire alarm or a guaranteed delivery mechanism.
 */
class PitMonitorService : Service() {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val ongoingChannel = "pitvoss_monitor"
    private val alertChannel = "pitvoss_alerts"
    private val ongoingId = 14001
    private val notified = HashSet<String>()
    private var monitorIdentity = ""
    private var failures = 0
    private var nextAlertId = 14100

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val stored = getSharedPreferences("pitvoss_monitor", MODE_PRIVATE)
            .getStringSet("fired_timers", hashSetOf<String>()) ?: hashSetOf<String>()
        notified.addAll(stored)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                ongoingChannel, "Smoker monitoring", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(
                alertChannel, "Smoker and timer alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("pitvoss_monitor", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) { stopSelf(); return START_NOT_STICKY }
        startForeground(ongoingId, ongoing("Monitoring smoker"))
        if (!scheduler.isShutdown) {
            // Scheduling only once per service instance.
            if (!started) {
                started = true
                scheduler.scheduleWithFixedDelay({ try { poll() } catch (_: Exception) {} }, 0, 15, TimeUnit.SECONDS)
            }
        }
        return START_STICKY
    }
    private var started = false
    private fun ongoing(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, ongoingChannel)
        return builder.setContentTitle("Pit Master Controller monitoring")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open).setOngoing(true).build()
    }
    private fun sendAlert(key: String, title: String, message: String) {
        if (!notified.add(key)) return
        if (key.startsWith("timer:")) {
            getSharedPreferences("pitvoss_monitor", MODE_PRIVATE).edit()
                .putStringSet("fired_timers", HashSet(notified.filter { it.startsWith("timer:") }.takeLast(200)))
                .apply()
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, alertChannel)
        val note = builder.setContentTitle(title).setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true).setContentIntent(open).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(nextAlertId++, note)
    }
    private fun poll() {
        val prefs = getSharedPreferences("pitvoss_monitor", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) { stopSelf(); return }
        val now = System.currentTimeMillis() / 1000
        // Timers are checked independently of Wi-Fi connectivity.
        val timers = try { JSONArray(prefs.getString("timers", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        for (i in 0 until timers.length()) {
            val t = timers.optJSONObject(i) ?: continue
            val id = t.optString("id")
            if (id.isNotEmpty() && now >= t.optLong("end", Long.MAX_VALUE))
                sendAlert("timer:$id", "Timer finished", t.optString("name", "Timer"))
        }
        val ip = prefs.getString("ip", "") ?: ""
        val identity = ip + prefs.getString("rules", "")
        if (identity != monitorIdentity) {
            monitorIdentity = identity
            notified.removeAll { !it.startsWith("timer:") }
            failures = 0
        }
        if (!Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(ip)) return
        if (!LocalNetwork.isAllowedHost(ip)) return
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://$ip/rpc").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3500
            conn.readTimeout = 4000
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("""{"id":14001,"method":"GetState","params":{}}""".toByteArray()) }
            val response = JSONObject(conn.inputStream.use { LocalNetwork.readBounded(it) })
            val error = response.optJSONObject("error")
            if (error != null && error.optInt("code", -1) != 0) throw IllegalStateException("RPC failed")
            val state = if (error != null) JSONObject(error.optString("message", "{}"))
                        else response.optJSONObject("result") ?: response
            if (!state.has("grillTemp") && !state.has("smokerActTemp")) throw IllegalStateException("No decoded temperature readings")
            failures = 0
            notified.remove("connection")
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ongoingId, ongoing("Connected · monitoring temperature and timers"))
            val rules = try { JSONObject(prefs.getString("rules", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            val names = mapOf("grillTemp" to "Grill", "smokerActTemp" to "Cabinet",
                "p_1_Temp" to "Probe 1", "p_2_Temp" to "Probe 2",
                "p_3_Temp" to "Probe 3", "p_4_Temp" to "Probe 4")
            for ((field, label) in names) {
                if (!state.has(field) || state.isNull(field)) continue
                val rawTemp = state.optDouble(field, Double.NaN)
                val canonicalRules = prefs.getBoolean("rules_canonical_f", false)
                val temp = if (canonicalRules && !state.optBoolean("isFahrenheit", true)) rawTemp * 9 / 5 + 32 else rawTemp
                if (!temp.isFinite() || temp < 0 || temp >= 900) continue
                val rule = rules.optJSONObject(field) ?: continue
                for (side in listOf("low", "high")) {
                    if (!rule.has(side) || rule.isNull(side)) continue
                    val limit = rule.optDouble(side, Double.NaN)
                    if (!limit.isFinite()) continue
                    val key = "$field:$side"
                    val displayF = prefs.getString("display_unit", "F") != "C"
                    fun display(value: Double): String = "${kotlin.math.round(if (canonicalRules && !displayF) (value - 32) * 5 / 9 else value).toInt()}°${if (displayF) "F" else "C"}"
                    val tripped = if (side == "low") temp <= limit else temp >= limit
                    if (tripped) sendAlert(key, "$label ${side.uppercase()} alert",
                        "$label: ${display(temp)} (limit ${display(limit)})")
                    else if ((side == "low" && temp > limit + 5) || (side == "high" && temp < limit - 5))
                        notified.remove(key)
                }
            }
        } catch (_: Exception) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ongoingId, ongoing("No fresh readings · check grill connection"))
            failures++
            if (failures >= 3) {
                sendAlert("connection", "Pit Master Controller connection lost",
                    "Unable to reach your smoker over Wi-Fi.")
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(ongoingId, ongoing("No connection · check smoker Wi-Fi"))
            }
        } finally { conn?.disconnect() }
    }
    override fun onDestroy() {
        scheduler.shutdownNow()
        super.onDestroy()
    }
}
