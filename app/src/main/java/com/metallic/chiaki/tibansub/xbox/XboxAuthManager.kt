package com.metallic.chiaki.tibansub.xbox

import android.content.Context
import android.os.Parcelable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Parcelize
data class XboxConsoleItem(
    val serverId: String,
    val deviceName: String,
    val consoleType: String,
    val powerState: String
) : Parcelable

@Parcelize
data class XboxSessionLaunchConfig(
    val serverId: String,
    val deviceName: String,
    val gsToken: String,
    val regionBaseUri: String
) : Parcelable

/**
 * Handles Microsoft Live OAuth2 -> Xbox Live User Token -> XSTS Streaming Token -> xHome GSToken,
 * console discovery, and xHome WebRTC signaling REST calls for Xbox Series X|S and Xbox One.
 */
class XboxAuthManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "tibansub_xbox_prefs"
        private const val CLIENT_ID = "000000004c20a908"
        const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"

        val OAUTH_AUTHORIZE_URL: String =
            "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=$CLIENT_ID" +
                "&response_type=code" +
                "&approval_prompt=auto" +
                "&scope=" + URLEncoder.encode("Xboxlive.signin Xboxlive.offline_access", "UTF-8") +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSavedLogin(): Boolean = !prefs.getString("refresh_token", null).isNullOrBlank()

    fun getSavedGamertag(): String = prefs.getString("gamertag", "") ?: ""

    fun signOut() {
        prefs.edit().clear().apply()
    }

    suspend fun loginWithAuthCode(code: String): Result<XboxSessionTokens> = withContext(Dispatchers.IO) {
        try {
            val form = "client_id=$CLIENT_ID" +
                "&code=" + URLEncoder.encode(code, "UTF-8") +
                "&grant_type=authorization_code" +
                "&scope=" + URLEncoder.encode("Xboxlive.signin Xboxlive.offline_access", "UTF-8") +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")

            val oauthJson = postForm("https://login.live.com/oauth20_token.srf", form)
            val accessToken = oauthJson.getString("access_token")
            val refreshToken = oauthJson.optString("refresh_token", "")
            if (refreshToken.isNotBlank()) {
                prefs.edit().putString("refresh_token", refreshToken).apply()
            }
            exchangeMicrosoftAccessTokenForXHome(accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreSessionFromRefreshToken(): Result<XboxSessionTokens> = withContext(Dispatchers.IO) {
        try {
            val rt = prefs.getString("refresh_token", null)
                ?: return@withContext Result.failure(IllegalStateException("ยังไม่ได้ล็อกอินบัญชี Microsoft"))

            val form = "client_id=$CLIENT_ID" +
                "&refresh_token=" + URLEncoder.encode(rt, "UTF-8") +
                "&grant_type=refresh_token" +
                "&scope=" + URLEncoder.encode("Xboxlive.signin Xboxlive.offline_access", "UTF-8") +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")

            val oauthJson = postForm("https://login.live.com/oauth20_token.srf", form)
            val accessToken = oauthJson.getString("access_token")
            val newRefresh = oauthJson.optString("refresh_token", "")
            if (newRefresh.isNotBlank()) {
                prefs.edit().putString("refresh_token", newRefresh).apply()
            }
            exchangeMicrosoftAccessTokenForXHome(accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class XboxSessionTokens(
        val gsToken: String,
        val regionBaseUri: String,
        val gamertag: String
    )

    private fun exchangeMicrosoftAccessTokenForXHome(msAccessToken: String): Result<XboxSessionTokens> {
        // 1. User Token
        val userReq = JSONObject().apply {
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=$msAccessToken")
            })
        }
        val userResp = postJson("https://user.auth.xboxlive.com/user/authenticate", userReq, mapOf("x-xbl-contract-version" to "1"))
        val userToken = userResp.getString("Token")
        val uhs = userResp.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")

        // 2. XSTS Streaming Token (RelyingParty = http://gssv.xboxlive.com/)
        val xstsReq = JSONObject().apply {
            put("RelyingParty", "http://gssv.xboxlive.com/")
            put("TokenType", "JWT")
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().put(userToken))
            })
        }
        val xstsResp = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsReq, mapOf("x-xbl-contract-version" to "1"))
        val xstsToken = xstsResp.getString("Token")
        val xuiObj = xstsResp.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)
        val gamertag = xuiObj.optString("gtg", uhs)
        prefs.edit().putString("gamertag", gamertag).apply()

        // 3. xHome Streaming Login -> gsToken & region URI
        val xhomeReq = JSONObject().apply {
            put("token", xstsToken)
            put("offeringId", "xhome")
        }
        val xhomeResp = postJson(
            "https://xhome.gssv-play-prod.xboxlive.com/v5/login/user",
            xhomeReq,
            mapOf("Content-Type" to "application/json")
        )
        val gsToken = xhomeResp.getString("gsToken")
        var baseUri = "https://uks.core.gssv-play-prodxhome.xboxlive.com"
        val regions = xhomeResp.optJSONObject("offeringSettings")?.optJSONArray("regions")
        if (regions != null && regions.length() > 0) {
            for (i in 0 until regions.length()) {
                val r = regions.getJSONObject(i)
                val uri = r.optString("baseUri", "")
                if (r.optBoolean("isDefault", false) && uri.isNotBlank()) {
                    baseUri = uri
                    break
                } else if (uri.isNotBlank()) {
                    baseUri = uri
                }
            }
        }
        return Result.success(XboxSessionTokens(gsToken = gsToken, regionBaseUri = baseUri.trimEnd('/'), gamertag = gamertag))
    }

    suspend fun fetchConsoles(tokens: XboxSessionTokens): Result<List<XboxConsoleItem>> = withContext(Dispatchers.IO) {
        try {
            val resp = getJson(
                "${tokens.regionBaseUri}/v6/servers/home",
                mapOf("Authorization" to "Bearer ${tokens.gsToken}")
            )
            val results = resp.optJSONArray("results") ?: JSONArray()
            val list = mutableListOf<XboxConsoleItem>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                list.add(
                    XboxConsoleItem(
                        serverId = item.optString("serverId", ""),
                        deviceName = item.optString("deviceName", "Xbox Console"),
                        consoleType = item.optString("consoleType", "XboxSeriesX"),
                        powerState = item.optString("powerState", "ConnectedStandby")
                    )
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Negotiates an xHome WebRTC streaming session: creates the session, polls until Provisioned,
     * submits the browser SDP Offer & ICE candidates, and returns the remote SDP Answer + ICE list.
     */
    suspend fun negotiateWebRtcSession(
        config: XboxSessionLaunchConfig,
        sdpOffer: String,
        iceCandidatesJsonArray: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val headers = mapOf(
                "Authorization" to "Bearer ${config.gsToken}",
                "Content-Type" to "application/json",
                "X-MS-Device-Info" to "{\"dev\":{\"os\":{\"name\":\"android\",\"ver\":\"13.0\"},\"hw\":{\"make\":\"Microsoft\",\"model\":\"Surface\"}}}"
            )
            val playReq = JSONObject().apply {
                put("titleId", "")
                put("systemUpdateGroup", "")
                put("settings", JSONObject().apply {
                    put("nanoVersion", "V3;WebrtcTransport.dll")
                    put("enableTextToSpeech", false)
                    put("highContrast", 0)
                    put("locale", "en-US")
                    put("useIceConnection", false)
                    put("timezoneOffsetMinutes", 420)
                    put("sdkType", "web")
                    put("osName", "android")
                })
                put("serverId", config.serverId)
                put("fallbackRegionNames", JSONArray())
            }
            val startResp = postJson("${config.regionBaseUri}/v5/sessions/home/play", playReq, headers)
            val sessionPath = startResp.getString("sessionPath")
            val sessionBase = "${config.regionBaseUri}/$sessionPath"

            // Poll until Provisioned or ReadyToConnect
            var ready = false
            for (attempt in 0 until 35) {
                val stateResp = getJson("$sessionBase/state", headers)
                val state = stateResp.optString("state", "")
                if (state.equals("Provisioned", true) || state.equals("ReadyToConnect", true)) {
                    ready = true
                    break
                }
                if (state.equals("Failed", true)) {
                    val errDetails = stateResp.optJSONObject("errorDetails")?.optString("message", "Console unreachable")
                    return@withContext Result.failure(IllegalStateException("เครื่อง Xbox ปฏิเสธการเชื่อมต่อ: $errDetails"))
                }
                delay(1000)
            }
            if (!ready) {
                return@withContext Result.failure(IllegalStateException("หมดเวลารอเครื่อง Xbox ตื่น (กรุณาเปิดเครื่อง Xbox ก่อนลองใหม่)"))
            }

            // Send SDP Offer
            val sdpPayload = JSONObject().apply {
                put("messageType", "offer")
                put("sdp", sdpOffer)
                put("configuration", JSONObject().apply {
                    put("chatConfiguration", JSONObject().apply {
                        put("bytesPerSample", 2)
                        put("expectedClipDurationMs", 20)
                        put("format", JSONObject().apply {
                            put("codec", "opus")
                            put("container", "webm")
                        })
                        put("numChannels", 1)
                        put("sampleFrequencyHz", 24000)
                    })
                    put("chat", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                    put("control", JSONObject().put("minVersion", 1).put("maxVersion", 3))
                    put("input", JSONObject().put("minVersion", 1).put("maxVersion", 8))
                    put("message", JSONObject().put("minVersion", 1).put("maxVersion", 1))
                })
            }
            postRaw("$sessionBase/sdp", sdpPayload.toString(), headers)

            // Poll SDP Answer
            var answerSdp = ""
            for (attempt in 0 until 20) {
                delay(600)
                val sdpGet = getJson("$sessionBase/sdp", headers)
                val exRespStr = sdpGet.optString("exchangeResponse", "")
                if (exRespStr.isNotBlank()) {
                    val exJson = if (exRespStr.trim().startsWith("{")) JSONObject(exRespStr) else sdpGet
                    answerSdp = exJson.optString("sdp", "")
                    if (answerSdp.isNotBlank()) break
                }
            }
            if (answerSdp.isBlank()) {
                return@withContext Result.failure(IllegalStateException("ไม่ได้รับ SDP Answer จากเครื่อง Xbox"))
            }

            // Exchange ICE candidates
            val icePost = JSONObject().apply {
                put("messageType", "iceCandidate")
                put("candidates", JSONArray(iceCandidatesJsonArray))
            }
            postRaw("$sessionBase/ice", icePost.toString(), headers)

            var remoteIceArray = "[]"
            for (attempt in 0 until 15) {
                delay(500)
                val iceGet = getJson("$sessionBase/ice", headers)
                val exIce = iceGet.optString("exchangeResponse", "")
                if (exIce.isNotBlank()) {
                    remoteIceArray = exIce
                    break
                }
            }

            Result.success(answerSdp to remoteIceArray)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun postForm(urlStr: String, formBody: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 12000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    private fun postJson(urlStr: String, body: JSONObject, extraHeaders: Map<String, String>): JSONObject {
        val text = postRaw(urlStr, body.toString(), extraHeaders)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun postRaw(urlStr: String, rawBody: String, extraHeaders: Map<String, String>): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(rawBody.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("Xbox API HTTP $code: $text")
        return text
    }

    private fun getJson(urlStr: String, extraHeaders: Map<String, String>): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code == 204 || text.isBlank()) return JSONObject()
        if (code !in 200..299) throw IllegalStateException("Xbox GET HTTP $code: $text")
        return JSONObject(text)
    }
}
