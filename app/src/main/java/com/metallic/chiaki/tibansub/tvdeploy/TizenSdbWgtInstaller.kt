package com.metallic.chiaki.tibansub.tvdeploy

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.metallic.chiaki.stream.TvOverlayServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Full 1:1 Kotlin port of the proven Apps2Samsung / TizenSdb backend engine:
 * - Pre-flight check against TV REST API (`http://<tvIp>:8001/api/v2/`) to verify `developerMode` & `developerIP`
 * - Persistent multiplexed `SdbTcpDevice` with 3x automatic handshake retry (handles sdbd mid-handshake resets)
 * - Full SDB `CMD_AUTH` RSA-2048 handshake (`AUTH_TOKEN` -> `AUTH_SIGNATURE` / `AUTH_PUBLICKEY`) for modern Tizen 6.5-9 TVs
 * - Dynamic `capability:` query to read the TV's real `sdk_toolpath` (`/opt/usr/apps/tmp` or `/home/owner/share/tmp/sdk_tools`)
 * - Official Samsung OAuth2 callback (`127.0.0.1:4794/signin/callback`) + `svdca.samsungqbe.com/apis/v3` cert issuance
 * - Exact `TizenWgtSigner` W3C XML-DSig RSA-SHA512 + C14N signing (author-signature.xml first, signature1.xml second)
 * - Exact `SdbTcpDevice.PushAsync` (`sync:` SEND -> DATA -> DONE -> OKAY) + `0 vd_appinstall <appId> <remotePath>`
 */
object TizenSdbWgtInstaller {

    const val SAMSUNG_OAUTH_CALLBACK_PORT = 4794
    const val SAMSUNG_OAUTH_CALLBACK_URL = "http://localhost:$SAMSUNG_OAUTH_CALLBACK_PORT/signin/callback"
    const val SAMSUNG_OAUTH_LOGIN_URL =
        "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate" +
            "?locale=&clientId=v285zxnl3h" +
            "&redirect_uri=http%3A%2F%2Flocalhost%3A4794%2Fsignin%2Fcallback" +
            "&state=accountcheckdogeneratedstatetext&tokenType=TOKEN"

    private const val TIZEN_APP_ID = "TibanTV001.TIBANSUB"
    private const val FALLBACK_SDK_TOOLPATH = "/home/owner/share/tmp/sdk_tools"

    private const val SDB_PORT = 26101
    private const val CMD_CNXN = 0x4e584e43 // "CNXN"
    private const val CMD_AUTH = 0x48545541 // "AUTH"
    private const val CMD_OPEN = 0x4e45504f // "OPEN"
    private const val CMD_OKAY = 0x59414b4f // "OKAY"
    private const val CMD_WRTE = 0x45545257 // "WRTE"
    private const val CMD_CLSE = 0x45534c43 // "CLSE"
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_PUBLICKEY = 3
    private const val CLIENT_VERSION = 0x01000000
    private const val DEFAULT_MAX_DATA = 4096
    private const val SYNC_MAX_DATA = 64 * 1024

    private const val PREFS_CERT = "tibansub_tizen_cert_prefs"
    private const val KEY_ACCESS_TOKEN = "samsung_access_token"
    private const val KEY_USER_ID = "samsung_user_id"
    private const val KEY_EMAIL = "samsung_email"
    private const val KEY_ISSUED_DUID = "issued_duid"

    private const val CERT_FILE_AUTHOR_P12 = "tizen_author.p12"
    private const val CERT_FILE_DIST_P12 = "tizen_distributor.p12"
    private const val ISSUED_AUTHOR_KEY = "samsung_issued_author.pkcs8"
    private const val ISSUED_AUTHOR_CERT = "samsung_issued_author.cer"
    private const val ISSUED_DIST_KEY = "samsung_issued_dist.pkcs8"
    private const val ISSUED_DIST_CERT = "samsung_issued_dist.cer"
    private const val SDB_AUTH_PRIV_KEY = "sdb_adbkey.pkcs8"
    private const val SDB_AUTH_PUB_KEY = "sdb_adbkey.x509"

    data class SamsungAccountSession(
        val accessToken: String,
        val userId: String,
        val email: String
    )

    fun saveSamsungSession(context: Context, session: SamsungAccountSession) {
        context.getSharedPreferences(PREFS_CERT, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .remove(KEY_ISSUED_DUID)
            .apply()
        // Clear any previously cached issued certs so a fresh sign-in always re-issues cleanly
        listOf(ISSUED_AUTHOR_KEY, ISSUED_AUTHOR_CERT, ISSUED_DIST_KEY, ISSUED_DIST_CERT).forEach { name ->
            try { File(context.filesDir, name).delete() } catch (_: Exception) {}
        }
    }

    fun getSavedSamsungSession(context: Context): SamsungAccountSession? {
        val prefs = context.getSharedPreferences(PREFS_CERT, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_ACCESS_TOKEN, "")?.trim().orEmpty()
        val uid = prefs.getString(KEY_USER_ID, "")?.trim().orEmpty()
        val email = prefs.getString(KEY_EMAIL, "")?.trim().orEmpty()
        if (token.isEmpty() || uid.isEmpty()) return null
        return SamsungAccountSession(token, uid, email.ifBlank { "Samsung Developer" })
    }

    fun hasCustomCertificate(context: Context): Boolean =
        File(context.filesDir, CERT_FILE_AUTHOR_P12).exists() ||
            (File(context.filesDir, ISSUED_AUTHOR_CERT).exists() && File(context.filesDir, ISSUED_DIST_CERT).exists())

    fun getIssuedDuid(context: Context): String? =
        context.getSharedPreferences(PREFS_CERT, Context.MODE_PRIVATE)
            .getString(KEY_ISSUED_DUID, null)?.takeIf { it.isNotBlank() }

    // ==================== Samsung Account OAuth Loopback Server ====================

    class SamsungOAuthCallbackServer(
        private val onSessionCaptured: (SamsungAccountSession) -> Unit,
        private val onError: (String) -> Unit
    ) {
        @Volatile
        private var running = false
        private var serverSocket: ServerSocket? = null

        fun start() {
            if (running) return
            running = true
            Thread {
                try {
                    val ss = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), SAMSUNG_OAUTH_CALLBACK_PORT))
                    }
                    serverSocket = ss
                    while (running) {
                        val client = try { ss.accept() } catch (_: Exception) { break }
                        handleClient(client)
                    }
                } catch (e: Exception) {
                    if (running) onError("เปิดพอร์ต Callback 4794 ไม่สำเร็จ: ${e.message}")
                }
            }.start()
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 6000
                val ins = socket.getInputStream()
                val headerBuf = ByteArrayOutputStream()
                var state = 0
                while (headerBuf.size() < 32768) {
                    val b = ins.read()
                    if (b == -1) break
                    headerBuf.write(b)
                    state = when {
                        state == 0 && b == '\r'.code -> 1
                        state == 1 && b == '\n'.code -> 2
                        state == 2 && b == '\r'.code -> 3
                        state == 3 && b == '\n'.code -> 4
                        else -> 0
                    }
                    if (state == 4) break
                }
                val headerText = headerBuf.toString("UTF-8")
                val firstLine = headerText.lineSequence().firstOrNull().orEmpty()
                var contentLength = 0
                for (line in headerText.lineSequence()) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val bodyBytes = ByteArray(contentLength.coerceIn(0, 131072))
                var readTotal = 0
                while (readTotal < bodyBytes.size) {
                    val r = ins.read(bodyBytes, readTotal, bodyBytes.size - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                val bodyStr = String(bodyBytes, 0, readTotal, Charsets.UTF_8)
                val queryPart = firstLine.split(" ").getOrNull(1)?.substringAfter("?", "").orEmpty()
                val combinedParams = listOf(bodyStr, queryPart).filter { it.isNotBlank() }.joinToString("&")
                val session = parseSamsungCallbackPayload(combinedParams)

                val html = if (session != null) {
                    "<html><body style='background:#130A2B;color:#fff;font-family:sans-serif;text-align:center;padding:40px;'>" +
                        "<h2>ล็อกอิน Samsung สำเร็จแล้ว</h2><p>${session.email}</p><p>กำลังกลับสู่หน้าติดตั้งทีวี...</p></body></html>"
                } else {
                    "<html><body style='background:#130A2B;color:#fff;font-family:sans-serif;text-align:center;padding:40px;'>" +
                        "<h2>ไม่พบข้อมูลโทเคนจาก Samsung</h2><p>กรุณาลองล็อกอินใหม่อีกครั้ง</p></body></html>"
                }
                val respBytes = html.toByteArray(Charsets.UTF_8)
                val httpResp = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: ${respBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                socket.getOutputStream().use { out ->
                    out.write(httpResp.toByteArray(Charsets.UTF_8))
                    out.write(respBytes)
                    out.flush()
                }
                socket.close()

                if (session != null) {
                    onSessionCaptured(session)
                } else if (firstLine.contains("/signin/callback")) {
                    onError("Samsung ไม่ได้ส่งค่า access_token กลับมา กรุณาลองล็อกอินใหม่อีกครั้ง")
                }
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        fun stop() {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
        }
    }

    fun parseSamsungCallbackPayload(raw: String): SamsungAccountSession? {
        if (raw.isBlank()) return null
        val params = mutableMapOf<String, String>()
        raw.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = pair.substring(0, idx).trim()
                val v = try { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") } catch (_: Exception) { pair.substring(idx + 1) }
                params[k] = v
            }
        }

        val codeParam = params["code"]?.trim()
        if (!codeParam.isNullOrEmpty() && codeParam.startsWith("{")) {
            try {
                val json = JSONObject(codeParam)
                val token = json.optString("access_token", "").trim()
                val uid = json.optString("userId", "").ifBlank { json.optString("user_id", "") }.trim()
                val email = json.optString("inputEmailID", "").ifBlank { json.optString("email", "") }.trim()
                if (token.isNotEmpty() && uid.isNotEmpty()) {
                    return SamsungAccountSession(token, uid, email.ifBlank { "Samsung Developer" })
                }
            } catch (_: Exception) {}
        }

        val directToken = params["access_token"]?.trim().orEmpty()
        val directUid = (params["userId"] ?: params["user_id"])?.trim().orEmpty()
        val directEmail = (params["inputEmailID"] ?: params["email"])?.trim().orEmpty()
        if (directToken.isNotEmpty() && directUid.isNotEmpty()) {
            return SamsungAccountSession(directToken, directUid, directEmail.ifBlank { "Samsung Developer" })
        }

        return null
    }

    suspend fun importUserCertificateP12(
        context: Context,
        uri: Uri,
        password: String,
        isDistributor: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IllegalArgumentException("ไม่สามารถอ่านไฟล์ .p12 ได้"))
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(bytes.inputStream(), password.toCharArray())
            val alias = ks.aliases().toList().firstOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("ไม่พบใบรับรองในไฟล์ .p12"))

            val targetName = if (isDistributor) CERT_FILE_DIST_P12 else CERT_FILE_AUTHOR_P12
            File(context.filesDir, targetName).writeBytes(bytes)
            val passKey = if (isDistributor) "dist_pass" else "author_pass"
            context.getSharedPreferences(PREFS_CERT, Context.MODE_PRIVATE)
                .edit()
                .putString(passKey, password)
                .apply()
            Result.success("บันทึกใบรับรอง ($alias) เรียบร้อยแล้ว")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== Pre-Flight TV REST Check ====================

    private data class TvRestStatus(
        val reachable: Boolean,
        val developerMode: String?,
        val developerIp: String?,
        val duid: String?,
        val modelName: String?
    )

    private fun querySamsungTvRestInfo(tvIp: String): TvRestStatus {
        return try {
            val conn = (URL("http://$tvIp:8001/api/v2/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
            }
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val dev = json.optJSONObject("device")
                val devMode = dev?.optString("developerMode", "")?.trim()
                val devIp = dev?.optString("developerIP", "")?.trim()
                val rawDuid = dev?.optString("duid", "")?.removePrefix("uuid:")?.trim()
                val model = dev?.optString("modelName", "")?.trim()
                TvRestStatus(true, devMode, devIp, rawDuid, model)
            } else {
                conn.disconnect()
                TvRestStatus(false, null, null, null, null)
            }
        } catch (_: Exception) {
            TvRestStatus(false, null, null, null, null)
        }
    }

    // ==================== Full Apps2Samsung Install Pipeline ====================

    suspend fun installEmbeddedWgtToSamsungTv(
        context: Context,
        tvIp: String,
        fallbackDuid: String? = null,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val signedWgtFile = File(context.cacheDir, "tibansub.wgt")
        try {
            val cleanIp = tvIp.trim()
            if (cleanIp.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("กรุณาระบุเลข IP ของทีวี Samsung"))
            }

            // Step 0: Pre-flight check TV Developer Mode & Host PC IP via Port 8001 REST API
            onProgress("1/6 ตรวจสอบสถานะ Developer Mode ของทีวี Samsung...")
            val phoneIp = TvOverlayServer.getLocalWifiIp(context)
            val restInfo = querySamsungTvRestInfo(cleanIp)
            if (restInfo.reachable) {
                if (restInfo.developerMode == "0") {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "ทีวียังปิด Developer Mode อยู่ (developerMode=0)\nกรุณาเปิดเมนู Apps บนทีวี > กดรีโมต 1-2-3-4-5 > เปิด ON > กรอก IP $phoneIp แล้วกดค้างปุ่มปิดทีวี 5 วิเพื่อรีสตาร์ท"
                        )
                    )
                }
                if (!restInfo.developerIp.isNullOrBlank() &&
                    restInfo.developerIp != "0.0.0.0" &&
                    phoneIp.count { it == '.' } == 3 &&
                    restInfo.developerIp != phoneIp
                ) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "IP โหมดนักพัฒนาบนทีวีไม่ตรงกับมือถือ!\n" +
                                "• บนทีวีตั้งค่าไว้เป็น: ${restInfo.developerIp}\n" +
                                "• แต่มือถือเครื่องนี้คือ: $phoneIp\n" +
                                "กรุณากด 1-2-3-4-5 บนทีวี เปลี่ยน Host PC IP เป็น $phoneIp แล้วรีสตาร์ททีวี 1 รอบ"
                        )
                    )
                }
            }

            // Step 1: Connect using persistent multiplexed SdbTcpDevice (3x retry + full RSA AUTH)
            onProgress("2/6 กำลังเชื่อมต่อ SDB พอร์ต 26101 (รองรับ RSA AUTH)...")
            SdbTcpDevice(context, cleanIp, SDB_PORT).use { sdb ->
                sdb.connect()

                // Step 2: Query capability (exact sdk_toolpath) & real TV DUID over the SAME persistent connection
                val caps = try {
                    sdb.capability()
                } catch (_: Exception) {
                    emptyMap()
                }
                Thread.sleep(120)

                val sdkToolPath = caps["sdk_toolpath"]?.trim()?.trimEnd('/')
                    ?.takeIf { it.startsWith("/") }
                    ?: FALLBACK_SDK_TOOLPATH

                val duidOut = sdb.shellCommand("0 getduid").trim()
                val resolvedDuid = duidOut.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.length >= 10 && !it.contains("not found", true) && !it.contains("error", true) && !it.equals("closed", true) }
                    ?: restInfo.duid?.takeIf { it.length >= 10 }
                    ?: fallbackDuid?.takeIf { it.length >= 10 }
                    ?: return@withContext Result.failure(
                        IllegalStateException("อ่านรหัส DUID จากทีวีไม่ได้ (ตอบกลับ: '$duidOut')")
                    )

                // Step 3: Issue or load Samsung Author + Distributor certificates for this exact DUID
                onProgress("3/6 พบ DUID ($resolvedDuid) | Path: $sdkToolPath — กำลังเตรียมใบรับรอง...")
                val signingBundle = resolveSigningBundle(context, resolvedDuid, onProgress)
                    ?: return@withContext Result.failure(
                        IllegalStateException("ยังไม่มีใบรับรอง Samsung กรุณากด 'ล็อกอิน Samsung' ด้านบนก่อนแล้วกดติดตั้งอีกครั้ง")
                    )

                // Step 4: Sign embedded .wgt using exact TizenWgtSigner format
                onProgress("4/6 กำลังเซ็นไฟล์ TIBANSUB TV (.wgt) ด้วยใบรับรอง DUID...")
                signEmbeddedWgtApps2SamsungFormat(context, signingBundle, signedWgtFile)
                val wgtSizeKb = (signedWgtFile.length() / 1024).coerceAtLeast(1)

                // Step 5: Push device-profile.xml (if available) & tibansub.wgt via Apps2Samsung PushAsync
                onProgress("5/6 กำลังส่งไฟล์เข้าทีวี ($wgtSizeKb KB -> $sdkToolPath/tibansub.wgt)...")
                val distPem = signingBundle.distributorRawPem
                if (!distPem.isNullOrBlank()) {
                    try {
                        sdb.pushBytes(distPem.toByteArray(Charsets.UTF_8), "$sdkToolPath/device-profile.xml")
                        Thread.sleep(120)
                    } catch (_: Exception) {
                        // Optional on some Tizen models
                    }
                }

                val remoteWgtPath = "$sdkToolPath/tibansub.wgt"
                sdb.pushFile(signedWgtFile, remoteWgtPath)
                Thread.sleep(150)

                // Step 6: Install using exact Apps2Samsung command: `0 vd_appinstall <appId> <remotePath>`
                onProgress("6/6 กำลังติดตั้ง TIBANSUB TV บนทีวี (รอ 5-15 วินาที)...")
                val installOut = sdb.shellCommand("0 vd_appinstall $TIZEN_APP_ID $remoteWgtPath", timeoutMs = 60000)
                val cleanLog = installOut.trim()
                val lower = cleanLog.lowercase()

                val hasError = lower.contains("fail") ||
                    lower.contains("error") ||
                    Regex("\\[-\\d+\\]").containsMatchIn(cleanLog)
                val hasSuccess = lower.contains("val[ok]") ||
                    lower.contains("install completed") ||
                    (lower.contains("spend time") && !hasError)

                if (hasSuccess && !hasError) {
                    // Clean up staged wgt & auto-launch app on TV (just like Apps2Samsung!)
                    try {
                        Thread.sleep(150)
                        sdb.shellCommand("0 rmfile", timeoutMs = 5000)
                    } catch (_: Exception) {}
                    try {
                        Thread.sleep(150)
                        sdb.shellCommand("0 was_execute $TIZEN_APP_ID", timeoutMs = 8000)
                    } catch (_: Exception) {}

                    Result.success("ติดตั้ง TIBANSUB TV บนทีวี Samsung ($cleanIp) สำเร็จแล้ว เปิดใช้งานได้จากเมนู Apps บนทีวีเลย")
                } else if (cleanLog.isEmpty()) {
                    Result.failure(
                        IllegalStateException(
                            "ทีวีไม่ได้ส่งผลลัพธ์การติดตั้งกลับมา กรุณาถอดปลั๊กทีวี 30 วินาทีแล้วเปิดใหม่ (หรือกดค้างปุ่มปิด 5 วิ) แล้วลองติดตั้งอีกครั้ง"
                        )
                    )
                } else {
                    val hint = when {
                        cleanLog.contains("-12") || lower.contains("cert") || lower.contains("signature") ->
                            "ทีวีแจ้งเตือนใบรับรอง (รหัส -12): กรุณากด 'สลับบัญชี' ล็อกอิน Samsung ใหม่อีกครั้งเพื่อให้ระบบออกใบรับรองใหม่ตรงกับ DUID ($resolvedDuid)\nข้อความทีวี: $cleanLog"
                        cleanLog.contains("-14") || lower.contains("privilege") ->
                            "ทีวีปฏิเสธสิทธิ์แอป (รหัส -14)\nข้อความทีวี: $cleanLog"
                        cleanLog.contains("-27") ->
                            "ทีวีหาไฟล์ไม่เจอ (รหัส -27): $cleanLog"
                        else -> "ติดตั้งไม่สำเร็จ — ทีวีตอบกลับ: $cleanLog"
                    }
                    Result.failure(IllegalStateException(hint))
                }
            }
        } catch (e: Exception) {
            val rawMsg = e.message ?: e.javaClass.simpleName
            val friendlyHint = if (rawMsg.contains("Connection reset", true) || rawMsg.contains("EOF", true)) {
                "ทีวีตัดการเชื่อมต่อ SDB ($rawMsg)\n" +
                    "สาเหตุที่พบบ่อยบนทีวี Samsung:\n" +
                    "1) เลข Host PC IP ใน Developer Mode (1-2-3-4-5) ไม่ตรงกับ IP มือถือเครื่องนี้\n" +
                    "2) ยังไม่ได้รีสตาร์ททีวีแบบกดค้างปุ่มปิด 5 วิ (หรือถอดปลั๊ก 30 วิ) หลังตั้งค่า IP\n" +
                    "3) มีโปรแกรมอื่นบนคอมพิวเตอร์/มือถือเปิดเชื่อมทีวีค้างอยู่"
            } else {
                "ติดตั้งลงทีวี Samsung ไม่สำเร็จ: $rawMsg"
            }
            Result.failure(IllegalStateException(friendlyHint))
        } finally {
            try { signedWgtFile.delete() } catch (_: Exception) {}
        }
    }

    // ==================== Samsung Certificate Issuance (svdca.samsungqbe.com) ====================

    private data class CertIdentity(
        val privateKey: PrivateKey,
        val certChainPemBodies: List<String>
    )

    private data class SigningBundle(
        val author: CertIdentity,
        val distributor: CertIdentity,
        val distributorRawPem: String? = null
    )

    private fun resolveSigningBundle(
        context: Context,
        duid: String,
        onProgress: (String) -> Unit
    ): SigningBundle? {
        val cachedDuid = getIssuedDuid(context)
        val authorKeyFile = File(context.filesDir, ISSUED_AUTHOR_KEY)
        val authorCertFile = File(context.filesDir, ISSUED_AUTHOR_CERT)
        val distKeyFile = File(context.filesDir, ISSUED_DIST_KEY)
        val distCertFile = File(context.filesDir, ISSUED_DIST_CERT)

        if (cachedDuid == duid && authorKeyFile.exists() && authorCertFile.exists() &&
            distKeyFile.exists() && distCertFile.exists()
        ) {
            try {
                return loadIssuedCertFiles(context, authorKeyFile, authorCertFile, distKeyFile, distCertFile)
            } catch (_: Exception) {}
        }

        val session = getSavedSamsungSession(context)
        if (session != null) {
            onProgress("3/6 กำลังขอใบรับรอง Samsung Developer สำหรับ DUID ($duid)...")
            val issued = issueOfficialSamsungCertificates(context, session, duid)
            if (issued != null) return issued
        }

        return loadImportedP12Bundle(context)
    }

    private fun extractPemCertBodies(pem: String): List<String> {
        val regex = Regex("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)
        val matches = regex.findAll(pem).map { it.groupValues[1].replace(Regex("[\\r\\n\\s]+"), "") }.filter { it.isNotEmpty() }.toList()
        return if (matches.isNotEmpty()) matches else listOf(stripPemHeaders(pem))
    }

    private fun buildCertChainWithCa(leafPem: String, caPem: String): List<String> {
        val list = ArrayList<String>()
        list.addAll(extractPemCertBodies(leafPem))
        val caBody = extractPemCertBodies(caPem).firstOrNull()
        if (!caBody.isNullOrEmpty() && list.none { it == caBody }) {
            list.add(caBody)
        }
        return list
    }

    private fun loadIssuedCertFiles(
        context: Context,
        authorKeyFile: File,
        authorCertFile: File,
        distKeyFile: File,
        distCertFile: File
    ): SigningBundle {
        val kf = KeyFactory.getInstance("RSA")
        val aKey = kf.generatePrivate(PKCS8EncodedKeySpec(authorKeyFile.readBytes()))
        val dKey = kf.generatePrivate(PKCS8EncodedKeySpec(distKeyFile.readBytes()))
        val aLeafPem = authorCertFile.readText(Charsets.UTF_8)
        val dLeafPem = distCertFile.readText(Charsets.UTF_8)
        val authorCaPem = readBundledCaPem(context, "vd_tizen_dev_author_ca.cer")
        val distCaPem = readBundledCaPem(context, "vd_tizen_dev_public2.crt")

        return SigningBundle(
            author = CertIdentity(aKey, buildCertChainWithCa(aLeafPem, authorCaPem)),
            distributor = CertIdentity(dKey, buildCertChainWithCa(dLeafPem, distCaPem)),
            distributorRawPem = dLeafPem
        )
    }

    private fun loadImportedP12Bundle(context: Context): SigningBundle? {
        val prefs = context.getSharedPreferences(PREFS_CERT, Context.MODE_PRIVATE)
        val authorFile = File(context.filesDir, CERT_FILE_AUTHOR_P12)
        if (!authorFile.exists()) return null
        return try {
            val authorPass = prefs.getString("author_pass", "") ?: ""
            val authorId = loadP12Identity(authorFile, authorPass)
            val distFile = File(context.filesDir, CERT_FILE_DIST_P12)
            val distId = if (distFile.exists()) {
                val distPass = prefs.getString("dist_pass", authorPass) ?: authorPass
                loadP12Identity(distFile, distPass)
            } else {
                authorId
            }
            SigningBundle(authorId, distId, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadP12Identity(file: File, password: String): CertIdentity {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { ks.load(it, password.toCharArray()) }
        val alias = ks.aliases().toList().first()
        val key = ks.getKey(alias, password.toCharArray()) as PrivateKey
        val rawChain = ks.getCertificateChain(alias)?.toList() ?: listOf(ks.getCertificate(alias))
        // Leaf first, then non-root intermediates (matching TizenWgtSigner.GetKeyInfoChain)
        val filtered = ArrayList<String>()
        rawChain.forEachIndexed { idx, cert ->
            val x509 = cert as? X509Certificate
            if (idx == 0 || (x509 != null && x509.subjectX500Principal != x509.issuerX500Principal)) {
                filtered.add(Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            }
        }
        return CertIdentity(key, filtered)
    }

    private fun readBundledCaPem(context: Context, fileName: String): String {
        return context.assets.open("samsung_ca/$fileName").use { ins ->
            val raw = ins.readBytes()
            val text = String(raw, Charsets.UTF_8)
            if (text.contains("BEGIN CERTIFICATE")) {
                text
            } else {
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(ByteArrayInputStream(raw)) as X509Certificate
                "-----BEGIN CERTIFICATE-----\n" +
                    Base64.encodeToString(cert.encoded, Base64.NO_WRAP) +
                    "\n-----END CERTIFICATE-----"
            }
        }
    }

    private fun issueOfficialSamsungCertificates(
        context: Context,
        session: SamsungAccountSession,
        duid: String
    ): SigningBundle? {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val authorKp = kpg.generateKeyPair()
        val distKp = kpg.generateKeyPair()

        val authorCsrPem = buildPkcs10CsrPem(authorKp, "TIBANSUB", null, emptyList())
        val distCsrPem = buildPkcs10CsrPem(
            distKp,
            "TizenSDK",
            session.email,
            listOf("URN:tizen:packageid=", "URN:tizen:deviceid=$duid")
        )

        val authorCertPem = postMultipartToSamsungCa(
            endpointUrl = "https://svdca.samsungqbe.com/apis/v3/authors",
            fields = linkedMapOf(
                "access_token" to session.accessToken,
                "user_id" to session.userId,
                "platform" to "VD"
            ),
            csrFilename = "author.csr",
            csrPem = authorCsrPem
        )

        val distCertPem = postMultipartToSamsungCa(
            endpointUrl = "https://svdca.samsungqbe.com/apis/v3/distributors",
            fields = linkedMapOf(
                "access_token" to session.accessToken,
                "user_id" to session.userId,
                "platform" to "VD",
                "privilege_level" to "Public",
                "developer_type" to "Individual"
            ),
            csrFilename = "distributor.csr",
            csrPem = distCsrPem
        )

        if (!authorCertPem.contains("BEGIN CERTIFICATE") || !distCertPem.contains("BEGIN CERTIFICATE")) {
            throw IllegalStateException("โทเคน Samsung หมดอายุ กรุณากด 'สลับบัญชี' ล็อกอิน Samsung ใหม่อีกครั้ง")
        }

        File(context.filesDir, ISSUED_AUTHOR_KEY).writeBytes(authorKp.private.encoded)
        File(context.filesDir, ISSUED_AUTHOR_CERT).writeText(authorCertPem, Charsets.UTF_8)
        File(context.filesDir, ISSUED_DIST_KEY).writeBytes(distKp.private.encoded)
        File(context.filesDir, ISSUED_DIST_CERT).writeText(distCertPem, Charsets.UTF_8)
        context.getSharedPreferences(PREFS_CERT, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ISSUED_DUID, duid)
            .apply()

        val authorCaPem = readBundledCaPem(context, "vd_tizen_dev_author_ca.cer")
        val distCaPem = readBundledCaPem(context, "vd_tizen_dev_public2.crt")

        return SigningBundle(
            author = CertIdentity(authorKp.private, buildCertChainWithCa(authorCertPem, authorCaPem)),
            distributor = CertIdentity(distKp.private, buildCertChainWithCa(distCertPem, distCaPem)),
            distributorRawPem = distCertPem
        )
    }

    private fun postMultipartToSamsungCa(
        endpointUrl: String,
        fields: Map<String, String>,
        csrFilename: String,
        csrPem: String
    ): String {
        val boundary = "----TibanSubSamsungBoundary${System.currentTimeMillis()}"
        val body = ByteArrayOutputStream()
        fun writeStr(s: String) = body.write(s.toByteArray(Charsets.UTF_8))

        fields.forEach { (k, v) ->
            writeStr("--$boundary\r\n")
            writeStr("Content-Disposition: form-data; name=\"$k\"\r\n\r\n")
            writeStr("$v\r\n")
        }
        writeStr("--$boundary\r\n")
        writeStr("Content-Disposition: form-data; name=\"csr\"; filename=\"$csrFilename\"\r\n")
        writeStr("Content-Type: application/octet-stream\r\n\r\n")
        writeStr(csrPem)
        writeStr("\r\n--$boundary--\r\n")

        val payload = body.toByteArray()
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }

        val conn = (URL(endpointUrl).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = sslContext.socketFactory
            setHostnameVerifier { hostname, _ -> hostname.equals("svdca.samsungqbe.com", ignoreCase = true) }
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Content-Length", payload.size.toString())
        }
        conn.outputStream.use { it.write(payload) }

        val status = conn.responseCode
        val respText = if (status in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $status"
            conn.disconnect()
            throw IllegalStateException("เซิร์ฟเวอร์ใบรับรอง Samsung ปฏิเสธคำขอ (HTTP $status): $err")
        }
        conn.disconnect()
        return respText
    }

    // ==================== Pure Kotlin ASN.1 DER PKCS#10 CSR Builder ====================

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
    }

    private fun derWrap(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derSeq(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return derWrap(0x30, out.toByteArray())
    }

    private fun derSet(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return derWrap(0x31, out.toByteArray())
    }

    private fun derOid(vararg arcs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            val tmp = ArrayList<Int>()
            tmp.add(v and 0x7F)
            v = v ushr 7
            while (v > 0) {
                tmp.add((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            for (j in tmp.size - 1 downTo 0) out.write(tmp[j])
        }
        return derWrap(0x06, out.toByteArray())
    }

    private fun derBitString(bytes: ByteArray): ByteArray = derWrap(0x03, byteArrayOf(0x00) + bytes)

    private fun buildPkcs10CsrPem(
        keyPair: KeyPair,
        commonName: String,
        emailAddress: String?,
        sanUris: List<String>
    ): String {
        val version = derWrap(0x02, byteArrayOf(0x00))
        val rdnParts = ArrayList<ByteArray>()
        rdnParts.add(derSet(derSeq(derOid(2, 5, 4, 3), derWrap(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        if (!emailAddress.isNullOrBlank()) {
            rdnParts.add(derSet(derSeq(derOid(1, 2, 840, 113549, 1, 9, 1), derWrap(0x16, emailAddress.toByteArray(Charsets.US_ASCII)))))
        }
        val subject = derSeq(*rdnParts.toTypedArray())
        val spki = keyPair.public.encoded

        val attrs = if (sanUris.isEmpty()) {
            derWrap(0xA0, ByteArray(0))
        } else {
            val generalNamesContent = ByteArrayOutputStream()
            sanUris.forEach { uri -> generalNamesContent.write(derWrap(0x86, uri.toByteArray(Charsets.US_ASCII))) }
            val sanExtValue = derWrap(0x04, derWrap(0x30, generalNamesContent.toByteArray()))
            val sanExtension = derSeq(derOid(2, 5, 29, 17), sanExtValue)
            val extReqAttr = derSeq(derOid(1, 2, 840, 113549, 1, 9, 14), derSet(derSeq(sanExtension)))
            derWrap(0xA0, extReqAttr)
        }

        val certReqInfo = derSeq(version, subject, spki, attrs)
        val sig = Signature.getInstance("SHA512withRSA")
        sig.initSign(keyPair.private)
        sig.update(certReqInfo)
        val sigBytes = sig.sign()

        val sigAlgId = derSeq(derOid(1, 2, 840, 113549, 1, 1, 13), byteArrayOf(0x05, 0x00))
        val csrDer = derSeq(certReqInfo, sigAlgId, derBitString(sigBytes))
        val b64 = wrap76(Base64.encodeToString(csrDer, Base64.NO_WRAP))
        return "-----BEGIN CERTIFICATE REQUEST-----\n$b64\n-----END CERTIFICATE REQUEST-----\n"
    }

    // ==================== Exact Apps2Samsung TizenWgtSigner Format ====================

    private fun signEmbeddedWgtApps2SamsungFormat(context: Context, bundle: SigningBundle, outWgt: File) {
        val originalFiles = linkedMapOf<String, ByteArray>()
        context.assets.open(EmbeddedTvPackageServer.ASSET_SAMSUNG_TV_WGT).use { rawIn ->
            ZipInputStream(rawIn).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val normalizedName = entry.name.replace('\\', '/')
                    val lower = normalizedName.lowercase()
                    if (!entry.isDirectory &&
                        !normalizedName.endsWith("/") &&
                        !lower.endsWith("author-signature.xml") &&
                        !lower.endsWith("signature1.xml")
                    ) {
                        val baos = ByteArrayOutputStream()
                        zis.copyTo(baos)
                        originalFiles[normalizedName] = baos.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }
        }

        // 1) Author signature signs original files
        val authorSigXml = buildApps2SamsungSignatureXml("AuthorSignature", originalFiles, bundle.author)

        // 2) Distributor signature: author-signature.xml FIRST, then original files
        val filesForDist = linkedMapOf<String, ByteArray>()
        filesForDist["author-signature.xml"] = authorSigXml
        filesForDist.putAll(originalFiles)
        val distSigXml = buildApps2SamsungSignatureXml("DistributorSignature", filesForDist, bundle.distributor)

        // 3) Create final zip: author-signature.xml first, signature1.xml second, then original files
        ZipOutputStream(FileOutputStream(outWgt)).use { zos ->
            fun put(name: String, data: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
            put("author-signature.xml", authorSigXml)
            put("signature1.xml", distSigXml)
            originalFiles.forEach { (name, data) -> put(name, data) }
        }
    }

    /**
     * Exact byte-for-byte mirror of Apps2Samsung `TizenWgtSigner.BuildSignatureFileAsync`:
     * - Compact XML (no XML declaration, no extra indentation inside SignedInfo)
     * - Plain base64 DigestValue & SignatureValue
     * - Dynamic SHA-512 C14N11 digest computed over exact canonicalized `<Object Id="prop">...</Object>`
     * - Leaf + intermediate CA certs wrapped at 76 chars with leading & trailing `\n`
     */
    private fun buildApps2SamsungSignatureXml(
        id: String,
        files: Map<String, ByteArray>,
        identity: CertIdentity
    ): ByteArray {
        val sha512 = MessageDigest.getInstance("SHA-512")
        val roleSlug = if (id == "AuthorSignature") "author" else "distributor"

        // Exact C14N11 canonical form of <Object Id="prop">...</Object> inheriting xmldsig# default ns
        val propObjectC14n =
            "<Object xmlns=\"http://www.w3.org/2000/09/xmldsig#\" Id=\"prop\">" +
                "<SignatureProperties xmlns:dsp=\"http://www.w3.org/2009/xmldsig-properties\">" +
                "<SignatureProperty Id=\"profile\" Target=\"#$id\">" +
                "<dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"></dsp:Profile>" +
                "</SignatureProperty>" +
                "<SignatureProperty Id=\"role\" Target=\"#$id\">" +
                "<dsp:Role URI=\"http://www.w3.org/ns/widgets-digsig#role-$roleSlug\"></dsp:Role>" +
                "</SignatureProperty>" +
                "<SignatureProperty Id=\"identifier\" Target=\"#$id\">" +
                "<dsp:Identifier></dsp:Identifier>" +
                "</SignatureProperty>" +
                "</SignatureProperties>" +
                "</Object>"
        val propDigest = Base64.encodeToString(sha512.digest(propObjectC14n.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

        val propObjectXml =
            "<Object Id=\"prop\">" +
                "<SignatureProperties xmlns:dsp=\"http://www.w3.org/2009/xmldsig-properties\">" +
                "<SignatureProperty Id=\"profile\" Target=\"#$id\">" +
                "<dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"></dsp:Profile>" +
                "</SignatureProperty>" +
                "<SignatureProperty Id=\"role\" Target=\"#$id\">" +
                "<dsp:Role URI=\"http://www.w3.org/ns/widgets-digsig#role-$roleSlug\"></dsp:Role>" +
                "</SignatureProperty>" +
                "<SignatureProperty Id=\"identifier\" Target=\"#$id\">" +
                "<dsp:Identifier></dsp:Identifier>" +
                "</SignatureProperty>" +
                "</SignatureProperties>" +
                "</Object>"

        val refsSb = StringBuilder()
        files.forEach { (path, data) ->
            val digestB64 = Base64.encodeToString(sha512.digest(data), Base64.NO_WRAP)
            refsSb.append("<Reference URI=\"$path\">")
            refsSb.append("<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>")
            refsSb.append("<DigestValue>$digestB64</DigestValue>")
            refsSb.append("</Reference>")
        }
        refsSb.append("<Reference URI=\"#prop\">")
        refsSb.append("<Transforms><Transform Algorithm=\"http://www.w3.org/2006/12/xml-c14n11\"></Transform></Transforms>")
        refsSb.append("<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>")
        refsSb.append("<DigestValue>$propDigest</DigestValue>")
        refsSb.append("</Reference>")

        val references = refsSb.toString()

        // Exact Exc-C14N byte-for-byte form of <SignedInfo>
        val signedInfoC14n =
            "<SignedInfo xmlns=\"http://www.w3.org/2000/09/xmldsig#\">" +
                "<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"></CanonicalizationMethod>" +
                "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha512\"></SignatureMethod>" +
                references +
                "</SignedInfo>"

        val signer = Signature.getInstance("SHA512withRSA")
        signer.initSign(identity.privateKey)
        signer.update(signedInfoC14n.toByteArray(Charsets.UTF_8))
        val sigB64 = Base64.encodeToString(signer.sign(), Base64.NO_WRAP)

        val keyInfoSb = StringBuilder("<KeyInfo><X509Data>")
        identity.certChainPemBodies.forEach { rawB64 ->
            val wrapped = wrap76(rawB64.replace(Regex("\\s+"), ""))
            keyInfoSb.append("<X509Certificate>\n$wrapped\n</X509Certificate>")
        }
        keyInfoSb.append("</X509Data></KeyInfo>")

        val fullXml =
            "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\" Id=\"$id\">" +
                "<SignedInfo>" +
                "<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"></CanonicalizationMethod>" +
                "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha512\"></SignatureMethod>" +
                references +
                "</SignedInfo>" +
                "<SignatureValue>$sigB64</SignatureValue>" +
                keyInfoSb.toString() +
                propObjectXml +
                "</Signature>"

        return fullXml.toByteArray(Charsets.UTF_8)
    }

    private fun stripPemHeaders(pem: String): String = pem
        .replace("-----BEGIN CERTIFICATE-----", "")
        .replace("-----END CERTIFICATE-----", "")
        .replace(Regex("[\\r\\n\\s]+"), "")

    private fun wrap76(s: String): String {
        if (s.length <= 76) return s
        val sb = StringBuilder(s.length + s.length / 76 + 4)
        var i = 0
        while (i < s.length) {
            val end = (i + 76).coerceAtMost(s.length)
            sb.append(s, i, end)
            if (end < s.length) sb.append('\n')
            i = end
        }
        return sb.toString()
    }

    // ==================== Apps2Samsung SdbTcpDevice (RSA AUTH + Multiplexed Pump) ====================

    private class SdbTcpDevice(
        private val context: Context,
        private val ip: String,
        private val port: Int = SDB_PORT
    ) : AutoCloseable {

        private var socket: Socket? = null
        private var din: DataInputStream? = null
        private var dout: DataOutputStream? = null
        private val writeLock = Any()
        private val running = AtomicBoolean(false)
        private var pumpThread: Thread? = null
        private val nextLocalId = AtomicInteger('a'.code)
        private var maxData = DEFAULT_MAX_DATA

        private val channelsByLocalId = ConcurrentHashMap<Int, SdbChannel>()

        private data class SdbFrame(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

        private inner class SdbChannel(val localId: Int) : AutoCloseable {
            @Volatile var remoteId: Int = 0
            @Volatile var closed: Boolean = false
            val openLatch = Semaphore(0)
            val okaySem = Semaphore(0)
            val incoming = LinkedBlockingQueue<ByteArray>()
            private var currentChunk: ByteArray? = null
            private var cursor = 0

            fun write(bytes: ByteArray) {
                var offset = 0
                while (offset < bytes.size) {
                    if (closed) throw IllegalStateException("SDB channel closed during write")
                    val chunkLen = minOf(maxData, bytes.size - offset)
                    val slice = bytes.copyOfRange(offset, offset + chunkLen)
                    sendFrame(CMD_WRTE, localId, remoteId, slice)
                    if (!okaySem.tryAcquire(20, TimeUnit.SECONDS)) {
                        throw IllegalStateException("หมดเวลารอ TV ตอบรับแพ็กเกต SDB (OKAY timeout)")
                    }
                    offset += chunkLen
                }
            }

            fun read(dst: ByteArray, offset: Int, len: Int, timeoutMs: Long): Int {
                while (true) {
                    val cur = currentChunk
                    if (cur != null) {
                        val avail = cur.size - cursor
                        if (avail > 0) {
                            val toCopy = minOf(avail, len)
                            System.arraycopy(cur, cursor, dst, offset, toCopy)
                            cursor += toCopy
                            if (cursor >= cur.size) {
                                currentChunk = null
                                cursor = 0
                            }
                            return toCopy
                        }
                        currentChunk = null
                        cursor = 0
                    }

                    val next = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS)
                        ?: throw IllegalStateException("หมดเวลารอข้อมูลจากทีวี (${timeoutMs / 1000}s)")
                    if (next.isEmpty()) {
                        closed = true
                        return 0
                    }
                    currentChunk = next
                    cursor = 0
                }
            }

            fun readExactly(size: Int, timeoutMs: Long): ByteArray {
                val out = ByteArray(size)
                var pos = 0
                while (pos < size) {
                    val r = read(out, pos, size - pos, timeoutMs)
                    if (r == 0) throw EOFException("Unexpected EOF while reading SDB sync response")
                    pos += r
                }
                return out
            }

            override fun close() {
                if (closed) return
                closed = true
                channelsByLocalId.remove(localId)
                try {
                    if (remoteId != 0) sendFrame(CMD_CLSE, localId, remoteId, ByteArray(0))
                } catch (_: Exception) {}
                incoming.offer(ByteArray(0))
                okaySem.release()
            }
        }

        fun connect() {
            val maxAttempts = 3
            var lastErr: Exception? = null
            for (attempt in 1..maxAttempts) {
                try {
                    connectOnce()
                    return
                } catch (e: Exception) {
                    lastErr = e
                    cleanupSocket()
                    if (attempt < maxAttempts) {
                        try { Thread.sleep(450L * attempt) } catch (_: Exception) {}
                    }
                }
            }
            throw lastErr ?: IllegalStateException("เชื่อมต่อ SDB พอร์ต $port ไม่สำเร็จ")
        }

        private fun connectOnce() {
            val s = Socket()
            s.connect(InetSocketAddress(ip, port), 8000)
            s.soTimeout = 15000
            socket = s
            din = DataInputStream(s.getInputStream())
            dout = DataOutputStream(s.getOutputStream())

            // Exact Apps2Samsung CNXN banner
            sendFrame(CMD_CNXN, CLIENT_VERSION, maxData, "host::sdb-net-client".toByteArray(Charsets.UTF_8))

            val rsaKeyPair = loadOrCreateSdbAuthKeyPair(context)
            var sentPubKey = false

            while (true) {
                val resp = readFrameBlocking()
                if (resp.cmd == CMD_CNXN) {
                    if (resp.arg1 > 0) {
                        maxData = minOf(maxData, resp.arg1)
                    }
                    // Switch to infinite read timeout for pump thread (per-operation timeouts handled by channel queues)
                    s.soTimeout = 0
                    startPumpLoop()
                    return
                }
                if (resp.cmd == CMD_AUTH) {
                    val authType = resp.arg0
                    when (authType) {
                        AUTH_TOKEN -> {
                            if (!sentPubKey) {
                                val sig = Signature.getInstance("SHA1withRSA").apply {
                                    initSign(rsaKeyPair.private)
                                    update(resp.payload)
                                }.sign()
                                sendFrame(CMD_AUTH, AUTH_SIGNATURE, 0, sig)
                                // If TV doesn't know our key yet it will reply with another AUTH_TOKEN; send pubkey next
                                sentPubKey = true
                            } else {
                                val pubBlob = encodeAndroidAdbPublicKey(rsaKeyPair.public as RSAPublicKey)
                                val payloadStr = Base64.encodeToString(pubBlob, Base64.NO_WRAP) + " tibansub@android\u0000"
                                sendFrame(CMD_AUTH, AUTH_PUBLICKEY, 0, payloadStr.toByteArray(Charsets.US_ASCII))
                            }
                        }
                        AUTH_PUBLICKEY -> {
                            val pubBlob = encodeAndroidAdbPublicKey(rsaKeyPair.public as RSAPublicKey)
                            val payloadStr = Base64.encodeToString(pubBlob, Base64.NO_WRAP) + " tibansub@android\u0000"
                            sendFrame(CMD_AUTH, AUTH_PUBLICKEY, 0, payloadStr.toByteArray(Charsets.US_ASCII))
                        }
                        else -> throw IllegalStateException("Unsupported SDB AUTH type: $authType")
                    }
                    continue
                }
                throw IllegalStateException("Expected CNXN or AUTH from TV, got 0x${resp.cmd.toString(16)}")
            }
        }

        private fun startPumpLoop() {
            running.set(true)
            pumpThread = Thread {
                try {
                    while (running.get()) {
                        val frame = readFrameBlocking()
                        when (frame.cmd) {
                            CMD_OKAY -> {
                                val remoteId = frame.arg0
                                val localId = frame.arg1
                                val ch = channelsByLocalId[localId]
                                if (ch != null) {
                                    if (ch.remoteId == 0) {
                                        ch.remoteId = remoteId
                                        ch.openLatch.release()
                                    } else {
                                        ch.okaySem.release()
                                    }
                                }
                            }
                            CMD_WRTE -> {
                                val remoteId = frame.arg0
                                val localId = frame.arg1
                                val ch = channelsByLocalId[localId]
                                if (ch != null) {
                                    if (ch.remoteId == 0) ch.remoteId = remoteId
                                    ch.incoming.offer(frame.payload)
                                    try { sendFrame(CMD_OKAY, localId, remoteId, ByteArray(0)) } catch (_: Exception) {}
                                } else {
                                    try { sendFrame(CMD_CLSE, localId, remoteId, ByteArray(0)) } catch (_: Exception) {}
                                }
                            }
                            CMD_CLSE -> {
                                val remoteId = frame.arg0
                                val localId = frame.arg1
                                val ch = channelsByLocalId.remove(localId)
                                if (ch != null) {
                                    ch.closed = true
                                    ch.incoming.offer(ByteArray(0))
                                    ch.openLatch.release()
                                    ch.okaySem.release()
                                }
                                try { sendFrame(CMD_CLSE, localId, remoteId, ByteArray(0)) } catch (_: Exception) {}
                            }
                            CMD_OPEN -> {
                                try { sendFrame(CMD_CLSE, frame.arg1, frame.arg0, ByteArray(0)) } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Connection closed; signal EOF on all open channels
                } finally {
                    channelsByLocalId.values.forEach { ch ->
                        ch.closed = true
                        ch.incoming.offer(ByteArray(0))
                        ch.openLatch.release()
                        ch.okaySem.release()
                    }
                    channelsByLocalId.clear()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun openChannel(service: String): SdbChannel {
            val localId = nextLocalId.incrementAndGet()
            val ch = SdbChannel(localId)
            channelsByLocalId[localId] = ch
            sendFrame(CMD_OPEN, localId, 0, service.toByteArray(Charsets.US_ASCII))
            if (!ch.openLatch.tryAcquire(10, TimeUnit.SECONDS) || ch.remoteId == 0) {
                channelsByLocalId.remove(localId)
                throw IllegalStateException("เปิดช่อง SDB '$service' ไม่สำเร็จ (ทีวีปฏิเสธหรือหมดเวลา)")
            }
            return ch
        }

        fun capability(): Map<String, String> {
            openChannel("capability:\u0000").use { ch ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(maxData)
                while (true) {
                    val r = ch.read(buf, 0, buf.size, 8000)
                    if (r == 0) break
                    out.write(buf, 0, r)
                }
                val bytes = out.toByteArray()
                if (bytes.size < 2) return emptyMap()
                // Skip first 2-4 hex length prefix bytes safely
                val raw = String(bytes, Charsets.UTF_8)
                val map = LinkedHashMap<String, String>()
                raw.lineSequence().forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0 && idx < line.length - 1) {
                        val k = line.substring(0, idx).trim().trimStart { !it.isLetter() }
                        val v = line.substring(idx + 1).trim()
                        if (k.isNotEmpty()) map[k] = v
                    }
                }
                return map
            }
        }

        fun shellCommand(command: String, timeoutMs: Long = 15000): String {
            openChannel("shell:$command\u0000").use { ch ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(maxData)
                while (true) {
                    val r = ch.read(buf, 0, buf.size, timeoutMs)
                    if (r == 0) break
                    out.write(buf, 0, r)
                }
                return out.toString("UTF-8")
            }
        }

        fun pushFile(localFile: File, remotePath: String) {
            FileInputStream(localFile).use { fis ->
                pushStream(fis, remotePath)
            }
        }

        fun pushBytes(bytes: ByteArray, remotePath: String) {
            ByteArrayInputStream(bytes).use { bis ->
                pushStream(bis, remotePath)
            }
        }

        /**
         * Exact 1:1 port of Apps2Samsung `SdbTcpDevice.PushAsync`.
         */
        private fun pushStream(input: java.io.InputStream, remotePath: String) {
            openChannel("sync:\u0000").use { ch ->
                // 1) SEND <remotePath>
                sendSyncPacket(ch, "SEND", remotePath.toByteArray(Charsets.UTF_8))

                // 2) DATA chunks (64KB sync packets, split to maxData frames by SdbChannel.write)
                val buf = ByteArray(SYNC_MAX_DATA)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sendSyncPacket(ch, "DATA", buf.copyOf(n))
                }

                // 3) DONE + 4-byte little-endian mtime (exact SdbTcpDevice.PushAsync wire format)
                val mtime = (System.currentTimeMillis() / 1000L).toInt()
                val mtimeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mtime).array()
                sendSyncPacket(ch, "DONE", mtimeBytes)

                // 4) Read sync response until OKAY or FAIL
                while (true) {
                    val hdr = ch.readExactly(8, 25000)
                    val id = String(hdr, 0, 4, Charsets.US_ASCII)
                    val len = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val payload = if (len > 0) ch.readExactly(len.coerceAtMost(65536), 10000) else ByteArray(0)
                    if (id == "OKAY") return
                    if (id == "FAIL") {
                        val msg = if (payload.isNotEmpty()) String(payload, Charsets.UTF_8) else "unknown"
                        throw IllegalStateException("ทีวีปฏิเสธไฟล์ที่ $remotePath ($msg)")
                    }
                }
            }
        }

        private fun sendSyncPacket(ch: SdbChannel, id4: String, payload: ByteArray) {
            val pkt = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(id4.toByteArray(Charsets.US_ASCII))
                .putInt(payload.size)
                .put(payload)
                .array()
            ch.write(pkt)
        }

        private fun sendFrame(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            var sum = 0
            for (b in payload) sum += (b.toInt() and 0xFF)
            val msg = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd)
                .putInt(arg0)
                .putInt(arg1)
                .putInt(payload.size)
                .putInt(sum)
                .putInt(cmd xor -1)
                .put(payload)
                .array()
            synchronized(writeLock) {
                val out = dout ?: throw IllegalStateException("SDB socket not connected")
                out.write(msg)
                out.flush()
            }
        }

        private fun readFrameBlocking(): SdbFrame {
            val ins = din ?: throw IllegalStateException("SDB socket not connected")
            val hdr = ByteArray(24)
            ins.readFully(hdr)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = bb.int
            val arg0 = bb.int
            val arg1 = bb.int
            val len = bb.int
            bb.int // checksum
            val magic = bb.int
            if (magic != (cmd xor -1)) {
                throw IllegalStateException("Invalid SDB frame magic (cmd=0x${cmd.toString(16)})")
            }
            val payload = if (len > 0) {
                val arr = ByteArray(len.coerceAtMost(1024 * 1024))
                ins.readFully(arr)
                arr
            } else {
                ByteArray(0)
            }
            return SdbFrame(cmd, arg0, arg1, payload)
        }

        private fun cleanupSocket() {
            running.set(false)
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            din = null
            dout = null
        }

        override fun close() {
            cleanupSocket()
            try { pumpThread?.interrupt() } catch (_: Exception) {}
            pumpThread = null
        }
    }

    private fun loadOrCreateSdbAuthKeyPair(context: Context): KeyPair {
        val privFile = File(context.filesDir, SDB_AUTH_PRIV_KEY)
        val pubFile = File(context.filesDir, SDB_AUTH_PUB_KEY)
        if (privFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            } catch (_: Exception) {}
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        try {
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
        } catch (_: Exception) {}
        return kp
    }

    /**
     * Exact port of Apps2Samsung `SdbTcpDevice.EncodePublicKey`: Android ADB binary RSAPublicKey struct (524 bytes).
     */
    private fun encodeAndroidAdbPublicKey(pubKey: RSAPublicKey): ByteArray {
        val modSizeBytes = 256
        val modSizeWords = modSizeBytes / 4

        val n = pubKey.modulus
        val rawBe = n.toByteArray()
        val modBe = ByteArray(modSizeBytes)
        if (rawBe.size >= modSizeBytes) {
            System.arraycopy(rawBe, rawBe.size - modSizeBytes, modBe, 0, modSizeBytes)
        } else {
            System.arraycopy(rawBe, 0, modBe, modSizeBytes - rawBe.size, rawBe.size)
        }

        val modLe = ByteArray(modSizeBytes)
        for (i in 0 until modSizeBytes) {
            modLe[i] = modBe[modSizeBytes - 1 - i]
        }

        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong() and 0xFFFFFFFFL

        val r = BigInteger.ONE.shiftLeft(modSizeBytes * 8)
        val rr = r.modPow(BigInteger.valueOf(2), n)
        val rrBeRaw = rr.toByteArray()
        val rrBe = ByteArray(modSizeBytes)
        if (rrBeRaw.size >= modSizeBytes) {
            System.arraycopy(rrBeRaw, rrBeRaw.size - modSizeBytes, rrBe, 0, modSizeBytes)
        } else {
            System.arraycopy(rrBeRaw, 0, rrBe, modSizeBytes - rrBeRaw.size, rrBeRaw.size)
        }
        val rrLe = ByteArray(modSizeBytes)
        for (i in 0 until modSizeBytes) {
            rrLe[i] = rrBe[modSizeBytes - 1 - i]
        }

        val exp = pubKey.publicExponent.toInt()
        return ByteBuffer.allocate(4 + 4 + modSizeBytes + modSizeBytes + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(modSizeWords)
            .putInt(n0inv.toInt())
            .put(modLe)
            .put(rrLe)
            .putInt(exp)
            .array()
    }
}
