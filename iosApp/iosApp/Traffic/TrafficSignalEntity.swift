//
//  TrafficSignalEntity.swift
//  iosApp
//
//  Android Room 의 TrafficSignalEntity 와 1:1 대응.
//  로컬 캐시(파일)로 저장 가능하도록 Codable.
//

import Foundation

struct TrafficSignalEntity: Codable {
    let id: String
    let xcrd: Double
    let ycrd: Double
    let lat: Double
    let lon: Double
    let updatedAt: TimeInterval
}
