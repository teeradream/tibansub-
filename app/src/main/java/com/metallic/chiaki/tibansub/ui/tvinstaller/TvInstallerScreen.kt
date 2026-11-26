package com.metallic.chiaki.tibansub.ui.tvinstaller

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metallic.chiaki.stream.TvOverlayServer
import com.metallic.chiaki.tibansub.tvdeploy.DiscoveredSmartTv
import com.metallic.chiaki.tibansub.tvdeploy.EmbeddedTvPackageServer
import com.metallic.chiaki.tibansub.tvdeploy.TizenSdbWgtInstaller
import com.metallic.chiaki.tibansub.tvdeploy.WirelessAdbTvInstaller
import com.metallic.chiaki.tibansub.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TvInstallerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val phoneWifiIp = remember { TvOverlayServer.getLocalWifiIp(context) }
    val localPackageServer = remember { EmbeddedTvPackageServer(context) }

    DisposableEffect(Unit) {
        localPackageServer.start()
        onDispose { localPackageServer.stop() }
    }

    var selectedTab by remember { mutableStateOf(0) } // 0 = Samsung .wgt, 1 = Android TV .apk
    var isScanning by remember { mutableStateOf(false) }
    var discoveredTvs by remember { mutableStateOf<List<DiscoveredSmartTv>>(emptyList()) }

    var samsungIp by remember { mutableStateOf("") }
    var samsungFallbackDuid by remember { mutableStateOf<String?>(null) }
    var certPassword by remember { mutableStateOf("") }
    var samsungSession by remember { mutableStateOf(TizenSdbWgtInstaller.getSavedSamsungSession(context)) }
    var hasCert by remember { mutableStateOf(TizenSdbWgtInstaller.hasCustomCertificate(context)) }
    var issuedDuid by remember { mutableStateOf(TizenSdbWgtInstaller.getIssuedDuid(context)) }
    var showSamsungAccountWeb by remember { mutableStateOf(false) }

    var androidTvIp by remember { mutableStateOf("") }
    var androidTvPort by remember { mutableStateOf("5555") }

    var isInstalling by remember { mutableStateOf(false) }
    var progressMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val p12Picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = TizenSdbWgtInstaller.importUserCertificateP12(context, uri, certPassword, isDistributor = false)
                res.onSuccess { msg ->
                    hasCert = true
                    isError = false
                    progressMessage = msg
                }.onFailure { err ->
                    isError = true
                    progressMessage = "นำเข้า .p12 ไม่สำเร็จ: ${err.message}"
                }
            }
        }
    }

    fun triggerScan() {
        scope.launch {
            isScanning = true
            val list = EmbeddedTvPackageServer.scanHomeNetworkForTvs(context)
            discoveredTvs = list
            list.firstOrNull { it.isSamsungTizen }?.let {
                if (samsungIp.isBlank()) {
                    samsungIp = it.ip
                    samsungFallbackDuid = it.knownDuid
                }
            }
            list.firstOrNull { !it.isSamsungTizen }?.let {
                if (androidTvIp.isBlank()) androidTvIp = it.ip
            }
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        triggerScan()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "ติดตั้งแอปทีวีในตัว (All-in-One)",
            fontFamily = TibanPromptFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = Color.White
        )
        Text(
            text = "ไฟล์แอปทีวีทั้ง Samsung (.wgt) และ Android TV (.apk) ถูกฝังไว้ในตัวแอปนี้เรียบร้อยแล้ว",
            fontFamily = TibanPromptFontFamily,
            fontSize = 13.sp,
            color = TibanColors.TextSecondary
        )

        // Phone IP Banner (Crucial for Samsung 1-2-3-4-5 Host PC IP)
        LiquidGlassCard(
            accentRimColor = TibanColors.AccentGold,
            contentPadding = PaddingValues(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "เลข IP มือถือเครื่องนี้ (Host IP)",
                        fontFamily = TibanPromptFontFamily,
                        fontSize = 12.sp,
                        color = TibanColors.TextSecondary
                    )
                    Text(
                        text = phoneWifiIp,
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = TibanColors.AccentGold
                    )
                }
                LiquidGlassButton(
                    text = if (isScanning) "กำลังสแกน..." else "สแกนหาทีวี",
                    primaryGlow = false,
                    compact = true,
                    enabled = !isScanning,
                    leadingContent = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    },
                    onClick = { triggerScan() }
                )
            }
        }

        // Platform Segmented Selector
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            LiquidGlassSelectablePill(
                label = "Samsung TV (.wgt)",
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f)
            )
            LiquidGlassSelectablePill(
                label = "Android / Google TV (.apk)",
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                modifier = Modifier.weight(1f)
            )
        }

        // Discovered TVs Quick Pick
        val filteredTvs = discoveredTvs.filter { if (selectedTab == 0) it.isSamsungTizen else !it.isSamsungTizen }
        if (filteredTvs.isNotEmpty()) {
            filteredTvs.forEach { tv ->
                LiquidGlassCard(
                    accentRimColor = TibanColors.AccentMint,
                    contentPadding = PaddingValues(12.dp),
                    onClick = {
                        if (tv.isSamsungTizen) {
                            samsungIp = tv.ip
                            samsungFallbackDuid = tv.knownDuid
                        } else {
                            androidTvIp = tv.ip
                        }
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tv.name, fontFamily = TibanPromptFontFamily, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                text = when {
                                    tv.isSamsungTizen && tv.sdbDevPortOpen -> "IP: ${tv.ip} | พอร์ต 26101 พร้อมติดตั้ง"
                                    tv.isSamsungTizen -> "IP: ${tv.ip} | กรุณาเปิดโหมด 1-2-3-4-5 บนทีวี"
                                    else -> "IP: ${tv.ip} | พอร์ต ADB 5555 พร้อมติดตั้ง"
                                },
                                fontFamily = TibanPromptFontFamily,
                                fontSize = 12.sp,
                                color = TibanColors.AccentMint
                            )
                        }
                        Text("แตะเลือก", fontFamily = TibanPromptFontFamily, fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        if (selectedTab == 0) {
            // SAMSUNG TIZEN (.WGT) 1-CLICK INSTALLER CARD
            LiquidGlassCard(
                chamferedBottom = true,
                accentRimColor = TibanColors.AccentCyan
            ) {
                Text(
                    text = "ติดตั้งลงทีวี Samsung ในคลิกเดียว",
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "1. เปิดเมนู Apps บนทีวี กดรีโมต 1-2-3-4-5 เปิด Developer Mode: ON แล้วกรอกเลข $phoneWifiIp\n" +
                        "2. กดค้างปุ่มเปิด/ปิดบนรีโมต 5 วินาทีเพื่อให้ทีวีรีสตาร์ท 1 รอบ\n" +
                        "3. กดล็อกอิน Samsung Account ด้านล่าง 1 ครั้ง แล้วกดปุ่มติดตั้ง แอปจะดึง DUID ออกใบรับรองและยิงไฟล์ .wgt เข้าทีวีอัตโนมัติ",
                    fontFamily = TibanPromptFontFamily,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = TibanColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = samsungIp,
                    onValueChange = { samsungIp = it },
                    label = { Text("เลข IP ของทีวี Samsung (เช่น 192.168.1.45)", fontFamily = TibanPromptFontFamily, color = Color.White) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TibanColors.AccentCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Samsung Account OAuth & Certificate Status Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x550C0620))
                        .border(
                            1.dp,
                            if (samsungSession != null || hasCert) TibanColors.AccentMint.copy(alpha = 0.65f)
                            else Color.White.copy(alpha = 0.24f),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (samsungSession != null || hasCert) {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = TibanColors.AccentMint,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = when {
                                samsungSession != null -> "ล็อกอิน Samsung แล้ว: ${samsungSession!!.email}"
                                hasCert && !issuedDuid.isNullOrBlank() -> "มีใบรับรอง Samsung พร้อมใช้แล้ว (DUID: $issuedDuid)"
                                hasCert -> "มีไฟล์ใบรับรอง .p12 พร้อมใช้งานแล้ว"
                                else -> "ขั้นตอนสำคัญ: ล็อกอิน Samsung Account เพื่อออกใบรับรองทีวี"
                            },
                            fontFamily = TibanPromptFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                            color = if (samsungSession != null || hasCert) TibanColors.AccentMint else Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LiquidGlassButton(
                            text = if (samsungSession != null) "สลับบัญชี" else "ล็อกอิน Samsung",
                            primaryGlow = samsungSession == null && !hasCert,
                            compact = true,
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = if (samsungSession == null && !hasCert) Color(0xFF130A2B) else Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            onClick = { showSamsungAccountWeb = true },
                            modifier = Modifier.weight(1.15f)
                        )
                        LiquidGlassButton(
                            text = "นำเข้า .p12",
                            primaryGlow = false,
                            compact = true,
                            leadingContent = {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            },
                            onClick = { p12Picker.launch("*/*") },
                            modifier = Modifier.weight(0.85f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = certPassword,
                        onValueChange = { certPassword = it },
                        label = { Text("รหัสผ่านไฟล์ .p12 (ใช้เฉพาะกรณีนำเข้าไฟล์ .p12 เอง)", fontFamily = TibanPromptFontFamily, color = Color.White, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TibanColors.AccentCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.30f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!progressMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = progressMessage!!,
                        fontFamily = TibanPromptFontFamily,
                        fontSize = 13.sp,
                        color = if (isError) TibanColors.ErrorSoft else TibanColors.AccentMint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                if (isInstalling) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("กำลังติดตั้งลงทีวี Samsung...", fontFamily = TibanPromptFontFamily, color = Color.White)
                    }
                } else {
                    LiquidGlassButton(
                        text = "ติดตั้งลงทีวี Samsung",
                        leadingContent = {
                            Icon(Icons.Rounded.Tv, contentDescription = null, tint = Color(0xFF130A2B), modifier = Modifier.size(17.dp))
                        },
                        onClick = {
                            isInstalling = true
                            isError = false
                            scope.launch {
                                val res = TizenSdbWgtInstaller.installEmbeddedWgtToSamsungTv(
                                    context = context,
                                    tvIp = samsungIp,
                                    fallbackDuid = samsungFallbackDuid,
                                    onProgress = { msg -> progressMessage = msg }
                                )
                                isInstalling = false
                                hasCert = TizenSdbWgtInstaller.hasCustomCertificate(context)
                                issuedDuid = TizenSdbWgtInstaller.getIssuedDuid(context)
                                res.onSuccess { msg ->
                                    isError = false
                                    progressMessage = msg
                                }.onFailure { err ->
                                    isError = true
                                    progressMessage = err.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            // ANDROID TV / GOOGLE TV (.APK) WIRELESS ADB + LAN SERVER CARD
            LiquidGlassCard(
                chamferedBottom = true,
                accentRimColor = TibanColors.AccentMint
            ) {
                Text(
                    text = "ติดตั้งลง Android TV / Google TV ในคลิกเดียว",
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "วิธีที่ 1 (ยิงตรงจากมือถือ): เปิด Developer Options > ADB Debugging บนทีวี กรอก IP ทีวีด้านล่างแล้วกดยิงติดตั้งทันที\n" +
                        "วิธีที่ 2 (ไม่ต้องเปิด ADB): เปิดแอป Browser หรือ Downloader บนทีวี พิมพ์ลิงก์ http://$phoneWifiIp:${EmbeddedTvPackageServer.PORT}/tv.apk โหลดจากมือถือเครื่องนี้ได้เลย",
                    fontFamily = TibanPromptFontFamily,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = TibanColors.TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = androidTvIp,
                        onValueChange = { androidTvIp = it },
                        label = { Text("IP ทีวี Android TV", fontFamily = TibanPromptFontFamily, color = Color.White) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TibanColors.AccentMint,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = androidTvPort,
                        onValueChange = { androidTvPort = it.filter { ch -> ch.isDigit() } },
                        label = { Text("พอร์ต ADB", fontFamily = TibanPromptFontFamily, color = Color.White) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TibanColors.AccentMint,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!progressMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = progressMessage!!,
                        fontFamily = TibanPromptFontFamily,
                        fontSize = 13.sp,
                        color = if (isError) TibanColors.ErrorSoft else TibanColors.AccentMint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                if (isInstalling) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("กำลังส่งไฟล์และติดตั้งลงทีวี...", fontFamily = TibanPromptFontFamily, color = Color.White)
                    }
                } else {
                    LiquidGlassButton(
                        text = "ติดตั้งลง Android TV",
                        leadingContent = {
                            Icon(Icons.Rounded.RocketLaunch, contentDescription = null, tint = Color(0xFF130A2B), modifier = Modifier.size(17.dp))
                        },
                        onClick = {
                            isInstalling = true
                            isError = false
                            val port = androidTvPort.toIntOrNull() ?: 5555
                            scope.launch {
                                val res = WirelessAdbTvInstaller.installEmbeddedApkToAndroidTv(
                                    context = context,
                                    tvIp = androidTvIp,
                                    adbPort = port,
                                    onProgress = { msg -> progressMessage = msg }
                                )
                                isInstalling = false
                                res.onSuccess { msg ->
                                    isError = false
                                    progressMessage = msg
                                }.onFailure { err ->
                                    isError = true
                                    progressMessage = err.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x550C0620))
                        .border(1.dp, TibanColors.AccentCyan.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = TibanColors.AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "ลิงก์โหลดผ่าน Wi-Fi บ้านเปิดทำงานอยู่:\nhttp://$phoneWifiIp:${EmbeddedTvPackageServer.PORT}/tv.apk",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(90.dp))
    }

    if (showSamsungAccountWeb) {
        SamsungCertSignInWebDialog(
            onSignedIn = { capturedSession ->
                TizenSdbWgtInstaller.saveSamsungSession(context, capturedSession)
                samsungSession = capturedSession
                showSamsungAccountWeb = false
                isError = false
                progressMessage = "ล็อกอิน Samsung Account สำเร็จ (${capturedSession.email}) — กดปุ่ม 'ติดตั้งลงทีวี Samsung อัตโนมัติ' ด้านล่างได้เลย"
            },
            onError = { errMsg ->
                isError = true
                progressMessage = errMsg
            },
            onDismiss = { showSamsungAccountWeb = false }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SamsungCertSignInWebDialog(
    onSignedIn: (TizenSdbWgtInstaller.SamsungAccountSession) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Start local loopback OAuth callback server on 127.0.0.1:4794 matching Samsung's official Tizen Certificate Extension
    DisposableEffect(Unit) {
        val server = TizenSdbWgtInstaller.SamsungOAuthCallbackServer(
            onSessionCaptured = { session ->
                scope.launch { onSignedIn(session) }
            },
            onError = { msg ->
                scope.launch { onError(msg) }
            }
        )
        server.start()
        onDispose { server.stop() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF130A2B))
                .border(1.2.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "ล็อกอิน Samsung Account (ออกใบรับรองทีวี)",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        "ล็อกอินเสร็จหน้าต่างนี้จะปิดอัตโนมัติและพร้อมติดตั้งแอปเข้าทีวีทันที",
                        fontFamily = TibanPromptFontFamily,
                        fontSize = 11.5.sp,
                        color = TibanColors.TextMuted
                    )
                }
                LiquidGlassSelectablePill(
                    label = "ปิด",
                    selected = false,
                    leadingIcon = {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    },
                    onClick = onDismiss
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        val wv = this
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(wv, true)
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "")
                            .replace("Version/4.0 ", "")
                        webViewClient = WebViewClient()
                        loadUrl(TizenSdbWgtInstaller.SAMSUNG_OAUTH_LOGIN_URL)
                    }
                }
            )
        }
    }
}
