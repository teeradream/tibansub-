package com.metallic.chiaki.tibansub.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metallic.chiaki.tibansub.ui.theme.*

private data class GuideStep(
    val number: String,
    val title: String,
    val detail: String,
    val tip: String? = null
)

@Composable
fun HowToGuideScreen(
    onJumpToPsPairing: () -> Unit,
    onJumpToXboxConnect: () -> Unit,
    onJumpToTvInstaller: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(0) }
    val categories = listOf(
        "1. PS5/PS4 (2 บัญชี)",
        "2. เชื่อมต่อ Xbox",
        "3. ติดตั้งแอปขึ้นทีวี",
        "4. ปุ่ม ☰ ตอนเล่นจริง"
    )

    val steps = when (selectedCategory) {
        0 -> listOf(
            GuideStep(
                "01",
                "เตรียม 2 บัญชี PSN บนเครื่อง PS5 (ทำครั้งเดียว)",
                "ให้มี Account A (ไอดีหลักที่ใช้เล่นเก็บถ้วยบนทีวี) และสร้าง Account B (ไอดีสำรองฟรี) เพิ่มอีก 1 บัญชี เพื่อใช้เชื่อมแอป TIBANSUB โดยไม่แย่งจอย DualSense บนทีวี",
                "หากตั้งใจเล่นบนหน้าจอมือถือโดยตรง ไม่ได้เล่นบนทีวี ใช้ไอดีหลักบัญชีเดียวได้เลย"
            ),
            GuideStep(
                "02",
                "เปิด Remote Play & ดูรหัส PIN 8 หลักบน PS5",
                "สลับเข้า Account B บนเครื่อง PS5 แล้วไปที่ Settings > System > Remote Play > เปิด Enable Remote Play > กด Link Device หน้าจอจะแสดงรหัสตัวเลข 8 หลัก"
            ),
            GuideStep(
                "03",
                "ดึง PSN ID อัตโนมัติ & กรอก PIN ในแอป TIBANSUB",
                "กดการ์ด PlayStation ในหน้าหลัก แตะปุ่ม 'ดึงรหัส PSN ID ในแอป' (ล็อกอิน Account B หรือค้นหาชื่อ PSN) แล้วกรอกตัวเลข 8 หลักลงในช่องแก้ว Glassy PIN กดยืนยันครั้งเดียวจำถาวร!"
            ),
            GuideStep(
                "04",
                "เข้าเล่นจริงบนทีวี 4K พร้อมซับไทย & เสียงพากย์",
                "กดเชื่อมเครื่อง PS5 ในแอป TIBANSUB จากนั้นหยิบจอย DualSense กดปุ่ม PS สลับกลับเข้า Account A (ไอดีหลัก) แล้วเล่นบนทีวีได้ตามปกติ แอปจะแปลซับและพากย์ไทยให้ต่อเนื่องทันที!"
            )
        )
        1 -> listOf(
            GuideStep(
                "01",
                "เปิดโหมด Remote Features บนเครื่อง Xbox",
                "ที่เครื่อง Xbox Series X|S หรือ Xbox One ไปที่ Settings > Devices & connections > Remote features แล้วติ๊กถูก Enable remote features"
            ),
            GuideStep(
                "02",
                "ล็อกอินบัญชี Microsoft ในหน้า Xbox ของ TIBANSUB",
                "กลับมาที่หน้าหลักของแอป เลือกการ์ด Xbox Series X|S แตะล็อกอินด้วยบัญชี Microsoft เดียวกับในเครื่อง Xbox (ล็อกอินครั้งเดียว ไม่ต้องกรอกรหัส Base64 หรือ PIN)"
            ),
            GuideStep(
                "03",
                "เลือกเครื่อง Xbox ในบ้านแล้วเริ่มแปลทันที",
                "แตะชื่อเครื่อง Xbox ที่ตรวจพบ แอปจะเชื่อมต่อภาพสตรีมหลังบ้านเข้าสู่หน้าจอซับหลัก ป๊อปอัพซ้าย/ขวา เสียงพากย์ไทย และเมนูขีดสามขีด (☰) เหมือนฝั่ง PS5 ทุกประการ!"
            )
        )
        2 -> listOf(
            GuideStep(
                "01",
                "แอปทีวีทั้ง Samsung (.wgt) และ Android TV (.apk) ฝังอยู่ในตัวแล้ว",
                "ไม่ต้องโหลดไฟล์แยกจากเว็บ หรือใช้แฟลชไดรฟ์ เพียงเชื่อมต่อมือถือและทีวีในวง Wi-Fi เดียวกัน แล้วเปิดแท็บ 'ติดตั้งแอปทีวี' ด้านล่าง"
            ),
            GuideStep(
                "02",
                "สำหรับทีวี Samsung (Tizen 2018 ขึ้นไป)",
                "เปิดหน้า Apps บนทีวี กดรีโมตปุ่มตัวเลข 1-2-3-4-5 เปิด Developer Mode เป็น ON แล้วกรอกเลข IP มือถือที่แสดงในแอป จากนั้นกดค้างปุ่มปิดทีวี 5 วินาทีเพื่อรีสตาร์ท แล้วกดปุ่ม 'ติดตั้งลงทีวี Samsung อัตโนมัติ' ในแอปได้เลย (มีระบบ Auto-Sign DUID ในตัว ไม่ต้องใช้ Apps2Samsung)"
            ),
            GuideStep(
                "03",
                "สำหรับทีวี Android TV / Google TV (Sony, TCL, Hisense, Xiaomi, Box)",
                "เปิด Developer Options > Wireless Debugging บนทีวี ดูเลขจับคู่ 6 หลัก แล้วนำมากรอกในแท็บ Android TV ของแอป TIBANSUB กดยิงติดตั้ง APK เข้าทีวีเสร็จในคลิกเดียว! (หรือโหลดผ่านลิงก์ Wi-Fi ในบ้านก็ได้)"
            )
        )
        else -> listOf(
            GuideStep(
                "01",
                "ปุ่มเมนูขีดสามขีด (☰) มุมขวาบน",
                "ขณะเชื่อมต่อเข้าเกมแล้ว แตะปุ่มวงกลมสามขีด (☰) มุมขวาบนเพื่อปรับขนาด/สีซับ, กรอบสแกนซับ, เสียงพากย์ไทย, เลือกค่าย AI และซิงค์คลังซับสดๆ แบบ PS-Translator เดิมครบ 100%"
            ),
            GuideStep(
                "02",
                "ซับหลัก + คำแนะนำซ้าย + แชทมือถือขวา",
                "ซับพูดหลักจะแสดงพร้อมเงาอ่านชัดตรงกลาง/ล่าง ส่วนคำแนะนำเกมจะขึ้นกล่องสีเทาฝั่งซ้าย และข้อความแชทมือถือในเกมจะขึ้นเป็นการ์ดฝั่งขวาโดยอัตโนมัติ"
            ),
            GuideStep(
                "03",
                "ปุ่มลัดมุมขวาล่าง & ถาดด่วนขอบบน",
                "ปัดขอบบนลงมาเพื่อสลับ พากย์/เสียงเกม/ซับ/ป๊อปอัพ อย่างรวดเร็ว หรือกดปุ่ม 📺 มุมขวาล่างเพื่อเข้า 'โหมดทีวีจอดำสนิท 100%' วางมือถือพักไว้ข้างๆ ทีวีได้เลย (แตะจอ 2 ครั้งเพื่อออก)"
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "วิธีใช้ & วิธีเชื่อมต่อทีละขั้นตอน",
            fontFamily = TibanPromptFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = Color.White
        )
        Text(
            text = "คู่มือจับมือทำในแอป ครบทั้ง PlayStation, Xbox และการติดตั้งแอปขึ้นทีวี",
            fontFamily = TibanPromptFontFamily,
            fontSize = 13.sp,
            color = TibanColors.TextSecondary
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            categories.forEachIndexed { idx, title ->
                LiquidGlassSelectablePill(
                    label = title,
                    selected = selectedCategory == idx,
                    onClick = { selectedCategory = idx }
                )
            }
        }

        steps.forEach { step ->
            LiquidGlassCard(accentRimColor = TibanColors.AccentCyan) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.22f))
                            .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = step.number,
                            fontFamily = TibanPromptFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.title,
                            fontFamily = TibanPromptFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = step.detail,
                            fontFamily = TibanPromptFontFamily,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            color = TibanColors.TextSecondary
                        )
                        if (!step.tip.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x3300F0FF))
                                    .border(1.dp, TibanColors.AccentCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "💡 ทริค: ${step.tip}",
                                    fontFamily = TibanPromptFontFamily,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        when (selectedCategory) {
            0 -> LiquidGlassButton(
                text = "ไปหน้าจับคู่ PlayStation 5 / PS4 ทันที →",
                onClick = onJumpToPsPairing,
                modifier = Modifier.fillMaxWidth()
            )
            1 -> LiquidGlassButton(
                text = "ไปหน้าเชื่อมต่อเครื่อง Xbox ทันที →",
                onClick = onJumpToXboxConnect,
                modifier = Modifier.fillMaxWidth()
            )
            2 -> LiquidGlassButton(
                text = "เปิดหน้าติดตั้งแอปลงทีวีในตัว →",
                onClick = onJumpToTvInstaller,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(90.dp))
    }
}
