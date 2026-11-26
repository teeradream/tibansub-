package com.metallic.chiaki.tibansub.ui.xbox

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.metallic.chiaki.tibansub.ui.theme.*
import com.metallic.chiaki.tibansub.xbox.XboxAuthManager
import com.metallic.chiaki.tibansub.xbox.XboxConsoleItem
import com.metallic.chiaki.tibansub.xbox.XboxSessionLaunchConfig
import kotlinx.coroutines.launch

@Composable
fun XboxConnectScreen(
    onLaunchXboxStream: (XboxSessionLaunchConfig) -> Unit,
    onOpenGuide: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { XboxAuthManager(context) }

    var isLoading by remember { mutableStateOf(false) }
    var showMicrosoftOAuth by remember { mutableStateOf(false) }
    var tokens by remember { mutableStateOf<XboxAuthManager.XboxSessionTokens?>(null) }
    var consoles by remember { mutableStateOf<List<XboxConsoleItem>>(emptyList()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun loadConsolesFromTokens(t: XboxAuthManager.XboxSessionTokens) {
        scope.launch {
            isLoading = true
            val res = authManager.fetchConsoles(t)
            isLoading = false
            res.onSuccess { list ->
                consoles = list
                isError = false
                statusText = if (list.isEmpty()) {
                    "ล็อกอินสำเร็จแล้ว แต่ยังไม่พบเครื่อง Xbox ที่เปิด Remote Features ในบัญชีนี้"
                } else {
                    "พบเครื่อง Xbox พร้อมเชื่อมต่อ ${list.size} เครื่อง"
                }
            }.onFailure { err ->
                isError = true
                statusText = "ดึงรายชื่อเครื่อง Xbox ไม่สำเร็จ: ${err.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (authManager.hasSavedLogin()) {
            isLoading = true
            statusText = "กำลังเชื่อมต่อบัญชี Microsoft ที่บันทึกไว้..."
            val res = authManager.restoreSessionFromRefreshToken()
            isLoading = false
            res.onSuccess { t ->
                tokens = t
                loadConsolesFromTokens(t)
            }.onFailure {
                statusText = "เซสชันหมดอายุ กรุณากดล็อกอินบัญชี Microsoft ใหม่"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "เชื่อมต่อ Xbox Series X|S",
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 23.sp,
                color = Color.White
            )
            LiquidGlassSelectablePill(
                label = "กลับ",
                selected = false,
                leadingIcon = {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                },
                onClick = onBack
            )
        }

        LiquidGlassCard(
            chamferedBottom = true,
            accentRimColor = TibanColors.AccentMint
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0x55107C10), Color(0x5528FFA5))
                            )
                        )
                        .border(1.3.dp, TibanColors.AccentMint, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    XboxLogoIcon(modifier = Modifier.size(30.dp), tint = Color.White)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (tokens != null) "ล็อกอินแล้ว: ${tokens!!.gamertag}" else "Xbox Remote Play ในแอปเดียว",
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "ใช้ซับหลัก กล่องข้อความซ้าย/ขวา พากย์ไทย และปุ่มสามขีด (☰) ร่วมกับฝั่ง PS5 ได้ครบ 100%",
                    fontFamily = TibanPromptFontFamily,
                    fontSize = 12.5.sp,
                    color = TibanColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )

                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!statusText.isNullOrEmpty()) {
                    Text(
                        text = statusText!!,
                        fontFamily = TibanPromptFontFamily,
                        fontSize = 13.sp,
                        color = if (isError) TibanColors.ErrorSoft else TibanColors.AccentMint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                if (tokens == null) {
                    LiquidGlassButton(
                        text = "ล็อกอินบัญชี Microsoft",
                        leadingContent = {
                            Icon(Icons.Rounded.Key, contentDescription = null, tint = Color(0xFF130A2B), modifier = Modifier.size(16.dp))
                        },
                        onClick = { showMicrosoftOAuth = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        LiquidGlassButton(
                            text = "รีเฟรชเครื่อง",
                            primaryGlow = false,
                            compact = true,
                            leadingContent = {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                            },
                            onClick = { loadConsolesFromTokens(tokens!!) },
                            modifier = Modifier.weight(1f)
                        )
                        LiquidGlassButton(
                            text = "สลับบัญชี",
                            primaryGlow = false,
                            compact = true,
                            onClick = {
                                authManager.signOut()
                                tokens = null
                                consoles = emptyList()
                                statusText = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Discovered Xbox Consoles List
        consoles.forEach { xbox ->
            LiquidGlassCard(
                accentRimColor = TibanColors.AccentMint,
                onClick = {
                    val t = tokens ?: return@LiquidGlassCard
                    onLaunchXboxStream(
                        XboxSessionLaunchConfig(
                            serverId = xbox.serverId,
                            deviceName = xbox.deviceName,
                            gsToken = t.gsToken,
                            regionBaseUri = t.regionBaseUri
                        )
                    )
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = xbox.deviceName,
                            fontFamily = TibanPromptFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = "${xbox.consoleType} | สถานะ: ${xbox.powerState}",
                            fontFamily = TibanPromptFontFamily,
                            fontSize = 12.5.sp,
                            color = TibanColors.AccentMint
                        )
                    }
                    LiquidGlassButton(
                        text = "เริ่มแปล",
                        compact = true,
                        onClick = {
                            val t = tokens ?: return@LiquidGlassButton
                            onLaunchXboxStream(
                                XboxSessionLaunchConfig(
                                    serverId = xbox.serverId,
                                    deviceName = xbox.deviceName,
                                    gsToken = t.gsToken,
                                    regionBaseUri = t.regionBaseUri
                                )
                            )
                        }
                    )
                }
            }
        }

        LiquidGlassButton(
            text = "ดูวิธีเปิด Remote Features บน Xbox",
            primaryGlow = false,
            compact = true,
            leadingContent = {
                Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            },
            onClick = onOpenGuide,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showMicrosoftOAuth) {
        MicrosoftXboxOAuthDialog(
            onDismiss = { showMicrosoftOAuth = false },
            onCodeReceived = { authCode ->
                showMicrosoftOAuth = false
                isLoading = true
                statusText = "กำลังยืนยันสิทธิ์ Xbox Remote Play..."
                scope.launch {
                    val res = authManager.loginWithAuthCode(authCode)
                    isLoading = false
                    res.onSuccess { t ->
                        tokens = t
                        loadConsolesFromTokens(t)
                    }.onFailure { err ->
                        isError = true
                        statusText = "ล็อกอินไม่สำเร็จ: ${err.message}"
                    }
                }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MicrosoftXboxOAuthDialog(
    onDismiss: () -> Unit,
    onCodeReceived: (String) -> Unit
) {
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
                Text(
                    "ล็อกอินบัญชี Microsoft (Xbox)",
                    fontFamily = TibanPromptFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
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

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith(XboxAuthManager.REDIRECT_URI)) {
                                    val code = Uri.parse(url).getQueryParameter("code")
                                    if (!code.isNullOrBlank()) {
                                        onCodeReceived(code)
                                        return true
                                    }
                                }
                                return false
                            }
                        }
                        loadUrl(XboxAuthManager.OAUTH_AUTHORIZE_URL)
                    }
                }
            )
        }
    }
}
