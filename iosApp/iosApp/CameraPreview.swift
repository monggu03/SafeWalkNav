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

    /// SwiftUI가 처음 뷰를 그릴 때 호출 — PreviewUIView를 만들고 세션 주입.
    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.session = session
        return view
    }

    /// SwiftUI state가 바뀌어 갱신이 필요할 때 호출되지만,
    /// 세션은 한 번 주입하면 끝이라 갱신할 게 없어 비워둠.
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

    /// UIView의 backing layer를 AVCaptureVideoPreviewLayer로 교체.
    /// → 별도의 sublayer 추가 없이 layer 자체가 카메라 프리뷰가 됨.
    override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    /// layerClass 덕분에 항상 AVCaptureVideoPreviewLayer로 강제 캐스팅 가능.
    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    /// 뷰 크기가 바뀔 때마다 호출 — 프리뷰 레이어를 bounds에 꽉 차게(aspect fill) 맞춤.
    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = bounds
    }
}
