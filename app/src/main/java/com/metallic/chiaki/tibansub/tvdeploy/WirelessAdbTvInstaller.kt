package com.metallic.chiaki.tibansub.tvdeploy

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Built-in Wireless ADB Installer for Android TV / Google TV (Sony Bravia, TCL, Hisense, Xiaomi, Box).
 * Pushes the embedded `assets/tv/TIBANSUB-Android-TV.apk` directly over Wi-Fi ADB (port 5555 or Wireless Debugging port)
 * and runs `pm install -r -g` in 1 click without needing USB drives or external downloader apps!
 */
object WirelessAdbTvInstaller {

    private const val CMD_CNXN = 0x4e584e43
    private const val CMD_AUTH = 0x48545541
    private const val CMD_OPEN = 0x4e45504f
    private const val CMD_OKAY = 0x59414b4f
    private const val CMD_WRTE = 0x45545257
    private const val CMD_CLSE = 0x45534c43
    private const val ADB_VERSION = 0x01000000
    private const val MAX_DATA = 4096

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    private val SHA1_DIGEST_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    suspend fun installEmbeddedApkToAndroidTv(
        context: Context,
        tvIp: String,
        adbPort: Int = 5555,
        onProgress: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val ip = tvIp.trim()
            if (ip.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("กรุณากรอกเลข IP ของทีวี Android TV / Google TV"))
            }

            val apkBytes = context.assets.open(EmbeddedTvPackageServer.ASSET_ANDROID_TV_APK).use { it.readBytes() }
            val keyPair = loadOrCreateAdbRsaKeyPair(context)

            onProgress("1/3 กำลังเชื่อมต่อ Wireless ADB ที่ $ip:$adbPort (กรุณากดยอมรับ 'Allow USB/Wireless Debugging' บนจอทีวีหากมีป๊อปอัพ)...")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, adbPort), 6000)
                socket.soTimeout = 25000
                val din = DataInputStream(socket.getInputStream())
                val dout = DataOutputStream(socket.getOutputStream())

                sendAdbPacket(dout, CMD_CNXN, ADB_VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.UTF_8))
                var pkt = readAdbPacket(din)

                if (pkt.cmd == CMD_AUTH && pkt.arg0 == AUTH_TOKEN) {
                    // Sign the 20-byte SHA-1 token raw with RSA private key
                    val sigBytes = signAdbToken(keyPair, pkt.payload)
                    sendAdbPacket(dout, CMD_AUTH, AUTH_SIGNATURE, 0, sigBytes)
                    pkt = readAdbPacket(din)

                    if (pkt.cmd == CMD_AUTH) {
                        // Send public key so the user can tap "Allow" on TV screen!
                        onProgress("กรุณากด 'อนุญาต (Allow)' บนหน้าจอทีวีเพื่อยืนยันการเชื่อมต่อครั้งแรก...")
                        val pubPayload = encodeAndroidRsaPublicKey(keyPair.public as RSAPublicKey)
                        sendAdbPacket(dout, CMD_AUTH, AUTH_RSAPUBLICKEY, 0, pubPayload)
                        pkt = readAdbPacket(din)
                    }
                }

                if (pkt.cmd != CMD_CNXN) {
                    return@withContext Result.failure(IllegalStateException("ทีวีไม่อนุญาตการเชื่อมต่อ ADB"))
                }

                onProgress("2/3 เชื่อมต่อสำเร็จ! กำลังส่งไฟล์ TIBANSUB-Android-TV.apk (${apkBytes.size / 1024} KB) เข้าทีวี...")
                val remoteApk = "/data/local/tmp/tibansub_tv.apk"
                pushBytesOverSync(din, dout, 1, apkBytes, remoteApk)

                onProgress("3/3 กำลังติดตั้งแอป TIBANSUB TV ลงระบบทีวีอัตโนมัติ...")
                val out = runShellCommand(din, dout, 2, "pm install -r -g $remoteApk && rm -f $remoteApk")

                if (out.contains("Success", ignoreCase = true)) {
                    // Optionally auto-launch TIBANSUB TV right on the TV screen!
                    try {
                        runShellCommand(din, dout, 3, "monkey -p com.tibansub.tv -c android.intent.category.LEANBACK_LAUNCHER 1")
                    } catch (_: Exception) {}
                    Result.success("ติดตั้งและเปิดแอป TIBANSUB TV บนทีวีเรียบร้อยแล้ว!")
                } else {
                    Result.failure(IllegalStateException("ผลลัพธ์จากทีวี: $out"))
                }
            }
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException(
                    "เชื่อมต่อ ADB ไม่สำเร็จ (${e.message}) — ตรวจสอบว่าเปิด ADB Debugging บนทีวีแล้ว หรือโหลดผ่านลิงก์ Wi-Fi ด้านล่างแทนได้ทันที"
                )
            )
        }
    }

    private fun loadOrCreateAdbRsaKeyPair(context: Context): KeyPair {
        val prefs = context.getSharedPreferences("tibansub_adb_keys", Context.MODE_PRIVATE)
        val privB64 = prefs.getString("priv", null)
        val pubB64 = prefs.getString("pub", null)
        if (privB64 != null && pubB64 != null) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.DEFAULT)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.DEFAULT)))
                return KeyPair(pub, priv)
            } catch (_: Exception) {}
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        prefs.edit()
            .putString("priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    private fun signAdbToken(kp: KeyPair, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, kp.private)
        val padded = ByteArray(SHA1_DIGEST_PREFIX.size + token.size)
        System.arraycopy(SHA1_DIGEST_PREFIX, 0, padded, 0, SHA1_DIGEST_PREFIX.size)
        System.arraycopy(token, 0, padded, SHA1_DIGEST_PREFIX.size, token.size)
        return cipher.doFinal(padded)
    }

    private fun encodeAndroidRsaPublicKey(pub: RSAPublicKey): ByteArray {
        val words = 64 // 2048 bits / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = pub.modulus
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toLong()

        val buf = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())

        var remN = n
        for (i in 0 until words) {
            val divRem = remN.divideAndRemainder(r32)
            buf.putInt(divRem[1].toLong().toInt())
            remN = divRem[0]
        }
        var remRR = rr
        for (i in 0 until words) {
            val divRem = remRR.divideAndRemainder(r32)
            buf.putInt(divRem[1].toLong().toInt())
            remRR = divRem[0]
        }
        buf.putInt(pub.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 tibansub@android\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun pushBytesOverSync(
        din: DataInputStream,
        dout: DataOutputStream,
        localId: Int,
        bytes: ByteArray,
        remotePath: String
    ) {
        sendAdbPacket(dout, CMD_OPEN, localId, 0, "sync:\u0000".toByteArray(Charsets.UTF_8))
        val resp = readAdbPacket(din)
        if (resp.cmd != CMD_OKAY) throw IllegalStateException("ADB sync open failed")
        val remoteId = resp.arg0

        val spec = "$remotePath,33206".toByteArray(Charsets.UTF_8)
        val sendHdr = ByteBuffer.allocate(8 + spec.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("SEND".toByteArray(Charsets.US_ASCII))
            .putInt(spec.size)
            .put(spec)
            .array()
        sendSyncChunk(din, dout, localId, remoteId, sendHdr)

        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(3072, bytes.size - offset)
            val chunk = ByteBuffer.allocate(8 + len).order(ByteOrder.LITTLE_ENDIAN)
                .put("DATA".toByteArray(Charsets.US_ASCII))
                .putInt(len)
                .put(bytes, offset, len)
                .array()
            sendSyncChunk(din, dout, localId, remoteId, chunk)
            offset += len
        }

        val done = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put("DONE".toByteArray(Charsets.US_ASCII))
            .putInt((System.currentTimeMillis() / 1000L).toInt())
            .array()
        sendSyncChunk(din, dout, localId, remoteId, done)

        val st = readAdbPacket(din)
        if (st.cmd == CMD_WRTE) sendAdbPacket(dout, CMD_OKAY, localId, remoteId, ByteArray(0))
        sendAdbPacket(dout, CMD_CLSE, localId, remoteId, ByteArray(0))
    }

    private fun sendSyncChunk(din: DataInputStream, dout: DataOutputStream, localId: Int, remoteId: Int, data: ByteArray) {
        sendAdbPacket(dout, CMD_WRTE, localId, remoteId, data)
        val ack = readAdbPacket(din)
        if (ack.cmd != CMD_OKAY && ack.cmd != CMD_WRTE) throw IllegalStateException("ADB sync interrupted")
    }

    private fun runShellCommand(din: DataInputStream, dout: DataOutputStream, localId: Int, cmd: String): String {
        sendAdbPacket(dout, CMD_OPEN, localId, 0, "shell:$cmd\u0000".toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        var remoteId = 0
        while (true) {
            val pkt = readAdbPacket(din)
            when (pkt.cmd) {
                CMD_OKAY -> remoteId = pkt.arg0
                CMD_WRTE -> {
                    remoteId = pkt.arg0
                    out.write(pkt.payload)
                    sendAdbPacket(dout, CMD_OKAY, localId, remoteId, ByteArray(0))
                }
                CMD_CLSE -> {
                    sendAdbPacket(dout, CMD_CLSE, localId, remoteId, ByteArray(0))
                    break
                }
            }
        }
        return out.toString("UTF-8")
    }

    private data class AdbPkt(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun sendAdbPacket(dout: DataOutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
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

    private fun readAdbPacket(din: DataInputStream): AdbPkt {
        val hdr = ByteArray(24)
        din.readFully(hdr)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val a0 = bb.int
        val a1 = bb.int
        val len = bb.int
        bb.int; bb.int
        val payload = ByteArray(len.coerceIn(0, 65536))
        if (payload.isNotEmpty()) din.readFully(payload)
        return AdbPkt(cmd, a0, a1, payload)
    }
}
