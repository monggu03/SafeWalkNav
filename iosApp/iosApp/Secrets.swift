//
//  Secrets.swift
//  iosApp
//
//  API 키 등 민감 정보를 Secrets.plist에서 읽어오는 헬퍼
//  Secrets.plist는 .gitignore에 등록되어 Git에 커밋되지 않음
//

import Foundation

enum Secrets {

    /// TMap API 앱 키
    static var tMapAppKey: String {
        return value(forKey: "TMapAppKey")
    }

    /// 서울 T-Data 신호제어기 잔여시간 API 키
    static var tDataApiKey: String {
        return value(forKey: "TDataApiKey")
    }

    // MARK: - Private Helper

    /// Secrets.plist에서 문자열 값을 읽어옴.
    /// 파일/키/값 중 하나라도 누락되면 fatalError로 즉시 크래시 — 빌드 단계에서
    /// 잘못된 설정을 빨리 잡아내기 위함 (런타임에 조용히 실패하지 않도록).
    private static func value(forKey key: String) -> String {
        guard let url = Bundle.main.url(forResource: "Secrets", withExtension: "plist"),
              let data = try? Data(contentsOf: url),
              let plist = try? PropertyListSerialization.propertyList(
                from: data, format: nil
              ) as? [String: Any],
              let value = plist[key] as? String,
              !value.isEmpty
        else {
            fatalError("""
                ⚠️ Secrets.plist에 '\(key)' 키가 없거나 비어있습니다.
                
                해결 방법:
                1. iosApp/Secrets.plist 파일이 있는지 확인
                2. Root에 '\(key)' (String) 항목이 있는지 확인
                3. 값이 비어있지 않은지 확인
                """)
        }
        return value
    }
}
