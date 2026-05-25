//
//  SpatialBeeperImpl.swift
//  iosApp
//
//  KMP 의 SpatialBeeper(actual class) 에 iOS 실제 재생 로직을 주입한다.
//  AVAudioEngine + AVAudioPlayerNode 로 사인파를 메모리 합성해 재생 — 외부 wav 리소스 불필요.
//
//  사용 패턴:
//      let beeper = navigationManager.spatialBeeper  // KMP 측 인스턴스
//      let impl = SpatialBeeperImpl(kmpBeeper: beeper)
//      // 이후 NavigationManager.handleVirtualWaypointPassed 가 호출되면 자동 재생.
//

import AVFoundation
import shared

@MainActor
final class SpatialBeeperImpl {

    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let format: AVAudioFormat

    private let sampleRate: Double = 44_100

    /// LOW: 600 Hz / 150 ms,  HIGH: 1200 Hz / 100 ms.
    /// 미리 합성해 둔 사인파 버퍼 (재생마다 합성 비용 절약 + 즉시 재생).
    /// 패닝은 AVAudioPlayerNode.pan 으로 채널 분배.
    private let lowBuffer: AVAudioPCMBuffer
    private let highBuffer: AVAudioPCMBuffer

    init(kmpBeeper: SpatialBeeper) {
        // Stereo 32-bit float — 패닝 처리에 가장 무난한 포맷.
        self.format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: 2,
            interleaved: false
        )!

        self.lowBuffer = Self.makeSineBuffer(
            format: format, frequency: 600, durationMs: 150
        )
        self.highBuffer = Self.makeSineBuffer(
            format: format, frequency: 1200, durationMs: 100
        )

        engine.attach(playerNode)
        engine.connect(playerNode, to: engine.mainMixerNode, format: format)
        do {
            // 다른 오디오(TTS 등) 와 함께 재생되도록 ambient + mixWithOthers.
            try AVAudioSession.sharedInstance().setCategory(
                .playback, mode: .default, options: [.mixWithOthers, .duckOthers]
            )
            try AVAudioSession.sharedInstance().setActive(true, options: [])
            try engine.start()
        } catch {
            print("[SpatialBeeperImpl] AVAudioEngine 시작 실패: \(error)")
        }

        // KMP → Swift 콜백 주입
        //   KMP 의 (Float, String, Int) -> Unit 시그니처가 Swift 측에선
        //   (KotlinFloat, NSString, KotlinInt) -> Void 로 노출되므로 명시 변환.
        kmpBeeper.iosImpl = { [weak self] pan, toneName, repeatCount in
            let p = pan.floatValue
            let t = toneName as String
            let r = repeatCount.intValue
            // 콜백은 KMP 측이 메인 스레드에서 호출한다는 보장이 없어 메인으로 hop.
            Task { @MainActor in
                self?.play(pan: p, toneName: t, repeatCount: r)
            }
        }
        kmpBeeper.iosStop = { [weak self] in
            Task { @MainActor in
                self?.stopAll()
            }
        }
    }

    // MARK: - Playback

    private func play(pan: Float, toneName: String, repeatCount: Int) {
        let buffer = (toneName == "HIGH") ? highBuffer : lowBuffer
        playerNode.pan = max(-1.0, min(1.0, pan))
        // 반복 재생 — 짧은 비프이므로 schedule 을 N회 누적
        for _ in 0..<max(1, repeatCount) {
            playerNode.scheduleBuffer(buffer, at: nil, options: [])
        }
        if !playerNode.isPlaying {
            playerNode.play()
        }
    }

    private func stopAll() {
        playerNode.stop()
    }

    // MARK: - Sine buffer

    /// `durationMs` 길이의 사인파 stereo 버퍼 생성. 좌/우 채널 모두 같은 사인 — pan 으로 분배.
    /// 양 끝 5ms 페이드로 클릭 노이즈 방지.
    private static func makeSineBuffer(
        format: AVAudioFormat, frequency: Double, durationMs: Int
    ) -> AVAudioPCMBuffer {
        let sampleRate = format.sampleRate
        let totalFrames = AVAudioFrameCount(sampleRate * Double(durationMs) / 1000.0)
        let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: totalFrames)!
        buf.frameLength = totalFrames

        let twoPiF = 2.0 * Double.pi * frequency
        let fadeFrames = AVAudioFrameCount(sampleRate * 0.005)
        let amplitude: Float = 0.5

        let leftPtr = buf.floatChannelData![0]
        let rightPtr = buf.floatChannelData![1]

        for i in 0..<Int(totalFrames) {
            let envelope: Float
            if i < Int(fadeFrames) {
                envelope = Float(i) / Float(fadeFrames)
            } else if i > Int(totalFrames - fadeFrames) {
                envelope = Float(totalFrames - AVAudioFrameCount(i)) / Float(fadeFrames)
            } else {
                envelope = 1.0
            }
            let sample = Float(sin(twoPiF * Double(i) / sampleRate)) * amplitude * envelope
            leftPtr[i] = sample
            rightPtr[i] = sample
        }
        return buf
    }
}
