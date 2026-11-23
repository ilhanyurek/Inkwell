# Changelog

All notable changes to Inkwell are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.5.0] - 2026-11-23

### Changed
- New look across the app: a deep navy accent on a light grey page, white rounded cards, large bold titles, pill-shaped switches between Recent, Folders and Tools, and the Figtree typeface
- Editor tool bars float as rounded cards above the bottom edge
- Theme is picked with one control: System, Light, Dark or Black

### Added
- Black (AMOLED) theme with a true black page and outlined cards
- Several files can be open at once. Back goes Home and leaves them open; an "Open files" card on Home leads back
- The number of open files sits in each editor's top bar and opens a list to switch, open another or close files
- Closing a file with unsaved changes asks whether to save, discard or keep it open
- Open files come back after the app is restarted

- Edit the text of a PDF on the page: tap a paragraph, type, and its lines rewrap in place. The old text is taken out of the file (not covered), the new text uses the paragraph's size, colour and alignment and the PDF's own font when it has the letters
- "Keep several files open" can be turned off in Settings; then one file is open at a time and Back closes it

### Fixed
- Pictures saved as TIFF are shown, for example university logos in the header of a form

## [1.4.1] - 2026-11-21

### Added
- Documents are shown on a white sheet with their own colours, also in the dark theme (can be turned off in Settings)
- Tables look like the original: column widths, merged cells, borders, cell colours and formatted, aligned text, with rows as tall as their content
- The header and footer of the first page (for example a title block with logos) are shown above and below the text
- Page breaks are shown as a dashed line

### Fixed
- Text inside content controls (common in forms) is shown and can be edited; the controls are kept when saving
- Text boxes show their text instead of an empty frame

## [1.4.0] - 2026-11-13

### Added
- Word documents look like the original: fonts (closest available family), sizes, text colours, highlight, superscript and subscript, style-based headings, indents, line and paragraph spacing
- Lists show their real numbers and levels (1.1, a), i. and custom bullets)

### Fixed
- Saving no longer replaces a document's own heading styles with built-in ones
- Text that is explicitly not bold or italic inside a bold or italic style stays that way after saving
- Double, dotted and coloured underlines are kept when saving

## [1.3.2] - 2026-11-10

### Fixed
- Documents: the cursor and selection handles now appear where you tap, so text can be edited and deleted anywhere in a paragraph
- Lines of a wrapped title or heading no longer overlap

## [1.3.1] - 2026-11-09

### Added
- Open and edit old Word (.doc) and Excel (.xls) files. Saving creates a .docx or .xlsx copy and leaves the original file untouched; .xls formulas are kept as their values

## [1.3.0] - 2026-11-07

### Added
- Documents: tables and pictures appear in place; tap a table to edit cells or add and remove rows and columns; insert new tables and pictures
- PDF: long-press to select and copy text; Contents menu jumps to the document outline
- Spreadsheets: view charts from the file, freeze panes, filter rows by column values
- Presentations: add, duplicate, move and delete slides; create a new presentation
- OpenDocument: open and save .odt and .ods, view .odp
- Launcher shortcuts: new document, new spreadsheet, scan, tools and the last opened file

## [1.2.1] - 2026-11-06

### Changed
- New English store screenshots with sample documents
- Simpler wording in the store descriptions

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
- PDF signatures, form filling and redaction (blur or black box; Inkwell deletes the hidden content from the file)
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
