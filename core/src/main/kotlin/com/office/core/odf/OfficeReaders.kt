package com.office.core.odf

import com.office.core.docx.DocxReader
import com.office.core.pptx.Presentation
import com.office.core.pptx.PptxReader
import com.office.core.sheet.Workbook
import com.office.core.text.TextDocument
import com.office.core.xlsx.XlsxReader
import com.office.core.zip.ZipPackage
import java.io.InputStream

/** Opens Office Open XML or OpenDocument files, whichever the package turns out to be. */
object OfficeReaders {
    fun document(pkg: ZipPackage): TextDocument = if (Odf.isOdf(pkg)) OdtReader.read(pkg) else DocxReader.read(pkg)
    fun document(input: InputStream): TextDocument = document(ZipPackage.read(input))

    fun workbook(pkg: ZipPackage): Workbook = if (Odf.isOdf(pkg)) OdsReader.read(pkg) else XlsxReader.read(pkg)
    fun workbook(input: InputStream): Workbook = workbook(ZipPackage.read(input))

    fun presentation(pkg: ZipPackage): Presentation = if (Odf.isOdf(pkg)) OdpReader.read(pkg) else PptxReader.read(pkg)
    fun presentation(input: InputStream): Presentation = presentation(ZipPackage.read(input))
}
