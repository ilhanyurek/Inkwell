# Inkwell

Inkwell is an Android app that opens and edits DOCX, XLSX, CSV, PDF, PPTX and OpenDocument files. It is written in Kotlin
with Jetpack Compose, and its own `core` module reads and writes the file formats without a third-party office library.

## Features

| Format | Open | Edit | Save as |
|---|---|---|---|
| **DOCX** | ✓ Shows tables and pictures in place; keeps links, equations, headers and styles | Bold, italic, underline, strikethrough, font size, headings, lists, quotes, alignment, find and replace; edit tables, insert tables and pictures | DOCX (only the text you changed is rewritten), PDF, print |
| **TXT / MD** | ✓ | Plain text; headings, lists and bold/italic in Markdown | TXT / MD, PDF |
| **XLSX** | ✓ Multiple sheets, styles, number and date formats, charts; keeps merged cells and conditional formatting | Cell editing; bold/italic/colours/fill/borders/alignment/number formats; 50+ formulas incl. VLOOKUP, XLOOKUP, INDEX/MATCH, SUMIFS, dates and TEXT (also under Turkish names such as DÜŞEYARA, ETOPLA, BUGÜN); rows/columns, sort, fill down, sheets, freeze panes, filter | XLSX, CSV, PDF |
| **CSV / TSV** | ✓ Detects the delimiter and encoding | Spreadsheet editor; typed dates, percentages and amounts become numbers | CSV, XLSX, PDF |
| **PDF** | ✓ Including password-protected files | Highlights and notes you can delete later, signatures, form filling, redaction (blur or black box; Inkwell deletes the hidden content from the file), rotate/delete/extract pages, merge, search, select and copy text, table of contents | PDF |
| **PPTX** | ✓ Text boxes, images, layouts, speaker notes, presentation mode | Slide text; add, duplicate, move and delete slides; new presentations | PPTX, PDF |
| **ODT / ODS / ODP** | ✓ | Same editors as DOCX and XLSX; ODP is view only | ODT, ODS, PDF |

- **Tools tab** with 29 offline tools: merge, split, organize, rotate and delete pages; compress and repair;
  images, camera scans, Word, Excel, PowerPoint and text to PDF; PDF to images, Word, PowerPoint and text;
  sign, watermark, page numbers, password protect/unlock, redact, crop, fill form, flatten, properties,
  extract images and compare.
- Launcher shortcuts for a new document, a new spreadsheet, scanning, the tools and the last opened file.
- Undo / redo in every editor.
- Drafts: Inkwell saves your changes as a draft a few seconds after you type and offers them back after a crash. You can also turn on saving straight to the file.
- Home: recent and pinned files, list or thumbnail grid, search, type filters, recovered drafts, and a **Folders** tab to browse, rename and delete files.
- 22 interface languages: Turkish, English, Chinese, Hindi, Spanish, French, Arabic, Bengali, Portuguese, Russian, Urdu, Indonesian, German, Japanese, Marathi, Telugu, Tamil, Vietnamese, Filipino, Korean, Persian and Swahili.
- Light/dark theme, Material You colours; the app icon follows the wallpaper palette and themed icons.

## Download

The latest APK is on the **[Releases](../../releases/latest)** page. Download `Inkwell-x.y.z.apk` to your
phone and open it (you may need to allow installs from unknown sources). New versions install over the
old one.

**Automatic updates with Obtainium:** install [Obtainium](https://github.com/ImranR98/Obtainium/releases), then open
this link on your phone. Obtainium adds Inkwell, installs it from the latest release and notifies you about new versions.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.inkwell.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Filhanyurek%2FInkwell%22%2C%22author%22%3A%22ilhanyurek%22%2C%22name%22%3A%22Inkwell%22%7D"><img src="docs/badge_obtainium.png" alt="Get it on Obtainium" height="48"></a>

**Zapstore:** install the [Zapstore](https://zapstore.dev) app and search for *Inkwell*. Every GitHub release
reaches Zapstore automatically, signed by the developer's key (owner steps in [docs/ZAPSTORE.md](docs/ZAPSTORE.md)).

Development builds are produced on every push: **Actions → Android → Artifacts → `inkwell-debug-apk`**.

## Publishing a release

Push a tag like `v1.2.3`, or run **Actions → Release → Run workflow** and enter a version number.
Signing requires these repository secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

Release notes live in [CHANGELOG.md](CHANGELOG.md); the release workflow copies the matching section
into the GitHub release.

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

## Testing

- `core` unit tests, including files produced by python-docx, openpyxl, python-pptx and reportlab (`scripts/make_fixtures.py`).
- Files saved by Inkwell are re-opened with those libraries in CI (`scripts/validate_outputs.py`).
- Compose UI tests run on an Android emulator in CI, plus a smoke test of the minified release APK.

## Known limitations

- Legacy binary formats (.doc, .xls, .ppt) are not supported.
- DOCX tables, images and equations are kept but not editable in the app (they appear as labelled chips).
- XLSX charts are kept but not shown; formulas the app does not know show the value Excel last saved.
- PPTX: only text can be edited; shapes and images are kept as they are.

## License

Inkwell is free software under the [GNU General Public License v3.0 or later](LICENSE).
It uses PdfBox-Android (Apache License 2.0) and AndroidX libraries.
