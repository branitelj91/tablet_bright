package hr.cedomir.tabletbright

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        fun field(hint: String, value: String = "") = EditText(this).apply { this.hint = hint; setText(value) }
        val host = field("MQTT broker", prefs.getString("host", "192.168.0.114") ?: "")
        val port = field("Port", prefs.getInt("port", 1883).toString())
        val user = field("Username", prefs.getString("user", "") ?: "")
        val pass = field("Password", prefs.getString("pass", "") ?: "").apply { inputType = 0x81 }
        val topic = field("Base topic", prefs.getString("topic", "tablet/backlight") ?: "")
        val status = TextView(this).apply { text = "Service stopped" }
        val permission = Button(this).apply { text = "Enable system brightness control" }
        val start = Button(this).apply { text = "Save & start service" }
        listOf(host, port, user, pass, topic, permission, start, status).forEach(root::addView)
        setContentView(root)

        permission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        start.setOnClickListener {
            prefs.edit().putString("host", host.text.toString().trim())
                .putInt("port", port.text.toString().toIntOrNull() ?: 1883)
                .putString("user", user.text.toString())
                .putString("pass", pass.text.toString())
                .putString("topic", topic.text.toString().trim().trimEnd('/'))
                .putBoolean("enabled", true).apply()
            if (!Settings.System.canWrite(this)) {
                Toast.makeText(this, "First enable Modify system settings", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, BacklightService::class.java))
            status.text = "Service started"
        }
    }
}
