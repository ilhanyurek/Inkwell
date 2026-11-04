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

from openpyxl import load_workbook
wb = load_workbook(out / "budget-resaved.xlsx")
assert wb.sheetnames == ["Sales", "Notes"], wb.sheetnames
ws = wb["Sales"]
assert ws["A2"].value == "Kalem" and ws["D6"].value == "=SUM(D2:D4)", (ws["A2"].value, ws["D6"].value)
values = load_workbook(out / "budget-resaved.xlsx", data_only=True)["Sales"]
assert values["D6"].value == 1750, values["D6"].value
print("budget-resaved.xlsx OK: formulas kept, cached totals present")
