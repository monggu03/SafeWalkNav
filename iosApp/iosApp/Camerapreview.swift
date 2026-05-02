//
//  CameraPreview.swift
//  iosApp
//
//  AVCaptureSession의 카메라 프리뷰를 SwiftUI에서 표시
//  TrafficLightDetector와 ContentView가 공유
//

import SwiftUI
import AVFoundation

// MARK: - CameraPreview (SwiftUI Wrapper)
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.session = session
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}
}

// MARK: - PreviewUIView
/// AVCaptureVideoPreviewLayer를 직접 레이어로 쓰는 UIView
/// → bounds 변화에 자동 반응해서 카메라 화면이 꽉 차게 표시됨
class PreviewUIView: UIView {
    var session: AVCaptureSession? {
        didSet {
            guard let session else { return }
            previewLayer.session = session
        }
    }

    override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = bounds
    }
}
