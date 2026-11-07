package com.office.app.ui.sheet

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.office.app.AppContainer
import com.office.app.data.Draft
import com.office.app.data.DraftAutosaver
import com.office.app.data.DraftStore
import com.office.app.data.FileInfo
import com.office.app.export.PdfExporter
import com.office.core.FileKind
import com.office.core.csv.Csv
import com.office.core.formula.FormulaEngine
import com.office.core.sheet.CellRef
import com.office.core.sheet.CellStyle
import com.office.core.sheet.ChartRef
import com.office.core.sheet.ChartSpec
import com.office.core.sheet.NumberFormat
import com.office.core.zip.ZipPackage
import com.office.core.sheet.Sheet
import com.office.core.sheet.Value
import com.office.core.sheet.Workbook
import com.office.core.xlsx.XlsxReader
import com.office.core.xlsx.XlsxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

data class Selection(val anchor: CellRef, val focus: CellRef) {
    val top get() = minOf(anchor.row, focus.row)
    val bottom get() = maxOf(anchor.row, focus.row)
    val left get() = minOf(anchor.col, focus.col)
    val right get() = maxOf(anchor.col, focus.col)
    val isSingle get() = anchor == focus
    operator fun contains(ref: CellRef) = ref.row in top..bottom && ref.col in left..right
    fun label() = if (isSingle) focus.toString() else "${CellRef(top, left)}:${CellRef(bottom, right)}"

    companion object {
        fun single(ref: CellRef) = Selection(ref, ref)
    }
}

class SheetEditorViewModel(
    private val container: AppContainer,
    initialUri: Uri?,
    info: FileInfo?,
    val kind: FileKind,
    draftKey: String? = null,
) : ViewModel() {

    sealed interface LoadState {
        data object Loading : LoadState
        data object Ready : LoadState
        data class Failed(val message: String?) : LoadState
    }

    var loadState by mutableStateOf<LoadState>(if (initialUri == null) LoadState.Ready else LoadState.Loading)
        private set
    var uri by mutableStateOf(initialUri)
        private set
    var fileName by mutableStateOf(info?.name)
        private set

    private var workbook = Workbook.empty()
    private var engine = FormulaEngine(workbook)
    private var csvDelimiter = ','

    /** Bumped on every mutation so the grid recomposes (the workbook itself is mutable). */
    var revision by mutableIntStateOf(0)
        private set
    var activeSheetIndex by mutableIntStateOf(0)
        private set
    var selection by mutableStateOf(Selection.single(CellRef(0, 0)))
        private set
    var editText by mutableStateOf(TextFieldValue(""))
        private set
    var editing by mutableStateOf(false)
        private set
    var dirty by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<Pair<List<Sheet>, Int>>()
    private val redoStack = ArrayDeque<Pair<List<Sheet>, Int>>()

    val sheets: List<Sheet> get() = workbook.sheets
    val sheet: Sheet get() = workbook.sheets[activeSheetIndex.coerceIn(0, workbook.sheets.lastIndex)]
    val isCsv get() = kind == FileKind.CSV

    val autosaver = DraftAutosaver(
        scope = container.appScope,
        store = container.drafts,
        settings = container.settings,
        key = draftKey ?: initialUri?.let(DraftStore::keyFor) ?: DraftStore.newKey(),
        describe = { Triple(uri, fileName ?: "Untitled.${kind.primaryExtension}", kind) },
        writeDraft = { f -> f.writeBytes(withContext(Dispatchers.Default) { serialize(kind) }) },
        saveToFile = {
            val target = uri
            if (target == null) false
            else {
                container.files.writeBytes(target, withContext(Dispatchers.Default) { serialize(kind) })
                dirty = false
                true
            }
        },
    )

    var recoverableDraft by mutableStateOf<Draft?>(null)
        private set

    init {
        when {
            initialUri != null -> load(initialUri)
            draftKey != null && container.drafts.get(draftKey) != null -> restore(container.drafts.get(draftKey)!!)
            else -> loadEditTextForSelection()
        }
    }

    private fun parse(bytes: ByteArray): Workbook =
        if (kind == FileKind.CSV) {
            val parsed = Csv.parse(bytes)
            csvDelimiter = parsed.delimiter
            Csv.toWorkbook(parsed, fileName?.substringBeforeLast('.')?.take(31) ?: "Sheet1")
        } else {
            ZipPackage.read(ByteArrayInputStream(bytes)).also { originalPackage = it }.let { XlsxReader.read(it) }
        }

    private fun apply(wb: Workbook) {
        workbook = wb
        engine = FormulaEngine(wb)
        activeSheetIndex = 0
        loadEditTextForSelection()
        revision++
    }

    private fun load(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val bytes = container.files.readBytes(uri)
                withContext(Dispatchers.Default) { parse(bytes) }
            }.onSuccess { wb ->
                apply(wb)
                recoverableDraft = container.drafts.get(autosaver.key)
                loadState = LoadState.Ready
            }.onFailure { loadState = LoadState.Failed(it.message) }
        }
    }

    fun restoreDraft() {
        recoverableDraft?.let { restore(it) }
    }

    private fun restore(draft: Draft) {
        viewModelScope.launch {
            loadState = LoadState.Loading
            runCatching { withContext(Dispatchers.IO) { parse(draft.file.readBytes()) } }.onSuccess { wb ->
                apply(wb)
                undoStack.clear(); redoStack.clear(); canUndo = false; canRedo = false
                dirty = true
            }
            recoverableDraft = null
            loadState = LoadState.Ready
        }
    }

    fun discardDraft() {
        recoverableDraft = null
        autosaver.discard()
    }

    fun flushDraft() {
        container.appScope.launch { autosaver.flush() }
    }

    private fun markDirty() {
        dirty = true
        autosaver.changed()
    }

    override fun onCleared() {
        flushDraft()
    }

    // --- reading ------------------------------------------------------------------------

    fun display(ref: CellRef): Value = engine.value(sheet, ref)

    /** The text shown in a cell: numbers go through the cell's number format. */
    fun displayText(ref: CellRef, value: Value = display(ref)): String {
        val fmt = sheet.styles[ref]?.numFmt
        return if (value is Value.Num && fmt != null) NumberFormat.format(value.v, fmt) else value.display()
    }

    /** What the formula bar shows: dates and percentages as typed rather than as raw numbers. */
    private fun editableRaw(ref: CellRef): String {
        val raw = sheet[ref]
        val fmt = sheet.styles[ref]?.numFmt ?: return raw
        val v = raw.toDoubleOrNull() ?: return raw
        return when {
            NumberFormat.isDate(fmt) && v == Math.floor(v) -> NumberFormat.format(v, "dd.mm.yyyy")
            NumberFormat.isDate(fmt) && v < 1 -> NumberFormat.format(v, "hh:mm")
            fmt.contains('%') -> Value.formatNumber(v * 100) + "%"
            else -> raw
        }
    }

    /** The package an XLSX was read from; saving writes into it so charts and other parts survive. */
    private var originalPackage: ZipPackage? = null

    /** Style shared by the selection's focused cell, for the format panel. */
    val focusStyle: CellStyle get() = sheet.style(selection.focus)

    /** Applies [transform] to the style of every cell in the selection. */
    fun applyStyle(transform: (CellStyle) -> CellStyle) {
        val s = selection
        val lastRow = if (s.bottom - s.top > 2000) minOf(s.bottom, maxOf(sheet.usedMaxRow, s.top)) else s.bottom
        val lastCol = if (s.right - s.left > 200) minOf(s.right, maxOf(sheet.usedMaxCol, s.left)) else s.right
        mutate {
            for (r in s.top..lastRow) for (c in s.left..lastCol) {
                val ref = CellRef(r, c)
                sheet.setStyle(ref, transform(sheet.style(ref)).edited())
            }
        }
    }

    fun raw(ref: CellRef): String = sheet[ref]

    fun columnWidthChars(col: Int): Float = sheet.columnWidths[col] ?: DEFAULT_WIDTH

    data class Stats(val sum: Double, val count: Int, val numeric: Int) {
        val average get() = if (numeric == 0) 0.0 else sum / numeric
    }

    fun selectionStats(): Stats? {
        val s = selection
        if (s.isSingle) return null
        var sum = 0.0
        var count = 0
        var numeric = 0
        val maxRow = minOf(s.bottom, sheet.maxRow)
        val maxCol = minOf(s.right, sheet.maxCol)
        for (r in s.top..maxRow) for (c in s.left..maxCol) {
            when (val v = display(CellRef(r, c))) {
                is Value.Num -> { sum += v.v; numeric++; count++ }
                Value.Empty -> {}
                else -> count++
            }
        }
        return if (count == 0) null else Stats(sum, count, numeric)
    }

    // --- selection & editing ---------------------------------------------------------------

    fun tapCell(ref: CellRef) {
        if (editing && editText.text.startsWith("=") && ref != selection.focus) {
            insertReference(ref.toString())
            return
        }
        if (editing) commitEdit(move = false)
        selection = Selection.single(ref)
        loadEditTextForSelection()
    }

    fun extendSelection(ref: CellRef) {
        if (editing) commitEdit(move = false)
        selection = selection.copy(focus = ref)
        loadEditTextForSelection()
    }

    fun selectColumn(col: Int) {
        if (editing) commitEdit(move = false)
        selection = Selection(CellRef(0, col), CellRef(maxOf(sheet.maxRow, 0), col))
        loadEditTextForSelection()
    }

    fun selectRow(row: Int) {
        if (editing) commitEdit(move = false)
        selection = Selection(CellRef(row, 0), CellRef(row, maxOf(sheet.maxCol, 0)))
        loadEditTextForSelection()
    }

    private fun insertReference(ref: String) {
        val t = editText
        val start = t.selection.min
        val end = t.selection.max
        val newText = t.text.substring(0, start) + ref + t.text.substring(end)
        editText = TextFieldValue(newText, TextRange(start + ref.length))
    }

    fun insertFunction(name: String) {
        val t = editText
        val prefix = if (t.text.isEmpty()) "=" else ""
        val insert = "$prefix$name()"
        val start = t.selection.min
        val newText = t.text.substring(0, start) + insert + t.text.substring(t.selection.max)
        editText = TextFieldValue(newText, TextRange(start + insert.length - 1))
        editing = true
    }

    private fun loadEditTextForSelection() {
        val raw = editableRaw(selection.focus)
        editText = TextFieldValue(raw, TextRange(raw.length))
    }

    fun onEditChange(value: TextFieldValue) {
        editText = value
        editing = true
    }

    fun beginEditing() {
        editing = true
    }

    fun cancelEdit() {
        editing = false
        loadEditTextForSelection()
    }

    /** Writes the formula bar into the focused cell; optionally moves down like Enter in Excel. */
    fun commitEdit(move: Boolean = true) {
        val ref = selection.focus
        val newRaw = editText.text
        if (newRaw != editableRaw(ref)) {
            val text = newRaw.trimEnd('\n')
            // Typed dates, percentages and amounts become numbers with a matching format.
            val parsed = if (text.startsWith("=") || text.startsWith("'")) null else NumberFormat.parseInput(text)
            mutate {
                if (parsed != null) {
                    sheet[ref] = Value.formatNumber(parsed.first)
                    if (sheet.style(ref).numFmt == null) sheet.setStyle(ref, sheet.style(ref).copy(numFmt = parsed.second).edited())
                } else {
                    sheet[ref] = text
                }
            }
        }
        editing = false
        if (move) {
            selection = Selection.single(CellRef(ref.row + 1, ref.col))
        }
        loadEditTextForSelection()
    }

    // --- mutations -----------------------------------------------------------------------------

    private inline fun mutate(block: () -> Unit) {
        undoStack.addLast(workbook.sheets.map { it.copy() } to activeSheetIndex)
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        block()
        engine.invalidate()
        markDirty()
        canUndo = true
        canRedo = false
        revision++
    }

    private fun restore(state: Pair<List<Sheet>, Int>) {
        workbook.sheets.clear()
        workbook.sheets.addAll(state.first)
        activeSheetIndex = state.second.coerceIn(0, workbook.sheets.lastIndex)
        engine = FormulaEngine(workbook)
        markDirty()
        revision++
        loadEditTextForSelection()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(workbook.sheets.map { it.copy() } to activeSheetIndex)
        restore(s)
        canUndo = undoStack.isNotEmpty()
        canRedo = true
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(workbook.sheets.map { it.copy() } to activeSheetIndex)
        restore(s)
        canUndo = true
        canRedo = redoStack.isNotEmpty()
    }

    fun clearSelection() = mutate {
        val s = selection
        sheet.cells.keys.filter { it in s }.forEach { sheet[it] = "" }
    }

    fun insertRow(above: Boolean) = mutate { sheet.insertRow(if (above) selection.top else selection.bottom + 1) }

    fun deleteRows() = mutate {
        val s = selection
        repeat(s.bottom - s.top + 1) { sheet.deleteRow(s.top) }
    }

    fun insertColumn(left: Boolean) = mutate { sheet.insertColumn(if (left) selection.left else selection.right + 1) }

    fun deleteColumns() = mutate {
        val s = selection
        repeat(s.right - s.left + 1) { sheet.deleteColumn(s.left) }
    }

    /** Sorts by the focused column. When [hasHeader] the first row stays in place. */
    fun sort(ascending: Boolean, hasHeader: Boolean) = mutate {
        sheet.sortRows(selection.focus.col, ascending, if (hasHeader) 1 else 0, engine)
    }

    fun adjustColumnWidth(delta: Float) = mutate {
        val s = selection
        for (c in s.left..s.right) {
            sheet.columnWidths[c] = (columnWidthChars(c) + delta).coerceIn(3f, 60f)
        }
    }

    /** Copies the selection as tab-separated text (what spreadsheets paste natively). */
    fun copyText(asValues: Boolean = false): String {
        val s = selection
        return (s.top..s.bottom).joinToString("\n") { r ->
            (s.left..s.right).joinToString("\t") { c ->
                val ref = CellRef(r, c)
                if (asValues) displayText(ref) else sheet[ref]
            }
        }
    }

    fun cut(): String {
        val text = copyText()
        clearSelection()
        return text
    }

    fun paste(text: String) {
        if (text.isEmpty()) return
        val rows = if (text.contains('\t') || !text.contains(',')) {
            text.trimEnd('\n', '\r').split(Regex("\r?\n")).map { it.split('\t') }
        } else {
            Csv.parse(text).rows
        }
        val origin = CellRef(selection.top, selection.left)
        mutate {
            rows.forEachIndexed { dr, row ->
                row.forEachIndexed { dc, v -> sheet[CellRef(origin.row + dr, origin.col + dc)] = v }
            }
        }
        val last = CellRef(origin.row + rows.size - 1, origin.col + (rows.maxOfOrNull { it.size } ?: 1) - 1)
        selection = Selection(origin, last)
        loadEditTextForSelection()
    }

    /** Fills the selection downward from its first row (like Ctrl+D), shifting relative references. */
    fun fillDown() {
        val s = selection
        if (s.top == s.bottom) return
        mutate {
            for (c in s.left..s.right) {
                val source = sheet[CellRef(s.top, c)]
                for (r in s.top + 1..s.bottom) {
                    sheet[CellRef(r, c)] = if (source.startsWith("=")) shiftFormula(source, r - s.top) else source
                }
            }
        }
    }

    private fun shiftFormula(formula: String, rows: Int): String {
        // Shift unanchored row numbers in A1 references outside of string literals.
        val sb = StringBuilder()
        var inString = false
        var i = 0
        val refPattern = Regex("""(\$?)([A-Za-z]{1,3})(\$?)(\d+)""")
        while (i < formula.length) {
            val ch = formula[i]
            if (ch == '"') { inString = !inString; sb.append(ch); i++; continue }
            if (!inString) {
                val m = refPattern.find(formula, i)?.takeIf { it.range.first == i }
                val prevOk = i == 0 || !(formula[i - 1].isLetterOrDigit() || formula[i - 1] == '_')
                if (m != null && prevOk) {
                    val after = m.range.last + 1
                    val nextIsParen = after < formula.length && (formula[after] == '(' || formula[after].isLetter())
                    if (!nextIsParen) {
                        val (colAbs, col, rowAbs, row) = m.destructured
                        val newRow = if (rowAbs.isEmpty()) row.toInt() + rows else row.toInt()
                        sb.append(colAbs).append(col).append(rowAbs).append(newRow)
                        i = after
                        continue
                    }
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    // --- frozen panes, filter, charts ------------------------------------------------------

    val isFrozen: Boolean get() = sheet.frozenRows > 0 || sheet.frozenCols > 0

    /** Freezes the rows above and the columns left of the selected cell (the top row from A1). */
    fun freezeAtSelection() = mutate {
        val f = selection.focus
        if (f.row == 0 && f.col == 0) {
            sheet.frozenRows = 1
            sheet.frozenCols = 0
        } else {
            sheet.frozenRows = f.row
            sheet.frozenCols = f.col
        }
    }

    fun unfreeze() = mutate {
        sheet.frozenRows = 0
        sheet.frozenCols = 0
    }

    /** A view-only filter: rows below [headerRow] whose value in [col] is not in [allowed] are hidden. */
    data class Filter(val sheet: Int, val col: Int, val headerRow: Int, val allowed: Set<String>)

    var filter by mutableStateOf<Filter?>(null)
        private set

    /** The header row and the distinct values below it in [col], for the filter dialog. */
    fun filterChoices(col: Int): Pair<Int, List<String>> {
        val header = (0..sheet.maxRow).firstOrNull { sheet[CellRef(it, col)].isNotEmpty() } ?: 0
        val values = ((header + 1)..sheet.maxRow).map { displayText(CellRef(it, col)) }.distinct()
        return header to values.sortedWith(compareBy({ it.isEmpty() }, { it.lowercase() }))
    }

    fun setFilter(col: Int, headerRow: Int, allowed: Set<String>, all: Int) {
        filter = if (allowed.size >= all) null else Filter(activeSheetIndex, col, headerRow, allowed)
    }

    fun clearFilter() {
        filter = null
    }

    fun hiddenRows(): Set<Int> {
        val f = filter?.takeIf { it.sheet == activeSheetIndex } ?: return emptySet()
        return ((f.headerRow + 1)..sheet.maxRow).filter { displayText(CellRef(it, f.col)) !in f.allowed }.toSet()
    }

    /** Category labels and series values of [chart], read live from the cells it points to. */
    data class ChartData(val categories: List<String>, val series: List<Pair<String, List<Double?>>>)

    fun chartData(chart: ChartSpec): ChartData {
        fun sheetFor(ref: ChartRef) = ref.sheet?.let { workbook.sheet(it) } ?: sheet
        val categories = chart.categories?.let { ref -> ref.cells.map { engine.display(sheetFor(ref), it) } } ?: chart.cachedCategories
        val series = chart.series.map { s ->
            val name = s.nameRef?.let { engine.display(sheetFor(it), it.from) }?.takeIf { it.isNotEmpty() } ?: s.name
            val values = s.values?.let { ref ->
                val target = ref.sheet?.let { workbook.sheet(it) }
                if (ref.sheet != null && target == null) null
                else ref.cells.map { (engine.value(sheetFor(ref), it) as? Value.Num)?.v }
            } ?: s.cachedValues
            name to values
        }
        return ChartData(categories, series)
    }

    fun selectSheet(index: Int) {
        if (editing) commitEdit(move = false)
        filter = null
        activeSheetIndex = index.coerceIn(0, workbook.sheets.lastIndex)
        selection = Selection.single(CellRef(0, 0))
        loadEditTextForSelection()
        revision++
    }

    fun addSheet(baseName: String) {
        var n = workbook.sheets.size + 1
        while (workbook.sheet("$baseName$n") != null) n++
        mutate { workbook.sheets.add(Sheet("$baseName$n")) }
        selectSheet(workbook.sheets.lastIndex)
    }

    fun renameSheet(index: Int, name: String) {
        val clean = name.trim().take(31)
        if (clean.isEmpty() || workbook.sheets.withIndex().any { (i, s) -> i != index && s.name.equals(clean, true) }) return
        mutate { workbook.sheets[index].name = clean }
    }

    fun deleteSheet(index: Int) {
        if (workbook.sheets.size <= 1) return
        mutate { workbook.sheets.removeAt(index) }
        selectSheet(minOf(index, workbook.sheets.lastIndex))
    }

    // --- saving ---------------------------------------------------------------------------------

    val mimeType get() = kind.primaryMime

    fun suggestedName(base: String, targetKind: FileKind = kind) =
        (fileName?.substringBeforeLast('.') ?: base) + "." + targetKind.primaryExtension

    private fun serialize(target: FileKind): ByteArray = when (target) {
        FileKind.CSV -> Csv.write(Csv.fromSheet(sheet), csvDelimiter).toByteArray(Charsets.UTF_8)
        else -> ByteArrayOutputStream().also { XlsxWriter.write(workbook, it, originalPackage) }.toByteArray()
    }

    fun save(onDone: (Throwable?) -> Unit) {
        val target = uri ?: return onDone(IllegalStateException("No target"))
        saveTo(target, kind, onDone)
    }

    /** Saves to [target]. When the format differs from the current one, the file is exported, not re-targeted. */
    fun saveTo(target: Uri, format: FileKind, onDone: (Throwable?) -> Unit) {
        if (editing) commitEdit(move = false)
        viewModelScope.launch {
            saving = true
            val result = runCatching {
                val bytes = withContext(Dispatchers.Default) { serialize(format) }
                container.files.writeBytes(target, bytes)
            }
            saving = false
            result.onSuccess {
                if (format == kind) {
                    if (uri != target) {
                        uri = target
                        container.files.takePersistablePermission(target)
                        val info = container.files.info(target)
                        fileName = info.name
                        container.recents.record(info.copy(kind = kind))
                    }
                    dirty = false
                    autosaver.discard()
                }
            }
            onDone(result.exceptionOrNull())
        }
    }

    fun exportPdf(target: Uri, onDone: (Throwable?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { PdfExporter.sheet(sheet, engine, it) }.toByteArray()
                }
                container.files.writeBytes(target, bytes)
            }
            onDone(result.exceptionOrNull())
        }
    }

    suspend fun exportPdfToCache(title: String): File = withContext(Dispatchers.Default) {
        val file = File(container.files.exportsDir(), "${title.substringBeforeLast('.')}.pdf")
        file.outputStream().use { PdfExporter.sheet(sheet, engine, it) }
        file
    }

    companion object {
        const val DEFAULT_WIDTH = 10f
        val FUNCTIONS = FormulaEngine.FUNCTIONS
    }
}
