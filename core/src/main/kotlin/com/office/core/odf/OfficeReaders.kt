package com.office.core.odf

import com.office.core.docx.DocxReader
import com.office.core.pptx.Presentation
import com.office.core.pptx.PptxReader
import com.office.core.sheet.Workbook
import com.office.core.text.TextDocument
import com.office.core.xlsx.XlsxReader
import com.office.core.zip.ZipPackage
import com.office.core.doc.DocReader
import com.office.core.ole.Cfb
import com.office.core.xls.XlsReader
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Opens Office Open XML or OpenDocument files, whichever the package turns out to be. */
object OfficeReaders {
    /** Word/Excel 97-2003 (.doc/.xls) files are OLE compound files rather than zip packages. */
    fun isLegacy(bytes: ByteArray): Boolean = Cfb.isCfb(bytes)

    fun document(bytes: ByteArray): TextDocument =
        if (Cfb.isCfb(bytes)) DocReader.read(bytes) else document(ZipPackage.read(ByteArrayInputStream(bytes)))

    fun workbook(bytes: ByteArray): Workbook =
        if (Cfb.isCfb(bytes)) XlsReader.read(bytes) else workbook(ZipPackage.read(ByteArrayInputStream(bytes)))

    fun document(pkg: ZipPackage): TextDocument = if (Odf.isOdf(pkg)) OdtReader.read(pkg) else DocxReader.read(pkg)
    fun document(input: InputStream): TextDocument = document(input.readBytes())

    fun workbook(pkg: ZipPackage): Workbook = if (Odf.isOdf(pkg)) OdsReader.read(pkg) else XlsxReader.read(pkg)
    fun workbook(input: InputStream): Workbook = workbook(input.readBytes())

    fun presentation(pkg: ZipPackage): Presentation = if (Odf.isOdf(pkg)) OdpReader.read(pkg) else PptxReader.read(pkg)
    fun presentation(input: InputStream): Presentation = presentation(ZipPackage.read(input))
}
