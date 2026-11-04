import SwiftUI
import MessageUI
import UIKit

/// A prefilled email the person sends themselves (reports, reading reports, arbitration opt-out):
/// the in-app mail sheet when Mail is set up, else a mailto: link, else the text goes on the
/// clipboard (legal/IN_APP_CONSENT_COPY.md section 8). Nothing is sent without the person.
struct EmailDraft: Identifiable {
    let id = UUID()
    let subject: String
    let body: String
    var to: String = AppInfo.supportEmail
}

enum EmailRoute {
    case sheet
    case openedMailto
    case copied
}

enum Email {
    /// How [draft] will reach the person's mail app. `.sheet` = present `MailSheet`.
    @MainActor
    static func route(_ draft: EmailDraft) async -> EmailRoute {
        if MFMailComposeViewController.canSendMail() { return .sheet }
        var c = URLComponents()
        c.scheme = "mailto"
        c.path = draft.to
        c.queryItems = [URLQueryItem(name: "subject", value: draft.subject), URLQueryItem(name: "body", value: draft.body)]
        if let url = c.url, await UIApplication.shared.open(url) { return .openedMailto }
        UIPasteboard.general.string = "To: \(draft.to)\nSubject: \(draft.subject)\n\n\(draft.body)"
        return .copied
    }
}

/// MFMailComposeViewController in SwiftUI.
struct MailSheet: UIViewControllerRepresentable {
    let draft: EmailDraft
    var onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let vc = MFMailComposeViewController()
        vc.mailComposeDelegate = context.coordinator
        vc.setToRecipients([draft.to])
        vc.setSubject(draft.subject)
        vc.setMessageBody(draft.body, isHTML: false)
        return vc
    }

    func updateUIViewController(_ vc: MFMailComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }

        func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
            controller.dismiss(animated: true)
            onFinish()
        }
    }
}

/// Presents an email draft wherever it is attached: sheet, mailto, or clipboard with a note.
struct EmailPresenter: ViewModifier {
    @Binding var draft: EmailDraft?
    var onDone: (EmailRoute) -> Void = { _ in }
    @State private var sheet: EmailDraft?

    func body(content: Content) -> some View {
        content
            .onChange(of: draft?.id) { _, _ in
                guard let d = draft else { return }
                draft = nil
                Task { @MainActor in
                    let route = await Email.route(d)
                    if route == .sheet { sheet = d } else { onDone(route) }
                }
            }
            .sheet(item: $sheet) { d in
                MailSheet(draft: d) { onDone(.sheet) }.ignoresSafeArea()
            }
    }
}

extension View {
    func emailDraft(_ draft: Binding<EmailDraft?>, onDone: @escaping (EmailRoute) -> Void = { _ in }) -> some View {
        modifier(EmailPresenter(draft: draft, onDone: onDone))
    }
}
