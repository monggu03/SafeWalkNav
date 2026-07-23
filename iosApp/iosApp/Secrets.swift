//
//  Secrets.swift
//  iosApp
//
//  API 키 등 민감 정보를 Secrets.plist에서 읽어오는 헬퍼
//  Secrets.plist는 .gitignore에 등록되어 Git에 커밋되지 않음
//

import Foundation

enum Secrets {

    /// TMap API 앱 키 — 없으면 앱이 작동하지 않으므로 fatalError.
    static var tMapAppKey: String {
        return requiredValue(forKey: "TMapAppKey")
    }

    // MARK: - Private Helpers

    private static func requiredValue(forKey key: String) -> String {
        guard let v = readPlistValue(forKey: key), !v.isEmpty else {
            fatalError("""
                ⚠️ Secrets.plist에 '\(key)' 키가 없거나 비어있습니다.

                해결 방법:
                1. iosApp/Secrets.plist 파일이 있는지 확인
                2. Root에 '\(key)' (String) 항목이 있는지 확인
                3. 값이 비어있지 않은지 확인
                """)
        }
        return v
    }

    private static func readPlistValue(forKey key: String) -> String? {
        guard let url = Bundle.main.url(forResource: "Secrets", withExtension: "plist"),
              let data = try? Data(contentsOf: url),
              let plist = try? PropertyListSerialization.propertyList(
                from: data, format: nil
              ) as? [String: Any]
        else {
            return nil
        }
        return plist[key] as? String
    }
}
