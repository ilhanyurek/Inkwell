# Ofis

Android için sade, hızlı bir ofis uygulaması. Kotlin + Jetpack Compose ile yazıldı; belge biçimleri için
harici bir “office” kütüphanesi kullanmaz, format motoru kendi `core` modülündedir.

## Neler yapabilir?

| Biçim | Aç | Düzenle | Kaydet |
|---|---|---|---|
| **DOCX** | ✓ | Kalın, italik, altı/üstü çizili, yazı boyutu, başlıklar, listeler, alıntı, hizalama, bul-değiştir, geri al/yinele | DOCX, PDF'e aktar, yazdır |
| **TXT / MD** | ✓ | Metin; Markdown'da başlık, liste, kalın/italik | TXT / MD, PDF'e aktar |
| **XLSX** | ✓ (çok sayfalı, paylaşılan metinler, tarih biçimleri) | Hücre düzenleme, 30+ formül (TOPLA/SUM, EĞER/IF, ETOPLA/SUMIF…), satır/sütun ekle-sil, sıralama, kopyala/yapıştır, aşağı doldur, sayfa ekle/yeniden adlandır | XLSX, CSV, PDF |
| **CSV / TSV** | ✓ (ayraç ve kodlama otomatik algılanır: `,` `;` sekme, UTF-8 / Windows-1254) | Tablo düzenleyici | CSV, XLSX, PDF |
| **PDF** | ✓ (parola korumalı dahil) | Vurgulama, metin ekleme, sayfa döndürme/silme, sayfa ayırma, birleştirme, metin arama | PDF |
| **PPTX** | ✓ (metin kutuları, görseller, düzen yer tutucuları, notlar) | — (yalnız görüntüleme, sunum modu) | — |

Diğer: son dosyalar ve sabitleme, tür filtresi, dosya arama, “Birlikte aç” ve “Paylaş” ile gelen dosyaları açma,
açık/koyu tema, Material You renkleri (Android 12+), Türkçe/İngilizce arayüz.

## APK'yı indirme

Her push'ta GitHub Actions uygulamayı derler. **Actions → Android → son çalıştırma → Artifacts →
`ofis-debug-apk`** dosyasını indirip telefonunuza kurabilirsiniz (bilinmeyen kaynaklardan yüklemeye izin vermeniz gerekir).

## Derleme

```bash
./gradlew :app:assembleDebug        # Android SDK gerekir
./gradlew :core:test -PcoreOnly     # Android SDK olmadan çekirdek testleri
```

## Proje yapısı

```
core/   Saf Kotlin (Android bağımlılığı yok): XML ayrıştırıcı, DOCX/XLSX/PPTX okuyucu-yazıcı,
        CSV, Markdown, formül motoru, zengin metin düzenleme modeli + birim testleri
app/    Android uygulaması: Compose arayüzü, dosya erişimi (SAF), PDF (PdfRenderer + PdfBox-Android)
```

## Bilinen sınırlar

- Eski ikili biçimler (.doc, .xls, .ppt) desteklenmez.
- DOCX içindeki görsel, tablo ve denklemler düz metin olarak gösterilir; bu dosyalar kaydedilirken uyarı verilir ve kopya kaydetme önerilir.
- XLSX hücre biçimleri (renk, kenarlık, yazı tipi) ve grafikler korunmaz; desteklenmeyen formüllerde Excel'in kaydettiği son değer gösterilir.
- PPTX yalnızca görüntülenir.
