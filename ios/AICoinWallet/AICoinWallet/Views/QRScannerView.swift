import AVFoundation
import AudioToolbox
import SwiftUI

/// Scans a wallet address out of a QR code, so paying somebody does not mean retyping their
/// address — the one step in a hand-off where a typo costs real money and is unrecoverable.
///
/// What it hands back is only ever the scanned text; deciding whether that text is an address is
/// the caller's job, and it is done in front of the user rather than trusted.
struct QRScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onScan: onScan)
    }

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.coordinator = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: ScannerViewController, context: Context) {
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        private let onScan: (String) -> Void
        /// One scan per presentation: a camera fires the same code many times a second, and the
        /// second one would arrive after the sheet is already dismissing.
        private var delivered = false

        init(onScan: @escaping (String) -> Void) {
            self.onScan = onScan
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput,
                            didOutput objects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !delivered,
                  let object = objects.first as? AVMetadataMachineReadableCodeObject,
                  let value = object.stringValue else { return }
            delivered = true
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            onScan(value.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }

    final class ScannerViewController: UIViewController {
        var coordinator: Coordinator?
        private let session = AVCaptureSession()
        private var preview: AVCaptureVideoPreviewLayer?

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else {
                showUnavailable()
                return
            }
            session.addInput(input)

            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else {
                showUnavailable()
                return
            }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(coordinator, queue: .main)
            output.metadataObjectTypes = [.qr]

            if AVCaptureDevice.authorizationStatus(for: .video) == .denied
                || AVCaptureDevice.authorizationStatus(for: .video) == .restricted {
                showUnavailable("Camera access is off. Turn it on for AICoin Wallet in Settings.")
                return
            }

            let preview = AVCaptureVideoPreviewLayer(session: session)
            preview.frame = view.layer.bounds
            preview.videoGravity = .resizeAspectFill
            view.layer.addSublayer(preview)
            self.preview = preview

            // Off the main thread: starting a capture session blocks for long enough to drop frames
            // from the presentation animation.
            DispatchQueue.global(qos: .userInitiated).async { [session] in
                session.startRunning()
            }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.layer.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            DispatchQueue.global(qos: .userInitiated).async { [session] in
                session.stopRunning()
            }
        }

        /// No camera — the simulator, a denied permission, a device without one. Says so rather
        /// than showing a black rectangle that looks broken.
        private func showUnavailable(_ text: String = "No camera available. Paste the code's text instead.") {
            let label = UILabel()
            label.text = text
            label.textColor = .white
            label.numberOfLines = 0
            label.textAlignment = .center
            label.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(label)
            NSLayoutConstraint.activate([
                label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
                label.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, multiplier: 0.8),
            ])
        }
    }
}

/// The scanner as a sheet: full-bleed camera, a viewfinder to aim with, and a way out.
struct ScannerSheet: View {
    let title: String
    let onScan: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            QRScannerView(onScan: onScan, onCancel: { dismiss() })
                .ignoresSafeArea()
                .overlay {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .strokeBorder(.white.opacity(0.9), lineWidth: 3)
                        .frame(width: 250, height: 250)
                        .allowsHitTesting(false)
                }
                .overlay(alignment: .bottom) {
                    Text("Point the camera at a QR code")
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 40)
                }
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbarBackground(.visible, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
        }
    }
}
