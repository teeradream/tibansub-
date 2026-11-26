package com.metallic.chiaki.tibansub.ui.presettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.stream.AppUpdater
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.stream.TranslationCacheDb
import com.metallic.chiaki.tibansub.ui.theme.*

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val statusText: String? = null,
    val isError: Boolean = false,
    val availableRelease: AppUpdater.ReleaseInfo? = null
)

@Composable
fun PreSettingsScreen(
    snapshot: PreSettingsSnapshot,
    cacheSentenceCount: Int,
    gameCategories: List<TranslationCacheDb.GameCategoryInfo>,
    activeGameId: String,
    appVersionName: String,
    updateState: AppUpdateUiState,
    onUpdate: (PreSettingsSnapshot) -> Unit,
    onSelectActiveGame: (String) -> Unit,
    onAddGameCategory: (String) -> Unit,
    onDeleteGameCategory: (String) -> Unit,
    onImportCacheDbForGame: (String) -> Unit,
    onSyncCloudDb: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDownloadUpdate: (AppUpdater.ReleaseInfo) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    var showAddGameSheet by remember { mutableStateOf(false) }
    var newGameName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "ตั้งค่า",
            fontFamily = TibanPromptFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = Color.White
        )
        Text(
            text = "จัดการค่าย AI แปลภาษา คลังซับแยกตามเกม คุณภาพสตรีม และการอัปเดตแอป (ส่วนรูปแบบซับและเสียงพากย์ปรับสดได้จากปุ่ม ☰ ในหน้าสตรีม)",
            fontFamily = TibanPromptFontFamily,
            fontSize = 13.sp,
            color = TibanColors.TextSecondary
        )

        // 1. AI 7 Providers Card
        LiquidGlassCard(accentRimColor = TibanColors.AccentCyan) {
            Text(
                text = "1. ค่าย AI แปลภาษา (7 ค่าย) & แคช 0ms",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))

            LiquidGlassToggleRow(
                title = "เปิดคลังซับออฟไลน์ในเครื่อง (0ms Local Cache)",
                subtitle = "พร้อมใช้งานรวมทั้งหมด %,d ประโยค (แปลทันทีไม่ต้องรอเน็ต)".format(cacheSentenceCount),
                checked = snapshot.localCacheEnabled,
                onCheckedChange = { onUpdate(snapshot.copy(localCacheEnabled = it)) }
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                StreamActivity.TranslationProvider.values().forEach { prov ->
                    LiquidGlassSelectablePill(
                        label = prov.displayName,
                        selected = snapshot.activeProvider == prov,
                        onClick = { onUpdate(snapshot.copy(activeProvider = prov)) }
                    )
                }
            }

            val selectedProv = snapshot.activeProvider
            if (selectedProv != StreamActivity.TranslationProvider.GOOGLE_FREE) {
                Spacer(modifier = Modifier.height(10.dp))
                val currentKey = snapshot.providerKeys[selectedProv] ?: ""
                val currentModel = snapshot.providerModels[selectedProv] ?: selectedProv.defaultModel

                OutlinedTextField(
                    value = currentKey,
                    onValueChange = { newKey ->
                        val nextKeys = snapshot.providerKeys.toMutableMap()
                        nextKeys[selectedProv] = newKey
                        onUpdate(snapshot.copy(providerKeys = nextKeys))
                    },
                    label = { Text(selectedProv.keyLabel, fontFamily = TibanPromptFontFamily, color = Color.White) },
                    placeholder = { Text(selectedProv.keyHint, color = Color.White.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TibanColors.AccentCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = currentModel,
                    onValueChange = { newModel ->
                        val nextModels = snapshot.providerModels.toMutableMap()
                        nextModels[selectedProv] = newModel
                        onUpdate(snapshot.copy(providerModels = nextModels))
                    },
                    label = { Text("ชื่อโมเดล AI (Model)", fontFamily = TibanPromptFontFamily, color = Color.White) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TibanColors.AccentCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (selectedProv == StreamActivity.TranslationProvider.CUSTOM) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = snapshot.customApiUrl,
                        onValueChange = { onUpdate(snapshot.copy(customApiUrl = it)) },
                        label = { Text("Custom OpenAI Endpoint URL", fontFamily = TibanPromptFontFamily, color = Color.White) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TibanColors.AccentCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 2. Game Subtitle Library Categories + Per-Game Subtitle File Import Card
        LiquidGlassCard(accentRimColor = TibanColors.AccentPink) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "2. คลังซับแยกตามหมวดหมู่เกม",
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Color.White
                )
                LiquidGlassSelectablePill(
                    label = "เพิ่มหมวดเกม",
                    selected = false,
                    leadingIcon = {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    },
                    onClick = { showAddGameSheet = true }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "เลือกหมวดเกมที่กำลังเล่น (หรือเลือก 'ทุกเกม') และนำเข้าไฟล์ซับ (.db / .tsv / .txt) แยกเก็บตามแต่ละเกมได้",
                fontFamily = TibanPromptFontFamily,
                fontSize = 12.5.sp,
                color = TibanColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Filter Chips: All Games + Each Game Category
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                LiquidGlassSelectablePill(
                    label = "ทุกเกม (%,d)".format(cacheSentenceCount),
                    selected = activeGameId == TranslationCacheDb.ALL_GAMES_ID,
                    onClick = { onSelectActiveGame(TranslationCacheDb.ALL_GAMES_ID) }
                )
                gameCategories.forEach { cat ->
                    LiquidGlassSelectablePill(
                        label = "${cat.gameName} (%,d)".format(cat.sentenceCount),
                        selected = activeGameId == cat.gameId,
                        onClick = { onSelectActiveGame(cat.gameId) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Game Category List with Per-Game File Import button
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gameCategories.forEach { cat ->
                    val isSelected = activeGameId == cat.gameId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) Color(0x403BF0FF) else Color(0x550C0620))
                            .border(
                                1.dp,
                                if (isSelected) TibanColors.AccentCyan else Color.White.copy(alpha = 0.20f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelectActiveGame(cat.gameId) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SportsEsports,
                                contentDescription = null,
                                tint = if (isSelected) TibanColors.AccentCyan else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = cat.gameName,
                                    fontFamily = TibanPromptFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = "%,d ประโยคในคลัง${if (cat.isBuiltIn) " | คลังหลัก" else " | หมวดเกมที่เพิ่มเอง"}".format(cat.sentenceCount),
                                    fontFamily = TibanPromptFontFamily,
                                    fontSize = 11.5.sp,
                                    color = TibanColors.TextMuted
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LiquidGlassSelectablePill(
                                label = "เพิ่มไฟล์ซับ",
                                selected = false,
                                leadingIcon = {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                },
                                onClick = { onImportCacheDbForGame(cat.gameId) }
                            )
                            if (!cat.isBuiltIn) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x33FF5577))
                                        .border(1.dp, Color(0x66FF9AA2), CircleShape)
                                        .clickable { onDeleteGameCategory(cat.gameId) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "ลบหมวดเกม",
                                        tint = TibanColors.ErrorSoft,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            LiquidGlassButton(
                text = "ซิงค์คลังซับจากคลาวด์",
                primaryGlow = false,
                compact = true,
                leadingContent = {
                    Icon(Icons.Rounded.CloudSync, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                },
                onClick = onSyncCloudDb,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3. Stream Quality, TV Sync & Backup Card
        LiquidGlassCard(accentRimColor = TibanColors.AccentMint) {
            Text(
                text = "3. คุณภาพสตรีมหลังบ้าน & สำรองข้อมูล",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "ความละเอียดสตรีม PS5 / PS4 (แนะนำ 720p หรือ 1080p)",
                fontFamily = TibanPromptFontFamily,
                fontSize = 13.sp,
                color = TibanColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preferences.Resolution.values().forEach { res ->
                    LiquidGlassSelectablePill(
                        label = res.value,
                        selected = snapshot.resolution == res,
                        onClick = { onUpdate(snapshot.copy(resolution = res)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Preferences.FPS.values().forEach { fpsItem ->
                    LiquidGlassSelectablePill(
                        label = "${fpsItem.value} FPS",
                        selected = snapshot.fps == fpsItem,
                        onClick = { onUpdate(snapshot.copy(fps = fpsItem)) }
                    )
                }
                Preferences.Codec.values().forEach { cItem ->
                    LiquidGlassSelectablePill(
                        label = cItem.value.uppercase(),
                        selected = snapshot.codec == cItem,
                        onClick = { onUpdate(snapshot.copy(codec = cItem)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            LiquidGlassToggleRow(
                title = "ส่งซับลอยขึ้นทีวีอัตโนมัติ (พอร์ต 8791)",
                subtitle = "เชื่อมกับแอป TIBANSUB TV บนทีวี Samsung และ Android TV ทันที",
                checked = snapshot.tvCastOverlayEnabled,
                onCheckedChange = { onUpdate(snapshot.copy(tvCastOverlayEnabled = it)) }
            )
            LiquidGlassToggleRow(
                title = "แสดงปุ่มจอยสัมผัสบนจอมือถือ",
                subtitle = "ปิดไว้หากใช้จอยจริงเล่นบนทีวี",
                checked = snapshot.onScreenControls,
                onCheckedChange = { onUpdate(snapshot.copy(onScreenControls = it)) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LiquidGlassButton(
                    text = "ส่งออกสำรอง",
                    primaryGlow = false,
                    compact = true,
                    leadingContent = {
                        Icon(Icons.Rounded.Backup, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    },
                    onClick = onExportBackup,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "กู้คืนสำรอง",
                    primaryGlow = false,
                    compact = true,
                    leadingContent = {
                        Icon(Icons.Rounded.Restore, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    },
                    onClick = onImportBackup,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 4. App Version & Check for Updates Card
        LiquidGlassCard(accentRimColor = TibanColors.AccentGold) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "4. เวอร์ชันแอป & เช็กอัปเดต",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White
                    )
                    Text(
                        text = "TIBANSUB เวอร์ชันปัจจุบัน: v$appVersionName",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp,
                        color = TibanColors.AccentGold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x33FFB86C))
                        .border(1.dp, TibanColors.AccentGold.copy(alpha = 0.65f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "v$appVersionName",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TibanColors.AccentGold
                    )
                }
            }

            if (!updateState.statusText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = updateState.statusText,
                    fontFamily = TibanPromptFontFamily,
                    fontSize = 12.5.sp,
                    color = if (updateState.isError) TibanColors.ErrorSoft else TibanColors.AccentMint
                )
            }

            if (updateState.isDownloading) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = (updateState.downloadProgress / 100f).coerceIn(0f, 1f),
                    color = TibanColors.AccentCyan,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "กำลังดาวน์โหลดอัปเดต ${updateState.downloadProgress}%...",
                    fontFamily = TibanPromptFontFamily,
                    fontSize = 12.sp,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LiquidGlassButton(
                    text = if (updateState.isChecking) "กำลังเช็ก..." else "เช็กอัปเดต",
                    primaryGlow = updateState.availableRelease == null,
                    compact = true,
                    enabled = !updateState.isChecking && !updateState.isDownloading,
                    leadingContent = {
                        if (updateState.isChecking) {
                            CircularProgressIndicator(
                                color = Color(0xFF130A2B),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp)
                            )
                        } else {
                            Icon(
                                Icons.Rounded.SystemUpdate,
                                contentDescription = null,
                                tint = if (updateState.availableRelease == null) Color(0xFF130A2B) else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = onCheckForUpdate,
                    modifier = Modifier.weight(1f)
                )

                val rel = updateState.availableRelease
                if (rel != null) {
                    LiquidGlassButton(
                        text = "ติดตั้ง ${rel.tagName}",
                        primaryGlow = true,
                        compact = true,
                        enabled = !updateState.isDownloading,
                        leadingContent = {
                            Icon(Icons.Rounded.Download, contentDescription = null, tint = Color(0xFF130A2B), modifier = Modifier.size(16.dp))
                        },
                        onClick = { onDownloadUpdate(rel) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(90.dp))
    }

    LiquidGlassBottomSheet(
        visible = showAddGameSheet,
        onDismiss = { showAddGameSheet = false },
        title = "เพิ่มหมวดหมู่คลังซับของเกมใหม่"
    ) {
        Text(
            text = "ตั้งชื่อเกมเพื่อแยกเก็บไฟล์ซับ (.db หรือ .tsv) ไม่ให้ปะปนกับเกมอื่น",
            fontFamily = TibanPromptFontFamily,
            fontSize = 13.sp,
            color = TibanColors.TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        OutlinedTextField(
            value = newGameName,
            onValueChange = { newGameName = it },
            label = { Text("ชื่อเกม (เช่น Red Dead Redemption 2, Cyberpunk 2077)", fontFamily = TibanPromptFontFamily, color = Color.White) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = TibanColors.AccentPink,
                unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(14.dp))
        LiquidGlassButton(
            text = "สร้างหมวดหมู่เกม",
            enabled = newGameName.isNotBlank(),
            onClick = {
                onAddGameCategory(newGameName.trim())
                newGameName = ""
                showAddGameSheet = false
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
