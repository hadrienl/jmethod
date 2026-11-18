import Foundation
import PDFKit
#if canImport(UIKit)
import UIKit
private typealias PlatformFont = UIFont
#else
import AppKit
private typealias PlatformFont = NSFont
#endif

/// Extraction d'un PDF avec PDFKit : titre des métadonnées (`/Title`, sinon `dc:title` du XMP) et lignes de la première
/// page avec leur taille de police. Un PDF chiffré (mot de passe d'ouverture) ou abîmé est « illisible ».
enum PDFText {
    static func extract(data: Data) -> DocumentExtraction {
        guard let document = PDFDocument(data: data) else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        // Règle 4 du README partagé : un PDF chiffré est ignoré, même s'il s'ouvre sans mot de passe.
        if document.isEncrypted || document.isLocked { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        guard let page = document.page(at: 0) else { return DocumentExtraction(error: DocumentExtraction.unreadable) }
        var result = DocumentExtraction()
        if let info = document.documentAttributes?[PDFDocumentAttribute.titleAttribute] as? String, !CourseTitleRules.cleanWhitespace(info).isEmpty {
            result.metadata = info
            result.metadataDetail = "pdfInfoTitle"
        } else if let xmp = xmpTitle(data) {
            result.metadata = xmp
            result.metadataDetail = "xmpDcTitle"
        }
        result.pdfLines = lines(page)
        return result
    }

    /// `dc:title` du paquet XMP (flux `/Metadata` du catalogue, lu avec CoreGraphics : PDFKit ne l'expose pas) :
    /// `rdf:li xml:lang="x-default"`, sinon le premier `rdf:li`, sinon le texte direct.
    static func xmpTitle(_ data: Data) -> String? {
        guard let provider = CGDataProvider(data: data as CFData), let pdf = CGPDFDocument(provider), let catalog = pdf.catalog else { return nil }
        var stream: CGPDFStreamRef?
        guard CGPDFDictionaryGetStream(catalog, "Metadata", &stream), let stream else { return nil }
        var format = CGPDFDataFormat.raw
        guard let content = CGPDFStreamCopyData(stream, &format) as Data?, let root = XMLElementNode.parse(content) else { return nil }
        guard let title = root.all("dc:title").first else { return nil }
        let items = title.all("rdf:li")
        if let item = items.first(where: { $0.attributes["xml:lang"] == "x-default" }) ?? items.first { return item.directText }
        return title.directText
    }

    /// Lignes non vides de la première page, dans l'ordre du texte, avec la plus grande taille de police de chaque ligne.
    static func lines(_ page: PDFPage) -> [PDFLine] {
        guard let attributed = page.attributedString else {
            return (page.string ?? "").components(separatedBy: .newlines).map(CourseTitleRules.cleanWhitespace)
                .filter { !$0.isEmpty }.map { PDFLine(text: $0, size: 0) }
        }
        let text = attributed.string as NSString
        var result: [PDFLine] = []
        var location = 0
        while location < text.length {
            let range = text.lineRange(for: NSRange(location: location, length: 0))
            let line = CourseTitleRules.cleanWhitespace(text.substring(with: range))
            if !line.isEmpty {
                var size: CGFloat = 0
                attributed.enumerateAttribute(.font, in: range) { value, _, _ in
                    if let font = value as? PlatformFont { size = max(size, font.pointSize) }
                }
                result.append(PDFLine(text: line, size: (Double(size) * 100).rounded() / 100))
            }
            location = NSMaxRange(range)
        }
        return result
    }
}

/// Entités XML prédéfinies et numériques.
enum XMLEntities {
    static func decode(_ text: String) -> String {
        var result = text
        for (entity, value) in [("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&apos;", "'")] {
            result = result.replacingOccurrences(of: entity, with: value)
        }
        if let expression = try? NSRegularExpression(pattern: "&#(x?)([0-9A-Fa-f]+);") {
            let source = result as NSString
            var output = ""
            var last = 0
            for match in expression.matches(in: result, range: NSRange(location: 0, length: source.length)) {
                output += source.substring(with: NSRange(location: last, length: match.range.location - last))
                let hex = source.substring(with: match.range(at: 1)) == "x"
                let digits = source.substring(with: match.range(at: 2))
                if let code = UInt32(digits, radix: hex ? 16 : 10), let scalar = Unicode.Scalar(code) {
                    output.unicodeScalars.append(scalar)
                } else {
                    output += source.substring(with: match.range)
                }
                last = match.range.location + match.range.length
            }
            output += source.substring(from: last)
            result = output
        }
        return result.replacingOccurrences(of: "&amp;", with: "&")
    }
}
