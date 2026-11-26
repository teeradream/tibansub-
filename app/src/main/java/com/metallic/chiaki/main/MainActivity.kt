// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.stream.AppUpdater
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.stream.TranslationCacheDb
import com.metallic.chiaki.tibansub.ui.guide.HowToGuideScreen
import com.metallic.chiaki.tibansub.ui.home.TibanHomeScreen
import com.metallic.chiaki.tibansub.ui.pairing.PsGlassyPairingScreen
import com.metallic.chiaki.tibansub.ui.presettings.AppUpdateUiState
import com.metallic.chiaki.tibansub.ui.presettings.PreSettingsRepository
import com.metallic.chiaki.tibansub.ui.presettings.PreSettingsScreen
import com.metallic.chiaki.tibansub.ui.theme.*
import com.metallic.chiaki.tibansub.ui.tvinstaller.TvInstallerScreen
import com.metallic.chiaki.tibansub.ui.xbox.XboxConnectScreen
import com.metallic.chiaki.tibansub.xbox.XboxAuthManager
import com.metallic.chiaki.tibansub.xbox.XboxSessionLaunchConfig
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TIBANSUB Main Hub Activity:
 * - Option B (Leonida Twilight Dusk) + Dark Liquid Glass + /liquid-glass-motion spring physics
 * - Supports PlayStation 5 / PS4 (libchiaki backend) AND Xbox Series X|S / One (WebRTC backend)
 * - Settings (AI 7 Providers, Game Subtitle Library Categories, Stream Quality, Version & OTA Update)
 * - Built-in TV Installer (Samsung .wgt with Official OAuth + Auto-DUID Cert + SDB, Android TV .apk)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_EXPORT_BACKUP = 9101
        private const val REQUEST_CODE_IMPORT_BACKUP = 9102
        private const val REQUEST_CODE_IMPORT_CACHE_DB = 9103
    }

    private lateinit var viewModel: MainViewModel
    private lateinit var preSettingsRepo: PreSettingsRepository

    private var refreshSettingsTick = mutableStateOf(0)
    private var cacheCountState = mutableStateOf(15868)
    private var gameCategoriesState = mutableStateOf<List<TranslationCacheDb.GameCategoryInfo>>(emptyList())
    private var activeGameIdState = mutableStateOf(TranslationCacheDb.ALL_GAMES_ID)
    private var pendingImportGameId: String = TranslationCacheDb.DEFAULT_GAME_ID
    private var updateUiState = mutableStateOf(AppUpdateUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        viewModel = ViewModelProvider(this, viewModelFactory {
            MainViewModel(getDatabase(this), Preferences(this))
        })[MainViewModel::class.java]

        preSettingsRepo = PreSettingsRepository(this)
        refreshCacheMetadata()

        lifecycleScope.launch {
            val restored = AppStateBackupManager.autoRestoreIfNeeded(applicationContext)
            if (restored && !isFinishing && !isDestroyed) {
                refreshSettingsTick.value++
                Toast.makeText(this@MainActivity, "กู้คืนการตั้งค่าและข้อมูลคอนโซลอัตโนมัติแล้ว", Toast.LENGTH_LONG).show()
            }
        }

        setContent {
            TibanSubTheme {
                val hosts by viewModel.displayHosts.observeAsState(emptyList())
                val tick by refreshSettingsTick
                val cacheCount by cacheCountState
                val gameCategories by gameCategoriesState
                val activeGameId by activeGameIdState
                val updateState by updateUiState

                var currentTab by remember { mutableStateOf(0) } // 0=Home, 1=TvInstaller, 2=Guide, 3=Settings
                var psPairingHostIp by remember { mutableStateOf<String?>(null) }
                var psPairingManualId by remember { mutableStateOf<Long?>(null) }
                var showPsPairingOverlay by remember { mutableStateOf(false) }
                var showXboxOverlay by remember { mutableStateOf(false) }

                var preSettings by remember(tick) { mutableStateOf(preSettingsRepo.load()) }
                val xboxGamertag = remember(showXboxOverlay, tick) {
                    XboxAuthManager(this@MainActivity).getSavedGamertag()
                }

                val activeGameLabel = remember(activeGameId, gameCategories) {
                    if (activeGameId == TranslationCacheDb.ALL_GAMES_ID) "ทุกเกม"
                    else gameCategories.firstOrNull { it.gameId == activeGameId }?.gameName ?: "GTA VI"
                }

                BackHandler(enabled = showPsPairingOverlay || showXboxOverlay || currentTab != 0) {
                    when {
                        showPsPairingOverlay -> showPsPairingOverlay = false
                        showXboxOverlay -> showXboxOverlay = false
                        else -> currentTab = 0
                    }
                }

                TibanSunsetBackground {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        when {
                            showPsPairingOverlay -> {
                                PsGlassyPairingScreen(
                                    prefillHostIp = psPairingHostIp,
                                    assignManualHostId = psPairingManualId,
                                    onPairedSuccess = { showPsPairingOverlay = false },
                                    onOpenGuide = {
                                        showPsPairingOverlay = false
                                        currentTab = 2
                                    },
                                    onClose = { showPsPairingOverlay = false }
                                )
                            }
                            showXboxOverlay -> {
                                XboxConnectScreen(
                                    onLaunchXboxStream = { xboxCfg -> launchXboxStream(xboxCfg) },
                                    onOpenGuide = {
                                        showXboxOverlay = false
                                        currentTab = 2
                                    },
                                    onBack = { showXboxOverlay = false }
                                )
                            }
                            else -> {
                                when (currentTab) {
                                    0 -> TibanHomeScreen(
                                        displayHosts = hosts,
                                        xboxGamertag = xboxGamertag,
                                        cacheSentenceCount = cacheCount,
                                        activeGameLabel = activeGameLabel,
                                        preSettings = preSettings,
                                        onUpdatePreSettings = { next ->
                                            preSettings = next
                                            preSettingsRepo.save(next)
                                        },
                                        onConnectPsHost = { host ->
                                            handlePsHostTriggered(host) { prefillIp, manualId ->
                                                psPairingHostIp = prefillIp
                                                psPairingManualId = manualId
                                                showPsPairingOverlay = true
                                            }
                                        },
                                        onWakePsHost = { host -> wakeupHost(host) },
                                        onDeleteManualHost = { mHost -> viewModel.deleteManualHost(mHost.manualHost) },
                                        onAddManualHostIp = { ip -> insertManualHostIp(ip) },
                                        onOpenPsPairing = { ip ->
                                            psPairingHostIp = ip
                                            psPairingManualId = null
                                            showPsPairingOverlay = true
                                        },
                                        onOpenXboxScreen = { showXboxOverlay = true },
                                        onNavigateTab = { currentTab = it }
                                    )
                                    1 -> TvInstallerScreen()
                                    2 -> HowToGuideScreen(
                                        onJumpToPsPairing = {
                                            psPairingHostIp = null
                                            psPairingManualId = null
                                            showPsPairingOverlay = true
                                        },
                                        onJumpToXboxConnect = { showXboxOverlay = true },
                                        onJumpToTvInstaller = { currentTab = 1 }
                                    )
                                    else -> PreSettingsScreen(
                                        snapshot = preSettings,
                                        cacheSentenceCount = cacheCount,
                                        gameCategories = gameCategories,
                                        activeGameId = activeGameId,
                                        appVersionName = BuildConfig.VERSION_NAME,
                                        updateState = updateState,
                                        onUpdate = { next ->
                                            preSettings = next
                                            preSettingsRepo.save(next)
                                        },
                                        onSelectActiveGame = { gameId -> selectActiveGameCategory(gameId) },
                                        onAddGameCategory = { name -> addGameCategory(name) },
                                        onDeleteGameCategory = { gameId -> deleteGameCategory(gameId) },
                                        onImportCacheDbForGame = { gameId -> pickDatabaseFileForGame(gameId) },
                                        onSyncCloudDb = { syncCloudDatabase() },
                                        onCheckForUpdate = { checkForAppUpdate() },
                                        onDownloadUpdate = { rel -> downloadAndInstallUpdate(rel) },
                                        onExportBackup = { exportBackupFile() },
                                        onImportBackup = { importBackupFile() }
                                    )
                                }

                                LiquidGlassBottomDock(
                                    selectedTab = currentTab,
                                    onSelectTab = { currentTab = it },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::viewModel.isInitialized) {
            viewModel.discoveryManager.resume()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSettingsTick.value++
        refreshCacheMetadata()
    }

    override fun onStop() {
        super.onStop()
        if (::viewModel.isInitialized) {
            viewModel.discoveryManager.pause()
        }
    }

    private fun refreshCacheMetadata() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = TranslationCacheDb(applicationContext)
                val cnt = db.count()
                val cats = db.listGameCategories()
                val active = db.getActiveGameId()
                try { db.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    if (cnt > 0L) cacheCountState.value = cnt.toInt()
                    gameCategoriesState.value = cats
                    activeGameIdState.value = active
                }
            } catch (_: Exception) {}
        }
    }

    private fun selectActiveGameCategory(gameId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = TranslationCacheDb(applicationContext)
            db.setActiveGameId(gameId)
            try { db.close() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                activeGameIdState.value = gameId
            }
        }
    }

    private fun addGameCategory(gameName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = TranslationCacheDb(applicationContext)
            val created = db.addGameCategory(gameName)
            val cats = db.listGameCategories()
            try { db.close() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                gameCategoriesState.value = cats
                Toast.makeText(this@MainActivity, "สร้างหมวดเกม '${created.gameName}' เรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteGameCategory(gameId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = TranslationCacheDb(applicationContext)
            db.deleteGameCategory(gameId)
            val cnt = db.count()
            val cats = db.listGameCategories()
            val active = db.getActiveGameId()
            try { db.close() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                cacheCountState.value = cnt.toInt()
                gameCategoriesState.value = cats
                activeGameIdState.value = active
                Toast.makeText(this@MainActivity, "ลบหมวดหมู่เกมเรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkForAppUpdate() {
        updateUiState.value = updateUiState.value.copy(
            isChecking = true,
            isError = false,
            statusText = "กำลังตรวจสอบเวอร์ชันล่าสุดจาก GitHub Releases..."
        )
        lifecycleScope.launch {
            val release = AppUpdater.fetchLatestReleaseInfo()
            if (release == null) {
                updateUiState.value = updateUiState.value.copy(
                    isChecking = false,
                    isError = true,
                    statusText = "เชื่อมต่อเซิร์ฟเวอร์อัปเดตไม่สำเร็จ กรุณาตรวจสอบอินเทอร์เน็ต"
                )
            } else if (AppUpdater.isNewerVersion(release.tagName, BuildConfig.VERSION_NAME)) {
                updateUiState.value = updateUiState.value.copy(
                    isChecking = false,
                    isError = false,
                    availableRelease = release,
                    statusText = "พบเวอร์ชันใหม่ ${release.tagName} (${release.title}) พร้อมดาวน์โหลดติดตั้งทันที"
                )
            } else {
                updateUiState.value = updateUiState.value.copy(
                    isChecking = false,
                    isError = false,
                    availableRelease = null,
                    statusText = "คุณใช้เวอร์ชันล่าสุดแล้ว (v${BuildConfig.VERSION_NAME})"
                )
            }
        }
    }

    private fun downloadAndInstallUpdate(release: AppUpdater.ReleaseInfo) {
        updateUiState.value = updateUiState.value.copy(
            isDownloading = true,
            downloadProgress = 0,
            isError = false,
            statusText = "กำลังดาวน์โหลด ${release.tagName}..."
        )
        lifecycleScope.launch {
            val ok = AppUpdater.downloadAndInstallApk(
                activity = this@MainActivity,
                apkUrl = release.apkUrl,
                onProgress = { pct ->
                    updateUiState.value = updateUiState.value.copy(downloadProgress = pct)
                }
            )
            updateUiState.value = updateUiState.value.copy(
                isDownloading = false,
                isError = !ok,
                statusText = if (ok) "ดาวน์โหลดเสร็จแล้ว กำลังเปิดหน้าติดตั้งอัปเดต..." else "ดาวน์โหลดอัปเดตไม่สำเร็จ กรุณาลองใหม่อีกครั้ง"
            )
        }
    }

    private fun insertManualHostIp(ip: String) {
        val dao = getDatabase(this).manualHostDao()
        dao.insert(ManualHost(id = 0, host = ip, registeredHost = null))
            .subscribeOn(Schedulers.io())
            .subscribe({
                AppStateBackupManager.autoBackupAsync(applicationContext)
            }, {})
    }

    private fun handlePsHostTriggered(
        host: DisplayHost,
        onNeedPairing: (String?, Long?) -> Unit
    ) {
        val registeredHost = host.registeredHost
        if (registeredHost != null) {
            fun connectNow() {
                viewModel.rememberConnectedHost(host.host, registeredHost.id)
                AppStateBackupManager.autoBackupAsync(applicationContext)
                val connectInfo = ConnectInfo(
                    host.isPS5,
                    host.host,
                    registeredHost.rpRegistKey,
                    registeredHost.rpKey,
                    Preferences(this).videoProfile
                )
                Intent(this, StreamActivity::class.java).also {
                    it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
                    startActivity(it)
                }
            }

            if (host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("เครื่องอยู่ในโหมดพัก (Rest Mode)")
                    .setMessage("ต้องการส่งคำสั่งปลุกเครื่อง PlayStation ก่อนเชื่อมต่อหรือไม่?")
                    .setPositiveButton("ปลุกเครื่อง") { _, _ -> wakeupHost(host) }
                    .setNeutralButton("เชื่อมต่อทันที") { _, _ -> connectNow() }
                    .setNegativeButton("ยกเลิก", null)
                    .show()
            } else {
                connectNow()
            }
        } else {
            val manualId = (host as? ManualDisplayHost)?.manualHost?.id
            onNeedPairing(host.host, manualId)
        }
    }

    private fun launchXboxStream(xboxConfig: XboxSessionLaunchConfig) {
        Intent(this, StreamActivity::class.java).also {
            it.putExtra(StreamActivity.EXTRA_XBOX_SESSION, xboxConfig)
            startActivity(it)
        }
    }

    private fun wakeupHost(host: DisplayHost) {
        val registeredHost = host.registeredHost ?: return
        viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
        Toast.makeText(this, "ส่งสัญญาณปลุกเครื่อง ${host.name ?: host.host} แล้ว...", Toast.LENGTH_SHORT).show()
    }

    private fun pickDatabaseFileForGame(gameId: String) {
        pendingImportGameId = gameId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_IMPORT_CACHE_DB)
    }

    private fun syncCloudDatabase() {
        Toast.makeText(this, "กำลังซิงค์คลังซับล่าสุดจากคลาวด์...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val db = TranslationCacheDb(applicationContext)
            val added = AppUpdater.syncCloudSubtitleDatabase(this@MainActivity, db, TranslationCacheDb.DEFAULT_GAME_ID)
            val total = db.count()
            val cats = db.listGameCategories()
            try { db.close() } catch (_: Exception) {}
            if (total > 0L) cacheCountState.value = total.toInt()
            gameCategoriesState.value = cats
            Toast.makeText(
                this@MainActivity,
                if (added > 0) "ซิงค์สำเร็จ รวมในเครื่อง $total ประโยค" else "ซิงค์ไม่สำเร็จ กรุณาตรวจสอบอินเทอร์เน็ต",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun exportBackupFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "tibansub_backup.json")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_EXPORT_BACKUP)
    }

    private fun importBackupFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CODE_IMPORT_BACKUP)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_CODE_IMPORT_CACHE_DB -> {
                val targetGame = pendingImportGameId
                lifecycleScope.launch {
                    val db = TranslationCacheDb(applicationContext)
                    val imported = withContext(Dispatchers.IO) { db.importFromUri(this@MainActivity, uri, targetGame) }
                    val total = withContext(Dispatchers.IO) { db.count() }
                    val cats = withContext(Dispatchers.IO) { db.listGameCategories() }
                    try { db.close() } catch (_: Exception) {}
                    if (total > 0L) cacheCountState.value = total.toInt()
                    gameCategoriesState.value = cats
                    val gameLabel = cats.firstOrNull { it.gameId == targetGame }?.gameName ?: targetGame
                    Toast.makeText(
                        this@MainActivity,
                        if (imported > 0) "นำเข้าคลังซับเข้าหมวด '$gameLabel' สำเร็จ $imported ประโยค (รวม $total ประโยค)"
                        else "ไม่สามารถนำเข้าไฟล์นี้ได้ (รองรับไฟล์ .db, .tsv หรือ .txt คั่นด้วยแท็บ)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            REQUEST_CODE_EXPORT_BACKUP -> {
                lifecycleScope.launch {
                    val ok = AppStateBackupManager.exportToUri(this@MainActivity, uri)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "สำรองข้อมูลสำเร็จแล้ว" else "สำรองข้อมูลไม่สำเร็จ",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            REQUEST_CODE_IMPORT_BACKUP -> {
                lifecycleScope.launch {
                    val ok = AppStateBackupManager.importFromUri(this@MainActivity, uri)
                    refreshSettingsTick.value++
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "กู้คืนการตั้งค่าและข้อมูลคอนโซลสำเร็จแล้ว" else "ไฟล์สำรองไม่ถูกต้อง",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

private data class DockTabItem(
    val icon: ImageVector,
    val label: String
)

@Composable
private fun LiquidGlassBottomDock(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        DockTabItem(Icons.Rounded.Home, "หน้าหลัก"),
        DockTabItem(Icons.Rounded.Tv, "ติดตั้งทีวี"),
        DockTabItem(Icons.Rounded.MenuBook, "วิธีใช้งาน"),
        DockTabItem(Icons.Rounded.Settings, "ตั้งค่า")
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xD9130A2B))
            .border(1.1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(22.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = selectedTab == index
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                    .clickable { onSelectTab(index) }
                    .padding(vertical = 5.dp, horizontal = 3.dp)
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (isSelected) Color(0xFF130A2B) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = item.label,
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = if (isSelected) Color(0xFF130A2B) else Color.White
                )
            }
        }
    }
}
