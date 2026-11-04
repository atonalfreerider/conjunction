import SwiftUI
import UIKit
import ConjunctionCore

/// Decoded photos from app storage only (the owner's photo, photos received over Bluetooth).
/// Nothing is fetched from the network.
final class PhotoCache: @unchecked Sendable {
    static let shared = PhotoCache()
    private let cache = NSCache<NSURL, UIImage>()

    init() { cache.totalCostLimit = 16 * 1024 * 1024 }

    func image(_ url: URL, maxEdge: Int = 1024) -> UIImage? {
        if let hit = cache.object(forKey: url as NSURL) { return hit }
        guard let data = try? Data(contentsOf: url), let img = PhotoCodec.decode(data, maxEdge: maxEdge) else { return nil }
        let cost = Int(img.size.width * img.size.height * img.scale * img.scale * 4)
        cache.setObject(img, forKey: url as NSURL, cost: cost)
        return img
    }

    func invalidate(_ url: URL) { cache.removeObject(forKey: url as NSURL) }
    func removeAll() { cache.removeAllObjects() }
}

/// A local photo with an artwork placeholder while loading or when absent.
struct ProfilePhoto: View {
    let url: URL?
    let placeholder: String
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(placeholder).resizable().scaledToFill()
            }
        }
        .task(id: url) {
            guard let url else { image = nil; return }
            image = await Task.detached(priority: .userInitiated) { PhotoCache.shared.image(url) }.value
        }
    }
}

/// A photo someone sent, after on-device screening (SPEC 5c): shown when it was checked and looks
/// fine; blurred until "Show photo" when this phone could not check it; replaced by the zodiac art
/// with a warning when it was flagged (still viewable on purpose). [compact] = small avatars.
struct ScreenedPhoto: View {
    let url: URL?
    let screening: Screening
    let placeholder: String
    var compact = false
    @State private var revealed = false

    var body: some View {
        if url == nil || screening == .safe || revealed {
            ProfilePhoto(url: url, placeholder: placeholder)
        } else if compact {
            Image(placeholder).resizable().scaledToFill()
        } else {
            ZStack {
                if screening == .flagged {
                    Image(placeholder).resizable().scaledToFill()
                } else {
                    ProfilePhoto(url: url, placeholder: placeholder).blur(radius: 28, opaque: true)
                }
                VStack(spacing: 6) {
                    Text(screening == .flagged ? L10n.photoFlagged : L10n.photoHidden)
                        .typeStyle(.bodySmall).foregroundStyle(Palette.moonstone).multilineTextAlignment(.center)
                    GhostButton(L10n.photoShow, fullWidth: false) { revealed = true }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Palette.midnight.opacity(0.72))
            }
        }
    }
}
