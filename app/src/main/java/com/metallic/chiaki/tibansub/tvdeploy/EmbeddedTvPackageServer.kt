package com.metallic.chiaki.tibansub.tvdeploy

import android.content.Context
import com.metallic.chiaki.stream.TvOverlayServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredSmartTv(
    val ip: String,
    val name: String,
    val isSamsungTizen: Boolean,
    val sdbDevPortOpen: Boolean,
    val adbPortOpen: Boolean,
    val knownDuid: String? = null
)

/**
 * 1) Serves the embedded TIBANSUB-Android-TV.apk & TIBANSUB-Samsung-TV.wgt over local home Wi-Fi (port 8792)
 *    so any TV browser / Downloader app can also grab `http://<Phone-IP>:8792/tv.apk` directly offline.
 * 2) Scans the home LAN subnet in parallel for Samsung Tizen TVs (port 8001 + SDB port 26101)
 *    and Android TVs / Google TVs (ADB port 5555).
 */
class EmbeddedTvPackageServer(private val context: Context) {

    companion object {
        const val PORT = 8792
        const val ASSET_ANDROID_TV_APK = "tv/TIBANSUB-Android-TV.apk"
        const val ASSET_SAMSUNG_TV_WGT = "tv/TIBANSUB-Samsung-TV.wgt"

        suspend fun scanHomeNetworkForTvs(context: Context): List<DiscoveredSmartTv> = withContext(Dispatchers.IO) {
            val myIp = TvOverlayServer.getLocalWifiIp(context)
            val parts = myIp.split(".")
            if (parts.size != 4) return@withContext emptyList()
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            val found = java.util.Collections.synchronizedList(mutableListOf<DiscoveredSmartTv>())

            coroutineScope {
                (1..254).chunked(42).forEach { batch ->
                    batch.map { hostNum ->
                        async {
                            val ip = "$prefix.$hostNum"
                            if (ip == myIp) return@async

                            val sdbOpen = isPortOpen(ip, 26101, 240)
                            val samRestOpen = isPortOpen(ip, 8001, 240)
                            val adbOpen = isPortOpen(ip, 5555, 240)

                            if (sdbOpen || samRestOpen) {
                                var tvName = "Samsung Smart TV ($ip)"
                                var duid: String? = null
                                if (samRestOpen) {
                                    try {
                                        val conn = (URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection).apply {
                                            connectTimeout = 500
                                            readTimeout = 500
                                        }
                                        if (conn.responseCode in 200..299) {
                                            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                                            val dev = json.optJSONObject("device")
                                            val n = dev?.optString("name", "") ?: json.optString("name", "")
                                            if (n.isNotBlank()) tvName = n
                                            val rawId = dev?.optString("duid", "") ?: ""
                                            if (rawId.isNotBlank()) duid = rawId.removePrefix("uuid:")
                                        }
                                        conn.disconnect()
                                    } catch (_: Exception) {}
                                }
                                found.add(
                                    DiscoveredSmartTv(
                                        ip = ip,
                                        name = tvName,
                                        isSamsungTizen = true,
                                        sdbDevPortOpen = sdbOpen,
                                        adbPortOpen = false,
                                        knownDuid = duid
                                    )
                                )
                            } else if (adbOpen) {
                                found.add(
                                    DiscoveredSmartTv(
                                        ip = ip,
                                        name = "Android TV / Google TV ($ip)",
                                        isSamsungTizen = false,
                                        sdbDevPortOpen = false,
                                        adbPortOpen = true
                                    )
                                )
                            }
                        }
                    }.awaitAll()
                }
            }
            found.sortedWith(compareByDescending<DiscoveredSmartTv> { it.sdbDevPortOpen || it.adbPortOpen }.thenBy { it.ip })
        }

        private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
            return try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (running.getAndSet(true)) return
        executor.execute {
            try {
                val ss = ServerSocket(PORT).apply { reuseAddress = true }
                serverSocket = ss
                while (running.get()) {
                    val client = try { ss.accept() } catch (_: Exception) { break }
                    executor.execute { handleClient(client) }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val reqLine = reader.readLine() ?: return
                val path = reqLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                val out = client.getOutputStream()

                when {
                    path.equals("/tv.apk", ignoreCase = true) -> {
                        val bytes = context.assets.open(ASSET_ANDROID_TV_APK).use { it.readBytes() }
                        val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.android.package-archive\r\n" +
                            "Content-Disposition: attachment; filename=\"TIBANSUB-Android-TV.apk\"\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(bytes)
                        out.flush()
                    }
                    path.equals("/tv.wgt", ignoreCase = true) -> {
                        val bytes = context.assets.open(ASSET_SAMSUNG_TV_WGT).use { it.readBytes() }
                        val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Disposition: attachment; filename=\"TIBANSUB-Samsung-TV.wgt\"\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(bytes)
                        out.flush()
                    }
                    else -> {
                        val html = """
                            <!DOCTYPE html><html><head><meta charset="utf-8"/>
                            <title>TIBANSUB TV Installer</title>
                            <style>body{background:#181238;color:#fff;font-family:sans-serif;text-align:center;padding:40px;}
                            a{display:inline-block;margin:16px;padding:18px 34px;border-radius:99px;background:#fff;color:#181238;font-weight:bold;text-decoration:none;font-size:20px;}</style>
                            </head><body><h1>TIBANSUB TV Companion</h1>
                            <p>แตะปุ่มด้านล่างด้วยรีโมตทีวีเพื่อดาวน์โหลดและติดตั้งทันที</p>
                            <a href="/tv.apk">ดาวน์โหลดสำหรับ Android TV / Google TV (.APK)</a>
                            </body></html>
                        """.trimIndent().toByteArray(Charsets.UTF_8)
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n"
                        out.write(header.toByteArray(Charsets.UTF_8))
                        out.write(html)
                        out.flush()
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
