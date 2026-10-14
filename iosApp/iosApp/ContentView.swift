import SwiftUI
import Shared

struct ContentView: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("S-scan")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("扫码工具")
                .font(.subheadline)
                .foregroundColor(.secondary)

            Divider()

            // 使用共享代码
            Text("平台: \(Platform().name)")
                .font(.body)

            Text("HTTP 检测: \(ScanUtils.shared.isHttpUrl(content: "https://example.com"))")
                .font(.body)

            Spacer()
        }
        .padding()
    }
}
