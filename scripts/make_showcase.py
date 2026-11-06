"""Generates the polished English sample files used for the store screenshots
(StoreScreenshotsTest). Test fixtures stay in scripts/make_fixtures.py.

    pip install python-docx openpyxl python-pptx reportlab pillow
    python scripts/make_showcase.py app/src/androidTest/assets/showcase
"""
import io
import sys
from pathlib import Path

BLUE = (47, 91, 196)
GREEN = (46, 125, 50)
GREY = (90, 96, 106)


def bar_chart(width=960, height=520):
    """A simple bar chart PNG (revenue by quarter) drawn with Pillow."""
    from PIL import Image, ImageDraw, ImageFont

    img = Image.new("RGB", (width, height), "white")
    d = ImageDraw.Draw(img)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 30)
        small = ImageFont.truetype("DejaVuSans.ttf", 24)
    except OSError:
        font = small = ImageFont.load_default()
    d.text((40, 24), "Revenue by quarter ($k)", fill=(30, 30, 30), font=font)
    values = [("Q4 '25", 412), ("Q1", 455), ("Q2", 498), ("Q3", 586)]
    base, top, left = height - 70, 100, 90
    d.line((left, base, width - 40, base), fill=(180, 180, 180), width=2)
    bar_w = (width - left - 80) // len(values) - 40
    for i, (label, v) in enumerate(values):
        x = left + 30 + i * (bar_w + 40)
        h = int((base - top) * v / 620)
        colour = BLUE if i < len(values) - 1 else GREEN
        d.rectangle((x, base - h, x + bar_w, base), fill=colour)
        d.text((x + bar_w // 2 - 28, base - h - 36), str(v), fill=(30, 30, 30), font=small)
        d.text((x + bar_w // 2 - 36, base + 14), label, fill=GREY, font=small)
    out = io.BytesIO()
    img.save(out, "PNG")
    return out.getvalue()


def make_docx(out: Path):
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    from docx.shared import Inches, Pt, RGBColor

    d = Document()
    d.add_heading("Q3 2026 Business Review", level=1)
    p = d.add_paragraph("Prepared by the Finance team · October 14, 2026")
    p.runs[0].italic = True
    d.add_heading("Summary", level=2)
    p = d.add_paragraph("Revenue reached ")
    r = p.add_run("$586k"); r.bold = True
    p.add_run(", up ")
    r = p.add_run("18% on Q2"); r.bold = True; r.font.color.rgb = RGBColor(0x2E, 0x7D, 0x32)
    p.add_run(". Growth came mostly from new business customers in Europe, while costs stayed within budget.")
    d.add_heading("Highlights", level=2)
    for t in (
        "Signed 42 new business customers",
        "Opened the Berlin office in August",
        "Launched the mobile app in 22 languages",
        "Customer satisfaction rose to 4.7 / 5",
    ):
        d.add_paragraph(t, style="List Bullet")
    d.add_heading("Revenue by region", level=2)
    rows = [("Region", "Revenue", "Growth"), ("North America", "$248k", "+12%"), ("Europe", "$221k", "+31%"),
            ("Asia Pacific", "$117k", "+9%")]
    t = d.add_table(rows=len(rows), cols=3)
    t.style = "Light Grid Accent 1"
    for i, row in enumerate(rows):
        for j, v in enumerate(row):
            t.cell(i, j).text = v
    d.add_paragraph()
    d.add_picture(io.BytesIO(bar_chart()), width=Inches(5.5))
    d.add_heading("Next steps", level=2)
    for t in ("Hire two account managers for Europe", "Ship the Tools tab to all customers",
              "Review pricing before the Q4 campaign"):
        d.add_paragraph(t, style="List Number")
    p = d.add_paragraph("Full figures are in the attached budget workbook.")
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    d.save(out)


def make_xlsx(out: Path):
    from openpyxl import Workbook
    from openpyxl.chart import BarChart, Reference
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side

    wb = Workbook()
    ws = wb.active
    ws.title = "Budget"
    header = ["Category", "Q1", "Q2", "Q3", "Q4", "Total"]
    rows = [
        ("Salaries", 182000, 186000, 191000, 195000),
        ("Office rent", 24000, 24000, 26500, 26500),
        ("Marketing", 31000, 28500, 42000, 55000),
        ("Software", 12400, 12900, 13300, 13800),
        ("Travel", 8200, 9600, 7400, 11800),
        ("Equipment", 15500, 6200, 9100, 7300),
    ]
    blue = PatternFill("solid", fgColor="2F5BC4")
    thin = Side(style="thin", color="C9CED6")
    ws.append(header)
    for c in ws[1]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = blue
        c.alignment = Alignment(horizontal="center")
    for i, r in enumerate(rows, start=2):
        ws.append(list(r) + [f"=SUM(B{i}:E{i})"])
    last = len(rows) + 1
    total = last + 1
    ws.append(["Total"] + [f"=SUM({col}2:{col}{last})" for col in "BCDEF"])
    for c in ws[total]:
        c.font = Font(bold=True)
        c.fill = PatternFill("solid", fgColor="E8EEFB")
    for row in ws.iter_rows(min_row=2, max_row=total, min_col=2, max_col=6):
        for c in row:
            c.number_format = '"$"#,##0'
    for row in ws.iter_rows(min_row=1, max_row=total, max_col=6):
        for c in row:
            c.border = Border(top=thin, bottom=thin, left=thin, right=thin)
    ws.column_dimensions["A"].width = 16
    for col in "BCDEF":
        ws.column_dimensions[col].width = 12
    chart = BarChart()
    chart.title = "Spending by quarter"
    chart.add_data(Reference(ws, min_col=2, max_col=5, min_row=1, max_row=last), titles_from_data=True)
    chart.set_categories(Reference(ws, min_col=1, min_row=2, max_row=last))
    ws.add_chart(chart, "H2")
    notes = wb.create_sheet("Notes")
    notes.append(["Owner", "Finance team"])
    notes.append(["Updated", "October 14, 2026"])
    notes.append(["Status", "Approved"])
    notes.column_dimensions["A"].width = 12
    notes.column_dimensions["B"].width = 20
    wb.save(out)


def make_pptx(out: Path):
    from pptx import Presentation
    from pptx.dml.color import RGBColor
    from pptx.util import Emu, Inches, Pt

    prs = Presentation()
    prs.slide_width, prs.slide_height = Inches(13.333), Inches(7.5)
    blank = prs.slide_layouts[6]

    def box(slide, x, y, w, h, text, size, bold=False, colour=(30, 30, 30)):
        tb = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
        tf = tb.text_frame
        tf.word_wrap = True
        lines = text if isinstance(text, list) else [text]
        for i, line in enumerate(lines):
            p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
            r = p.add_run()
            r.text = line
            r.font.size = Pt(size)
            r.font.bold = bold
            r.font.color.rgb = RGBColor(*colour)
        return tb

    def background(slide, rgb):
        rect = slide.shapes.add_shape(1, 0, 0, prs.slide_width, prs.slide_height)
        rect.fill.solid()
        rect.fill.fore_color.rgb = RGBColor(*rgb)
        rect.line.fill.background()

    s = prs.slides.add_slide(blank)
    background(s, (31, 58, 125))
    box(s, 0.9, 2.4, 11.5, 1.4, "Inkwell 2.0 Launch", 54, bold=True, colour=(255, 255, 255))
    box(s, 0.9, 3.8, 11.5, 0.8, "Product review · November 2026", 26, colour=(206, 216, 240))
    s.notes_slide.notes_text_frame.text = "Welcome everyone and introduce the team."

    s = prs.slides.add_slide(blank)
    box(s, 0.8, 0.6, 11.5, 1.0, "Agenda", 40, bold=True, colour=(31, 58, 125))
    box(s, 0.8, 1.8, 11.5, 4.5, ["•  What we shipped", "•  Customer results", "•  Launch timeline",
                                 "•  Questions"], 28)

    s = prs.slides.add_slide(blank)
    box(s, 0.8, 0.6, 11.5, 1.0, "Results", 40, bold=True, colour=(31, 58, 125))
    s.shapes.add_picture(io.BytesIO(bar_chart()), Inches(0.8), Inches(1.8), width=Inches(6.6))
    box(s, 7.8, 2.0, 5.0, 4.0, ["Revenue +18% on Q2", "42 new customers", "Rating 4.7 / 5"], 26)
    prs.save(out)


def make_pdf(out: Path):
    from reportlab.lib.pagesizes import A4
    from reportlab.pdfgen import canvas

    w, h = A4
    c = canvas.Canvas(str(out), pagesize=A4)
    c.setTitle("Service Agreement")
    y = h - 72
    c.setFont("Helvetica-Bold", 22)
    c.drawString(72, y, "Service Agreement")
    y -= 24
    c.setFont("Helvetica", 11)
    c.drawString(72, y, "Between Northwind Studio Ltd. and Contoso Retail Inc. · Effective November 1, 2026")
    sections = [
        ("1. Services", [
            "Northwind Studio will design, build and maintain the Contoso mobile ordering app,",
            "including monthly updates, store listings and support for Android and iOS.",
        ]),
        ("2. Fees and payment", [
            "Contoso will pay a fixed fee of $8,400 per month, invoiced on the first business day.",
            "Invoices are due within 30 days. Late payments carry interest of 1% per month.",
        ]),
        ("3. Term", [
            "This agreement runs for twelve months and renews for another year unless either",
            "party gives written notice at least 60 days before the end of the term.",
        ]),
        ("4. Confidentiality", [
            "Both parties keep the other's business information confidential during the term",
            "and for three years after it ends, except where disclosure is required by law.",
        ]),
        ("5. Ownership", [
            "Contoso owns the app's source code and designs once each invoice has been paid.",
            "Northwind keeps the right to reuse its general tools and know-how.",
        ]),
        ("6. Termination", [
            "Either party may end this agreement with 60 days' written notice. Fees for work",
            "already delivered remain payable.",
        ]),
    ]
    y -= 34
    for title, lines in sections:
        c.setFont("Helvetica-Bold", 13)
        c.drawString(72, y, title)
        y -= 20
        c.setFont("Helvetica", 11)
        for line in lines:
            c.drawString(72, y, line)
            y -= 16
        y -= 14
    y -= 20
    c.setFont("Helvetica", 11)
    for x, who in ((72, "Northwind Studio Ltd."), (320, "Contoso Retail Inc.")):
        c.line(x, y, x + 200, y)
        c.drawString(x, y - 16, who)
        c.drawString(x, y - 32, "Date:")
    c.showPage()
    c.setFont("Helvetica-Bold", 16)
    c.drawString(72, h - 72, "Schedule A: Monthly deliverables")
    c.setFont("Helvetica", 11)
    yy = h - 104
    for item in ("App update with bug fixes", "Store listing review", "Usage report", "Support within 1 business day"):
        c.drawString(90, yy, "•  " + item)
        yy -= 18
    c.save()


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/androidTest/assets/showcase")
    out.mkdir(parents=True, exist_ok=True)
    make_docx(out / "Quarterly Report.docx")
    make_xlsx(out / "Budget 2026.xlsx")
    make_pptx(out / "Product Launch.pptx")
    make_pdf(out / "Service Agreement.pdf")
    print("written to", out)


if __name__ == "__main__":
    main()
