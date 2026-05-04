"""
Convert REPORT.md to REPORT.pdf with sensible code/heading styling and
embedded figures.
"""

import os
import sys
from pathlib import Path

import markdown
from weasyprint import HTML, CSS

LAB_DIR = Path(__file__).resolve().parent.parent
MD_PATH = LAB_DIR / "REPORT.md"
PDF_PATH = LAB_DIR / "REPORT.pdf"

CSS_TEXT = """
@page {
  size: Letter;
  margin: 0.75in 0.85in;
  @bottom-center { content: counter(page); font-size: 9pt; color: #666; }
}
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
               "Helvetica Neue", Arial, sans-serif;
  font-size: 10.5pt;
  line-height: 1.45;
  color: #1f2328;
}
h1 { font-size: 22pt; margin: 0 0 10pt 0; border-bottom: 2px solid #d0d7de;
     padding-bottom: 4pt; }
h2 { font-size: 15pt; margin: 18pt 0 6pt 0; border-bottom: 1px solid #d0d7de;
     padding-bottom: 3pt; }
h3 { font-size: 12pt; margin: 12pt 0 4pt 0; }
p, li { margin: 4pt 0; }
ul, ol { padding-left: 20pt; }
li > ul, li > ol { margin: 2pt 0; }
code {
  font-family: "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
  font-size: 9.5pt;
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
}
pre {
  background: #f6f8fa;
  border: 1px solid #d0d7de;
  border-radius: 5px;
  padding: 8pt 10pt;
  font-size: 9pt;
  line-height: 1.35;
  overflow-x: auto;
  page-break-inside: avoid;
}
pre code { background: transparent; padding: 0; font-size: 9pt; }
img {
  max-width: 100%;
  height: auto;
  display: block;
  margin: 8pt auto;
  page-break-inside: avoid;
}
a { color: #0969da; text-decoration: none; }
strong { color: #1f2328; }
"""


def main():
    md_text = MD_PATH.read_text(encoding="utf-8")
    html_body = markdown.markdown(
        md_text,
        extensions=["fenced_code", "tables", "sane_lists"],
    )
    html_doc = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Project 4 Report</title></head>
<body>{html_body}</body></html>"""

    HTML(string=html_doc, base_url=str(LAB_DIR)).write_pdf(
        target=str(PDF_PATH),
        stylesheets=[CSS(string=CSS_TEXT)],
    )
    print(PDF_PATH)


if __name__ == "__main__":
    main()
