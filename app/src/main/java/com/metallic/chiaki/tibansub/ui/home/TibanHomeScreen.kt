package com.metallic.chiaki.tibansub.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.tibansub.ui.presettings.PreSettingsSnapshot
import com.metallic.chiaki.tibansub.ui.theme.*

@Composable
fun TibanHomeScreen(
    displayHosts: List<DisplayHost>,
    xboxGamertag: String,
    cacheSentenceCount: Int,
    activeGameLabel: String,
    preSettings: PreSettingsSnapshot,
    onUpdatePreSettings: (PreSettingsSnapshot) -> Unit,
    onConnectPsHost: (DisplayHost) -> Unit,
    onWakePsHost: (DisplayHost) -> Unit,
    onDeleteManualHost: (ManualDisplayHost) -> Unit,
    onAddManualHostIp: (String) -> Unit,
    onOpenPsPairing: (String?) -> Unit,
    onOpenXboxScreen: () -> Unit,
    onNavigateTab: (Int) -> Unit
) {
    var showAddIpSheet by remember { mutableStateOf(false) }
    var manualIpInput by remember { mutableStateOf("") }

    val primaryPsHost = displayHosts.firstOrNull { it.isRegistered } ?: displayHosts.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Brand Header: Prominent User-Approved Retro-Neon Gamepad App Icon
        Image(
            painter = painterResource(id = R.drawable.tibansub_logo_badge),
            contentDescription = "TIBANSUB",
            modifier = Modifier
                .padding(top = 2.dp)
                .size(78.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = 1.3.dp,
                    brush = Brush.linearGradient(
                        listOf(TibanColors.AccentCyan.copy(alpha = 0.7f), TibanColors.AccentPink.copy(alpha = 0.7f))
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
        )

        // 1. PlayStation 5 / PS4 Console Card (libchiaki Backend)
        LiquidGlassCard(
            accentRimColor = TibanColors.AccentCyan,
            onClick = {
                if (primaryPsHost != null) {
                    onConnectPsHost(primaryPsHost)
                } else {
                    onOpenPsPairing(null)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0x4D006FCD), Color(0x4D3BF0FF))
                            )
                        )
                        .border(1.2.dp, TibanColors.AccentCyan.copy(alpha = 0.75f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    PlayStationLogoIcon(
                        modifier = Modifier.size(32.dp, 24.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PlayStation 5 / PS4",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                    val psStatusText = when {
                        primaryPsHost != null && primaryPsHost.isRegistered -> {
                            val name = primaryPsHost.name ?: primaryPsHost.host
                            val standby = (primaryPsHost as? DiscoveredDisplayHost)?.discoveredHost?.state == DiscoveryHost.State.STANDBY
                            if (standby) "$name | โหมดพักเครื่อง (แตะเพื่อปลุก)"
                            else "$name | พร้อมแปลภาษา"
                        }
                        primaryPsHost != null -> "พบเครื่อง ${primaryPsHost.name ?: primaryPsHost.host} | แตะเพื่อจับคู่ครั้งแรก"
                        else -> "ค้นหาอัตโนมัติใน Wi-Fi | แตะเพื่อจับคู่ PIN 8 หลัก"
                    }
                    Text(
                        text = psStatusText,
                        fontFamily = TibanPromptFontFamily,
                        fontSize = 12.5.sp,
                        color = if (primaryPsHost?.isRegistered == true) TibanColors.AccentMint else TibanColors.TextSecondary
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Discovered / saved PS consoles list + quick actions
            if (displayHosts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                displayHosts.forEach { host ->
                    val isStandby = (host as? DiscoveredDisplayHost)?.discoveredHost?.state == DiscoveryHost.State.STANDBY
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x550C0620))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                            .clickable { onConnectPsHost(host) }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${host.name ?: "PlayStation"} (${if (host.isPS5) "PS5" else "PS4"})",
                                fontFamily = TibanPromptFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            Text(
                                text = "IP: ${host.host} | ${if (host.isRegistered) "จับคู่ถาวรแล้ว" else "ยังไม่จับคู่"}",
                                fontFamily = TibanPromptFontFamily,
                                fontSize = 11.5.sp,
                                color = TibanColors.TextMuted
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isStandby && host.isRegistered) {
                                LiquidGlassSelectablePill(
                                    label = "ปลุก",
                                    selected = false,
                                    onClick = { onWakePsHost(host) }
                                )
                            }
                            if (host is ManualDisplayHost) {
                                LiquidGlassSelectablePill(
                                    label = "ลบ",
                                    selected = false,
                                    onClick = { onDeleteManualHost(host) }
                                )
                            }
                            LiquidGlassSelectablePill(
                                label = if (host.isRegistered) "เข้าแปล" else "จับคู่",
                                selected = true,
                                onClick = { onConnectPsHost(host) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LiquidGlassSelectablePill(
                    label = "จับคู่ใหม่ (PIN)",
                    selected = false,
                    leadingIcon = {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    },
                    onClick = { onOpenPsPairing(primaryPsHost?.host) },
                    modifier = Modifier.weight(1f)
                )
                LiquidGlassSelectablePill(
                    label = "เพิ่ม IP เอง",
                    selected = false,
                    leadingIcon = {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    },
                    onClick = { showAddIpSheet = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 2. Xbox Series X|S / One Console Card (Microsoft OAuth + WebRTC Backend)
        LiquidGlassCard(
            accentRimColor = TibanColors.AccentMint,
            onClick = onOpenXboxScreen,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0x4D107C10), Color(0x4D28FFA5))
                            )
                        )
                        .border(1.2.dp, TibanColors.AccentMint.copy(alpha = 0.78f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    XboxLogoIcon(
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Xbox Series X|S & One",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (xboxGamertag.isNotBlank()) "บัญชีที่เชื่อมไว้: $xboxGamertag | พร้อมเชื่อมต่อ"
                        else "Xbox Remote Play | แตะเพื่อล็อกอินบัญชี Microsoft",
                        fontFamily = TibanPromptFontFamily,
                        fontSize = 12.5.sp,
                        color = if (xboxGamertag.isNotBlank()) TibanColors.AccentMint else TibanColors.TextSecondary
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 3. Quick Settings Summary Card
        LiquidGlassCard(
            accentRimColor = TibanColors.AccentPink,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "สถานะการตั้งค่าปัจจุบัน",
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                LiquidGlassSelectablePill(
                    label = "ตั้งค่า",
                    selected = false,
                    leadingIcon = {
                        Icon(Icons.Rounded.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    },
                    onClick = { onNavigateTab(3) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            SummaryStatusRow(
                label = "ระบบแปล AI",
                value = "${preSettings.activeProvider.displayName}${if (preSettings.localCacheEnabled) " + 0ms DB" else ""}"
            )
            SummaryStatusRow(
                label = "หมวดคลังซับเกม",
                value = activeGameLabel
            )

            LiquidGlassToggleRow(
                title = "เสียงพากย์ไทย (${preSettings.speechSpeedRate}x)",
                checked = preSettings.ttsVoiceEnabled,
                onCheckedChange = { onUpdatePreSettings(preSettings.copy(ttsVoiceEnabled = it)) }
            )
            LiquidGlassToggleRow(
                title = "ส่งซับลอยขึ้นทีวีอัตโนมัติ (Samsung / Android TV)",
                checked = preSettings.tvCastOverlayEnabled,
                onCheckedChange = { onUpdatePreSettings(preSettings.copy(tvCastOverlayEnabled = it)) }
            )

            Spacer(modifier = Modifier.height(10.dp))
            LiquidGlassButton(
                text = if (primaryPsHost?.isRegistered == true) "เริ่มเชื่อมต่อ & แปลภาษา"
                else "ตั้งค่าจับคู่เครื่องเพื่อเริ่มใช้งาน",
                onClick = {
                    if (primaryPsHost != null) {
                        onConnectPsHost(primaryPsHost)
                    } else {
                        onOpenPsPairing(null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Local Subtitle Cache Pill positioned right above Bottom Dock (per user Figma layout)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0x88130A2B))
                .border(1.dp, Color.White.copy(alpha = 0.40f), CircleShape)
                .clickable { onNavigateTab(3) }
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = null,
                tint = TibanColors.AccentCyan,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = "คลังซับในเครื่อง: %,d ประโยค | 0ms ($activeGameLabel)".format(cacheSentenceCount),
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(86.dp))
    }

    LiquidGlassBottomSheet(
        visible = showAddIpSheet,
        onDismiss = { showAddIpSheet = false },
        title = "เพิ่มเลข IP เครื่อง PS5 / PS4 ด้วยตัวเอง"
    ) {
        Text(
            text = "ใช้กรณีเราเตอร์บล็อกการค้นหาอัตโนมัติ หรือต่อข้ามวงแลน",
            fontFamily = TibanPromptFontFamily,
            fontSize = 13.sp,
            color = TibanColors.TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        OutlinedTextField(
            value = manualIpInput,
            onValueChange = { manualIpInput = it },
            label = { Text("IP Address (เช่น 192.168.1.25)", fontFamily = TibanPromptFontFamily, color = Color.White) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = TibanColors.AccentCyan,
                unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(14.dp))
        LiquidGlassButton(
            text = "บันทึก IP เครื่อง",
            enabled = manualIpInput.isNotBlank(),
            onClick = {
                onAddManualHostIp(manualIpInput.trim())
                manualIpInput = ""
                showAddIpSheet = false
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SummaryStatusRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontFamily = TibanPromptFontFamily,
            fontSize = 13.sp,
            color = TibanColors.TextSecondary
        )
        Text(
            text = value,
            fontFamily = TibanPromptFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = Color.White
        )
    }
}
