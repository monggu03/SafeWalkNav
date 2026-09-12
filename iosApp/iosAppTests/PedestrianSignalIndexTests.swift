//
//  PedestrianSignalIndexTests.swift
//  iosAppTests
//
//  진단 v2-b(analysis/traffic_signal_check)에서 확인된 값으로 고정한 회귀 테스트.
//  좌표는 TMap 코엑스 경로의 실제 CROSSWALK waypoint, 기대 개수는 out/crosswalk_match.csv.
//

import XCTest
@testable import SafeWalkNav

final class PedestrianSignalIndexTests: XCTestCase {

    private var index: PedestrianSignalIndex!

    override func setUp() async throws {
        index = PedestrianSignalIndex.shared
        let ok = await withCheckedContinuation { cont in
            index.loadIfNeeded { cont.resume(returning: $0) }
        }
        try XCTSkipUnless(ok || index.loadedPointCount != nil, "인덱스 로드 실패")
        XCTAssertTrue(ok, "번들 CSV 로드 실패")
    }

    func testTotalPointCount() {
        XCTAssertEqual(index.loadedPointCount, 25_378)
    }

    func testCoexCrosswalk1_r50() {
        // 봉은사역 7번출구 횡단보도 (d_min 6.9m)
        XCTAssertEqual(index.countWithin(lat: 37.513957, lon: 127.059740, radiusM: 50), 3)
    }

    func testCoexCrosswalk0_r50() {
        // 동측 횡단보도 (d_min 18.9m)
        XCTAssertEqual(index.countWithin(lat: 37.511621, lon: 127.062023, radiusM: 50), 3)
    }

    func testCoexCrosswalk0_r15_zero() {
        XCTAssertEqual(index.countWithin(lat: 37.511621, lon: 127.062023, radiusM: 15), 0)
    }

    func testStatusInsideSeoul() {
        XCTAssertEqual(index.status(lat: 37.513957, lon: 127.059740), .hasNearby)
    }

    func testGimhaeOutsideCoverage() {
        // 김해시 좌표 (전국신호등표준데이터 첫 행) — 서울 bbox 밖.
        XCTAssertFalse(index.isInsideCoverage(lat: 35.278503, lon: 128.766609))
        XCTAssertEqual(index.status(lat: 35.278503, lon: 128.766609), .noData)
    }
}
