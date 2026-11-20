#!/usr/bin/env python3
"""Generate the synthetic documents of native/shared/documents/files/.

Standard library only (zipfile, zlib, hashlib, struct). Every byte is
deterministic: fixed zip timestamps and attributes, hand-written PDF with a
computed xref table, fixed PDF /ID. No real data: titles are school topics,
names are fictitious.

    python3 native/shared/documents/make_documents.py          # write files/
    python3 native/shared/documents/make_documents.py --check  # build twice, compare with disk
"""

from __future__ import annotations

import hashlib
import io
import struct
import sys
import zipfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "files"
MAX_SIZE = 30 * 1024

# --------------------------------------------------------------------------
# PDF
# --------------------------------------------------------------------------

PDF_HEADER = b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"


def pdf_lit(text: str) -> bytes:
    """PDF literal string, WinAnsi/PDFDoc bytes, non-ASCII as octal escapes."""
    raw = text.encode("cp1252")
    out = bytearray(b"(")
    for b in raw:
        if b in (0x28, 0x29, 0x5C):
            out += b"\\" + bytes([b])
        elif 0x20 <= b < 0x7F:
            out.append(b)
        else:
            out += b"\\%03o" % b
    out += b")"
    return bytes(out)


def pdf_utf16(text: str) -> bytes:
    return b"<FEFF" + text.encode("utf-16-be").hex().upper().encode() + b">"


def pdf_stream(data: bytes, extra: bytes = b"") -> bytes:
    return b"<< /Length %d%s >>\nstream\n" % (len(data), extra) + data + b"\nendstream"


def text_line(size: int, y: int, shown: bytes, op: bytes = b"Tj") -> bytes:
    return b"BT /F1 %d Tf 72 %d Td %s %s ET\n" % (size, y, shown, op)


def build_pdf(objects: list[bytes], *, info: int | None = None,
              trailer_extra: bytes = b"") -> bytes:
    """objects[i] is the body of object i+1; object 1 is the catalog."""
    out = bytearray(PDF_HEADER)
    offsets = []
    for num, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % num + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n" % (len(objects) + 1)
    out += b"0000000000 65535 f \n"
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer\n<< /Size %d /Root 1 0 R" % (len(objects) + 1)
    if info:
        out += b" /Info %d 0 R" % info
    out += trailer_extra + b" >>\nstartxref\n%d\n" % xref + b"%%EOF\n"
    return bytes(out)


def simple_pdf(content: bytes, *, info: bytes | None = None, xmp: str | None = None,
               flate: bool = False) -> bytes:
    """One A4 page, Helvetica (WinAnsiEncoding). Objects: 1 catalog, 2 pages,
    3 page, 4 font, 5 content, then optional info and XMP metadata."""
    stream = pdf_stream(zlib.compress(content, 9), b" /Filter /FlateDecode") if flate \
        else pdf_stream(content)
    objects = [
        b"",  # catalog, filled below
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        stream,
    ]
    catalog = b"<< /Type /Catalog /Pages 2 0 R"
    info_num = None
    if info is not None:
        objects.append(info)
        info_num = len(objects)
    if xmp is not None:
        objects.append(pdf_stream(xmp.encode("utf-8"), b" /Type /Metadata /Subtype /XML"))
        catalog += b" /Metadata %d 0 R" % len(objects)
    objects[0] = catalog + b" >>"
    return build_pdf(objects, info=info_num)


def rc4(key: bytes, data: bytes) -> bytes:
    s = list(range(256))
    j = 0
    for i in range(256):
        j = (j + s[i] + key[i % len(key)]) & 0xFF
        s[i], s[j] = s[j], s[i]
    i = j = 0
    out = bytearray()
    for b in data:
        i = (i + 1) & 0xFF
        j = (j + s[i]) & 0xFF
        s[i], s[j] = s[j], s[i]
        out.append(b ^ s[(s[i] + s[j]) & 0xFF])
    return bytes(out)


PDF_PAD = bytes.fromhex("28BF4E5E4E758A4164004E56FFFA01082E2E00B6D0683E802F0CA9FE6453697A")


def encrypted_pdf() -> bytes:
    """Standard security handler V1/R2 (RC4 40 bits) with a non-empty user
    password: no reader can open it without the password."""
    user_pw, owner_pw = b"motdepasse", b"proprietaire"
    file_id = hashlib.md5(b"jmethod-synthetic-encrypted").digest()
    p_value = -44
    pad = lambda pw: (pw + PDF_PAD)[:32]  # noqa: E731
    o_key = hashlib.md5(pad(owner_pw)).digest()[:5]
    o_entry = rc4(o_key, pad(user_pw))
    key = hashlib.md5(pad(user_pw) + o_entry + struct.pack("<i", p_value) + file_id).digest()[:5]
    u_entry = rc4(key, PDF_PAD)

    def obj_key(num: int) -> bytes:
        return hashlib.md5(key + struct.pack("<I", num)[:3] + b"\x00\x00").digest()[:10]

    content = text_line(24, 760, pdf_lit("Corrigé du contrôle")) + \
        text_line(11, 730, pdf_lit("Ce texte est chiffré."))
    enc_content = rc4(obj_key(5), content)
    enc_title = rc4(obj_key(6), "Contrôle chiffré".encode("cp1252"))
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        pdf_stream(enc_content),
        b"<< /Title <" + enc_title.hex().upper().encode() + b"> >>",
        b"<< /Filter /Standard /V 1 /R 2 /O <" + o_entry.hex().upper().encode() +
        b"> /U <" + u_entry.hex().upper().encode() + b"> /P %d >>" % p_value,
    ]
    hex_id = file_id.hex().upper().encode()
    return build_pdf(objects, info=6,
                     trailer_extra=b" /Encrypt 7 0 R /ID [<" + hex_id + b"><" + hex_id + b">]")


def corrupt_pdf() -> bytes:
    """Valid signature, then nothing a parser can use (no object, no trailer)."""
    body = bytearray(PDF_HEADER)
    seed = hashlib.sha256(b"jmethod-corrupt").digest()
    while len(body) < 600:
        seed = hashlib.sha256(seed).digest()
        body += bytes(b if b not in b"0123456789objtrailerxrefstartendBT()<>/" else 0x2A for b in seed)
    return bytes(body)


def xmp_packet(title: str) -> str:
    return (
        '<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>\n'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/">\n'
        ' <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
        '  <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">\n'
        '   <dc:format>application/pdf</dc:format>\n'
        '   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">' + title + '</rdf:li></rdf:Alt></dc:title>\n'
        '  </rdf:Description>\n'
        ' </rdf:RDF>\n'
        '</x:xmpmeta>\n'
        '<?xpacket end="w"?>'
    )


def pdfs() -> dict[str, bytes]:
    files = {}
    files["pdf-info-title.pdf"] = simple_pdf(
        text_line(22, 760, pdf_lit("Calcul littéral")) +
        text_line(11, 730, pdf_lit("Développer, factoriser, réduire.")),
        info=b"<< /Title " + pdf_lit("Le calcul littéral") + b" /Producer (make_documents.py) >>")
    files["pdf-word-title.pdf"] = simple_pdf(
        text_line(20, 760, pdf_lit("La Révolution française")) +
        text_line(11, 730, pdf_lit("1789 : la prise de la Bastille.")),
        info=b"<< /Title " + pdf_utf16("Microsoft Word - Chapitre 2 - La Révolution.docx") +
        b" /Creator (Microsoft Word) >>")
    files["pdf-xmp-title.pdf"] = simple_pdf(
        text_line(11, 760, pdf_lit("Fiche de révision")) +
        text_line(11, 740, pdf_lit("Évaporation, condensation, précipitations.")),
        info=b"<< /Producer (make_documents.py) >>",
        xmp=xmp_packet("Le cycle de l’eau"))
    files["pdf-plain-content.pdf"] = simple_pdf(
        text_line(22, 760, pdf_lit("La photosynthèse")) +
        text_line(10, 730, pdf_lit("Nom : ............  Prénom : ............  Classe : ......")) +
        text_line(10, 714, pdf_lit("Date : ....../....../......")) +
        text_line(11, 690, pdf_lit("Les plantes vertes fabriquent leur matière organique.")))
    # Header lines first (small), then the large title built with TJ and an
    # en dash (WinAnsi 0x96), compressed.
    files["pdf-flate-content.pdf"] = simple_pdf(
        text_line(10, 780, pdf_lit("Nom : Martin")) +
        text_line(10, 766, pdf_lit("Classe : 4e B")) +
        text_line(20, 730, b"[" + pdf_lit("Chapitre 5 ") + b" -15 " + pdf_lit("– Les volcans") + b"]",
                  b"TJ") +
        text_line(11, 700, pdf_lit("Un volcan est un relief formé par l’accumulation de laves.")),
        flate=True)
    files["pdf-generic-title.pdf"] = simple_pdf(
        text_line(18, 760, pdf_lit("Le théorème de Pythagore")) +
        text_line(11, 730, pdf_lit("Dans un triangle rectangle, le carré de l’hypoténuse...")),
        info=b"<< /Title (Document1) >>")
    files["fiche_exo_3.pdf"] = simple_pdf(
        b"", info=b"<< /Title (fiche_exo_3.pdf) >>")
    files["pdf-encrypted.pdf"] = encrypted_pdf()
    files["controle_SVT__version_finale.pdf"] = corrupt_pdf()
    return files


# --------------------------------------------------------------------------
# Zip helpers (ODF and OOXML)
# --------------------------------------------------------------------------

def make_zip(entries: list[tuple[str, str | bytes]], *, odf_mimetype: str | None = None) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        def add(name: str, data: bytes, compress: int) -> None:
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 1, 8, 0, 0))
            info.compress_type = compress
            info.create_system = 3
            info.external_attr = 0o644 << 16
            zf.writestr(info, data, compress_type=compress, compresslevel=9 if compress else None)

        if odf_mimetype:
            add("mimetype", odf_mimetype.encode("ascii"), zipfile.ZIP_STORED)
        for name, data in entries:
            add(name, data.encode("utf-8") if isinstance(data, str) else data, zipfile.ZIP_DEFLATED)
    return buf.getvalue()


XML = '<?xml version="1.0" encoding="UTF-8"?>\n'

# ---- ODF -------------------------------------------------------------------

ODF_NS = (
    'xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
    'xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" '
    'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" '
    'xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" '
    'xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" '
    'xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" '
    'xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" '
    'xmlns:xlink="http://www.w3.org/1999/xlink" '
    'xmlns:dc="http://purl.org/dc/elements/1.1/" '
    'xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0"'
)
ODT_MIME = "application/vnd.oasis.opendocument.text"
ODP_MIME = "application/vnd.oasis.opendocument.presentation"


def odf_meta(title: str | None) -> str:
    t = f"<dc:title>{title}</dc:title>" if title is not None else ""
    return (XML + f'<office:document-meta {ODF_NS} office:version="1.3"><office:meta>'
            f"<meta:generator>make_documents.py</meta:generator>"
            f"<meta:initial-creator>Camille Martin</meta:initial-creator>{t}"
            "</office:meta></office:document-meta>")


def odf_manifest(mime: str) -> str:
    entries = "".join(
        f'<manifest:file-entry manifest:full-path="{p}" manifest:media-type="text/xml"/>'
        for p in ("content.xml", "styles.xml", "meta.xml"))
    return (XML + '<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" '
            'manifest:version="1.3">'
            f'<manifest:file-entry manifest:full-path="/" manifest:version="1.3" manifest:media-type="{mime}"/>'
            + entries + "</manifest:manifest>")


ODT_STYLES = (
    XML + f'<office:document-styles {ODF_NS} office:version="1.3"><office:styles>'
    '<style:style style:name="Standard" style:family="paragraph"/>'
    '<style:style style:name="Title" style:display-name="Titre" style:family="paragraph" '
    'style:parent-style-name="Standard"><style:text-properties fo:font-size="26pt" fo:font-weight="bold"/></style:style>'
    '<style:style style:name="Heading_20_1" style:display-name="Titre 1" style:family="paragraph" '
    'style:default-outline-level="1"><style:text-properties fo:font-size="18pt" fo:font-weight="bold"/></style:style>'
    '<style:style style:name="Heading_20_2" style:display-name="Titre 2" style:family="paragraph" '
    'style:default-outline-level="2"><style:text-properties fo:font-size="14pt" fo:font-weight="bold"/></style:style>'
    "</office:styles></office:document-styles>")


def odt(body: str, *, title: str | None, auto_styles: str = "") -> bytes:
    content = (XML + f'<office:document-content {ODF_NS} office:version="1.3">'
               f"<office:automatic-styles>{auto_styles}</office:automatic-styles>"
               f"<office:body><office:text>{body}</office:text></office:body></office:document-content>")
    return make_zip([("content.xml", content), ("styles.xml", ODT_STYLES), ("meta.xml", odf_meta(title)),
                     ("META-INF/manifest.xml", odf_manifest(ODT_MIME))], odf_mimetype=ODT_MIME)


def h(level: int, text: str) -> str:
    return f'<text:h text:style-name="Heading_20_{level}" text:outline-level="{level}">{text}</text:h>'


def p(text: str, style: str = "Standard") -> str:
    return f'<text:p text:style-name="{style}">{text}</text:p>'


def ul(*items: str) -> str:
    return "<text:list>" + "".join(
        f"<text:list-item>{i if i.startswith('<text:list>') else p(i)}</text:list-item>" for i in items
    ) + "</text:list>"


def odts() -> dict[str, bytes]:
    files = {}
    files["odt-meta-title.odt"] = odt(
        h(1, "Chapitre 4 : Les fractions")
        + p("Une fraction représente un partage en parts égales.")
        + ul("Numérateur", "Dénominateur")
        + p("Exemple : <text:span>trois quarts</text:span> s’écrit 3/4."),
        title="Les fractions")
    files["odt-heading.odt"] = odt(
        h(1, "Chapitre 3<text:s/>: Les fonctions affines")
        + p("Une fonction affine s’écrit <text:span>f(x) = ax + b</text:span>.")
        + h(2, "Définition")
        + p("   Le nombre a est le <text:a xlink:type=\"simple\" xlink:href=\"https://exemple.fr\">coefficient"
            "</text:a><text:s text:c=\"2\"/>directeur.  ")
        + ul("Si a &gt; 0, f est croissante", ul("cas particulier : a = 1"), "Si a &lt; 0, f est décroissante")
        + p("")
        + p("Le nombre b est l’ordonnée à l’origine."),
        title=None)
    files["odt-title-style.odt"] = odt(
        p("La Révolution industrielle", "P1")
        + p("Au XIXe siècle, l’Europe se transforme.")
        + h(1, "Partie 2 – Les transformations")
        + ul("Le charbon", "La machine à vapeur"),
        title=None,
        auto_styles='<style:style style:name="P1" style:family="paragraph" style:parent-style-name="Title">'
                    '<style:paragraph-properties fo:text-align="center"/></style:style>')
    files["odt-heading-levels.odt"] = odt(
        h(2, "Rappels")
        + p("Un récit raconte une histoire.")
        + h(1, "Séquence IV – Le roman d’aventure")
        + p("Lecture d’un extrait."),
        title=None)
    # An ODT package under a misleading name: detected by its signature.
    files["chapitre-trompeur.bin"] = odt(
        h(1, "Partie 3 : Les solides")
        + p("Le cube, le pavé droit et la pyramide."),
        title=None)
    return files


def odp() -> bytes:
    def frame(cls: str, inner: str) -> str:
        return (f'<draw:frame presentation:class="{cls}" svg:x="2cm" svg:y="2cm" svg:width="24cm" '
                f'svg:height="4cm"><draw:text-box>{inner}</draw:text-box></draw:frame>')

    content = (
        XML + f'<office:document-content {ODF_NS} office:version="1.3"><office:body><office:presentation>'
        '<draw:page draw:name="page1" draw:master-page-name="Default">'
        + frame("title", p("Les grandes découvertes"))
        + frame("subtitle", p("Histoire – 5e"))
        + '</draw:page><draw:page draw:name="page2" draw:master-page-name="Default">'
        + frame("title", p("Les navigateurs"))
        + frame("outline", ul("Partir vers l’ouest", ul("Traverser l’océan"), "Revenir avec des cartes"))
        + '<presentation:notes>' + frame("notes", p("Note de l’enseignant : ne pas afficher")) + "</presentation:notes>"
        + "</draw:page></office:presentation></office:body></office:document-content>")
    styles = (XML + f'<office:document-styles {ODF_NS} office:version="1.3">'
              '<office:automatic-styles><style:page-layout style:name="PM1">'
              '<style:page-layout-properties fo:page-width="28cm" fo:page-height="15.75cm" '
              'style:print-orientation="landscape"/></style:page-layout></office:automatic-styles>'
              '<office:master-styles><style:master-page style:name="Default" style:page-layout-name="PM1"/>'
              "</office:master-styles></office:document-styles>")
    return make_zip([("content.xml", content), ("styles.xml", styles), ("meta.xml", odf_meta("Présentation1")),
                     ("META-INF/manifest.xml", odf_manifest(ODP_MIME))], odf_mimetype=ODP_MIME)


# ---- OOXML -----------------------------------------------------------------

W_NS = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
OFFDOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


def core_xml(title: str | None) -> str:
    t = f"<dc:title>{title}</dc:title>" if title is not None else ""
    return (XML + '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" '
            'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" '
            'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">'
            f"{t}<dc:creator>Camille Martin</dc:creator>"
            '<dcterms:created xsi:type="dcterms:W3CDTF">2026-11-01T08:00:00Z</dcterms:created>'
            "</cp:coreProperties>")


def rels(items: list[tuple[str, str, str]]) -> str:
    return (XML + f'<Relationships xmlns="{REL_NS}">' + "".join(
        f'<Relationship Id="{i}" Type="{t}" Target="{target}"/>' for i, t, target in items) + "</Relationships>")


DOCX_STYLES = (
    XML + f"<w:styles {W_NS}>"
    '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
    '<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>'
    '<w:rPr><w:sz w:val="52"/></w:rPr></w:style>'
    '<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>'
    '<w:pPr><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>'
    '<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>'
    '<w:pPr><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style>'
    '<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/>'
    '<w:basedOn w:val="Normal"/><w:pPr><w:ind w:left="720"/></w:pPr></w:style>'
    "</w:styles>")

DOCX_NUMBERING = (
    XML + f"<w:numbering {W_NS}>"
    '<w:abstractNum w:abstractNumId="0">'
    '<w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/></w:lvl>'
    '<w:lvl w:ilvl="1"><w:numFmt w:val="bullet"/><w:lvlText w:val="–"/></w:lvl>'
    '</w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>')


def wp(text: str | list[str], style: str | None = None, ilvl: int | None = None) -> str:
    ppr = ""
    if style or ilvl is not None:
        ppr = "<w:pPr>"
        if style:
            ppr += f'<w:pStyle w:val="{style}"/>'
        if ilvl is not None:
            ppr += f'<w:numPr><w:ilvl w:val="{ilvl}"/><w:numId w:val="1"/></w:numPr>'
        ppr += "</w:pPr>"
    runs = text if isinstance(text, list) else [text]
    return "<w:p>" + ppr + "".join(
        f'<w:r><w:t xml:space="preserve">{r}</w:t></w:r>' if not r.startswith("<") else r for r in runs) + "</w:p>"


def docx(body: str, *, title: str | None) -> bytes:
    document = (XML + f"<w:document {W_NS}><w:body>{body}"
                '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr></w:body></w:document>')
    ct = (XML + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
          '<Default Extension="xml" ContentType="application/xml"/>'
          '<Override PartName="/word/document.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
          '<Override PartName="/word/styles.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
          '<Override PartName="/word/numbering.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>'
          '<Override PartName="/docProps/core.xml" '
          'ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>')
    return make_zip([
        ("[Content_Types].xml", ct),
        ("_rels/.rels", rels([
            ("rId1", OFFDOC + "/officeDocument", "word/document.xml"),
            ("rId2", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties",
             "docProps/core.xml")])),
        ("word/document.xml", document),
        ("word/_rels/document.xml.rels", rels([
            ("rId1", OFFDOC + "/styles", "styles.xml"),
            ("rId2", OFFDOC + "/numbering", "numbering.xml")])),
        ("word/styles.xml", DOCX_STYLES),
        ("word/numbering.xml", DOCX_NUMBERING),
        ("docProps/core.xml", core_xml(title)),
    ])


def docxs() -> dict[str, bytes]:
    files = {}
    files["docx-core-title.docx"] = docx(
        wp("Chapitre 1 : La cellule", "Heading1")
        + wp("Tous les êtres vivants sont constitués de cellules.")
        + wp("Le noyau", ilvl=0) + wp("Le cytoplasme", ilvl=0) + wp("La membrane", ilvl=1),
        title="« La cellule, unité du vivant »")
    # Heading2 first (not a title style), title split across runs.
    files["docx-heading1.docx"] = docx(
        wp("Objectifs", "Heading2")
        + wp(["Les puis", "sances de dix"], "Heading1")
        + wp(["Écrire ", "<w:r><w:tab/></w:r>", "10", "<w:r><w:br/></w:r>", " puissance n."])
        + wp(["Exemple : 1 000 = 10³.",
              '<w:del w:id="1" w:author="Camille Martin" w:date="2026-11-01T08:00:00Z"><w:r>'
              "<w:delText> Texte supprimé.</w:delText></w:r></w:del>"]),
        title="MATHEMATIQUES")
    files["docx-title-style.docx"] = docx(
        wp("Le Moyen Âge : seigneurs et paysans", "Title")
        + wp("Chapitre 6 : La société féodale", "Heading1")
        + wp("Le seigneur protège les paysans de sa seigneurie."),
        title=None)
    return files


# ---- PPTX ------------------------------------------------------------------

P_NS = ('xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
        'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"')

PPTX_THEME = (
    XML + '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Synthétique">'
    '<a:themeElements><a:clrScheme name="S">'
    + "".join(f'<a:{n}><a:srgbClr val="{v}"/></a:{n}>' for n, v in (
        ("dk1", "000000"), ("lt1", "FFFFFF"), ("dk2", "1F2937"), ("lt2", "F3F4F6"), ("accent1", "2563EB"),
        ("accent2", "F97316"), ("accent3", "10B981"), ("accent4", "8B5CF6"), ("accent5", "EF4444"),
        ("accent6", "14B8A6"), ("hlink", "1D4ED8"), ("folHlink", "6366F1")))
    + '</a:clrScheme><a:fontScheme name="S"><a:majorFont><a:latin typeface="Arial"/><a:ea typeface=""/>'
    '<a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Arial"/><a:ea typeface=""/>'
    '<a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="S">'
    '<a:fillStyleLst>' + '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>' * 3 + '</a:fillStyleLst>'
    '<a:lnStyleLst>' + '<a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>' * 3
    + '</a:lnStyleLst><a:effectStyleLst>' + '<a:effectStyle><a:effectLst/></a:effectStyle>' * 3
    + '</a:effectStyleLst><a:bgFillStyleLst>' + '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>' * 3
    + '</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>')

SP_TREE_HEAD = ('<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
                '<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/>'
                '<a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>')


def shape(sid: int, ph: str, paragraphs: list[tuple[int, str]], y: int) -> str:
    paras = "".join(
        (f'<a:p><a:pPr lvl="{lvl}"/>' if lvl else "<a:p>") + f'<a:r><a:rPr lang="fr-FR"/><a:t>{t}</a:t></a:r></a:p>'
        for lvl, t in paragraphs)
    return (f'<p:sp><p:nvSpPr><p:cNvPr id="{sid}" name="Forme {sid}"/><p:cNvSpPr/><p:nvPr>{ph}</p:nvPr></p:nvSpPr>'
            f'<p:spPr><a:xfrm><a:off x="457200" y="{y}"/><a:ext cx="8229600" cy="1143000"/></a:xfrm></p:spPr>'
            f"<p:txBody><a:bodyPr/><a:lstStyle/>{paras}</p:txBody></p:sp>")


def slide(shapes: str) -> str:
    return (XML + f"<p:sld {P_NS}><p:cSld><p:spTree>{SP_TREE_HEAD}{shapes}</p:spTree></p:cSld>"
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")


def pptx(slides: list[str], order: list[int], *, title: str | None) -> bytes:
    """slides[i] is ppt/slides/slide{i+1}.xml; order lists slide numbers as
    they appear in presentation.xml (sldIdLst)."""
    layout = (XML + f'<p:sldLayout {P_NS} type="obj"><p:cSld><p:spTree>{SP_TREE_HEAD}</p:spTree></p:cSld>'
              "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
    master = (XML + f"<p:sldMaster {P_NS}><p:cSld><p:spTree>{SP_TREE_HEAD}</p:spTree></p:cSld>"
              '<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" '
              'accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" '
              'folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/>'
              "</p:sldLayoutIdLst></p:sldMaster>")
    n = len(slides)
    pres = (XML + f'<p:presentation {P_NS}><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/>'
            "</p:sldMasterIdLst><p:sldIdLst>"
            + "".join(f'<p:sldId id="{256 + k}" r:id="rId{num + 1}"/>' for k, num in enumerate(order))
            + '</p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/>'
            "</p:presentation>")
    pres_rels = [("rId1", OFFDOC + "/slideMaster", "slideMasters/slideMaster1.xml")] + [
        (f"rId{i + 1}", OFFDOC + "/slide", f"slides/slide{i}.xml") for i in range(1, n + 1)] + [
        (f"rId{n + 2}", OFFDOC + "/theme", "theme/theme1.xml")]
    ct = (XML + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
          '<Default Extension="xml" ContentType="application/xml"/>'
          '<Override PartName="/ppt/presentation.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
          '<Override PartName="/ppt/slideMasters/slideMaster1.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>'
          '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>'
          '<Override PartName="/ppt/theme/theme1.xml" '
          'ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
          + "".join(f'<Override PartName="/ppt/slides/slide{i}.xml" '
                    'ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>'
                    for i in range(1, n + 1))
          + '<Override PartName="/docProps/core.xml" '
          'ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>')
    entries = [
        ("[Content_Types].xml", ct),
        ("_rels/.rels", rels([
            ("rId1", OFFDOC + "/officeDocument", "ppt/presentation.xml"),
            ("rId2", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties",
             "docProps/core.xml")])),
        ("ppt/presentation.xml", pres),
        ("ppt/_rels/presentation.xml.rels", rels(pres_rels)),
        ("ppt/slideMasters/slideMaster1.xml", master),
        ("ppt/slideMasters/_rels/slideMaster1.xml.rels", rels([
            ("rId1", OFFDOC + "/slideLayout", "../slideLayouts/slideLayout1.xml"),
            ("rId2", OFFDOC + "/theme", "../theme/theme1.xml")])),
        ("ppt/slideLayouts/slideLayout1.xml", layout),
        ("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels([
            ("rId1", OFFDOC + "/slideMaster", "../slideMasters/slideMaster1.xml")])),
        ("ppt/theme/theme1.xml", PPTX_THEME),
    ]
    for i, s in enumerate(slides, 1):
        entries.append((f"ppt/slides/slide{i}.xml", s))
        entries.append((f"ppt/slides/_rels/slide{i}.xml.rels", rels([
            ("rId1", OFFDOC + "/slideLayout", "../slideLayouts/slideLayout1.xml")])))
    entries.append(("docProps/core.xml", core_xml(title)))
    return make_zip(entries)


def pptxs() -> dict[str, bytes]:
    files = {}
    files["pptx-ctrtitle.pptx"] = pptx([
        slide(shape(2, '<p:ph type="ctrTitle"/>', [(0, "Les états de la matière")], 1600200)
              + shape(3, '<p:ph type="subTitle" idx="1"/>', [(0, "Physique-chimie – 5e")], 3000000)),
        slide(shape(2, '<p:ph type="title"/>', [(0, "Les changements d’état")], 457200)
              + shape(3, '<p:ph idx="1"/>', [(0, "La fusion"), (1, "de solide à liquide"), (0, "La solidification")],
                      1600200)),
    ], [1, 2], title=None)
    # slide2.xml comes first in presentation.xml: the first slide is "Thème 1 :".
    files["pptx-theme-empty.pptx"] = pptx([
        slide(shape(2, '<p:ph type="title"/>', [(0, "Conclusion")], 457200)),
        slide(shape(2, '<p:ph type="title"/>', [(0, "Thème 1 :")], 457200)
              + shape(3, '<p:ph type="body" idx="1"/>', [(0, "Le Soleil et les planètes")], 1600200)),
    ], [2, 1], title=None)
    return files


# ---- Text, PNG, empty ------------------------------------------------------

def png_1x1() -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00\xff\xff\xff", 9)) + chunk(b"IEND", b""))


def texts() -> dict[str, bytes]:
    long_line = ("L’évolution des espèces au cours des temps géologiques, de l’apparition des premières "
                 "cellules jusqu’aux mammifères actuels, expliquée pas à pas.")
    return {
        "notes.txt": ("﻿\n   \nCHAP. 2) Les nombres relatifs\n\nAddition et soustraction.\n").encode("utf-8"),
        "long-title.txt": (long_line + "\nSuite du texte.\n").encode("utf-8"),
        "ancien-encodage.txt": "Unité 3 : Le récit fantastique\r\nTexte enregistré en Windows-1252.\r\n"
        .encode("cp1252"),
        "techno.txt": "Thème 2 – Les objets connectés\nCapteurs et actionneurs.\n".encode("utf-8"),
        "fiche-eleve.txt": "Nom : ..........  Classe : ......\n\nLes angles\n".encode("utf-8"),
        "fiche.md": ("# Les figures de style :\n\nUne figure de style est un procédé d’écriture.\n\n"
                     "- La comparaison\n- La métaphore\n").encode("utf-8"),
        "Sans titre.txt": b"",
        "schema.png": png_1x1(),
    }


def build() -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for part in (pdfs(), odts(), {"odp-slide-title.odp": odp()}, docxs(), pptxs(), texts()):
        files.update(part)
    for name, data in files.items():
        assert len(data) < MAX_SIZE, (name, len(data))
    return dict(sorted(files.items()))


def main() -> int:
    first, second = build(), build()
    if first != second:
        print("unstable build", file=sys.stderr)
        return 1
    if "--check" in sys.argv:
        on_disk = {p.name: p.read_bytes() for p in OUT.iterdir() if p.is_file()} if OUT.exists() else {}
        if on_disk != first:
            diff = sorted(set(on_disk) ^ set(first) | {k for k in first if on_disk.get(k) != first[k]})
            print("files/ differs from a fresh build: " + ", ".join(diff), file=sys.stderr)
            return 1
        print(f"OK: {len(first)} documents identical to a fresh build")
        return 0
    OUT.mkdir(exist_ok=True)
    for old in OUT.iterdir():
        if old.is_file() and old.name not in first:
            old.unlink()
    for name, data in first.items():
        (OUT / name).write_bytes(data)
    print(f"wrote {len(first)} documents to {OUT.relative_to(HERE.parent.parent.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
