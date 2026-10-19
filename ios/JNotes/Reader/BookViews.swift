import SwiftUI
import WebKit
import UniformTypeIdentifiers

struct BookLibraryView: View {
    let jump: Int
    @StateObject private var library = EpubLibrary()
    @AppStorage("theme") private var theme = "dark_1"
    @State private var imports = false
    @State private var folder = false
    @State private var selected: BookEntry?
    @State private var deleting: BookEntry?
    private var palette: Palette { Palette(theme) }
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    HStack {
                        Text("Ebooks").font(.title3); Spacer()
                        Menu { Button("Add EPUB files") { imports = true }; Button("Add EPUB folder") { folder = true } } label: { Image(systemName: "plus").frame(width: 44, height: 44) }.accessibilityLabel("Add ebooks")
                    }.id("top")
                    if library.busy { ProgressView("Adding books…") }
                    if library.books.isEmpty { Text("Add EPUB books or a folder to begin reading.").foregroundStyle(palette.secondary).padding(24) }
                    ForEach(library.books.sorted { $0.lastRead == $1.lastRead ? $0.title.localizedCompare($1.title) == .orderedAscending : $0.lastRead > $1.lastRead }) { book in
                        Button { selected = book } label: {
                            HStack(spacing: 14) {
                                if let cover = book.cover, let image = UIImage(contentsOfFile: cover) { Image(uiImage: image).resizable().scaledToFit().frame(width: 55, height: 80) }
                                else { Image(systemName: "book.closed").font(.largeTitle).frame(width: 55, height: 80) }
                                VStack(alignment: .leading, spacing: 6) { Text(book.title).font(.headline); Text(book.author).font(.subheadline).foregroundStyle(palette.secondary); ProgressView(value: book.progress); Text("\(Int(book.progress * 100))% read").font(.caption) }
                                Spacer(minLength: 0)
                            }.card(palette.surface)
                        }.buttonStyle(.plain).contextMenu { Button("Remove book", role: .destructive) { deleting = book } }
                    }
                }.padding(16)
            }.onChange(of: jump) { _ in withAnimation { proxy.scrollTo("top", anchor: .top) } }
        }
        .fileImporter(isPresented: $imports, allowedContentTypes: [UTType(filenameExtension: "epub") ?? .data], allowsMultipleSelection: true) { result in Task { do { await library.importBooks(try result.get()) } catch { library.error = error.localizedDescription } } }
        .fileImporter(isPresented: $folder, allowedContentTypes: [.folder]) { result in Task { do { await library.importBooks([try result.get()], folder: true) } catch { library.error = error.localizedDescription } } }
        .fullScreenCover(item: $selected) { BookReaderView(book: $0, library: library) }
        .alert("E Reader", isPresented: Binding(get: { library.error != nil }, set: { if !$0 { library.error = nil } })) { Button("OK") { library.error = nil } } message: { Text(library.error ?? "") }
        .confirmationDialog("Remove book?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) { Button("Remove", role: .destructive) { if let deleting { library.remove(deleting) }; deleting = nil } }
    }
}
struct BookReaderView: View {
    let book: BookEntry
    @ObservedObject var library: EpubLibrary
    @Environment(\.dismiss) private var dismiss
    @AppStorage("theme") private var theme = "dark_1"
    @AppStorage("readerFontSize") private var fontSize = 19.0
    @State private var publication: EpubPublication?
    @State private var chapter: Int
    @State private var scroll: Double
    @State private var error: String?
    @State private var link: URL?
    init(book: BookEntry, library: EpubLibrary) { self.book = book; self.library = library; _chapter = State(initialValue: book.chapter); _scroll = State(initialValue: book.scroll) }
    private var palette: Palette { Palette(theme) }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { saveProgress(); dismiss() } label: { Image(systemName: "arrow.left").frame(width: 44, height: 44) }.accessibilityLabel("Back")
                Text(book.title).lineLimit(1).frame(maxWidth: .infinity)
                Menu {
                    Button("Smaller text") { fontSize = max(12, fontSize - 1) }; Button("Larger text") { fontSize = min(36, fontSize + 1) }
                    if let publication { ForEach(publication.chapters.indices, id: \.self) { index in Button("\(index + 1). \(publication.names[index])") { changeChapter(index) } } }
                } label: { Image(systemName: "list.bullet").frame(width: 44, height: 44) }.accessibilityLabel("Reader settings and chapters")
            }.background(palette.chrome)
            if let publication {
                EpubWebView(url: publication.chapters[chapter], root: publication.root, dark: palette.dark, fontSize: fontSize, initialScroll: scroll, onProgress: { fraction in scroll = fraction; saveProgress() }, onInternal: { url in
                    if let index = publication.chapters.firstIndex(where: { $0.standardizedFileURL.path == url.standardizedFileURL.path }) { chapter = index; scroll = 0 }
                }, onExternal: { link = $0 })
                HStack {
                    Button { changeChapter(chapter - 1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.disabled(chapter == 0)
                    Spacer(); Text("\(chapter + 1) / \(publication.chapters.count)").font(.caption); Spacer()
                    Button { changeChapter(chapter + 1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }.disabled(chapter == publication.chapters.count - 1)
                }
            } else if let error { Text(error).padding(24); Spacer() } else { ProgressView().frame(maxHeight: .infinity) }
        }
        .background(palette.background.ignoresSafeArea()).foregroundStyle(palette.text).tint(palette.primary).preferredColorScheme(palette.dark ? .dark : .light)
        .task { do { let p = try await library.open(book); chapter = min(max(0, chapter), p.chapters.count - 1); publication = p } catch { self.error = error.localizedDescription } }
        .onDisappear { saveProgress() }
        .alert("Open link?", isPresented: Binding(get: { link != nil }, set: { if !$0 { link = nil } })) { Button("Open") { if let link { UIApplication.shared.open(link) }; link = nil }; Button("Cancel", role: .cancel) { link = nil } } message: { Text(link?.absoluteString ?? "") }
    }
    private func changeChapter(_ index: Int) {
        guard let publication, publication.chapters.indices.contains(index) else { return }
        chapter = index; scroll = 0; saveProgress()
    }
    private func saveProgress() { if let publication { library.progress(book.id, chapter: chapter, scroll: scroll, count: publication.chapters.count) } }
}
struct EpubWebView: UIViewRepresentable {
    let url: URL, root: URL
    let dark: Bool, fontSize: Double, initialScroll: Double
    let onProgress: (Double) -> Void, onInternal: (URL) -> Void, onExternal: (URL) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration(); config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        let view = WKWebView(frame: .zero, configuration: config); view.isOpaque = false; view.backgroundColor = .clear
        view.navigationDelegate = context.coordinator; view.scrollView.delegate = context.coordinator
        context.coordinator.view = view
        // Block remote subresources as well as top-level navigations.
        let rules = "[{\"trigger\":{\"url-filter\":\"^https?://\"},\"action\":{\"type\":\"block\"}}]"
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "JNotesEpubOffline", encodedContentRuleList: rules) { list, error in
            guard let list else { return }; view.configuration.userContentController.add(list)
            context.coordinator.ready = true; context.coordinator.loadIfNeeded()
        }
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) { context.coordinator.parent = self; context.coordinator.loadIfNeeded() }
    final class Coordinator: NSObject, WKNavigationDelegate, UIScrollViewDelegate {
        var parent: EpubWebView; weak var view: WKWebView?
        var ready = false, loaded: URL?, appearance = "", restoring = false
        init(_ parent: EpubWebView) { self.parent = parent }
        func loadIfNeeded() {
            guard ready, let view else { return }
            let style = "\(parent.dark):\(parent.fontSize)"
            if loaded != parent.url {
                loaded = parent.url; appearance = style; restoring = true
                view.loadFileURL(parent.url, allowingReadAccessTo: parent.root)
            } else if appearance != style { appearance = style; applyStyle() }
        }
        func applyStyle() {
            let bg = parent.dark ? "#151816" : "#fbf5ec", fg = parent.dark ? "#f1efe8" : "#27231d"
            // App-supplied isolated script only; publication JavaScript stays disabled.
            let css = "html,body{background:\(bg)!important;color:\(fg)!important;font-size:\(Int(parent.fontSize))px!important;line-height:1.55!important;} body{padding:12px!important;}img,svg{max-width:100%!important;height:auto!important;}"
            let quoted = String(data: try! JSONSerialization.data(withJSONObject: [css]), encoding: .utf8)!
            view?.evaluateJavaScript("var s=document.getElementById('jnotes-style')||document.head.appendChild(document.createElement('style'));s.id='jnotes-style';s.textContent=\(quoted)[0];", in: nil, in: .defaultClient, completionHandler: nil)
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            applyStyle()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                let scroll = webView.scrollView
                scroll.setContentOffset(CGPoint(x: 0, y: max(0, scroll.contentSize.height - scroll.bounds.height) * self.parent.initialScroll), animated: false)
                self.restoring = false
            }
        }
        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) { if !decelerate { updateProgress(scrollView) } }
        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) { updateProgress(scrollView) }
        private func updateProgress(_ scroll: UIScrollView) {
            guard !restoring else { return }
            let height = max(1, scroll.contentSize.height - scroll.bounds.height)
            parent.onProgress(min(1, max(0, scroll.contentOffset.y / height)))
        }
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
            if url.isFileURL && url.standardizedFileURL.path.hasPrefix(parent.root.standardizedFileURL.path + "/") {
                if navigationAction.navigationType == .linkActivated { parent.onInternal(url) }; decisionHandler(.allow)
            } else { if navigationAction.navigationType == .linkActivated, ["https","http","mailto"].contains(url.scheme ?? "") { parent.onExternal(url) }; decisionHandler(.cancel) }
        }
    }
}
