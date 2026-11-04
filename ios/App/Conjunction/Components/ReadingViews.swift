import SwiftUI
import ConjunctionCore

/// Renders legal Markdown in the app's typography (headings in Cinzel, body in Cormorant), the
/// subset ConjunctionCore's `MarkdownParser` understands (same as Android).
struct MarkdownView: View {
    let markdown: String

    private static func attributed(_ text: String) -> AttributedString {
        var out = AttributedString()
        for span in MarkdownParser.inline(text) {
            switch span {
            case .plain(let s):
                out += AttributedString(s)
            case .bold(let s):
                var a = AttributedString(s)
                a.foregroundColor = Palette.goldPale
                a.inlinePresentationIntent = .stronglyEmphasized
                out += a
            case .italic(let s):
                var a = AttributedString(s)
                a.inlinePresentationIntent = .emphasized
                out += a
            case .code(let s):
                var a = AttributedString(s)
                a.foregroundColor = Palette.goldPale
                out += a
            }
        }
        return out
    }

    var body: some View {
        let blocks = MarkdownParser.parse(markdown)
        LazyVStack(alignment: .leading, spacing: 4) {
            ForEach(blocks.indices, id: \.self) { i in
                block(blocks[i])
            }
        }
    }

    @ViewBuilder
    private func block(_ b: MdBlock) -> some View {
        switch b {
        case .heading(let level, let text):
            Text(Self.attributed(text))
                .typeStyle(level == 1 ? .headlineSmall : level == 2 ? .titleLarge : .titleMedium)
                .foregroundStyle(Palette.gold)
                .padding(.top, 16).padding(.bottom, 6)
                .accessibilityAddTraits(.isHeader)
        case .paragraph(let text):
            Text(Self.attributed(text)).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).padding(.vertical, 4)
                .fixedSize(horizontal: false, vertical: true)
        case .bullet(let text, let ordered, let indent):
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(ordered.map { "\($0)." } ?? "✦").typeStyle(.bodyMedium).foregroundStyle(Palette.gold).frame(width: 26, alignment: .leading)
                Text(Self.attributed(text)).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).fixedSize(horizontal: false, vertical: true)
            }
            .padding(.leading, CGFloat(indent * 14)).padding(.vertical, 2)
        case .tableRow(let cells):
            HStack(alignment: .top, spacing: 8) {
                ForEach(cells.indices, id: \.self) { i in
                    Text(Self.attributed(cells[i])).typeStyle(.bodySmall)
                        .foregroundStyle(i == 0 ? Palette.goldPale : Palette.moonstone)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .layoutPriority(i == 0 ? 0.8 : 1.2)
                }
            }
            .padding(.vertical, 3)
        case .quote(let text):
            Text(Self.attributed(text)).typeStyle(.bodyMedium, italic: true).foregroundStyle(Palette.moonstoneDim)
                .padding(.leading, 12).padding(.vertical, 4)
        case .rule:
            OrnateDivider().frame(maxWidth: .infinity).padding(.vertical, 10)
        }
    }
}

/// Full text of one bundled legal document. [onAcknowledge] adds "Got it" (the Safety Tips record).
struct LegalDocSheet: View {
    let doc: LegalDoc
    var onAcknowledge: ((LegalText) -> Void)?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let text = LegalTexts.load(doc)
        OrnateSheet(title: doc.title, onClose: { dismiss() }) {
            if let text {
                FinePrint("Version \(text.version)")
                MarkdownView(markdown: text.markdown)
                if let onAcknowledge {
                    GoldButton(L10n.safetyTipGotIt) { onAcknowledge(text); dismiss() }.padding(.top, 18)
                }
            } else {
                Text("This document could not be opened.").typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
            }
        }
    }
}

/// A reading presented as a tarot-like card: while it is written the card shows its gilded back
/// with the orrery; when ready it flips to the text (a cross-fade with Reduce Motion).
struct ReadingCard: View {
    let title: String
    let state: ReadingState
    var onRefresh: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var rotation: Double = 180
    @State private var reporting = false

    private var ready: Bool { if case .ready = state { return true } else { return false } }

    var body: some View {
        ZStack {
            back.modifier(FlipFace(angle: rotation, isFront: false))
            if case .ready(let reading) = state {
                front(reading).modifier(FlipFace(angle: rotation, isFront: true))
            }
        }
        .onAppear { rotation = ready ? 0 : 180 }
        .onChange(of: ready) { _, isReady in
            let target: Double = isReady ? 0 : 180
            if reduceMotion { rotation = target } else { withAnimation(.easeInOut(duration: 0.9)) { rotation = target } }
        }
        .sheet(isPresented: $reporting) {
            if case .ready(let r) = state { ReportReadingSheet(title: title, text: r.text) }
        }
    }

    private func front(_ reading: Reading) -> some View {
        OrnatePanel {
            GoldText(title, style: .titleLarge, alignment: .leading, heading: true)
            OrnateDivider().padding(.vertical, 6)
            Text(reading.text).typeStyle(.bodyLarge).foregroundStyle(Palette.moonstone).fixedSize(horizontal: false, vertical: true)
            Text(reading.source == .onDeviceAI ? AppText.aiSourceDevice : AppText.aiSourceFallback)
                .typeStyle(.bodySmall, italic: true).foregroundStyle(Palette.moonstoneDim).padding(.top, 12)
            if let note = reading.note { FinePrint(note) }
            FinePrint(AppText.entertainmentNote).padding(.top, 4)
            HStack(spacing: 10) {
                if let onRefresh { GhostButton("Write again", action: onRefresh) }
                GhostButton(L10n.reportReading, color: Palette.roseCopper) { reporting = true }
            }
            .padding(.top, 10)
        }
    }

    private var back: some View {
        OrnatePanel(alignment: .center) {
            Image("splash_mark").resizable().scaledToFit().frame(width: 96, height: 96).accessibilityHidden(true)
            GoldText(title, style: .titleLarge)
            switch state {
            case .loading(let status):
                OrreryLoader(size: 56).padding(.top, 10)
                Text(status).typeStyle(.bodyMedium, italic: true).foregroundStyle(Palette.moonstoneDim)
                    .multilineTextAlignment(.center).padding(.top, 8)
            case .failed(let message):
                Text(message).typeStyle(.bodyMedium).foregroundStyle(Palette.roseCopper).multilineTextAlignment(.center).padding(.top, 10)
                if let onRefresh { GhostButton("Try again", fullWidth: false, action: onRefresh).padding(.top, 10) }
            case .ready:
                EmptyView()
            }
        }
        .frame(minHeight: 260)
    }
}

/// One face of the flipping card: the angle animates frame by frame (Animatable), so the face
/// switches exactly at 90 degrees; nothing is re-laid out during the flip.
struct FlipFace: ViewModifier, Animatable {
    var angle: Double
    let isFront: Bool

    var animatableData: Double {
        get { angle }
        set { angle = newValue }
    }

    func body(content: Content) -> some View {
        content
            .rotation3DEffect(.degrees(isFront ? angle : angle - 180), axis: (x: 0, y: 1, z: 0), perspective: 0.4)
            .opacity((angle < 90) == isFront ? 1 : 0)
            .accessibilityHidden((angle < 90) != isFront)
    }
}

/// "Report this reading": asks what was wrong, then opens an email with the reading and the reason.
/// Birth details are not included. Nothing is sent until the person presses Send.
struct ReportReadingSheet: View {
    let title: String
    let text: String
    @Environment(\.dismiss) private var dismiss
    @State private var reason = ""
    @State private var draft: EmailDraft?
    @State private var note: String?

    var body: some View {
        OrnateSheet(title: L10n.reportReading, onClose: { dismiss() }) {
            Text(L10n.reportReadingBody).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
            OrnateTextField(label: "What was wrong? (optional)", text: $reason, multiline: true, minLines: 3, limit: 1000)
            if let note { Text(note).typeStyle(.bodySmall).foregroundStyle(Palette.roseCopper) }
            GoldButton("Write email") {
                let r = reason.trimmingCharacters(in: .whitespacesAndNewlines)
                let body = "What was wrong with this reading?\n\(r.isEmpty ? "(not given)" : r)\n\n---\n\(title)\n\(text)\n\n" +
                    "App: \(AppInfo.name) \(AppInfo.version) (\(AppInfo.build)), iOS \(UIDevice.current.systemVersion)"
                draft = EmailDraft(subject: "\(AppInfo.name) report: reading", body: body)
            }
        }
        .emailDraft($draft) { route in
            if route == .copied { note = L10n.format(L10n.reportNoEmailApp, AppInfo.supportEmail) } else { dismiss() }
        }
    }
}
