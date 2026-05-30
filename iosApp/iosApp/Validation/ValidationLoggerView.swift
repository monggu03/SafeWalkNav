//
//  ValidationLoggerView.swift
//  iosApp
//
//  IMU 검증 로거 디버그 진입 화면 — 시작 / 정지 / CSV 공유.
//  ValidationLogger를 구동하고 누적 행 수를 표시한다.
//
//  ⚠️ 개발자 전용 측정 화면. 반드시 실기기에서 실행 (시뮬레이터는 센서값 무의미).
//

import SwiftUI

struct ValidationLoggerView: View {
    @StateObject private var logger = ValidationLogger()
    @State private var showShareSheet = false

    var body: some View {
        VStack(spacing: 24) {
            statusCard

            VStack(spacing: 12) {
                Button(action: { logger.start() }) {
                    Label("로깅 시작", systemImage: "record.circle")
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .disabled(logger.isLogging)

                Button(action: { logger.stop() }) {
                    Label("정지 및 저장", systemImage: "stop.circle")
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.bordered)
                .disabled(!logger.isLogging)

                Button(action: { showShareSheet = true }) {
                    Label("CSV 공유", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.bordered)
                .disabled(logger.lastSavedURL == nil || logger.isLogging)
            }

            Text("⚠️ 실기기에서만 의미 있는 값이 수집됩니다.\n(시뮬레이터는 나침반·모션 센서 없음)")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            Spacer()
        }
        .padding()
        .navigationTitle("IMU 검증 로거")
        .sheet(isPresented: $showShareSheet) {
            if let url = logger.lastSavedURL {
                ShareSheet(items: [url])
            }
        }
    }

    // MARK: - Status Card

    private var statusCard: some View {
        VStack(spacing: 8) {
            HStack {
                Circle()
                    .fill(logger.isLogging ? Color.red : Color.gray)
                    .frame(width: 12, height: 12)
                Text(logger.isLogging ? "로깅 중" : "정지됨")
                    .font(.headline)
            }
            Text("누적 행: \(logger.rowCount)")
                .font(.system(.title2, design: .monospaced))
            if let url = logger.lastSavedURL {
                Text("저장됨: \(url.lastPathComponent)")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.gray.opacity(0.1)))
    }
}

// MARK: - UIActivityViewController 래퍼

private struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

#Preview {
    NavigationStack {
        ValidationLoggerView()
    }
}
