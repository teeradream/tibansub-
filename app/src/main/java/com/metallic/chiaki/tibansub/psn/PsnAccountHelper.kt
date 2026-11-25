package com.metallic.chiaki.tibansub.psn

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resolves Sony PSN 64-bit Account ID into the exact 8-byte little-endian Base64 string required by libchiaki
 * directly inside TIBANSUB (via Official Sony Remote Play OAuth redirect code OR numeric/hex/Base64 input),
 * so users never have to leave the app to visit external Base64 converter websites.
 */
object PsnAccountHelper {

    const val SONY_OAUTH_AUTHORIZE_URL =
        "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
            "?service_entity=urn:service-entity:psn" +
            "&response_type=code" +
            "&client_id=ba495a24-818c-472b-b12d-ff231c1b5745" +
            "&redirect_uri=https://remoteplay.dl.playstation.net/remoteplay/redirect" +
            "&scope=psn:clientapp"

    const val SONY_REDIRECT_PREFIX = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val SONY_TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
    private const val SONY_BASIC_AUTH = "YmE0OTVhMjQtODE4Yy00NzJiLWIxMmQtZmYyMzFjMWI1NzQ1OjhyZW03NzF0NTkyczVtMDU="

    /**
     * Converts an unsigned 64-bit decimal PSN Account ID string (e.g. "5849201948291048291")
     * into the 8-byte little-endian Base64 string expected by Sony Remote Play Regist.
     */
    fun decimalAccountIdToBase64(decimalStr: String): String? {
        return try {
            val unsignedVal = java.lang.Long.parseUnsignedLong(decimalStr.trim())
            val bytes = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(unsignedVal)
                .array()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validates or normalizes user input: accepts either valid 8-byte Base64, 16-char Hex, or 64-bit Decimal Account ID.
     */
    fun normalizeToValidBase64(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 1. Check if already valid 8-byte Base64
        try {
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            if (decoded != null && decoded.size == 8) {
                return Base64.encodeToString(decoded, Base64.NO_WRAP)
            }
        } catch (_: Exception) {}

        // 2. Check if unsigned 64-bit decimal ID
        if (trimmed.all { it.isDigit() } && trimmed.length in 10..22) {
            decimalAccountIdToBase64(trimmed)?.let { return it }
        }

        // 3. Check if 16-char hex ID (e.g. from lookup tools)
        val cleanHex = trimmed.removePrefix("0x").replace("-", "").replace(" ", "")
        if (cleanHex.length == 16 && cleanHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return try {
                val unsignedVal = java.lang.Long.parseUnsignedLong(cleanHex, 16)
                val bytes = ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(unsignedVal)
                    .array()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    /**
     * Exchanges the OAuth `?code=...` returned by Sony Remote Play Login WebView for the user's Account ID
     * and converts it directly to 8-byte Base64.
     */
    suspend fun exchangeOAuthCodeForBase64Id(code: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val body = "code=" + URLEncoder.encode(code, "UTF-8") +
                "&redirect_uri=" + URLEncoder.encode(SONY_REDIRECT_PREFIX, "UTF-8") +
                "&grant_type=authorization_code" +
                "&token_format=jwt"

            val tokenConn = (URL(SONY_TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Basic $SONY_BASIC_AUTH")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "PlayStation/21090100 Android/13")
            }
            tokenConn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = tokenConn.responseCode
            if (status !in 200..299) {
                val err = tokenConn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $status"
                tokenConn.disconnect()
                return@withContext Result.failure(IllegalStateException("ล็อกอิน PSN ไม่สำเร็จ ($status): $err"))
            }

            val tokenResp = tokenConn.inputStream.bufferedReader().use { it.readText() }
            tokenConn.disconnect()
            val tokenJson = JSONObject(tokenResp)
            val accessToken = tokenJson.optString("access_token", "")

            // Try extracting account_id directly from JWT payload first!
            val jwtParts = accessToken.split(".")
            if (jwtParts.size >= 2) {
                val payloadJsonStr = String(
                    Base64.decode(jwtParts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                val payload = JSONObject(payloadJsonStr)
                val accountIdDec = payload.optString("account_id", "")
                val onlineId = payload.optString("online_id", "PSN Account")
                if (accountIdDec.isNotEmpty()) {
                    val b64 = decimalAccountIdToBase64(accountIdDec)
                    if (b64 != null) {
                        return@withContext Result.success(b64 to onlineId)
                    }
                }
            }

            // Fallback: Query Sony DMS / Accounts endpoint with Bearer token
            val profConn = (URL("https://dms.api.playstation.com/api/v1/devices/accounts/me").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (profConn.responseCode in 200..299) {
                val profJson = JSONObject(profConn.inputStream.bufferedReader().use { it.readText() })
                profConn.disconnect()
                val accId = profJson.optString("accountId", "")
                val b64 = decimalAccountIdToBase64(accId)
                if (b64 != null) {
                    return@withContext Result.success(b64 to "PSN Verified")
                }
            } else {
                profConn.disconnect()
            }

            Result.failure(IllegalStateException("ไม่พบ Account ID จากเซสชัน PSN"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
