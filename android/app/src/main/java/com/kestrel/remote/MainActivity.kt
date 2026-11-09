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
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
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
    private lateinit var botTokenInput: EditText
    private lateinit var chatIdInput: EditText
    private lateinit var telegramSetupCodeInput: EditText

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

        val telegramHeading = TextView(this).apply {
            text = "Telegram Settings"
            textSize = 22f
            setPadding(0, 32, 0, 12)
        }
        val telegramHelp = TextView(this).apply {
            text = "Settings are encrypted on this phone. These fields do not change Railway's BOT_TOKEN or ADMIN_ID."
            textSize = 13f
        }
        botTokenInput = EditText(this).apply {
            hint = "Telegram Bot Token"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(TelegramSettings.loadToken(this@MainActivity))
        }
        chatIdInput = EditText(this).apply {
            hint = "Telegram Chat ID (numeric)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(TelegramSettings.loadChatId(this@MainActivity))
        }
        val showToken = CheckBox(this).apply {
            text = "Show bot token"
            setOnCheckedChangeListener { _, checked ->
                botTokenInput.transformationMethod = if (checked) {
                    null
                } else {
                    PasswordTransformationMethod.getInstance()
                }
                botTokenInput.setSelection(botTokenInput.text.length)
            }
        }
        val saveTelegram = Button(this).apply {
            text = "Save Telegram Settings"
            setOnClickListener { saveTelegramSettings() }
        }
        val testTelegram = Button(this).apply {
            text = "Test Telegram"
            setOnClickListener { testTelegramConnection() }
        }

        root.addView(telegramHeading)
        root.addView(telegramHelp)
        root.addView(botTokenInput, matchWidth())
        root.addView(chatIdInput, matchWidth())
        root.addView(showToken, matchWidth())
        root.addView(saveTelegram, matchWidth())
        root.addView(testTelegram, matchWidth())
        telegramSetupCodeInput = EditText(this).apply {
            hint = "6-digit code received in Telegram"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val setupTelegram = Button(this).apply {
            text = "Connect Telegram to Railway"
            setOnClickListener { requestRailwayTelegramSetup() }
        }
        val verifyTelegram = Button(this).apply {
            text = "Verify Code & Activate Bot"
            setOnClickListener { verifyRailwayTelegramSetup() }
        }
        root.addView(setupTelegram, matchWidth())
        root.addView(telegramSetupCodeInput, matchWidth())
        root.addView(verifyTelegram, matchWidth())
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun saveTelegramSettings() {
        val token = botTokenInput.text.toString().trim()
        val chatId = chatIdInput.text.toString().trim()
        if (!Regex("^[0-9]{5,15}:[A-Za-z0-9_-]{20,}$").matches(token) ||
            !Regex("^-?[0-9]{1,20}$").matches(chatId)) {
            toast("Enter a valid Telegram bot token and numeric Chat ID.")
            return
        }
        try {
            TelegramSettings.save(this, token, chatId)
            toast("Telegram settings saved securely on this phone.")
        } catch (_: Exception) {
            toast("Unable to save settings. Try again.")
        }
    }

    private fun testTelegramConnection() {
        val token = botTokenInput.text.toString().trim()
        val chatId = chatIdInput.text.toString().trim()
        toast("Testing Telegram connection…")
        executor.execute {
            val result = try {
                TelegramSettings.sendTest(token, chatId)
            } catch (error: Exception) {
                "Telegram test failed: ${error.message ?: "Connection error"}"
            }
            runOnUiThread { toast(result) }
        }
    }

    private fun requestRailwayTelegramSetup() {
        val baseUrl = serverInput.text.toString().trim().trimEnd('/')
        val token = botTokenInput.text.toString().trim()
        val chatId = chatIdInput.text.toString().trim()
        if (!baseUrl.startsWith("https://") ||
            !Regex("^[0-9]{5,15}:[A-Za-z0-9_-]{20,}$").matches(token) ||
            !Regex("^[0-9]{1,20}$").matches(chatId)) {
            toast("Enter a valid HTTPS Railway URL, Bot Token and private Chat ID.")
            return
        }
        toast("Requesting setup code…")
        executor.execute {
            val result = try {
                ApiClient.requestTelegramSetup(baseUrl, token, chatId)
                "Check your Telegram chat for the six-digit setup code."
            } catch (error: Exception) {
                "Setup failed: ${error.message ?: "Connection error"}"
            }
            runOnUiThread { toast(result) }
        }
    }

    private fun verifyRailwayTelegramSetup() {
        val baseUrl = serverInput.text.toString().trim().trimEnd('/')
        val code = telegramSetupCodeInput.text.toString().trim()
        if (!baseUrl.startsWith("https://") || !Regex("^[0-9]{6}$").matches(code)) {
            toast("Enter Railway HTTPS URL and six-digit Telegram code.")
            return
        }
        executor.execute {
            val result = try {
                ApiClient.verifyTelegramSetup(baseUrl, code)
                "Telegram bot activated on Railway."
            } catch (error: Exception) {
                "Verification failed: ${error.message ?: "Connection error"}"
            }
            runOnUiThread { toast(result) }
        }
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
