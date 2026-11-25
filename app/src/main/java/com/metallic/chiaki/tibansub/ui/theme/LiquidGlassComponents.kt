package com.metallic.chiaki.tibansub.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen pure Twilight-to-Sunset gradient background (Indigo -> Violet -> Magenta -> Coral -> Gold)
 * with subtle breathing liquid light refraction — strictly ZERO palm trees and ZERO background icons.
 */
@Composable
fun TibanSunsetBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sunsetGlow")
    val drift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TibanColors.SunsetWallpaperBrush)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Soft silky ambient glow orbs for realistic glass refraction (pure smooth color)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x40FF65D8),
                        Color.Transparent
                    ),
                    center = Offset(w * (0.25f + 0.12f * drift), h * (0.32f + 0.06f * drift)),
                    radius = w * 0.72f
                ),
                radius = w * 0.72f,
                center = Offset(w * (0.25f + 0.12f * drift), h * (0.32f + 0.06f * drift))
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x38FFE07D),
                        Color.Transparent
                    ),
                    center = Offset(w * (0.82f - 0.10f * drift), h * 0.84f),
                    radius = w * 0.65f
                ),
                radius = w * 0.65f,
                center = Offset(w * (0.82f - 0.10f * drift), h * 0.84f)
            )
        }
        content()
    }
}

/**
 * Multi-layered Liquid Glass Card with Specular Rim Border & Inner Top Highlight (Glassy V20 + Liquid Glass Motion).
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    chamferedBottom: Boolean = false,
    accentRimColor: Color = Color.White,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Tactile spring physics from /liquid-glass-motion (stiffness 520, damping 24, mass 0.7)
    val scale by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = 0.64f,
            stiffness = 520f
        ),
        label = "cardScale"
    )

    val shape: Shape = if (chamferedBottom) {
        CutCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 26.dp,
            bottomEnd = 26.dp
        )
    } else {
        RoundedCornerShape(cornerRadius)
    }

    val rimBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.72f),
            accentRimColor.copy(alpha = 0.42f),
            Color.White.copy(alpha = 0.18f),
            accentRimColor.copy(alpha = 0.55f)
        )
    )

    val fillBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0x38FFFFFF),
            Color(0x3D160B32),
            Color(0x52120829)
        )
    )

    val clickableMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null
        ) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fillBrush)
            .border(BorderStroke(1.2.dp, rimBrush), shape)
            .drawWithContent {
                drawContent()
                // Inner top specular highlight line (shadow-[inset_0_1px_1px_rgba(255,255,255,0.45)])
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.55f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(size.width * 0.12f, 1.dp.toPx()),
                    end = Offset(size.width * 0.88f, 1.dp.toPx()),
                    strokeWidth = 1.3.dp.toPx()
                )
            }
            .then(clickableMod)
            .padding(contentPadding),
        content = content
    )
}

/**
 * Tactile Gel Capsule Button (whileTap scale = 0.94, spring stiffness 520, damping 24).
 */
@Composable
fun LiquidGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primaryGlow: Boolean = true,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = 0.62f,
            stiffness = 520f
        ),
        label = "btnScale"
    )

    val bgBrush = when {
        !enabled -> Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color(0x22FFFFFF)))
        primaryGlow -> Brush.horizontalGradient(
            listOf(
                Color(0xEBFFFFFF),
                Color(0xE8FFE5F7),
                Color(0xEBFFF0D6)
            )
        )
        else -> Brush.horizontalGradient(
            listOf(
                Color(0x36FFFFFF),
                Color(0x24FFFFFF)
            )
        )
    }

    val borderBrush = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (primaryGlow) 0.95f else 0.55f),
            TibanColors.AccentCyan.copy(alpha = if (primaryGlow) 0.65f else 0.25f),
            Color.White.copy(alpha = 0.45f)
        )
    )

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.52f
            }
            .clip(CircleShape)
            .background(bgBrush)
            .border(1.2.dp, borderBrush, CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(horizontal = 22.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            fontFamily = TibanPromptFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (primaryGlow && enabled) Color(0xFF1A103D) else Color.White,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Tactile Selectable Glass Pill (for tabs, colors, AI providers, speed presets).
 */
@Composable
fun LiquidGlassSelectablePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "pillScale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                if (selected) Color.White.copy(alpha = 0.88f)
                else Color(0x2B150A30)
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accentColor else Color.White.copy(alpha = 0.28f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = TibanPromptFontFamily,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
            color = if (selected) Color(0xFF1A103D) else Color.White
        )
    }
}

/**
 * Tactile Liquid Glass Toggle Switch.
 */
@Composable
fun LiquidGlassToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.66f, stiffness = 480f),
        label = "switchThumb"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onCheckedChange(!checked)
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color.White
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    fontFamily = TibanPromptFontFamily,
                    fontSize = 12.sp,
                    color = TibanColors.TextMuted
                )
            }
        }

        Box(
            modifier = Modifier
                .width(50.dp)
                .height(28.dp)
                .clip(CircleShape)
                .background(
                    if (checked) Brush.horizontalGradient(
                        listOf(TibanColors.AccentCyan, TibanColors.AccentMint)
                    ) else Brush.horizontalGradient(
                        listOf(Color(0x4012092B), Color(0x4012092B))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = if (checked) 0.8f else 0.3f), CircleShape)
                .padding(3.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .offset(x = (22f * thumbOffset).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/**
 * Glassy OTP V20 Digit Input Slots (Supports 8-digit PS5 PIN & 6-digit Wireless ADB Pairing Code).
 */
@Composable
fun GlassyDigitSlotsInput(
    value: String,
    onValueChange: (String) -> Unit,
    digitCount: Int = 8,
    splitInTwoRowsForEight: Boolean = true,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "cursorAlpha"
    )

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Hidden input field capturing numeric keypad & paste cleanly
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                val digitsOnly = raw.filter { it.isDigit() }.take(digitCount)
                onValueChange(digitsOnly)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
        )

        if (digitCount == 8 && splitInTwoRowsForEight) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusRequester.requestFocus() }
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (idx in 0 until 4) {
                        GlassySingleDigitBox(
                            char = value.getOrNull(idx),
                            isActive = value.length == idx,
                            cursorAlpha = cursorAlpha,
                            boxWidth = 52.dp,
                            boxHeight = 58.dp
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (idx in 4 until 8) {
                        GlassySingleDigitBox(
                            char = value.getOrNull(idx),
                            isActive = value.length == idx,
                            cursorAlpha = cursorAlpha,
                            boxWidth = 52.dp,
                            boxHeight = 58.dp
                        )
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { focusRequester.requestFocus() }
            ) {
                for (idx in 0 until digitCount) {
                    GlassySingleDigitBox(
                        char = value.getOrNull(idx),
                        isActive = value.length == idx,
                        cursorAlpha = cursorAlpha,
                        boxWidth = 42.dp,
                        boxHeight = 50.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassySingleDigitBox(
    char: Char?,
    isActive: Boolean,
    cursorAlpha: Float,
    boxWidth: Dp,
    boxHeight: Dp
) {
    val popScale by animateFloatAsState(
        targetValue = if (char != null) 1.03f else if (isActive) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMediumLow),
        label = "digitPop"
    )

    val borderColor = when {
        isActive -> Color.White
        char != null -> TibanColors.AccentCyan.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.32f)
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = popScale
                scaleY = popScale
            }
            .width(boxWidth)
            .height(boxHeight)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isActive) Color.White.copy(alpha = 0.24f)
                else Color(0x38130A2E)
            )
            .border(
                width = if (isActive) 1.8.dp else 1.1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (char != null) {
            Text(
                text = char.toString(),
                fontFamily = TibanPromptFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = Color.White
            )
        } else if (isActive) {
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(2.5.dp)
                    .graphicsLayer { alpha = cursorAlpha }
                    .background(Color.White, CircleShape)
            )
        }
    }
}

/**
 * Velocity-Aware Liquid Glass Bottom Sheet with Child Follow-Through Inertia Bounce (/liquid-glass-motion).
 */
@Composable
fun LiquidGlassBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    var rawDragPx by remember { mutableStateOf(0f) }

    val smoothDragPx by animateFloatAsState(
        targetValue = rawDragPx,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 350f),
        label = "sheetDrag"
    )
    val childFollowThroughPx = smoothDragPx * 0.12f

    LaunchedEffect(visible) {
        if (visible) rawDragPx = 0f
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 420f),
            initialOffsetY = { it }
        ),
        exit = fadeOut(tween(180)) + slideOutVertically(
            animationSpec = tween(200),
            targetOffsetY = { it }
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x8808041A))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            val dismissOffsetPx = with(density) { 120.dp.toPx() }
            val dismissVelocityPx = with(density) { 500.dp.toPx() }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .graphicsLayer {
                        translationY = smoothDragPx
                    }
                    .pointerInput(Unit) {
                        val velocityTracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragEnd = {
                                val velocityY = velocityTracker.calculateVelocity().y
                                if (rawDragPx > dismissOffsetPx || velocityY > dismissVelocityPx) {
                                    rawDragPx = 0f
                                    onDismiss()
                                } else {
                                    rawDragPx = 0f
                                }
                            },
                            onDragCancel = { rawDragPx = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val next = rawDragPx + dragAmount
                                // Rubber-band resistance (0.18) when pulled past top limit
                                rawDragPx = if (next < 0f) next * 0.18f else next
                            }
                        )
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xEB291B5C),
                                Color(0xF2170D38)
                            )
                        )
                    )
                    .border(
                        1.2.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.15f))
                        ),
                        RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
                    )
                    .padding(horizontal = 22.dp, vertical = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 14.dp)
                        .width(46.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.45f))
                )
                if (!title.isNullOrEmpty()) {
                    Text(
                        text = title,
                        fontFamily = TibanPromptFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                Column(
                    modifier = Modifier.graphicsLayer {
                        translationY = childFollowThroughPx
                    },
                    content = content
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
