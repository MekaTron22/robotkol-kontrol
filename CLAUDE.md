# CLAUDE.md – Robot Kollu Rover

Kullanıcıyla Türkçe konuş. Tüm bağlam: `docs/PROJE_OZETI.md` (önce bunu oku), pinler: `docs/PLAN.md`.

## Proje
- Android (Kotlin, `android/`) → HC-05 → Master Uno (`firmware/rover_master`) → I2C 0x08 → Slave Uno (`firmware/rover_slave`, 7 servo).
- Omuz: Arm A1 + Arm A2 zıt çalışır (A2 = 180 − A1 + `SHOULDER_A2_TRIM`).
- Kol arayüzü İLERİ kinematik: her segment bir öncekine göre döner; ters kinematik istenmiyor.
- Dirsek montajı: servo 90 = ön kol üst kola ≈90° sağa; 180 = paralel. Sabit: `ArmKinematics.ELBOW_MOUNT_DEG` (kullanıcı gerçek değeri verebilir).
- Protokol `<GÖVDE*CS>` (XOR sağlama). Değiştirirsen hem `core/Protocol.kt` hem master'ı güncelle.
- Eklem sınırları/ev pozu iki yerde: slave `JOINT_MIN/MAX/HOME` ve `android/.../core/Joints.kt` – birlikte değiştir.
- Referans duruşlar: `android/.../core/PoseLibrary.kt` (sahada doğrulanacak).

## Derleme / test
- Araçlar ortam kurulum betiğiyle gelir: `/opt/arduino/arduino-cli`, `/opt/android-sdk`.
- `arduino-cli compile -b arduino:avr:uno firmware/rover_slave` (ve rover_master)
- `cd android && ./gradlew testDebugUnitTest assembleRelease`
- Ekran görüntüleri: `ScreenshotTest` → `android/app/build/screenshots/`
- Yeni APK'yı `release/` altına kopyala, README/PROJE_OZETI'ndeki SHA-256'yı güncelle.
- Bağlantı şeması: `python3 tools/baglanti_semasi_uret.py docs/baglanti_semasi.svg`

## Durum
Kod ve APK hazır; gerçek donanım/telefon testi yapılmadı. Sıradaki iş: `docs/PROJE_OZETI.md` §14 saha testleri ve duruş açılarının kalibrasyonu.
