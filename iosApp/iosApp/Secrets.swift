//
//  Secrets.swift
//  iosApp
//
//  API 키 등 민감 정보를 Secrets.plist에서 읽어오는 헬퍼
//  Secrets.plist는 .gitignore에 등록되어 Git에 커밋되지 않음
//

import Foundation

enum Secrets {

    /// TMap API 앱 키. Secrets.plist에서 읽으며, 키가 없거나 비어 있으면 `nil`.
    ///
    /// 과거에는 키를 못 읽으면 `fatalError`로 앱을 즉시 종료했으나, 출시 빌드에서
    /// (키 누락·오타·만료 등으로) 시작 크래시가 나는 것을 막기 위해 옵셔널로 완화했다.
    /// 호출부는 `nil`(= 명시적 실패 상태)을 확인해 사용자에게 안내해야 한다.
    /// 실제 가드는 경로 탐색 진입점(NavigationCoordinator.onDestinationChosen)에 있다.
    static var tMapAppKey: String? {
        return readPlistValue(forKey: "TMapAppKey")
    }

    /// 앱 구동에 필요한 필수 키가 모두 유효한지 여부.
    /// 길 안내를 시작하기 전에 이 값으로 설정 무결성을 확인한다.
    static var isConfigured: Bool {
        return tMapAppKey != nil
    }

    // MARK: - Private Helpers

    /// Secrets.plist에서 문자열 값을 읽는다. 파일·키가 없거나 값이 비어 있으면 `nil`.
    private static func readPlistValue(forKey key: String) -> String? {
        guard let url = Bundle.main.url(forResource: "Secrets", withExtension: "plist"),
              let data = try? Data(contentsOf: url),
              let plist = try? PropertyListSerialization.propertyList(
                from: data, format: nil
              ) as? [String: Any],
              let value = plist[key] as? String,
              !value.isEmpty
        else {
            return nil
        }
        return value
    }
}
