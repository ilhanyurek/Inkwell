# Ofis

A clean, fast office app for Android. Built with Kotlin and Jetpack Compose. It does not depend on a
third-party "office" library: the file-format engine lives in its own `core` module.

## Features

| Format | Open | Edit | Save as |
|---|---|---|---|
| **DOCX** | ✓ | Bold, italic, underline, strikethrough, font size, headings, lists, quotes, alignment, find and replace | DOCX, export to PDF, print |
| **TXT / MD** | ✓ | Plain text; headings, lists and bold/italic in Markdown | TXT / MD, export to PDF |
| **XLSX** | ✓ (multiple sheets, shared strings, date formats) | Cell editing, 30+ formulas (SUM, IF, SUMIF… also under their Turkish names such as TOPLA, EĞER, ETOPLA), insert/delete rows and columns, sort, copy/paste, fill down, add/rename sheets | XLSX, CSV, PDF |
| **CSV / TSV** | ✓ (delimiter and encoding detected automatically: `,` `;` tab, UTF-8 / Windows-1254) | Spreadsheet editor | CSV, XLSX, PDF |
| **PDF** | ✓ (including password-protected files) | Highlight, add text, rotate/delete pages, extract pages, merge, text search | PDF |
| **PPTX** | ✓ (text boxes, images, layout placeholders, speaker notes) | — (view only, presentation mode) | — |

Every editor (document, spreadsheet, PDF) has undo / redo buttons in its bottom toolbar.

Also: recent and pinned files, type filters, file search, opening files from "Open with" and "Share",
light/dark theme, Material You colours (Android 12+), Turkish and English interface.

## Download

The latest APK is on the **[Releases](../../releases/latest)** page. Download `Ofis-x.y.z.apk` to your
phone and open it (you may need to allow installs from unknown sources). New versions install over the
old one.

Development builds are produced on every push: **Actions → Android → Artifacts → `ofis-debug-apk`**.

## Publishing a release

Push a tag like `v1.2.3`, or run **Actions → Release → Run workflow** and enter a version number.
Signing requires these repository secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

## Building

```bash
./gradlew :app:assembleDebug        # requires the Android SDK
./gradlew :core:test -PcoreOnly     # core tests, no Android SDK needed
```

## Project layout

```
core/   Pure Kotlin (no Android dependency): XML parser, DOCX/XLSX/PPTX readers and writers,
        CSV, Markdown, formula engine, rich-text editing model, unit tests
app/    Android app: Compose UI, file access (SAF), PDF (PdfRenderer + PdfBox-Android)
```

## Known limitations

- Legacy binary formats (.doc, .xls, .ppt) are not supported.
- Images, tables and equations inside DOCX files are shown as plain text; the app warns before saving such files and offers to save a copy.
- XLSX cell formatting (colours, borders, fonts) and charts are not preserved; for unsupported formulas the last value saved by Excel is shown.
- PPTX files are view-only.
