# 🎮 TIBANSUB (All-in-One PS5/PS4 & Xbox Translator + Built-in TV Installer)

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/tibansub_logo_badge.png" width="128" height="128" alt="TIBANSUB Logo" />
</p>

<p align="center">
  <b>แอปพลิเคชันแปลซับไตเติลเกมและพากย์เสียงไทยแบบเรียลไทม์ รองรับทั้ง PlayStation 5 / PS4 และ Xbox Series X|S / One ในแอปเดียว</b><br/>
  <i>ดีไซน์ใหม่ Twilight-to-Sunset × Glassy V20 (Liquid Glass Motion) พร้อมระบบฝังและติดตั้งแอปขึ้นทีวี Samsung & Android TV ในตัว</i>
</p>

---

## ✨ ไฮไลต์ฟีเจอร์ทั้งหมดของ TIBANSUB

1. **รองรับทั้ง PlayStation และ Xbox ในแอปเดียว**
   - **PlayStation 5 / PS4:** ใช้ Engine หลังบ้าน `libchiaki` ความหน่วงต่ำ พร้อมระบบ **ล็อกอินดึง PSN Account ID อัตโนมัติในแอป** และหน้าจับคู่รหัส PIN 8 หลักสไตล์ **Glassy OTP V20**
   - **Xbox Series X|S & Xbox One:** เชื่อมต่อผ่านบัญชี Microsoft OAuth2 และสตรีมภาพผ่าน **Xbox Home Console WebRTC** เข้าสู่ระบบแปลภาษากลางตัวเดียวกันทันที
2. **ตั้งค่าแยกครบทุกหมวดตั้งแต่ "ก่อนเริ่มเชื่อมต่อ" (Pre-Settings Studio)**
   - ปรับขนาดฟอนต์ (`10–80 sp`), สีซับ 6 สี, ตำแหน่งซับ, โซนป๊อปอัพซ้าย/ขวา, เสียงพากย์ไทย TTS (`1.0x–1.5x` พร้อมปุ่มทดลองฟังเสียง), เลือกค่าย AI 7 ค่าย และจัดการคลังซับออฟไลน์ `0ms` พร้อมจอ **Live Preview** ดูตัวอย่างซับสดๆ ก่อนเข้าเกม
   - ซิงค์ข้อมูลแบบสองทางกับ **ปุ่มเมนูขีดสามขีด (`☰`)** ในหน้าสตรีมแบบ 100%
3. **ฝังแอปทีวีทั้ง Samsung (`.wgt`) และ Android TV (`.apk`) มาพร้อมในตัวแอปเดียว**
   - **Samsung Smart TV (Tizen):** อ่านค่า `DUID` ของทีวีผ่านพอร์ต `26101` ทำการประทับลายเซ็น (Auto-Sign) และยิงติดตั้งลงทีวีจากมือถือในคลิกเดียว
   - **Android TV / Google TV:** ยิงติดตั้งไฟล์ APK ที่ฝังอยู่ในแอปผ่าน **Wireless ADB** ในคลิกเดียว หรือดาวน์โหลดผ่าน Wi-Fi บ้าน (`http://<IP-มือถือ>:8792/tv.apk`) โดยไม่ต้องต่อเน็ตนอก
4. **มีคู่มือวิธีใช้และวิธีเชื่อมต่อทีละขั้นอยู่ในตัวแอป**
5. **คงหน้าจอตอนเล่นจริงแบบต้นฉบับ `PS-Translator` ครบ 100%**
   - กล่องซับไตเติลหลักฟอนต์ **`GTA Art Deco + Prompt SemiBold`** พร้อมเงาหลังตัวหนังสือ เรียงซ้อน 1–3 แถว
   - กล่องคำแนะนำสีเทาฝั่งซ้าย, การ์ดแชทมือถือฝั่งขวา, โหมดแปลเฉพาะจุด, โหมดทีวีจอดำสนิท 100% และปุ่มตั้งค่าขีดสามขีด (`☰`) มุมขวาบน

---

## 🛠️ การบิลด์โปรเจกต์ (Local & GitHub Actions)

เมื่อ Push ขึ้น GitHub Repository ระบบ `.github/workflows/android.yml` จะทำการ:
1. บิลด์แอปฝั่ง Android TV (`:android-tv:assembleDebug`) และแพ็กแอปฝั่ง Samsung Tizen (`packageTizenWgt`)
2. ฝังไฟล์ทั้งคู่เข้าสู่ `app/src/main/assets/tv/` อัตโนมัติ
3. คอมไพล์ `libchiaki` NDK และสร้างไฟล์ **`TIBANSUB.apk`** แบบ All-in-One ขึ้นหน้า GitHub Releases ทันที

## 📜 License
พัฒนาต่อยอดภายใต้สัญญาอนุญาตโอเพนซอร์ส **AGPLv3 / GPLv3**
