import SwiftUI
import PhotosUI
import AVFoundation
import ConjunctionCore

private enum PalmStage {
    case intro
    case camera
    case analyzing(UIImage)
    case result(PalmScan)
}

/// Palm reading (Android `PalmScreen`): photograph the open palm (AVFoundation with the gold guide
/// overlay) or pick a photo (PhotosPicker, no library permission), find the hand with Vision,
/// measure the lines on the phone and have the on-device model write the reading. The photo lives
/// in memory and is dropped when you leave, unless you save it (app-private storage, never uploaded).
struct PalmScreen: View {
    @EnvironmentObject var app: AppModel
    @State private var stage: PalmStage = .intro
    @AppStorage("palm.hand") private var handRaw = Hand.right.rawValue
    @State private var error: String?
    @State private var reading: ReadingState = .loading()
    @State private var rationale = false
    @State private var pick: PhotosPickerItem?
    @State private var saved = false

    private var hand: Hand { Hand(rawValue: handRaw) ?? .right }

    var body: some View {
        Group {
            switch stage {
            case .camera:
                PalmCameraView(hand: hand, onCaptured: analyze, onCancel: { stage = .intro }, onError: { error = $0; stage = .intro })
            default:
                TabPage(title: "Palm Reading", subtitle: "Read on this phone — your photo is never uploaded") { content }
            }
        }
        .sheet(isPresented: $rationale) {
            PermissionRationaleSheet(title: L10n.permCameraTitle, message: L10n.permCameraBody, button: L10n.permCameraButton,
                                     art: "palm_lines_diagram") {
                rationale = false
                Task {
                    if await AVCaptureDevice.requestAccess(for: .video) { stage = .camera } else { error = L10n.permCameraDenied }
                }
            }
        }
        .onChange(of: pick) { _, item in
            guard let item else { return }
            pick = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self), let image = PhotoCodec.decode(data, maxEdge: 1280) else {
                    error = "That photo could not be opened."
                    return
                }
                analyze(image)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch stage {
        case .intro, .camera:
            Image("palm_lines_diagram").resizable().scaledToFit().frame(width: 240, height: 240)
                .accessibilityLabel("Palm with heart, head, life and fate lines")
            OrnatePanel {
                Text("Which hand?").typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
                FinePrint("Palmists read your dominant hand for the life you are making.")
                ChoiceChips(options: [(Hand.left, "Left hand"), (Hand.right, "Right hand")], selected: hand) { handRaw = $0.rawValue }
                    .padding(.top, 8)
                Text("Lay the hand flat, palm up, fingers slightly apart, in soft even light. Fill the outline.")
                    .typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone).padding(.top, 10)
            }
            GoldButton("Use the camera") {
                switch AVCaptureDevice.authorizationStatus(for: .video) {
                case .authorized: stage = .camera
                case .notDetermined: rationale = true
                default: error = L10n.permCameraDenied
                }
            }
            PhotosPicker(selection: $pick, matching: .images, photoLibrary: .shared()) {
                Text("CHOOSE A PHOTO").typeStyle(.labelMedium).foregroundStyle(Palette.goldPale)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(Palette.midnight.opacity(0.55).clipShape(CutCornerShape(cut: 10)))
                    .overlay(CutCornerShape(cut: 10).stroke(GoldBrush.leaf, lineWidth: 1))
            }
            if let error {
                Text(error).typeStyle(.bodyMedium).foregroundStyle(Palette.roseCopper).multilineTextAlignment(.center)
            }
        case .analyzing(let photo):
            ZStack {
                Image(uiImage: photo).resizable().scaledToFit().opacity(0.6).accessibilityLabel("Your palm photo")
                OrreryLoader(size: 96)
            }
            Text("Tracing your lines…").typeStyle(.titleMedium).foregroundStyle(Palette.goldPale)
        case .result(let scan):
            PalmOverlayImage(scan: scan)
            PalmScannerView(scan: scan)
            PalmSummary(features: scan.features)
            ReadingCard(title: "Your palm", state: reading)
            HStack(spacing: 10) {
                GhostButton(saved ? "Saved" : "Save photo", enabled: !saved) { saved = PalmScanner.save(scan.photo) }
                GoldButton("New reading") { stage = .intro }
            }
            FinePrint("Saved palm photos stay in this app's private storage and are erased by Delete all my data.")
        }
    }

    private func analyze(_ photo: UIImage) {
        stage = .analyzing(photo)
        error = nil
        saved = false
        let h = hand
        Task {
            do {
                let scan = try await PalmScanner.scan(photo, hand: h)
                stage = .result(scan)
                reading = .loading()
                let r = await app.readings.palm(scan.features) { status in Task { @MainActor in reading = .loading(status) } }
                reading = .ready(r)
            } catch {
                self.error = error.localizedDescription
                stage = .intro
            }
        }
    }
}

/// The photo with the detected joints and the four lines traced in colour.
private struct PalmOverlayImage: View {
    let scan: PalmScan

    var body: some View {
        let size = scan.photo.size
        Image(uiImage: scan.photo).resizable().aspectRatio(size, contentMode: .fit)
            .overlay(
                Canvas { ctx, sz in
                    let sx = sz.width / size.width, sy = sz.height / size.height
                    for p in scan.landmarks {
                        ctx.fill(Path(ellipseIn: CGRect(x: p.x * sx - 3, y: p.y * sy - 3, width: 6, height: 6)), with: .color(Palette.goldPale.opacity(0.7)))
                    }
                    let colors: [PalmLine: Color] = [.heart: Palette.roseCopper, .head: Palette.goldPale, .life: Palette.gold, .fate: Palette.moonstone]
                    for (line, detected) in scan.detection.lines {
                        var path = Path()
                        for (i, p) in detected.polyline.enumerated() {
                            let pt = CGPoint(x: p.x * sx, y: p.y * sy)
                            if i == 0 { path.move(to: pt) } else { path.addLine(to: pt) }
                        }
                        ctx.stroke(path, with: .color(colors[line] ?? .white), lineWidth: 3)
                    }
                }
            )
            .overlay(Rectangle().stroke(GoldBrush.leaf, lineWidth: 1))
            .accessibilityLabel("Your palm with the detected lines traced: " +
                                PalmLine.allCases.filter { scan.detection.lines[$0] != nil }.map(\.displayName).joined(separator: ", "))
    }
}

/// "Scanner view": the straightened palm after the heavy contrast pass, creases glowing gold.
private struct PalmScannerView: View {
    let scan: PalmScan

    var body: some View {
        if let enhanced = scan.detection.enhanced, let image = PalmScanner.scannerImage(enhanced) {
            OrnatePanel(padding: 14) {
                Text("SCANNER VIEW").typeStyle(.labelMedium).foregroundStyle(Palette.gold)
                Image(uiImage: image).resizable().aspectRatio(CGFloat(enhanced.width) / CGFloat(enhanced.height), contentMode: .fit)
                    .accessibilityLabel("Your palm straightened and contrast-enhanced, creases shown in gold")
                FinePrint("Fingers at the top, wrist at the bottom. Faint lines show best in soft, even light with the palm flat.")
            }
        }
    }
}

private struct PalmSummary: View {
    let features: PalmFeatures

    var body: some View {
        OrnatePanel {
            Text("\(features.handShape.displayName) hand".uppercased()).typeStyle(.titleMedium).foregroundStyle(Palette.gold)
            FinePrint(String(format: "Palm width/length %.2f · middle finger/palm %.2f · index/ring %.2f",
                             features.palmRatio, features.fingers.fingerToPalm, features.fingers.indexToRing))
            ForEach(PalmLine.allCases, id: \.self) { line in
                let lf = features.line(line)
                HStack {
                    Text(String(line.displayName.prefix(1)).uppercased() + String(line.displayName.dropFirst()) + " line")
                        .typeStyle(.bodyMedium).foregroundStyle(Palette.goldPale)
                    Spacer()
                    Text(describe(lf)).typeStyle(.bodyMedium).foregroundStyle(Palette.moonstone)
                }
                .padding(.vertical, 2)
            }
            if features.quality < 0.5 { FinePrint("The photo was hard to read (light or focus); this reading is extra playful.") }
        }
    }

    private func describe(_ lf: LineFeature) -> String {
        guard lf.present else { return "not clearly visible" }
        let length: String
        switch lf.lengthClass {
        case .long: length = "long"
        case .medium: length = "medium"
        case .short: length = "short"
        default: length = "faint"
        }
        return "\(length), \(lf.isCurved ? "curved" : "straight")"
    }
}

// MARK: - Camera

/// Full-screen camera with the gold hand-outline guide. The art shows a LEFT palm as the rear camera
/// sees it (thumb on the left), so it is mirrored for a right hand.
private struct PalmCameraView: View {
    let hand: Hand
    let onCaptured: (UIImage) -> Void
    let onCancel: () -> Void
    let onError: (String) -> Void
    @StateObject private var camera = PalmCamera()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: camera.session).ignoresSafeArea()
            Image("palm_guide_overlay").resizable().scaledToFit().padding(24)
                .scaleEffect(x: hand == .right ? -1 : 1, y: 1)
                .accessibilityLabel("Hand outline guide")
                .allowsHitTesting(false)
            VStack {
                Text("Fit your \(hand == .left ? "left" : "right") palm inside the outline")
                    .typeStyle(.titleMedium).foregroundStyle(Palette.goldPale).multilineTextAlignment(.center)
                    .padding(16)
                Spacer()
                HStack {
                    GhostButton("Cancel", fullWidth: false, action: onCancel)
                    Spacer()
                    Button {
                        camera.capture { result in
                            switch result {
                            case .success(let image): onCaptured(image)
                            case .failure(let e): onError("The photo could not be taken: \(e.localizedDescription)")
                            }
                        }
                    } label: {
                        Circle().fill(camera.busy ? Palette.goldDeep : Palette.gold).padding(8)
                            .overlay(Circle().stroke(GoldBrush.leaf, lineWidth: 3))
                            .frame(width: 78, height: 78)
                    }
                    .disabled(camera.busy)
                    .accessibilityLabel("Take palm photo")
                    Spacer()
                    Color.clear.frame(width: 80, height: 1)
                }
                .padding(24)
            }
        }
        .onAppear {
            camera.start { onError($0) }
        }
        .onDisappear { camera.stop() }
    }
}

/// AVFoundation capture: back wide camera, photo output, on a private session queue.
@MainActor
final class PalmCamera: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let sessionQueue = DispatchQueue(label: "com.metavirtuoso.conjunction.camera")
    @Published var busy = false
    private var completion: ((Result<UIImage, Error>) -> Void)?
    private var configured = false

    func start(onError: @escaping (String) -> Void) {
        let session = self.session, output = self.output
        let needsConfig = !configured
        configured = true
        sessionQueue.async {
            if needsConfig {
                session.beginConfiguration()
                session.sessionPreset = .photo
                guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                      let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input), session.canAddOutput(output) else {
                    session.commitConfiguration()
                    DispatchQueue.main.async { onError("The camera could not start on this device.") }
                    return
                }
                session.addInput(input)
                session.addOutput(output)
                output.maxPhotoQualityPrioritization = .quality
                session.commitConfiguration()
            }
            if !session.isRunning { session.startRunning() }
        }
    }

    func stop() {
        let session = self.session
        sessionQueue.async { if session.isRunning { session.stopRunning() } }
    }

    func capture(_ done: @escaping (Result<UIImage, Error>) -> Void) {
        guard !busy else { return }
        busy = true
        completion = done
        let settings = AVCapturePhotoSettings()
        settings.photoQualityPrioritization = .quality
        output.capturePhoto(with: settings, delegate: self)
    }

    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let result: Result<UIImage, Error>
        if let error {
            result = .failure(error)
        } else if let data = photo.fileDataRepresentation(), let image = PhotoCodec.decode(data, maxEdge: 1280) {
            result = .success(image)
        } else {
            result = .failure(PalmScanError(message: "no image data"))
        }
        Task { @MainActor in
            self.busy = false
            self.completion?(result)
            self.completion = nil
        }
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.previewLayer.session = session
        v.previewLayer.videoGravity = .resizeAspectFill
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}
