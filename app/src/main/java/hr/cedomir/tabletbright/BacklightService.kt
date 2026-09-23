package hr.cedomir.tabletbright

import android.app.*
import android.content.*
import android.database.ContentObserver
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import java.nio.charset.StandardCharsets
import java.util.UUID

class BacklightService : Service() {
    private var client: Mqtt3AsyncClient? = null
    private lateinit var base: String
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val channelId = "backlight_agent"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channelId, "HA Backlight Agent", NotificationManager.IMPORTANCE_MIN))
        }
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("HA Backlight Agent")
            .setContentText("MQTT brightness service running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true).build()
        startForeground(1001, n)

        contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) { publishBrightness() }
            })
        connect()
    }

    private fun connect() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val host = p.getString("host", "192.168.0.114") ?: return
        val port = p.getInt("port", 1883)
        val user = p.getString("user", "") ?: ""
        val pass = p.getString("pass", "") ?: ""
        base = (p.getString("topic", "tablet/backlight") ?: "tablet/backlight").trimEnd('/')
        val builder = MqttClient.builder().useMqttVersion3().identifier("tablet-bright-${UUID.randomUUID()}").serverHost(host).serverPort(port)
        client = builder.buildAsync()
        val connect = client!!.connectWith().keepAlive(60)
            .willPublish().topic("$base/available").payload("offline".toByteArray()).retain(true).applyWillPublish()
        if (user.isNotBlank()) connect.simpleAuth().username(user).password(pass.toByteArray()).applySimpleAuth()
        connect.send().whenComplete { _, err ->
            if (err == null) {
                client!!.subscribeWith().topicFilter("$base/set").qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE).send()
                client!!.publishes(MqttGlobalPublishFilter.ALL) { msg ->
                    if (msg.topic.toString() == "$base/set") {
                        val raw = StandardCharsets.UTF_8.decode(msg.payload.get()).toString().trim()
                        val v = raw.toIntOrNull()?.coerceIn(1, 255) ?: return@publishes
                        if (Settings.System.canWrite(this)) Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                        publishBrightness()
                    }
                }
                publish("$base/available", "online", true)
                publishDiscovery()
                publishBrightness()
            } else handler.postDelayed({ connect() }, 15000)
        }
    }

    private fun publishBrightness() {
        val v = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { return }
        publish("$base/state", v.coerceIn(0,255).toString(), true)
    }

    private fun publishDiscovery() {
        val payload = """{"name":"Tablet Backlight","unique_id":"tablet_backlight_agent","command_topic":"$base/set","state_topic":"$base/state","availability_topic":"$base/available","payload_available":"online","payload_not_available":"offline","brightness":true,"brightness_scale":255,"schema":"json","command_template":"{{ value_json.brightness }}","state_value_template":"{{ value_json.brightness }}","device":{"identifiers":["tablet_backlight_agent"],"name":"Tablet Backlight Agent"}}"""
        // Discovery is intentionally deferred to v0.2; raw brightness topics work in v0.1.
    }

    private fun publish(topic: String, text: String, retain: Boolean) {
        val c = client ?: return
        if (c.state.isConnected) c.publishWith().topic(topic).payload(text.toByteArray()).retain(retain).send()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { client?.disconnect(); super.onDestroy() }
}
