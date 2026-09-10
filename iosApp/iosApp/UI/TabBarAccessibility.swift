import SwiftUI
import UIKit

/// SwiftUI의 탭 콘텐츠 라벨과 별개로 실제 UIKit 탭 항목에 발화 정보를 제공한다.
/// 탭의 표시·선택·카메라 lifecycle은 기존 TabView가 계속 담당한다.
struct TabBarAccessibility: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller {
        Controller()
    }

    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.updateAccessibility()
    }

    final class Controller: UIViewController {
        override func loadView() {
            let view = UIView()
            view.isUserInteractionEnabled = false
            view.isAccessibilityElement = false
            self.view = view
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            updateAccessibility()
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            updateAccessibility()
        }

        func updateAccessibility() {
            guard let items = tabBarController?.tabBar.items, items.count == 2 else { return }
            let labels = ["내비게이션", "신호등 확인"]
            let identifiers = ["tabs.navigation", "tabs.signal"]
            for (index, item) in items.enumerated() {
                if item.accessibilityLabel != labels[index] {
                    item.accessibilityLabel = labels[index]
                }
                item.accessibilityLanguage = "ko-KR"
                item.accessibilityIdentifier = identifiers[index]
            }
        }
    }
}
