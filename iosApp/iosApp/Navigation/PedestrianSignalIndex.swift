//
//  PedestrianSignalIndex.swift
//  iosApp
//
//  서울시 보행등 위치(공공누리 1유형, 서울특별시, 2026-02-13 갱신)를 격자 인덱스로 보관.
//  경로 탐색 시 CROSSWALK waypoint 의 신호등 유무 사전 판정에 쓴다.
//
//  ⚠️ 판정 반경·bbox 는 경험값이며 오탐률 미측정 — 긍정 안내("신호등 있음")에 쓰지 말 것.
//     부정 판정(.noneNearby)과 데이터 없음 고지(.noData)만 허용.
//

import Foundation
import CoreLocation

/// 횡단보도별 신호등 유무 상태 (횡단보도 단위 판정).
enum CrosswalkSignalStatus {
    case noneNearby   // 데이터 범위 안 + 반경 50m 보행등 0개
    case hasNearby    // 데이터 범위 안 + 1개 이상 → 현재 동작 그대로 (긍정 안내 없음)
    case noData       // 데이터 범위(서울 bbox) 밖
}

final class PedestrianSignalIndex {
    static let shared = PedestrianSignalIndex()

    /// 표본 2개(d_min 6.9/18.9m) 기준 보수적 설정. 오탐률 미측정.
    nonisolated static let matchRadiusM: Double = 50
    /// 데이터 커버리지(서울) 경계 — 진단 v1 좌표 품질 검사와 동일 범위.
    nonisolated static let seoulBBox = (latMin: 37.40, latMax: 37.70, lonMin: 126.70, lonMax: 127.20)
    /// 위도 0.001° ≈ 111m, 경도 ≈ 88m → 대상 셀 ±1(9셀)이면 50m 반경을 덮는다.
    private nonisolated static let cellDeg = 0.001

    private nonisolated static let resourceName = "pedlights_seoul_20260213"

    /// key = cellKey(latIdx, lonIdx). MainActor 에서만 접근(로드 완료 시 main 에서 대입).
    private var grid: [Int: [(lat: Double, lon: Double)]] = [:]
    private var loaded = false
    private var isLoading = false
    private var pendingCompletions: [(Bool) -> Void] = []

    /// 로드 완료 후 총 점 수. 로드 전 nil. (테스트·로그 검증용)
    private(set) var loadedPointCount: Int?

    /// 첫 경로 탐색 전(POI 선택 시점)에 1회 호출. CSV 파싱은 백그라운드,
    /// 완료 콜백은 main 에서. 실패해도 크래시 없이 loaded=false 유지 → 전부 .noData 처리.
    func loadIfNeeded(completion: @escaping (Bool) -> Void) {
        if loaded { completion(true); return }
        pendingCompletions.append(completion)
        guard !isLoading else { return }
        isLoading = true

        DispatchQueue.global(qos: .userInitiated).async {
            let result = Self.parseBundledCSV()
            DispatchQueue.main.async {
                if let (grid, count) = result {
                    self.grid = grid
                    self.loadedPointCount = count
                    self.loaded = true
                } else {
                    print("🚦 [PEDLIGHT] load failed — \(Self.resourceName).csv")
                }
                self.isLoading = false
                let completions = self.pendingCompletions
                self.pendingCompletions = []
                completions.forEach { $0(self.loaded) }
            }
        }
    }

    /// 데이터 커버리지(서울 bbox) 안인가.
    func isInsideCoverage(lat: Double, lon: Double) -> Bool {
        let b = Self.seoulBBox
        return lat >= b.latMin && lat <= b.latMax && lon >= b.lonMin && lon <= b.lonMax
    }

    /// 반경 내 보행등 수. 로드 전이면 nil.
    func countWithin(lat: Double, lon: Double, radiusM: Double) -> Int? {
        guard loaded else { return nil }
        let latIdx = Int(floor(lat / Self.cellDeg))
        let lonIdx = Int(floor(lon / Self.cellDeg))
        let origin = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        var count = 0
        for dLat in -1...1 {
            for dLon in -1...1 {
                let key = Self.cellKey(latIdx: latIdx + dLat, lonIdx: lonIdx + dLon)
                guard let points = grid[key] else { continue }
                for p in points {
                    let coord = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
                    if FollowingController.haversine(origin, coord) <= radiusM {
                        count += 1
                    }
                }
            }
        }
        return count
    }

    /// 횡단보도 단위 판정. 로드 전이면 nil (호출부는 .noData 로 취급 + 로그).
    func status(lat: Double, lon: Double) -> CrosswalkSignalStatus? {
        guard isInsideCoverage(lat: lat, lon: lon) else { return .noData }
        guard let n = countWithin(lat: lat, lon: lon, radiusM: Self.matchRadiusM) else {
            return nil
        }
        return n == 0 ? .noneNearby : .hasNearby
    }

    // MARK: - 내부

    /// 충돌 없음: 서울 bbox 에서 latIdx 37400~37700, lonIdx 126700~127200.
    private nonisolated static func cellKey(latIdx: Int, lonIdx: Int) -> Int {
        latIdx &* 100_000 &+ lonIdx
    }

    /// 번들 CSV(lat,lon 2열, 헤더 1줄) → 격자. 파싱 실패 행은 건너뛰고 개수 로그.
    private nonisolated static func parseBundledCSV() -> ([Int: [(lat: Double, lon: Double)]], Int)? {
        guard let url = Bundle(for: PedestrianSignalIndex.self)
            .url(forResource: resourceName, withExtension: "csv"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return nil
        }
        var grid: [Int: [(lat: Double, lon: Double)]] = [:]
        var count = 0
        var skipped = 0
        for line in text.split(separator: "\n").dropFirst() {   // 헤더 1줄 제외
            let parts = line.split(separator: ",")
            guard parts.count == 2,
                  let lat = Double(parts[0]), let lon = Double(parts[1]) else {
                skipped += 1
                continue
            }
            let key = cellKey(latIdx: Int(floor(lat / cellDeg)),
                              lonIdx: Int(floor(lon / cellDeg)))
            grid[key, default: []].append((lat: lat, lon: lon))
            count += 1
        }
        if skipped > 0 {
            print("🚦 [PEDLIGHT] CSV 파싱 스킵 \(skipped)행")
        }
        print("🚦 [PEDLIGHT] index loaded — \(count)점, \(grid.count)셀")
        return (grid, count)
    }
}
