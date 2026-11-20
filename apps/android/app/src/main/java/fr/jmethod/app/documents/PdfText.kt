package fr.jmethod.app.documents

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * Lecture minimale d’un PDF pour en tirer un titre, sans bibliothèque ni rendu : `/Title` du
 * dictionnaire Info (sinon `dc:title` du XMP) et lignes de texte de la première page avec leur taille
 * de police (tables `ToUnicode` ou encodages simples WinAnsi / MacRoman / Differences).
 *
 * Choix : une bibliothèque comme pdfbox-android ajouterait plusieurs Mo (polices, BouncyCastle) et
 * dépend d’Android dans les tests ; ce lecteur, testé sur la JVM, couvre les PDF exportés par Word et
 * LibreOffice (flux `FlateDecode`, flux d’objets PDF 1.5). Un PDF chiffré ou exotique ne donne
 * simplement pas de titre : les candidats suivants prennent le relais.
 */
object PdfText {
    private const val MAX_STREAM = 16 * 1024 * 1024

    /** Ligne de la page 1 : texte nettoyé et taille de police effective (arrondie à 0,01). */
    data class Line(val text: String, val size: Double)

    data class Result(val metadata: String?, val metadataDetail: String?, val lines: List<Line>)

    /**
     * Titre des métadonnées (`/Info /Title`, sinon `dc:title` du XMP) et lignes de la page 1.
     * `null` si le fichier est illisible ou chiffré (règle 6 : candidats 1 et 2 absents).
     */
    fun analyze(bytes: ByteArray): Result? {
        if (bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-") return null
        val doc = Parsed(bytes)
        if (doc.objects.isEmpty() || doc.trailerFound.not() || doc.encrypted) return null
        val info = doc.infoTitle()
        val (metadata, detail) = when {
            info != null && fr.jmethod.app.domain.NotebookCourses.cleanWhitespace(info).isNotEmpty() -> info to "pdfInfoTitle"
            else -> doc.xmpTitle()?.let { it to "xmpDcTitle" } ?: (null to null)
        }
        val lines = runCatching { doc.firstPageLines() }.getOrDefault(emptyList())
        return Result(metadata, detail, lines)
    }

    // region Valeurs PDF

    private data class Ref(val num: Int, val gen: Int)
    private data class Name(val value: String)
    private class PdfString(val bytes: ByteArray)
    private data class Operator(val value: String)

    private class PdfObject(val value: Any?, val stream: ByteArray?)

    /** Analyseur lexical commun aux objets et aux flux de contenu. */
    private class Lexer(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
        fun byte(i: Int) = data[i].toInt() and 0xFF
        fun isWhite(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code ||
            c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        fun skipSpace() {
            while (pos < end) {
                val c = byte(pos)
                if (isWhite(c)) pos++
                else if (c == '%'.code) { while (pos < end && byte(pos) != 10 && byte(pos) != 13) pos++ }
                else return
            }
        }

        /** Prochaine valeur, ou opérateur nu ; `null` en fin de données. */
        fun next(): Any? {
            skipSpace()
            if (pos >= end) return EOF
            val c = byte(pos)
            return when {
                c == '/'.code -> {
                    pos++
                    val start = pos
                    while (pos < end && !isWhite(byte(pos)) && !isDelim(byte(pos))) pos++
                    Name(decodeName(String(data, start, pos - start, Charsets.ISO_8859_1)))
                }
                c == '('.code -> literal()
                c == '<'.code && pos + 1 < end && byte(pos + 1) == '<'.code -> { pos += 2; dict() }
                c == '<'.code -> hex()
                c == '['.code -> { pos++; array() }
                c == ']'.code -> { pos++; END_ARRAY }
                c == '>'.code && pos + 1 < end && byte(pos + 1) == '>'.code -> { pos += 2; END_DICT }
                c == '{'.code || c == '}'.code -> { pos++; Operator(c.toChar().toString()) }
                else -> {
                    val start = pos
                    while (pos < end && !isWhite(byte(pos)) && !isDelim(byte(pos))) pos++
                    if (pos == start) { pos++; return Operator("?") }
                    val token = String(data, start, pos - start, Charsets.ISO_8859_1)
                    token.toDoubleOrNull()?.let { number ->
                        // Référence indirecte « n g R ».
                        val save = pos
                        skipSpace()
                        val genStart = pos
                        while (pos < end && byte(pos) in '0'.code..'9'.code) pos++
                        if (pos > genStart && number == Math.floor(number)) {
                            val gen = String(data, genStart, pos - genStart, Charsets.ISO_8859_1).toInt()
                            skipSpace()
                            if (pos < end && byte(pos) == 'R'.code && (pos + 1 >= end || isWhite(byte(pos + 1)) || isDelim(byte(pos + 1)))) {
                                pos++
                                return Ref(number.toInt(), gen)
                            }
                        }
                        pos = save
                        return number
                    }
                    when (token) {
                        "true" -> true
                        "false" -> false
                        "null" -> null
                        else -> Operator(token)
                    }
                }
            }
        }

        private fun decodeName(raw: String) = Regex("#([0-9A-Fa-f]{2})").replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }

        private fun literal(): PdfString {
            pos++
            val out = ByteArrayOutputStream()
            var depth = 1
            while (pos < end) {
                val c = byte(pos++)
                when (c) {
                    '\\'.code -> {
                        if (pos >= end) break
                        val e = byte(pos++)
                        when (e) {
                            'n'.code -> out.write(10)
                            'r'.code -> out.write(13)
                            't'.code -> out.write(9)
                            'b'.code -> out.write(8)
                            'f'.code -> out.write(12)
                            10 -> {}
                            13 -> if (pos < end && byte(pos) == 10) pos++
                            in '0'.code..'7'.code -> {
                                var value = e - '0'.code
                                repeat(2) { if (pos < end && byte(pos) in '0'.code..'7'.code) value = value * 8 + (byte(pos++) - '0'.code) }
                                out.write(value and 0xFF)
                            }
                            else -> out.write(e)
                        }
                    }
                    '('.code -> { depth++; out.write(c) }
                    ')'.code -> { depth--; if (depth == 0) return PdfString(out.toByteArray()); out.write(c) }
                    else -> out.write(c)
                }
            }
            return PdfString(out.toByteArray())
        }

        private fun hex(): PdfString {
            pos++
            val digits = StringBuilder()
            while (pos < end && byte(pos) != '>'.code) {
                val c = byte(pos++).toChar()
                if (c.isLetterOrDigit()) digits.append(c)
            }
            pos++
            if (digits.length % 2 == 1) digits.append('0')
            return PdfString(ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toIntOrNull(16)?.toByte() ?: 0 })
        }

        private fun array(): List<Any?> {
            val items = mutableListOf<Any?>()
            while (true) {
                val value = next()
                if (value === END_ARRAY || value === EOF) return items
                items += value
            }
        }

        private fun dict(): Map<String, Any?> {
            val items = mutableMapOf<String, Any?>()
            while (true) {
                val key = next()
                if (key === END_DICT || key === EOF) return items
                if (key is Name) items[key.value] = next()
            }
        }

        companion object {
            val EOF = Any()
            val END_ARRAY = Any()
            val END_DICT = Any()
        }
    }

    // endregion

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, count)
                if (out.size() > MAX_STREAM) return null
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    /** Chaîne de texte PDF : UTF-16BE (BOM `FE FF`), UTF-8 (BOM), sinon PDFDocEncoding (≈ Latin-1). */
    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.ISO_8859_1)
    }

    private class Parsed(val data: ByteArray) {
        val objects = mutableMapOf<Int, PdfObject>()
        var root: Ref? = null
        var info: Ref? = null
        var encrypted = false
        var trailerFound = false

        init {
            val text = String(data, Charsets.ISO_8859_1)
            val header = Regex("(?<![0-9])(\\d+)\\s+(\\d+)\\s+obj\\b")
            for (match in header.findAll(text)) {
                val num = match.groupValues[1].toIntOrNull() ?: continue
                runCatching { parseObject(text, match.range.last + 1) }.getOrNull()?.let { objects[num] = it }
            }
            // Dictionnaires de fin (trailer) et flux de références croisées (PDF 1.5).
            // Dernier `trailer` (comme la référence) ; à défaut, flux de références croisées (PDF 1.5).
            Regex("trailer\\s*<<").findAll(text).lastOrNull()?.let { match ->
                (Lexer(data, match.range.last - 1).next() as? Map<*, *>)?.let { trailerFound = true; trailer(it) }
            }
            if (!trailerFound) {
                objects.values.lastOrNull { ((it.value as? Map<*, *>)?.get("Type") as? Name)?.value == "XRef" }?.let {
                    trailerFound = true
                    trailer(it.value as Map<*, *>)
                }
            }
            // Flux d’objets : objets compressés.
            objects.values.toList().forEach { obj ->
                val dict = obj.value as? Map<*, *> ?: return@forEach
                if ((dict["Type"] as? Name)?.value != "ObjStm") return@forEach
                val content = streamData(obj) ?: return@forEach
                val count = (dict["N"] as? Double)?.toInt() ?: return@forEach
                val first = (dict["First"] as? Double)?.toInt() ?: return@forEach
                val headerLexer = Lexer(content, 0, minOf(first, content.size))
                val pairs = (0 until count).mapNotNull {
                    val n = headerLexer.next() as? Double ?: return@mapNotNull null
                    val offset = headerLexer.next() as? Double ?: return@mapNotNull null
                    n.toInt() to offset.toInt()
                }
                pairs.forEachIndexed { i, (n, offset) ->
                    val start = first + offset
                    val stop = if (i + 1 < pairs.size) first + pairs[i + 1].second else content.size
                    if (start in 0..content.size && stop in start..content.size && n !in objects) {
                        objects[n] = PdfObject(Lexer(content, start, stop).next().takeIf { it !== Lexer.EOF }, null)
                    }
                }
            }
        }

        private fun trailer(dict: Map<*, *>) {
            (dict["Root"] as? Ref)?.let { root = it }
            (dict["Info"] as? Ref)?.let { info = it }
            if (dict["Encrypt"] != null) encrypted = true
        }

        private fun parseObject(text: String, offset: Int): PdfObject {
            val lexer = Lexer(data, offset)
            val value = lexer.next()
            lexer.skipSpace()
            if (value is Map<*, *> && text.startsWith("stream", lexer.pos)) {
                var start = lexer.pos + 6
                if (start < data.size && data[start] == 13.toByte()) start++
                if (start < data.size && data[start] == 10.toByte()) start++
                val length = (value["Length"] as? Double)?.toInt()
                val stop = if (length != null && start + length <= data.size && text.startsWith("endstream", skipWhite(text, start + length))) {
                    start + length
                } else {
                    text.indexOf("endstream", start).takeIf { it >= 0 } ?: data.size
                }
                return PdfObject(value, data.copyOfRange(start, stop))
            }
            return PdfObject(value.takeIf { it !== Lexer.EOF }, null)
        }

        private fun skipWhite(text: String, from: Int): Int {
            var i = from
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }

        fun resolve(value: Any?): Any? = if (value is Ref) objects[value.num]?.value else value

        fun streamOf(value: Any?): ByteArray? = (if (value is Ref) objects[value.num] else null)?.let(::streamData)

        fun streamData(obj: PdfObject): ByteArray? {
            val raw = obj.stream ?: return null
            val dict = obj.value as? Map<*, *> ?: return raw
            val filters = when (val filter = resolve(dict["Filter"])) {
                null -> emptyList()
                is Name -> listOf(filter.value)
                is List<*> -> filter.mapNotNull { (resolve(it) as? Name)?.value }
                else -> return null
            }
            var bytes: ByteArray = raw
            for (filter in filters) {
                bytes = when (filter) {
                    "FlateDecode", "Fl" -> inflate(bytes) ?: return null
                    else -> return null
                }
            }
            return bytes
        }

        fun infoTitle(): String? {
            val dict = resolve(info) as? Map<*, *> ?: return null
            val title = resolve(dict["Title"]) as? PdfString ?: return null
            return decodeText(title.bytes)
        }

        fun xmpTitle(): String? {
            val catalog = resolve(root) as? Map<*, *> ?: return null
            if (!catalog.containsKey("Metadata")) return null
            val xmp = streamOf(catalog["Metadata"])?.let { String(it, Charsets.UTF_8) } ?: return null
            val block = Regex("<dc:title\\b[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(xmp)?.groupValues?.get(1) ?: return null
            val items = Regex("<rdf:li\\b([^>]*)>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL).findAll(block).map { it.groupValues[1] to it.groupValues[2] }.toList()
            if (items.isEmpty()) return null
            val chosen = items.firstOrNull { "x-default" in it.first }?.second ?: items.first().second
            return xmlUnescape(chosen)
        }

        private fun xmlUnescape(text: String) =
            text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        /** Première page : premier `Kids` en profondeur ; ressources héritées comprises. */
        fun firstPageLines(): List<Line> {
            val catalog = resolve(root) as? Map<*, *> ?: return emptyList()
            var node = resolve(catalog["Pages"]) as? Map<*, *> ?: return emptyList()
            var resources = resolve(node["Resources"]) as? Map<*, *>
            var page: Map<*, *>? = null
            for (step in 0 until 32) {
                (resolve(node["Resources"]) as? Map<*, *>)?.let { resources = it }
                if ((node["Type"] as? Name)?.value == "Page") { page = node; break }
                val kids = resolve(node["Kids"]) as? List<*> ?: break
                node = resolve(kids.firstOrNull()) as? Map<*, *> ?: break
            }
            val current = page ?: return emptyList()
            val contents = when (val c = resolve(current["Contents"])) {
                is List<*> -> c.mapNotNull { streamOf(it) ?: return emptyList() }
                else -> listOf(streamOf(current["Contents"]) ?: return emptyList())
            }
            val fonts = (resolve(resources?.get("Font")) as? Map<*, *>).orEmpty()
                .mapNotNull { (key, value) -> (key as? String)?.let { it to FontDecoder.of(this, resolve(value) as? Map<*, *>) } }.toMap()
            val stream = java.io.ByteArrayOutputStream()
            contents.forEachIndexed { i, part -> if (i > 0) stream.write(10); stream.write(part) }
            return ContentReader(stream.toByteArray(), fonts).lines()
        }
    }

    // region Polices

    private class FontDecoder(val twoBytes: Boolean, val unicode: Map<Int, String>?, val simple: Array<String?>?) {
        fun decode(bytes: ByteArray): String {
            val out = StringBuilder()
            if (twoBytes) {
                var i = 0
                while (i + 1 < bytes.size) {
                    val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                    unicode?.get(code)?.let(out::append)
                    i += 2
                }
            } else {
                for (b in bytes) {
                    val code = b.toInt() and 0xFF
                    val mapped = unicode?.get(code) ?: simple?.get(code)
                    if (mapped != null) out.append(mapped)
                }
            }
            return out.toString()
        }

        companion object {
            val default by lazy { FontDecoder(false, null, winAnsi) }

            private val winAnsi: Array<String?> = Array(256) { code ->
                when {
                    code < 32 -> null
                    else -> String(byteArrayOf(code.toByte()), Charset.forName("windows-1252")).takeIf { it != "�" }
                }
            }

            fun of(doc: Parsed, font: Map<*, *>?): FontDecoder {
                font ?: return FontDecoder(false, null, winAnsi)
                val type0 = (font["Subtype"] as? Name)?.value == "Type0"
                val cmap = doc.streamOf(font["ToUnicode"])?.let { parseCMap(it) }
                if (type0) return FontDecoder(cmap?.second ?: true, cmap?.first, null)
                val table = winAnsi.copyOf()
                when (val encoding = doc.resolve(font["Encoding"])) {
                    is Map<*, *> -> (doc.resolve(encoding["Differences"]) as? List<*>)?.let { differences ->
                        var code = 0
                        differences.forEach { item ->
                            when (item) {
                                is Double -> code = item.toInt()
                                is Name -> { if (code in 0..255) table[code] = GlyphNames.unicode(item.value); code++ }
                            }
                        }
                    }
                }
                return FontDecoder(cmap?.second ?: false, cmap?.first, table)
            }

            /** Table `ToUnicode` : `bfchar` et `bfrange` ; vrai si les codes font deux octets. */
            fun parseCMap(bytes: ByteArray): Pair<Map<Int, String>, Boolean> {
                val text = String(bytes, Charsets.ISO_8859_1)
                val map = mutableMapOf<Int, String>()
                fun hexToInt(h: String) = h.toLongOrNull(16)?.toInt() ?: 0
                fun hexToText(h: String): String {
                    val clean = if (h.length % 4 == 0) h else h.padStart(((h.length + 3) / 4) * 4, '0')
                    val out = ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    return String(out, Charsets.UTF_16BE)
                }
                val twoBytes = Regex("begincodespacerange\\s*<([0-9A-Fa-f]+)>").find(text)?.groupValues?.get(1)?.length?.let { it >= 4 } ?: true
                for (block in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                    Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]*)>").findAll(block.groupValues[1]).forEach {
                        map[hexToInt(it.groupValues[1])] = hexToText(it.groupValues[2])
                    }
                }
                for (block in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                    Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f]*>|\\[[^\\]]*\\])").findAll(block.groupValues[1]).forEach { m ->
                        val low = hexToInt(m.groupValues[1])
                        val high = minOf(hexToInt(m.groupValues[2]), low + 65535)
                        val target = m.groupValues[3]
                        if (target.startsWith("[")) {
                            Regex("<([0-9A-Fa-f]*)>").findAll(target).forEachIndexed { i, t -> if (low + i <= high) map[low + i] = hexToText(t.groupValues[1]) }
                        } else {
                            val base = target.trim('<', '>')
                            val start = hexToText(base)
                            if (start.isNotEmpty()) {
                                for (code in low..high) {
                                    val last = start.last().code + (code - low)
                                    map[code] = start.dropLast(1) + last.toChar()
                                }
                            }
                        }
                    }
                }
                return map to twoBytes
            }
        }
    }

    // endregion

    // region Flux de contenu

    /**
     * Opérateurs `BT Tf Td TD Tm T* Tj TJ ' "` comme `content_lines` de la référence : un passage de
     * texte est placé à l’ordonnée `y` du `Tm` courant ; les passages consécutifs de même ligne de base
     * (à 1 point près) forment une ligne ; taille = taille de `Tf` × échelle verticale de `Tm`.
     * Dans un `TJ`, les nombres n’insèrent jamais d’espace.
     */
    private class ContentReader(val data: ByteArray, val fonts: Map<String, FontDecoder>) {
        private data class Run(val y: Double, val size: Double, val text: String)

        fun lines(): List<Line> {
            val lexer = Lexer(data)
            val stack = mutableListOf<Any?>()
            var size = 0.0
            var leading = 0.0
            var font: FontDecoder? = null
            var tm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            var tlm = tm.copyOf()
            val runs = mutableListOf<Run>()
            fun move(tx: Double, ty: Double) {
                val (a, b, c, d, e) = tlm.take(5)
                val f = tlm[5]
                tlm = doubleArrayOf(a, b, c, d, tx * a + ty * c + e, tx * b + ty * d + f)
                tm = tlm.copyOf()
            }
            fun show(bytes: ByteArray) {
                val decoder = font ?: FontDecoder.default
                runs += Run(tm[5], size * Math.hypot(tm[2], tm[3]), decoder.decode(bytes))
            }
            fun number(value: Any?) = value as? Double
            var guard = 0
            while (guard++ < 2_000_000) {
                val token = lexer.next()
                if (token === Lexer.EOF) break
                if (token !is Operator) {
                    stack += token
                    continue
                }
                when (token.value) {
                    "BT" -> { tm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0); tlm = tm.copyOf() }
                    "Tf" -> if (stack.size >= 2) {
                        number(stack.last())?.let { size = it }
                        (stack[stack.size - 2] as? Name)?.let { font = fonts[it.value] }
                    }
                    "TL" -> number(stack.lastOrNull())?.let { leading = it }
                    "Td" -> if (stack.size >= 2) move(number(stack[stack.size - 2]) ?: 0.0, number(stack.last()) ?: 0.0)
                    "TD" -> if (stack.size >= 2) {
                        leading = -(number(stack.last()) ?: 0.0)
                        move(number(stack[stack.size - 2]) ?: 0.0, number(stack.last()) ?: 0.0)
                    }
                    "Tm" -> if (stack.size >= 6) {
                        tm = DoubleArray(6) { number(stack[stack.size - 6 + it]) ?: 0.0 }
                        tlm = tm.copyOf()
                    }
                    "T*" -> move(0.0, -leading)
                    "Tj" -> (stack.lastOrNull() as? PdfString)?.let { show(it.bytes) }
                    "TJ" -> (stack.lastOrNull() as? List<*>)?.let { items ->
                        val out = java.io.ByteArrayOutputStream()
                        items.forEach { if (it is PdfString) out.write(it.bytes) }
                        show(out.toByteArray())
                    }
                    "'", "\"" -> (stack.lastOrNull() as? PdfString)?.let { move(0.0, -leading); show(it.bytes) }
                    "BI" -> skipInlineImage(lexer)
                }
                stack.clear()
            }
            val grouped = mutableListOf<Triple<Double, Double, StringBuilder>>()
            for (run in runs) {
                val last = grouped.lastOrNull()
                if (last != null && Math.abs(last.first - run.y) < 1) {
                    last.third.append(run.text)
                    grouped[grouped.size - 1] = Triple(last.first, maxOf(last.second, run.size), last.third)
                } else {
                    grouped += Triple(run.y, run.size, StringBuilder(run.text))
                }
            }
            return grouped.mapNotNull { (_, size, text) ->
                val clean = fr.jmethod.app.domain.NotebookCourses.cleanWhitespace(text.toString())
                if (clean.isEmpty()) null else Line(clean, Math.round(size * 100) / 100.0)
            }
        }

        /** Image en ligne : données binaires jusqu’à `EI`. */
        private fun skipInlineImage(lexer: Lexer) {
            var i = maxOf(lexer.pos, 1)
            while (i + 1 < data.size && !(data[i] == 'I'.code.toByte() && data[i - 1] == 'D'.code.toByte())) i++
            i++
            while (i + 2 < data.size) {
                if (data[i] == 'E'.code.toByte() && data[i + 1] == 'I'.code.toByte() &&
                    (data[i - 1].toInt() == 32 || data[i - 1].toInt() == 10 || data[i - 1].toInt() == 13)
                ) {
                    lexer.pos = i + 2
                    return
                }
                i++
            }
            lexer.pos = data.size
        }
    }

    // endregion
}

/** Noms de glyphes courants (encodages `Differences`) vers Unicode. */
internal object GlyphNames {
    private val names = mapOf(
        "space" to " ", "exclam" to "!", "quotedbl" to "\"", "numbersign" to "#", "dollar" to "$", "percent" to "%",
        "ampersand" to "&", "quotesingle" to "'", "quoteright" to "’", "quoteleft" to "‘", "parenleft" to "(", "parenright" to ")",
        "asterisk" to "*", "plus" to "+", "comma" to ",", "hyphen" to "-", "period" to ".", "slash" to "/", "colon" to ":",
        "semicolon" to ";", "less" to "<", "equal" to "=", "greater" to ">", "question" to "?", "at" to "@",
        "bracketleft" to "[", "backslash" to "\\", "bracketright" to "]", "underscore" to "_", "endash" to "–", "emdash" to "—",
        "guillemotleft" to "«", "guillemotright" to "»", "ellipsis" to "…", "degree" to "°", "bullet" to "•",
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9",
        "agrave" to "à", "acircumflex" to "â", "adieresis" to "ä", "ccedilla" to "ç", "eacute" to "é", "egrave" to "è",
        "ecircumflex" to "ê", "edieresis" to "ë", "icircumflex" to "î", "idieresis" to "ï", "ocircumflex" to "ô", "odieresis" to "ö",
        "ugrave" to "ù", "ucircumflex" to "û", "udieresis" to "ü", "ydieresis" to "ÿ", "oe" to "œ", "ae" to "æ",
        "Agrave" to "À", "Acircumflex" to "Â", "Ccedilla" to "Ç", "Eacute" to "É", "Egrave" to "È", "Ecircumflex" to "Ê",
        "Edieresis" to "Ë", "Icircumflex" to "Î", "Idieresis" to "Ï", "Ocircumflex" to "Ô", "Ugrave" to "Ù", "Ucircumflex" to "Û",
        "OE" to "Œ", "AE" to "Æ", "fi" to "fi", "fl" to "fl",
    )

    fun unicode(name: String): String? {
        names[name]?.let { return it }
        if (name.length == 1) return name
        Regex("^uni([0-9A-Fa-f]{4})$").find(name)?.let { return it.groupValues[1].toInt(16).toChar().toString() }
        Regex("^u([0-9A-Fa-f]{4,6})$").find(name)?.let { return String(Character.toChars(it.groupValues[1].toInt(16))) }
        return null
    }
}
