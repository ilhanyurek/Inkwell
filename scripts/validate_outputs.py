"""Opens files saved by Inkwell's core tests (core/build/fixture-out) with python-docx and
openpyxl, failing if they cannot be read or lost content. Run after `./gradlew :core:test`."""
import sys
from pathlib import Path

out = Path(sys.argv[1] if len(sys.argv) > 1 else "core/build/fixture-out")

from docx import Document
d = Document(out / "report-edited.docx")
texts = [p.text for p in d.paragraphs]
assert "Key points" in texts, texts
assert len(d.tables) == 1 and d.tables[0].cell(1, 0).text == "Ankara", "table lost"
assert len(d.inline_shapes) == 1, "image lost"
assert d.sections[0].header.paragraphs[0].text == "Inkwell fixture header", "header lost"
print("report-edited.docx OK:", len(texts), "paragraphs, 1 table, 1 image")

import zipfile
from openpyxl import load_workbook
path = out / "budget-resaved.xlsx"
wb = load_workbook(path)
assert wb.sheetnames == ["Sales", "Notes", "Added"], wb.sheetnames
ws = wb["Sales"]
assert ws["A2"].value == "Kalem" and ws["D6"].value == "=SUM(D2:D4)", (ws["A2"].value, ws["D6"].value)
assert ws["A1"].font.b and ws["A1"].fill.fgColor.rgb.endswith("2B59C3"), "header style lost"
assert ws["E2"].number_format == "dd.mm.yyyy", ws["E2"].number_format
assert ws["A2"].font.i and ws["A2"].font.color.rgb.endswith("C00000"), "edited style missing"
assert ws["D7"].number_format == "#,##0.000" and ws["D7"].font.b, "new style missing"
assert "A10:D10" in [str(r) for r in ws.merged_cells.ranges], "merged cells lost"
assert any(n.startswith("xl/charts/") for n in zipfile.ZipFile(path).namelist()), "chart lost"
values = load_workbook(path, data_only=True)["Sales"]
assert values["D6"].value == 1785, values["D6"].value
print("budget-resaved.xlsx OK: formulas, styles, merged cells, chart and new sheet kept")
