//
//  TrafficSignalRemainingTimeParser.swift
//  iosApp
//
//  Android 의 traffic/TrafficSignalRemainingTimeParser.kt 와 1:1 대응.
//
//  서울시 실시간 신호정보 API 응답(JSON) 한 건을 받아서
//  보행자 신호 잔여시간(초) 과 상태(GREEN / RED_OR_WAIT / UNKNOWN)를 뽑는다.
//
//  규칙:
//   - 응답 JSON 안에서 처음 만나는 배열을 row 목록으로 간주 (findFirstArray)
//   - 첫 번째 row 만 사용
//   - 8 방향 보행자 잔여시간 필드 중 양수인 것 중 최소값 채택
//   - 단위는 centisecond → /10 해서 초 단위로 변환
//

import Foundation

struct TrafficSignalRemainingTime {
    let itstId: String
    let remainSeconds: Int?
    let rawFieldName: String?
    let status: TrafficLightStatus
}

enum TrafficLightStatus {
    case green
    case redOrWait
    case unknown
}

enum TrafficSignalRemainingTimeParser {

    private static let pedestrianFields: [String] = [
        "ntPdsgRmdrCs",
        "etPdsgRmdrCs",
        "stPdsgRmdrCs",
        "wtPdsgRmdrCs",
        "nePdsgRmdrCs",
        "sePdsgRmdrCs",
        "swPdsgRmdrCs",
        "nwPdsgRmdrCs"
    ]

    static func parse(jsonText: String) -> TrafficSignalRemainingTime? {
        guard let data = jsonText.data(using: .utf8) else {
            return nil
        }

        guard
            let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
            let rows = findFirstArray(in: root),
            let firstRow = rows.first as? [String: Any]
        else {
            return nil
        }

        let itstId = (firstRow["itstId"] as? String) ?? ""

        let candidate: (field: String, value: Int)? = pedestrianFields
            .compactMap { field -> (String, Int)? in
                guard let value = optInt(firstRow[field]), value > 0 else {
                    return nil
                }
                return (field, value)
            }
            .min(by: { $0.1 < $1.1 })

        let remainSeconds = candidate.map { $0.value / 10 }

        let status: TrafficLightStatus
        if let remain = remainSeconds, remain > 0 {
            status = .green
        } else if pedestrianFields.contains(where: { firstRow[$0] != nil }) {
            status = .redOrWait
        } else {
            status = .unknown
        }

        return TrafficSignalRemainingTime(
            itstId: itstId,
            remainSeconds: remainSeconds,
            rawFieldName: candidate?.field,
            status: status
        )
    }

    private static func optInt(_ raw: Any?) -> Int? {
        switch raw {
        case let value as Int: return value
        case let value as Int64: return Int(value)
        case let value as Double: return Int(value)
        case let value as NSNumber: return value.intValue
        case let value as String: return Int(value)
        default: return nil
        }
    }

    private static func findFirstArray(in obj: [String: Any]) -> [Any]? {
        for (_, value) in obj {
            if let array = value as? [Any] {
                return array
            }
            if let nested = value as? [String: Any],
               let found = findFirstArray(in: nested) {
                return found
            }
        }
        return nil
    }
}
