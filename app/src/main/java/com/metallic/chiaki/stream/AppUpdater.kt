package com.metallic.chiaki.stream

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provides:
 * 1. Over-The-Air (OTA) Cloud Database Sync: pulls the latest preload_gta6_cache.tsv directly from GitHub
 *    into TranslationCacheDb in-place without needing to download or install any new APK.
 * 2. In-App 1-Click APK Auto-Updater: checks GitHub Releases for newer versions, downloads TIBANSUB.apk
 *    with live progress, and launches the Android Package Installer via FileProvider.
 */
object AppUpdater {
	private const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/teeradream/tibansub-/releases/latest"
	private const val DIRECT_APK_DOWNLOAD_URL = "https://github.com/teeradream/tibansub-/releases/latest/download/TIBANSUB.apk"
	private const val CLOUD_TSV_URL = "https://raw.githubusercontent.com/teeradream/tibansub-/main/app/src/main/assets/preload_gta6_cache.tsv"

	data class ReleaseInfo(
		val tagName: String,
		val title: String,
		val apkUrl: String,
		val publishedAt: String
	)

	fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
		val rParts = remoteTag.trim().removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
		val cParts = currentVersion.trim().removePrefix("v").removePrefix("V").split(".").map { it.toIntOrNull() ?: 0 }
		val maxLen = maxOf(rParts.size, cParts.size)
		for (i in 0 until maxLen) {
			val r = rParts.getOrElse(i) { 0 }
			val c = cParts.getOrElse(i) { 0 }
			if (r != c) return r > c
		}
		return false
	}

	suspend fun fetchLatestReleaseInfo(): ReleaseInfo? = withContext(Dispatchers.IO) {
		try {
			val conn = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				setRequestProperty("User-Agent", "TIBANSUB-Android")
				setRequestProperty("Accept", "application/vnd.github+json")
				connectTimeout = 6000
				readTimeout = 6000
			}
			if (conn.responseCode !in 200..299) {
				conn.disconnect()
				return@withContext null
			}
			val text = conn.inputStream.bufferedReader().use { it.readText() }
			conn.disconnect()

			val json = JSONObject(text)
			val tag = json.optString("tag_name", "").trim()
			val name = json.optString("name", tag).trim()
			val published = json.optString("published_at", "").trim()
			var apkUrl = DIRECT_APK_DOWNLOAD_URL
			val assets = json.optJSONArray("assets")
			if (assets != null) {
				var fallbackPhoneApk: String? = null
				for (i in 0 until assets.length()) {
					val a = assets.getJSONObject(i)
					val aName = a.optString("name", "")
					val url = a.optString("browser_download_url", "")
					if (url.isEmpty() || !aName.endsWith(".apk", ignoreCase = true)) continue
					if (aName.equals("TIBANSUB.apk", ignoreCase = true)) {
						apkUrl = url
						break
					}
					if (!aName.contains("tv", ignoreCase = true) && fallbackPhoneApk == null) {
						fallbackPhoneApk = url
					}
				}
				if (apkUrl == DIRECT_APK_DOWNLOAD_URL && fallbackPhoneApk != null) {
					apkUrl = fallbackPhoneApk
				}
			}
			if (tag.isEmpty()) null else ReleaseInfo(tag, name, apkUrl, published)
		} catch (_: Exception) {
			null
		}
	}

	suspend fun syncCloudSubtitleDatabase(
		activity: Activity,
		cacheDb: TranslationCacheDb,
		targetGameId: String = TranslationCacheDb.DEFAULT_GAME_ID
	): Int = withContext(Dispatchers.IO) {
		val tmpFile = File(activity.cacheDir, "cloud_sync_cache.tsv")
		try {
			val conn = (URL("$CLOUD_TSV_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				setRequestProperty("User-Agent", "TIBANSUB-Android")
				connectTimeout = 8000
				readTimeout = 15000
			}
			if (conn.responseCode !in 200..299) {
				conn.disconnect()
				return@withContext 0
			}
			conn.inputStream.use { input ->
				FileOutputStream(tmpFile).use { output ->
					input.copyTo(output)
				}
			}
			conn.disconnect()
			if (!tmpFile.exists() || tmpFile.length() < 32) return@withContext 0
			cacheDb.importFromUri(activity, Uri.fromFile(tmpFile), targetGameId)
		} catch (_: Exception) {
			0
		} finally {
			try { tmpFile.delete() } catch (_: Exception) {}
		}
	}

	suspend fun downloadAndInstallApk(
		activity: Activity,
		apkUrl: String = DIRECT_APK_DOWNLOAD_URL,
		onProgress: (Int) -> Unit
	): Boolean = withContext(Dispatchers.IO) {
		try {
			val updateDir = File(activity.cacheDir, "app_updates")
			if (!updateDir.exists()) updateDir.mkdirs()
			val apkFile = File(updateDir, "TIBANSUB-Update.apk")
			if (apkFile.exists()) apkFile.delete()

			val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
				instanceFollowRedirects = true
				setRequestProperty("User-Agent", "TIBANSUB-Android")
				connectTimeout = 10000
				readTimeout = 30000
			}
			val code = conn.responseCode
			if (code !in 200..299) {
				conn.disconnect()
				return@withContext false
			}

			val totalBytes = conn.contentLengthLong
			var downloadedBytes = 0L
			var lastReportedPercent = -1

			BufferedInputStream(conn.inputStream).use { input ->
				FileOutputStream(apkFile).use { output ->
					val buffer = ByteArray(16384)
					while (true) {
						val read = input.read(buffer)
						if (read <= 0) break
						output.write(buffer, 0, read)
						downloadedBytes += read
						if (totalBytes > 0) {
							val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
							if (pct != lastReportedPercent) {
								lastReportedPercent = pct
								withContext(Dispatchers.Main) { onProgress(pct) }
							}
						}
					}
					output.flush()
				}
			}
			conn.disconnect()

			if (!apkFile.exists() || apkFile.length() < 100_000L) {
				return@withContext false
			}

			withContext(Dispatchers.Main) {
				val apkUri = FileProvider.getUriForFile(
					activity,
					"${activity.packageName}.fileprovider",
					apkFile
				)
				val installIntent = Intent(Intent.ACTION_VIEW).apply {
					setDataAndType(apkUri, "application/vnd.android.package-archive")
					addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				}
				activity.startActivity(installIntent)
			}
			true
		} catch (e: Exception) {
			android.util.Log.e("TibanSubUpdate", "In-app update failed: ${e.message}", e)
			false
		}
	}
}
