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
 * directly inside TIBANSUB using the exact official Sony Remote Play OAuth flow (chiaki-ng verified):
 * 1. User signs in via SONY_OAUTH_AUTHORIZE_URL -> redirected to remoteplay.dl.playstation.net/remoteplay/redirect?code=...
 * 2. POST https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token (Basic client_id:client_secret) -> access_token
 * 3. GET https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token/{access_token} (same Basic Auth) -> user_id (uint64)
 * 4. Encode uint64 little-endian 8 bytes -> Base64!
 */
object PsnAccountHelper {

    private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"

    const val SONY_REDIRECT_PREFIX = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val SONY_TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"

    val SONY_OAUTH_AUTHORIZE_URL: String =
        "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
            "?service_entity=urn:service-entity:psn" +
            "&response_type=code" +
            "&client_id=$CLIENT_ID" +
            "&redirect_uri=$SONY_REDIRECT_PREFIX" +
            "&scope=psn:clientapp" +
            "&request_locale=en_US" +
            "&ui=pr" +
            "&service_logo=ps" +
            "&layout_type=popup" +
            "&smcid=remoteplay" +
            "&prompt=always" +
            "&PlatformPrivacyWs1=minimal&"

    private fun basicAuthHeader(): String {
        val raw = "$CLIENT_ID:$CLIENT_SECRET".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /**
     * Extracts OAuth `?code=...` from either a full redirect URL or a raw code string.
     */
    fun extractCodeFromRedirectOrInput(urlOrCode: String): String? {
        val trimmed = urlOrCode.trim()
        if (trimmed.isEmpty()) return null
        val regex = Regex("[?&]code=([^&#\\s]+)")
        val m = regex.find(trimmed)
        if (m != null) {
            return try {
                java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
            } catch (_: Exception) {
                m.groupValues[1]
            }
        }
        return null
    }

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
     * Validates or normalizes user input: accepts valid 8-byte Base64, 16-char Hex, or 64-bit Decimal Account ID.
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

        // 3. Check if 16-char hex ID
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
     * and converts it directly to 8-byte little-endian Base64.
     * Returns Pair(base64AccountId, displayOnlineIdOrDecimal).
     */
    suspend fun exchangeOAuthCodeForBase64Id(code: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val authHeader = basicAuthHeader()
            val body = "grant_type=authorization_code" +
                "&code=" + URLEncoder.encode(code.trim(), "UTF-8") +
                "&redirect_uri=" + URLEncoder.encode(SONY_REDIRECT_PREFIX, "UTF-8") + "&"

            val tokenConn = (URL(SONY_TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            tokenConn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = tokenConn.responseCode
            if (status !in 200..299) {
                val err = tokenConn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $status"
                tokenConn.disconnect()
                return@withContext Result.failure(
                    IllegalStateException("แลกโทเคน PSN ไม่สำเร็จ (HTTP $status): $err")
                )
            }

            val tokenResp = tokenConn.inputStream.bufferedReader().use { it.readText() }
            tokenConn.disconnect()
            val tokenJson = JSONObject(tokenResp)
            val accessToken = tokenJson.optString("access_token", "").trim()
            if (accessToken.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("ไม่ได้รับ access_token จาก Sony"))
            }

            // Primary verified method (exact chiaki-ng psn-account-id.py flow):
            // GET https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token/{access_token} with Basic Auth
            val infoUrl = "$SONY_TOKEN_URL/${URLEncoder.encode(accessToken, "UTF-8")}"
            val infoConn = (URL(infoUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Authorization", authHeader)
                setRequestProperty("Accept", "application/json")
            }
            val infoStatus = infoConn.responseCode
            if (infoStatus in 200..299) {
                val infoText = infoConn.inputStream.bufferedReader().use { it.readText() }
                infoConn.disconnect()
                val infoJson = JSONObject(infoText)
                val userIdStr = infoJson.optString("user_id", "").ifBlank {
                    infoJson.optString("account_id", "")
                }.trim()
                val onlineId = infoJson.optString("online_id", "").ifBlank { "PSN ID $userIdStr" }
                if (userIdStr.isNotEmpty()) {
                    val b64 = decimalAccountIdToBase64(userIdStr)
                    if (b64 != null) {
                        return@withContext Result.success(b64 to onlineId)
                    }
                }
            } else {
                infoConn.disconnect()
            }

            // Fallback 1: Check JWT payload if Sony returns JWT format
            val jwtParts = accessToken.split(".")
            if (jwtParts.size >= 2) {
                try {
                    val payloadJsonStr = String(
                        Base64.decode(jwtParts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                        Charsets.UTF_8
                    )
                    val payload = JSONObject(payloadJsonStr)
                    val accountIdDec = payload.optString("account_id", "")
                        .ifBlank { payload.optString("user_id", "") }
                    val onlineId = payload.optString("online_id", "PSN Account")
                    if (accountIdDec.isNotEmpty()) {
                        decimalAccountIdToBase64(accountIdDec)?.let { b64 ->
                            return@withContext Result.success(b64 to onlineId)
                        }
                    }
                } catch (_: Exception) {}
            }

            // Fallback 2: Query Sony DMS / Accounts endpoint with Bearer token
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
                decimalAccountIdToBase64(accId)?.let { b64 ->
                    return@withContext Result.success(b64 to "PSN Verified")
                }
            } else {
                profConn.disconnect()
            }

            Result.failure(IllegalStateException("ไม่พบ user_id ในคำตอบจากเซิร์ฟเวอร์ Sony"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
