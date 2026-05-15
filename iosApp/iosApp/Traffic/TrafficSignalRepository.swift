//
//  TrafficSignalRepository.swift
//  iosApp
//
//  Android 의 traffic/TrafficSignalRepository.kt 와 1:1 대응.
//
//  흐름:
//   1. 로컬 캐시(JSON 파일) 가 있으면 그대로 사용.
//   2. 없으면 KMM 의 SeoulTrafficSignalLocationApiClient 로 XML 페이지들을 받아온다.
//   3. XML 을 파싱하고 EPSG:5186 → WGS84 변환 후 캐시에 저장.
//   4. 도메인 모델인 TrafficSignalLocation (KMM 공유 타입) 리스트로 반환.
//

import Foundation
import shared

final class TrafficSignalRepository {

    private let apiClient: SeoulTrafficSignalLocationApiClient
    private let cache: TrafficSignalCache
    private let apiKeyAvailable: Bool

    init(
        apiClient: SeoulTrafficSignalLocationApiClient,
        cache: TrafficSignalCache,
        apiKeyAvailable: Bool
    ) {
        self.apiClient = apiClient
        self.cache = cache
        self.apiKeyAvailable = apiKeyAvailable
    }

    func getTrafficSignals() async -> [TrafficSignalLocation] {

        let local = cache.getAll()

        if !local.isEmpty {
            return local.map { $0.toDomain() }
        }

        guard apiKeyAvailable else {
            print("⚠️ [TrafficSignalRepository] Seoul API 키 없음 — 빈 배열 반환")
            return []
        }

        let remote = await fetchRemoteEntities()

        if !remote.isEmpty {
            cache.replaceAll(remote)
        }

        return cache.getAll().map { $0.toDomain() }
    }

    private func fetchRemoteEntities() async -> [TrafficSignalEntity] {

        let xmlPages: [String]
        do {
            xmlPages = try await apiClient.fetchTrafficSignalXmlPages(pageSize: Int32(1000))
        } catch {
            print("🔴 [TrafficSignalRepository] fetchTrafficSignalXmlPages 실패: \(error)")
            return []
        }

        var entities: [TrafficSignalEntity] = []

        for xml in xmlPages {
            let pageEntities = TrafficSignalXmlParser.parse(xml: xml)

            if pageEntities.isEmpty {
                break
            }

            entities.append(contentsOf: pageEntities)
        }

        return entities
    }
}

private extension TrafficSignalEntity {
    func toDomain() -> TrafficSignalLocation {
        return TrafficSignalLocation(
            itstId: id,
            lat: lat,
            lon: lon
        )
    }
}
