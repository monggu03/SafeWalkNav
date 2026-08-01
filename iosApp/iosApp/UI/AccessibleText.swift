//
//  AccessibleText.swift
//  iosApp
//
//  저시력자 대상 — 화면 글씨를 크고 굵게. 고대비(노랑/검정)는 각 화면이 유지.
//  화면엔 축약된 핵심만 크게, 전체 자연문장은 음성(TTS)이 담당한다.
//
//  · 크기 기준(§1): 주 60 / 보조 40 / 조작안내 48 / 신호화면 56 (bold~semibold)
//  · 넘침 방지(§3): lineLimit(nil) + minimumScaleFactor(0.6) — 기본은 최대한 크게,
//    도저히 안 맞을 때만 그 범위 안에서 축소.
//  · Dynamic Type(§4): @ScaledMetric(relativeTo: .largeTitle) 로 시스템 글자 크기에
//    맞춰 커진다. 화면 루트에 .dynamicTypeSize(.large...) 를 걸어 기본값 아래로는
//    작아지지 않게 바닥을 둔다(accessibleFloor()).
//

import SwiftUI

enum TextRole {
    case primary     // 핵심 안내·남은 거리
    case secondary   // 횡단보도 수·목적지명
    case action      // 탭 유도 등 조작 안내
    case signal      // 신호 인식 화면 상태 글씨

    var size: CGFloat {
        switch self {
        case .primary:   return 60
        case .secondary: return 40
        case .action:    return 48
        case .signal:    return 56
        }
    }

    var weight: Font.Weight {
        switch self {
        case .primary, .action, .signal: return .bold
        case .secondary:                 return .semibold
        }
    }
}

private struct AccessibleTextModifier: ViewModifier {
    @ScaledMetric private var scaledSize: CGFloat
    private let weight: Font.Weight

    init(role: TextRole) {
        _scaledSize = ScaledMetric(wrappedValue: role.size, relativeTo: .largeTitle)
        self.weight = role.weight
    }

    func body(content: Content) -> some View {
        content
            .font(.system(size: scaledSize, weight: weight))
            .lineLimit(nil)
            .minimumScaleFactor(0.6)
            .multilineTextAlignment(.center)
    }
}

extension View {
    /// 저시력자 기준 큰 글씨 + 넘침 방지 + Dynamic Type 스케일.
    func accessibleText(_ role: TextRole) -> some View {
        modifier(AccessibleTextModifier(role: role))
    }

    /// 화면 루트에 적용 — 시스템 글자 크기가 기본(.large) 아래여도 그 이하로는 줄지 않도록 바닥.
    func accessibleFloor() -> some View {
        dynamicTypeSize(.large...)
    }
}
