#!/usr/bin/env python3
"""Reference implementation of notebook-to-course.md §4 and §5.1–5.4.

Standard library only. Reads the synthetic documents of files/ (built by
make_documents.py) and writes:

  native/shared/documents/document-title-vectors.json
  native/shared/notebook-course-vectors.json

Every `expected` value is computed here; nothing is written by hand.

    python3 native/shared/documents/reference.py          # write both JSON files
    python3 native/shared/documents/reference.py --check  # build twice, compare with disk
"""

from __future__ import annotations

import copy
import datetime as dt
import hashlib
import io
import json
import math
import re
import sys
import unicodedata
import zipfile
import zlib
from pathlib import Path
from xml.etree import ElementTree as ET

HERE = Path(__file__).resolve().parent
SHARED = HERE.parent
FILES = HERE / "files"
DOC_VECTORS = HERE / "document-title-vectors.json"
COURSE_VECTORS = SHARED / "notebook-course-vectors.json"
CONSTANTS = SHARED / "domain" / "constants.json"

# ===========================================================================
# 5.2 Normalisation and rejection
# ===========================================================================

MAX_TITLE = 120
MAX_DESCRIPTION = 2000
WHITESPACE = "\t\n\u000b\u000c\r    "
REMOVED_CHARS = re.compile("[\u0000-\u0008\u000e-\u001f\u007f-\u009f‎‏‪-‮⁦-⁩﻿]")
WS_RUN = re.compile("[" + re.escape(WHITESPACE) + "]+")
QUOTES = "\"'«»“”„‘’‹›"
TRAILING_PUNCT = ".:;-–—"
PREFIXES = ("Microsoft Word - ", "Microsoft PowerPoint - ")
SUFFIXES = (".docx", ".odt", ".pdf", ".pptx")
ADMIN = re.compile(r"^(nom|prenom|classe|date)\s*:")
GENERIC = re.compile(r"^(sans titre|untitled|document\s*\d*|presentation\s*\d*|diapositive\s*\d+|cours|seance"
                     r"|exercices?|devoirs?)$")
NUMBER = re.compile(r"^[+-]?\d+([.,]\d+)?$")
DATE_NUMERIC = re.compile(r"^\d{1,4}[/.-]\d{1,2}([/.-]\d{1,4})?$")
MONTHS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre",
          "novembre", "décembre"]
DATE_TEXT = re.compile(r"^((lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\s+)?(\d{1,2}|1er)\s+"
                       r"(janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)"
                       r"(\s+\d{4})?$")
CHAPTER = re.compile(
    r"^(?i:(chapitre|chap\.?|ch\.?|s[eéEÉ]quence|s[eéEÉ]q\.?|th[eèéEÈÉ]me|partie|unit[eéEÉ]))"
    r"\s*([0-9]+|[IVXLC]+)(?![0-9A-Za-zÀ-ÖØ-öø-ÿ])\s*[:\-–—.)]?\s*(.*)$")
CHAPTER_LABELS = {"chapitre": "Chapitre", "chap": "Chapitre", "ch": "Chapitre", "sequence": "Séquence",
                  "seq": "Séquence", "theme": "Thème", "partie": "Partie", "unite": "Unité"}
NO_CHAPTER = "Sans chapitre"


def fold(s: str) -> str:
    """Same folding as matchSubject: lowercase, NFD, drop U+0300–U+036F."""
    return re.sub("[̀-ͯ]", "", unicodedata.normalize("NFD", s.lower()))


def clean_ws(s: str) -> str:
    s = unicodedata.normalize("NFC", s)
    s = REMOVED_CHARS.sub("", s)
    return WS_RUN.sub(" ", s).strip(" ")


def cut_words(s: str, limit: int, spaces: str = " ") -> str:
    """At most `limit` characters, cut before the last space if the limit
    falls inside a word; hard cut when the first `limit` characters hold no space."""
    if len(s) <= limit:
        return s
    if s[limit] in spaces:
        return s[:limit].rstrip(spaces)
    head = s[:limit]
    cut = max(head.rfind(c) for c in spaces)
    return (head[:cut] if cut > 0 else head).rstrip(spaces)


def strip_ends(s: str) -> str:
    prev = None
    while prev != s:
        prev = s
        s = s.strip(" ")
        s = s.lstrip(QUOTES).rstrip(QUOTES + TRAILING_PUNCT)
    return s


def normalize(raw: str, *, file_name: bool = False) -> str:
    s = raw
    if file_name:
        s = re.sub("_+", " ", s)
        s = re.sub("-{2,}", " ", s)
    s = clean_ws(s)
    for prefix in PREFIXES:
        if s.lower().startswith(prefix.lower()):
            s = s[len(prefix):].lstrip(" ")
    for suffix in SUFFIXES:
        if s.lower().endswith(suffix):
            s = s[: -len(suffix)].rstrip(" ")
    s = strip_ends(s)
    if len(s) > MAX_TITLE:
        s = strip_ends(cut_words(s, MAX_TITLE))
    return s


class Context:
    def __init__(self, subject_names: list[str], file_name: str | None):
        self.subjects = {fold(normalize(n)) for n in subject_names if n and normalize(n)}
        self.file_name = fold(file_name) if file_name else None


def reject_reason(value: str, raw: str, ctx: Context) -> str | None:
    if not value:
        return "empty"
    f = fold(value)
    if NUMBER.match(f) or DATE_NUMERIC.match(f) or DATE_TEXT.match(f):
        return "numberOrDate"
    if sum(1 for c in value if c.isalpha()) < 3:
        return "tooFewLetters"
    if ADMIN.match(f):
        return "adminHeader"
    if GENERIC.match(f):
        return "generic"
    if f in ctx.subjects:
        return "subjectName"
    if ctx.file_name and ctx.file_name in (f, fold(clean_ws(raw))):
        return "fileName"
    return None


def candidate(source: str, raw: str, ctx: Context, detail: str | None = None) -> dict:
    value = normalize(raw, file_name=source == "fileName")
    reason = reject_reason(value, raw, ctx)
    c = {"source": source}
    if detail:
        c["detail"] = detail
    c.update({"raw": raw, "value": value, "accepted": reason is None, "reason": reason})
    return c


def chapter_of(value: str) -> tuple[str, str] | None:
    m = CHAPTER.match(value)
    if not m:
        return None
    key = fold(m.group(1)).rstrip(".")
    return f"{CHAPTER_LABELS[key]} {m.group(2)}", m.group(3)


def valid_rest(rest: str, ctx: Context) -> str | None:
    value = normalize(rest)
    return value if reject_reason(value, rest, ctx) is None else None


def choose_title(cands: list[dict], ctx: Context) -> dict:
    """§5.3 applied to the ordered candidates. Returns title, chapter and the
    index of the candidate that gave each."""
    accepted = [i for i, c in enumerate(cands) if c["accepted"]]
    if not accepted:
        return {"title": None, "chapter": NO_CHAPTER, "titleFrom": None, "chapterFrom": None}
    first = accepted[0]
    found = chapter_of(cands[first]["value"])
    if found:
        chapter, rest = found
        title = valid_rest(rest, ctx)
        title_from = first
        if title is None:
            title_from = None
            for i in accepted[1:]:
                sub = chapter_of(cands[i]["value"])
                if sub is None:
                    title, title_from = cands[i]["value"], i
                    break
                rest_title = valid_rest(sub[1], ctx)
                if rest_title is not None:
                    title, title_from = rest_title, i
                    break
        return {"title": title, "chapter": chapter, "titleFrom": title_from, "chapterFrom": first}
    for i, c in enumerate(cands):
        if c["source"] in ("metadata", "firstHeading", "pronoteTitle") and c["value"]:
            found = chapter_of(c["value"])
            if found:
                return {"title": cands[first]["value"], "chapter": found[0], "titleFrom": first, "chapterFrom": i}
    return {"title": cands[first]["value"], "chapter": NO_CHAPTER, "titleFrom": first, "chapterFrom": None}


def date_fallback(day: str) -> str:
    d = dt.date.fromisoformat(day)
    return f"Cours du {'1er' if d.day == 1 else d.day} {MONTHS[d.month - 1]}"


def file_stem(name: str) -> str:
    m = re.match(r"^(.*)\.[A-Za-z0-9]{1,5}$", name)
    return m.group(1) if m and m.group(1) else name


# ===========================================================================
# 5.1 Type detection
# ===========================================================================

EXTRACTIBLE = ("pdf", "odt", "odp", "docx", "pptx", "txt", "md")
MIME = {"pdf": "application/pdf", "odt": "application/vnd.oasis.opendocument.text",
        "odp": "application/vnd.oasis.opendocument.presentation",
        "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "txt": "text/plain", "md": "text/markdown", "png": "image/png", "jpg": "image/jpeg",
        "jpeg": "image/jpeg", "gif": "image/gif", "heic": "image/heic", "webp": "image/webp"}
ODF_TYPES = {"application/vnd.oasis.opendocument.text": "odt",
             "application/vnd.oasis.opendocument.presentation": "odp"}


def extension(name: str) -> str | None:
    m = re.search(r"\.([A-Za-z0-9]{1,5})$", name)
    return m.group(1).lower() if m else None


def detect_type(name: str, data: bytes) -> tuple[str | None, str | None]:
    ext = extension(name)
    if ext in EXTRACTIBLE:
        return ext, "extension"
    if data.startswith(b"%PDF-"):
        return "pdf", "signature"
    if data.startswith(b"PK\x03\x04"):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                names = set(zf.namelist())
                if "mimetype" in names:
                    kind = ODF_TYPES.get(zf.read("mimetype").decode("ascii", "replace").strip())
                    if kind:
                        return kind, "signature"
                elif "[Content_Types].xml" in names:
                    if "word/document.xml" in names:
                        return "docx", "signature"
                    if "ppt/presentation.xml" in names:
                        return "pptx", "signature"
        except zipfile.BadZipFile:
            pass
    return None, None


def mime_of(name: str, kind: str | None) -> str:
    return MIME.get(kind or extension(name) or "", "application/octet-stream")


# ===========================================================================
# PDF (minimal reader: enough for the vectors, not a general parser)
# ===========================================================================

class PdfUnreadable(Exception):
    pass


class Name(str):
    pass


class Ref(int):
    pass


class Op(str):
    pass


DELIMS = b"()<>[]{}/%"
WS_BYTES = b"\x00\t\n\x0c\r "


class Lexer:
    def __init__(self, data: bytes, pos: int = 0):
        self.d, self.p = data, pos

    def skip(self) -> None:
        d = self.d
        while self.p < len(d):
            if d[self.p] in WS_BYTES:
                self.p += 1
            elif d[self.p] == 0x25:  # % comment
                while self.p < len(d) and d[self.p] not in b"\r\n":
                    self.p += 1
            else:
                break

    def token(self):
        """Returns one object or operator, or None at end of data."""
        self.skip()
        d = self.d
        if self.p >= len(d):
            return None
        c = d[self.p]
        if d.startswith(b"<<", self.p):
            self.p += 2
            out = {}
            while True:
                self.skip()
                if d.startswith(b">>", self.p):
                    self.p += 2
                    return out
                key = self.token()
                if not isinstance(key, Name):
                    raise PdfUnreadable("bad dict")
                out[str(key)] = self.value()
        if c == 0x3C:  # <hex>
            end = d.index(b">", self.p)
            hexs = re.sub(rb"\s", b"", d[self.p + 1:end])
            self.p = end + 1
            if len(hexs) % 2:
                hexs += b"0"
            return bytes.fromhex(hexs.decode("ascii"))
        if c == 0x28:
            return self.literal()
        if c == 0x2F:
            self.p += 1
            start = self.p
            while self.p < len(d) and d[self.p] not in WS_BYTES and d[self.p] not in DELIMS:
                self.p += 1
            raw = re.sub(rb"#([0-9A-Fa-f]{2})", lambda m: bytes([int(m.group(1), 16)]), d[start:self.p])
            return Name(raw.decode("latin-1"))
        if c == 0x5B:
            self.p += 1
            out = []
            while True:
                self.skip()
                if self.p >= len(d):
                    raise PdfUnreadable("unterminated array")
                if d[self.p] == 0x5D:
                    self.p += 1
                    return out
                out.append(self.value())
        if c in (0x5D, 0x3E, 0x7B, 0x7D, 0x29):
            self.p += 1
            return Op(chr(c))
        start = self.p
        while self.p < len(d) and d[self.p] not in WS_BYTES and d[self.p] not in DELIMS:
            self.p += 1
        word = d[start:self.p].decode("latin-1")
        if re.fullmatch(r"[+-]?(\d+\.?\d*|\.\d+)", word):
            return float(word) if "." in word else int(word)
        return {"true": True, "false": False, "null": None}.get(word, Op(word))

    def value(self):
        """Like token(), but folds `n g R` into a Ref."""
        v = self.token()
        if isinstance(v, int) and not isinstance(v, bool):
            save = self.p
            g = self.token()
            if isinstance(g, int) and not isinstance(g, bool):
                r = self.token()
                if r == "R":
                    return Ref(v)
            self.p = save
        return v

    def literal(self) -> bytes:
        d = self.d
        self.p += 1
        depth, out = 1, bytearray()
        escapes = {ord("n"): 10, ord("r"): 13, ord("t"): 9, ord("b"): 8, ord("f"): 12}
        while self.p < len(d):
            c = d[self.p]
            self.p += 1
            if c == 0x5C:
                e = d[self.p]
                self.p += 1
                if e in escapes:
                    out.append(escapes[e])
                elif 0x30 <= e <= 0x37:
                    digits = bytes([e])
                    while len(digits) < 3 and 0x30 <= d[self.p] <= 0x37:
                        digits += bytes([d[self.p]])
                        self.p += 1
                    out.append(int(digits, 8) & 0xFF)
                elif e in b"\r\n":
                    if e == 0x0D and d[self.p] == 0x0A:
                        self.p += 1
                else:
                    out.append(e)
            elif c == 0x28:
                depth += 1
                out.append(c)
            elif c == 0x29:
                depth -= 1
                if depth == 0:
                    return bytes(out)
                out.append(c)
            else:
                out.append(c)
        raise PdfUnreadable("unterminated string")


class Pdf:
    def __init__(self, data: bytes):
        if not data.startswith(b"%PDF-"):
            raise PdfUnreadable("signature")
        self.data = data
        self.objects: dict[int, object] = {}
        for m in re.finditer(rb"(?<![0-9])(\d+)\s+(\d+)\s+obj\b", data):
            try:
                lx = Lexer(data, m.end())
                val = lx.value()
                lx.skip()
                if isinstance(val, dict) and data.startswith(b"stream", lx.p):
                    start = lx.p + 6
                    start += 2 if data.startswith(b"\r\n", start) else 1
                    length = self.resolve(val.get("Length"))
                    val = {"dict": val, "stream": data[start:start + int(length)]}
                self.objects[int(m.group(1))] = val
            except (PdfUnreadable, ValueError, IndexError, TypeError):
                continue
        pos = data.rfind(b"trailer")
        if pos < 0 or not self.objects:
            raise PdfUnreadable("no trailer")
        try:
            self.trailer = Lexer(data, pos + 7).value()
        except (PdfUnreadable, ValueError, IndexError) as exc:
            raise PdfUnreadable("bad trailer") from exc
        if not isinstance(self.trailer, dict):
            raise PdfUnreadable("bad trailer")
        if "Encrypt" in self.trailer:
            raise PdfUnreadable("encrypted")

    def resolve(self, v):
        seen = 0
        while isinstance(v, Ref) and seen < 32:
            v, seen = self.objects.get(int(v)), seen + 1
        return v

    def stream_data(self, obj) -> bytes:
        obj = self.resolve(obj)
        if not isinstance(obj, dict) or "stream" not in obj:
            raise PdfUnreadable("not a stream")
        filters = self.resolve(obj["dict"].get("Filter"))
        filters = [filters] if isinstance(filters, Name) else (filters or [])
        data = obj["stream"]
        for f in filters:
            if f != "FlateDecode":
                raise PdfUnreadable(f"filter {f}")
            data = zlib.decompress(data)
        return data

    def info_title(self) -> str | None:
        info = self.resolve(self.trailer.get("Info"))
        if isinstance(info, dict):
            t = self.resolve(info.get("Title"))
            if isinstance(t, bytes):
                return pdf_text_string(t)
        return None

    def xmp_title(self) -> str | None:
        root = self.resolve(self.trailer.get("Root"))
        if not isinstance(root, dict) or "Metadata" not in root:
            return None
        xml = self.stream_data(root["Metadata"]).decode("utf-8", "replace")
        m = re.search(r"<dc:title\b[^>]*>(.*?)</dc:title>", xml, re.S)
        if not m:
            return None
        items = re.findall(r"<rdf:li\b([^>]*)>(.*?)</rdf:li>", m.group(1), re.S)
        if not items:
            return None
        chosen = next((t for attrs, t in items if 'x-default' in attrs), items[0][1])
        return xml_unescape(chosen)

    def first_page(self):
        node = self.resolve(self.resolve(self.trailer.get("Root")).get("Pages"))
        for _ in range(32):
            if not isinstance(node, dict):
                break
            if node.get("Type") == "Page":
                return node
            kids = self.resolve(node.get("Kids")) or []
            if not kids:
                break
            node = self.resolve(kids[0])
        raise PdfUnreadable("no page")

    def first_page_lines(self) -> list[dict]:
        page = self.first_page()
        contents = self.resolve(page.get("Contents"))
        parts = contents if isinstance(contents, list) else [page.get("Contents")]
        data = b"\n".join(self.stream_data(p) for p in parts if p is not None)
        return content_lines(data)


def pdf_text_string(b: bytes) -> str:
    if b.startswith(b"\xfe\xff"):
        return b[2:].decode("utf-16-be", "replace")
    if b.startswith(b"\xef\xbb\xbf"):
        return b[3:].decode("utf-8", "replace")
    return b.decode("latin-1")  # PDFDocEncoding: identical to Latin-1 for the bytes used here


def xml_unescape(s: str) -> str:
    return (s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'")
            .replace("&amp;", "&"))


def content_lines(data: bytes) -> list[dict]:
    """Text runs of a content stream grouped into lines (same baseline y),
    in stream order. Font size = Tf size × vertical scale of Tm."""
    lx = Lexer(data)
    stack: list = []
    size, leading = 0.0, 0.0
    tm = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]
    tlm = tm[:]
    runs: list[tuple[float, float, str]] = []

    def move(tx: float, ty: float) -> None:
        nonlocal tm, tlm
        a, b, c, d, e, f = tlm
        tlm = [a, b, c, d, tx * a + ty * c + e, tx * b + ty * d + f]
        tm = tlm[:]

    def show(s: bytes) -> None:
        eff = size * math.hypot(tm[2], tm[3])
        runs.append((tm[5], eff, s.decode("cp1252", "replace")))

    while True:
        t = lx.token()
        if t is None:
            break
        if not isinstance(t, Op):
            stack.append(t)
            continue
        if t == "BT":
            tm, tlm = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0], [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]
        elif t == "Tf" and len(stack) >= 2:
            size = float(stack[-1])
        elif t == "TL" and stack:
            leading = float(stack[-1])
        elif t == "Td" and len(stack) >= 2:
            move(float(stack[-2]), float(stack[-1]))
        elif t == "TD" and len(stack) >= 2:
            leading = -float(stack[-1])
            move(float(stack[-2]), float(stack[-1]))
        elif t == "Tm" and len(stack) >= 6:
            tm = [float(x) for x in stack[-6:]]
            tlm = tm[:]
        elif t == "T*":
            move(0, -leading)
        elif t == "Tj" and stack and isinstance(stack[-1], bytes):
            show(stack[-1])
        elif t == "TJ" and stack and isinstance(stack[-1], list):
            show(b"".join(x for x in stack[-1] if isinstance(x, bytes)))
        elif t in ("'", '"') and stack and isinstance(stack[-1], bytes):
            move(0, -leading)
            show(stack[-1])
        stack.clear()

    lines: list[dict] = []
    for y, sz, text in runs:
        if lines and abs(lines[-1]["y"] - y) < 1:
            lines[-1]["text"] += text
            lines[-1]["size"] = max(lines[-1]["size"], sz)
        else:
            lines.append({"y": y, "size": sz, "text": text})
    out = []
    for line in lines:
        text = clean_ws(line["text"])
        if text:
            out.append({"text": text, "size": round(line["size"], 2)})
    return out


# ===========================================================================
# ODF / OOXML
# ===========================================================================

NS = {
    "office": "urn:oasis:names:tc:opendocument:xmlns:office:1.0",
    "style": "urn:oasis:names:tc:opendocument:xmlns:style:1.0",
    "text": "urn:oasis:names:tc:opendocument:xmlns:text:1.0",
    "table": "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
    "draw": "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0",
    "presentation": "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0",
    "dc": "http://purl.org/dc/elements/1.1/",
    "w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
    "p": "http://schemas.openxmlformats.org/presentationml/2006/main",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
    "rel": "http://schemas.openxmlformats.org/package/2006/relationships",
    "cp": "http://schemas.openxmlformats.org/package/2006/metadata/core-properties",
}


def q(prefix_local: str) -> str:
    prefix, local = prefix_local.split(":")
    return f"{{{NS[prefix]}}}{local}"


def block(kind: str, text: str, level: int | None = None) -> dict | None:
    text = clean_ws(text)
    if not text:
        return None
    b = {"kind": kind}
    if level is not None:
        b["level"] = level
    b["text"] = text
    return b


def dc_title(xml: bytes) -> str | None:
    el = ET.fromstring(xml).find(".//" + q("dc:title"))
    return el.text or "" if el is not None else None


# ---- ODF -------------------------------------------------------------------

def odf_text(el) -> str:
    parts: list[str] = []

    def walk(e) -> None:
        if e.text:
            parts.append(e.text)
        for c in e:
            if c.tag == q("text:s"):
                parts.append(" " * int(c.get(q("text:c"), "1")))
            elif c.tag in (q("text:tab"), q("text:line-break")):
                parts.append(" ")
            elif c.tag in (q("text:note"), q("office:annotation")):
                pass
            else:
                walk(c)
            if c.tail:
                parts.append(c.tail)

    walk(el)
    return "".join(parts)


def odf_title_styles(root) -> set[str]:
    """Paragraph style names that resolve to Title/Titre, through the
    style:parent-style-name of automatic styles (content.xml)."""
    names = {"title", "titre"}
    auto = root.find(q("office:automatic-styles"))
    out = set()
    if auto is not None:
        for st in auto.findall(q("style:style")):
            parent = (st.get(q("style:parent-style-name")) or "").lower()
            if parent in names:
                out.add(st.get(q("style:name")))
    return out


def is_title_paragraph(p, title_auto: set[str]) -> bool:
    style = p.get(q("text:style-name")) or ""
    return style.lower() in ("title", "titre") or style in title_auto


def odf_blocks(container, title_auto: set[str], depth: int = 0) -> list[dict]:
    out: list[dict] = []
    for c in container:
        b = None
        if c.tag == q("text:h"):
            b = block("heading", odf_text(c), int(c.get(q("text:outline-level"), "1")))
        elif c.tag == q("text:p"):
            if depth:
                b = block("listItem", odf_text(c), depth)
            elif is_title_paragraph(c, title_auto):
                b = block("heading", odf_text(c), 1)
            else:
                b = block("paragraph", odf_text(c))
        elif c.tag == q("text:list"):
            for item in c:
                if item.tag in (q("text:list-item"), q("text:list-header")):
                    out += odf_blocks(item, title_auto, depth + 1)
        elif c.tag in (q("text:section"), q("table:table"), q("table:table-row"), q("table:table-cell"),
                       q("table:table-rows"), q("table:table-header-rows")):
            out += odf_blocks(c, title_auto, depth)
        if b:
            out.append(b)
    return out


def extract_odt(zf: zipfile.ZipFile) -> dict:
    root = ET.fromstring(zf.read("content.xml"))
    body = root.find(q("office:body")).find(q("office:text"))
    title_auto = odf_title_styles(root)
    headings = list(body.iter(q("text:h")))
    min_level = min((int(h.get(q("text:outline-level"), "1")) for h in headings if clean_ws(odf_text(h))),
                    default=None)
    first = None
    for el in body.iter():
        if el.tag == q("text:h") and int(el.get(q("text:outline-level"), "1")) == min_level \
                or el.tag == q("text:p") and is_title_paragraph(el, title_auto):
            text = odf_text(el)
            if clean_ws(text):
                first = text
                break
    return {"metadata": meta_title(zf, "meta.xml"), "firstHeading": first,
            "readingView": odf_blocks(body, title_auto)}


def extract_odp(zf: zipfile.ZipFile) -> dict:
    root = ET.fromstring(zf.read("content.xml"))
    pres = root.find(q("office:body")).find(q("office:presentation"))
    title_auto = odf_title_styles(root)
    first = None
    blocks: list[dict] = []
    for i, page in enumerate(pres.findall(q("draw:page"))):
        for frame in page.findall(q("draw:frame")):
            cls = frame.get(q("presentation:class"))
            box = frame.find(q("draw:text-box"))
            if box is None:
                continue
            if cls == "title":
                text = " ".join(odf_text(p) for p in box.iter(q("text:p")))
                if i == 0 and first is None:
                    first = text
                b = block("heading", text, 1)
                if b:
                    blocks.append(b)
            else:
                blocks += odf_blocks(box, title_auto)
    return {"metadata": meta_title(zf, "meta.xml"), "firstHeading": first, "readingView": blocks}


def meta_title(zf: zipfile.ZipFile, path: str) -> str | None:
    try:
        return dc_title(zf.read(path))
    except KeyError:
        return None


# ---- DOCX ------------------------------------------------------------------

DOCX_TITLE_STYLES = ("title", "titre", "heading1", "titre1")
SKIP_W = ("w:del", "w:moveFrom", "w:instrText", "w:delText")


def w_text(p) -> str:
    parts: list[str] = []
    skip = {q(t) for t in SKIP_W}

    def walk(e) -> None:
        for c in e:
            if c.tag in skip:
                continue
            if c.tag == q("w:t"):
                parts.append(c.text or "")
            elif c.tag in (q("w:tab"), q("w:br"), q("w:cr")):
                parts.append(" ")
            else:
                walk(c)

    walk(p)
    return "".join(parts)


def w_style(p) -> str:
    st = p.find(q("w:pPr") + "/" + q("w:pStyle"))
    return (st.get(q("w:val")) or "") if st is not None else ""


def docx_blocks(container) -> list[dict]:
    out: list[dict] = []
    for c in container:
        if c.tag == q("w:p"):
            style = w_style(c)
            text = w_text(c)
            m = re.fullmatch(r"(?i)(heading|titre)([1-9])", style)
            num = c.find(q("w:pPr") + "/" + q("w:numPr"))
            if style.lower() in ("title", "titre"):
                b = block("heading", text, 1)
            elif m:
                b = block("heading", text, int(m.group(2)))
            elif num is not None:
                ilvl = num.find(q("w:ilvl"))
                b = block("listItem", text, int(ilvl.get(q("w:val"), "0")) + 1 if ilvl is not None else 1)
            else:
                b = block("paragraph", text)
            if b:
                out.append(b)
        elif c.tag in (q("w:tbl"), q("w:tr"), q("w:tc"), q("w:sdt"), q("w:sdtContent")):
            out += docx_blocks(c)
    return out


def extract_docx(zf: zipfile.ZipFile) -> dict:
    body = ET.fromstring(zf.read("word/document.xml")).find(q("w:body"))
    first = None
    for p in body.iter(q("w:p")):
        if w_style(p).lower() in DOCX_TITLE_STYLES and clean_ws(w_text(p)):
            first = w_text(p)
            break
    return {"metadata": meta_title(zf, "docProps/core.xml"), "firstHeading": first,
            "readingView": docx_blocks(body)}


# ---- PPTX ------------------------------------------------------------------

def a_paragraphs(sp) -> list[tuple[int, bool, str]]:
    out = []
    body = sp.find(q("p:txBody"))
    if body is None:
        return out
    for para in body.findall(q("a:p")):
        ppr = para.find(q("a:pPr"))
        lvl = int(ppr.get("lvl", "0")) if ppr is not None else 0
        bu_none = ppr is not None and ppr.find(q("a:buNone")) is not None
        parts = []
        for c in para:
            if c.tag in (q("a:r"), q("a:fld")):
                t = c.find(q("a:t"))
                parts.append(t.text or "" if t is not None else "")
            elif c.tag == q("a:br"):
                parts.append(" ")
        out.append((lvl, bu_none, "".join(parts)))
    return out


def slide_paths(zf: zipfile.ZipFile) -> list[str]:
    pres = ET.fromstring(zf.read("ppt/presentation.xml"))
    rels = ET.fromstring(zf.read("ppt/_rels/presentation.xml.rels"))
    targets = {r.get("Id"): r.get("Target") for r in rels.findall(q("rel:Relationship"))}
    out = []
    lst = pres.find(q("p:sldIdLst"))
    for sid in (lst if lst is not None else []):
        target = targets.get(sid.get(q("r:id")), "")
        out.append(target.lstrip("/") if target.startswith("/") else "ppt/" + target)
    return out


def extract_pptx(zf: zipfile.ZipFile) -> dict:
    first = None
    blocks: list[dict] = []
    for i, path in enumerate(slide_paths(zf)):
        tree = ET.fromstring(zf.read(path)).find(q("p:cSld") + "/" + q("p:spTree"))
        for sp in tree.iter(q("p:sp")):
            ph = sp.find(q("p:nvSpPr") + "/" + q("p:nvPr") + "/" + q("p:ph"))
            kind = None if ph is None else ph.get("type", "obj")
            paras = a_paragraphs(sp)
            if kind in ("title", "ctrTitle"):
                text = " ".join(t for _, _, t in paras)
                if i == 0 and first is None:
                    first = text
                b = block("heading", text, 1)
                if b:
                    blocks.append(b)
                continue
            for lvl, bu_none, t in paras:
                b = block("listItem", t, lvl + 1) if kind in ("body", "obj") and not bu_none \
                    else block("paragraph", t)
                if b:
                    blocks.append(b)
    return {"metadata": meta_title(zf, "docProps/core.xml"), "firstHeading": first, "readingView": blocks}


# ---- TXT / MD --------------------------------------------------------------

def decode_text(data: bytes) -> str:
    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("cp1252", "replace")


def extract_text(data: bytes, markdown: bool) -> dict:
    for line in re.split(r"\r\n|\r|\n", decode_text(data)):
        if markdown:
            line = re.sub(r"^\s{0,3}#{1,6}(?=\s|$)", "", line)
        if clean_ws(line):
            return {"metadata": None, "firstHeading": line, "readingView": None}
    return {"metadata": None, "firstHeading": None, "readingView": None}


# ---- Dispatcher -------------------------------------------------------------

def extract(kind: str | None, data: bytes) -> dict:
    """{metadata, metadataDetail, firstHeading | pdfLines, readingView, error}"""
    out = {"metadata": None, "metadataDetail": None, "firstHeading": None, "pdfLines": None,
           "readingView": None, "error": None}
    if kind is None:
        return out
    try:
        if kind == "pdf":
            pdf = Pdf(data)
            title = pdf.info_title()
            if title is not None and clean_ws(title):
                out["metadata"], out["metadataDetail"] = title, "pdfInfoTitle"
            else:
                xmp = pdf.xmp_title()
                if xmp is not None:
                    out["metadata"], out["metadataDetail"] = xmp, "xmpDcTitle"
            out["pdfLines"] = pdf.first_page_lines()
        elif kind in ("txt", "md"):
            out.update(extract_text(data, kind == "md"))
        else:
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                res = {"odt": extract_odt, "odp": extract_odp, "docx": extract_docx, "pptx": extract_pptx}[kind](zf)
            out.update(res)
            out["metadataDetail"] = {"odt": "metaXmlDcTitle", "odp": "metaXmlDcTitle"}.get(kind, "coreXmlDcTitle") \
                if res["metadata"] is not None else None
    except (PdfUnreadable, zipfile.BadZipFile, KeyError, ET.ParseError, zlib.error, ValueError, AttributeError) as exc:
        out.update({"metadata": None, "metadataDetail": None, "firstHeading": None, "pdfLines": None,
                    "readingView": None, "error": "unreadable"})
        out["errorDetail"] = str(exc) if isinstance(exc, PdfUnreadable) else type(exc).__name__
    return out


def pdf_heading(lines: list[dict], ctx: Context) -> str | None:
    """First line, by decreasing font size then stream order, that is not
    rejected. If every line is rejected, the first of that order (rejected)."""
    ordered = sorted(lines, key=lambda line: -line["size"])  # stable
    for line in ordered:
        if candidate("firstHeading", line["text"], ctx)["accepted"]:
            return line["text"]
    return ordered[0]["text"] if ordered else None


def document_candidates(ext: dict, ctx: Context) -> list[dict]:
    cands = []
    if ext["metadata"] is not None:
        cands.append(candidate("metadata", ext["metadata"], ctx, ext["metadataDetail"]))
    heading = pdf_heading(ext["pdfLines"], ctx) if ext["pdfLines"] is not None else ext["firstHeading"]
    if heading is not None:
        cands.append(candidate("firstHeading", heading, ctx))
    return cands


def title_pipeline(principal: dict | None, content_title: str | None, subject_names: list[str],
                   session_date: str) -> dict:
    """principal: {name, kind, extraction} or None."""
    ctx = Context(subject_names, principal["name"] if principal else None)
    cands = document_candidates(principal["extraction"], ctx) if principal else []
    if content_title is not None:
        cands.append(candidate("pronoteTitle", content_title, ctx))
    if principal:
        cands.append(candidate("fileName", file_stem(principal["name"]), ctx))
    fallback = date_fallback(session_date)
    cands.append({"source": "dateFallback", "raw": fallback, "value": fallback, "accepted": True, "reason": None})
    choice = choose_title(cands, ctx)
    return {"candidates": cands, **choice}


def extracted_title(extraction: dict, name: str, subject_names: list[str]) -> str | None:
    """Course.documents[].extractedTitle: §5.2–5.3 limited to the document's
    own candidates (metadata, first heading)."""
    ctx = Context(subject_names, name)
    cands = document_candidates(extraction, ctx)
    return choose_title(cands, ctx)["title"] if cands else None


# ===========================================================================
# document-title-vectors.json
# ===========================================================================

SUBJECTS_INPUT = {
    "maths": ("MATHEMATIQUES", "Mathématiques"),
    "hg": ("HISTOIRE-GEOGRAPHIE", "Histoire-géographie"),
    "svt": ("S.V.T.", "S.V.T."),
    "pc": ("PHYSIQUE-CHIMIE", "Physique-chimie"),
    "fr": ("FRANCAIS", "Français"),
    "techno": ("TECHNOLOGIE", "Technologie"),
    "musique": ("MUSIQUE", "Musique"),
}

DOC_CASES = [
    # (id, file, subject, contentTitle, sessionDate, note)
    ("pdf-info-title", "pdf-info-title.pdf", "maths", None, None, "PDF /Info /Title (PDFDocEncoding)"),
    ("pdf-word-title", "pdf-word-title.pdf", "hg", None, None,
     "PDF /Title UTF-16BE « Microsoft Word - … .docx » : préfixe et suffixe retirés, chapitre"),
    ("pdf-xmp-title", "pdf-xmp-title.pdf", "svt", None, None, "PDF sans /Title, dc:title XMP"),
    ("pdf-plain-content", "pdf-plain-content.pdf", "svt", None, None,
     "PDF sans métadonnées, flux non compressé : grande ligne puis en-tête « Nom : »"),
    ("pdf-flate-content", "pdf-flate-content.pdf", "svt", None, None,
     "PDF FlateDecode : en-têtes « Nom : » / « Classe : » d’abord, titre en grand (TJ, tiret demi-cadratin)"),
    ("pdf-generic-title", "pdf-generic-title.pdf", "maths", None, None,
     "PDF /Title « Document1 » rejeté (générique)"),
    ("pdf-title-is-file-name", "fiche_exo_3.pdf", "maths", None, None,
     "PDF /Title égal au nom du fichier, page vide : repli sur le nom du fichier"),
    ("pdf-encrypted", "pdf-encrypted.pdf", "musique", "Le rythme et la pulsation", None,
     "PDF chiffré (RC4, mot de passe utilisateur) : replis, titre Pronote"),
    ("pdf-unreadable", "controle_SVT__version_finale.pdf", "svt", "S.V.T.", None,
     "PDF illisible ; titre Pronote = matière (rejeté) ; nom de fichier avec `__`"),
    ("odt-meta-title", "odt-meta-title.odt", "maths", "Exercices", None,
     "ODT meta.xml dc:title ; chapitre trouvé sur le premier titre"),
    ("odt-heading", "odt-heading.odt", "maths", None, None,
     "ODT sans dc:title, premier text:h « Chapitre 3 : … » ; text:s, liens, listes imbriquées"),
    ("odt-title-style", "odt-title-style.odt", "hg", None, None,
     "ODT paragraphe de style automatique P1 hérité de Title"),
    ("odt-heading-levels", "odt-heading-levels.odt", "fr", None, None,
     "ODT : un titre de niveau 2 précède le niveau 1 ; « Séquence IV – »"),
    ("signature-odt-as-bin", "chapitre-trompeur.bin", "maths", None, None,
     "Paquet ODT nommé .bin : type détecté par la signature ; « Partie 3 : »"),
    ("odp-slide-title", "odp-slide-title.odp", "hg", None, None,
     "ODP : dc:title « Présentation1 » rejeté, titre de la première diapositive"),
    ("docx-core-title", "docx-core-title.docx", "svt", None, None,
     "DOCX core.xml dc:title entre guillemets ; chapitre trouvé sur Heading1"),
    ("docx-heading1", "docx-heading1.docx", "maths", "Exercices", None,
     "DOCX dc:title = matière (rejeté) ; Heading2 ignoré ; Heading1 en deux runs ; w:del ignoré"),
    ("docx-title-style", "docx-title-style.docx", "hg", None, None, "DOCX style Title"),
    ("pptx-ctrtitle", "pptx-ctrtitle.pptx", "pc", None, None, "PPTX ctrTitle de la première diapositive"),
    ("pptx-theme-empty", "pptx-theme-empty.pptx", "svt", "La Terre dans le système solaire", None,
     "PPTX : ordre de sldIdLst ≠ noms de fichiers ; « Thème 1 : » sans reste → titre Pronote"),
    ("txt-chap-abbrev", "notes.txt", "maths", None, None, "TXT avec BOM et lignes vides ; « CHAP. 2) »"),
    ("txt-admin-header", "fiche-eleve.txt", "maths", "Les angles", None,
     "TXT dont la première ligne non vide est un en-tête « Nom : » (rejeté) : titre Pronote"),
    ("txt-long-title", "long-title.txt", "svt", None, None, "Titre > 120 caractères coupé au mot"),
    ("txt-windows-1252", "ancien-encodage.txt", "fr", None, None, "TXT Windows-1252, CRLF ; « Unité 3 : »"),
    ("txt-theme-dash", "techno.txt", "techno", None, None, "TXT « Thème 2 – … »"),
    ("md-heading", "fiche.md", "fr", None, None, "MD « # Titre : » : # et ponctuation finale retirés"),
    ("empty-file", "Sans titre.txt", "fr", None, "2026-11-01",
     "Fichier vide ; nom générique ; repli « Cours du 1er novembre »"),
    ("not-extractible-png", "schema.png", "svt", "Mercredi 18 novembre", None,
     "PNG : pas de document principal ; titre Pronote = date (rejeté)"),
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_name(cands: list[dict], i: int | None) -> str | None:
    return None if i is None else cands[i]["source"]


def build_document_vectors() -> dict:
    cases = []
    for cid, file, subj, content_title, day, note in DOC_CASES:
        data = (FILES / file).read_bytes()
        label, name = SUBJECTS_INPUT[subj]
        day = day or "2026-11-18"
        kind, by = detect_type(file, data)
        extraction = extract(kind, data)
        principal = {"name": file, "kind": kind, "extraction": extraction} if kind else None
        result = title_pipeline(principal, content_title, [label, name], day)
        case = {
            "id": cid,
            "note": note,
            "file": f"files/{file}",
            "size": len(data),
            "sha256": sha256(data),
            "input": {"fileName": file, "sessionDate": day, "contentTitle": content_title,
                      "subjectLabel": label, "subjectName": name},
            "detectedType": kind,
            "detectedBy": by,
            "mime": mime_of(file, kind),
            "extraction": {"error": extraction["error"], "metadata": extraction["metadata"],
                           "metadataDetail": extraction["metadataDetail"]},
            "candidates": result["candidates"],
            "expected": {"title": result["title"], "chapter": result["chapter"],
                         "titleSource": source_name(result["candidates"], result["titleFrom"]),
                         "chapterSource": source_name(result["candidates"], result["chapterFrom"]),
                         "extractedTitle": extracted_title(extraction, file, [label, name]) if kind else None},
            "readingView": extraction["readingView"] if kind in ("odt", "odp", "docx", "pptx") else None,
        }
        if extraction["pdfLines"] is not None:
            case["extraction"]["pdfLines"] = extraction["pdfLines"]
        cases.append(case)
    return {
        "schemaVersion": 1,
        "generator": "native/shared/documents/reference.py (documents: make_documents.py)",
        "regenerate": "python3 native/shared/documents/make_documents.py && python3 native/shared/documents/reference.py",
        "spec": "native/shared/notebook-to-course.md §5 et §6, README.md de ce dossier",
        "containsPersonalData": False,
        "rules": RULES,
        "cases": cases,
    }


RULES = {
    "extractibleTypes": list(EXTRACTIBLE),
    "mime": MIME,
    "whitespace": [f"U+{ord(c):04X}" for c in WHITESPACE],
    "removedCharacters": "U+0000–U+0008, U+000E–U+001F, U+007F–U+009F, U+200E, U+200F, U+202A–U+202E, "
                         "U+2066–U+2069, U+FEFF",
    "prefixes": list(PREFIXES),
    "suffixes": list(SUFFIXES),
    "quotes": QUOTES,
    "trailingPunctuation": TRAILING_PUNCT,
    "maxTitleLength": MAX_TITLE,
    "maxDescriptionLength": MAX_DESCRIPTION,
    "fold": "lowercase, NFD, remove U+0300–U+036F (as matchSubject)",
    "rejectionOrder": ["empty", "numberOrDate", "tooFewLetters", "adminHeader", "generic", "subjectName",
                       "fileName"],
    "numberPattern": NUMBER.pattern,
    "datePatterns": [DATE_NUMERIC.pattern, DATE_TEXT.pattern],
    "adminHeaderPattern": ADMIN.pattern,
    "genericPattern": GENERIC.pattern,
    "chapterPattern": CHAPTER.pattern,
    "chapterLabels": CHAPTER_LABELS,
    "noChapter": NO_CHAPTER,
    "dateFallback": "Cours du <jour> <mois> (jour 1 → « 1er »)",
    "months": MONTHS,
}


# ===========================================================================
# matchSubject (port of src/lib/icalParser.ts, see domain/README.md)
# ===========================================================================

EPS = re.compile(r"\beps\b|sport|ed\.?\s*physique|education physique", re.ASCII)
ALIASES = {
    "maths": ("math", "mathematiques"), "svt": ("s.v.t", "svt", "sciences de la vie", "sciences vie"),
    "physique-chimie": ("physique", "chimie", "spc"), "histoire-geo": ("histoire", "geo", "emc", "h-g"),
    "francais": ("francais",), "anglais": ("anglais",), "espagnol": ("espagnol",),
    "techno": ("techno", "technologie"), "arts-plastiques": ("arts", "plastique"), "musique": ("musique", "chant"),
}


def match_subject(summary: str, subjects: list[dict]) -> dict | None:
    s = fold(summary)
    if EPS.search(s):
        return next((x for x in subjects if x["id"] == "eps"), None)
    for subj in subjects:
        if fold(subj["name"]) in s or any(a in s for a in ALIASES.get(subj["id"], ())):
            return subj
    return None


def check_match_subject(subjects_sets: dict) -> None:
    vectors = json.loads((SHARED / "domain" / "domain-vectors.json").read_text("utf-8"))
    for case in vectors["suites"]["matchSubject"]:
        got = match_subject(case["input"]["summary"], vectors["subjectSets"][case["input"]["subjects"]])
        assert (got or {}).get("id") == case["expectedSubjectId"], case


# ===========================================================================
# notebook-course-vectors.json (§1–§5)
# ===========================================================================

TODAY = "2026-11-18"
BACKFILL_DAYS = 14
MAX_FILES = 10


def att(aid: str, name: str, fixture: str | None = None, error: str | None = None) -> dict:
    a = {"kind": "file", "id": aid, "name": name}
    if fixture:
        a["fixture"] = f"files/{fixture}"
    if error:
        a["downloadError"] = error
    return a


def content(title: str, description: str = "", attachments: list | None = None, category: str = "",
            links: list | None = None) -> dict:
    return {"title": title, "description": description, "links": links or [], "category": category,
            "attachments": attachments or []}


LONG_ES = " ".join(["La casa tiene una cocina, un salón y dos dormitorios."] * 45)

SESSIONS = [
    {"id": "cdt-20", "lessonId": "cours-maths", "date": "2026-11-20", "subject": "MATHEMATIQUES",
     "contents": [content("Chapitre 4 : Les fractions", "Préparer la leçon.",
                          [att("pj-20", "Les fractions.odt", "odt-meta-title.odt")], "Cours")]},
    {"id": "cdt-18a", "lessonId": "cours-hg", "date": "2026-11-18", "subject": "HISTOIRE-GEOGRAPHIE",
     "contents": [content("La Révolution française", "Lire le document 2 p. 112.",
                          [att("pj-18a", "Chapitre 2.pdf", "pdf-word-title.pdf")], "Cours")]},
    {"id": "cours:cours-svt@2026-11-18", "lessonId": "cours-svt", "date": "2026-11-18",
     "subject": "SCIENCES VIE & TERRE",
     "contents": [content("", "Observer les lames au microscope.",
                          [{"kind": "link", "id": "pj-18b", "name": "Vidéo",
                            "url": "https://video.exemple.fr/microscope"}])]},
    {"id": "cdt-17", "lessonId": "cours-maths", "date": "2026-11-17", "subject": "MATHEMATIQUES",
     "contents": [content("Exercices", "Ex. 12 et 13 p. 45",
                          [att("pj-17a", "Photo du tableau.png", "schema.png"),
                           att("pj-17b", "Cours fonctions.odt", "odt-heading.odt")], "Exercices"),
                  content("Correction")]},
    {"id": "cdt-16", "lessonId": "cours-ang", "date": "2026-11-16", "subject": "ANGLAIS LV1",
     "contents": [content("Daily routine", "Learn the vocabulary list.")]},
    {"id": "cdt-13", "lessonId": "cours-pc", "date": "2026-11-13", "subject": "PHYSIQUE-CHIMIE",
     "contents": [content("Les états de la matière", "",
                          [att("pj-13", "Etats.pptx", "pptx-ctrtitle.pptx")])]},
    {"id": "cdt-13b", "lessonId": "cours-eps", "date": "2026-11-13", "subject": "ED.PHYSIQUE & SPORT.",
     "contents": [content("Course d’endurance", "Apporter une tenue de sport.")]},
    {"id": "cdt-12", "lessonId": "cours-fr", "date": "2026-11-12", "subject": "FRANCAIS",
     "contents": [content("Cours", "Relire les exemples.",
                          [att("pj-12a", "Cours complet.pdf", error="tooLarge"),
                           att("pj-12b", "Fiche.md", "fiche.md")], "Cours"),
                  content("Exercices", "Trouver trois métaphores.\n• dans le poème\n• dans la chanson")]},
    {"id": "cdt-12b", "lessonId": "cours-techno", "date": "2026-11-12", "subject": "TECHNOLOGIE",
     "contents": [content("TP", "", [att("pj-12b-0", "Programme.txt", error="unavailable")]
                          + [att(f"pj-12b-{i}", f"Fiche {i}.txt", "techno.txt") for i in range(1, 12)])]},
    {"id": "cdt-10", "lessonId": "cours-esp", "date": "2026-11-10", "subject": "ESPAGNOL LV2",
     "contents": [content("La casa", LONG_ES)]},
    {"id": "cdt-09", "lessonId": "cours-vie", "date": "2026-11-09", "subject": "Cours",
     "contents": [content("Sortie au musée", "Autorisation à signer.")]},
    {"id": "cdt-05", "lessonId": "cours-mus", "date": "2026-11-05", "subject": "MUSIQUE",
     "contents": [content("Le rythme et la pulsation", "",
                          [att("pj-05", "Corrigé.pdf", "pdf-encrypted.pdf")])]},
    {"id": "cdt-04", "lessonId": "cours-maths", "date": "2026-11-04", "subject": "MATHEMATIQUES",
     "contents": [content("Mathématiques", "Fiche de synthèse.",
                          [att("pj-04", "Chapitre_7_-_Statistiques.pdf", "controle_SVT__version_finale.pdf")])]},
]

BASE_COURSES = [
    {"id": "course-hg-ch2", "subjectId": "histoire-geo", "chapter": "Chapitre 2", "courseNumber": 1,
     "title": "La monarchie absolue", "date": "2026-10-06"},
    {"id": "course-maths-ch3a", "subjectId": "maths", "chapter": "chapitre 3", "courseNumber": 1,
     "title": "Notion de fonction", "date": "2026-10-13"},
    {"id": "course-maths-ch3b", "subjectId": "maths", "chapter": "Chapitre 3", "courseNumber": 4,
     "title": "Fonctions linéaires", "date": "2026-11-03"},
    {"id": "course-fr-sans", "subjectId": "francais", "chapter": "Sans chapitre", "courseNumber": 2,
     "title": "Le conte", "date": "2026-10-20"},
    {"id": "course-nb-anglais", "subjectId": "anglais", "chapter": "Sans chapitre", "courseNumber": 1,
     "title": "Daily routine", "date": "2026-11-16",
     "source": {"kind": "pronote-notebook", "sessionKey": "cdt-16", "subjectLabel": "ANGLAIS LV1",
                "date": "2026-11-16"}},
]

BASE_IMPORTS = {
    "cdt-16": {"status": "created", "courseId": "course-nb-anglais", "at": "2026-11-16T18:00:00Z"},
    "cdt-13": {"status": "dismissed", "courseId": "course-deleted-pc", "at": "2026-11-14T18:00:00Z"},
}


def next_course_number(courses: list[dict], subject_id: str, chapter: str) -> int:
    key = chapter.strip().lower()
    if not key:
        return 1
    nums = [c["courseNumber"] for c in courses
            if c["subjectId"] == subject_id and c["chapter"].strip().lower() == key]
    return max(nums) + 1 if nums else 1


def description_of(session: dict) -> str:
    parts = []
    for c in session["contents"]:
        for part in (c["title"], c["description"]):
            if part:
                parts.append(part)
    return cut_words("\n\n".join(parts), MAX_DESCRIPTION, WHITESPACE)


def download(session: dict) -> tuple[list[dict], list[dict]]:
    """Attachments of kind file, contents then attachments order; at most
    MAX_FILES stored. Returns (documents, skipped)."""
    docs, skipped = [], []
    for c in session["contents"]:
        for a in c["attachments"]:
            if a["kind"] != "file":
                continue
            if len(docs) >= MAX_FILES:
                skipped.append({"id": a["id"], "name": a["name"], "reason": "limit"})
            elif "downloadError" in a:
                skipped.append({"id": a["id"], "name": a["name"], "reason": a["downloadError"]})
            else:
                data = (HERE / a["fixture"]).read_bytes()
                kind, _ = detect_type(a["name"], data)
                docs.append({"id": a["id"], "name": a["name"], "kind": kind, "data": data,
                             "extraction": extract(kind, data)})
    return docs, skipped


def prefill(session: dict, subjects: list[dict], courses: list[dict]) -> dict:
    subject = match_subject(session["subject"], subjects)
    docs, skipped = download(session)
    principal = next((d for d in docs if d["kind"] in EXTRACTIBLE), None)
    names = [session["subject"]] + ([subject["name"]] if subject else [])
    content_title = next((c["title"] for c in session["contents"] if c["title"]), None)
    result = title_pipeline(principal, content_title, names, session["date"])
    out = {
        "sessionKey": session["id"],
        "subjectId": subject["id"] if subject else None,
        "date": session["date"],
        "chapter": result["chapter"],
        "title": result["title"],
        "titleSource": source_name(result["candidates"], result["titleFrom"]),
        "candidates": result["candidates"],
        "description": description_of(session),
        "principalDocument": principal["name"] if principal else None,
        "documents": [{"name": d["name"], "mime": mime_of(d["name"], d["kind"]), "size": len(d["data"]),
                       "extractedTitle": extracted_title(d["extraction"], d["name"], names) if d["kind"] else None}
                      for d in docs],
        "skippedAttachments": skipped,
    }
    if subject:
        out["courseNumber"] = next_course_number(courses, subject["id"], result["chapter"])
    return out


def add_days(day: str, n: int) -> str:
    return (dt.date.fromisoformat(day) + dt.timedelta(days=n)).isoformat()


def skip_reason(session: dict, settings: dict, imports: dict, subjects: list[dict], today: str) -> str | None:
    if not settings.get("notebookAutoCreate"):
        return "autoCreateDisabled"
    status = imports.get(session["id"], {}).get("status")
    if status in ("created", "dismissed"):
        return status
    if session["date"] > today:
        return "future"
    since = settings.get("notebookAutoCreateSince")
    if since and session["date"] < since:
        return "beforeSince"
    if match_subject(session["subject"], subjects) is None:
        return "unknownSubject"
    return None


def backfill_proposal(sessions, imports, subjects, today) -> dict:
    start, end = add_days(today, -BACKFILL_DAYS), add_days(today, -1)
    keys = [s["id"] for s in sessions
            if start <= s["date"] <= end and imports.get(s["id"], {}).get("status") not in ("created", "dismissed")
            and match_subject(s["subject"], subjects) is not None]
    count = len(keys)
    return {"from": start, "to": end, "count": count, "sessionKeys": keys,
            "message": f"Importer aussi les {count} séances des 14 derniers jours ?" if count else None,
            "acceptSetsSince": start}


def toast(n: int) -> str | None:
    if n == 0:
        return None
    return f"{n} cours {'créé' if n == 1 else 'créés'} depuis le cahier de textes"


def run_scenario(sc: dict, sessions, subjects) -> dict:
    today = sc["today"]
    courses = copy.deepcopy(sc["courses"])
    imports = copy.deepcopy(sc["notebookImports"])
    skipped, eligible = [], []
    for s in sessions:
        reason = skip_reason(s, sc["settings"], imports, subjects, today)
        if reason:
            skipped.append({"sessionKey": s["id"], "reason": reason})
        else:
            eligible.append(s)
    eligible.sort(key=lambda s: s["date"])  # stable: response order within a date
    created = []
    for s in eligible:
        pf = prefill(s, subjects, courses)
        course = {"id": f"course-nb-{s['id']}", "subjectId": pf["subjectId"], "chapter": pf["chapter"],
                  "courseNumber": pf["courseNumber"], "title": pf["title"], "date": pf["date"],
                  "description": pf["description"],
                  "source": {"kind": "pronote-notebook", "sessionKey": s["id"], "subjectLabel": s["subject"],
                             "date": s["date"]},
                  "documents": pf["documents"]}
        courses.append(course)
        created.append({"sessionKey": s["id"], "titleSource": pf["titleSource"], **course})
        imports[s["id"]] = {"status": "created", "courseId": course["id"]}
    course_ids = {c["id"] for c in courses}
    buttons = {s["id"]: "viewCourse" if imports.get(s["id"], {}).get("status") == "created"
               and imports[s["id"]].get("courseId") in course_ids else "createCourse" for s in sessions}
    return {
        "eligibleSessionKeys": [s["id"] for s in eligible],
        "skipped": skipped,
        "createdCourses": created,
        "notebookImportsAfter": {k: {"status": v["status"], "courseId": v.get("courseId")}
                                 for k, v in sorted(imports.items())},
        "toast": toast(len(created)),
        "buttons": buttons,
    }


def build_course_vectors() -> dict:
    constants = json.loads(CONSTANTS.read_text("utf-8"))
    subjects = constants["DEFAULT_SUBJECTS"]
    settings_base = dict(constants["DEFAULT_SETTINGS"])
    scenarios_in = [
        ("auto-since-nov-12", "Réglage actif depuis le 12 novembre : éligibilité, dédoublonnage, dismissed, "
         "matière inconnue, date future, numérotation, limites de fichiers",
         {"notebookAutoCreate": True, "notebookAutoCreateSince": "2026-11-12"}, BASE_COURSES, BASE_IMPORTS),
        ("activation-today", "Activation aujourd’hui (since = today) : seules les séances du jour ; "
         "proposition des 14 derniers jours",
         {"notebookAutoCreate": True, "notebookAutoCreateSince": TODAY}, BASE_COURSES, BASE_IMPORTS),
        ("backfill-accepted", "Proposition acceptée : since = today − 14",
         {"notebookAutoCreate": True, "notebookAutoCreateSince": add_days(TODAY, -BACKFILL_DAYS)},
         BASE_COURSES, BASE_IMPORTS),
        ("auto-disabled", "Réglage désactivé (défaut) : aucune création, boutons seulement",
         {"notebookAutoCreate": False}, BASE_COURSES, BASE_IMPORTS),
    ]
    scenarios = []
    for sid, note, settings, courses, imports in scenarios_in:
        sc = {"id": sid, "note": note, "today": TODAY, "settings": {**settings_base, **settings},
              "courses": copy.deepcopy(courses), "notebookImports": copy.deepcopy(imports)}
        sc["expected"] = run_scenario(sc, SESSIONS, subjects)
        if sid == "activation-today":
            # Computed before the automatic run of the day (it only counts days before today).
            sc["expected"]["backfillProposal"] = backfill_proposal(SESSIONS, sc["notebookImports"], subjects, TODAY)
        scenarios.append(sc)
    # Second pass after the first scenario: nothing is created twice.
    first = scenarios[0]
    rerun = {"id": "rerun-after-auto", "note": "Deuxième lecture après « auto-since-nov-12 » : aucun doublon",
             "today": TODAY, "settings": copy.deepcopy(first["settings"]),
             "courses": copy.deepcopy(first["courses"]) + [
                 {k: v for k, v in c.items() if k not in ("sessionKey", "titleSource")}
                 for c in first["expected"]["createdCourses"]],
             "notebookImports": {k: {**v, "at": "2026-11-18T19:00:00Z"}
                                 for k, v in first["expected"]["notebookImportsAfter"].items()}}
    rerun["expected"] = run_scenario(rerun, SESSIONS, subjects)
    assert not rerun["expected"]["createdCourses"]
    scenarios.append(rerun)
    return {
        "schemaVersion": 1,
        "generator": "native/shared/documents/reference.py",
        "regenerate": "python3 native/shared/documents/reference.py",
        "spec": "native/shared/notebook-to-course.md §1–§5, native/shared/documents/README.md",
        "containsPersonalData": False,
        "today": TODAY,
        "subjects": subjects,
        "sessions": SESSIONS,
        "prefill": [prefill(s, subjects, BASE_COURSES) for s in SESSIONS],
        "scenarios": scenarios,
    }


# ===========================================================================

def dump(obj: dict) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2) + "\n"


def build() -> dict[Path, str]:
    check_match_subject({})
    return {DOC_VECTORS: dump(build_document_vectors()), COURSE_VECTORS: dump(build_course_vectors())}


def main() -> int:
    first, second = build(), build()
    if first != second:
        print("unstable output", file=sys.stderr)
        return 1
    if "--check" in sys.argv:
        bad = [p.name for p, text in first.items() if not p.exists() or p.read_text("utf-8") != text]
        if bad:
            print("out of date: " + ", ".join(bad), file=sys.stderr)
            return 1
        print("OK: vectors identical to a fresh build")
        return 0
    for path, text in first.items():
        path.write_text(text, "utf-8")
        print(f"wrote {path.relative_to(SHARED.parent.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
