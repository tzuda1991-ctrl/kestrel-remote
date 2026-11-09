// Kotlin | ApiClient.kt | Android API client | authenticated Kestrel backend
package com.kestrel.remote

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    private fun request(
        baseUrl: String,
        path: String,
        method: String = "GET",
        token: String? = null,
        body: JSONObject? = null
    ): JSONObject {
        val cleanBase = baseUrl.trim().trimEnd('/')
        require(cleanBase.startsWith("https://")) {
            "Use an HTTPS server URL."
        }

        val connection = (URL(cleanBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(response).optString("detail", "HTTP $code")
                } catch (_: Exception) {
                    "HTTP $code"
                }
                throw IllegalStateException(detail)
            }
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    fun requestTelegramSetup(baseUrl: String, botToken: String, chatId: String) {
        val body = JSONObject()
            .put("token", botToken)
            .put("chat_id", chatId.toLong())
        request(baseUrl, "/api/setup/request", "POST", body = body)
    }

    fun verifyTelegramSetup(baseUrl: String, code: String) {
        val body = JSONObject().put("code", code)
        request(baseUrl, "/api/setup/verify", "POST", body = body)
    }

    fun pair(baseUrl: String, code: String, deviceId: String, name: String): String {
        val body = JSONObject()
            .put("code", code.trim().uppercase())
            .put("device_id", deviceId)
            .put("name", name)
        return request(baseUrl, "/api/pair", "POST", body = body)
            .getString("token")
    }

    fun heartbeat(
        baseUrl: String,
        deviceId: String,
        token: String,
        model: String,
        androidVersion: String,
        battery: Int
    ) {
        val body = JSONObject()
            .put("model", model)
            .put("android_version", androidVersion)
            .put("battery", battery)
        request(
            baseUrl,
            "/api/devices/$deviceId/heartbeat",
            "POST",
            token,
            body
        )
    }

    fun commands(baseUrl: String, deviceId: String, token: String): List<JSONObject> {
        val response = request(
            baseUrl,
            "/api/devices/$deviceId/commands",
            "GET",
            token
        )
        val array = response.optJSONArray("commands") ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    fun result(
        baseUrl: String,
        deviceId: String,
        token: String,
        commandId: Int,
        message: String
    ) {
        val body = JSONObject()
            .put("command_id", commandId)
            .put("result", message.take(300))
        request(
            baseUrl,
            "/api/devices/$deviceId/result",
            "POST",
            token,
            body
        )
    }
}
