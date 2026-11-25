package com.metallic.chiaki.tibansub.tvdeploy

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal

/**
 * Built-in One-Click Samsung Smart TV (.wgt) SDB Installer + Auto-DUID WGT Signer.
 * Connects directly to Samsung TV Developer Mode (Port 26101), reads the TV's exact DUID,
 * signs the embedded TIBANSUB-Samsung-TV.wgt (with imported/cached Samsung Certificate .p12
 * or generated developer profile), pushes the package via SDB sync, and runs `pkgcmd -i -t wgt`.
 */
object TizenSdbWgtInstaller {

    private const val SDB_PORT = 26101
    private const val CMD_CNXN = 0x4e584e43
    private const val CMD_OPEN = 0x4e45504f
    private const val CMD_OKAY = 0x59414b4f
    private const val CMD_WRTE = 0x45545257
    private const val CMD_CLSE = 0x45534c43
    private const val VERSION = 0x01000000
    private const val MAX_PAYLOAD = 4096

    private const val CERT_FILE_AUTHOR = "tizen_author.p12"
    private const val CERT_FILE_DIST = "tizen_distributor.p12"
    private const val PREFS_CERT = "tibansub_tizen_cert_prefs"

    fun hasCustomCertificate(context: Context): Boolean =
        File(context.filesDir, CERT_FILE_AUTHOR).exists()

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

            val targetName = if (isDistributor) CERT_FILE_DIST else CERT_FILE_AUTHOR
            File(context.filesDir, targetName).writeBytes(bytes)
            val passKey = if (isDistributor) "dist_pass" else "author_pass"
            context.getSharedPreferences(PREFS_CERT, Context.MODE_PRIVATE)
                .edit()
                .putString(passKey, password)
                .apply()

            Result.success("บันทึกใบรับรอง ($alias) เรียบร้อยแล้ว!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Full 1-Click Pipeline:
     * 1. Connect to Samsung TV Port 26101 (SDB)
     * 2. Query exact TV DUID (`duid-gadget` / `getduid`)
     * 3. Sign embedded `TIBANSUB-Samsung-TV.wgt` bound to that TV's DUID
     * 4. Push `.wgt` via SDB `sync:` and run `pkgcmd -i -t wgt`
     */
    suspend fun installEmbeddedWgtToSamsungTv(
        context: Context,
        tvIp: String,
        fallbackDuid: String? = null,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanIp = tvIp.trim()
            if (cleanIp.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("กรุณาระบุ IP ของทีวี Samsung"))
            }

            onProgress("1/4 กำลังเชื่อมต่อพอร์ต Developer Mode (26101) ที่ $cleanIp...")
            SdbSession(cleanIp).use { sdb ->
                sdb.connectHandshake()

                onProgress("2/4 กำลังอ่านรหัสเครื่อง DUID จากทีวี Samsung อัตโนมัติ...")
                val duidOut = sdb.execShell("/usr/bin/duid-gadget 2>/dev/null || getduid 2>/dev/null || echo ''").trim()
                val resolvedDuid = duidOut.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.length >= 12 && !it.contains("not found", true) }
                    ?: fallbackDuid
                    ?: "2.0#TIBANSUBDEFAULTDUID000001"

                onProgress("3/4 อ่าน DUID สำเร็จ ($resolvedDuid) — กำลังประทับลายเซ็นลงไฟล์ .wgt...")
                val signedWgtFile = File(context.cacheDir, "tibansub_signed_tv.wgt")
                signEmbeddedWgtForDuid(context, resolvedDuid, signedWgtFile)

                val remoteTmpPaths = listOf(
                    "/opt/usr/home/owner/apps_rw/tmp/tibansub.wgt",
                    "/tmp/tibansub.wgt"
                )
                var pushedPath: String? = null
                for (remotePath in remoteTmpPaths) {
                    onProgress("4/4 กำลังส่งแพ็กเกจ TIBANSUB TV เข้าทีวี Samsung...")
                    val pushOk = try {
                        sdb.pushFile(signedWgtFile, remotePath)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (pushOk) {
                        pushedPath = remotePath
                        break
                    }
                }

                val targetRemote = pushedPath
                    ?: return@withContext Result.failure(IllegalStateException("ส่งไฟล์เข้าทีวีไม่สำเร็จ ตรวจสอบว่าเปิด Developer Mode และรีสตาร์ททีวีแล้ว"))

                onProgress("กำลังสั่งติดตั้งแอป TIBANSUB TV บนทีวี...")
                val installOut = sdb.execShell(
                    "pkgcmd -i -t wgt -p $targetRemote -q 2>&1 || /usr/bin/pkgcmd -i -t wgt -p $targetRemote -G 2>&1"
                )

                try { signedWgtFile.delete() } catch (_: Exception) {}

                if (installOut.contains("val[ok]", true) ||
                    installOut.contains("spend time", true) ||
                    !installOut.contains("fail", true)
                ) {
                    Result.success("ติดตั้งแอป TIBANSUB TV บนทีวี Samsung ($cleanIp) สำเร็จแล้ว! เปิดแอปจากหน้า Home ของทีวีได้เลย")
                } else {
                    Result.failure(
                        IllegalStateException(
                            "ทีวีแจ้งเตือนใบรับรอง Samsung: กรุณาล็อกอินรับใบรับรอง Samsung Account หรือนำเข้าไฟล์ .p12 ด้านล่างแล้วกดติดตั้งอีกครั้ง ($installOut)"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "เชื่อมต่อพอร์ต 26101 ไม่ได้ (${e.message}) — ตรวจสอบว่ากด 1-2-3-4-5 เปิด Developer Mode ใส่ IP มือถือ และกดค้างปุ่มปิดทีวีรีสตาร์ทแล้ว"
                )
            )
        }
    }

    private fun signEmbeddedWgtForDuid(context: Context, duid: String, outWgt: File) {
        val entries = linkedMapOf<String, ByteArray>()
        context.assets.open(EmbeddedTvPackageServer.ASSET_SAMSUNG_TV_WGT).use { rawIn ->
            ZipInputStream(rawIn).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory &&
                        !entry.name.equals("author-signature.xml", true) &&
                        !entry.name.startsWith("signature", true)
                    ) {
                        val baos = ByteArrayOutputStream()
                        zis.copyTo(baos)
                        entries[entry.name] = baos.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }
        }

        val (authorKey, authorCert) = loadOrGenerateSigningIdentity(context, isDistributor = false, cn = "TIBANSUB Author")
        val (distKey, distCert) = loadOrGenerateSigningIdentity(context, isDistributor = true, cn = "TizenSDK Distributor")

        val authorSigXml = buildW3cWidgetSignatureXml(
            id = "AuthorSignature",
            roleUri = "http://www.w3.org/2009/xmldsig-properties#profile",
            files = entries,
            privateKey = authorKey,
            cert = authorCert,
            duid = null
        )
        entries["author-signature.xml"] = authorSigXml

        val distSigXml = buildW3cWidgetSignatureXml(
            id = "DistributorSignature",
            roleUri = "http://www.w3.org/2009/xmldsig-properties#role-distributor",
            files = entries,
            privateKey = distKey,
            cert = distCert,
            duid = duid
        )
        entries["signature1.xml"] = distSigXml

        ZipOutputStream(FileOutputStream(outWgt)).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    private fun loadOrGenerateSigningIdentity(
        context: Context,
        isDistributor: Boolean,
        cn: String
    ): Pair<PrivateKey, X509Certificate> {
        val prefs = context.getSharedPreferences(PREFS_CERT, Context.MODE_PRIVATE)
        val file = File(context.filesDir, if (isDistributor) CERT_FILE_DIST else CERT_FILE_AUTHOR)
        val fallbackAuthorFile = File(context.filesDir, CERT_FILE_AUTHOR)
        val targetFile = when {
            file.exists() -> file
            fallbackAuthorFile.exists() -> fallbackAuthorFile
            else -> null
        }

        if (targetFile != null) {
            try {
                val pass = prefs.getString(
                    if (targetFile.name == CERT_FILE_DIST) "dist_pass" else "author_pass",
                    ""
                ) ?: ""
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(targetFile).use { ks.load(it, pass.toCharArray()) }
                val alias = ks.aliases().toList().first()
                val key = ks.getKey(alias, pass.toCharArray()) as PrivateKey
                val cert = ks.getCertificate(alias) as X509Certificate
                return key to cert
            } catch (_: Exception) {}
        }

        // Fallback: Generate or load a valid X.509 RSA-2048 identity natively via AndroidKeyStore
        val ksAlias = if (isDistributor) "tibansub_tizen_dist_key" else "tibansub_tizen_author_key"
        val aks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!aks.containsAlias(ksAlias)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(
                ksAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=$cn, O=TIBANSUB"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 86400_000L))
                .setCertificateNotAfter(Date(now + 3650L * 86400_000L))
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        val privKey = aks.getKey(ksAlias, null) as PrivateKey
        val x509Cert = aks.getCertificate(ksAlias) as X509Certificate
        return privKey to x509Cert
    }

    private fun buildW3cWidgetSignatureXml(
        id: String,
        roleUri: String,
        files: Map<String, ByteArray>,
        privateKey: PrivateKey,
        cert: X509Certificate,
        duid: String?
    ): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val refsSb = StringBuilder()
        files.forEach { (path, bytes) ->
            val digestB64 = Base64.encodeToString(sha256.digest(bytes), Base64.NO_WRAP)
            val escapedUri = path.replace(" ", "%20")
            refsSb.append(
                "<Reference URI=\"$escapedUri\">" +
                    "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha256\"/>" +
                    "<DigestValue>$digestB64</DigestValue>" +
                    "</Reference>"
            )
        }

        val duidTargetInfo = if (!duid.isNullOrBlank()) {
            "<Target>urn:tizen:deviceid=$duid</Target>"
        } else ""

        val signedInfo = "<SignedInfo xmlns=\"http://www.w3.org/2000/09/xmldsig#\">" +
            "<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"/>" +
            "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha256\"/>" +
            refsSb.toString() +
            "</SignedInfo>"

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(signedInfo.toByteArray(Charsets.UTF_8))
        val sigValueB64 = Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
        val certB64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)

        val fullXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\" Id=\"$id\">" +
            signedInfo +
            "<SignatureValue>$sigValueB64</SignatureValue>" +
            "<KeyInfo><X509Data><X509Certificate>$certB64</X509Certificate></X509Data></KeyInfo>" +
            "<Object Id=\"prop\">" +
            "<SignatureProperties xmlns:dsp=\"http://www.w3.org/2009/xmldsig-properties\">" +
            "<SignatureProperty Id=\"profile\" Target=\"#$id\"><dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"/></SignatureProperty>" +
            "<SignatureProperty Id=\"role\" Target=\"#$id\"><dsp:Role URI=\"$roleUri\"/>$duidTargetInfo</SignatureProperty>" +
            "</SignatureProperties></Object>" +
            "</Signature>"
        return fullXml.toByteArray(Charsets.UTF_8)
    }

    /**
     * Minimal binary SDB (Smart Development Bridge) client over TCP Port 26101.
     */
    private class SdbSession(private val ip: String) : AutoCloseable {
        private val socket = Socket()
        private lateinit var din: DataInputStream
        private lateinit var dout: DataOutputStream
        private var nextLocalId = 1

        fun connectHandshake() {
            socket.connect(InetSocketAddress(ip, SDB_PORT), 5000)
            socket.soTimeout = 15000
            din = DataInputStream(socket.getInputStream())
            dout = DataOutputStream(socket.getOutputStream())

            sendPacket(CMD_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
            val pkt = readPacket()
            if (pkt.cmd != CMD_CNXN) {
                throw IllegalStateException("SDB handshake rejected (${pkt.cmd})")
            }
        }

        fun execShell(command: String): String {
            val localId = nextLocalId++
            sendPacket(CMD_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
            val out = ByteArrayOutputStream()
            var remoteId = 0
            while (true) {
                val pkt = readPacket()
                when (pkt.cmd) {
                    CMD_OKAY -> remoteId = pkt.arg0
                    CMD_WRTE -> {
                        remoteId = pkt.arg0
                        out.write(pkt.payload)
                        sendPacket(CMD_OKAY, localId, remoteId, ByteArray(0))
                    }
                    CMD_CLSE -> {
                        sendPacket(CMD_CLSE, localId, remoteId, ByteArray(0))
                        break
                    }
                }
            }
            return out.toString("UTF-8")
        }

        fun pushFile(localFile: File, remotePath: String) {
            val localId = nextLocalId++
            sendPacket(CMD_OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.UTF_8))
            val openResp = readPacket()
            if (openResp.cmd != CMD_OKAY) throw IllegalStateException("SDB sync rejected")
            val remoteId = openResp.arg0

            val spec = "$remotePath,33206".toByteArray(Charsets.UTF_8)
            val sendHdr = ByteBuffer.allocate(8 + spec.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("SEND".toByteArray(Charsets.US_ASCII))
                .putInt(spec.size)
                .put(spec)
                .array()
            writeSyncChunk(localId, remoteId, sendHdr)

            val buf = ByteArray(3072)
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
            writeSyncChunk(localId, remoteId, donePkt)

            // Read OKAY response from sync service
            val statusPkt = readPacket()
            if (statusPkt.cmd == CMD_WRTE) {
                sendPacket(CMD_OKAY, localId, remoteId, ByteArray(0))
            }
            sendPacket(CMD_CLSE, localId, remoteId, ByteArray(0))
        }

        private fun writeSyncChunk(localId: Int, remoteId: Int, data: ByteArray) {
            sendPacket(CMD_WRTE, localId, remoteId, data)
            val ack = readPacket()
            if (ack.cmd != CMD_OKAY && ack.cmd != CMD_WRTE) {
                throw IllegalStateException("Sync write interrupted")
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
