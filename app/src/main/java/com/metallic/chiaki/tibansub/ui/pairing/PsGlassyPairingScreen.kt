package com.metallic.chiaki.tibansub.ui.pairing

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.metallic.chiaki.common.AppStateBackupManager
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEventCanceled
import com.metallic.chiaki.lib.RegistEventFailed
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target as ConsoleTarget
import com.metallic.chiaki.regist.ChiakiRxLog
import com.metallic.chiaki.tibansub.psn.PsnAccountHelper
import com.metallic.chiaki.tibansub.ui.theme.*
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class PsTargetOption(val label: String, val target: ConsoleTarget) {
    PS5("PS5", ConsoleTarget.PS5_1),
    PS4_GE8("PS4 (8.0+)", ConsoleTarget.PS4_10),
    PS4_7("PS4 (7.x)", ConsoleTarget.PS4_9)
}

@Composable
fun PsGlassyPairingScreen(
    prefillHostIp: String?,
    assignManualHostId: Long? = null,
    onPairedSuccess: () -> Unit,
    onOpenGuide: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("ps_translator_prefs", Context.MODE_PRIVATE) }

    var selectedTarget by remember { mutableStateOf(PsTargetOption.PS5) }
    var hostIp by remember { mutableStateOf(prefillHostIp?.ifBlank { "255.255.255.255" } ?: "255.255.255.255") }
    var broadcastMode by remember { mutableStateOf(prefillHostIp.isNullOrBlank()) }
    var psnIdInput by remember { mutableStateOf(prefs.getString("saved_psn_id", "") ?: "") }
    var psnDisplayLabel by remember { mutableStateOf(prefs.getString("saved_psn_online_label", "") ?: "") }
    var pinDigits by remember { mutableStateOf("") }

    var showSonyOAuthDialog by remember { mutableStateOf(false) }
    var showInAppLookupWebDialog by remember { mutableStateOf(false) }

    var isFetchingPsn by remember { mutableStateOf(false) }
    var isPairing by remember { mutableStateOf(false) }
    var pairingSuccess by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    var activeRegist by remember { mutableStateOf<Regist?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            try {
                activeRegist?.stop()
                activeRegist?.dispose()
            } catch (_: Exception) {}
        }
    }

    val normalizedBase64 = remember(psnIdInput) { PsnAccountHelper.normalizeToValidBase64(psnIdInput) }
    val step1Done = true
    val step2Done = normalizedBase64 != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Step Indicator Pills
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepBadgePill("1. เตรียม 2 บัญชี", active = false, done = step1Done)
            StepBadgePill(
                if (step2Done) "2. PSN ID พร้อม" else "2. ดึง PSN ID",
                active = !step2Done,
                done = step2Done
            )
            StepBadgePill("3. PIN 8 หลัก", active = step2Done, done = pairingSuccess)
        }

        // Main Glassy OTP V20 Chamfered Card
        LiquidGlassCard(
            chamferedBottom = true,
            accentRimColor = if (pairingSuccess) TibanColors.AccentMint else TibanColors.AccentCyan,
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0x55006FCD), Color(0x553BF0FF))
                            )
                        )
                        .border(1.2.dp, TibanColors.AccentCyan.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (pairingSuccess) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = TibanColors.AccentMint,
                            modifier = Modifier.size(30.dp)
                        )
                    } else {
                        PlayStationLogoIcon(
                            modifier = Modifier.size(34.dp, 26.dp),
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (pairingSuccess) "จับคู่เครื่องสำเร็จแล้ว" else "เชื่อมต่อ ${selectedTarget.label}",
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "ไปที่ Settings > System > Remote Play > Link Device บนเครื่องเพื่อดูรหัส PIN 8 หลัก",
                    fontFamily = TibanPromptFontFamily,
                    fontSize = 12.5.sp,
                    color = TibanColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PsTargetOption.values().forEach { opt ->
                        LiquidGlassSelectablePill(
                            label = opt.label,
                            selected = selectedTarget == opt,
                            onClick = { selectedTarget = opt }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // PSN Account ID Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x550D0722))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (psnDisplayLabel.isNotEmpty()) "PSN ID ($psnDisplayLabel)" else "รหัส PSN Account ID",
                            fontFamily = TibanPromptFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Text(
                            text = if (step2Done) "พร้อมใช้งาน" else "ต้องระบุ 1 ครั้ง",
                            fontFamily = TibanPromptFontFamily,
                            fontSize = 12.sp,
                            color = if (step2Done) TibanColors.AccentMint else TibanColors.AccentGold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LiquidGlassButton(
                            text = if (isFetchingPsn) "กำลังดึงรหัส..." else "ล็อกอิน PSN ดึงอัตโนมัติ",
                            primaryGlow = !step2Done,
                            enabled = !isFetchingPsn,
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Key,
                                    contentDescription = null,
                                    tint = if (!step2Done) Color(0xFF130A2B) else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            onClick = { showSonyOAuthDialog = true },
                            modifier = Modifier.weight(1.15f)
                        )
                        LiquidGlassButton(
                            text = "ค้นจากชื่อ PSN",
                            primaryGlow = false,
                            leadingContent = {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            },
                            onClick = { showInAppLookupWebDialog = true },
                            modifier = Modifier.weight(0.85f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = psnIdInput,
                        onValueChange = { psnIdInput = it },
                        label = { Text("PSN Account ID (Base64 / เลข 64-bit / Hex)", fontFamily = TibanPromptFontFamily, color = Color.White) },
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

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = hostIp,
                    onValueChange = {
                        hostIp = it
                        broadcastMode = it.trim() == "255.255.255.255" || it.isBlank()
                    },
                    label = { Text("IP เครื่อง PS5/PS4 (หรือ 255.255.255.255 ค้นหาในวง Wi-Fi)", fontFamily = TibanPromptFontFamily, color = Color.White) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TibanColors.AccentCyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.30f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "กรอกรหัส PIN 8 หลักจากหน้าจอทีวี",
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                GlassyDigitSlotsInput(
                    value = pinDigits,
                    onValueChange = { pinDigits = it },
                    digitCount = 8,
                    splitInTwoRowsForEight = true
                )

                if (!statusMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = statusMessage!!,
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = if (isError) TibanColors.ErrorSoft else TibanColors.AccentMint,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                if (isPairing || isFetchingPsn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = if (isFetchingPsn) "กำลังดึงรหัส PSN Account ID จาก Sony..." else "กำลังจับคู่กับเครื่อง PlayStation...",
                            fontFamily = TibanPromptFontFamily,
                            color = Color.White
                        )
                    }
                } else {
                    LiquidGlassButton(
                        text = if (pairingSuccess) "เสร็จสิ้น — พร้อมเริ่มแปลภาษา" else "ยืนยันรหัส & จับคู่เครื่องถาวร",
                        enabled = pairingSuccess || (normalizedBase64 != null && pinDigits.length == 8),
                        onClick = {
                            if (pairingSuccess) {
                                onPairedSuccess()
                                return@LiquidGlassButton
                            }
                            val validB64 = normalizedBase64 ?: return@LiquidGlassButton
                            val accountBytes = try {
                                Base64.decode(validB64, Base64.DEFAULT)
                            } catch (_: Exception) {
                                null
                            }
                            if (accountBytes == null || accountBytes.size != RegistInfo.ACCOUNT_ID_SIZE) {
                                isError = true
                                statusMessage = "รหัส PSN Account ID ไม่ถูกต้อง"
                                return@LiquidGlassButton
                            }

                            prefs.edit().putString("saved_psn_id", validB64).apply()
                            isPairing = true
                            isError = false
                            statusMessage = "กำลังส่งคำสั่งลงทะเบียนไปที่ ${hostIp.ifBlank { "255.255.255.255" }}..."

                            try {
                                activeRegist?.stop()
                                activeRegist?.dispose()
                            } catch (_: Exception) {}

                            val targetIp = hostIp.trim().ifBlank { "255.255.255.255" }
                            val info = RegistInfo(
                                selectedTarget.target,
                                targetIp,
                                broadcastMode || targetIp == "255.255.255.255",
                                null,
                                accountBytes,
                                pinDigits.toInt()
                            )
                            val rxLog = ChiakiRxLog(ChiakiLog.Level.ALL.value)
                            try {
                                activeRegist = Regist(info, rxLog.log) { event ->
                                    when (event) {
                                        is RegistEventSuccess -> {
                                            val host = event.host
                                            val db = getDatabase(context)
                                            val regHost = RegisteredHost(host)
                                            db.registeredHostDao()
                                                .deleteByMac(MacAddress(host.serverMac))
                                                .andThen(db.registeredHostDao().insert(regHost))
                                                .let { single ->
                                                    if (assignManualHostId != null) {
                                                        single.flatMapCompletable { regId ->
                                                            db.manualHostDao().assignRegisteredHost(assignManualHostId, regId)
                                                        }
                                                    } else {
                                                        single.ignoreElement()
                                                    }
                                                }
                                                .subscribeOn(Schedulers.io())
                                                .subscribe({
                                                    AppStateBackupManager.autoBackupAsync(context)
                                                    scope.launch {
                                                        isPairing = false
                                                        pairingSuccess = true
                                                        isError = false
                                                        statusMessage = "จับคู่สำเร็จกับ ${host.serverNickname} บันทึกเครื่องถาวรเรียบร้อยแล้ว"
                                                    }
                                                }, { err ->
                                                    scope.launch {
                                                        isPairing = false
                                                        isError = true
                                                        statusMessage = "บันทึกข้อมูลไม่สำเร็จ: ${err.message}"
                                                    }
                                                })
                                        }
                                        is RegistEventFailed -> {
                                            scope.launch {
                                                isPairing = false
                                                isError = true
                                                statusMessage = "จับคู่ไม่สำเร็จ: ตรวจสอบว่าล็อกอินถูกบัญชีตรงกับ PSN ID และอยู่ใน Wi-Fi เดียวกัน"
                                            }
                                        }
                                        is RegistEventCanceled -> {
                                            scope.launch { isPairing = false }
                                        }
                                    }
                                }
                            } catch (e: CreateError) {
                                isPairing = false
                                isError = true
                                statusMessage = "ไม่สามารถเริ่มระบบลงทะเบียนได้ (${e.errorCode})"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LiquidGlassButton(
                        text = "ดูวิธีทำทีละขั้น",
                        primaryGlow = false,
                        leadingContent = {
                            Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        },
                        onClick = onOpenGuide,
                        modifier = Modifier.weight(1f)
                    )
                    LiquidGlassButton(
                        text = "กลับหน้าหลัก",
                        primaryGlow = false,
                        onClick = onClose,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    // In-App Sony Remote Play OAuth Dialog (chiaki-ng verified secret + multi-hook redirect capture)
    if (showSonyOAuthDialog) {
        SonyPsnOAuthWebViewDialog(
            onDismiss = { showSonyOAuthDialog = false },
            onCodeReceived = { authCode ->
                showSonyOAuthDialog = false
                isFetchingPsn = true
                statusMessage = "กำลังดึงรหัส PSN Account ID และแปลงเป็น Base64 อัตโนมัติ..."
                isError = false
                scope.launch {
                    val res = PsnAccountHelper.exchangeOAuthCodeForBase64Id(authCode)
                    isFetchingPsn = false
                    res.onSuccess { (base64Id, onlineName) ->
                        psnIdInput = base64Id
                        psnDisplayLabel = onlineName
                        prefs.edit()
                            .putString("saved_psn_id", base64Id)
                            .putString("saved_psn_online_label", onlineName)
                            .apply()
                        statusMessage = "ดึง PSN ID ของ $onlineName สำเร็จแล้ว ($base64Id) กรอก PIN 8 หลักต่อได้เลย"
                    }.onFailure { err ->
                        isError = true
                        statusMessage = "${err.message} — ลองล็อกอินใหม่ หรือใช้ปุ่ม 'ค้นจากชื่อ PSN' แทนได้"
                    }
                }
            }
        )
    }

    if (showInAppLookupWebDialog) {
        InAppPsnLookupDialog(
            onDismiss = { showInAppLookupWebDialog = false },
            onApplyId = { pasted ->
                val norm = PsnAccountHelper.normalizeToValidBase64(pasted) ?: pasted.trim()
                psnIdInput = norm
                prefs.edit().putString("saved_psn_id", norm).apply()
                showInAppLookupWebDialog = false
            }
        )
    }
}

@Composable
private fun StepBadgePill(label: String, active: Boolean, done: Boolean) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                when {
                    active -> Color.White.copy(alpha = 0.92f)
                    done -> Color(0x3800FFA6)
                    else -> Color(0x55130A2B)
                }
            )
            .border(
                1.dp,
                when {
                    active -> Color.White
                    done -> TibanColors.AccentMint
                    else -> Color.White.copy(alpha = 0.30f)
                },
                CircleShape
            )
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontFamily = TibanPromptFontFamily,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 11.5.sp,
            color = if (active) Color(0xFF130A2B) else Color.White
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SonyPsnOAuthWebViewDialog(
    onDismiss: () -> Unit,
    onCodeReceived: (String) -> Unit
) {
    val codeDelivered = remember { AtomicBoolean(false) }

    fun tryCaptureCode(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.startsWith(PsnAccountHelper.SONY_REDIRECT_PREFIX, ignoreCase = true) ||
            url.contains("remoteplay/redirect", ignoreCase = true)
        ) {
            val code = PsnAccountHelper.extractCodeFromRedirectOrInput(url)
            if (!code.isNullOrBlank() && codeDelivered.compareAndSet(false, true)) {
                onCodeReceived(code)
                return true
            }
        }
        return false
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
                        "ล็อกอินบัญชี PSN (แนะนำใช้ไอดีสำรอง Account B)",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        "เมื่อล็อกอินสำเร็จ แอปจะดึงรหัส PSN Account ID อัตโนมัติทันที",
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
                        settings.setSupportMultipleWindows(false)
                        // Remove '; wv' so Sony OAuth treats this as a standard mobile browser
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv", "")
                            .replace("Version/4.0 ", "")

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString()
                                if (tryCaptureCode(url)) return true
                                return false
                            }

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (tryCaptureCode(url)) return true
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (tryCaptureCode(url)) {
                                    view?.stopLoading()
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString()
                                if (!url.isNullOrBlank() && url.contains("remoteplay/redirect", ignoreCase = true)) {
                                    view?.post { tryCaptureCode(url) }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        loadUrl(PsnAccountHelper.SONY_OAUTH_AUTHORIZE_URL)
                    }
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppPsnLookupDialog(
    onDismiss: () -> Unit,
    onApplyId: (String) -> Unit
) {
    var pastedValue by remember { mutableStateOf("") }

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
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ค้นหารหัสจากชื่อ PSN ในแอป",
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    LiquidGlassSelectablePill(
                        label = "ปิด",
                        selected = false,
                        leadingIcon = {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        },
                        onClick = onDismiss
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = pastedValue,
                        onValueChange = { pastedValue = it },
                        placeholder = { Text("วางรหัส Base64 หรือเลข Account ID ตรงนี้", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TibanColors.AccentCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    LiquidGlassButton(
                        text = "นำมาใช้",
                        enabled = pastedValue.isNotBlank(),
                        onClick = { onApplyId(pastedValue) }
                    )
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl("https://psn.flipscreen.games/")
                    }
                }
            )
        }
    }
}
