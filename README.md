# Mara IPTV Player 2.0

Xtream Codes ve M3U destekli IPTV oynatıcı. Arayüz önce TV box ve kumanda için tasarlandı. Tek proje 3 ayrı APK üretir:

| Sürüm (flavor) | En düşük Android | Oynatıcı motoru | Ek özellikler |
|---|---|---|---|
| **legacy** | 4.2 (API 17) | ExoPlayer 2.19.1 | Ortak özellikler |
| **lollipop** | 5.0 (API 21) | AndroidX Media3 1.5 + FFmpeg ses | Çoklu ekran, Android TV öneriler / "İzlemeye devam" |
| **nougat** | 7.0 (API 24) | AndroidX Media3 1.5 + FFmpeg ses | Yukarıdakiler + **resim içinde resim (PiP)** |

Üç sürümün paket adı aynı (`com.atlanta.maraplayer`); versionCode'lar 1200 / 2200 / 3200. Bu yüzden aynı imzayla derlenmişlerse yüksek sürüm düşük sürümün üzerine kurulabilir.

## Tüm sürümlerde ortak özellikler

- **Hesaplar:** Xtream Codes ve **M3U çalma listesi**, birden çok profil, otomatik bağlanma.
- **Canlı TV:** Kategoriler, önizleme, şimdi/sonra EPG, rakamla kanal seçme, zapping, kanal listesi, otomatik yeniden bağlanma.
- **Film / dizi:** Poster ızgarası, detay, sezon/bölüm, kaldığı yerden devam, otomatik sonraki bölüm.
- **EPG:** Xtream EPG'si ve/veya **harici XMLTV** (.xml / .xml.gz). XMLTV'yi Ayarlar'dan ya da web panelden güncelleyebilirsiniz.
- **Geri izleme (catch-up):** Arşivli kanallarda.
- **Kayıt:**
  - Canlı yayında MENU → Kaydet (veya kumandadaki ● REC tuşu). Süre: program sonuna kadar / 30-180 dk / siz durdurana kadar.
  - Yayın akışında ileri tarihli bir programa basınca **zamanlı kayıt** kurulur. Uygulama kapalıyken ve cihaz yeniden başladıktan sonra da çalışır.
  - Kayıtlar USB / SD / dahili hafızaya `.ts` olarak yazılır (Ana sayfa → Kayıtlar).
- **Ebeveyn kilidi:** PIN ile kategori kilitleme; yetişkin kategorileri otomatik kilitlenir.
- **Kategori gizleme:** Web panelden yapılır; gizlenen kategori cihazda hiç görünmez.
- **Sesli arama:** Kumandada mikrofon varsa.
- **Web panel (port 8765):** Aşağıdaki bölüme bakın.

## Web panel

Aynı ağdaki telefon veya bilgisayarın tarayıcısından girilir. Cihazın adresi Profiller ve Ana sayfa ekranında yazar:

```
http://CIHAZ_IP:8765
```

Giriş **ebeveyn PIN'i** ile yapılır (varsayılan 0000; lütfen değiştirin). 5 hatalı denemede 1 dakika kilitlenir. Panelden yapılabilenler:

- **Hesaplar:** Ekleme, düzenleme, silme, bağlantı testi. Xtream veya M3U; XMLTV adresi; EPG güncelleme.
- **Ebeveyn kontrolü:** Kilidi açma/kapama, yetişkin otomatik kilidi, PIN değiştirme, kilitleri sıfırlama.
- **Kategoriler:** Her hesap için Canlı / Film / Dizi kategorilerini tek tek veya toplu **gizleme** ve **kilitleme**. Hangi kategorinin gizli veya kilitli olduğu listede görünür.

Paneli Ayarlar → "Web panel"den kapatabilirsiniz.

## Logo seti

`branding/` klasörüne logo sayfasını **marka-seti.png** adıyla koyun. GitHub derlemesi `tools/brand.py` ile bu sayfadan şunları otomatik keser:

- uygulama ikonu
- Android TV banner
- açılış görseli
- yazı logosu
- web panel logosu

Bilgisayarda derleyecekseniz bir kez şunları çalıştırın:

```
pip install pillow
python tools\brand.py
```

## Derleme

### GitHub Actions

Her push'ta **Actions → APK Derle** çalışır. Bitince **Artifacts → MaraIPTV-apk** zip'inde şu dosyalar olur:

```
apk/legacy/debug/app-legacy-debug.apk       -> Android 4.2+
apk/lollipop/debug/app-lollipop-debug.apk   -> Android 5.0+
apk/nougat/debug/app-nougat-debug.apk       -> Android 7.0+
(+ release/ klasorlerinde kucultulmus surumler)
```

### Android Studio

1. `gradle-wrapper-olustur.bat` dosyasını çalıştırın (Gradle 8.10.2).
2. Projeyi açın.
3. Sol alttaki **Build Variants** panelinden sürümü seçin (`legacyDebug`, `lollipopDebug`, `nougatDebug`).
4. **Build → Build APK(s)** ile derleyin.

## Sorun giderme

- **FFmpeg ses eklentisi derlemede hata verirse:** `gradle.properties` içinde `mara.ffmpeg=false` yapın.
- **Kanal açılmıyorsa:** Ayarlar → Canlı yayın formatı'nı TS ↔ HLS olarak değiştirin.
- **https sorunu (Android 4.x - 7.0):** Ayarlar → "Sertifika hatalarını yoksay".
- **Çoklu ekranda bazı pencereler açılmıyorsa:** Cihazın donanım video çözücü sayısı sınırlıdır; 2 pencere düzenini kullanın.
- **Web panele girilemiyorsa:** Cihaz ve telefon aynı ağda olmalı, uygulama açık olmalı. Bazı modemlerde "AP isolation" kapalı olmalı.
