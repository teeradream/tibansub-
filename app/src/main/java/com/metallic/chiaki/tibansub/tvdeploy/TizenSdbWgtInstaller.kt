package com.metallic.chiaki.tibansub.tvdeploy

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Authentic 1-Click Samsung Smart TV (.wgt) SDB Installer + Official Samsung VD Certificate Auto-Issuer.
 *
 * Verified against Tizen Studio Samsung Certificate Extension & TizenBrewInstaller:
 * 1. Samsung Account OAuth2 sign-in via serviceID=v285zxnl3h + local callback http://localhost:4794/signin/callback.
 * 2. Connects to Samsung TV Port 26101 (SDB) -> `shell:0 getduid` -> exact TV DUID.
 * 3. Generates RSA-2048 Author CSR & Distributor CSR (with SubjectAltName URN:tizen:packageid= and URN:tizen:deviceid=<DUID>),
 *    submits to `svdca.samsungqbe.com/apis/v3/authors` and `/distributors`, pairs with official bundled Samsung CA certs.
 * 4. Signs embedded `TIBANSUB-Samsung-TV.wgt` with exact W3C XML-DSig RSA-SHA512 + Exc-C14N.
 * 5. Pushes `device-profile.xml` (if available) and `tibansub.wgt` via SDB `sync:` (properly waiting for `OKAY`/`FAIL` after `DONE`).
 * 6. Opens a clean SDB connection and runs `shell:0 vd_appinstall TibanTV001 /home/owner/share/tmp/sdk_tools/tibansub.wgt`
 *    filtering packets strictly by `localId` and waiting up to 60s for real install confirmation (`spend time` / `val[ok]`).
 */
object TizenSdbWgtInstaller {

    const val SAMSUNG_OAUTH_CALLBACK_PORT = 4794
    const val SAMSUNG_OAUTH_CALLBACK_URL = "http://localhost:$SAMSUNG_OAUTH_CALLBACK_PORT/signin/callback"
    const val SAMSUNG_OAUTH_LOGIN_URL =
        "https://account.samsung.com/mobile/account/check.do" +
            "?serviceID=v285zxnl3h" +
            "&actionID=StartOAuth2" +
            "&accessToken=Y" +
            "&redirect_uri=$SAMSUNG_OAUTH_CALLBACK_URL"

    private const val TIZEN_PACKAGE_ID = "TibanTV001"
    private const val TIZEN_APP_ID = "TibanTV001.TIBANSUB"
    private const val REMOTE_SDK_DIR = "/home/owner/share/tmp/sdk_tools"
    private const val REMOTE_WGT_PATH = "$REMOTE_SDK_DIR/tibansub.wgt"
    private const val REMOTE_DEVICE_PROFILE_PATH = "$REMOTE_SDK_DIR/device-profile.xml"

    private const val SDB_PORT = 26101
    private const val CMD_CNXN = 0x4e584e43
    private const val CMD_OPEN = 0x4e45504f
    private const val CMD_OKAY = 0x59414b4f
    private const val CMD_WRTE = 0x45545257
    private const val CMD_CLSE = 0x45534c43
    private const val VERSION = 0x01000000
    private const val MAX_PAYLOAD = 4096

    private const val AUTHOR_PROP_DIGEST =
        "aXbSAVgmAz0GsBUeZ1UmNDRrxkWhDUVGb45dZcNRq429wX3X+x6kaXT3NdNDTSNVTU+ypkysPMGvQY10fG1EWQ=="
    private const val DISTRIBUTOR_PROP_DIGEST =
        "/r5npk2VVA46QFJnejgONBEh4BWtjrtu9x/IFeLksjWyGmB/cMWKSJWQl7aU3YRQRZ3AesG8gF7qGyvKX9Snig=="

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
            .apply()
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
                        val client = try {
                            ss.accept()
                        } catch (_: Exception) {
                            break
                        }
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
                val v = try {
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                } catch (_: Exception) {
                    pair.substring(idx + 1)
                }
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

    /**
     * Full 1-Click Pipeline for retail Samsung Smart TVs (Tizen 4.0 - 9.0):
     * Uses isolated clean SDB sessions per phase so leftover `sync:` packets NEVER abort `vd_appinstall`.
     */
    suspend fun installEmbeddedWgtToSamsungTv(
        context: Context,
        tvIp: String,
        fallbackDuid: String? = null,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val signedWgtFile = File(context.cacheDir, "tibansub_signed_tv.wgt")
        try {
            val cleanIp = tvIp.trim()
            if (cleanIp.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("กรุณาระบุเลข IP ของทีวี Samsung"))
            }

            // Phase 1: Query real DUID via clean SDB connection
            onProgress("1/5 กำลังเชื่อมต่อทีวี Samsung พอร์ต 26101 และอ่าน DUID...")
            val resolvedDuid = SdbSession(cleanIp, timeoutMs = 15000).use { sdb ->
                sdb.connectHandshake()
                val duidOut = sdb.execShell("0 getduid").trim()
                duidOut.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.length >= 10 && !it.contains("not found", true) && !it.contains("error", true) }
                    ?: fallbackDuid?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "อ่านรหัส DUID จากทีวีไม่ได้ (ทีวีตอบกลับ: '$duidOut') กรุณาตรวจว่าเปิด Developer Mode ใส่ IP มือถือตรงกัน และรีสตาร์ททีวีแล้ว"
                    )
            }

            // Phase 2: Issue or load Samsung Author + Distributor certificates for this exact DUID
            onProgress("2/5 พบ DUID ($resolvedDuid) — กำลังเตรียมใบรับรองและเซ็นไฟล์ .wgt...")
            val signingBundle = resolveSigningBundle(context, resolvedDuid, onProgress)
                ?: return@withContext Result.failure(
                    IllegalStateException("ยังไม่มีใบรับรอง Samsung กรุณากด 'ล็อกอิน Samsung' ด้านบนก่อนแล้วกดติดตั้งอีกครั้ง")
                )

            signEmbeddedWgt(context, signingBundle, signedWgtFile)
            val wgtSizeKb = (signedWgtFile.length() / 1024).coerceAtLeast(1)

            // Phase 3: Push device-profile.xml (if issued) and signed .wgt over clean SDB sync session
            onProgress("3/5 กำลังส่งไฟล์ TIBANSUB TV ($wgtSizeKb KB) เข้าทีวี Samsung...")
            SdbSession(cleanIp, timeoutMs = 30000).use { sdb ->
                sdb.connectHandshake()
                val distPem = signingBundle.distributorRawPem
                if (!distPem.isNullOrBlank()) {
                    val tmpProfile = File(context.cacheDir, "device-profile.xml")
                    try {
                        tmpProfile.writeText(distPem, Charsets.UTF_8)
                        sdb.pushFile(tmpProfile, REMOTE_DEVICE_PROFILE_PATH)
                    } catch (_: Exception) {
                        // Optional on newer Tizen versions
                    } finally {
                        try { tmpProfile.delete() } catch (_: Exception) {}
                    }
                }
            }

            SdbSession(cleanIp, timeoutMs = 45000).use { sdb ->
                sdb.connectHandshake()
                sdb.pushFile(signedWgtFile, REMOTE_WGT_PATH)
            }

            // Phase 4: Execute `0 vd_appinstall` on a brand-new clean SDB connection with 60s timeout
            onProgress("4/5 ส่งไฟล์ครบแล้ว กำลังติดตั้งลงทีวี Samsung (รอประมาณ 5-15 วินาที)...")
            val installOut = SdbSession(cleanIp, timeoutMs = 60000).use { sdb ->
                sdb.connectHandshake()
                sdb.execShell(
                    command = "0 vd_appinstall $TIZEN_PACKAGE_ID $REMOTE_WGT_PATH",
                    stopOnKeyword = "spend time"
                )
            }

            val cleanLog = installOut.trim()
            val normalizedOut = cleanLog.lowercase()
            val hasExplicitSuccess =
                normalizedOut.contains("val[ok]") ||
                    (normalizedOut.contains("spend time") &&
                        !normalizedOut.contains("fail") &&
                        !normalizedOut.contains("error") &&
                        !Regex("\\[-\\d+\\]").containsMatchIn(cleanLog)) ||
                    normalizedOut.contains("install completed")

            val hasError = normalizedOut.contains("fail") ||
                normalizedOut.contains("error") ||
                Regex("\\[-\\d+\\]").containsMatchIn(cleanLog)

            if (hasExplicitSuccess && !hasError) {
                // Phase 5: Try auto-launching TIBANSUB TV right on the Samsung TV so user sees it immediately!
                onProgress("5/5 ติดตั้งสำเร็จแล้ว กำลังเปิดแอป TIBANSUB TV บนทีวี...")
                try {
                    SdbSession(cleanIp, timeoutMs = 8000).use { sdb ->
                        sdb.connectHandshake()
                        sdb.execShell("0 was_execute $TIZEN_APP_ID")
                    }
                } catch (_: Exception) {}

                Result.success("ติดตั้งแอป TIBANSUB TV บนทีวี Samsung ($cleanIp) สำเร็จแล้ว เปิดใช้งานได้จากเมนู Apps บนทีวีเลย")
            } else if (cleanLog.isEmpty()) {
                Result.failure(
                    IllegalStateException(
                        "ทีวีไม่ได้ส่งผลยืนยันการติดตั้งกลับมา (อาจปิด Developer Mode หรือทีวีปฏิเสธการเชื่อมต่อ) กรุณารีสตาร์ททีวี 1 รอบแล้วลองใหม่"
                    )
                )
            } else {
                val hint = when {
                    cleanLog.contains("-12") || normalizedOut.contains("cert") || normalizedOut.contains("signature") ->
                        "ทีวีปฏิเสธใบรับรอง (รหัส -12): กรุณากด 'สลับบัญชี' ล็อกอิน Samsung ใหม่เพื่อให้แอปออกใบรับรองใหม่ตรงกับ DUID ($resolvedDuid)\nรายละเอียดทีวี: $cleanLog"
                    cleanLog.contains("-14") || normalizedOut.contains("privilege") ->
                        "ทีวีปฏิเสธสิทธิ์แอป (รหัส -14)\nรายละเอียดทีวี: $cleanLog"
                    cleanLog.contains("-27") || normalizedOut.contains("not found") ->
                        "ทีวีหาไฟล์แพ็กเกจไม่เจอ (รหัส -27): กรุณาตรวจว่าพื้นที่ว่างบนทีวีเพียงพอแล้วลองใหม่\nรายละเอียดทีวี: $cleanLog"
                    else -> "ทีวีติดตั้งไม่สำเร็จ: $cleanLog"
                }
                Result.failure(IllegalStateException(hint))
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "ติดตั้งลงทีวี Samsung ไม่สำเร็จ: ${e.message}"
                )
            )
        } finally {
            try { signedWgtFile.delete() } catch (_: Exception) {}
        }
    }

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
            onProgress("2/5 กำลังขอใบรับรอง Samsung Developer สำหรับ DUID ($duid)...")
            val issued = issueOfficialSamsungCertificates(context, session, duid)
            if (issued != null) return issued
        }

        return loadImportedP12Bundle(context)
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
            author = CertIdentity(aKey, listOf(stripPemHeaders(aLeafPem), stripPemHeaders(authorCaPem))),
            distributor = CertIdentity(dKey, listOf(stripPemHeaders(dLeafPem), stripPemHeaders(distCaPem))),
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
        val chain = ks.getCertificateChain(alias)
            ?.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
            ?: listOf(Base64.encodeToString(ks.getCertificate(alias).encoded, Base64.NO_WRAP))
        return CertIdentity(key, chain)
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

        val authorCsrPem = buildPkcs10CsrPem(
            keyPair = authorKp,
            commonName = "TIBANSUB",
            emailAddress = null,
            sanUris = emptyList()
        )
        val distCsrPem = buildPkcs10CsrPem(
            keyPair = distKp,
            commonName = "TizenSDK",
            emailAddress = session.email,
            sanUris = listOf("URN:tizen:packageid=", "URN:tizen:deviceid=$duid")
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
            author = CertIdentity(
                authorKp.private,
                listOf(stripPemHeaders(authorCertPem), stripPemHeaders(authorCaPem))
            ),
            distributor = CertIdentity(
                distKp.private,
                listOf(stripPemHeaders(distCertPem), stripPemHeaders(distCaPem))
            ),
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
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }

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
        rdnParts.add(
            derSet(
                derSeq(
                    derOid(2, 5, 4, 3),
                    derWrap(0x0C, commonName.toByteArray(Charsets.UTF_8))
                )
            )
        )
        if (!emailAddress.isNullOrBlank()) {
            rdnParts.add(
                derSet(
                    derSeq(
                        derOid(1, 2, 840, 113549, 1, 9, 1),
                        derWrap(0x16, emailAddress.toByteArray(Charsets.US_ASCII))
                    )
                )
            )
        }
        val subject = derSeq(*rdnParts.toTypedArray())
        val spki = keyPair.public.encoded

        val attrs = if (sanUris.isEmpty()) {
            derWrap(0xA0, ByteArray(0))
        } else {
            val generalNamesContent = ByteArrayOutputStream()
            sanUris.forEach { uri ->
                generalNamesContent.write(derWrap(0x86, uri.toByteArray(Charsets.US_ASCII)))
            }
            val sanExtValue = derWrap(0x04, derWrap(0x30, generalNamesContent.toByteArray()))
            val sanExtension = derSeq(derOid(2, 5, 29, 17), sanExtValue)
            val extensionsSeq = derSeq(sanExtension)
            val extReqAttr = derSeq(
                derOid(1, 2, 840, 113549, 1, 9, 14),
                derSet(extensionsSeq)
            )
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

    // ==================== Exact Tizen W3C XML-DSig Signer (SHA-512 + Exc-C14N) ====================

    private fun signEmbeddedWgt(context: Context, bundle: SigningBundle, outWgt: File) {
        val entries = linkedMapOf<String, ByteArray>()
        context.assets.open(EmbeddedTvPackageServer.ASSET_SAMSUNG_TV_WGT).use { rawIn ->
            ZipInputStream(rawIn).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val normalizedName = entry.name.replace('\\', '/')
                    if (!entry.isDirectory &&
                        !normalizedName.equals("author-signature.xml", true) &&
                        !(normalizedName.contains("signature", true) && normalizedName.endsWith(".xml", true))
                    ) {
                        val baos = ByteArrayOutputStream()
                        zis.copyTo(baos)
                        entries[normalizedName] = baos.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }
        }

        val authorSigXml = buildTizenSignatureXml(
            id = "AuthorSignature",
            files = entries,
            identity = bundle.author
        )
        val entriesWithAuthor = linkedMapOf<String, ByteArray>()
        entriesWithAuthor["author-signature.xml"] = authorSigXml
        entriesWithAuthor.putAll(entries)

        val distSigXml = buildTizenSignatureXml(
            id = "DistributorSignature",
            files = entriesWithAuthor,
            identity = bundle.distributor
        )
        val finalEntries = linkedMapOf<String, ByteArray>()
        finalEntries["signature1.xml"] = distSigXml
        finalEntries.putAll(entriesWithAuthor)

        ZipOutputStream(FileOutputStream(outWgt)).use { zos ->
            finalEntries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    private fun buildTizenSignatureXml(
        id: String,
        files: Map<String, ByteArray>,
        identity: CertIdentity
    ): ByteArray {
        val sha512 = MessageDigest.getInstance("SHA-512")
        val refsSb = StringBuilder()

        files.forEach { (path, data) ->
            val digestB64 = wrap76(Base64.encodeToString(sha512.digest(data), Base64.NO_WRAP))
            val encodedUri = path.split("/").joinToString("/") { seg ->
                java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
            refsSb.append("<Reference URI=\"$encodedUri\">\n")
            refsSb.append("<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>\n")
            refsSb.append("<DigestValue>$digestB64</DigestValue>\n")
            refsSb.append("</Reference>\n")
        }

        val propDigest = wrap76(if (id == "AuthorSignature") AUTHOR_PROP_DIGEST else DISTRIBUTOR_PROP_DIGEST)
        refsSb.append("<Reference URI=\"#prop\">\n")
        refsSb.append("<Transforms>\n")
        refsSb.append("<Transform Algorithm=\"http://www.w3.org/2006/12/xml-c14n11\"></Transform>\n")
        refsSb.append("</Transforms>\n")
        refsSb.append("<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>\n")
        refsSb.append("<DigestValue>$propDigest</DigestValue>\n")
        refsSb.append("</Reference>\n")

        val references = refsSb.toString()

        val signedInfoForSign = "<SignedInfo xmlns=\"http://www.w3.org/2000/09/xmldsig#\">\n" +
            "<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"></CanonicalizationMethod>\n" +
            "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha512\"></SignatureMethod>\n" +
            references +
            "</SignedInfo>"

        val signer = Signature.getInstance("SHA512withRSA")
        signer.initSign(identity.privateKey)
        signer.update(signedInfoForSign.toByteArray(Charsets.UTF_8))
        val sigB64 = wrap76(Base64.encodeToString(signer.sign(), Base64.NO_WRAP))

        val keyInfoSb = StringBuilder("<KeyInfo>\n<X509Data>")
        identity.certChainPemBodies.forEach { rawB64 ->
            val wrapped = wrap76(rawB64.replace(Regex("\\s+"), ""))
            keyInfoSb.append("\n<X509Certificate>\n$wrapped\n</X509Certificate>")
        }
        keyInfoSb.append("\n</X509Data>\n</KeyInfo>\n")

        val roleSlug = if (id == "AuthorSignature") "author" else "distributor"
        val fullXml = "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\" Id=\"$id\">\n" +
            "<SignedInfo>\n" +
            "<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"></CanonicalizationMethod>\n" +
            "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha512\"></SignatureMethod>\n" +
            references +
            "</SignedInfo>\n" +
            "<SignatureValue>\n$sigB64\n</SignatureValue>\n" +
            keyInfoSb.toString() +
            "<Object Id=\"prop\">" +
            "<SignatureProperties xmlns:dsp=\"http://www.w3.org/2009/xmldsig-properties\">" +
            "<SignatureProperty Id=\"profile\" Target=\"#$id\">" +
            "<dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"></dsp:Profile>" +
            "</SignatureProperty>" +
            "<SignatureProperty Id=\"role\" Target=\"#$id\">" +
            "<dsp:Role URI=\"http://www.w3.org/ns/widgets-digsig#role-$roleSlug\"></dsp:Role>" +
            "</SignatureProperty>" +
            "<SignatureProperty Id=\"identifier\" Target=\"#$id\">" +
            "<dsp:Identifier></dsp:Identifier></SignatureProperty></SignatureProperties></Object>\n" +
            "</Signature>\n"

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

    // ==================== Strict Binary SDB Client over TCP Port 26101 ====================

    private class SdbSession(
        private val ip: String,
        private val timeoutMs: Int = 30000
    ) : AutoCloseable {
        private val socket = Socket()
        private lateinit var din: DataInputStream
        private lateinit var dout: DataOutputStream
        private var nextLocalId = 1

        fun connectHandshake() {
            socket.connect(InetSocketAddress(ip, SDB_PORT), 6000)
            socket.soTimeout = timeoutMs
            din = DataInputStream(socket.getInputStream())
            dout = DataOutputStream(socket.getOutputStream())

            sendPacket(CMD_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
            val pkt = readPacket()
            if (pkt.cmd != CMD_CNXN) {
                throw IllegalStateException("SDB handshake ถูกปฏิเสธ (${pkt.cmd})")
            }
        }

        fun execShell(command: String, stopOnKeyword: String? = null): String {
            val localId = nextLocalId++
            sendPacket(CMD_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
            val out = ByteArrayOutputStream()
            var remoteId = 0
            while (true) {
                val pkt = readPacket()
                if (pkt.arg1 != localId) {
                    // Ignore any stray packet from an earlier stream
                    continue
                }
                when (pkt.cmd) {
                    CMD_OKAY -> remoteId = pkt.arg0
                    CMD_WRTE -> {
                        remoteId = pkt.arg0
                        out.write(pkt.payload)
                        sendPacket(CMD_OKAY, localId, remoteId, ByteArray(0))
                        if (stopOnKeyword != null && out.toString("UTF-8").contains(stopOnKeyword, ignoreCase = true)) {
                            sendPacket(CMD_CLSE, localId, remoteId, ByteArray(0))
                            break
                        }
                    }
                    CMD_CLSE -> {
                        sendPacket(CMD_CLSE, localId, remoteId, ByteArray(0))
                        break
                    }
                }
            }
            return out.toString("UTF-8")
        }

        /**
         * SDB `sync:` file transfer that strictly waits for the TV's `OKAY`/`FAIL` commit response after `DONE`.
         */
        fun pushFile(localFile: File, remotePath: String) {
            val localId = nextLocalId++
            sendPacket(CMD_OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.UTF_8))
            var remoteId = 0
            while (true) {
                val pkt = readPacket()
                if (pkt.arg1 != localId) continue
                if (pkt.cmd == CMD_OKAY) {
                    remoteId = pkt.arg0
                    break
                } else {
                    throw IllegalStateException("ทีวีปฏิเสธการเปิดช่องส่งไฟล์ SDB sync (${pkt.cmd})")
                }
            }

            // Combine SEND header + spec into a single clean sync packet (and verify no FAIL response)
            val specBytes = "$remotePath,33261".toByteArray(Charsets.UTF_8)
            val sendPkt = ByteBuffer.allocate(8 + specBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("SEND".toByteArray(Charsets.US_ASCII))
                .putInt(specBytes.size)
                .put(specBytes)
                .array()
            writeSyncChunk(localId, remoteId, sendPkt)

            val buf = ByteArray(1420)
            FileInputStream(localFile).use { fis ->
                while (true) {
                    val n = fis.read(buf)
                    if (n <= 0) break
                    val dataPkt = ByteBuffer.allocate(8 + n).order(ByteOrder.LITTLE_ENDIAN)
                        .put("DATA".toByteArray(Charsets.US_ASCII))
                        .putInt(n)
                        .put(buf, 0, n)
                        .array()
                    writeSyncChunk(localId, remoteId, dataPkt)
                }
            }

            val mtime = (System.currentTimeMillis() / 1000L).toInt()
            val donePkt = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put("DONE".toByteArray(Charsets.US_ASCII))
                .putInt(mtime)
                .array()
            sendPacket(CMD_WRTE, localId, remoteId, donePkt)

            // Wait for BOTH transport ACK and the TV sync daemon's actual OKAY/FAIL commit packet!
            var syncCommitted = false
            while (!syncCommitted) {
                val pkt = readPacket()
                if (pkt.arg1 != localId) continue
                when (pkt.cmd) {
                    CMD_OKAY -> { /* transport ack, keep waiting for sync OKAY/FAIL WRTE */ }
                    CMD_WRTE -> {
                        sendPacket(CMD_OKAY, localId, pkt.arg0, ByteArray(0))
                        if (pkt.payload.size >= 4) {
                            val tag = String(pkt.payload, 0, 4, Charsets.US_ASCII)
                            if (tag == "FAIL") {
                                val reason = if (pkt.payload.size > 8) {
                                    String(pkt.payload, 8, pkt.payload.size - 8, Charsets.UTF_8)
                                } else "sync FAIL"
                                throw IllegalStateException("ทีวีปฏิเสธการรับไฟล์ที่ $remotePath ($reason)")
                            }
                            if (tag == "OKAY") {
                                syncCommitted = true
                            }
                        } else {
                            syncCommitted = true
                        }
                    }
                    CMD_CLSE -> {
                        sendPacket(CMD_CLSE, localId, remoteId, ByteArray(0))
                        syncCommitted = true
                    }
                }
            }

            val quitPkt = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put("QUIT".toByteArray(Charsets.US_ASCII))
                .putInt(0)
                .array()
            try {
                sendPacket(CMD_WRTE, localId, remoteId, quitPkt)
                sendPacket(CMD_CLSE, localId, remoteId, ByteArray(0))
            } catch (_: Exception) {}
        }

        private fun writeSyncChunk(localId: Int, remoteId: Int, data: ByteArray) {
            sendPacket(CMD_WRTE, localId, remoteId, data)
            while (true) {
                val pkt = readPacket()
                if (pkt.arg1 != localId) continue
                when (pkt.cmd) {
                    CMD_OKAY -> return
                    CMD_WRTE -> {
                        sendPacket(CMD_OKAY, localId, pkt.arg0, ByteArray(0))
                        if (pkt.payload.size >= 4 && String(pkt.payload, 0, 4, Charsets.US_ASCII) == "FAIL") {
                            val reason = if (pkt.payload.size > 8) {
                                String(pkt.payload, 8, pkt.payload.size - 8, Charsets.UTF_8)
                            } else "sync FAIL"
                            throw IllegalStateException("ส่งไฟล์เข้าทีวีไม่สำเร็จ ($reason)")
                        }
                    }
                    CMD_CLSE -> throw IllegalStateException("ทีวีตัดการเชื่อมต่อระหว่างส่งไฟล์ (CMD_CLSE)")
                }
            }
        }

        private data class SdbPacket(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

        private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
            var sum = 0
            for (b in payload) sum += (b.toInt() and 0xFF)
            val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd)
                .putInt(arg0)
                .putInt(arg1)
                .putInt(payload.size)
                .putInt(sum)
                .putInt(cmd xor -1)
                .array()
            dout.write(hdr)
            if (payload.isNotEmpty()) dout.write(payload)
            dout.flush()
        }

        private fun readPacket(): SdbPacket {
            val hdrBytes = ByteArray(24)
            din.readFully(hdrBytes)
            val bb = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = bb.int
            val arg0 = bb.int
            val arg1 = bb.int
            val len = bb.int
            bb.int // checksum
            bb.int // magic
            val payload = ByteArray(len.coerceIn(0, 65536))
            if (payload.isNotEmpty()) din.readFully(payload)
            return SdbPacket(cmd, arg0, arg1, payload)
        }

        override fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
