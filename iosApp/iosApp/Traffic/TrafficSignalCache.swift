//
//  TrafficSignalCache.swift
//  iosApp
//
//  Android 의 Room (TrafficSignalDatabase + TrafficSignalDao) 대응.
//  iOS 는 Core Data 까지 끌어올 만한 데이터량이 아니므로
//  Documents 디렉토리에 JSON 파일로 단순 캐시.
//
//  - 첫 호출 시 파일이 없으면 빈 배열을 돌려준다.
//  - 새로 채워 넣으면 기존 파일을 통째로 덮어쓴다 (Android 의 clearAll + insertAll 과 동일).
//

import Foundation

final class TrafficSignalCache {

    private let fileURL: URL

    init(fileName: String = "traffic_signals.json") {
        let docs = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first!
        self.fileURL = docs.appendingPathComponent(fileName)
    }

    func getAll() -> [TrafficSignalEntity] {
        guard let data = try? Data(contentsOf: fileURL) else {
            return []
        }

        return (try? JSONDecoder().decode([TrafficSignalEntity].self, from: data)) ?? []
    }

    func replaceAll(_ entities: [TrafficSignalEntity]) {
        guard let data = try? JSONEncoder().encode(entities) else {
            return
        }
        try? data.write(to: fileURL, options: .atomic)
    }
}
