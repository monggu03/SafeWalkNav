//
//  DebugLogOverlay.swift
//  iosApp
//
//  Created by 이지민 on 5/8/26.
//

// iosApp/iosApp/Debug/DebugLogOverlay.swift
import SwiftUI

struct DebugLogOverlay: View {
    @ObservedObject var logger = DebugLogger.shared

    /// 시간 포맷터 (HH:mm:ss)
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text("Debug Log")
                    .font(.caption.bold())
                    .foregroundColor(.white)
                Spacer()
                Button("Clear") { logger.clear() }
                    .font(.caption)
                    .foregroundColor(.cyan)
            }
            .padding(.horizontal, 8)
            .padding(.top, 4)

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 1) {
                        ForEach(logger.entries) { entry in
                            HStack(alignment: .top, spacing: 4) {
                                Text(Self.formatter.string(from: entry.timestamp))
                                    .foregroundColor(.gray)
                                // StdoutCapture 로 들어온 라인은 tag 가 비어 있음 → 브래킷 생략.
                                if !entry.tag.isEmpty {
                                    Text("[\(entry.tag)]")
                                        .foregroundColor(.cyan)
                                }
                                Text(entry.message)
                                    .foregroundColor(entry.level.color)
                            }
                            .font(.system(size: 11, design: .monospaced))
                            .id(entry.id)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 8)
                }
                // 새 로그가 들어오면 자동으로 맨 아래로 스크롤
                .onChange(of: logger.entries.count) { _, _ in
                    if let last = logger.entries.last {
                        withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                    }
                }
            }
        }
        .frame(height: 180)                          // 하단 영역 높이
        .background(Color.black.opacity(0.75))       // 반투명 검정 배경
        .cornerRadius(8)
        .padding(.horizontal, 8)
        .padding(.bottom, 8)
    }
}
