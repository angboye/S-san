# S-scan 扫码应用 · 开发设计文档

## 1. 项目概述

**S-scan** 是一款基于 Android 的多模式扫码应用，支持一维条码（EAN/UPC/Code128 等）与二维条码（QR Code/Data Matrix 等）的识别，提供历史记录管理、图像存档、连续扫码、筛选导出等能力。

### 1.1 基本信息

| 项目 | 内容 |
|------|------|
| 应用包名 | `com.example.sscan` |
| 开发语言 | Kotlin 2.0.21 |
| 最低 SDK | Android 7.0（API 24） |
| 目标 SDK | Android 15（API 35） |
| 编译 SDK | 35 |
| JVM 版本 | 17 |
| 构建工具 | Android Gradle Plugin 8.13.0 |
| 版本号 | 1.0 (versionCode=1) |
| 支持 ABI | armeabi-v7a / arm64-v8a / x86 / x86_64 |

### 1.2 核心功能

- **多模式扫码**：默认模式 / 仅扫条码 / 仅扫二维码 / 连续扫码
- **扫码去抖**：450ms 节流 + 5 秒去重（最近 100 条）
- **历史记录**：扫码结果本地持久化，支持关键字搜索、时间筛选、内容类型筛选
- **图像存档**：扫码时自动截取清晰图像，添加时间戳水印，保存至系统相册
- **批量操作**：长按多选，支持批量分享、批量导出为 TXT 文件
- **扫码反馈**：振动、提示音、语音播报（重复扫码/模糊/过快提示）
- **用户设置**：语言、主题、各类开关（振动/提示音/连续扫码/自动对焦等）
- **登录认证**：手机号 + 短信验证码（当前使用 Mock 服务）

---

## 2. 技术栈

### 2.1 核心框架与版本

| 类别 | 技术 | 版本 |
|------|------|------|
| 依赖注入 | Dagger Hilt | 2.52 |
| 本地数据库 | Room | 2.6.1 |
| 相机 | CameraX | 1.4.1 |
| 条码识别 | Google ML Kit Barcode Scanning | 17.3.0 |
| 异步 | Kotlin Coroutines | 1.9.0 |
| 生命周期 | AndroidX Lifecycle | 2.8.7 |
| UI | Material Components | 1.12.0 |
| ViewBinding | AndroidX ViewBinding | 已启用 |

### 2.2 关键依赖清单（[app/build.gradle.kts](file:///e:/S-scan/app/build.gradle.kts)）

```kotlin
// 相机
androidx.camera:camera-core / camera-camera2 / camera-lifecycle / camera-view / camera-extensions (1.4.1)

// 条码识别
com.google.mlkit:barcode-scanning (17.3.0)

// 数据库
androidx.room:room-runtime / room-ktx (2.6.1)

// 依赖注入
com.google.dagger:hilt-android (2.52)

// 协程
org.jetbrains.kotlinx:kotlinx-coroutines-android (1.9.0)

// 日志
com.jakewharton.timber:timber (5.0.1)
```

### 2.3 AndroidManifest 权限（[AndroidManifest.xml](file:///e:/S-scan/app/src/main/AndroidManifest.xml)）

```xml
<uses-permission android:name="android.permission.CAMERA" />        <!-- 相机扫码 -->
<uses-permission android:name="android.permission.VIBRATE" />        <!-- 扫码振动反馈 -->
<uses-permission android:name="android.permission.FLASHLIGHT" />     <!-- 闪光灯 -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />  <!-- 读取图片（Android 13+） -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

`FileProvider` 配置：用于共享图片文件（分享/导出功能），路径定义在 [xml/file_paths.xml](file:///e:/S-scan/app/src/main/res/xml/file_paths.xml)。

---

## 3. 项目架构

### 3.1 整体设计原则：Hilt + MVVM + Repository + UseCase

```
┌────────────────────────────────────────────────────────────────┐
│                         UI 层 (View)                           │
│  [MainActivity] [ScanActivity] [HistoryActivity]               │
│  [LoginActivity] [MineActivity] [HistoryDetailActivity] ...    │
│  ───────────────────────────────────────────────────────────── │
│  只观察 ViewModel 的 StateFlow/SharedFlow，不直接操作数据                                            │
└──────────────────────────────┬─────────────────────────────────┘
                               │ subscribe / collect
                               ▼
┌────────────────────────────────────────────────────────────────┐
│                    ViewModel 层 (Presentation)                 │
│  [MainViewModel] [ScanViewModel] [HistoryViewModel]             │
│  [LoginViewModel]                                               │
│  ───────────────────────────────────────────────────────────── │
│  状态聚合、节流去重、协程调度、UI 事件分发                       │
└──────────────────────────────┬─────────────────────────────────┘
                               │ invoke
                               ▼
┌────────────────────────────────────────────────────────────────┐
│                    UseCase 层 (Domain)                         │
│  [SaveScanWithImageUseCase] [MoveHistoryToTrashUseCase]         │
│  ───────────────────────────────────────────────────────────── │
│  封装业务原子操作，保证业务逻辑可复用与可测试                     │
└──────────────────────────────┬─────────────────────────────────┘
                               │ call
                               ▼
┌────────────────────────────────────────────────────────────────┐
│                    Repository 层 (Data)                        │
│  [ScanRepository] → [ScanRepositoryImpl]                        │
│  ───────────────────────────────────────────────────────────── │
│  对上层暴露统一接口，隐藏底层存储细节                            │
└──────────────────────────────┬─────────────────────────────────┘
                               │ implementation
                               ▼
┌────────────────────────────────────────────────────────────────┐
│                    数据存储层 (Local)                           │
│  [Room: AppDatabase / ScanHistoryDao / ScanHistoryEntity]       │
│  [MediaStore: MediaStoreImageSaver]                             │
│  [SharedPreferences: AppSettings]                               │
│  ───────────────────────────────────────────────────────────── │
│  Room 存文本记录，MediaStore 存图片，SharedPreferences 存配置   │
└──────────────────────────────┬─────────────────────────────────┘
                               │ 提供通用能力
┌────────────────────────────────────────────────────────────────┐
│                    通用工具层 (Core)                           │
│  [CameraManager] [MlKitProvider] [AppSettings]                 │
│  [MediaStoreImageSaver] [ImageSharpnessDetector]                │
│  [ImageWatermarkHelper] [SmsService] [MockSmsService]           │
│  ───────────────────────────────────────────────────────────── │
│  可被多个 Feature 复用的底层能力                                │
└────────────────────────────────────────────────────────────────┘
```

### 3.2 依赖注入（DI）

通过 **Hilt** 管理所有依赖，注入点分布：

| 模块 | 文件 | 职责 |
|------|------|------|
| App 入口 | [MainApplication.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/MainApplication.kt) | `@HiltAndroidApp` 启用注入 |
| 接口绑定 | [AppModule.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/di/AppModule.kt) | `@Binds` 绑定 `ScanRepository` → `ScanRepositoryImpl` |
| 实例提供 | [DatabaseModule.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/di/DatabaseModule.kt) | `@Provides` 提供 `AppDatabase` / `ScanHistoryDao` / `SmsService(Mock)` |
| Activity | 各 Feature Activity | `@AndroidEntryPoint` 标记，通过 `by viewModels()` 获取 ViewModel |

### 3.3 响应式数据流

```kotlin
// StateFlow — 状态（有初值，可重复观察，保留最后状态）
viewModel.uiState: StateFlow<ScanUiState / HistoryUiState / LoginUiState>

// SharedFlow — 一次性事件（无初值，触发即消费）
viewModel.events: SharedFlow<ScanUiEvent / LoginEvent>
```

**UI 层订阅方式**：`lifecycleScope.launch { repeatOnLifecycle(STARTED) { collect { ... } } }`

**优势**：
- 避免内存泄漏（`repeatOnLifecycle` 保证生命周期安全）
- 状态与事件分离，互不干扰
- 冷启动时 UI 能立即收到最新状态

---

## 4. 目录结构

```
e:\S-scan
├── app/src/main/java/com/example/sscan/
│   ├── MainApplication.kt          # 应用入口 (Hilt)
│   ├── MainActivity.kt              # 主界面 / 扫码模式选择 / 导航
│   │
│   ├── core/                         # 通用工具层
│   │   ├── auth/                     # ├─ 短信认证
│   │   │   ├── SmsService.kt         # │  接口
│   │   │   └── MockSmsService.kt     # │  Mock 实现
│   │   ├── camera/                   # ├─ 相机管理
│   │   │   └── CameraManager.kt      # │  CameraX 封装
│   │   ├── scanner/                  # ├─ 扫码引擎
│   │   │   └── MlKitProvider.kt      # │  ML Kit BarcodeScanner
│   │   ├── settings/                 # ├─ 全局配置
│   │   │   └── AppSettings.kt        # │  SharedPreferences 管理
│   │   ├── storage/                  # ├─ 图像存储
│   │   │   └── MediaStoreImageSaver.kt  # │  相册读写
│   │   └── utils/                    # └─ 图像处理
│   │       ├── ImageSharpnessDetector.kt  #    清晰度检测
│   │       └── ImageWatermarkHelper.kt    #    水印添加
│   │
│   ├── data/                         # 数据层
│   │   ├── local/                    # ├─ Room 本地存储
│   │   │   ├── entity/               # │   实体
│   │   │   ├── dao/                  # │   DAO
│   │   │   ├── model/                # │   统计模型
│   │   │   └── AppDatabase.kt        # │   数据库入口
│   │   └── repository/               # └─ Repository 模式
│   │       ├── ScanRepository.kt     #     接口
│   │       └── ScanRepositoryImpl.kt #     Room 实现
│   │
│   ├── di/                           # 依赖注入模块
│   │   ├── AppModule.kt              # ├─ 接口绑定
│   │   └── DatabaseModule.kt         # └─ 实例提供
│   │
│   ├── domain/                       # 业务领域层
│   │   └── usecase/
│   │       ├── SaveScanWithImageUseCase.kt      # 保存扫码+图片
│   │       └── MoveHistoryToTrashUseCase.kt     # 移入回收站
│   │
│   └── feature/                      # 功能 UI 层
│       ├── auth/presentation/        # ├─ 登录
│       │   ├── LoginActivity.kt
│       │   └── LoginViewModel.kt
│       ├── history/presentation/     # ├─ 历史记录
│       │   ├── HistoryActivity.kt
│       │   ├── HistoryViewModel.kt
│       │   ├── FilterHistoryActivity.kt
│       │   ├── HistoryDetailActivity.kt
│       │   └── ImagePreviewActivity.kt
│       ├── mine/                     # ├─ 我的
│       │   └── MineActivity.kt
│       └── scan/presentation/        # └─ 扫码
│           ├── ScanActivity.kt
│           ├── ScanViewModel.kt
│           ├── BarcodeOverlayView.kt
│           └── ScanFeedbackManager.kt
│
├── app/src/main/res/
│   ├── layout/                       # XML 布局
│   ├── drawable/                     # 按钮背景 / 图标 / SVG
│   ├── values/                       # 颜色 / 尺寸 / 字符串 / 主题
│   ├── values-en/                    # 英文国际化
│   ├── values-sw600dp/               # 平板适配
│   ├── values-sw720dp/               # 大屏平板适配
│   ├── anim/                         # 动画
│   └── xml/                          # FileProvider 路径
│
├── build.gradle.kts                  # 根构建脚本
├── app/build.gradle.kts              # 应用模块构建脚本
└── settings.gradle.kts               # Gradle 设置
```

---

## 5. 核心模块详细设计

---

### 5.1 扫码模块 (feature/scan)

#### 5.1.1 模块职责
负责相机调用、条码识别、图像捕获、结果保存、反馈处理。

#### 5.1.2 [CameraManager.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/camera/CameraManager.kt) — 相机管理核心

**核心功能**：
- 封装 **CameraX** 的三用例：`Preview`（预览画面）+ `ImageAnalysis`（实时图像分析）+ `ImageCapture`（拍照存图）
- 自动对焦：定时触发 AF（可配置是否自动取消）
- 模式切换：`ALL` / `QR_ONLY` / `BARCODE_ONLY`
- 闪光灯切换：`torch()`
- 条码注解：在 `BarcodeOverlayView` 上绘制识别到的条码 bounding box
- 节流去抖：内部维护时间戳，避免短时间重复回调
- 生命周期管理：`start()` / `stop()` / `release()`，与 Activity lifecycle 绑定

**关键流程**：
```
Activity.onCreate → CameraManager(cameraProvider) → bindToLifecycle(lifecycleOwner)
   ↓
ImageAnalysis.Analyzer → ML Kit BarcodeScanner → 识别成功 → onBarcodeDetected 回调
   ↓
Activity.captureAndDispatch() → 通知 ScanViewModel
```

#### 5.1.3 [MlKitProvider.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/scanner/MlKitProvider.kt) — 扫码引擎

**功能**：根据扫码模式选择识别格式集合，创建 `BarcodeScanner` 实例。

```kotlin
enum class ScanCodeMode {
    ALL,           // 识别所有条码类型
    QR_ONLY,       // 仅识别二维码 (QR_CODE, DATA_MATRIX, AZTEC, PDF417)
    BARCODE_ONLY   // 仅识别一维码 (CODE128, CODE39, EAN13, UPC_A, ...)
}
```

**ML Kit 支持的条码类型**：
- 一维：CODE_128 / CODE_39 / CODE_93 / CODABAR / EAN_13 / EAN_8 / ITF / UPC_A / UPC_E
- 二维：QR_CODE / DATA_MATRIX / PDF_417 / AZTEC

#### 5.1.4 [ScanActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/scan/presentation/ScanActivity.kt) — 扫码界面

**四大职责**：

| 职责 | 说明 |
|------|------|
| 权限 & 生命周期 | 相机权限申请（ActivityResultContracts）、相册权限、CameraManager 创建/释放 |
| 扫码反馈 & 结果 | `captureAndDispatch()` → `ScanViewModel.onScanResult()` → 处理 `ACCEPTED/DUPLICATE/THROTTLED/EMPTY` |
| 模式切换 | QR_ONLY / BARCODE_ONLY 切换、连续扫码开关、闪光灯切换 |
| UI 渲染 | 扫描线动画、底部控制面板、状态文字、图像保存预览弹窗、WindowInsets 边缘到边缘 |

**扫码结果处理流程**：

```
ImageAnalysis 检测到条码 → onBarcodeDetected 回调
   ↓
Activity.captureAndDispatch() → 保存临时图像文件
   ↓
ScanViewModel.onScanResult(text, imageFile, timestamp)
   ├─ 450ms 内 → THROTTLED（过快，丢弃）
   ├─ 5 秒内相同文本 → DUPLICATE（重复，振动提示）
   └─ 通过 → ACCEPTED → 调用 SaveScanWithImageUseCase
            ↓
         保存成功 → ScanUiEvent.ScanAccepted(text, savedCount, imageUri, blurRejected)
            ↓
         Activity 收集 → ScanFeedbackManager.play() → 振动 + 提示音
            ↓
         若开启连续扫码 → 继续扫描
         若未开启 → 显示结果弹窗（复制/分享/关闭）
```

#### 5.1.5 [ScanViewModel.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/scan/presentation/ScanViewModel.kt) — 状态管理

**核心数据结构**：

```kotlin
// 扫码结果类型
enum class ScanResultType { ACCEPTED, DUPLICATE, THROTTLED, EMPTY }

// UI 状态 (StateFlow)
data class ScanUiState(
    val mode: ScanCodeMode,           // 当前扫码模式
    val isContinuous: Boolean,        // 连续扫码开关
    val isFlashOn: Boolean,           // 闪光灯状态
    val totalScans: Int,              // 累计扫码数
    val lastScanText: String?         // 最近一次文本
)

// UI 事件 (SharedFlow)
sealed class ScanUiEvent {
    data class ScanAccepted(val text: String, val savedCount: Int,
                            val imageUri: String?, val blurRejected: Boolean)
    data class ScanDuplicate(val text: String)
    data class ScanThrottled(val text: String)
    data class ScanError(val message: String)
}
```

**关键算法**：

| 算法 | 参数 | 说明 |
|------|------|------|
| 节流 | 450ms | 两次扫码必须间隔 ≥ 450ms |
| 去重 | 5s / 100 条 | 最近 5 秒内或最近 100 条中出现过相同文本 → 标记为重复 |
| 连续模式 | 开关 | 连续扫码模式下，扫码成功后不暂停，继续扫描 |

#### 5.1.6 [BarcodeOverlayView.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/scan/presentation/BarcodeOverlayView.kt) — 条码注解

**功能**：在预览画面上叠加绘制识别到的条码 bounding box：

- `updateAnnotations(List<Rect>)`: 接收 ML Kit 返回的坐标列表
- `clearAnnotations()`: 清空（无条码时）
- 坐标映射：自动将图像分析坐标系 → 屏幕视图坐标系

#### 5.1.7 [ScanFeedbackManager.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/scan/presentation/ScanFeedbackManager.kt) — 反馈管理

**反馈事件**：

```kotlin
sealed class ScanFeedbackEvent {
    object Success          // 成功：短振动 + 提示音
    object Duplicate        // 重复：震动 + 语音"重复扫码"
    object TooFast          // 过快：震动 + "请稍等"
    object Blur             // 模糊：震动 + "图像模糊"
    object Empty            // 空内容：震动
}
```

**实现细节**：
- 振动：`Vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))`
- 提示音：`MediaPlayer` 加载系统/自定义音效
- 语音播报：`TextToSpeech` 或预录音频（重复扫码提示）

---

### 5.2 历史模块 (feature/history)

#### 5.2.1 模块职责
扫码记录查询、筛选、多选、分享、导出、删除、详情查看。

#### 5.2.2 [HistoryActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/HistoryActivity.kt) — 历史列表

**界面布局**：

```
┌────────────────────────────────────────┐
│  ← 返回    历史记录    [筛选] [更多]  │  ← 顶部栏
│  ┌──────────────────────────────────┐ │
│  │ 🔍 搜索输入框                    │ │  ← 搜索框
│  └──────────────────────────────────┘ │
│  [扫码记录] [转码记录]                │  ← 标签页（Tab）
│  ┌──────────────────────────────────┐ │
│  │ 类型标签  时间                   │ │
│  │ 扫码文本内容                    │ │  ← 卡片列表
│  │ [导出] [删除]                   │ │
│  └──────────────────────────────────┘ │
│  ┌──────────────────────────────────┐ │
│  │ 多选工具栏  (已选 N 条)          │ │  ← 多选工具栏
│  │ [取消全选] [分享] [导出]         │ │
│  └──────────────────────────────────┘ │
└────────────────────────────────────────┘
```

**更多操作菜单**（右上 `···`）：
1. **创建文件夹** — 对记录分组管理
2. **编辑** — 编辑记录的自定义标题

**核心流程**：

```
Activity 启动 → HistoryViewModel 加载数据
   ↓
renderList(items) → 遍历 items 动态添加 ItemHistoryRecordBinding
   ↓
长按卡片 → 进入选择模式 (isSelectionMode = true)
   ↓
点击 → 切换选中状态；选中 ≥ 1 条 → 显示顶部多选工具栏
   ↓
[导出] → 调用 HistoryViewModel.exportSelected() → 生成 TXT 文件
[分享] → 调用 HistoryViewModel.shareSelected() → 分享文本 + 图片
```

#### 5.2.3 [HistoryViewModel.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/HistoryViewModel.kt) — 状态聚合

**核心数据结构**：

```kotlin
// 历史记录单项
data class HistoryItemUi(
    val id: Long,
    val result: String,        // 扫码文本
    val imageUri: String?,     // 关联图像
    val timeLabel: String,     // 格式化时间
    val isSelected: Boolean    // 选中状态
)

// UI 状态
data class HistoryUiState(
    val items: List<HistoryItemUi>,    // 当前显示列表
    val isSelectionMode: Boolean,       // 是否处于选择模式
    val selectedIds: Set<Long>,         // 已选 ID 集合
    val searchQuery: String,            // 当前搜索关键字
    val isEmpty: Boolean                // 是否为空
)

// 过滤维度
data class FilterConfig(
    val timeRange: HistoryTimeRange,   // 今日 / 昨日 / 近7天 / 近30天 / 自定义
    val startMillis: Long?,
    val endMillis: Long?,
    val contentType: HistoryContentType // 全部 / 仅图片 / 仅文本
)
```

**数据过滤逻辑**：

```kotlin
// 1. 文本搜索（忽略大小写，包含关键字即可）
items.filter { it.result.contains(query, ignoreCase = true) }

// 2. 时间过滤（基于 ScanHistoryEntity.time 字段）
items.filter { it.time in [startMillis, endMillis] }

// 3. 内容类型过滤
items.filter {
    when (contentType) {
        ALL -> true
        IMAGE_ONLY -> it.imageUri != null
        TEXT_ONLY -> it.imageUri == null
    }
}

// 4. 组合过滤 = 搜索 ∩ 时间 ∩ 类型
```

**计算字段**：
- 今日条数：`items.count { it.timeLabel.startsWith("今天") }`
- 已保存图片数：`items.count { it.imageUri != null }`

#### 5.2.4 [FilterHistoryActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/FilterHistoryActivity.kt) — 筛选页

**快捷时间选项**：今日 / 昨日 / 当月 / 上月 / 近 7 天 / 近 30 天 / 自定义日期（日期选择器）

**内容类型选项**：全部记录 / 仅图片记录 / 仅文本记录

**返回方式**：通过 Intent extra 回传 `rangeOrdinal` / `start` / `end` / `typeOrdinal`

#### 5.2.5 [HistoryDetailActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/HistoryDetailActivity.kt) — 记录详情

**展示内容**：
- 扫码文本大文本框（可复制/分享）
- 时间戳
- 图片预览（点击全屏）
- 备注/自定义标题

#### 5.2.6 [ImagePreviewActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/ImagePreviewActivity.kt) — 图片预览

简单全屏 ImageView，通过 Intent extra 传入 `imageUri`，使用 `ImageView.setImageURI(uri)` 渲染。

---

### 5.3 数据层 (data)

#### 5.3.1 [ScanHistoryEntity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/local/entity/ScanHistoryEntity.kt) — 数据实体

```kotlin
@Entity(tableName = "scan_history")
data class ScanHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val result: String,           // 扫码结果文本
    val imageUri: String?,        // 图像文件 URI（可为空）
    val customTitle: String?,     // 用户自定义标题
    val groupName: String?,       // 分组/文件夹名
    val time: Long,               // 时间戳（毫秒）
    val deleted: Int = 0          // 软删除标记 (0=正常, 1=回收站)
)
```

**设计说明**：
- `deleted` 软删除而非物理删除，支持"回收站"恢复
- `imageUri` 指向 MediaStore 或应用私有目录，图片由 [MediaStoreImageSaver.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/storage/MediaStoreImageSaver.kt) 管理
- `customTitle` / `groupName` 为可扩展字段，用于"创建文件夹"、"编辑"功能

#### 5.3.2 [ScanHistoryDao.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/local/dao/ScanHistoryDao.kt) — 数据访问

**核心方法**：

```kotlin
@Dao interface ScanHistoryDao {

    // 插入/更新
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: ScanHistoryEntity): Long

    // 观察：正常记录（非回收站）
    @Query("SELECT * FROM scan_history WHERE deleted = 0 ORDER BY time DESC")
    fun observeHistory(): Flow<List<ScanHistoryEntity>>

    // 观察：回收站
    @Query("SELECT * FROM scan_history WHERE deleted = 1 ORDER BY time DESC")
    fun observeTrash(): Flow<List<ScanHistoryEntity>>

    // 关键字搜索
    @Query("SELECT * FROM scan_history WHERE deleted = 0 " +
           "AND (result LIKE '%' || :query || '%' OR customTitle LIKE '%' || :query || '%') " +
           "ORDER BY time DESC")
    fun searchHistory(query: String): Flow<List<ScanHistoryEntity>>

    // 时间范围过滤
    @Query("SELECT * FROM scan_history WHERE deleted = 0 " +
           "AND time BETWEEN :start AND :end ORDER BY time DESC")
    fun filterByTime(start: Long, end: Long): Flow<List<ScanHistoryEntity>>

    // 每日统计（首页显示）
    @Query("SELECT DATE(time/1000, 'unixepoch', 'localtime') as dayLabel, " +
           "COUNT(*) as scanCount FROM scan_history WHERE deleted = 0 " +
           "GROUP BY dayLabel ORDER BY dayLabel DESC LIMIT 30")
    fun observeDailyStats(): Flow<List<DailyScanStat>>

    // 软删除 → 回收站
    @Query("UPDATE scan_history SET deleted = 1 WHERE id = :id")
    suspend fun moveToTrash(id: Long)

    // 从回收站恢复
    @Query("UPDATE scan_history SET deleted = 0 WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    // 更新标题
    @Query("UPDATE scan_history SET customTitle = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    // 物理删除（清空回收站时调用）
    @Query("DELETE FROM scan_history WHERE id = :id")
    suspend fun deletePermanently(id: Long)
}
```

#### 5.3.3 [AppDatabase.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/local/AppDatabase.kt) — 数据库入口

```kotlin
@Database(entities = [ScanHistoryEntity::class], version = 3)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scanHistoryDao(): ScanHistoryDao

    // 版本升级采用 fallbackToDestructiveMigration（销毁重建）
    // 生产环境建议实现 Migration 以保留用户数据
}
```

#### 5.3.4 [ScanRepository.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/repository/ScanRepository.kt) & [ScanRepositoryImpl.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/repository/ScanRepositoryImpl.kt)

Repository 作为 DAO 的代理层，向上层暴露统一接口：

```kotlin
interface ScanRepository {
    fun observeHistory(): Flow<List<ScanHistoryEntity>>
    fun observeHistoryFiltered(query: String, start: Long?, end: Long?): Flow<List<ScanHistoryEntity>>
    fun observeTrash(): Flow<List<ScanHistoryEntity>>
    fun observeDailyStats(): Flow<List<DailyScanStat>>
    suspend fun save(entity: ScanHistoryEntity): Long
    suspend fun delete(id: Long)             // 软删除
    suspend fun restore(id: Long)            // 恢复
    suspend fun deletePermanently(id: Long)  // 物理删除
    suspend fun updateTitle(id: Long, title: String)
}
```

**优势**：如果未来将 Room 替换为 Realm/SQLite 或接入云端同步，只需替换 Repository 实现，上层（ViewModel/UseCase）代码零改动。

---

### 5.4 业务领域层 (domain/usecase)

#### 5.4.1 [SaveScanWithImageUseCase.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/domain/usecase/SaveScanWithImageUseCase.kt) — 保存扫码结果

**完整执行流程**：

```
输入: (text: String, imageFile: File?, timestamp: Long)
   ↓
① [清晰度检测] 如果有 imageFile → ImageSharpnessDetector.detect(file)
   - 将图片降采样至 ≤ 256px 尺寸
   - 计算灰度图 Laplacian 方差
   - 方差 < 阈值(120.0) → blurRejected = true，跳过图片保存
   ↓
② [水印添加] 如果清晰度达标且用户开启保存图片 → ImageWatermarkHelper.addWatermark(bitmap)
   - 在右下角绘制圆角背景 + 时间戳文字（如 "2026-06-17 14:30"）
   ↓
③ [保存到相册] MediaStoreImageSaver.saveBitmap(bitmap, folderName)
   - Android 10+ 使用 MediaStore API（无需 WRITE_EXTERNAL_STORAGE）
   - Android 9- 兼容方式写入 Pictures/MultiQR 目录
   - 返回 content:// Uri
   ↓
④ [写入数据库] ScanRepository.save(ScanHistoryEntity(result, imageUri, time))
   ↓
⑤ [清理临时文件] 删除 CameraManager 生成的临时图像文件
   ↓
输出: SaveResult(savedCount, imageUri, blurRejected)
```

#### 5.4.2 [MoveHistoryToTrashUseCase.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/domain/usecase/MoveHistoryToTrashUseCase.kt) — 移入回收站

```kotlin
suspend fun execute(id: Long) {
    scanRepository.delete(id)  // → DAO 执行 UPDATE deleted = 1
}
```

**设计意图**：即使当前只有一行 `delete(id)` 调用，Use Case 仍然存在，为未来扩展预留位置（如：同时清理图片文件、记录操作日志、同步云端等）。

---

### 5.5 登录模块 (feature/auth)

#### 5.5.1 [SmsService.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/auth/SmsService.kt) & [MockSmsService.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/auth/MockSmsService.kt)

**接口定义**：

```kotlin
interface SmsService {
    suspend fun sendCode(phone: String): SmsResult
    suspend fun verifyCode(phone: String, code: String): SmsResult
}

sealed class SmsResult {
    data class Success(val message: String) : SmsResult()
    data class Failure(val message: String) : SmsResult()
}
```

**Mock 实现行为**：
- 手机号校验：必须是 11 位大陆手机号（以 1 开头，第二位 3-9）
- 验证码：固定返回 `123456`（方便开发调试）
- 延时：模拟 800ms 网络耗时

**生产替换**：将 `MockSmsService` 替换为接入阿里云短信/腾讯云短信的 `RealSmsService`，只需修改 [DatabaseModule.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/di/DatabaseModule.kt) 中的 `@Provides`。

#### 5.5.2 [LoginActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/auth/presentation/LoginActivity.kt) & [LoginViewModel.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/auth/presentation/LoginViewModel.kt)

**登录流程**：

```
[手机号输入] → [获取验证码] → [60秒倒计时] → [验证码输入] → [登录]
   ↓                ↓                ↓              ↓
格式校验      SmsService.sendCode   倒计时       SmsService.verifyCode
                (Mock: 发"123456")  ↓           (Mock: 校验"123456")
                                  按钮状态更新
   ↓
成功 → AppSettings.setLoggedIn(phone, timestamp) → 关闭页面
```

**ViewModel 管理**：倒计时、按钮状态、登录结果 Toast，全部通过 `LoginUiState` 和 `LoginEvent` 向 Activity 分发。

---

### 5.6 我的 / 设置模块 (feature/mine)

#### 5.6.1 [MineActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/mine/MineActivity.kt)

**设置项一览**（全部持久化至 SharedPreferences，通过 [AppSettings.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/settings/AppSettings.kt) 管理）：

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| 用户信息 | 文本 | - | 手机号/登录时间（登录后显示） |
| 语言 | 下拉 | 跟随系统 | 中文 / English |
| 主题 | 下拉 | 浅色 | 浅色 / 深色 / 跟随系统 |
| 连续扫码 | 开关 | 关 | 扫码成功后是否继续扫描 |
| 连续扫码间隔 | 数字 | 2 秒 | 连续模式下的间隔时间 |
| 振动反馈 | 开关 | 开 | 扫码成功是否振动 |
| 提示音 | 开关 | 开 | 扫码成功是否播放提示音 |
| 重复提示 | 开关 | 开 | 重复扫码是否语音提示 |
| 连续语音 | 开关 | 关 | 连续扫码时播报扫码结果 |
| 自动对焦重置 | 开关 | 开 | 定期重置对焦（避免持续模糊） |
| 自动保存图片 | 开关 | 开 | 是否截取并保存扫码图像 |
| 允许重复扫码 | 开关 | 关 | 是否允许 5 秒内重复扫码相同文本 |
| 自动复制 | 开关 | 关 | 扫码成功后是否自动复制文本到剪贴板 |
| 保存到相册 | 开关 | 开 | 是否写入系统相册 |
| 独立文件夹 | 开关 | 关 | 是否每个二维码创建独立文件夹 |
| 清空记录 | 按钮 | - | 清空所有历史记录（含回收站） |
| 清除缓存 | 按钮 | - | 清除应用缓存文件 |
| 分享应用 | 按钮 | - | 分享应用下载链接 |
| 关于 | 文本 | - | 版本号 / 版权信息 |

---

### 5.7 通用工具层 (core)

#### 5.7.1 [AppSettings.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/settings/AppSettings.kt) — 全局配置

**基于 SharedPreferences 的单例管理**，对外暴露属性和方法：

```kotlin
// 读写方式：字段 = SharedPreferences.getString/putString(key, default)

object AppSettings {
    var language: String          // "zh" / "en"
    var theme: String             // "light" / "dark" / "system"
    var isVibrationEnabled: Boolean
    var isSoundEnabled: Boolean
    var isContinuousScanEnabled: Boolean
    var continuousScanIntervalMs: Long
    var isAutoResetFocusEnabled: Boolean
    var isSaveImageEnabled: Boolean
    var isDuplicateAllowed: Boolean
    var currentScanMode: ScanCodeMode
    var loggedInPhone: String?
    var loginTimestamp: Long

    fun applyAppPreferences(context: Context)     // 启动时应用语言/主题
    fun clearCaches(context: Context)             // 清除缓存
}
```

**关键能力**：`applyAppPreferences()` 在 [MainApplication.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/MainApplication.kt) 的 `onCreate` 中调用，保证应用启动时语言/主题即刻生效。

#### 5.7.2 [MediaStoreImageSaver.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/storage/MediaStoreImageSaver.kt) — 图像存储

**核心方法**：

```kotlin
// 将 Bitmap 保存到系统相册
fun saveBitmap(context: Context, bitmap: Bitmap, folderName: String = "MultiQR"): Uri?

// 将已有 JPEG 文件写入相册
fun saveFile(context: Context, file: File, folderName: String = "MultiQR"): Uri?

// 列出应用已保存的图片
fun listAppImages(context: Context): List<Uri>

// 从相册选择图片（配合 ActivityResultContracts.PickVisualMedia）
fun pickImageIntent(): Intent

// 重命名图片
fun renameImage(context: Context, uri: Uri, newName: String): Boolean

// 删除图片
fun deleteImage(context: Context, uri: Uri): Boolean
```

**Android 10+ 适配**：
- 使用 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 写入
- 设置 `RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/MultiQR"`
- 无需申请 `WRITE_EXTERNAL_STORAGE` 权限（Android 10+ 使用分区存储）
- Android 9 及以下：使用 `Environment.getExternalStoragePublicDirectory()`

#### 5.7.3 [ImageSharpnessDetector.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/utils/ImageSharpnessDetector.kt) — 图像清晰度检测

**算法原理：Laplacian 方差**

```
步骤：
1. 将图片解码为 Bitmap，并缩放至较短边 ≤ 256px（加速计算）
2. 转换为灰度图（每个像素 = 0.299·R + 0.587·G + 0.114·B）
3. 对灰度图应用 Laplacian 算子（二阶差分）：
   L(x,y) = |4·G(x,y) - G(x-1,y) - G(x+1,y) - G(x,y-1) - G(x,y+1)|
4. 计算所有像素 L(x,y) 的方差 σ²
5. 方差越大 → 图像越清晰；方差越小 → 图像越模糊
6. 默认阈值：σ² = 120.0
```

**应用场景**：在 [SaveScanWithImageUseCase.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/domain/usecase/SaveScanWithImageUseCase.kt) 中用于判断是否保存当前捕获的图像；模糊时跳过保存，仅保存文本。

#### 5.7.4 [ImageWatermarkHelper.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/utils/ImageWatermarkHelper.kt) — 水印添加

**功能**：在扫码图像右下角添加带圆角背景的时间戳水印。

```kotlin
fun addWatermark(bitmap: Bitmap, timestamp: Long): Bitmap {
    // 1. 绘制圆角半透明背景矩形
    // 2. 在背景上绘制时间文本（如 "2026-06-17 14:30:25"）
    // 3. 返回新 Bitmap（原图不修改）
}
```

---

### 5.8 应用入口与主界面

#### 5.8.1 [MainApplication.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/MainApplication.kt)

```kotlin
@HiltAndroidApp
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyAppPreferences(this)  // 加载语言/主题
        // Timber.plant(DebugTree())           // （可选）初始化日志
    }
}
```

#### 5.8.2 [MainActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/MainActivity.kt)

**界面布局**：

```
┌─────────────────────────────────────────┐
│  👤 用户信息 (登录状态 + 今日统计)      │  ← 顶部卡片
├─────────────────────────────────────────┤
│  ┌────────┐ ┌────────┐                 │
│  │ 扫码   │ │ 条码   │    ← 扫码模式   │
│  │ QR码   │ │ 专属   │      选择卡片   │
│  └────────┘ └────────┘                 │
│  ┌─────────────────┐                   │
│  │ 连续扫码        │                   │
│  └─────────────────┘                   │
├─────────────────────────────────────────┤
│  🏠 首页    📋 历史    👤 我的         │  ← 底部导航栏
└─────────────────────────────────────────┘
```

**导航实现**：底部 Tab 使用自定义 LinearLayout + 点击切换 Activity，而非 Navigation 组件（每个页面独立 Activity，便于生命周期管理）。

**沉浸式状态栏**：
- `WindowCompat.setDecorFitsSystemWindows(window, false)` — 边缘到边缘
- `window.statusBarColor = Color.TRANSPARENT` — 透明状态栏
- `ViewCompat.setOnApplyWindowInsetsListener` — 手动处理内容 padding，避免被系统栏遮挡

---

## 6. UI 资源与设计规范

### 6.1 主题 (values/themes.xml)

`Theme.SScan` — 基于 `Theme.MaterialComponents.Light.NoActionBar`，自定义主题色：

```xml
<item name="colorPrimary">?attr/colorPrimaryVariant</item>   <!-- 主色（绿色系） -->
<item name="colorSecondary">#F5F7F2</item>                   <!-- 背景米白 -->
```

### 6.2 关键颜色 (values/colors.xml)

| 颜色名称 | 值 | 用途 |
|----------|-----|------|
| `green_primary` | `#4CAF50` / 变体 | 主品牌色（按钮、标签、高亮） |
| `bg_history_export_btn` | 渐变 `#E8F1E3 → #D4E8D1` | 历史导出按钮背景 |
| `bg_history_delete_btn` | 渐变 `#F3D6D0 → #E8C5BE` | 历史删除按钮背景 |
| 卡片背景 | `#F5F7F2` | 历史记录卡片底色 |

### 6.3 按钮样式 (drawable/bg_history_*.xml)

采用 `ShapeDrawable` + **线性渐变**，圆角 `18dp`，无需 selector（按压时通过 `foreground` 提供 ripple 反馈）。

### 6.4 历史卡片 (layout/item_history_record.xml)

```
┌────────────────────────────────────┐
│  [类型标签]            时间文本    │  ← 类型标签紧贴顶部
│  ──────────────────────────────── │
│  扫码结果文本内容               │  ← 可折行，最大 2 行 + 省略号
│  ──────────────────────────────── │
│      [导出按钮]   [删除按钮]       │  ← 水平居中对齐
└────────────────────────────────────┘
```

### 6.5 国际化

- `values/strings.xml` — 中文（默认）
- `values-en/strings.xml` — 英文
- `values/scan_preview_strings.xml` / `values-en/scan_preview_strings.xml` — 扫码预览相关文案

---

## 7. 关键业务流程

### 7.1 扫码 → 保存 → 显示结果

```
用户打开扫码页
   ↓
ScanActivity 请求 CAMERA 权限 → 已授权
   ↓
创建 CameraManager → 启动预览 + 图像分析
   ↓
ML Kit BarcodeScanner 识别到条码 → onBarcodeDetected(text)
   ↓
Activity.captureAndDispatch() → 保存临时图像文件 → 通知 ViewModel
   ↓
ScanViewModel.onScanResult(text, imageFile, now)
   ├─ 节流检查（距上次 < 450ms） → THROTTLED（丢弃）
   ├─ 去重检查（5s 内相同文本 或 最近100条包含）→ DUPLICATE
   └─ 通过 → ACCEPTED
         ↓
         SaveScanWithImageUseCase(imageFile, text, now)
         ├─ 清晰度检测 → 是否模糊？
         ├─ 清晰 + 保存图片开关开 → 加水印 → MediaStore 保存
         ├─ Room 写入 ScanHistoryEntity
         └─ 返回 savedCount / imageUri / blurRejected
   ↓
Activity 收到 ScanUiEvent.ScanAccepted
   ↓
ScanFeedbackManager.play(Success) → 振动 + 提示音
   ↓
显示结果弹窗（复制/分享/关闭）→ 或 连续扫码模式下继续扫描
```

### 7.2 历史记录筛选

```
用户打开历史页 → HistoryViewModel 加载 observeHistory Flow
   ↓
输入搜索关键字 → updateSearchQuery(query) → Flow 新发射
   ↓
点击筛选 → FilterHistoryActivity → 选择条件 → 返回 (range, start, end, type)
   ↓
HistoryViewModel.applyFilter(range, start, end, type) → 组合过滤
   ↓
HistoryActivity.renderList(items) → 动态添加卡片
   ↓
长按卡片 → isSelectionMode = true → 显示多选工具栏
   ↓
[导出] → 生成 "扫码记录_yyyyMMdd_HHmmss.txt" → FileProvider 分享 / 打开
[分享] → Intent.ACTION_SEND_MULTIPLE → 文本 + 图片 Uri 分享
```

### 7.3 连续扫码

```
连续模式开关 = true
   ↓
扫码成功 → 不显示弹窗，不暂停相机
   ↓
ScanFeedbackManager.play(Success) → 振动 + 提示音 + 语音（可选）
   ↓
Sleep(continuousScanIntervalMs) → 继续扫描下一条
   ↓
累计 N 条 → 状态栏显示"已扫码 N 条"
   ↓
用户关闭页面 → 停止相机 → 释放资源
```

---

## 8. 构建与打包

### 8.1 构建命令

```bash
# Debug 构建（开发/测试）
./gradlew :app:assembleDebug

# Release 构建（需签名）
./gradlew :app:assembleRelease

# 清理
./gradlew clean

# 安装到设备（需设备连接）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 8.2 签名配置

**Debug**：使用 Android SDK 自带 debug keystore（`~/.android/debug.keystore`），密码 `android`。

**Release**：当前 `app/build.gradle.kts` 未配置 signingConfig，发布前需补充：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../release-keystore.jks")
        storePassword = "..."
        keyAlias = "..."
        keyPassword = "..."
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ...
    }
}
```

### 8.3 ProGuard / R8

Release 构建启用 `minifyEnabled = true` 和 `shrinkResources = true`，ProGuard 规则位于 [app/proguard-rules.pro](file:///e:/S-scan/app/proguard-rules.pro)，需确保保留：
- Room 实体类
- Hilt 生成类
- ML Kit Barcode 相关类
- Serializable / Parcelable 数据类

### 8.4 输出文件

```
app/build/outputs/apk/
├── debug/
│   └── app-debug.apk           ← 测试安装包（~30MB）
└── release/
    └── app-release.apk         ← 发布安装包（~? MB，经 R8 压缩）
```

---

## 9. 安全与隐私

### 9.1 权限使用说明

| 权限 | 用途 | 是否必须 |
|------|------|---------|
| CAMERA | 相机扫码 | 核心功能必须 |
| VIBRATE | 扫码振动反馈 | 增强体验，非必须 |
| FLASHLIGHT | 闪光灯控制 | 增强体验，非必须 |
| READ_MEDIA_IMAGES | 从相册选择图像 | 可选功能 |
| READ_EXTERNAL_STORAGE (API ≤32) | 兼容老设备，访问图片 | 可选功能 |

**`android.hardware.camera` 标记为 `required="false"`** — 不强制要求设备有相机（如部分平板/模拟器），但缺少相机将无法使用核心扫码功能，应用会提示用户。

### 9.2 FileProvider 配置

应用通过 `androidx.core.content.FileProvider` 分享图像/导出文件，authority 为 `${applicationId}.fileprovider`，路径配置在 [xml/file_paths.xml](file:///e:/S-scan/app/src/main/res/xml/file_paths.xml)，包括：

- `external-files-path` — 应用私有外部存储
- `external-cache-path` — 应用外部缓存
- `cache-path` — 应用内部缓存

**优势**：无需暴露 `file://` URI，避免暴露私有目录路径，符合 Android 7.0+ StrictMode 要求。

### 9.3 用户数据存储位置

| 数据类型 | 存储位置 | 持久化方式 |
|---------|---------|-----------|
| 扫码记录 | Room SQLite | `/data/data/com.example.sscan/databases/` |
| 扫码图像 | 系统相册 | `Pictures/MultiQR/`（MediaStore 管理） |
| 用户设置 | SharedPreferences | `/data/data/com.example.sscan/shared_prefs/` |
| 登录态 | SharedPreferences | 同上 |

### 9.4 Mock 短信服务说明

当前 `SmsService` 的实现为 `MockSmsService`（开发/调试用），**验证码固定为 `123456`**，未发送真实短信。生产上线前需替换为真实短信服务（阿里云/腾讯云短信 SDK），并在 [DatabaseModule.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/di/DatabaseModule.kt) 中切换绑定。

---

## 10. 可扩展点与后续优化建议

### 10.1 已预留扩展字段

| 字段 | 位置 | 用途 |
|------|------|------|
| `customTitle` | `ScanHistoryEntity` | 支持"编辑"记录自定义标题（数据层已支持，UI 待完善） |
| `groupName` | `ScanHistoryEntity` | 支持"创建文件夹"对记录分组（数据层已支持，UI 待完善） |
| `deleted` | `ScanHistoryEntity` | 支持回收站/恢复功能（DAO 已实现，UI 待补充回收站页面） |

### 10.2 生产环境建议优化

1. **数据库迁移**：当前使用 `fallbackToDestructiveMigration`，生产环境应实现 `Migration` 类保留用户数据
2. **短信服务**：替换 `MockSmsService` 为真实短信 SDK（阿里云/腾讯云/极光）
3. **崩溃收集**：集成 Firebase Crashlytics / Bugly / Sentry
4. **应用统计**：集成 Firebase Analytics / 友盟+
5. **混淆加固**：Release 构建完善 ProGuard 规则，可考虑加壳加固（360加固/爱加密）
6. **应用签名**：使用 Google Play App Signing / 自签名并妥善保管 keystore
7. **扫码模式记忆**：当前扫码模式已在 `AppSettings` 持久化，下次进入自动恢复
8. **图片缩略图缓存**：历史列表显示图片时建议使用 Glide/Coil 加载（当前直接 Uri 加载可能卡顿）

### 10.3 潜在功能扩展

- **云端同步**：扫码记录同步到云端（需账号体系 + 后端 API）
- **多语言扩展**：当前仅中英，可扩展日/韩/法/德等
- **条码生成**：反向功能 — 输入文本生成二维码图片
- **批量导入**：从 CSV/TXT 导入历史记录
- **标签分类**：比"文件夹"更灵活的打标签系统
- **扫码统计图表**：更丰富的数据可视化（饼图/折线图/柱状图）

---

## 11. 文件索引表

| 文件路径 | 职责 | 关键类/函数 |
|---------|------|-------------|
| [core/camera/CameraManager.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/camera/CameraManager.kt) | 相机管理 | `start()`, `stop()`, `torch()`, `onBarcodeDetected` |
| [core/scanner/MlKitProvider.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/scanner/MlKitProvider.kt) | 扫码引擎 | `getScanner(mode)`, `ScanCodeMode` |
| [core/settings/AppSettings.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/settings/AppSettings.kt) | 全局配置 | `applyAppPreferences()`, 各设置字段 |
| [core/storage/MediaStoreImageSaver.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/storage/MediaStoreImageSaver.kt) | 图像存储 | `saveBitmap()`, `saveFile()`, `listAppImages()` |
| [core/utils/ImageSharpnessDetector.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/utils/ImageSharpnessDetector.kt) | 清晰度检测 | `detect(file)`, Laplacian 方差算法 |
| [core/utils/ImageWatermarkHelper.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/utils/ImageWatermarkHelper.kt) | 水印添加 | `addWatermark(bitmap, time)` |
| [core/auth/SmsService.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/auth/SmsService.kt) | 短信接口 | `sendCode()`, `verifyCode()`, `SmsResult` |
| [core/auth/MockSmsService.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/core/auth/MockSmsService.kt) | Mock 短信 | 开发调试实现 |
| [data/local/entity/ScanHistoryEntity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/local/entity/ScanHistoryEntity.kt) | Room 实体 | `@Entity scan_history` 表 |
| [data/local/dao/ScanHistoryDao.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/local/dao/ScanHistoryDao.kt) | DAO | CRUD/搜索/过滤/统计 |
| [data/local/AppDatabase.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/local/AppDatabase.kt) | 数据库入口 | `@Database version=3` |
| [data/repository/ScanRepository.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/repository/ScanRepository.kt) | Repository 接口 | 统一数据访问协议 |
| [data/repository/ScanRepositoryImpl.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/data/repository/ScanRepositoryImpl.kt) | Room 实现 | DAO 转发 |
| [di/AppModule.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/di/AppModule.kt) | DI 接口绑定 | `@Binds` Repository |
| [di/DatabaseModule.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/di/DatabaseModule.kt) | DI 实例提供 | `@Provides` DB/DAO/SmsService |
| [domain/usecase/SaveScanWithImageUseCase.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/domain/usecase/SaveScanWithImageUseCase.kt) | 保存业务 | `invoke(text, image, time)` |
| [domain/usecase/MoveHistoryToTrashUseCase.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/domain/usecase/MoveHistoryToTrashUseCase.kt) | 回收站 | `execute(id)` |
| [feature/scan/ScanActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/scan/presentation/ScanActivity.kt) | 扫码界面 | `captureAndDispatch()`, 模式切换 |
| [feature/scan/ScanViewModel.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/scan/presentation/ScanViewModel.kt) | 扫码状态 | `onScanResult()`, 节流/去重算法 |
| [feature/scan/BarcodeOverlayView.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/scan/presentation/BarcodeOverlayView.kt) | 条码注解 | `updateAnnotations()`, 坐标映射 |
| [feature/scan/ScanFeedbackManager.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/scan/presentation/ScanFeedbackManager.kt) | 反馈管理 | `play(event)`, 振动/提示音/语音 |
| [feature/history/HistoryActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/HistoryActivity.kt) | 历史列表 | `renderList()`, 多选工具栏 |
| [feature/history/HistoryViewModel.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/HistoryViewModel.kt) | 历史状态 | `applyFilter()`, 搜索/时间/类型组合过滤 |
| [feature/history/FilterHistoryActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/FilterHistoryActivity.kt) | 筛选页 | 快捷时间 + 内容类型 |
| [feature/history/HistoryDetailActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/HistoryDetailActivity.kt) | 详情页 | 文本/时间/图片/备注 |
| [feature/history/ImagePreviewActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/history/presentation/ImagePreviewActivity.kt) | 图片预览 | 全屏 ImageView |
| [feature/auth/LoginActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/auth/presentation/LoginActivity.kt) | 登录界面 | 手机号/验证码输入 |
| [feature/auth/LoginViewModel.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/auth/presentation/LoginViewModel.kt) | 登录状态 | 倒计时/验证/登录态 |
| [feature/mine/MineActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/feature/mine/MineActivity.kt) | 设置页 | 所有用户可配置项 |
| [MainApplication.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/MainApplication.kt) | 应用入口 | Hilt 初始化 + 设置应用 |
| [MainActivity.kt](file:///e:/S-scan/app/src/main/java/com/example/sscan/MainActivity.kt) | 主界面 | 扫码模式选择 + 底部导航 |

---

*文档版本：1.0 · 更新日期：2026-06-19*
