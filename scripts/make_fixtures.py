"""Generates realistic test documents with independent libraries (python-docx, openpyxl,
python-pptx, reportlab), so our readers are tested against files we did not write ourselves.

    pip install python-docx openpyxl python-pptx reportlab
    python scripts/make_fixtures.py core/src/test/resources/fixtures
"""
import datetime
import io
import struct
import sys
import zlib
from pathlib import Path


def png(width=40, height=24, rgb=(43, 89, 195)):
    """A tiny solid-colour PNG without needing Pillow."""
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def make_docx(out: Path):
    from docx import Document
    from docx.enum.text import WD_BREAK
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    from docx.shared import Pt, RGBColor, Inches

    d = Document()
    d.sections[0].header.paragraphs[0].text = "Inkwell fixture header"
    d.add_heading("Quarterly Report", level=1)
    p = d.add_paragraph("Revenue grew ")
    r = p.add_run("strongly"); r.bold = True
    p.add_run(" in ")
    r = p.add_run("Q3"); r.italic = True; r.font.color.rgb = RGBColor(0xC0, 0x00, 0x00); r.font.size = Pt(14)
    p.add_run(". Details below.")
    d.add_heading("Highlights", level=2)
    for t in ("Ankara office opened", "İzmir sales doubled", "New product: Şirin"):
        d.add_paragraph(t, style="List Bullet")
    for t in ("Plan", "Build", "Ship"):
        d.add_paragraph(t, style="List Number")
    table = d.add_table(rows=3, cols=3)
    table.style = "Table Grid"
    for i, row in enumerate([("City", "Q2", "Q3"), ("Ankara", "120", "150"), ("İzmir", "80", "160")]):
        for j, v in enumerate(row):
            table.cell(i, j).text = v
    d.add_paragraph("Chart:")
    d.add_picture(io.BytesIO(png()), width=Inches(1.5))
    # A hyperlink (python-docx has no API for it).
    para = d.add_paragraph("Visit ")
    rel = d.part.relate_to("https://example.com", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", is_external=True)
    link = OxmlElement("w:hyperlink"); link.set(qn("r:id"), rel)
    run = OxmlElement("w:r"); text = OxmlElement("w:t"); text.text = "our site"; run.append(text); link.append(run)
    para._p.append(link)
    para.add_run(" for more.")
    br = d.add_paragraph().add_run(); br.add_break(WD_BREAK.PAGE)
    q = d.add_paragraph("Centered closing line.")
    q.alignment = 1
    d.save(out / "report.docx")


def make_styled_docx(out: Path):
    """A document with the formatting the editor must show and keep: fonts, colours, highlight, indents,
    spacing, a multi-level list, a merged and shaded table, header/footer and a page break."""
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_COLOR_INDEX, WD_UNDERLINE, WD_BREAK
    from docx.oxml import parse_xml
    from docx.oxml.ns import nsdecls
    from docx.shared import Pt, RGBColor, Cm

    d = Document()
    d.sections[0].header.paragraphs[0].text = "Styled header"
    d.sections[0].footer.paragraphs[0].text = "Styled footer"
    d.add_heading("Styled Title", level=0)
    h = d.add_heading("Heading with a ", level=1)
    r = h.add_run("light"); r.bold = False
    h.add_run(" word")

    p = d.add_paragraph("Plain, ")
    r = p.add_run("red"); r.font.color.rgb = RGBColor(0xC0, 0x00, 0x00)
    p.add_run(", ")
    r = p.add_run("highlighted"); r.font.highlight_color = WD_COLOR_INDEX.YELLOW
    p.add_run(", ")
    r = p.add_run("Times 14"); r.font.name = "Times New Roman"; r.font.size = Pt(14)
    p.add_run(", ")
    r = p.add_run("double"); r.font.underline = WD_UNDERLINE.DOUBLE
    p.add_run(", x")
    r = p.add_run("2"); r.font.superscript = True
    p.add_run(" and ")
    r = p.add_run("mono"); r.font.name = "Courier New"

    p = d.add_paragraph("Indented paragraph with space around it and wider lines.")
    p.paragraph_format.left_indent = Cm(1.5)
    p.paragraph_format.first_line_indent = Cm(0.75)
    p.paragraph_format.space_before = Pt(18)
    p.paragraph_format.space_after = Pt(12)
    p.paragraph_format.line_spacing = 1.5
    p = d.add_paragraph("Right aligned line")
    p.alignment = WD_ALIGN_PARAGRAPH.RIGHT

    # A real multi-level list: 1. / 1.1. / a)
    numbering = d.part.numbering_part.element
    numbering.insert(0, parse_xml(
        '<w:abstractNum %s w:abstractNumId="90"><w:multiLevelType w:val="multilevel"/>'
        '<w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%%1."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="360" w:hanging="360"/></w:pPr></w:lvl>'
        '<w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%%1.%%2."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="792" w:hanging="432"/></w:pPr></w:lvl>'
        '<w:lvl w:ilvl="2"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%%3)"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="1224" w:hanging="504"/></w:pPr></w:lvl>'
        '</w:abstractNum>' % nsdecls("w")))
    numbering.append(parse_xml('<w:num %s w:numId="90"><w:abstractNumId w:val="90"/></w:num>' % nsdecls("w")))
    for text, lvl in (("First", 0), ("First child", 1), ("Second child", 1), ("Deep a", 2), ("Deep b", 2), ("Second", 0), ("Second first child", 1)):
        p = d.add_paragraph(text)
        p._p.get_or_add_pPr().append(parse_xml(
            '<w:numPr %s><w:ilvl w:val="%d"/><w:numId w:val="90"/></w:numPr>' % (nsdecls("w"), lvl)))
    d.add_paragraph("Bullet from a style", style="List Bullet")
    d.add_paragraph("Nested bullet from a style", style="List Bullet 2")

    t = d.add_table(rows=3, cols=3)
    t.style = "Table Grid"
    t.cell(0, 0).merge(t.cell(0, 2)).text = "Merged header"
    for i, row in enumerate((("A", "B", "C"), ("D", "E", "F")), start=1):
        for j, v in enumerate(row):
            t.cell(i, j).text = v
    t.cell(0, 0)._tc.get_or_add_tcPr().append(parse_xml('<w:shd %s w:val="clear" w:color="auto" w:fill="2F5BC4"/>' % nsdecls("w")))
    t.cell(1, 0).merge(t.cell(2, 0))

    p = d.add_paragraph()
    p.add_run().add_break(WD_BREAK.PAGE)
    d.add_paragraph("Text on the second page.")
    d.save(out / "styled.docx")


def make_form_docx(out: Path):
    """A university form laid out the way such forms usually are: the title block with logos in the page
    header, small tables with bold labels and merged cells, text inside content controls, checkbox content
    controls, a checkbox grid nested in a table cell and a page break."""
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
    from docx.oxml import parse_xml
    from docx.oxml.ns import nsdecls, qn
    from docx.shared import Pt, Cm

    W14 = 'xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"'
    d = Document()
    sec = d.sections[0]
    sec.page_width, sec.page_height = Cm(21), Cm(29.7)
    normal = d.styles["Normal"]
    normal.font.name = "Times New Roman"
    normal.font.size = Pt(11)
    normal.paragraph_format.space_after = Pt(0)

    def borders(table):
        table.style = "Table Grid"

    def bold_cell(cell, text, align=None):
        cell.text = ""
        p = cell.paragraphs[0]
        r = p.add_run(text)
        r.bold = True
        if align is not None:
            p.alignment = align

    # Title block with two logos, in the header (repeats on every page).
    header = sec.header
    ht = header.add_table(rows=1, cols=3, width=Cm(17))
    borders(ht)
    logo = io.BytesIO(png(60, 60, (30, 70, 160)))
    ht.cell(0, 0).paragraphs[0].add_run().add_picture(logo, width=Cm(1.6))
    logo.seek(0)
    ht.cell(0, 2).paragraphs[0].add_run().add_picture(logo, width=Cm(1.6))
    mid = ht.cell(0, 1)
    lines = ["Örnek Üniversitesi", "Mühendislik Fakültesi", "Malzeme Mühendisliği Bölümü", "TASARIM ÇALIŞMASI ÖNERİ FORMU"]
    mid.text = ""
    for i, line in enumerate(lines):
        p = mid.paragraphs[0] if i == 0 else mid.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.add_run(line).bold = True
    header.paragraphs[0].text = ""

    d.add_paragraph()
    t1 = d.add_table(rows=2, cols=4)
    borders(t1)
    bold_cell(t1.cell(0, 0), "Öğretim Yılı")
    t1.cell(0, 1).text = "20... - 20... GÜZ/BAHAR"
    bold_cell(t1.cell(0, 2), "Tarih", WD_ALIGN_PARAGRAPH.RIGHT)
    t1.cell(0, 3).text = "…/…/20….."
    bold_cell(t1.cell(1, 0), "Danışman")
    bold_cell(t1.cell(1, 2), "Danışman İmza", WD_ALIGN_PARAGRAPH.RIGHT)
    d.add_paragraph()

    t2 = d.add_table(rows=4, cols=7)
    borders(t2)
    for j, h in enumerate(["Öğrenci No", "Adı Soyadı", "İmza", "", "Öğrenci No", "Adı Soyadı", "İmza"]):
        if h:
            bold_cell(t2.cell(0, j), h, WD_ALIGN_PARAGRAPH.CENTER)
    # A narrow spacer column, like the original.
    grid = t2._tbl.tblGrid
    for j, w in enumerate([2000, 2000, 1100, 300, 2000, 2000, 1100]):
        grid.gridCol_lst[j].set(qn("w:w"), str(w))
    d.add_paragraph()

    p = d.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    p.add_run("Öğretim üyelerinin, öğrencilerin önceki derslerde edindikleri bilgi ve becerileri kullanacakları, ")
    r = p.add_run("gerçekçi koşulları/kısıtları"); r.bold = True; r.underline = True
    r = p.add_run(" (Maliyet analiz, ekonomi, çevre sorunları, sürdürülebilirlik, etik, sağlık, güvenlik gibi)"); r.bold = True
    p.add_run(" ve ")
    r = p.add_run("mühendislik standartlarını"); r.bold = True; r.underline = True
    p.add_run(" içerecek bir ana tasarım deneyimiyle mühendislik uygulamasına hazır hale getirilmesi hususunu dikkate alarak konu tercihinde bulunmaları gerekmektedir.")

    # The study box: each cell holds a bold label and text inside content controls.
    t3 = d.add_table(rows=3, cols=1)
    borders(t3)
    labels = [("Çalışmanın Adı: ", "Seramik Bal Peteği Yapıların Tasarımı ve Optimizasyonu"),
              ("Çalışmanın Amacı: ", "Gövde malzemesi olarak kordiyerit (2MgO·2Al2O3·5SiO2) üzerinde karar kılınmıştır."),
              ("Çalışmanın Yöntemi: ", "Çalışma, literatür taraması ve buna dayalı malzeme ve süreç seçimi üzerine kurulmuştur.")]
    for i, (label, text) in enumerate(labels):
        c = t3.cell(i, 0)
        c.text = ""
        para = c.paragraphs[0]
        para.add_run(label).bold = True
        # Inline content control holding the answer text.
        sdt = parse_xml(
            '<w:sdt %s><w:sdtPr><w:alias w:val="Cevap %d"/><w:id w:val="%d"/></w:sdtPr><w:sdtContent>'
            '<w:r><w:t xml:space="preserve">%s</w:t></w:r></w:sdtContent></w:sdt>' % (nsdecls("w"), i + 1, 100 + i, text))
        para._p.append(sdt)
    # A subscript in the formula, as in the original.
    d.add_paragraph()

    # Block-level content control around a paragraph and a table with a checkbox grid.
    box = parse_xml(
        '<w:sdt %s><w:sdtPr><w:id w:val="200"/></w:sdtPr><w:sdtContent>'
        '<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Çalışmada incelenecek gerçekçi koşullar/kısıtlar (en az 3 adet işaretleyiniz):</w:t></w:r></w:p>'
        '</w:sdtContent></w:sdt>' % nsdecls("w"))
    d.element.body.insert(len(d.element.body) - 1, box)
    grid_t = d.add_table(rows=3, cols=6)
    borders(grid_t)
    items = [["Ekonomi", "Maliyet analizi", "Çevre sorunları"], ["Sürdürülebilirlik", "Üretilebilirlik", "Etik"], ["Sağlık", "Güvenlik", "Sosyal ve politik sorunlar"]]
    for r_i, row in enumerate(items):
        for k, label in enumerate(row):
            mark = grid_t.cell(r_i, k * 2).paragraphs[0]
            mark._p.append(parse_xml(
                '<w:sdt %s %s><w:sdtPr><w:id w:val="%d"/><w14:checkbox><w14:checked w14:val="%d"/>'
                '<w14:checkedState w14:val="2612" w14:font="MS Gothic"/><w14:uncheckedState w14:val="2610" w14:font="MS Gothic"/></w14:checkbox></w:sdtPr>'
                '<w:sdtContent><w:r><w:rPr><w:rFonts w:ascii="MS Gothic" w:hAnsi="MS Gothic"/></w:rPr><w:t>%s</w:t></w:r></w:sdtContent></w:sdt>'
                % (nsdecls("w"), W14, 300 + r_i * 3 + k, 1 if k == 0 else 0, "☒" if k == 0 else "☐")))
            grid_t.cell(r_i, k * 2 + 1).text = label
    d.add_paragraph()
    for line in ("☒ Ekonomi / Maliyet Analizi: ham madde ve enerji maliyetlerinin karşılaştırılması.",
                 "☒ Çevre sorunları: yüksek sinterleme sıcaklığının enerji tüketimi."):
        d.add_paragraph(line)

    # Approval table with a vertically merged cell.
    t4 = d.add_table(rows=3, cols=3)
    borders(t4)
    a = t4.cell(0, 0).merge(t4.cell(1, 0))
    a.text = "Önerilen çalışma MÜDEK değerlendirme kriterlerine uygundur."
    bold_cell(t4.cell(0, 2), "UYGUNDUR")
    bold_cell(t4.cell(1, 2), "UYGUN DEĞİLDİR")
    last = t4.cell(2, 0).merge(t4.cell(2, 2))
    last.text = "Malzeme Mühendisliği Bölüm Başkanlığı"
    last.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.CENTER

    p = d.add_paragraph()
    p.add_run().add_break(WD_BREAK.PAGE)
    d.add_paragraph("Ek sayfa: bulgular tasarım ölçütleri halinde derlenip raporlanacaktır.")
    d.save(out / "form.docx")


def make_xlsx(out: Path):
    from openpyxl import Workbook
    from openpyxl.chart import BarChart, Reference
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side

    wb = Workbook()
    ws = wb.active
    ws.title = "Sales"
    ws.append(["Product", "Units", "Price", "Revenue", "Date", "Share"])
    rows = [("Kalem", 120, 3.5), ("Defter", 80, 12.25), ("Silgi", 200, 1.75)]
    for i, (name, units, price) in enumerate(rows, start=2):
        ws.append([name, units, price, f"=B{i}*C{i}", datetime.date(2025, 1, 10 + i), f"=D{i}/D$6"])
    ws["A6"] = "Total"
    ws["B6"] = "=SUM(B2:B4)"
    ws["D6"] = "=SUM(D2:D4)"
    ws["A7"] = "Average price"
    ws["C7"] = "=AVERAGE(C2:C4)"
    ws["A8"] = "Status"
    ws["B8"] = '=IF(D6>1000,"good","low")'
    bold = Font(bold=True, color="FFFFFF")
    fill = PatternFill("solid", fgColor="2B59C3")
    thin = Side(style="thin", color="999999")
    for c in ws[1]:
        c.font = bold; c.fill = fill; c.alignment = Alignment(horizontal="center")
        c.border = Border(top=thin, bottom=thin, left=thin, right=thin)
    for r in range(2, 7):
        ws[f"C{r}"].number_format = '#,##0.00 "₺"'
        ws[f"D{r}"].number_format = "#,##0.00"
        ws[f"E{r}"].number_format = "dd.mm.yyyy"
        ws[f"F{r}"].number_format = "0.0%"
    ws.column_dimensions["A"].width = 18
    ws.merge_cells("A10:D10")
    ws["A10"] = "Merged note across four columns"
    chart = BarChart()
    chart.add_data(Reference(ws, min_col=2, min_row=1, max_row=4), titles_from_data=True)
    chart.set_categories(Reference(ws, min_col=1, min_row=2, max_row=4))
    ws.add_chart(chart, "H2")
    ws2 = wb.create_sheet("Notes")
    ws2["A1"] = "Linked total"
    ws2["B1"] = "=Sales!D6*2"
    ws2["A2"] = "Text with = sign"
    ws2["B2"] = "'=not a formula"
    wb.save(out / "budget.xlsx")


def make_pptx(out: Path):
    from pptx import Presentation
    from pptx.util import Inches, Pt

    prs = Presentation()
    s = prs.slides.add_slide(prs.slide_layouts[0])
    s.shapes.title.text = "Inkwell Deck"
    s.placeholders[1].text = "Fixture subtitle"
    s = prs.slides.add_slide(prs.slide_layouts[1])
    s.shapes.title.text = "Agenda"
    body = s.placeholders[1].text_frame
    body.text = "Introduction"
    for t in ("Numbers", "Next steps"):
        body.add_paragraph().text = t
    s.notes_slide.notes_text_frame.text = "Speaker note for agenda"
    s = prs.slides.add_slide(prs.slide_layouts[5])
    s.shapes.title.text = "Picture and table"
    s.shapes.add_picture(io.BytesIO(png(60, 40, (30, 127, 79))), Inches(1), Inches(2), width=Inches(2))
    tbl = s.shapes.add_table(2, 2, Inches(4), Inches(2), Inches(4), Inches(1)).table
    tbl.cell(0, 0).text = "Q2"; tbl.cell(0, 1).text = "Q3"
    tbl.cell(1, 0).text = "120"; tbl.cell(1, 1).text = "150"
    box = s.shapes.add_textbox(Inches(1), Inches(5), Inches(6), Inches(1)).text_frame
    box.text = "Free text box"
    box.paragraphs[0].runs[0].font.size = Pt(24)
    box.paragraphs[0].runs[0].font.bold = True
    prs.save(out / "deck.pptx")


def make_pdf(out: Path):
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas

    c = canvas.Canvas(str(out / "form.pdf"), pagesize=A4)
    c.setFont("Helvetica", 14)
    c.drawString(72, 780, "Application form (Inkwell fixture)")
    c.drawString(72, 740, "Full name:")
    c.acroForm.textfield(name="full_name", x=160, y=730, width=250, height=20, borderWidth=1)
    c.drawString(72, 700, "City:")
    c.acroForm.textfield(name="city", x=160, y=690, width=250, height=20, borderWidth=1)
    c.drawString(72, 660, "I agree:")
    c.acroForm.checkbox(name="agree", x=160, y=655, size=16)
    c.showPage()
    c.setFont("Helvetica", 12)
    c.drawString(72, 780, "Second page with searchable text: Ankara")
    c.save()


if __name__ == "__main__":
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "core/src/test/resources/fixtures")
    target.mkdir(parents=True, exist_ok=True)
    make_docx(target)
    make_styled_docx(target)
    make_form_docx(target)
    make_xlsx(target)
    make_pptx(target)
    make_pdf(target)
    print("fixtures written to", target)
