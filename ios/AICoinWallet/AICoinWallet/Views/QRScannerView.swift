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

            let preview = AVCaptureVideoPreviewLayer(session: session)
            preview.frame = view.layer.bounds
            preview.videoGravity = .resizeAspectFill
            view.layer.addSublayer(preview)

            // Off the main thread: starting a capture session blocks for long enough to drop frames
            // from the presentation animation.
            DispatchQueue.global(qos: .userInitiated).async { [session] in
                session.startRunning()
            }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            DispatchQueue.global(qos: .userInitiated).async { [session] in
                session.stopRunning()
            }
        }

        /// No camera — the simulator, a denied permission, a device without one. Says so rather
        /// than showing a black rectangle that looks broken.
        private func showUnavailable() {
            let label = UILabel()
            label.text = "No camera available — paste the address instead"
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
