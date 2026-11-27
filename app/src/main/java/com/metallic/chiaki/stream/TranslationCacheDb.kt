package com.metallic.chiaki.stream

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-speed on-device SQLite translation cache with:
 * 1. Game Subtitle Library Categories (e.g. GTA VI + user-added game packs, per-game import & filtering)
 * 2. Strict separation between Popup/Tutorial tips (*_pop) and Dialogue Subtitles (*_sub)
 * 3. Exact normalized text lookup (0ms)
 * 4. OCR-invariant skeleton lookup (collapses I/l/1, O/0, punctuation & spacing differences in O(1))
 * 5. Exact full-row Levenshtein fuzzy matching (>= 90% similarity, length >= 15)
 * 6. Speaker-prefix decomposition ("Lucia: ..." <-> bare dialogue)
 * 7. External .db / .tsv file import into any chosen game category
 */
class TranslationCacheDb(private val appContext: Context) : SQLiteOpenHelper(
	appContext.applicationContext,
	DB_NAME,
	null,
	DB_VERSION
) {
	data class GameCategoryInfo(
		val gameId: String,
		val gameName: String,
		val sentenceCount: Int,
		val isBuiltIn: Boolean
	)

	companion object {
		private const val DB_NAME = "ps_translator_cache.db"
		private const val DB_VERSION = 2
		private const val TABLE_NAME = "translation_cache"
		private const val GAMES_TABLE = "subtitle_games"
		const val PRELOAD_KEY = "preload_sub"
		const val DEFAULT_GAME_ID = "gta6"
		const val DEFAULT_GAME_NAME = "GTA VI"
		const val ALL_GAMES_ID = "ALL"
		private const val ASSET_PRELOAD_FILE = "preload_gta6_cache.tsv"
		private const val PRELOAD_PREFS = "ps_translator_cache_meta"
		private const val PREF_PRELOAD_COUNT = "seeded_asset_line_count_v6"
		private const val PREF_ACTIVE_GAME_ID = "active_subtitle_game_id"
		private const val MAX_ROWS = 120000
		private const val MAX_SKELETON_BUCKET = 260

		private val SPEAKER_PREFIX_REGEX = Regex("^([A-Za-z][A-Za-z0-9 .'_-]{1,22}):\\s+(.+)$")

		private val KNOWN_SPEAKERS_TH = mapOf(
			"lucia" to "ลูเซีย",
			"lucia caminos" to "ลูเซีย",
			"jason" to "เจสัน",
			"jason duval" to "เจสัน",
			"stefanie" to "สเตฟานี",
			"stefanie gates" to "สเตฟานี เกตส์",
			"brian" to "ไบรอัน",
			"brian heder" to "ไบรอัน",
			"arnold" to "อาร์โนลด์",
			"cal" to "แคล",
			"cal hampton" to "แคล",
			"boobie" to "บูบี",
			"boobie ike" to "บูบี",
			"dre'quan" to "เดรควอน",
			"drequan" to "เดรควอน",
			"raul" to "ราอูล",
			"raul bautista" to "ราอูล",
			"donnie" to "ดอนนี",
			"ronda" to "รอนดา",
			"mandy" to "แมนดี",
			"bert" to "เบิร์ต",
			"dobbs" to "ด็อบส์",
			"roxy" to "ร็อกซี",
			"bae-luxe" to "เบ-ลักซ์",
			"tyrone" to "ไทโรน",
			"roscoe" to "รอสโค",
			"dook" to "ดุ๊ก",
			"karim" to "คาริม",
			"mia" to "มีอา",
			"nicole" to "นิโคล",
			"andrea" to "แอนเดรีย",
			"pete" to "พีท",
			"halifax" to "ฮาลิแฟกซ์",
			"valentina" to "วาเลนตินา",
			"monty" to "มอนตี",
			"felipe" to "เฟลิเป",
			"terro" to "เทอร์โร",
			"monterossa" to "มอนเตโรสซา",
			"evelyn" to "เอเวอลีน",
			"raymond" to "เรย์มอนด์",
			"ernesto" to "เออร์เนสโต",
			"santino" to "ซานติโน",
			"rodney" to "ร็อดนีย์",
			"lex" to "เล็กซ์",
			"viper" to "ไวเปอร์",
			"guardia brother" to "พี่น้องกัวร์เดีย",
			"guardia" to "กัวร์เดีย",
			"passenger" to "ผู้โดยสาร",
			"director" to "ผู้กำกับ",
			"gang member" to "สมาชิกแก๊ง",
			"thief" to "โจร",
			"buyer" to "คนซื้อ",
			"tourist" to "นักท่องเที่ยว",
			"sam" to "แซม",
			"kai" to "ไค",
			"shanese" to "ชานีส",
			"bobby" to "บ็อบบี",
			"local" to "ชาวบ้าน",
			"cop" to "ตำรวจ",
			"police" to "ตำรวจ",
			"officer" to "เจ้าหน้าที่",
			"guard" to "ผู้คุม",
			"waitress" to "พนักงานเสิร์ฟ",
			"teller" to "พนักงานแคชเชียร์",
			"clerk" to "พนักงานร้าน",
			"stranger" to "คนแปลกหน้า",
			"bouncer" to "การ์ดคุมร้าน",
			"bartender" to "บาร์เทนเดอร์",
			"announcer" to "ผู้ประกาศ",
			"dispatch" to "ศูนย์วิทยุ",
			"radio" to "วิทยุ"
		)

		fun normalizeEnglish(text: String): String {
			return text.lowercase()
				.replace('’', '\'')
				.replace('‘', '\'')
				.replace('“', '"')
				.replace('”', '"')
				.replace(Regex("\\s+"), " ")
				.trim()
				.trim('"', '\'', '.', '!', '?', ',', ';', ':', '-', ' ')
		}

		fun ocrSkeleton(text: String): String {
			val sb = StringBuilder(text.length)
			for (ch in text) {
				when (ch) {
					'I', 'l', '1', 'i', '|' -> sb.append('i')
					'O', '0', 'o' -> sb.append('o')
					in 'a'..'z' -> sb.append(ch)
					in 'A'..'Z' -> sb.append(ch.lowercaseChar())
					in '2'..'9' -> sb.append(ch)
				}
			}
			return sb.toString()
		}

		fun exactSimilarityAtLeast(a: String, b: String, minSimilarity: Double = 0.90): Double {
			val lenA = a.length
			val lenB = b.length
			val maxLen = max(lenA, lenB)
			if (maxLen == 0) return 1.0
			val maxAllowedDist = ((1.0 - minSimilarity) * maxLen).toInt()
			if (abs(lenA - lenB) > maxAllowedDist) return 0.0

			var prev = IntArray(lenB + 1) { it }
			var curr = IntArray(lenB + 1)

			for (i in 1..lenA) {
				curr[0] = i
				var rowMin = curr[0]
				val ca = a[i - 1]
				for (j in 1..lenB) {
					val cost = if (ca == b[j - 1]) 0 else 1
					val d = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
					curr[j] = d
					if (d < rowMin) rowMin = d
				}
				if (rowMin > maxAllowedDist) return 0.0
				val tmp = prev
				prev = curr
				curr = tmp
			}

			val dist = prev[lenB]
			if (dist > maxAllowedDist) return 0.0
			return 1.0 - (dist.toDouble() / maxLen.toDouble())
		}
	}

	private data class IndexedEntry(
		val skeleton: String,
		val thai: String
	)

	@Volatile
	private var memoryIndexLoaded = false
	private val exactMemoryMap = HashMap<String, String>(8192)
	private val skeletonMemoryMap = HashMap<String, String>(8192)
	private val lengthBuckets = Array(MAX_SKELETON_BUCKET + 1) { ArrayList<IndexedEntry>() }

	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS $TABLE_NAME (
				cache_key TEXT NOT NULL,
				normalized_en TEXT NOT NULL,
				thai_text TEXT NOT NULL,
				updated_at INTEGER NOT NULL,
				game_id TEXT NOT NULL DEFAULT '$DEFAULT_GAME_ID',
				PRIMARY KEY (cache_key, normalized_en)
			)
			""".trimIndent()
		)
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS $GAMES_TABLE (
				game_id TEXT PRIMARY KEY,
				game_name TEXT NOT NULL,
				created_at INTEGER NOT NULL
			)
			""".trimIndent()
		)
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_updated_at ON $TABLE_NAME(updated_at)")
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_norm_en ON $TABLE_NAME(normalized_en)")
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_game_id ON $TABLE_NAME(game_id)")
		ensureDefaultGameRow(db)
	}

	override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
		if (oldVersion < 2) {
			try {
				db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN game_id TEXT NOT NULL DEFAULT '$DEFAULT_GAME_ID'")
			} catch (_: Exception) {}
			db.execSQL(
				"""
				CREATE TABLE IF NOT EXISTS $GAMES_TABLE (
					game_id TEXT PRIMARY KEY,
					game_name TEXT NOT NULL,
					created_at INTEGER NOT NULL
				)
				""".trimIndent()
			)
			db.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_game_id ON $TABLE_NAME(game_id)")
			ensureDefaultGameRow(db)
		}
	}

	private fun ensureDefaultGameRow(db: SQLiteDatabase) {
		try {
			val cv = ContentValues().apply {
				put("game_id", DEFAULT_GAME_ID)
				put("game_name", DEFAULT_GAME_NAME)
				put("created_at", 1L)
			}
			db.insertWithOnConflict(GAMES_TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
		} catch (_: Exception) {}
	}

	fun getActiveGameId(): String {
		return appContext.getSharedPreferences(PRELOAD_PREFS, Context.MODE_PRIVATE)
			.getString(PREF_ACTIVE_GAME_ID, ALL_GAMES_ID)
			?.ifBlank { ALL_GAMES_ID }
			?: ALL_GAMES_ID
	}

	@Synchronized
	fun setActiveGameId(gameId: String) {
		val clean = gameId.trim().ifBlank { ALL_GAMES_ID }
		appContext.getSharedPreferences(PRELOAD_PREFS, Context.MODE_PRIVATE)
			.edit()
			.putString(PREF_ACTIVE_GAME_ID, clean)
			.apply()
		if (memoryIndexLoaded) {
			rebuildMemoryIndex()
		}
	}

	@Synchronized
	fun listGameCategories(): List<GameCategoryInfo> {
		ensureInitialized()
		val result = ArrayList<GameCategoryInfo>()
		try {
			val db = readableDatabase
			ensureDefaultGameRow(writableDatabase)
			val counts = HashMap<String, Int>()
			db.rawQuery(
				"SELECT game_id, COUNT(DISTINCT normalized_en) FROM $TABLE_NAME GROUP BY game_id",
				null
			).use { c ->
				while (c.moveToNext()) {
					val gid = c.getString(0)?.ifBlank { DEFAULT_GAME_ID } ?: DEFAULT_GAME_ID
					counts[gid] = c.getInt(1)
				}
			}
			db.rawQuery(
				"SELECT game_id, game_name FROM $GAMES_TABLE ORDER BY created_at ASC",
				null
			).use { c ->
				while (c.moveToNext()) {
					val gid = c.getString(0) ?: continue
					val gname = c.getString(1) ?: gid
					result.add(
						GameCategoryInfo(
							gameId = gid,
							gameName = gname,
							sentenceCount = counts[gid] ?: 0,
							isBuiltIn = gid == DEFAULT_GAME_ID
						)
					)
				}
			}
		} catch (_: Exception) {}

		if (result.none { it.gameId == DEFAULT_GAME_ID }) {
			result.add(0, GameCategoryInfo(DEFAULT_GAME_ID, DEFAULT_GAME_NAME, count().toInt(), true))
		}
		return result
	}

	@Synchronized
	fun addGameCategory(gameName: String): GameCategoryInfo {
		ensureInitialized()
		val cleanName = gameName.trim().ifBlank { "Custom Game" }
		val baseSlug = cleanName.lowercase()
			.replace(Regex("[^a-z0-9]+"), "_")
			.trim('_')
			.ifBlank { "game_${System.currentTimeMillis() % 100000}" }
		val uniqueId = if (baseSlug == DEFAULT_GAME_ID || baseSlug == ALL_GAMES_ID.lowercase()) {
			"${baseSlug}_${System.currentTimeMillis() % 10000}"
		} else {
			baseSlug
		}
		val cv = ContentValues().apply {
			put("game_id", uniqueId)
			put("game_name", cleanName)
			put("created_at", System.currentTimeMillis())
		}
		writableDatabase.insertWithOnConflict(GAMES_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
		return GameCategoryInfo(uniqueId, cleanName, 0, false)
	}

	@Synchronized
	fun deleteGameCategory(gameId: String) {
		if (gameId == DEFAULT_GAME_ID || gameId == ALL_GAMES_ID) return
		ensureInitialized()
		try {
			val db = writableDatabase
			db.delete(TABLE_NAME, "game_id = ?", arrayOf(gameId))
			db.delete(GAMES_TABLE, "game_id = ?", arrayOf(gameId))
			if (getActiveGameId() == gameId) {
				setActiveGameId(ALL_GAMES_ID)
			} else {
				rebuildMemoryIndex()
			}
		} catch (_: Exception) {}
	}

	@Synchronized
	fun ensureInitialized() {
		if (memoryIndexLoaded) return
		ensureDefaultGameRow(writableDatabase)
		seedFromAssetsIfNeeded()
		rebuildMemoryIndex()
		memoryIndexLoaded = true
	}

	private fun seedFromAssetsIfNeeded() {
		try {
			val assetLines = ArrayList<Pair<String, String>>(6000)
			appContext.assets.open(ASSET_PRELOAD_FILE).use { input ->
				BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
					lines.forEach { rawLine ->
						val line = rawLine.trim()
						if (line.isEmpty() || line.startsWith("#")) return@forEach
						val tabIdx = line.indexOf('\t')
						if (tabIdx > 0 && tabIdx < line.length - 1) {
							val en = line.substring(0, tabIdx).trim()
							val th = line.substring(tabIdx + 1).trim()
							if (en.length >= 4 && th.any { it in '\u0E00'..'\u0E7F' }) {
								assetLines.add(en to th)
							}
						}
					}
				}
			}
			if (assetLines.isEmpty()) return

			val prefs = appContext.getSharedPreferences(PRELOAD_PREFS, Context.MODE_PRIVATE)
			val lastSeededCount = prefs.getInt(PREF_PRELOAD_COUNT, -1)
			val currentPreloadCount = countPreloadedRows()
			if (lastSeededCount == assetLines.size && currentPreloadCount >= assetLines.size / 2) {
				return
			}

			val db = writableDatabase
			val now = System.currentTimeMillis()
			db.beginTransaction()
			try {
				db.delete(TABLE_NAME, "cache_key = ? AND game_id = ?", arrayOf(PRELOAD_KEY, DEFAULT_GAME_ID))
				val stmt = db.compileStatement(
					"INSERT OR REPLACE INTO $TABLE_NAME (cache_key, normalized_en, thai_text, updated_at, game_id) VALUES (?, ?, ?, ?, ?)"
				)
				for ((en, th) in assetLines) {
					val norm = normalizeEnglish(en)
					if (norm.length >= 4) {
						stmt.clearBindings()
						stmt.bindString(1, PRELOAD_KEY)
						stmt.bindString(2, norm)
						stmt.bindString(3, th)
						stmt.bindLong(4, now)
						stmt.bindString(5, DEFAULT_GAME_ID)
						stmt.executeInsert()
					}
				}
				db.setTransactionSuccessful()
			} finally {
				db.endTransaction()
			}
			prefs.edit().putInt(PREF_PRELOAD_COUNT, assetLines.size).apply()
		} catch (_: Exception) {}
	}

	private fun countPreloadedRows(): Int {
		return try {
			readableDatabase.rawQuery(
				"SELECT COUNT(*) FROM $TABLE_NAME WHERE cache_key = ? AND game_id = ?",
				arrayOf(PRELOAD_KEY, DEFAULT_GAME_ID)
			).use { c ->
				if (c.moveToFirst()) c.getInt(0) else 0
			}
		} catch (_: Exception) {
			0
		}
	}

	@Synchronized
	private fun rebuildMemoryIndex() {
		exactMemoryMap.clear()
		skeletonMemoryMap.clear()
		for (bucket in lengthBuckets) bucket.clear()

		val activeGame = getActiveGameId()
		try {
			val cursor = if (activeGame == ALL_GAMES_ID) {
				readableDatabase.rawQuery(
					"SELECT normalized_en, thai_text FROM $TABLE_NAME WHERE cache_key NOT LIKE '%_pop' ORDER BY CASE WHEN cache_key = ? THEN 0 ELSE 1 END ASC, updated_at ASC",
					arrayOf(PRELOAD_KEY)
				)
			} else {
				readableDatabase.rawQuery(
					"SELECT normalized_en, thai_text FROM $TABLE_NAME WHERE game_id = ? AND cache_key NOT LIKE '%_pop' ORDER BY CASE WHEN cache_key = ? THEN 0 ELSE 1 END ASC, updated_at ASC",
					arrayOf(activeGame, PRELOAD_KEY)
				)
			}
			cursor.use { c ->
				while (c.moveToNext()) {
					val norm = c.getString(0) ?: continue
					val thai = c.getString(1) ?: continue
					indexInMemory(norm, thai)
				}
			}
		} catch (_: Exception) {}
	}

	private fun indexInMemory(norm: String, thai: String) {
		if (norm.length < 4 || thai.isEmpty()) return
		exactMemoryMap[norm] = thai
		val skel = ocrSkeleton(norm)
		if (skel.length >= 5) {
			skeletonMemoryMap[skel] = thai
			if (skel.length in 15..MAX_SKELETON_BUCKET) {
				lengthBuckets[skel.length].add(IndexedEntry(skel, thai))
			}
		}
	}

	@Synchronized
	fun get(cacheKey: String, englishText: String): String? {
		ensureInitialized()
		val trimmed = englishText.trim()
		if (trimmed.isEmpty()) return null

		if (cacheKey.endsWith("_pop")) {
			val norm = normalizeEnglish(trimmed)
			if (norm.isEmpty()) return null
			return try {
				readableDatabase.rawQuery(
					"SELECT thai_text FROM $TABLE_NAME WHERE cache_key = ? AND normalized_en = ? LIMIT 1",
					arrayOf(cacheKey, norm)
				).use { cursor ->
					if (cursor.moveToFirst()) cursor.getString(0) else null
				}
			} catch (_: Exception) {
				null
			}
		}

		lookupSingleSubtitleSegment(cacheKey, trimmed)?.let { return it }

		val match = SPEAKER_PREFIX_REGEX.find(trimmed)
		if (match != null) {
			val speakerRaw = match.groupValues[1].trim()
			val dialogueRaw = match.groupValues[2].trim()
			if (dialogueRaw.length >= 4) {
				val dialogueTh = lookupSingleSubtitleSegment(cacheKey, dialogueRaw)
				if (!dialogueTh.isNullOrBlank()) {
					val speakerTh = KNOWN_SPEAKERS_TH[speakerRaw.lowercase()] ?: speakerRaw
					return if (dialogueTh.startsWith("$speakerTh:") || dialogueTh.startsWith("$speakerRaw:")) {
						dialogueTh
					} else {
						"$speakerTh: $dialogueTh"
					}
				}
			}
		}

		return null
	}

	private fun lookupSingleSubtitleSegment(cacheKey: String, segmentText: String): String? {
		val norm = normalizeEnglish(segmentText)
		if (norm.length < 4) return null

		val activeGame = getActiveGameId()
		try {
			val cursor = if (activeGame == ALL_GAMES_ID) {
				readableDatabase.rawQuery(
					"SELECT thai_text FROM $TABLE_NAME WHERE cache_key = ? AND normalized_en = ? LIMIT 1",
					arrayOf(cacheKey, norm)
				)
			} else {
				readableDatabase.rawQuery(
					"SELECT thai_text FROM $TABLE_NAME WHERE cache_key = ? AND normalized_en = ? AND game_id = ? LIMIT 1",
					arrayOf(cacheKey, norm, activeGame)
				)
			}
			cursor.use { c ->
				if (c.moveToFirst()) return c.getString(0)
			}
		} catch (_: Exception) {}

		exactMemoryMap[norm]?.let { return it }

		val skel = ocrSkeleton(norm)
		if (skel.length >= 6) {
			skeletonMemoryMap[skel]?.let { return it }
		}

		if (skel.length in 15..MAX_SKELETON_BUCKET) {
			val minSim = 0.90
			val maxDiff = ((1.0 - minSim) * skel.length).toInt().coerceAtLeast(1)
			val minBucket = max(15, skel.length - maxDiff)
			val maxBucket = min(MAX_SKELETON_BUCKET, skel.length + maxDiff)

			var bestSim = 0.0
			var bestThai: String? = null
			for (bLen in minBucket..maxBucket) {
				val bucket = lengthBuckets[bLen]
				for (i in 0 until bucket.size) {
					val entry = bucket[i]
					val sim = exactSimilarityAtLeast(skel, entry.skeleton, minSim)
					if (sim > bestSim) {
						bestSim = sim
						bestThai = entry.thai
						if (sim >= 0.97) return bestThai
					}
				}
			}
			if (bestThai != null) return bestThai
		}

		return null
	}

	@Synchronized
	fun put(cacheKey: String, englishText: String, thaiText: String) {
		ensureInitialized()
		val norm = normalizeEnglish(englishText)
		val cleanThai = thaiText.trim()
		if (norm.length < 4 || cleanThai.isEmpty()) return
		if (!cleanThai.any { it in '\u0E00'..'\u0E7F' }) return

		val targetGame = getActiveGameId().let { if (it == ALL_GAMES_ID) DEFAULT_GAME_ID else it }
		try {
			val db = writableDatabase
			val values = ContentValues().apply {
				put("cache_key", cacheKey)
				put("normalized_en", norm)
				put("thai_text", cleanThai)
				put("updated_at", System.currentTimeMillis())
				put("game_id", targetGame)
			}
			db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
			if (!cacheKey.endsWith("_pop")) {
				indexInMemory(norm, cleanThai)
			}
			if (System.currentTimeMillis() % 50L == 0L) {
				pruneIfNeeded(db)
			}
		} catch (_: Exception) {}
	}

	/**
	 * Imports translations from a user-selected .db (SQLite) or .tsv / .txt (English\tThai) file
	 * directly into the specified game category (`targetGameId`).
	 */
	@Synchronized
	fun importFromUri(context: Context, uri: Uri, targetGameId: String = DEFAULT_GAME_ID): Int {
		ensureInitialized()
		val safeGameId = targetGameId.trim().ifBlank { DEFAULT_GAME_ID }.let {
			if (it == ALL_GAMES_ID) DEFAULT_GAME_ID else it
		}
		val importCacheKey = if (safeGameId == DEFAULT_GAME_ID) PRELOAD_KEY else "game_${safeGameId}_sub"

		val tmpFile = File(context.cacheDir, "import_translation_cache.tmp")
		try {
			context.contentResolver.openInputStream(uri)?.use { input ->
				FileOutputStream(tmpFile).use { output ->
					input.copyTo(output)
				}
			} ?: return 0

			val isSqlite = tmpFile.length() > 16 && tmpFile.inputStream().use { ins ->
				val hdr = ByteArray(15)
				val read = ins.read(hdr)
				read == 15 && String(hdr, Charsets.US_ASCII) == "SQLite format 3"
			}

			val pairs = ArrayList<Pair<String, String>>(4096)
			if (isSqlite) {
				SQLiteDatabase.openDatabase(tmpFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { extDb ->
					extDb.rawQuery("SELECT normalized_en, thai_text FROM $TABLE_NAME", null).use { c ->
						while (c.moveToNext()) {
							val en = c.getString(0)?.trim() ?: continue
							val th = c.getString(1)?.trim() ?: continue
							if (en.length >= 4 && th.any { it in '\u0E00'..'\u0E7F' }) {
								pairs.add(en to th)
							}
						}
					}
				}
			} else {
				BufferedReader(InputStreamReader(tmpFile.inputStream(), Charsets.UTF_8)).useLines { lines ->
					lines.forEach { rawLine ->
						val line = rawLine.trim()
						if (line.isEmpty() || line.startsWith("#")) return@forEach
						val sepIdx = line.indexOf('\t').takeIf { it > 0 } ?: line.indexOf('|')
						if (sepIdx > 0 && sepIdx < line.length - 1) {
							val en = line.substring(0, sepIdx).trim()
							val th = line.substring(sepIdx + 1).trim()
							if (en.length >= 4 && th.any { it in '\u0E00'..'\u0E7F' }) {
								pairs.add(en to th)
							}
						}
					}
				}
			}

			if (pairs.isEmpty()) return 0

			val db = writableDatabase
			val now = System.currentTimeMillis()
			var imported = 0
			db.beginTransaction()
			try {
				val stmt = db.compileStatement(
					"INSERT OR REPLACE INTO $TABLE_NAME (cache_key, normalized_en, thai_text, updated_at, game_id) VALUES (?, ?, ?, ?, ?)"
				)
				for ((en, th) in pairs) {
					val norm = normalizeEnglish(en)
					if (norm.length >= 4) {
						stmt.clearBindings()
						stmt.bindString(1, importCacheKey)
						stmt.bindString(2, norm)
						stmt.bindString(3, th)
						stmt.bindLong(4, now)
						stmt.bindString(5, safeGameId)
						stmt.executeInsert()
						imported++
					}
				}
				db.setTransactionSuccessful()
			} finally {
				db.endTransaction()
			}

			rebuildMemoryIndex()
			return imported
		} catch (_: Exception) {
			return 0
		} finally {
			try { tmpFile.delete() } catch (_: Exception) {}
		}
	}

	private fun pruneIfNeeded(db: SQLiteDatabase) {
		try {
			val total = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use { c ->
				if (c.moveToFirst()) c.getLong(0) else 0L
			}
			if (total > MAX_ROWS) {
				val toDelete = total - (MAX_ROWS - 2000)
				db.execSQL(
					"DELETE FROM $TABLE_NAME WHERE rowid IN (SELECT rowid FROM $TABLE_NAME WHERE cache_key != ? AND cache_key NOT LIKE 'game_%_sub' ORDER BY updated_at ASC LIMIT ?)",
					arrayOf(PRELOAD_KEY, toDelete)
				)
			}
		} catch (_: Exception) {}
	}

	@Synchronized
	fun count(): Long {
		ensureInitialized()
		return try {
			readableDatabase.rawQuery("SELECT COUNT(DISTINCT normalized_en) FROM $TABLE_NAME", null).use { c ->
				if (c.moveToFirst()) c.getLong(0) else 0L
			}
		} catch (_: Exception) {
			0L
		}
	}

	@Synchronized
	fun clearAll() {
		try {
			writableDatabase.delete(TABLE_NAME, null, null)
			appContext.getSharedPreferences(PRELOAD_PREFS, Context.MODE_PRIVATE)
				.edit().remove(PREF_PRELOAD_COUNT).apply()
			seedFromAssetsIfNeeded()
			rebuildMemoryIndex()
		} catch (_: Exception) {}
	}
}
