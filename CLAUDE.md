# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览
S-scan：Android 多模式扫码应用。Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.13 / JDK 17。`minSdk 24`，`targetSdk` + `compileSdk 35`。技术栈 Hilt 2.52 + Room 2.6.1 + CameraX 1.4.1 + ML Kit barcode-scanning 17.3.0 + Coroutines 1.9.0。39 个 Kotlin 文件，约 6850 行。

## 构建与运行
- `./gradlew :app:assembleDebug`：Debug 构建
- `./gradlew :app:assembleRelease`：Release（R8 + 资源压缩）
- `./gradlew clean`
- `./gradlew :app:lintDebug`
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`

要点：
- `buildToolsVersion = "35.0.0"` 在 `app/build.gradle.kts` 里是硬 pin 的，本机 SDK 缺这个版本会直接失败。
- 编译 JDK 由 `app/build.gradle.kts` 的 `kotlin { jvmToolchain(17) }` 锁定为 17，和 `compileOptions` / `kotlinOptions.jvmTarget` 的 17 一致。Gradle 自己跑在哪个 JVM 上不影响编译结果：命令行通常是 JDK 17，而 Android Studio 用自带的 JBR 21（`.idea/misc.xml` 写着 `project-jdk-name="jbr-21"`）。有了 toolchain，两条路径产出的字节码都是 major version 61 (Java 17)，已实测验证。因此**不需要**在 IDE 里手工设置 Gradle JDK，也**不要**往 `gradle.properties` 里加 `org.gradle.java.home`（机器相关的绝对路径，会破坏其它机器）。
- 该 toolchain 依赖本机存在一个 JDK 17。若换机器报 `No matching toolchains found`，装一个 JDK 17 即可；或者在 `settings.gradle.kts` 里引入 `foojay-resolver` 插件让 Gradle 自动下载（本仓库目前**未**引入）。
- **首次在新机器上构建，先跑 `./gradlew clean`。** `app/build/` 里可能残留旧的 kapt 生成代码（本项目同时应用了 `kapt` 与 `ksp` 插件，Room/Hilt 实际走 ksp）。陈旧的 `*_Factory.java` / `*_Impl.java` 会以 52 个 `compileDebugJavaWithJavac` 错误的形式爆出来（典型症状：`SaveScanWithImageUseCase_Factory` 按 3 个参数调用构造器，而实际是 4 个），`clean` 之后即消失。
- **`gradlew` 的可执行位可能丢失。** 若报 `permission denied: ./gradlew`，执行 `chmod +x gradlew`（Windows 时期遗留，已在基线之后修复并记录进 git）。
- **本机 `GRADLE_USER_HOME` 指向 `/Users/moon/develop/environment/gradle-8.10/conf`。** 那里有一个全局的阿里云镜像 `init.gradle`。本项目 `settings.gradle.kts` 设了 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`，若该 init 脚本使用老式 `allprojects { repositories { ... } }` 写法，构建会直接失败并报 `repository 'maven' was added by initialization script`。正确写法是用 `beforeSettings { settings -> settings.pluginManagement{...}; settings.dependencyResolutionManagement{...} }` 在 settings 级注入（本机已改为此写法，原文件备份在同目录 `init.gradle.bak`）。换机器时若遇到同样报错，先查全局 init 脚本，不要去改本项目的 `repositoriesMode`。
  > **注意 IDE 与命令行的 `GRADLE_USER_HOME` 并不相同。** 命令行走 shell 环境变量指定的 `/Users/moon/develop/environment/gradle-8.10/conf`（含阿里云镜像 init 脚本）；而从图形界面启动的 Android Studio 继承不到 shell 环境变量，用的是默认的 `~/.gradle`，那里**没有** init 脚本、**没有**镜像。目前 IDE 能正常 sync 只是因为 `~/.gradle/caches` 里已缓存了 AGP 等依赖（实测在该条件下 `clean :app:assembleDebug` 可通过）。**一旦缓存被清理或依赖发生变动需要联网拉取，IDE 侧就会因直连 `dl.google.com` TLS 握手失败而 sync 失败。** 彻底解决办法是把同一份 `init.gradle` 也放一份到 `~/.gradle/init.gradle`，或在 Android Studio 的 Settings → Build Tools → Gradle 里显式指定 Gradle user home。
- 另注：国内网络直连 `dl.google.com` 会 TLS 握手失败，阿里云镜像不是可选项而是必需项。
- **当前构建状态：`./gradlew clean :app:assembleDebug` 可通过**，产出 `app/build/outputs/apk/debug/app-debug.apk`（约 29MB）。
- **测试共 48 个**：仪器测试 42 个在 `app/src/androidTest/`（需模拟器或真机），JVM 单元测试 6 个在 `app/src/test/`。跑法：
  ```
  ./gradlew :app:testDebugUnitTest                                        # JVM
  ./gradlew :app:connectedDebugAndroidTest                                # 仪器
  ./gradlew :app:testDebugUnitTest --tests "com.example.sscan.MainStatsTest"   # 单个（仅 JVM 侧支持 --tests）
  ```
  覆盖的是高风险逻辑：Room 3→4 迁移（唯一「出错即静默清空用户数据」的改动）、全历史去重的两级判定、保存路径拼装与分组名安全化、节流双向污染的回归防线。相机链路与 `HistoryActivity` 不在覆盖范围。
  **注意 `connectedDebugAndroidTest` 跑完会自动卸载 App**，之后要重新 `adb install` 并恢复登录态才能继续手工验证。
- 本项目原先在 Windows(`E:\S-scan`)开发，`local.properties` 的 `sdk.dir` 和 `gradle.properties` 的 aapt2 覆盖曾硬编码 Windows 路径，现已修复。若换机器构建，第一件事是确认 `local.properties` 的 `sdk.dir` 指向本机 SDK（该文件已被 `.gitignore` 排除，不随仓库分发）。
- 根目录的 `compile.log` 是 Windows 时期一次 `:app:compileDebugKotlin` 失败的残留记录，不代表当前代码状态（该文件已被 `.gitignore`）。

## 架构主线
分层：`UI(Activity) → ViewModel → UseCase → Repository → Room / MediaStore / SharedPreferences`，Hilt 全程注入。

强调这几条（都是单看一个文件看不出来的）：
- **没有 Fragment，没有 Navigation Component。** 尽管 `navigation-fragment-ktx` / `navigation-ui-ktx` 在依赖列表里，全仓库 0 处 `NavHostFragment` / `findNavController` / `Fragment`。页面跳转一律 Activity + Intent + putExtra（例如 `ScanActivity.EXTRA_SCAN_MODE`、`EXTRA_FORCE_CONTINUOUS`）。新增页面请沿用 Activity 模式，不要引入 nav graph。
- **应用入口是 `LoginActivity`**（AndroidManifest 里唯一带 MAIN/LAUNCHER 的），不是 MainActivity。`MainActivity` 在 `onCreate` 和 `onResume` 都调用 `ensureLoggedIn()`，未登录则带 `CLEAR_TASK` 跳回登录页并 `finish` 自己。
- **`MainActivity` 没有 `@AndroidEntryPoint`**，不注入任何依赖，直接使用 `AppSettings` 单例，它只是个 dashboard/导航壳。
- UI 全部走 **ViewBinding**（`buildFeatures.viewBinding = true`），无 Compose、无 DataBinding。22 个 layout 文件。
- 状态流约定：`StateFlow` 承载状态（ScanUiState / HistoryUiState / LoginUiState），`SharedFlow` 承载一次性事件（ScanUiEvent 等）。UI 侧统一用 `repeatOnLifecycle(STARTED)` 订阅。

## 扫码链路（改扫码相关必读）
`ScanActivity`(CameraX 预览 + ImageAnalysis) → `MlKitProvider.createScanner(mode)` → `ScanViewModel.onScanResults(barcodes, imageFile)` → `SaveScanWithImageUseCase` → Room + MediaStore。

节流/去重常量硬编码在 `app/src/main/java/com/example/sscan/feature/scan/presentation/ScanViewModel.kt`：
- `scanThrottleWindowMs = 450L`
- `duplicateSuppressWindowMs = 5_000L`
- `maxCacheSize = 100`（`LinkedHashMap` 最近结果缓存）

分发结果枚举 `ScanDispatchResult`：ACCEPTED / DUPLICATE / THROTTLED / EMPTY。反馈事件枚举 `ScanFeedbackEvent`：SUCCESS / FAILED / DUPLICATE / TOO_FAST / BLUR_REJECTED，由 `ScanFeedbackManager` 转成振动/提示音/语音。

扫码模式 `ScanCodeMode`：ALL / QR_ONLY / BARCODE_ONLY，在 `core/scanner/MlKitProvider.kt` 里映射为 ML Kit 的 format 过滤。

`domain/usecase/SaveScanWithImageUseCase.kt` 里两个坑：
- **图片保存要求 API 29+**：`MediaStoreImageSaver.saveBitmap` 标了 `@RequiresApi(Q)`，use case 内也有 `Build.VERSION.SDK_INT >= Q` 判断。但 `minSdk = 24` —— 在 API 24~28 的设备上扫码图片会**静默不保存**，只写 Room 文本记录。
- **模糊图会被丢弃**：`ImageSharpnessDetector` 用拉普拉斯方差，默认阈值 `120.0`，低于阈值则 `blurRejected = true`、图片不存。
- 无论成功失败，`finally` 块都会删除临时的 `capturedImageFile`。

图片落盘位置：`MediaStoreImageSaver.DEFAULT_RELATIVE_PATH` = `Pictures/MultiQR`，保存前由 `ImageWatermarkHelper.addTimestampWatermark` 加时间戳水印。

## 数据层的两个真实陷阱

**陷阱一：分组(folder/group)状态被拆在两个存储里。**
- 分组"名字"存在 SharedPreferences：`AppSettings.getHistoryFolders/addHistoryFolder/removeHistoryFolder/renameHistoryFolder`，key 是 `history_folders`，类型是 `StringSet`
- 分组"归属"存在 Room：`scan_history.group_name` 列
- `ScanRepositoryImpl.getAllGroupNames()` 把两边 union 后去重排序

改分组功能必须同时动两边，否则会出现"文件夹存在但查不到记录"或"记录带组名但文件夹列表里没有"。参见 `data/repository/ScanRepositoryImpl.kt` 的 createGroup / deleteGroup / moveToGroup / renameGroup —— 这四个方法每一个都同时操作 AppSettings 和 DAO。

**陷阱二：分组不走 Room 表。** `ScanGroupEntity` / `ScanGroupDao` 曾是一次"把分组迁进 Room"半途而废的遗留（从未注册进 `@Database`），已删除。分组的唯一实现就是上面那套 SharedPreferences + `group_name` 列。

### 扫码目标文件夹
**进扫码页选一次，之后扫的过程完全不打断。** `ScanActivity.showFolderPicker()` 在
`startCamera()` 里自动弹一次（`maybeAutoOpenFolderPicker` 带 `folderPickerAutoOpened` 守卫），
顶部胶囊 `scanStatusChip` 常驻显示「保存到：xxx ▾」，点它可随时改。

曾经做过「每扫成功一次弹一次」，客户反馈**很不顺手**（重复扫码也要弹）。参考 App 的流程是扫之前选一次、扫完去历史页批量归类，现已改成同一套。**不要改回去。**

三条容易踩的约定：

- **自动弹窗必须挂在 `startCamera()`，不能挂 `onCreate()`。** `onCreate` 里 `ensureCameraPermission()` 会拉起系统权限框，紧接着弹自己的框会叠上去。`startCamera()` 是「权限已落定」的唯一汇合点（已授权分支与 `permissionLauncher` 授权回调都走它）。
- **胶囊兼任状态显示与文件夹芯片，回落由 `updateStatusText()` 自己负责，调用点什么都不用记。** 写入的若不是 `idleStatusText()` 就自动排一次回落，是则取消待回落。曾经靠「每个调用点记得调 `restoreActiveStatusLater()`」维持，结果手电筒 / 对焦 / 重置焦距 / 模式切换 / `renderScanStatus` 五处全漏了，那些瞬时提示会**永久**盖住目标文件夹。不要把这个职责再拆回调用点。
- **自动弹窗的守卫 `folderPickerAutoOpened` 只在弹窗真正关闭时置位，不能在显示前置位。** 它的语义是「用户已经有过一次选择机会」。提前置位 + `onSaveInstanceState` 持久化 = 转屏时 `onDestroy` 把弹窗 dismiss 掉、恢复出来的守卫却已是 true，提示永远不再出现，整批货静默落进「未分组」。
- **被拒权限态必须同时表达「相机没起来」和「这批存到哪」**（`scan_status_denied_with_folder`）。相机被拒之后相册读取照样产生记录，只显示「未授权」会让用户不知道这批去了哪儿；而 `applyTargetFolder` 必须写 `idleStatusText()` 而不是 `folderChipText()`，否则会被 dismiss 回调立刻盖掉。
- **胶囊的 `contentDescription` 必须在 `updateStatusText()` 里随文本更新，不能写死在 XML 容器上。** 带 `contentDescription` 的 `ViewGroup` 会被当成单个无障碍节点，子 View 的文字不再朗读——写死在容器上等于让读屏用户既听不到扫码状态、也听不到目标文件夹。
- **真·长任务状态（相册识别中 / 保存中）要传 `sticky = true`**，否则 900ms 的回落会把它们打断。
- **弹窗关闭（含取消）必须刷新一次 `idleStatusText()`；但不要在那里调 `resumeScanning()`。** 前者漏了扫码永久卡死——不崩溃、不报错，但等价于坏了；后者漏了芯片会停在 `startCamera` 设的文案上。

目标文件夹**不跨会话保留**：`targetFolder` 是 Activity 字段，每次进页面回到「未分组」（空串，与 `ScanHistoryEntity.groupName` 默认值一致），避免隔几天回来把新一批误扫进上一批。历史页的扫码搜索（`EXTRA_RETURN_RESULT_ONLY`）不弹窗也不显示芯片。

**弹窗逻辑全在 Activity 侧**，`ScanViewModel` / `SaveScanWithImageUseCase` / `CameraManager` 都没参与 —— 因为 `ScanDedupTest` 直接 new 出 `ScanViewModel` 调 `onScanRawResults()`，没有 Activity 接事件，若把选择回调塞进 `persistScanResults` 去 await，测试会永久挂起。

扫完之后的批量归类入口在**历史页「更多操作」→「批量归类 / 导出 / 删除」**。这是新流程的落脚点，此前叫「编辑」，没人看得出它能归类。

> **入口必须调 `showEditRecordDialog()`，不能直接调 `enterEditMode()`。** 后者只负责把编辑栏显示出来；`isEditMode = true`、重置 tab、清空上次勾选都在前者里。直接调内层会得到一个「看起来进了多选、实际没进」的状态：行点击跳详情页而不是勾选，且带着上一次的残留选中项——点「移动」会批量移动用户没选过的记录。

### 导出
`HistoryActivity.writeXlsx()` 用 `ZipOutputStream` 手写 OOXML，产出合法 `.xlsx`。**项目里没有也不需要 Apache POI**，别为了「加 Excel 支持」去引库。四种模式见 `ExportMode`：`CONTENT_TEXT("txt")` + 三个 `xlsx` 变体。

导出文件经 `core/storage/MediaStoreDocumentSaver` 落到**系统「下载」**（API 29+ 走 `MediaStore.Downloads`，无需存储权限；API 24~28 回退私有目录 + `FileProvider`）。**MediaStore 返回的 `content://media/...` 不能再喂 `FileProvider.getUriForFile()`**，会抛 `IllegalArgumentException`；两条路径在 `SavedDocument` 这个签名上合流。

### 已删除的功能（别再找了，也别"补回来"）

- **扫码出库**：客户明确说不做了 —— 他要的是**服务端 + 分享**，纯本地实现不了，留半套本地出库反而误导。已删 `ScanRepository.checkOutByResult` / `ScanHistoryDao.findActiveIdsByResult` / `ScanViewModel.performCheckOut` 与 `setCheckOutMode` / `ScanFeedbackEvent.CHECKED_OUT`+`NOT_IN_STOCK` / `ScanActivity.EXTRA_CHECK_OUT` 与 `applyCheckOutUi` / 首页出库卡 / 相关文案与图标，`ScanCheckOutTest`（5 个用例）一并删除。
  > `ScanRepository.moveToTrashByIds` **保留**：历史页删除与删分组仍在用，不是出库专用。

### 已知缺陷（尚未修，动到相关区块时注意）

- **历史页有两套互不相干的选择机制，长按那套是半成品。**
  - 编辑模式：`isEditMode` + `editSelectedRecordIds`，从「更多 → 批量归类 / 导出 / 删除」进（必须走 `showEditRecordDialog()`），移动/导出/对比/删除四个操作都只读它，唯一出口是 ✕。
  - 长按那套：`HistoryViewModel.selectedIds`，长按有完整勾选反馈（`selectIndicator` + `surface_selected` 底色），但**没有任何操作能施加到它上面**，也**没有取消入口**——`clearSelection()` / `selectAll()` / `onItemClick()` 全是零调用者。
  - 卡死路径：长按选中一行 → 用行内删除按钮删掉它 → id 仍留在 `selectedIds`，`isSelectionMode` 永远为 true，而那一行已从列表消失、无法再点掉，只能退出页面。
  - 死代码链：`shareSelectedItems()` → `getSelectedItemsOrShowToast()` / `resolveImageUri()` / `shareViaIntent()`，全部零调用者。
  - 交叉污染：长按选中若干行 → 进编辑模式（`renderList` 被 `isEditMode` 跳过，勾选看似消失）→ 退出编辑模式时旧勾选凭空复现。
- **全仓库没有任何返回键处理**（`onBackPressed` / `OnBackPressedCallback` 零命中）。编辑模式下按系统返回会直接结束整个 Activity，而不是退回普通列表——普通顶部栏此时是 `INVISIBLE`，返回箭头也点不到。
- **`captureAndDispatch` 的分发窗口没有 `try/finally`。** `dispatchingResult` 在 `CameraManager` 侧于分发**之前**置位，而三条路径会把它永久留在 true：`firstResult.isBlank()` 早退、`runCatching{}.onSuccess{}` 内抛异常（`onSuccess` 的 lambda 不在 try 里）、`scanViewModel.onScanResults` 抛异常。第一条目前不可达（`CameraManager` 已用 `isNullOrBlank()` 预过滤），但那是跨文件耦合，改任一侧都可能引爆。`onResume` 现有兜底复位，但那是止血不是治本。
- **抓拍往返没有看门狗**：`takePicture` 到回调之间标志一直被持有，CameraX 若丢掉回调就得靠 `onResume` 兜底。
- 4 个孤儿 dialog layout：`dialog_edit_record` / `dialog_edit_record_item` / `dialog_compare` / `dialog_rename`。
- 两个只读不写的设置项：`AppSettings.setFocusAutoCancelSeconds` / `setUserName` 零调用，值永远是默认值。
- **「不符合扫码规则时语音播报」开关**：它是个空壳 —— `MineActivity` 读写它，但没有任何代码消费，也没有 `RULE_MISMATCH` 事件、没有规则引擎，拨了永远无效。已删。客户圈过参考 App 的「扫码规则：无」，但没说清要什么规则，等他说清再做真的。

### 已删除的三个页面（别再找了）
- **分组管理页 `GroupActivity`**：与历史页的文件夹功能完全重复（历史页早就能新建/重命名/导出/删除文件夹），且它当时既没注册进 manifest 也没有任何入口，做完了却没人能打开。已连同 `GroupViewModel`、layout、相关文案一并删除。**历史页的文件夹功能保留**，那是客户要的「按分组分图片目录」。
- **筛选页 `FilterHistoryActivity`**：与搜索框能力高度重叠（搜索已支持按内容/备注/日期文本匹配），仅「时间区间」「仅图片/仅记录」不可替代但使用率低。已整页删除。
- **历史编辑页 `HistoryEditActivity`**：只在 manifest 里声明过，**从未被启动**；历史页早已内联重做了编辑模式（`isEditMode` / `enterEditMode()`，从「更多」菜单进入）。它那套独立的 TXT-only 导出（目录、文件名、是否分享都和主链路不同）根本没人能触达。已整页删除。
  > `HistoryViewModel` 里的时间/类型过滤逻辑**故意保留未删**：其默认值等同于不过滤，属惰性死代码，不会误导用户；而在 1800+ 行的 `HistoryActivity` 上继续做正则手术风险大于收益。

其它数据层事实：
- 库名 `s_scan.db`，当前 **`version = 4`**。`AppDatabase` 现在有一条真 Migration：`MIGRATION_3_4` 给 `result` 列建索引（全历史去重需要）。`fallbackToDestructiveMigration()` **仍然保留着**作为异常兜底 —— 去掉它会让 schema 不匹配时直接崩溃，留着至少不崩，但代价是**一旦漏写 Migration 就静默清空用户数据**。所以改 schema 必须同时写 Migration，并按 `MigrationTest` 的做法补测试。
  > 索引名必须写成 `index_scan_history_result`（Room 对 `@Index(value = ["result"])` 的自动命名），写错会导致 identity hash 校验失败。
- `exportSchema` 已开启，schema JSON 导出到 `app/schemas/` 并纳入版本控制，下一次迁移可以直接用标准的 `MigrationTestHelper`。
- `ScanHistoryEntity` 是软删除模型：靠 `deleted: Boolean` 字段区分历史与回收站，对应 `observeHistory()` 与 `observeTrash()`。字段还有 `result` / `imageUri` / `customTitle` / `groupName` / `time`。
- `AppSettings` 是 **`object` 单例、不走 Hilt**，所有方法都需要传 `Context`。它是全仓库唯一的 SharedPreferences 出入口（prefs 名 `s_scan_settings`，20+ 个 key）。不要在页面里直接 `getSharedPreferences`。

## 认证是 Mock 的
`DatabaseModule.provideSmsService()` 注入 `MockSmsService`，**验证码固定 123456**。登录态就是 `AppSettings` 里的 `login_phone` / `login_at` 两个 key，没有 token、没有服务端。接真实短信只需替换 `provideSmsService` 的返回值，`LoginViewModel` / `LoginActivity` 无需改动。

## 国际化现状
`values/strings.xml` 与 `values-en/strings.xml` 条目数一致（**各 253 条**）。语言切换走 `AppSettings.setLanguage` + `AppCompatDelegate` + `LocaleListCompat`；主题走 `AppSettings.setTheme`。两者在 `MainApplication.onCreate` 里通过 `AppSettings.applyAppPreferences(this)` 统一应用。**默认语言是中文**（客户在国内）。

用户可见文案已全部走 `strings.xml`，layout 里 0 处硬编码 `android:text`，Kotlin 里的中文只剩注释与 `Log`。新增文案一律走 `strings.xml` 并同步补 `values-en` —— **两侧 name 集合必须始终完全一致**，这是每次改动后要核对的硬约束。

## 设计令牌（改 UI 必读）

**尺寸维度已令牌化，不要再写硬编码 sp/dp。** `values/dimens.xml` 的 19 个令牌是唯一的尺度来源：

- 字阶 5 档：`text_xxl`(28sp) / `text_xl`(22sp) / `text_lg`(16sp) / `text_md`(14sp) / `text_sm`(12sp)
- 间距栅格 7 档：`space_1`(4) / `space_2`(8) / `space_3`(12) / `space_4`(16) / `space_5`(20) / `space_6`(24) / `space_8`(32)
- 圆角 3 档：`radius_lg`(12) / `radius_md`(8) / `radius_pill`(999)
- 尺寸 4 个：`size_icon`(24) / `size_icon_lg`(32) / `size_touch`(48) / `size_avatar`(56)

**全站页面左右 padding 统一 `space_5`(20dp)** —— 这一条保证各页左边缘对齐。

改这些之前先看 `values/themes.xml` 的 39 个 style：`activity_mine.xml` 有 137 处 `style=` 引用、`activity_main.xml` 57 处，**改 style 通常比改 layout 覆盖面大得多**。

**唯一允许的字面量例外**：`0dp`/`1dp`（权重占位与发丝线），以及「等于控件高度一半」的胶囊/圆形半径 —— 例如 `bg_login_input` 的 28dp 配 56dp 高输入行、`bg_scan_control_idle` 的 20dp 配 40dp 高按钮。**这些改成 `radius_lg` 会让圆按钮变成圆角方块**，文件里已加注释标注，不要「顺手统一」掉。

**颜色只有 4 个功能色**：`brand_primary`(蓝，主操作/选中) / `brand_success`(绿) / `brand_danger`(红，破坏性操作) / `brand_warning`(橙)，其余全是中性令牌。`values/colors.xml` 与 `values-night/colors.xml` 各 41 条，name 集合必须始终一致。

**不要用颜色别名法**（保留令牌名但把 hex 指到另一个颜色）。本仓库栽过一次：`bg_badge_mode_blue/green/purple` 三个 drawable 名叫蓝绿紫，实际内容全是同一个灰 `surface_subtle`，把后来的人误导了很久。要合并颜色就改引用后删令牌，`aapt2` 有编译期兜底。

**Kotlin 侧也不要写颜色字面量。** 历史模块曾有 51 处 `Color.parseColor("#XXX")`，在深色模式下会撞出白底黑字。现已全部改为 `getColor(R.color.token)`。唯二保留字面量的是 `ImageWatermarkHelper` 与 `BarcodeOverlayView` —— 它们画在位图和相机浮层上，与主题无关。

**平板适配已移除**：`values-sw600dp/dimens.xml` 与 `values-sw720dp/dimens.xml` 已删除。它们覆盖的是一套引用数为 0 的旧令牌，从未生效过；保留并让新令牌沿用同名，等于在平板上突然激活一套从未测试过的等比放大。将来做平板应新增 `layout-sw600dp/` 改列数，而不是靠 dimens 等比缩放。

## 其它需要知道的
- **`HistoryActivity.kt` 有 1824 行**，是全仓库最大的文件，混合了列表渲染、两套选择机制、筛选、导出 TXT、分享、文件夹等多套逻辑。动它之前先定位到具体区块，不要整体重写。第二大是 `ScanActivity.kt`(880 行)，然后是 `CameraManager.kt`(418 行)、`HistoryViewModel.kt`(409 行)。
- **`clean_food_data.py` 与本应用完全无关** —— 是个"贵阳美食"Excel 清洗脚本，硬编码着 `C:\Users\Administrator\Desktop\` 路径。不要把它当成项目的一部分。
- **`S-scan-开发设计文档.md`(1100+ 行) 内容详实但已与代码脱节**：写的 AGP 8.13.0（实际 8.7.3）、所有文件链接都是 `file:///e:/S-scan/` 的 Windows 路径。当背景资料读可以，**不要当事实源**，一切以代码为准。
- **`design/` 下有 5 张设计稿**，但它们是早期方向（多彩图标底圈、渐变 Hero、仪表盘式统计卡），客户明确否定了那套语言，说「花里胡哨、有点 low」。当前 UI 是**有意偏离设计稿**往更克制方向收的，**不要照设计稿去"还原"**。
- **`MainViewModel` 现在存在**（首页三个统计数字）。它的 `percentChange` 与 `todayDeltaPercent` / `totalDeltaPercent` / `imageDeltaPercent` 三个字段是**有意保留的**：UI 上的「较昨日」行已删，但这三者被 `MainStatsTest` 的 6 个用例直接测试，删了会让 JVM 测试全部编译失败。
- **`MainActivity` 未 exported**，`adb shell am start -n .../MainActivity` 会报 `SecurityException`。要从命令行启动用 `adb shell monkey -p com.example.sscan -c android.intent.category.LAUNCHER 1`。
- Release 构建开了 `isMinifyEnabled = true` + `isShrinkResources = true`，但 `signingConfigs` 未配置 —— `assembleRelease` 产不出可直接安装的包。ProGuard 规则在 `app/proguard-rules.pro`。
- `app/lint.xml` 已为 `androidx.camera.core.ExperimentalGetImage` 开启 opt-in，改动 ImageAnalysis 相关代码时无需再加注解。
- 仓库已建立版本控制。基线提交之后还有一次修复构建的提交；更早以前没有任何历史，因此没有 blame / 历史 diff 可参考。
- 仓库里有 14 个文件仍是 CRLF 行尾（Windows 时期遗留），git 会在 checkout 时提示归一化。
