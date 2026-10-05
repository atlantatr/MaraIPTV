# Mara IPTV Player

Xtream Codes API ile çalışan IPTV oynatıcı. **Minimum Android 4.2 (API 17)**, TV box + kumanda öncelikli arayüz.

Paket adı: `com.atlanta.maraplayer` (değiştirmek için `app/build.gradle` → `applicationId`).

## Özellikler

- **Çoklu profil:** Birden fazla Xtream hesabı, son profile otomatik bağlanma. Sunucu alanına M3U linki (`get.php?username=..&password=..`) yapıştırılırsa bilgiler otomatik doldurulur.
- **Ana ekran:** Hesap bitiş tarihi, kalan gün, bağlantı sayısı, saat.
- **Canlı TV:** Kategoriler, kanal logoları, önizleme penceresi, seçili kanalın şimdi/sonra EPG bilgisi, favoriler.
- **Tam ekran canlı yayın:**
  - Yukarı/Aşağı veya CH+/CH- ile kanal değiştirme.
  - Rakam tuşlarıyla kanal numarası girme.
  - OK veya Sol ile kanal listesi.
  - Bilgi çubuğu (logo, şimdi/sonra, ilerleme).
  - Yayın koparsa otomatik yeniden bağlanma.
- **EPG + Catch-up:** Tam yayın akışı; arşivi olan kanallarda geçmiş programları geri izleme (timeshift).
- **Filmler / Diziler:** Poster ızgarası, sıralama (A-Z, en yeni, puan), detay sayfası (konu, oyuncular, puan, fragman), sezon/bölüm listesi.
- **Devam et:** Film ve bölümlerde kaldığı yerden devam, izlenme çubuğu, otomatik sonraki bölüm.
- **Oynatıcı (ExoPlayer 2.19.1):**
  - Ses dili/parça seçimi, altyazı seçimi.
  - Görüntü oranı: sığdır, doldur, yakınlaştır, 16:9, 4:3.
  - Yayın bilgisi (çözünürlük/codec).
  - Harici oynatıcıya (VLC/MX) gönderme.
- **Ebeveyn kilidi:** PIN (varsayılan **0000**). "Adult / XXX / +18" kategoriler otomatik kilitli. Herhangi bir kategori MENU tuşu ile kilitlenebilir. Kilitli içerik aramada da gizlenir.
- **Arama:** Kanal + film + dizi içinde Türkçe karakter duyarsız arama.
- **Android 4.2 uyumu:**
  - Conscrypt ile TLS 1.2/1.3.
  - İsteğe bağlı "sertifika hatalarını yoksay" seçeneği (eski kök sertifikalar için).
  - Düşük RAM için akışlı JSON okuma ve küçültülmüş resimler.

## Kumanda tuşları

| Ekran | Tuş | İşlev |
|---|---|---|
| Listeler | MENU / uzun bas | Favori, kilit, yayın akışı, sıralama |
| Canlı TV (tam ekran) | OK / Sol | Kanal listesi |
| | Yukarı / Aşağı, CH+/- | Kanal değiştir |
| | 0-9 | Kanal numarası |
| | Sağ / INFO | Bilgi çubuğu |
| | GUIDE | Yayın akışı |
| | MENU | Ses, altyazı, oran, favori, yeniden bağlan... |
| Film / Dizi | OK | Duraklat / Oynat |
| | Sol / Sağ | 10 sn geri/ileri (basılı tutunca 30-60 sn) |
| | CH+/- , Next/Prev | Sonraki / önceki bölüm |

## APK nasıl derlenir (Windows)

1. **Android Studio** kurun (Koala / Ladybug veya daha yeni; içinde JDK 17 gelir).
2. Bu klasördeki `gradle-wrapper-olustur.bat` dosyasını çift tıklayıp çalıştırın. Wrapper ayar dosyasını oluşturur.
3. Android Studio → **File → Open** → `MaraIPTV` klasörünü seçin.
   - İlk açılışta Gradle 8.7 ve bağımlılıklar internetten iner.
   - "Gradle wrapper bulunamadı" gibi bir soru gelirse **OK / Use Gradle wrapper** deyin.
4. SDK eksikse Studio **Android SDK Platform 34** kurmayı önerir; kabul edin.
5. Menüden **Build → Build App Bundle(s) / APK(s) → Build APK(s)** seçin.
   - Çıktı: `app/build/outputs/apk/debug/app-debug.apk`
6. Daha küçük, optimize edilmiş sürüm için: **Build → Generate Signed App Bundle / APK** ya da Gradle panelinden `assembleRelease` çalıştırın.
   - Release, kolaylık olsun diye debug anahtarıyla imzalanır.
   - Çıktı: `app/build/outputs/apk/release/app-release.apk`

Kutuya yükleme:

```
adb connect 192.168.1.120
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Notlar / sorun giderme

- **Kanal açılmıyorsa:** Ayarlar → Canlı yayın formatı'nı **TS ↔ HLS** olarak değiştirin. Ayrıca **Tampon**'u "Yüksek" yapın.
- **https sunucuya bağlanamıyorsa (Android 4.x):** Ayarlar → **Sertifika hatalarını yoksay** seçeneğini açın.
- **Ses yok (AC3/EAC3/DTS):** Eski cihazların donanım çözücüsü bu formatları desteklemeyebilir. Oynatıcıda MENU → **Harici oynatıcıda aç** (VLC) seçin ya da Ayarlar → Oynatıcı motoru → Harici seçin.
- **Sunucu belirli bir User-Agent istiyorsa:** Ayarlar → **User-Agent** alanına girin.
- **EPG saatleri kaymışsa:** Ayarlar → **EPG saat düzeltmesi**.
- **Release derlemesinde R8 "Missing class" hatası:** `app/build/outputs/mapping/release/missing_rules.txt` içindeki satırları `app/proguard-rules.pro` sonuna ekleyin. Debug derlemesi etkilenmez.
