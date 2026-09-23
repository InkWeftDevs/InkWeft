#!/usr/bin/env python3
"""Generate assets/conditional-probability-note.pdf: a real, valid, tiny PDF.

Keyboard-ASCII only, no embedded fonts, standard Helvetica. The sample app uses
this file as its "real PDF origin" so the DocumentSurfaceAdapter can report an
honest capability status instead of claiming PDF support it does not ship.
"""
from pathlib import Path

LINES = [
    ("H1", "Conditional Probability"),
    ("P", "Definition. For events A and B with P(B) > 0, the conditional"),
    ("P", "probability of A given B is defined as"),
    ("F", "P(A | B) = P(A and B) / P(B)."),
    ("P", "Requirement. The condition P(B) > 0 must be checked before the"),
    ("P", "formula is applied. The denominator can never be zero."),
    ("P", "Interpretation. Conditioning restricts the sample space to B and"),
    ("P", "then rescales the measure so that the total mass is one again."),
    ("P", "Chain rule. For three events with positive probability,"),
    ("F", "P(A and B and C) = P(A) * P(B | A) * P(C | A and B)."),
    ("P", "Independence. A and B are independent exactly when"),
    ("F", "P(A | B) = P(A),"),
    ("P", "which requires P(B) > 0 as well."),
]

PAGE_W, PAGE_H = 595, 842
LEFT, TOP = 72, 92
LEADING = 22


def esc(text: str) -> str:
    return text.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def content_stream() -> bytes:
    out = ["BT"]
    y = PAGE_H - TOP
    for style, text in LINES:
        size = 17 if style == "H1" else 12
        font = "/F1" if style in ("H1", "P") else "/F2"
        out.append(f"{font} {size} Tf")
        out.append(f"1 0 0 1 {LEFT} {y} Tm")
        out.append(f"({esc(text)}) Tj")
        y -= LEADING + (10 if style == "H1" else 0)
        if style == "H1":
            y -= 12
    out.append("ET")
    return ("\n".join(out) + "\n").encode("latin-1")


def build() -> bytes:
    body = content_stream()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {PAGE_W} {PAGE_H}] "
         f"/Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>").encode("latin-1"),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>",
        b"<< /Length " + str(len(body)).encode() + b" >>\nstream\n" + body + b"endstream",
    ]

    pdf = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"

    xref_at = len(pdf)
    n = len(objects) + 1
    pdf += f"xref\n0 {n}\n".encode()
    pdf += b"0000000000 65535 f \n"
    for off in offsets:
        pdf += f"{off:010d} 00000 n \n".encode()
    pdf += (f"trailer\n<< /Size {n} /Root 1 0 R >>\nstartxref\n{xref_at}\n%%EOF\n").encode()
    return bytes(pdf)


if __name__ == "__main__":
    out = Path(__file__).resolve().parents[1] / "assets" / "conditional-probability-note.pdf"
    out.parent.mkdir(parents=True, exist_ok=True)
    data = build()
    out.write_bytes(data)
    import hashlib
    print(f"wrote {out} bytes={len(data)} sha256={hashlib.sha256(data).hexdigest()}")
