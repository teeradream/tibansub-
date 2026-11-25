package com.metallic.chiaki.tibansub.ui.presettings

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.tibansub.ui.theme.*
import java.util.Locale

private val SUBTITLE_COLORS = listOf(
    "#00FF88" to "เขียวนีออน",
    "#FFFFFF" to "ขาวสว่าง",
    "#00E5FF" to "ฟ้าไซเบอร์",
    "#FFD700" to "เหลืองทอง",
    "#FF8C00" to "ส้มสด",
    "#FF66B2" to "ชมพูไวซ์"
)

private fun parseHexSafe(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color(0xFF00FF88)
}

@Composable
fun PreSettingsScreen(
    snapshot: PreSettingsSnapshot,
    cacheSentenceCount: Int,
    onUpdate: (PreSettingsSnapshot) -> Unit,
    onImportCacheDb: () -> Unit,
    onSyncCloudDb: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val context = LocalContext.current
    var previewTts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale("th", "TH")
            }
        }
        previewTts = engine
        onDispose {
            try {
                engine?.stop()
                engine?.shutdown()
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "ตั้งค่าก่อนเริ่มเชื่อมต่อ",
            fontFamily = TibanPromptFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = Color.White
        )
        Text(
            text = "ค่าทุกอย่างในหน้านี้ซิงค์ตรงกับปุ่มเมนูสามขีด (☰) ในหน้าสตรีมแบบเรียลไทม์",
            fontFamily = TibanPromptFontFamily,
            fontSize = 13.sp,
            color = TibanColors.TextSecondary
        )

        // 1. Live 16:9 Subtitle & Popup Preview Box (Exact PS-Translator.apk look)
        LiquidGlassCard(
            accentRimColor = TibanColors.AccentCyan,
            contentPadding = PaddingValues(14.dp)
        ) {
            Text(
                text = "ตัวอย่างหน้าจอซับจริง (Live Preview)",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(178.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF0E1329),
                                Color(0xFF191230),
                                Color(0xFF090814)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                    .padding(12.dp)
            ) {
                // Top-right 3-line (☰) badge indicator matching classic StreamActivity
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0x990D1624))
                        .border(1.2.dp, TibanColors.AccentMint, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("☰", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                // Classic Left Tutorial & Right Chat Bubble Preview when Popup Zones enabled
                if (snapshot.popupZonesMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xCC22242A))
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "กด L1 + R1 เพื่อเปิดโหมดโฟกัส",
                            fontFamily = TibanPromptFontFamily,
                            fontSize = 11.sp,
                            color = Color(0xFFF2F2F2)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xD91B2338))
                            .border(1.dp, Color(0x66FF66B2), RoundedCornerShape(12.dp))
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "เจอกันที่ท่าเรือเที่ยงคืนนะ",
                            fontFamily = TibanPromptFontFamily,
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }
                }

                // Exact PS-Translator frameless fit-content main subtitle cascade
                if (snapshot.subOverlayEnabled) {
                    val subAlign = if (snapshot.subPosition == "CENTER") Alignment.Center else Alignment.BottomCenter
                    val bgAlpha = if (snapshot.subBgEnabled) (snapshot.subBgOpacity / 100f).coerceIn(0f, 1f) else 0f
                    val previewFontSp = (snapshot.subFontSizeSp * 0.56f).coerceIn(11f, 26f).sp
                    val subColor = parseHexSafe(snapshot.subTextColorHex)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(subAlign)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = bgAlpha * 0.78f))
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "เจสัน: ขึ้นรถเลย เราต้องรีบหนีแล้ว!",
                            style = TextStyle(
                                fontFamily = TibanPromptFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = previewFontSp,
                                color = subColor,
                                shadow = Shadow(
                                    color = Color.Black,
                                    offset = Offset(2f, 2f),
                                    blurRadius = 4f
                                )
                            ),
                            textAlign = TextAlign.Center
                        )
                        if (snapshot.subHistoryLimit >= 2) {
                            Text(
                                text = "ลูเซีย: ฉันเคลียร์ทางข้างหน้าไว้ให้แล้ว",
                                style = TextStyle(
                                    fontFamily = TibanPromptFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (previewFontSp.value * 0.88f).sp,
                                    color = subColor.copy(alpha = 0.65f),
                                    shadow = Shadow(color = Color.Black, offset = Offset(1.5f, 1.5f), blurRadius = 3f)
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                        if (snapshot.subHistoryLimit >= 3) {
                            Text(
                                text = "ตำรวจกำลังตามมาติดๆ!",
                                style = TextStyle(
                                    fontFamily = TibanPromptFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (previewFontSp.value * 0.80f).sp,
                                    color = subColor.copy(alpha = 0.35f),
                                    shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 2f)
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // 2. Subtitle Styling Card
        LiquidGlassCard {
            Text(
                text = "1. รูปแบบซับไตเติลหลัก & โซนป๊อปอัพ",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))

            LiquidGlassToggleRow(
                title = "แสดงกล่องซับไตเติลบนหน้าจอ",
                subtitle = "ฟอนต์ GTA Art Deco + Prompt SemiBold พร้อมเงา",
                checked = snapshot.subOverlayEnabled,
                onCheckedChange = { onUpdate(snapshot.copy(subOverlayEnabled = it)) }
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ขนาดตัวหนังสือซับ: ${snapshot.subFontSizeSp} sp",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color.White
            )
            Slider(
                value = snapshot.subFontSizeSp.toFloat(),
                onValueChange = { onUpdate(snapshot.copy(subFontSizeSp = it.toInt())) },
                valueRange = 10f..80f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = TibanColors.AccentCyan,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                )
            )

            Text(
                text = "สีตัวหนังสือซับ (6 สี)",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SUBTITLE_COLORS.forEach { (hex, _) ->
                    val c = parseHexSafe(hex)
                    val isSelected = snapshot.subTextColorHex.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.35f),
                                shape = CircleShape
                            )
                            .clickable { onUpdate(snapshot.copy(subTextColorHex = hex)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "ตำแหน่งซับ & จำนวนบรรทัดเรียงซ้อน",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidGlassSelectablePill(
                    label = "ด้านล่างจอ",
                    selected = snapshot.subPosition == "BOTTOM",
                    onClick = { onUpdate(snapshot.copy(subPosition = "BOTTOM")) }
                )
                LiquidGlassSelectablePill(
                    label = "กลางหน้าจอ",
                    selected = snapshot.subPosition == "CENTER",
                    onClick = { onUpdate(snapshot.copy(subPosition = "CENTER")) }
                )
                for (lines in 1..3) {
                    LiquidGlassSelectablePill(
                        label = "$lines แถว",
                        selected = snapshot.subHistoryLimit == lines,
                        onClick = { onUpdate(snapshot.copy(subHistoryLimit = lines)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            LiquidGlassToggleRow(
                title = "เปิดกรอบพื้นหลังซับไร้ขอบ (ย่อ-ขยายตามคำ)",
                checked = snapshot.subBgEnabled,
                onCheckedChange = { onUpdate(snapshot.copy(subBgEnabled = it)) }
            )
            if (snapshot.subBgEnabled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    listOf(40, 80, 100).forEach { op ->
                        LiquidGlassSelectablePill(
                            label = "ทึบ $op%",
                            selected = snapshot.subBgOpacity == op,
                            onClick = { onUpdate(snapshot.copy(subBgOpacity = op)) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            LiquidGlassToggleRow(
                title = "โหมดป๊อปอัพอัตโนมัติ (คำแนะนำซ้าย / มือถือขวา)",
                subtitle = "แสดงกล่องคำแนะนำสีเทาฝั่งซ้ายและการ์ดแชทฝั่งขวาแบบแอปเดิม",
                checked = snapshot.popupZonesMode,
                onCheckedChange = { onUpdate(snapshot.copy(popupZonesMode = it)) }
            )
        }

        // 3. Thai Voice Dubbing & Audio Card
        LiquidGlassCard {
            Text(
                text = "2. เสียงพากย์ไทย (TTS) & เสียงเกม",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))

            LiquidGlassToggleRow(
                title = "เปิดเสียงพากย์ภาษาไทยอัตโนมัติ",
                subtitle = "อ่านออกเสียงบทพูดภาษาไทยทันทีตามตัวละคร",
                checked = snapshot.ttsVoiceEnabled,
                onCheckedChange = { onUpdate(snapshot.copy(ttsVoiceEnabled = it)) }
            )
            LiquidGlassToggleRow(
                title = "ปิดเสียงเกมจากมือถือ (Mute Console Audio)",
                subtitle = "แนะนำให้เปิดเมื่อเล่นคู่กับทีวี เพื่อฟังเฉพาะเสียงพากย์ไทยชัดเจน",
                checked = snapshot.gameAudioMuted,
                onCheckedChange = { onUpdate(snapshot.copy(gameAudioMuted = it)) }
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ความเร็วเสียงพากย์ & โหมดแยกเสียง",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(1.0f to "1.0x ปกติ", 1.25f to "1.25x แนะนำ", 1.5f to "1.5x เร็ว").forEach { (spd, lbl) ->
                    LiquidGlassSelectablePill(
                        label = lbl,
                        selected = kotlin.math.abs(snapshot.speechSpeedRate - spd) < 0.08f,
                        onClick = { onUpdate(snapshot.copy(speechSpeedRate = spd)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(
                    "AUTO_AI" to "แยกชาย/หญิงอัตโนมัติ",
                    "MALE" to "เสียงชาย",
                    "FEMALE" to "เสียงหญิง"
                ).forEach { (mode, lbl) ->
                    LiquidGlassSelectablePill(
                        label = lbl,
                        selected = snapshot.voiceGenderMode == mode,
                        onClick = { onUpdate(snapshot.copy(voiceGenderMode = mode)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf(
                    "SMART_QUEUE" to "Smart Queue (แนะนำ)",
                    "QUEUE_ALL" to "อ่านจบทุกประโยค",
                    "FLUSH_IMMEDIATE" to "ตัดขึ้นซับใหม่ทันที"
                ).forEach { (qMode, lbl) ->
                    LiquidGlassSelectablePill(
                        label = lbl,
                        selected = snapshot.ttsQueueMode == qMode,
                        onClick = { onUpdate(snapshot.copy(ttsQueueMode = qMode)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            LiquidGlassButton(
                text = "🔊 ทดลองฟังเสียงพากย์ไทย",
                primaryGlow = false,
                onClick = {
                    previewTts?.setSpeechRate(snapshot.speechSpeedRate)
                    previewTts?.speak(
                        "พร้อมแปลภาษาและพากย์เสียงไทยในแอป ทิบ้านซับ แล้วครับ",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "preview_test"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 4. AI 7 Providers & 0ms Local Cache DB Card
        LiquidGlassCard {
            Text(
                text = "3. ค่าย AI แปลภาษา (7 ค่าย) & คลังซับ 0ms",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))

            LiquidGlassToggleRow(
                title = "เปิดคลังซับออฟไลน์ในเครื่อง (0ms Local Cache)",
                subtitle = "พร้อมใช้งานในเครื่องแล้ว $cacheSentenceCount ประโยค (แปลทันทีไม่ต้องรอเน็ต)",
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

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LiquidGlassButton(
                    text = "📂 นำเข้า .DB/.TSV",
                    primaryGlow = false,
                    onClick = onImportCacheDb,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "☁️ ซิงค์ซับล่าสุด",
                    primaryGlow = false,
                    onClick = onSyncCloudDb,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 5. Stream Quality & Backup Card
        LiquidGlassCard {
            Text(
                text = "4. คุณภาพสตรีมหลังบ้าน & สำรองข้อมูล",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    text = "💾 ส่งออกไฟล์สำรอง",
                    primaryGlow = false,
                    onClick = onExportBackup,
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassButton(
                    text = "📥 กู้คืนไฟล์สำรอง",
                    primaryGlow = false,
                    onClick = onImportBackup,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(90.dp))
    }
}
