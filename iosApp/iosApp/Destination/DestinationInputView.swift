//
//  DestinationInputView.swift
//  iosApp
//
//  §4-1 목적지 음성 입력.
//  전체화면 큰 탭 영역 1개 — 더블탭(또는 VoiceOver 활성화)으로 음성 인식 시작 →
//  STT 결과로 TMap POI 검색 → 첫 후보를 음성으로 확인 → 더블탭 확정 시
//  coordinator.onDestinationChosen(poi) 로 넘긴다(.guiding 전이).
//
//  음성의 소스 오브 트루스는 app 의 TtsManager. 화면 글씨는 저시력자/보호자 보조용.
//

import SwiftUI
import Combine
import CoreLocation
import shared

// MARK: - ViewModel

@MainActor
final class DestinationViewModel: ObservableObject {

    enum InputState: Equatable {
        case idle        // 대기 — 더블탭하면 듣기 시작
        case listening   // STT 수신 중
        case searching   // POI 검색 중
        case confirming  // 후보 확인 대기 — 더블탭하면 확정
        case error       // 실패(직후 idle 로 복귀)
    }

    @Published private(set) var state: InputState = .idle
    @Published private(set) var partial: String = ""      // 부분 인식 텍스트(저시력자 표시용)
    @Published private(set) var candidate: POIResult?

    private let stt: SttManager
    private let tts: TtsManager
    private let tMapClient: TMapApiClient
    private let locationTracker: LocationTracker
    private let onConfirm: (POIResult) -> Void

    private var cancellables = Set<AnyCancellable>()
    private var didStart = false

    init(
        stt: SttManager,
        tts: TtsManager,
        tMapClient: TMapApiClient,
        locationTracker: LocationTracker,
        onConfirm: @escaping (POIResult) -> Void
    ) {
        self.stt = stt
        self.tts = tts
        self.tMapClient = tMapClient
        self.locationTracker = locationTracker
        self.onConfirm = onConfirm
        subscribe()
    }

    // MARK: 구독

    private func subscribe() {
        // 부분 인식 텍스트 → 화면 표시
        stt.$partialText
            .sink { [weak self] text in
                Task { @MainActor in
                    guard let self, self.state == .listening else { return }
                    self.partial = text
                }
            }
            .store(in: &cancellables)

        // 최종 인식 결과 → 검색
        stt.finalResultPublisher
            .sink { [weak self] text in
                Task { @MainActor in self?.handleFinal(text) }
            }
            .store(in: &cancellables)
    }

    // MARK: 진입

    /// .destinationInput 진입 시 1회 — 위치 추적 시작 + 음성 권한 요청 + 안내.
    func onAppear() {
        guard !didStart else { return }
        didStart = true
        locationTracker.start()
        Task { await requestAuthAndGreet() }
    }

    private func requestAuthAndGreet() async {
        let granted = await stt.requestAuthorization()
        if granted {
            state = .idle
            tts.speak("목적지를 말씀하세요. 화면을 두 번 누르면 시작합니다.", display: true)
        } else {
            state = .error
            tts.speak("음성 인식 권한이 필요합니다. 설정에서 허용한 뒤 화면을 두 번 눌러 다시 시도해 주세요.", display: true)
        }

        #if DEBUG
        // STT 우회 자동 검증 경로 — 마이크 발화 없이 launch-arg 로 검색을 태운다.
        // 예: -debugDestKeyword 스타벅스 -debugAutoConfirm YES
        if let kw = UserDefaults.standard.string(forKey: "debugDestKeyword"), !kw.isEmpty {
            print("🧪 [DEBUG] auto debugSearch(keyword: \(kw))")
            debugSearch(keyword: kw)
        }
        #endif
    }

    // MARK: 탭 처리

    /// 전체화면 더블탭 / VoiceOver 활성화의 단일 진입점.
    func handleTap() {
        switch state {
        case .idle, .error:
            beginListening()
        case .confirming:
            confirm()
        case .listening, .searching:
            break   // 처리 중 — 무시
        }
    }

    private func beginListening() {
        partial = ""
        candidate = nil
        state = .listening
        stt.startListening()   // 권한 미허용이면 내부에서 재요청
    }

    private func handleFinal(_ text: String) {
        guard state == .listening else { return }
        stt.stopListening()
        let keyword = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !keyword.isEmpty else {
            state = .idle
            return
        }
        state = .searching
        Task { await search(keyword) }
    }

    private func search(_ keyword: String) async {
        let coord = locationTracker.currentLocation
        do {
            let pois = try await tMapClient.searchPOI(
                keyword: keyword,
                currentLat: coord.map { KotlinDouble(double: $0.latitude) },
                currentLon: coord.map { KotlinDouble(double: $0.longitude) },
                radiusKm: 1.0,
                maxResults: 5
            )
            if pois.isEmpty {
                tts.speak("결과를 찾지 못했습니다. 다시 말씀해 주세요.", display: true)
                state = .idle
            } else {
                let poi = pois[0]
                candidate = poi
                state = .confirming
                tts.speak("\(poi.name), \(poi.address). 여기로 안내할까요? 두 번 누르면 시작합니다.", display: true)

                #if DEBUG
                if UserDefaults.standard.bool(forKey: "debugAutoConfirm") {
                    // 확인 TTS 가 나가도록 잠깐 뒤 자동 확정.
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    print("🧪 [DEBUG] auto confirm → \(poi.name)")
                    confirm()
                }
                #endif
            }
        } catch {
            tts.speak("검색 중 오류가 발생했습니다. 다시 말씀해 주세요.", display: true)
            state = .idle
        }
    }

    private func confirm() {
        guard let poi = candidate else { return }
        onConfirm(poi)
    }

    /// 확인 취소 → 대기로 복귀.
    func cancel() {
        stt.stopListening()
        partial = ""
        candidate = nil
        state = .idle
    }

    #if DEBUG
    /// STT 우회 — 마이크 없이 검색 경로를 직접 태운다.
    /// 4-2/4-3 개발 내내 "말 안 하고 테스트"용으로 유지.
    func debugSearch(keyword: String) {
        partial = keyword
        state = .searching
        Task { await search(keyword) }
    }

    /// confirming 상태에서 확정을 직접 호출(디버그 버튼용).
    func debugConfirm() {
        confirm()
    }
    #endif
}

// MARK: - View

/// AppRootView 의 .destinationInput 케이스에서 VM 을 소유·주입하는 컨테이너.
struct DestinationInputScreen: View {
    @StateObject private var viewModel: DestinationViewModel

    init(deps: AppDependencies, coordinator: NavigationCoordinator) {
        _viewModel = StateObject(wrappedValue: DestinationViewModel(
            stt: deps.stt,
            tts: deps.tts,
            tMapClient: deps.tMapClient,
            locationTracker: deps.locationTracker,
            onConfirm: { poi in
                Task { await coordinator.onDestinationChosen(poi) }
            }
        ))
    }

    var body: some View {
        DestinationInputView(viewModel: viewModel)
    }
}

struct DestinationInputView: View {
    @ObservedObject var viewModel: DestinationViewModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 20) {
                Spacer()
                Text(headline)
                    .accessibleText(.action)
                    .foregroundColor(.white)
                    .padding(.horizontal, 24)

                if !viewModel.partial.isEmpty {
                    Text("“\(viewModel.partial)”")
                        .accessibleText(.secondary)
                        .foregroundColor(.yellow)
                        .padding(.horizontal, 24)
                        .accessibilityHidden(true)   // 음성은 TTS 가 주 채널
                }

                Spacer()

                if viewModel.state == .confirming {
                    Button(action: { viewModel.cancel() }) {
                        Text("다시 말하기")
                            .accessibleText(.secondary)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity, minHeight: 88)
                            .background(Color.white.opacity(0.15))
                    }
                    .accessibilityLabel("다시 말하기")
                    .accessibilityHint("두 번 탭하면 목적지를 다시 말합니다.")
                    .padding(.horizontal, 24)
                }
            }
        }
        #if DEBUG
        .overlay(alignment: .top) { debugBar }
        #endif
        // 전체화면 큰 탭 영역 — 사이티드 더블탭 + VoiceOver 활성화 모두 지원.
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { viewModel.handleTap() }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(accessibilityHint)
        .accessibilityAction { viewModel.handleTap() }
        .onAppear { viewModel.onAppear() }
        .accessibleFloor()
    }

    #if DEBUG
    /// STT 우회 디버그 바 — 마이크 없이 검색/확정을 태운다. 릴리스 제외.
    private var debugBar: some View {
        HStack(spacing: 8) {
            Button("DBG 검색") { viewModel.debugSearch(keyword: "스타벅스") }
            if viewModel.state == .confirming {
                Button("DBG 확정") { viewModel.debugConfirm() }
            }
        }
        .font(.system(size: 14, weight: .bold))
        .foregroundColor(.black)
        .padding(6)
        .background(Color.yellow.opacity(0.85))
        .cornerRadius(6)
        .padding(.top, 8)
        .accessibilityHidden(true)
    }
    #endif

    // MARK: 상태별 문구

    private var headline: String {
        switch viewModel.state {
        case .idle:       return "목적지를 말하려면\n화면을 두 번 누르세요"
        case .listening:  return "듣고 있어요…"
        case .searching:  return "검색 중…"
        case .confirming: return confirmHeadline
        case .error:      return "다시 시도해 주세요"
        }
    }

    private var confirmHeadline: String {
        guard let poi = viewModel.candidate else { return "확인해 주세요" }
        return "\(poi.name)\n여기로 안내할까요?\n두 번 누르면 시작"
    }

    private var accessibilityLabel: String {
        switch viewModel.state {
        case .idle:       return "목적지 입력"
        case .listening:  return "음성 인식 중"
        case .searching:  return "검색 중"
        case .confirming:
            if let poi = viewModel.candidate {
                return "\(poi.name), \(poi.address). 여기로 안내할까요?"
            }
            return "목적지 확인"
        case .error:      return "오류"
        }
    }

    private var accessibilityHint: String {
        switch viewModel.state {
        case .idle, .error:  return "화면을 두 번 누르면 목적지를 말합니다."
        case .confirming:    return "화면을 두 번 누르면 안내를 시작합니다."
        case .listening, .searching: return ""
        }
    }
}
