# Mara IPTV Player 2.0

Xtream Codes ve M3U destekli IPTV oynatıcı. Arayüz önce TV box ve kumanda için tasarlandı. Tek proje 3 ayrı APK üretir:

| Sürüm (flavor) | En düşük Android | Oynatıcı motoru | Ek özellikler |
|---|---|---|---|
| **legacy** | 4.2 (API 17) | ExoPlayer 2.19.1 | **Lite:** canlı TV, film/dizi, EPG, web panel, profiller (kayıt ve spor rehberi yok) |
| **lollipop** | 5.0 (API 21) | AndroidX Media3 1.5 + FFmpeg ses | Çoklu ekran, Android TV öneriler / "İzlemeye devam" |
| **nougat** | 7.0 (API 24) | AndroidX Media3 1.5 + FFmpeg ses | Yukarıdakiler + **resim içinde resim (PiP)** |

Üç sürümün paket adı aynı (`com.atlanta.maraplayer`); versionCode her derlemede artar (`5000000 + 2026-01-01'den bu yana geçen dakika`). Bu yüzden aynı imzayla derlenmişlerse yüksek sürüm düşük sürümün üzerine kurulabilir. Sürüm adı `2.0.0-a43 / a51 / a71.YYMMDD-HHmm` biçimindedir; hangi yapının kurulu olduğunu Profiller ekranından görebilirsiniz.

## Lite sürüm (Android 4.2+)

Android 4.2 cihazlar için `legacy` sürümü **lite** olarak derlenir (Profiller ekranında sürüm adı `Android 4.2+ (Lite)` yazar). Amaç, düşük RAM'li eski cihazlarda arka plan yükünü azaltmaktır. Lite sürümde **yok**:

- **Kayıt:** canlı kayıt, REC tuşu, zamanlı/seri kayıt, kayıt listesi, arka plan kayıt servisi ve tek bağlantı relay (`127.0.0.1:8767`). İlgili servis/receiver'lar manifestten çıkarılır, hiç çalışmaz; `RECEIVE_BOOT_COMPLETED` ve `FOREGROUND_SERVICE` izinleri istenmez.
- **Spor rehberi:** maç listesi, takım takibi, yayıncı eşleştirme, maç hatırlatıcı/alarm ve oynatıcıdaki maç notu. İlgili ekranlar ve receiver'lar manifestten çıkarılır.

Lite sürümde **kalanlar:** canlı TV (önizleme, EPG, geri izleme), film/dizi, favoriler, arama, ebeveyn kilidi, profiller, yedekleme/geri yükleme, güncelleme kontrolü ve web panel. EPG'de ileri tarihli programlar "Yakında" olarak görünür; kayıt/hatırlatma seçenekleri çıkmaz.

Android 4.2'nin sistem sertifika deposunda GitHub'ın kök sertifikası bulunmadığı için güncelleme denetimi ve APK indirmesi bu sürümde uygulama içinde yapılır; yalnızca GitHub adreslerinde sertifika doğrulaması atlanır, ek ayar gerekmez.

## Özellikler

- **Hesaplar:** Xtream Codes ve **M3U çalma listesi**, birden çok profil, otomatik bağlanma.
- **Canlı TV:** Kategoriler, önizleme, şimdi/sonra EPG, rakamla kanal seçme, zapping, kanal listesi, otomatik yeniden bağlanma.
  - **OK tuşu:** Kanala basınca tam ekran açılır; listede gezinirken önizleme oynar. Kanal adları ülke/kalite eklerinden (TR:, HD, FHD, 4K…) temizlenerek gösterilir, gerçek ad EPG'de kalır.
  - **Son kanal:** Uygulama kapanıp açıldığında son izlenen kanal seçili gelir ve önizleme o kanaldan başlar.
- **Spor Rehberi (legacy/lite hariç):** ESPN'den yaklaşan futbol maçları. Üstte logolu lig filtresi şeridi, **Takımlarım** sekmesinde takip ettiğin takımlar; maç kartlarında gün ayırıcıları, saat/skor ve canlı işareti. Bir maça basınca takımı takibe alabilir, hatırlatma kurabilir veya yayın bilgisini görebilirsin.
  - **Yayın bilgisi (FootOnSat mantığı):** LiveOnSat günlük listesinden maçın yayıncıları okunur. IPTV buketindeki bir kanalın adı yayıncı adıyla **birebir** (kalite/HD gibi ekler atılarak) aynıysa o kanal önerilir ve tek dokunuşla açılır; tahmin/zorlama yapılmaz. Eşleşme yoksa yalnızca yayıncı adları listelenir.
  - **Hatırlatma:** Maçtan 10 dakika önce veya maç başlarken bildirim kurulabilir. Uygulama kapalıyken ve cihaz yeniden başladıktan sonra da çalışır. Bildirim çubuğu görünmeyen TV kutularında uygulama içi hatırlatma penceresi açılır, "Spor Rehberi" ile maça gidilir.
- **Film / dizi:** Poster ızgarası, detay, sezon/bölüm, kaldığı yerden devam, otomatik sonraki bölüm.
- **EPG:** Xtream EPG'si ve/veya **harici XMLTV** (.xml / .xml.gz). XMLTV'yi Ayarlar'dan ya da web panelden güncelleyebilirsiniz.
- **Geri izleme (catch-up):** Arşivli kanallarda.
- **Kayıt (legacy/lite hariç):**
  - Canlı yayında MENU → Kaydet (veya kumandadaki ● REC tuşu). Süre: program sonuna kadar / 30-180 dk / siz durdurana kadar.
  - **Kayıt sürerken yayın devam eder.** Sağlayıcı tek eşzamanlı bağlantıya izin verse bile ikinci bağlantı açılmaz: kaydın yazdığı akış `127.0.0.1:8767` üzerinden oynatıcıya/önizlemeye verilir (canlı kenardan birkaç saniye geriden). Kayıt bitince oynatıcı kendiliğinden canlı adrese döner.
  - Oynatıcıda MENU → **Kaydı durdur** ile tam ekrandan çıkmadan kayıt bitirilebilir.
  - Yayın akışında ileri tarihli bir programa basınca **zamanlı kayıt** kurulur. Uygulama kapalıyken ve cihaz yeniden başladıktan sonra da çalışır.
  - **Dizi kaydı:** Program bir dizi bölümüyse "Bu bölüm" veya **"Tüm bölümler (EPG'de bulunanlar)"** seçilebilir; aynı gün EPG'de görünen bölümler (en fazla 30) sırayla zamanlanır.
  - **Güvenlik kontrolleri:** Boş alan 300 MB'ın altındaysa ve zamanlanmış/aktif bir kayıtla saat çakışması varsa kayıt öncesi onay istenir.
  - Kayıt bitince bildirim gösterilir; kayıt listesinde dosya boyutu ve **süre** yazar. Süre, kayıtla birlikte yazılan `.meta` dosyasından okunur ve kayıt silinince o da silinir.
  - Kayıtlar USB / SD / dahili hafızaya `.ts` olarak yazılır (Ana sayfa → Kayıtlar).
- **Ebeveyn kilidi:** PIN ile kategori kilitleme; yetişkin kategorileri otomatik kilitlenir.
- **Kumanda tekrar koruması:** Bazı TV kutuları tek basışta iki OK/yon olayı üretir. Aynı tuşun 120 ms içindeki ikinci basışı yutulur; basılı tutarak kaydırma etkilenmez.
- **Yedekleme / geri yükleme:** Ayarlar → Yedekleme. Hesaplar (Xtream/M3U), favoriler, takip edilen takımlar, kanal eşleştirmeleri ve maç hatırlatmaları tek JSON dosyasına yazılır (`Android/data/com.atlanta.maraplayer/files/backup/mara-backup.json`). Aynı dosya web panelden de indirilebilir (`http://CIHAZ_IP:8765/backup.json?t=...`). Geri yüklemede aynı adres+kullanıcıya sahip hesap güncellenir, diğerleri eklenir; favoriler/hatırlatmalar birleştirilir.
- **Güncelleme kontrolü:** Ayarlar → Güncelleme kontrolü, GitHub'daki `atlantatr/MaraIPTV` sürümlerine bakar. Yeni sürüm varsa APK indirilir ve kurulum ekranı açılır; yayınlanmış sürüm yoksa "Henüz yayınlanmış sürüm yok" yazar. Kurulu sürüm aynı satırda görünür.
- **Kategori gizleme:** Web panelden yapılır; gizlenen kategori cihazda hiç görünmez.
- **Sesli arama:** Kumandada mikrofon varsa.
- **EXIT / geri tuşu:** Her ekranda bir üst ekrana döner; ana ekranda uygulamayı arka plana alır. Oynatıcı doğrudan açılmış olsa bile (maç alarmı, maç hatırlatması, TV önerisi) EXIT uygulamadan atmaz; ana ekrana döner. Canlı TV'de önizleme açıksa ilk EXIT önizlemeyi kapatır.
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
apk/MaraIPTV-2.0.0-a43-legacy.apk     -> Android 4.2+
apk/MaraIPTV-2.0.0-a51-lollipop.apk   -> Android 5.0+
apk/MaraIPTV-2.0.0-a71-nougat.apk     -> Android 7.0+
```

(Artefakt zip'inde ayrıca `legacy/`, `lollipop/`, `nougat/` altında debug ve release APK'ları bulunur.)

**İmza:** Release APK'ları `~/.android/debug.keystore` ile imzalanır ve bu yol `app/build.gradle` içinde sabitlenmiştir. Yol sabit olmazsa Gradle, `user.home` farklı bir dizini gösterdiğinde yeni bir anahtar üretir; o zaman yeni APK eskisinin üzerine kurulamaz (`INSTALL_FAILED_INCONSISTENT_CERTIFICATES`).

### Android Studio

1. `gradle-wrapper-olustur.bat` dosyasını çalıştırın (Gradle 8.10.2).
2. Projeyi açın.
3. Sol alttaki **Build Variants** panelinden sürümü seçin (`legacyDebug`, `lollipopDebug`, `nougatDebug`).
4. **Build → Build APK(s)** ile derleyin.

## Sorun giderme

- **Film/dizide ses gelmiyor (AC-3 / E-AC-3 / DTS / TrueHD):** `lollipop` ve `nougat` sürümleri bu sesleri FFmpeg yazılım çözücüsüyle açar. Canlı TV her zaman donanım (MediaCodec) ile çalışır, bu yüzden yayında donma olmaz. AAC/MP3 gibi sesler de donanımda kalır.
- **Android 4.2'de canlı yayın donarak ilerliyordu (düzeltildi):** ExoPlayer, kareler 500 ms'den fazla gecikince "sonraki keyframe'e atla" yapıyor ve bunun için çözücüyü flush ediyor. Android 4.2'de (API 17) flush, çözücünün tamamen kapatılıp yeniden açılması demek (~150 ms + yeni keyframe beklemesi). Bu da "gecik → flush → daha çok gecik" kısır döngüsü kuruyordu: saniyede ~1 codec yeniden kurulumu, saniyede 5-10 kare. `legacy` sürümü artık `MaraVideoRenderer` ile keyframe'e atlamayı kapatır; geciken kareler tek tek düşürülür, çözücü korunur (ölçüm: 1 codec kurulumu, 0 drop, ~25 kare/sn).
- **FFmpeg ses eklentisi derlemede hata verirse:** `gradle.properties` içinde `mara.ffmpeg=false` yapın. Uygulama yine derlenir; sesler donanım çözücüye döner.
- **Kanal açılmıyorsa:** Ayarlar → Canlı yayın formatı'nı TS ↔ HLS olarak değiştirin.
- **https sorunu (Android 4.x - 7.0):** Ayarlar → "Sertifika hatalarını yoksay".
- **Çoklu ekranda bazı pencereler açılmıyorsa:** Cihazın donanım video çözücü sayısı sınırlıdır; 2 pencere düzenini kullanın.
- **Web panele girilemiyorsa:** Cihaz ve telefon aynı ağda olmalı, uygulama açık olmalı. Bazı modemlerde "AP isolation" kapalı olmalı.
