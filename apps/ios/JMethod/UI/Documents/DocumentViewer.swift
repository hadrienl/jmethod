import PDFKit
import QuickLook
import SafariServices
import SwiftUI

/// Visionneuse d'un document de cours, sans quitter l'app (`notebook-to-course.md`, 6) : PDFKit pour les PDF,
/// zoom pour les images, aperçu système pour Word/PowerPoint/Excel, vue « Lecture » pour l'OpenDocument et le texte.
/// « Ouvrir avec… » partage le fichier local, jamais une adresse Pronote.
struct DocumentViewer: View {
    let document: CourseDocument
    let url: URL

    private var kind: DocumentKind { DocumentKind.detect(name: document.name, data: nil) }

    var body: some View {
        Group {
            if kind == .pdf {
                PDFKitView(url: url)
            } else if kind.isImage, let image = UIImage(contentsOfFile: url.path) {
                ZoomableImage(image: image)
            } else if kind.isOffice || kind == .other, QLPreviewController.canPreview(url as NSURL) {
                QuickLookView(url: url)
            } else {
                ReadingView(url: url, name: document.name)
            }
        }
        .navigationTitle(document.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                ShareLink(item: url) {
                    Label("Ouvrir avec…", systemImage: "square.and.arrow.up")
                }
                .accessibilityLabel("Ouvrir avec une autre app ou partager")
            }
        }
    }
}

/// PDF : défilement continu, zoom, pages.
struct PDFKitView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = .secondarySystemBackground
        view.document = PDFDocument(url: url)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {}
}

/// Image avec zoom par pincement et double toucher.
struct ZoomableImage: UIViewRepresentable {
    let image: UIImage

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 6
        scroll.delegate = context.coordinator
        scroll.backgroundColor = .secondarySystemBackground
        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.isAccessibilityElement = true
        imageView.accessibilityLabel = "Image du document"
        imageView.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
            imageView.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
        ])
        context.coordinator.imageView = imageView
        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.toggleZoom(_:)))
        doubleTap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(doubleTap)
        return scroll
    }

    func updateUIView(_ view: UIScrollView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?

        nonisolated func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            MainActor.assumeIsolated { imageView }
        }

        @objc func toggleZoom(_ gesture: UITapGestureRecognizer) {
            guard let scroll = gesture.view as? UIScrollView else { return }
            scroll.setZoomScale(scroll.zoomScale > 1 ? 1 : 2.5, animated: true)
        }
    }
}

/// Aperçu système (QuickLook) intégré à la page.
struct QuickLookView: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

/// Vue « Lecture » : texte structuré extrait du document (titres, paragraphes, listes).
struct ReadingView: View {
    let url: URL
    let name: String
    @State private var blocks: [ReadingBlock]?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.s) {
                Label("Aperçu simplifié", systemImage: "text.alignleft")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.textSecondary)
                if let blocks {
                    if blocks.isEmpty {
                        Text("Aucun texte lisible dans ce document. Utilise « Ouvrir avec… » pour l’afficher dans une autre app.")
                            .font(.subheadline).foregroundStyle(Theme.textSecondary)
                    }
                    ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                        switch block.kind {
                        case .heading:
                            Text(block.text).font((block.level ?? 1) <= 1 ? .title3.weight(.bold) : .headline).accessibilityAddTraits(.isHeader)
                                .padding(.top, Spacing.xs)
                        case .paragraph:
                            Text(block.text).font(.body)
                        case .listItem:
                            HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
                                Text("•").accessibilityHidden(true)
                                Text(block.text)
                            }
                            .font(.body)
                            .padding(.leading, CGFloat(max(0, (block.level ?? 1) - 1)) * Spacing.m)
                        }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, Spacing.l)
                }
            }
            .textSelection(.enabled)
            .screenContent()
        }
        .background(Theme.surface)
        .task {
            let url = self.url, name = self.name
            blocks = await Task.detached(priority: .userInitiated) {
                let data = (try? Data(contentsOf: url)) ?? Data()
                let extraction = DocumentText.extract(name: name, data: data)
                if let reading = extraction.reading { return reading }
                if extraction.kind == .txt || extraction.kind == .md { return DocumentText.plainReading(data, markdown: extraction.kind == .md) }
                return (extraction.pdfLines ?? []).map { ReadingBlock(kind: .paragraph, level: nil, text: $0.text) }
            }.value
        }
    }
}

/// Navigateur intégré pour les liens du cahier de textes (jamais la WebView de l'app, ni les cookies Pronote).
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: url)
        controller.preferredControlTintColor = UIColor(Theme.violet)
        return controller
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
}

/// URL ouvrable dans `SafariView` (élément de feuille).
struct WebLink: Identifiable, Equatable {
    let url: URL
    var id: String { url.absoluteString }
}
