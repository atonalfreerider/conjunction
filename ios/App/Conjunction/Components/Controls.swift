import SwiftUI
import ConjunctionCore

/// A consent / option checkbox with the whole row as the touch target. Callers start it unticked.
struct GoldCheckRow: View {
    @Binding var checked: Bool
    let text: String
    var note: String?

    var body: some View {
        Button { checked.toggle() } label: {
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 3).stroke(checked ? Palette.gold : Palette.goldPale, lineWidth: 2)
                    if checked {
                        RoundedRectangle(cornerRadius: 3).fill(Palette.gold)
                        Image(systemName: "checkmark").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.midnight)
                    }
                }
                .frame(width: 22, height: 22)
                .padding(.top, 3)
                VStack(alignment: .leading, spacing: 2) {
                    Text(text).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).fixedSize(horizontal: false, vertical: true)
                    if let note {
                        Text(note).typeStyle(.bodySmall, italic: true).foregroundStyle(Palette.moonstoneDim)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            .multilineTextAlignment(.leading)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(checked ? [.isSelected, .isButton] : .isButton)
        .accessibilityValue(checked ? "Ticked" : "Not ticked")
    }
}

struct GoldToggleRow: View {
    @Binding var isOn: Bool
    let title: String
    var subtitle: String?
    var enabled = true

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                if let subtitle { Text(subtitle).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim) }
            }
        }
        .tint(Palette.gold)
        .disabled(!enabled)
        .padding(.vertical, 8)
    }
}

/// Wraps its children onto as many rows as needed (Compose FlowRow).
struct FlowLayout: Layout {
    var spacing: CGFloat = 10

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > maxWidth {
                y += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: proposal.width ?? widest, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// Single-choice row of engraved "tablet" chips (radio semantics).
struct ChoiceChips<T: Hashable>: View {
    let options: [(T, String)]
    let selected: T?
    let onSelect: (T) -> Void

    var body: some View {
        FlowLayout(spacing: 10) {
            ForEach(options.indices, id: \.self) { i in
                let option = options[i]
                let isSelected = option.0 == selected
                Button { onSelect(option.0) } label: {
                    Text(option.1.uppercased())
                        .typeStyle(.labelMedium)
                        .foregroundStyle(isSelected ? Palette.midnight : Palette.goldPale)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 13)
                        .frame(minHeight: 44)
                        .background(
                            Group {
                                if isSelected { CutCornerShape(cut: 8).fill(GoldBrush.leaf) } else { CutCornerShape(cut: 8).fill(Palette.midnight.opacity(0.5)) }
                            }
                        )
                        .overlay(CutCornerShape(cut: 8).stroke(GoldBrush.leaf, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Text field on midnight glass with a gold outline and an engraved label above.
struct OrnateTextField: View {
    let label: String
    @Binding var text: String
    var multiline = false
    var minLines = 1
    var supporting: String?
    var keyboard: UIKeyboardType = .default
    var limit: Int?
    var onSubmit: (() -> Void)?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).typeStyle(.bodySmall).foregroundStyle(focused ? Palette.gold : Palette.moonstoneDim)
            Group {
                if multiline {
                    TextField("", text: $text, axis: .vertical).lineLimit(minLines...max(minLines, 8))
                } else {
                    TextField("", text: $text)
                }
            }
            .typeStyle(.bodyLarge)
            .foregroundStyle(Palette.moonstone)
            .tint(Palette.goldPale)
            .keyboardType(keyboard)
            .focused($focused)
            .submitLabel(onSubmit != nil ? .search : .done)
            .onSubmit { onSubmit?() }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Palette.midnight.opacity(focused ? 0.6 : 0.45).clipShape(CutCornerShape(cut: 8)))
            .overlay(CutCornerShape(cut: 8).stroke(focused ? Palette.gold : Palette.goldDeep, lineWidth: 1))
            .accessibilityLabel(label)
            if let supporting { Text(supporting).typeStyle(.bodySmall).foregroundStyle(Palette.moonstoneDim) }
        }
        .onChange(of: text) { _, newValue in
            if let limit, newValue.count > limit { text = String(newValue.prefix(limit)) }
        }
    }
}

/// A sheet styled like the Android ornate dialogs: starfield, gold title, close button, content.
struct OrnateSheet<Content: View>: View {
    let title: String
    var onClose: () -> Void
    @ViewBuilder var content: Content

    var body: some View {
        StarfieldBackground(dim: 0.7) {
            VStack(spacing: 0) {
                HStack {
                    GoldText(title, style: .titleLarge, alignment: .leading, heading: true)
                    Spacer()
                    Button(action: onClose) {
                        Image(systemName: "xmark").font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.goldPale)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Close")
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                OrnateDivider()
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) { content }
                        .padding(18)
                }
            }
        }
        .presentationBackground(Palette.midnight)
    }
}

/// Artwork lookups (art/ imported by ios/App/tools/import_art.py).
enum Art {
    static func sign(_ sign: ZodiacSign) -> String { "zodiac_\(sign.key)" }
    static func signKey(_ key: String?) -> String {
        guard let key, let s = ZodiacSign.fromKey(key.lowercased()) else { return "brand_mark" }
        return sign(s)
    }
    static func animal(_ animal: ChineseAnimal) -> String { "chinese_\(animal.key)" }
    static func animalKey(_ key: String?) -> String {
        guard let key, let a = ChineseAnimal.fromKey(key.lowercased()) else { return "brand_mark" }
        return animal(a)
    }
    static func blood(_ type: BloodType) -> String { "blood_\(type.key)" }
    static func element(_ element: FiveElement) -> String { "element_\(element.key)" }
}
