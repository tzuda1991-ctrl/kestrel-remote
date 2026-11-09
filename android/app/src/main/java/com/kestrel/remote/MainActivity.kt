// Kotlin | MainActivity.kt | Android app entry point | owner-controlled pairing UI
package com.kestrel.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var serverInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var stateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("kestrel", MODE_PRIVATE)
        buildUi()
        requestNotificationPermissionIfNeeded()
        refreshState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 32)
        }

        val title = TextView(this).apply {
            text = "Kestrel Remote"
            textSize = 26f
        }
        stateText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 24)
        }
        serverInput = EditText(this).apply {
            hint = "HTTPS server URL, e.g. https://remote.example"
            setSingleLine(true)
            setText(prefs.getString("base_url", BuildConfig.DEFAULT_API_BASE_URL))
        }
        codeInput = EditText(this).apply {
            hint = "8-character pairing code"
            setSingleLine(true)
        }
        nameInput = EditText(this).apply {
            hint = "Device name"
            setSingleLine(true)
            setText("${Build.MANUFACTURER} ${Build.MODEL}".take(100))
        }

        val pairButton = Button(this).apply {
            text = "Pair device"
            setOnClickListener { pairDevice() }
        }
        val connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { startConnection() }
        }
        val disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { disconnect() }
        }

        root.addView(title)
        root.addView(stateText)
        root.addView(serverInput, matchWidth())
        root.addView(codeInput, matchWidth())
        root.addView(nameInput, matchWidth())
        root.addView(pairButton, matchWidth())
        root.addView(connectButton, matchWidth())
        root.addView(disconnectButton, matchWidth())
        setContentView(root)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun deviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "android_" + UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun pairDevice() {
        val baseUrl = serverInput.text.toString().trim().trimEnd('/')
        val code = codeInput.text.toString().trim()
        val deviceName = nameInput.text.toString().trim()
        if (!baseUrl.startsWith("https://") || code.length != 8 || deviceName.isBlank()) {
            toast("Enter an HTTPS URL, 8-character code, and device name.")
            return
        }

        prefs.edit().putString("base_url", baseUrl).apply()
        stateText.text = "Pairing…"
        executor.execute {
            try {
                val token = ApiClient.pair(baseUrl, code, deviceId(), deviceName)
                prefs.edit().putString("token", token).apply()
                runOnUiThread {
                    stateText.text = "Paired. Tap Connect to start the visible service."
                    toast("Device paired.")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    stateText.text = "Pairing failed: ${error.message}"
                    toast(error.message ?: "Pairing failed")
                }
            }
        }
    }

    private fun startConnection() {
        val token = prefs.getString("token", null)
        val baseUrl = serverInput.text.toString().trim().trimEnd('/')
        if (token.isNullOrBlank()) {
            toast("Pair this device first.")
            return
        }
        if (!baseUrl.startsWith("https://")) {
            toast("Use an HTTPS server URL.")
            return
        }
        prefs.edit().putString("base_url", baseUrl).apply()
        val intent = Intent(this, RemoteService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        stateText.text = "Connected service requested. A persistent notification will be shown."
    }

    private fun disconnect() {
        stopService(Intent(this, RemoteService::class.java))
        prefs.edit().remove("token").apply()
        stateText.text = "Disconnected. Local pairing credentials cleared."
        toast("Disconnected. Pair again to reconnect.")
    }

    private fun refreshState() {
        val paired = !prefs.getString("token", null).isNullOrBlank()
        stateText.text = if (paired) {
            "Device paired: ${deviceId()}\nTap Connect to enable status updates."
        } else {
            "Not paired. Generate a code with /newpair in your Telegram bot."
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
