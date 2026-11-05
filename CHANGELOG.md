# Changelog

All notable changes to Inkwell are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.2.1] - 2026-11-05

### Changed
- The version number is now stored in the source code, so app stores such as F-Droid can build Inkwell from a release tag
- Per-version release notes for app stores (Fastlane changelogs)

## [1.2.0] - 2026-11-05

### Added
- Tools tab with 29 tools that work offline:
  - Organize: merge, split, organize pages (reorder, rotate, duplicate, delete, insert blank), rotate, delete pages
  - Optimize: compress, repair
  - Convert to PDF: images, camera scan with a document filter, Word, Excel/CSV, PowerPoint, text/Markdown
  - Convert from PDF: images (JPG/PNG), Word (text only), PowerPoint, text
  - Edit and secure: sign, watermark, page numbers, password protect (AES-256), unlock, redact, crop, fill form, flatten, properties, extract images, compare two PDFs

## [1.1.0] - 2026-11-04

### Added
- DOCX editing keeps images, tables, links, equations and styles intact
- XLSX cell formatting: bold, italic, colours, fill, borders, alignment, number and date formats
- 20+ new formulas, including VLOOKUP, XLOOKUP, INDEX/MATCH, SUMIFS, COUNTIFS, dates and TEXT
- PDF signatures, form filling and real redaction (blur or black box; hidden content is removed from the file)
- PDF highlights and notes can be removed later
- PPTX: edit slide text and export slides to PDF
- Autosaved drafts with crash recovery; optional autosave straight to the file
- Folders tab to browse, rename and delete files
- Thumbnail grid on the home screen
- 20 more interface languages (22 in total)

### Changed
- Smaller APK
- New package name `com.inkwell.app`. Uninstall any earlier Inkwell build (package `com.office.app`) before installing this one

### Fixed
- Highlights and notes are now drawn in the PDF viewer, not only in other PDF apps

## [1.0.0] - 2026-11-04

### Added
- First release: open and edit DOCX, XLSX, CSV, TXT and Markdown; view, annotate, merge and split PDF; view and present PPTX
- Undo and redo in every editor
- Turkish and English interface; icon that follows the wallpaper palette
