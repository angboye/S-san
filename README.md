# S-scan 扫码应用

> 一款多模式扫码应用：一维条码 / 二维码识别、历史记录、图像存档、连续扫码、批量导出。
>
> 现已通过 Kotlin Multiplatform（KMP）扩展到 **Android + iOS**，并提供 GitHub Actions 持续构建。

## ✨ 功能特性

### 扫码
- **多模式扫码**：默认全部 / 仅条码 / 仅二维码 / 连续扫码
- **智能去抖**：450ms 节流窗口 + 5 秒内重复结果抑制（最近 100 条缓存）
- **图像存档**：扫码时自动截取清晰图像，添加时间戳水印，存至系统相册（`Pictures/MultiQR`）
- **模糊检测**：拉普拉斯方差判模糊，低于阈值的图像自动丢弃
- **扫码反馈**：振动 / 提示音 / 语音播报，成功与模糊拒绝使用不同音效

### 历史与数据
- **本地持久化**：Room 数据库（`s_scan.db`，v4），软删除模型（历史 / 回收站）
- **搜索与筛选**：按内容、备注、日期文本匹配
- **分组管理**：扫码目标文件夹 + 历史批量归类
- **批量操作**：多选后批量分享 / 导出 / 删除 / 移动到分组
- **导出**：TXT 文本 + XLSX（手写 OOXML，无需 Apache POI）

### 设置与体验
- **登录认证**：手机号 + 短信验证码（当前为 Mock，验证码固定 `123456`）
- **语言**：简体中文 / English（两侧字符串完全同步）
- **主题**：浅色 / 深色（手动切换）
- **相机控制**：闪光灯、对焦、重置焦距、相册导入识别
- **连续扫码间隔**：可在设置中调节（`continuous_interval_ms`）

## 🛠 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.0.21 |
| 构建 | AGP / Gradle | 8.7.3 / 8.13 |
| 最低 SDK | Android 7.0 | API 24 |
| 目标 SDK | Android 15 | API 35 |
| JVM | 17（toolchain 锁定） | |
| 依赖注入 | Hilt | 2.52 |
| 本地数据库 | Room | 2.6.1 |
| 相机 | CameraX | 1.4.1 |
| 条码识别 | ML Kit Barcode Scanning | 17.3.0 |
| 异步 | Kotlin Coroutines | 1.9.0 |
| UI | Material Components + ViewBinding | 1.12.0 |
| 跨平台 | Kotlin Multiplatform + Compose Multiplatform | — |

## 🏗 架构

采用 **Hilt + MVVM + Repository + UseCase** 分层：

```
UI (Activity/ViewBinding)
   │  订阅 StateFlow/SharedFlow（repeatOnLifecycle）
   ▼
ViewModel（状态聚合、节流去重、协程调度）
   │  invoke
   ▼
UseCase（业务原子操作，可测试）
   │  call
   ▼
Repository → Room / MediaStore / SharedPreferences
```

- 入口：`LoginActivity`（MAIN/LAUNCHER）
- 扫码链路：`ScanActivity` → `MlKitProvider` → `ScanViewModel` → `SaveScanWithImageUseCase` → Room + MediaStore
- `AppSettings` 为 SharedPreferences 单例出入口，不注入 Hilt

## 📁 项目结构

```
S-scan/
├── app/                  # Android 应用模块（Kotlin + ViewBinding）
│   └── src/main/java/com/example/sscan/
│       ├── core/         # 相机、扫码、设置、存储、工具
│       ├── data/         # Room 数据库 + Repository 实现
│       ├── domain/       # UseCase
│       ├── feature/      # 各页面 Activity + ViewModel
│       └── di/           # Hilt 模块
├── shared/               # KMP 共享模块（业务逻辑跨 Android/iOS 复用）
│   └── src/
│       ├── commonMain/   # 跨平台代码（数据模型、工具）
│       ├── androidMain/  # Android 平台实现
│       └── iosMain/      # iOS 平台实现
├── iosApp/               # iOS 应用（SwiftUI）
│   ├── iosApp/
│   │   ├── SscanApp.swift
│   │   ├── ContentView.swift
│   │   └── Info.plist
│   └── project.yml       # XcodeGen 配置
└── .github/workflows/    # CI 工作流
    ├── android-build.yml # Android Debug 构建
    └── ios-build.yml     # iOS 构建（macOS runner）
```

## 🔨 构建与运行

### Android

```bash
# Debug 构建
./gradlew :app:assembleDebug

# Release 构建（R8 + 资源压缩，需签名）
./gradlew :app:assembleRelease

# 单元测试（JVM）
./gradlew :app:testDebugUnitTest

# 仪器测试（需模拟器/真机）
./gradlew :app:connectedDebugAndroidTest
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 29MB）。

> 国内网络环境请配置阿里云 Maven 镜像，否则 `dl.google.com` TLS 握手会失败。

### iOS

iOS 构建需在 **macOS** 上进行（Xcode）。本仓库提供 GitHub Actions 工作流自动构建：

```bash
# 本地（需 macOS）
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64   # 编译共享模块 Framework
# 使用 XcodeGen 生成 Xcode 工程并编译
cd iosApp && xcodegen generate && xcodebuild ...
```

## 🤖 CI / 持续构建

推送代码到 `main` 分支会自动触发：

| Workflow | 运行环境 | 产物 |
|----------|----------|------|
| `android-build.yml` | ubuntu-latest | Android Debug APK |
| `ios-build.yml` | macos-latest | iOS Framework + 模拟器构建 |

在仓库 **Actions** 标签页可查看构建结果并下载产物。

## 🚀 快速开始

1. **克隆仓库**
   ```bash
   git clone https://github.com/angboye/S-san.git
   cd S-san
   ```

2. **配置 Android SDK**
   新建 `local.properties`：
   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

3. **构建**
   ```bash
   ./gradlew :app:assembleDebug
   ```

4. **安装到设备**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   登录验证码固定为 `123456`。

## 📝 文档

- [开发设计文档](S-scan-开发设计文档.md)（背景资料，细节以代码为准）

## ⚠️ 已知限制

- iOS 端当前为 KMP 骨架，扫码/相机等平台能力通过 `expect/actual` 逐步补齐
- 鸿蒙 NEXT 纯血版暂需通过 CPF-KMP-CMP / Kuikly 适配；HarmonyOS 6.1 兼容版可直接安装 Android APK
- 认证为 Mock，接真实短信服务只需替换 `DatabaseModule.provideSmsService()`
