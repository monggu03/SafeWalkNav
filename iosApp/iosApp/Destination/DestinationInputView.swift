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
        case confirming  // 후보 1개 확인 대기 — 더블탭하면 확정
        case selecting   // 후보 여러 개 — 목록에서 선택
        case error       // 실패(직후 idle 로 복귀)
    }

    /// 후보 1건(표시용 거리 포함). 거리순 정렬·목록 표시에 사용.
    struct Candidate: Identifiable {
        let id = UUID()
        let poi: POIResult
        let distanceM: Int?   // 현재 위치 없으면 nil
    }

    @Published private(set) var state: InputState = .idle
    @Published private(set) var partial: String = ""      // 부분 인식 텍스트(저시력자 표시용)
    @Published private(set) var candidate: POIResult?     // .confirming(단일) 용
    @Published private(set) var candidates: [Candidate] = []  // .selecting(복수) 용

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
            // Stage 2 이관: 조작 안내는 VoiceOver 라벨/힌트 담당 — 검증 완료 시 제거.
            // tts.speak("목적지를 말씀하세요. 화면을 두 번 누르면 시작합니다.", display: true)
        } else {
            state = .error
            // 권한 필요(상태 정보)는 TTS 유지, "설정에서 허용 후 재시도"(조작 안내)는 .error 힌트로 이관.
            tts.speak("음성 인식 권한이 필요합니다.", display: true)
            // Stage 2 이관 전 원문 — 검증 완료 시 제거.
            // tts.speak("음성 인식 권한이 필요합니다. 설정에서 허용한 뒤 화면을 두 번 눌러 다시 시도해 주세요.", display: true)
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
        case .listening, .searching, .selecting:
            break   // 처리 중 / 목록은 개별 행 버튼으로 — 전체화면 탭 무시
        }
    }

    private func beginListening() {
        partial = ""
        candidate = nil
        candidates = []
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
            guard !pois.isEmpty else {
                // Stage 2 이관: "다시 말씀해 주세요"(조작 안내)는 .idle 힌트 담당 — 검증 완료 시 원문 제거.
                // tts.speak("결과를 찾지 못했습니다. 다시 말씀해 주세요.", display: true)
                tts.speak("결과를 찾지 못했습니다.", display: true)
                state = .idle
                return
            }

            // 거리 계산(현재 위치 있을 때) 후 거리순 정렬.
            let cands: [Candidate] = pois.map { poi in
                let d = coord.map { Int(haversine($0, poi).rounded()) }
                return Candidate(poi: poi, distanceM: d)
            }.sorted { ($0.distanceM ?? .max) < ($1.distanceM ?? .max) }

            if cands.count == 1 {
                // 단일 후보 → 기존 확인 흐름(읽어주고 더블탭 확정).
                let poi = cands[0].poi
                candidate = poi
                candidates = []
                state = .confirming
                // Stage 2 이관: "두 번 누르면 시작합니다"(조작 안내)는 .confirming 힌트 담당 — 검증 완료 시 원문 제거.
                // tts.speak("\(poi.name), \(poi.address). 여기로 안내할까요? 두 번 누르면 시작합니다.", display: true)
                tts.speak("\(poi.name), \(poi.address). 여기로 안내할까요?", display: true)

                #if DEBUG
                if UserDefaults.standard.bool(forKey: "debugAutoConfirm") {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    print("🧪 [DEBUG] auto confirm(단일) → \(poi.name)")
                    confirm()
                }
                #endif
            } else {
                // 복수 후보 → 목록 선택. 개별 항목 읽기는 VoiceOver 에 위임(이중 발화 방지).
                candidate = nil
                candidates = cands
                state = .selecting
                // 화면 표시 개수(최대 3)와 안내 개수를 일치시킨다.
                let shown = min(cands.count, 3)
                // Stage 2 이관: "원하는 곳을 선택하세요"(조작 안내)는 후보 카드 힌트 담당 — 검증 완료 시 원문 제거.
                // tts.speak("\(shown)개의 장소를 찾았습니다. 원하는 곳을 선택하세요.", display: true)
                tts.speak("\(shown)개의 장소를 찾았습니다.", display: true)

                #if DEBUG
                if UserDefaults.standard.bool(forKey: "debugAutoConfirm") {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    print("🧪 [DEBUG] auto select(첫 후보) → \(cands[0].poi.name)")
                    select(cands[0].poi)
                }
                #endif
            }
        } catch {
            // Stage 2 이관: "다시 말씀해 주세요"(조작 안내)는 .idle 힌트 담당 — 검증 완료 시 원문 제거.
            // tts.speak("검색 중 오류가 발생했습니다. 다시 말씀해 주세요.", display: true)
            tts.speak("검색 중 오류가 발생했습니다.", display: true)
            state = .idle
        }
    }

    private func confirm() {
        guard let poi = candidate else { return }
        onConfirm(poi)
    }

    /// 목록에서 후보 하나를 선택 → 확정.
    func select(_ poi: POIResult) {
        onConfirm(poi)
    }

    /// 확인/선택 취소 → 대기로 복귀(다시 말하기).
    func cancel() {
        stt.stopListening()
        partial = ""
        candidate = nil
        candidates = []
        state = .idle
    }

    /// 두 좌표 사이 직선거리(m). 후보 거리 표시·정렬용.
    private func haversine(_ a: CLLocationCoordinate2D, _ poi: POIResult) -> Double {
        let R = 6_371_000.0
        let p1 = a.latitude * .pi / 180
        let p2 = poi.lat * .pi / 180
        let dp = (poi.lat - a.latitude) * .pi / 180
        let dl = (poi.lon - a.longitude) * .pi / 180
        let h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
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
        Group {
            if viewModel.state == .selecting {
                selectionView          // 복수 후보 — 개별 버튼(전체화면 탭 없음)
            } else {
                tapDrivenView          // 그 외 — 전체화면 더블탭/VoiceOver 활성화
            }
        }
        .onAppear { viewModel.onAppear() }
        .accessibleFloor()
    }

    // MARK: - 탭 구동 화면(.idle/.listening/.searching/.confirming/.error)

    private var tapDrivenView: some View {
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
    }

    // MARK: - 후보 선택 화면(.selecting) — fit-우선(공간 우선), 스크롤 없음

    /// 헤더 + 후보 최대 3개(남은 높이 균등 분할) + '다시 말하기' 를 한 화면에.
    /// 글씨는 각 영역 안에서 minimumScaleFactor 로 자동 축소되어 넘치지 않는다.
    private var selectionView: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 8) {
                // 헤더 — 고정, 필요시 축소.
                Text("장소를 선택하세요")
                    .font(.system(size: 34, weight: .bold))
                    .foregroundColor(.white)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                    .accessibilityHidden(true)   // 진입 안내는 이미 TTS 로 1회

                // 후보 최대 3개 — 남은 세로 공간을 균등 분할(스크롤 없음).
                ForEach(Array(viewModel.candidates.prefix(3))) { cand in
                    CandidateCard(candidate: cand) { viewModel.select(cand.poi) }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }

                // 푸터 — 고정 높이.
                Button(action: { viewModel.cancel() }) {
                    Text("다시 말하기")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)
                }
                .frame(height: 72)
                .background(Color.white.opacity(0.15))
                .cornerRadius(12)
                .accessibilityLabel("다시 말하기")
                .accessibilityHint("두 번 탭하면 목적지를 다시 말합니다.")
            }
            .padding(16)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
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
        case .selecting:  return ""   // selectionView 가 별도 렌더 — 미사용
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
        case .selecting:  return ""   // selectionView 가 별도 렌더 — 미사용
        case .error:      return "오류"
        }
    }

    private var accessibilityHint: String {
        switch viewModel.state {
        case .idle:       return "화면을 두 번 누르면 목적지를 말합니다."
        case .error:      return "설정에서 음성 인식 권한을 허용한 뒤, 화면을 두 번 누르면 다시 시도합니다."
        case .confirming: return "화면을 두 번 누르면 안내를 시작합니다."
        case .listening, .searching, .selecting: return ""
        }
    }
}

// MARK: - 후보 카드 (fit-우선: 부모가 준 균등 높이 안에서 글씨 자동 축소)

private struct CandidateCard: View {
    let candidate: DestinationViewModel.Candidate
    let onTap: () -> Void

    var body: some View {
        VStack(spacing: 4) {
            Text(candidate.poi.name)
                .font(.system(size: 38, weight: .bold))   // 시작은 크게
                .foregroundColor(.white)
                .minimumScaleFactor(0.4)                   // 길면 40%까지 자동 축소
                .lineLimit(3)
                .multilineTextAlignment(.center)
            if let d = candidate.distanceM {
                Text("\(d)미터")
                    .font(.system(size: 32, weight: .heavy))
                    .foregroundColor(.yellow)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)   // 부모가 준 균등 높이를 꽉 채움
        .padding(12)
        .background(Color(white: 0.12))
        .cornerRadius(16)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        // 접근성 — 축소돼도 VoiceOver 는 전체 라벨을 읽는다.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint("두 번 누르면 이곳으로 안내를 시작합니다.")
        .accessibilityAddTraits(.isButton)
    }

    private var accessibilityLabel: String {
        if let d = candidate.distanceM { return "\(candidate.poi.name), \(d)미터" }
        return candidate.poi.name
    }
}
