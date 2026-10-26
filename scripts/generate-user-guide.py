"""Build the shareable Word guide from docs/user-guide.md."""

from pathlib import Path
import re

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.shared import Inches, Pt, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs" / "user-guide.md"
OUTPUT = ROOT / "docs" / "Error_Intelligence_User_Guide.docx"


def add_inline(paragraph, value):
    """Add modest Markdown emphasis and readable links without external dependencies."""
    value = re.sub(r"\[([^]]+)\]\(([^)]+)\)", r"\1", value)
    value = re.sub(r"<https?://([^>]+)>", r"https://\1", value)
    parts = re.split(r"(\*\*[^*]+\*\*|`[^`]+`|\*[^*]+\*)", value)
    for part in parts:
        if not part:
            continue
        if part.startswith("**") and part.endswith("**"):
            paragraph.add_run(part[2:-2]).bold = True
        elif part.startswith("`") and part.endswith("`"):
            run = paragraph.add_run(part[1:-1])
            run.font.name = "Consolas"
            run.font.size = Pt(8.5)
        elif part.startswith("*") and part.endswith("*"):
            paragraph.add_run(part[1:-1]).italic = True
        else:
            paragraph.add_run(part)


doc = Document()
sec = doc.sections[0]
sec.top_margin = Inches(0.65)
sec.bottom_margin = Inches(0.65)
sec.left_margin = Inches(0.75)
sec.right_margin = Inches(0.75)
sec.header_distance = Inches(0.3)
sec.footer_distance = Inches(0.35)

styles = doc.styles
styles["Normal"].font.name = "Aptos"
styles["Normal"].font.size = Pt(9.5)
styles["Normal"].paragraph_format.space_after = Pt(5)
styles["Normal"].paragraph_format.line_spacing = 1.12
for name, size, before, after in [("Title", 21, 0, 10), ("Heading 1", 14, 14, 6), ("Heading 2", 11, 9, 4)]:
    s = styles[name]
    s.font.name = "Aptos Display" if name == "Title" else "Aptos"
    s.font.size = Pt(size)
    s.font.bold = True
    s.font.color.rgb = RGBColor(20, 29, 26)
    s.paragraph_format.space_before = Pt(before)
    s.paragraph_format.space_after = Pt(after)
    s.paragraph_format.keep_with_next = True

footer = sec.footer.paragraphs[0]
footer.alignment = WD_ALIGN_PARAGRAPH.RIGHT
footer.add_run("Error Intelligence  ·  Local user guide  ·  ")
field = OxmlElement("w:fldSimple")
field.set(qn("w:instr"), "PAGE")
footer._p.append(field)
for run in footer.runs:
    run.font.size = Pt(8)
    run.font.color.rgb = RGBColor(100, 108, 105)

lines = SOURCE.read_text(encoding="utf-8").splitlines()
i = 0
while i < len(lines):
    line = lines[i].strip()
    i += 1
    if not line:
        continue
    if line.startswith("# "):
        title = doc.add_paragraph()
        title.paragraph_format.space_after = Pt(12)
        title_run = title.add_run(line[2:])
        title_run.font.name = "Aptos Display"
        title_run.font.size = Pt(21)
        title_run.font.bold = True
        title_run.font.color.rgb = RGBColor(20, 29, 26)
    elif line.startswith("## "):
        heading = line[3:]
        if heading.startswith("5. ") or heading.startswith("6. ") or heading.startswith("7. "):
            doc.add_page_break()
        doc.add_paragraph(heading, style="Heading 1")
    elif line.startswith("!["):
        match = re.match(r"!\[([^]]+)\]\(([^)]+)\)", line)
        if match:
            path = SOURCE.parent / match.group(2)
            with Image.open(path) as img:
                width, height = img.size
            max_width, max_height = 6.9, 7.0
            shown_width = min(max_width, max_height * width / height)
            p = doc.add_paragraph()
            p.paragraph_format.space_before = Pt(3)
            p.paragraph_format.space_after = Pt(2)
            p.paragraph_format.keep_with_next = True
            p.add_run().add_picture(str(path), width=Inches(shown_width))
            caption = doc.add_paragraph(match.group(1))
            caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
            caption.paragraph_format.space_after = Pt(8)
            for run in caption.runs:
                run.italic = True
                run.font.size = Pt(8)
                run.font.color.rgb = RGBColor(90, 100, 95)
    elif line.startswith("```" ):
        code = []
        while i < len(lines) and not lines[i].startswith("```"):
            code.append(lines[i])
            i += 1
        i += 1
        p = doc.add_paragraph()
        p.paragraph_format.left_indent = Inches(0.16)
        p.paragraph_format.space_after = Pt(8)
        p.paragraph_format.keep_together = True
        run = p.add_run("\n".join(code))
        run.font.name = "Consolas"
        run.font.size = Pt(8)
    elif line.startswith("| "):
        rows = [line]
        while i < len(lines) and lines[i].startswith("| "):
            rows.append(lines[i])
            i += 1
        cells = [[v.strip() for v in row.strip().strip("|").split("|")] for row in rows]
        cells = [cells[0]] + [row for row in cells[1:] if not all(re.fullmatch(r"-+", v) for v in row)]
        table = doc.add_table(rows=0, cols=len(cells[0]))
        table.style = "Table Grid"
        table.alignment = WD_TABLE_ALIGNMENT.CENTER
        for row_values in cells:
            row = table.add_row()
            for cell, value in zip(row.cells, row_values):
                add_inline(cell.paragraphs[0], value)
        for c in table.rows[0].cells:
            for p in c.paragraphs:
                for r in p.runs:
                    r.bold = True
    elif line.startswith("For the API contract, see"):
        doc.add_paragraph("Further details: API overview, portfolio demo and repository README.")
    else:
        paragraph = doc.add_paragraph()
        add_inline(paragraph, line)

doc.save(OUTPUT)
print(OUTPUT)
