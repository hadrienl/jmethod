package fr.jmethod.app.documents

import fr.jmethod.app.domain.NotebookCourses
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Types de documents (`notebook-to-course.md` §5.1 et §6, README des documents, règle 1). */
enum class DocType(val extension: String, val mime: String, val extractable: Boolean) {
    PDF("pdf", "application/pdf", true),
    ODT("odt", "application/vnd.oasis.opendocument.text", true),
    ODP("odp", "application/vnd.oasis.opendocument.presentation", true),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", true),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", true),
    TXT("txt", "text/plain", true),
    MD("md", "text/markdown", true),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false),
    PNG("png", "image/png", false),
    JPEG("jpg", "image/jpeg", false),
    GIF("gif", "image/gif", false),
    WEBP("webp", "image/webp", false),
    HEIC("heic", "image/heic", false),
    OTHER("", "application/octet-stream", false);

    val isImage get() = this in setOf(PNG, JPEG, GIF, WEBP, HEIC)

    companion object {
        private val mimes = mapOf(
            "pdf" to PDF.mime, "odt" to ODT.mime, "odp" to ODP.mime, "docx" to DOCX.mime, "pptx" to PPTX.mime,
            "xlsx" to XLSX.mime, "txt" to TXT.mime, "md" to MD.mime, "png" to PNG.mime, "jpg" to JPEG.mime,
            "jpeg" to JPEG.mime, "gif" to GIF.mime, "heic" to HEIC.mime, "webp" to WEBP.mime,
        )

        /** Extension : 1 à 5 alphanumériques après le dernier point, insensible à la casse. */
        fun extensionOf(name: String): String? = Regex("\\.([A-Za-z0-9]{1,5})$").find(name)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

        /** Type MIME : type détecté, sinon extension du nom. */
        fun mimeOf(name: String, type: DocType?): String =
            type?.takeIf { it.extractable }?.mime ?: mimes[extensionOf(name).orEmpty()] ?: OTHER.mime

        /** Type d’affichage (images, bureautique) d’après l’extension. */
        fun displayTypeOf(name: String): DocType = when (extensionOf(name)) {
            "jpeg", "jpg" -> JPEG
            else -> entries.firstOrNull { it.extension == extensionOf(name) && it != OTHER } ?: OTHER
        }
    }
}

/** Bloc de la vue « Lecture » (règle 32). */
data class ReadingBlock(val kind: Kind, val text: String, val level: Int? = null) {
    enum class Kind(val wire: String) { HEADING("heading"), PARAGRAPH("paragraph"), LIST_ITEM("listItem") }
}

data class DocumentAnalysis(
    /** Type extractible détecté, ou `null`. */
    val type: DocType?,
    val detectedBy: String? = null,
    val unreadable: Boolean = false,
    val metadata: String? = null,
    val metadataDetail: String? = null,
    val firstHeading: String? = null,
    val pdfLines: List<PdfText.Line>? = null,
    val readingView: List<ReadingBlock>? = null,
) {
    /** Entrée de la logique de titre ; `null` si l’extraction a échoué ou si le type n’est pas extractible. */
    val extraction: NotebookCourses.Extraction?
        get() = if (type == null || unreadable) null else NotebookCourses.Extraction(metadata, firstHeading, pdfLines?.map { it.text to it.size })
}

/**
 * Extraction locale, sans réseau ni exécution, sur le modèle de `native/shared/documents/reference.py` :
 * ODF et OOXML avec `java.util.zip` et un analyseur DOM (DTD et entités externes refusées), PDF par
 * [PdfText], TXT et MD décodés en UTF-8 ou Windows-1252.
 */
object DocumentAnalyzer {
    private const val MAX_PART = 16 * 1024 * 1024
    private val extractible = setOf("pdf", "odt", "odp", "docx", "pptx", "txt", "md")

    /** Règle 1 : extension extractible, sinon signature (`%PDF-`, ODF `mimetype`, OOXML). */
    fun detect(data: ByteArray, name: String): Pair<DocType?, String?> {
        val ext = DocType.extensionOf(name)
        if (ext != null && ext in extractible) return DocType.entries.first { it.extension == ext } to "extension"
        if (data.size >= 5 && String(data, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return DocType.PDF to "signature"
        if (data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() && data[2] == 3.toByte() && data[3] == 4.toByte()) {
            val type = runCatching {
                withZip(data) { zip ->
                    when {
                        zip.getEntry("mimetype") != null -> when (String(part(zip, "mimetype") ?: ByteArray(0), Charsets.US_ASCII).trim()) {
                            DocType.ODT.mime -> DocType.ODT
                            DocType.ODP.mime -> DocType.ODP
                            else -> null
                        }
                        zip.getEntry("[Content_Types].xml") != null -> when {
                            zip.getEntry("word/document.xml") != null -> DocType.DOCX
                            zip.getEntry("ppt/presentation.xml") != null -> DocType.PPTX
                            else -> null
                        }
                        else -> null
                    }
                }
            }.getOrNull()
            if (type != null) return type to "signature"
        }
        return null to null
    }

    fun detect(file: File, name: String): DocType = detect(file.readBytesLimited(), name).first ?: DocType.displayTypeOf(name)

    fun analyze(file: File, name: String): DocumentAnalysis = analyze(file.readBytesLimited(), name)

    fun analyze(data: ByteArray, name: String): DocumentAnalysis {
        val (type, by) = detect(data, name)
        type ?: return DocumentAnalysis(null)
        return try {
            when (type) {
                DocType.PDF -> PdfText.analyze(data).let { pdf ->
                    if (pdf == null) DocumentAnalysis(type, by, unreadable = true)
                    else DocumentAnalysis(type, by, metadata = pdf.metadata, metadataDetail = pdf.metadataDetail, pdfLines = pdf.lines)
                }
                DocType.TXT, DocType.MD -> DocumentAnalysis(type, by, firstHeading = firstTextLine(data, type == DocType.MD))
                DocType.ODT -> withZip(data) { odt(it) }.copy(detectedBy = by)
                DocType.ODP -> withZip(data) { odp(it) }.copy(detectedBy = by)
                DocType.DOCX -> withZip(data) { docx(it) }.copy(detectedBy = by)
                DocType.PPTX -> withZip(data) { pptx(it) }.copy(detectedBy = by)
                else -> DocumentAnalysis(null)
            }
        } catch (_: Throwable) {
            DocumentAnalysis(type, by, unreadable = true)
        }
    }

    // region Utilitaires

    private fun File.readBytesLimited(): ByteArray = inputStream().use { it.readLimited(25 * 1024 * 1024) }

    private fun java.io.InputStream.readLimited(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= max) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        if (out.size() > max) throw IllegalStateException("trop volumineux")
        return out.toByteArray()
    }

    private fun <T> withZip(data: ByteArray, block: (ZipFile) -> T): T {
        val temp = File.createTempFile("jm-doc", ".zip")
        try {
            temp.writeBytes(data)
            return ZipFile(temp).use(block)
        } finally {
            temp.delete()
        }
    }

    private fun part(zip: ZipFile, name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readLimited(MAX_PART) }
    }

    private fun xml(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(ByteArrayInputStream(ByteArray(0))) }
        return builder.parse(ByteArrayInputStream(bytes)).documentElement
    }

    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    private const val PRESENTATION = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.child(ns: String, local: String): Element? = children().firstOrNull { it.namespaceURI == ns && it.localName == local }

    private fun Element.descendants(ns: String, local: String): List<Element> {
        val list = getElementsByTagNameNS(ns, local)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.attr(ns: String, local: String): String? = if (hasAttributeNS(ns, local)) getAttributeNS(ns, local) else null

    private fun Element.isTag(ns: String, local: String) = namespaceURI == ns && localName == local

    private fun block(kind: ReadingBlock.Kind, text: String, level: Int? = null): ReadingBlock? =
        NotebookCourses.cleanWhitespace(text).takeIf { it.isNotEmpty() }?.let { ReadingBlock(kind, it, level) }

    /** `dc:title` (texte brut, vide possible) d’une partie de métadonnées, ou `null` si absent. */
    private fun dcTitle(zip: ZipFile, path: String): String? {
        val bytes = part(zip, path) ?: return null
        return xml(bytes).descendants(DC, "title").firstOrNull()?.textContent
    }

    // endregion

    // region ODF

    private fun odfText(element: Element): String {
        val out = StringBuilder()
        fun walk(node: Node) {
            for (i in 0 until node.childNodes.length) {
                val child = node.childNodes.item(i)
                when {
                    child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE -> out.append(child.nodeValue)
                    child is Element && child.isTag(TEXT, "s") -> out.append(" ".repeat((child.attr(TEXT, "c")?.toIntOrNull() ?: 1).coerceIn(1, 1000)))
                    child is Element && (child.isTag(TEXT, "tab") || child.isTag(TEXT, "line-break")) -> out.append(' ')
                    child is Element && (child.isTag(TEXT, "note") || child.isTag(OFFICE, "annotation")) -> {}
                    child is Element -> walk(child)
                }
            }
        }
        walk(element)
        return out.toString()
    }

    /** Styles automatiques de `content.xml` hérités de Title/Titre (un niveau). */
    private fun titleStyles(root: Element): Set<String> {
        val auto = root.child(OFFICE, "automatic-styles") ?: return emptySet()
        return auto.children().filter { it.isTag(STYLE, "style") }
            .filter { (it.attr(STYLE, "parent-style-name") ?: "").lowercase(Locale.ROOT) in setOf("title", "titre") }
            .mapNotNull { it.attr(STYLE, "name") }.toSet()
    }

    private fun isTitleParagraph(p: Element, auto: Set<String>): Boolean {
        val style = p.attr(TEXT, "style-name") ?: ""
        return style.lowercase(Locale.ROOT) in setOf("title", "titre") || style in auto
    }

    private fun odfBlocks(container: Element, auto: Set<String>, depth: Int = 0): List<ReadingBlock> {
        val out = mutableListOf<ReadingBlock>()
        for (c in container.children()) {
            when {
                c.isTag(TEXT, "h") -> block(ReadingBlock.Kind.HEADING, odfText(c), c.attr(TEXT, "outline-level")?.toIntOrNull() ?: 1)?.let(out::add)
                c.isTag(TEXT, "p") -> when {
                    depth > 0 -> block(ReadingBlock.Kind.LIST_ITEM, odfText(c), depth)
                    isTitleParagraph(c, auto) -> block(ReadingBlock.Kind.HEADING, odfText(c), 1)
                    else -> block(ReadingBlock.Kind.PARAGRAPH, odfText(c))
                }?.let(out::add)
                c.isTag(TEXT, "list") -> c.children().filter { it.isTag(TEXT, "list-item") || it.isTag(TEXT, "list-header") }
                    .forEach { out += odfBlocks(it, auto, depth + 1) }
                c.isTag(TEXT, "section") || (c.namespaceURI == TABLE && c.localName in setOf("table", "table-row", "table-cell", "table-rows", "table-header-rows")) ->
                    out += odfBlocks(c, auto, depth)
            }
        }
        return out
    }

    private fun allElements(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            out += e
            e.children().forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun odt(zip: ZipFile): DocumentAnalysis {
        val root = xml(part(zip, "content.xml") ?: throw IllegalStateException("content.xml"))
        val body = root.child(OFFICE, "body")?.child(OFFICE, "text") ?: throw IllegalStateException("office:text")
        val auto = titleStyles(root)
        val minLevel = body.descendants(TEXT, "h").filter { NotebookCourses.cleanWhitespace(odfText(it)).isNotEmpty() }
            .minOfOrNull { it.attr(TEXT, "outline-level")?.toIntOrNull() ?: 1 }
        val first = allElements(body).firstOrNull { e ->
            ((e.isTag(TEXT, "h") && (e.attr(TEXT, "outline-level")?.toIntOrNull() ?: 1) == minLevel) ||
                (e.isTag(TEXT, "p") && isTitleParagraph(e, auto))) && NotebookCourses.cleanWhitespace(odfText(e)).isNotEmpty()
        }?.let(::odfText)
        val meta = dcTitle(zip, "meta.xml")
        return DocumentAnalysis(DocType.ODT, metadata = meta, metadataDetail = meta?.let { "metaXmlDcTitle" }, firstHeading = first, readingView = odfBlocks(body, auto))
    }

    private fun odp(zip: ZipFile): DocumentAnalysis {
        val root = xml(part(zip, "content.xml") ?: throw IllegalStateException("content.xml"))
        val presentation = root.child(OFFICE, "body")?.child(OFFICE, "presentation") ?: throw IllegalStateException("office:presentation")
        val auto = titleStyles(root)
        var first: String? = null
        val blocks = mutableListOf<ReadingBlock>()
        presentation.children().filter { it.isTag(DRAW, "page") }.forEachIndexed { index, page ->
            for (frame in page.children().filter { it.isTag(DRAW, "frame") }) {
                val box = frame.child(DRAW, "text-box") ?: continue
                if (frame.attr(PRESENTATION, "class") == "title") {
                    val text = box.descendants(TEXT, "p").joinToString(" ") { odfText(it) }
                    if (index == 0 && first == null) first = text
                    block(ReadingBlock.Kind.HEADING, text, 1)?.let(blocks::add)
                } else {
                    blocks += odfBlocks(box, auto)
                }
            }
        }
        val meta = dcTitle(zip, "meta.xml")
        return DocumentAnalysis(DocType.ODP, metadata = meta, metadataDetail = meta?.let { "metaXmlDcTitle" }, firstHeading = first, readingView = blocks)
    }

    // endregion

    // region DOCX

    private val skipW = setOf("del", "moveFrom", "instrText", "delText")

    private fun wText(p: Element): String {
        val out = StringBuilder()
        fun walk(e: Element) {
            for (c in e.children()) {
                if (c.namespaceURI == W && c.localName in skipW) continue
                when {
                    c.isTag(W, "t") -> out.append(c.textContent)
                    c.isTag(W, "tab") || c.isTag(W, "br") || c.isTag(W, "cr") -> out.append(' ')
                    else -> walk(c)
                }
            }
        }
        walk(p)
        return out.toString()
    }

    private fun wStyle(p: Element): String = p.child(W, "pPr")?.child(W, "pStyle")?.attr(W, "val") ?: ""

    private fun docxBlocks(container: Element): List<ReadingBlock> {
        val out = mutableListOf<ReadingBlock>()
        for (c in container.children()) {
            if (c.isTag(W, "p")) {
                val style = wStyle(c)
                val text = wText(c)
                val heading = Regex("(?i)(heading|titre)([1-9])").matchEntire(style)
                val numbering = c.child(W, "pPr")?.child(W, "numPr")
                when {
                    style.lowercase(Locale.ROOT) in setOf("title", "titre") -> block(ReadingBlock.Kind.HEADING, text, 1)
                    heading != null -> block(ReadingBlock.Kind.HEADING, text, heading.groupValues[2].toInt())
                    numbering != null -> block(ReadingBlock.Kind.LIST_ITEM, text, (numbering.child(W, "ilvl")?.attr(W, "val")?.toIntOrNull() ?: 0) + 1)
                    else -> block(ReadingBlock.Kind.PARAGRAPH, text)
                }?.let(out::add)
            } else if (c.namespaceURI == W && c.localName in setOf("tbl", "tr", "tc", "sdt", "sdtContent")) {
                out += docxBlocks(c)
            }
        }
        return out
    }

    private fun docx(zip: ZipFile): DocumentAnalysis {
        val body = xml(part(zip, "word/document.xml") ?: throw IllegalStateException("document.xml")).child(W, "body")
            ?: throw IllegalStateException("w:body")
        val first = body.descendants(W, "p").firstOrNull {
            wStyle(it).lowercase(Locale.ROOT) in setOf("title", "titre", "heading1", "titre1") && NotebookCourses.cleanWhitespace(wText(it)).isNotEmpty()
        }?.let(::wText)
        val meta = dcTitle(zip, "docProps/core.xml")
        return DocumentAnalysis(DocType.DOCX, metadata = meta, metadataDetail = meta?.let { "coreXmlDcTitle" }, firstHeading = first, readingView = docxBlocks(body))
    }

    // endregion

    // region PPTX

    private data class Paragraph(val level: Int, val noBullet: Boolean, val text: String)

    private fun aParagraphs(shape: Element): List<Paragraph> {
        val body = shape.child(P, "txBody") ?: return emptyList()
        return body.children().filter { it.isTag(A, "p") }.map { paragraph ->
            val properties = paragraph.child(A, "pPr")
            val level = properties?.getAttribute("lvl")?.toIntOrNull() ?: 0
            val noBullet = properties?.child(A, "buNone") != null
            val text = StringBuilder()
            paragraph.children().forEach { c ->
                when {
                    c.isTag(A, "r") || c.isTag(A, "fld") -> text.append(c.child(A, "t")?.textContent ?: "")
                    c.isTag(A, "br") -> text.append(' ')
                }
            }
            Paragraph(level, noBullet, text.toString())
        }
    }

    /** Règle 10 : ordre de `sldIdLst`, pas celui des noms de fichiers. */
    private fun slidePaths(zip: ZipFile): List<String> {
        val presentation = xml(part(zip, "ppt/presentation.xml") ?: return emptyList())
        val rels = xml(part(zip, "ppt/_rels/presentation.xml.rels") ?: return emptyList())
        val targets = rels.children().filter { it.isTag(REL, "Relationship") }.associate { it.getAttribute("Id") to it.getAttribute("Target") }
        val list = presentation.child(P, "sldIdLst") ?: return emptyList()
        return list.children().map { id ->
            val target = targets[id.attr(R, "id")].orEmpty()
            if (target.startsWith("/")) target.trimStart('/') else "ppt/$target"
        }
    }

    private fun pptx(zip: ZipFile): DocumentAnalysis {
        var first: String? = null
        val blocks = mutableListOf<ReadingBlock>()
        slidePaths(zip).forEachIndexed { index, path ->
            val tree = xml(part(zip, path) ?: throw IllegalStateException(path)).child(P, "cSld")?.child(P, "spTree") ?: return@forEachIndexed
            for (shape in tree.descendants(P, "sp")) {
                val placeholder = shape.child(P, "nvSpPr")?.child(P, "nvPr")?.child(P, "ph")
                val kind = placeholder?.let { if (it.hasAttribute("type")) it.getAttribute("type") else "obj" }
                val paragraphs = aParagraphs(shape)
                if (kind == "title" || kind == "ctrTitle") {
                    val text = paragraphs.joinToString(" ") { it.text }
                    if (index == 0 && first == null) first = text
                    block(ReadingBlock.Kind.HEADING, text, 1)?.let(blocks::add)
                    continue
                }
                for (paragraph in paragraphs) {
                    val item = if ((kind == "body" || kind == "obj") && !paragraph.noBullet) {
                        block(ReadingBlock.Kind.LIST_ITEM, paragraph.text, paragraph.level + 1)
                    } else {
                        block(ReadingBlock.Kind.PARAGRAPH, paragraph.text)
                    }
                    item?.let(blocks::add)
                }
            }
        }
        val meta = dcTitle(zip, "docProps/core.xml")
        return DocumentAnalysis(DocType.PPTX, metadata = meta, metadataDetail = meta?.let { "coreXmlDcTitle" }, firstHeading = first, readingView = blocks)
    }

    // endregion

    // region TXT / MD

    /** Règle 11 : UTF-8 (BOM retiré), sinon Windows-1252. */
    fun decodeText(data: ByteArray): String {
        val bytes = if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) data.copyOfRange(3, data.size) else data
        return runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, charset("windows-1252")) }
    }

    private fun firstTextLine(data: ByteArray, markdown: Boolean): String? {
        for (raw in decodeText(data).split(Regex("\r\n|\r|\n"))) {
            val line = if (markdown) raw.replaceFirst(Regex("^\\s{0,3}#{1,6}(?=\\s|$)"), "") else raw
            if (NotebookCourses.cleanWhitespace(line).isNotEmpty()) return line
        }
        return null
    }

    /** Vue « Lecture » des fichiers texte (hors vecteurs) : une ligne par paragraphe, titres Markdown. */
    fun textReading(data: ByteArray, markdown: Boolean): List<ReadingBlock> =
        decodeText(data).split(Regex("\r\n|\r|\n")).mapNotNull { raw ->
            val heading = if (markdown) Regex("^\\s{0,3}(#{1,6})\\s+(.*)$").find(raw) else null
            when {
                heading != null -> block(ReadingBlock.Kind.HEADING, heading.groupValues[2], heading.groupValues[1].length)
                markdown && Regex("^\\s*[-*+]\\s+").containsMatchIn(raw) -> block(ReadingBlock.Kind.LIST_ITEM, raw.replaceFirst(Regex("^\\s*[-*+]\\s+"), ""), 1)
                else -> block(ReadingBlock.Kind.PARAGRAPH, raw)
            }
        }

    // endregion
}
