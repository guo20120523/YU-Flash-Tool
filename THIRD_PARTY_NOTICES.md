# 第三方组件声明与许可证核验待办

[返回首页](<README.md>) · [构建说明](<docs/BUILD.md>)

## 重要说明

**本文件是基于当前构建声明整理的组件清单和许可核验提示，不是完整上游许可证文本合集，也不表示全部许可证、NOTICE、版权声明或传递依赖已经随工程/APK 打包。** 当前没有已交付 APK 或最终解析依赖清单，尚不能完成针对实际二进制产物的许可审计。

下述常见许可标识用于后续核验定位，应以所使用的具体版本、实际解析依赖及上游随附材料为准。本轮未下载组件或在线核验许可全文。不应把“使用开源组件”理解成免除再分发义务。

当前[关于页](<app/src/main/java/io/yu/flash/ui/SecondaryScreens.kt>)已明确声明本清单不等于完整上游许可证，发布前仍需补齐适用材料。

## 直接声明的应用及核心组件

依据：[应用模块](<app/build.gradle.kts>)、[核心模块](<core/build.gradle.kts>)、[根构建配置](<build.gradle.kts>)。

| 组件／坐标 | 当前声明版本 | 用途 | 常见许可标识／核验说明 |
| --- | --- | --- | --- |
| Kotlin 编译插件及相关运行库 | 插件 2.1.20；运行库以实际解析为准 | Kotlin/JVM、Android 与 Compose 编译 | Apache-2.0；核对具体分发中的第三方材料 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.1 | 核心协程与并发 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.1 | Android 协程调度 | Apache-2.0 |
| `androidx.compose:compose-bom` | 2025.04.01 | Compose 版本对齐，不是 UI 实现包版本本身 | AndroidX 通常 Apache-2.0；BOM 及解析结果分别核验 |
| Compose UI、Foundation、Material3、Material3 Window Size Class、UI Tooling Preview | 由上述 BOM 约束 | 界面、主题、预览与尺寸工具 | Apache-2.0；不要把 BOM 版本写成每个组件版本 |
| `androidx.activity:activity-compose` | 1.10.1 | Activity / Compose 集成 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose`、`lifecycle-runtime-compose` | 2.9.0 | 生命周期与状态订阅 | Apache-2.0 |
| `androidx.navigation:navigation-compose` | 2.9.0 | 页面导航 | Apache-2.0 |
| `androidx.datastore:datastore-preferences` | 1.1.7 | 设置存储 | Apache-2.0 |
| `androidx.core:core-ktx` | 1.16.0 | Android 扩展及兼容接口 | Apache-2.0 |
| `androidx.window:window` | 1.3.0 | 折叠/窗口信息 | Apache-2.0 |

以上是**直接声明清单**，不穷尽传递依赖。最终 APK 可能包含哪些具体版本，需要成功解析和构建后检查，不能凭当前声明宣称完成合规审计。

## 测试与调试依赖

| 组件 | 声明版本 | 用途及许可提示 |
| --- | --- | --- |
| `junit:junit` | 4.13.2 | JVM 测试；EPL-1.0；不应误写为 Apache-2.0 |
| `kotlinx-coroutines-test` | 1.10.1 | 核心测试工具；Apache-2.0 |
| Compose UI Tooling、UI Test JUnit4、UI Test Manifest | BOM 2025.04.01 约束 | 调试或 Android 测试依赖；Apache-2.0；实际打包范围需核验 |
| `androidx.test.ext:junit` | 1.2.1 | Android 测试扩展；Apache-2.0 |

声明测试依赖不等于测试已运行通过。当前有 22 个核心 `@Test` 方法，以及 [UiSmokeTest](<app/src/androidTest/java/io/yu/flash/UiSmokeTest.kt>) 中 3 个未运行的 Android 冒烟测试。核心直接 JUnitCore 与修正本机参数编码后的 Gradle `:core:test` 均为 22 项通过，详见[验证记录](<docs/VERIFICATION.md>)。

## 构建工具与 CI

- Android Gradle Plugin 8.9.2、Gradle 8.11.1、Kotlin/Compose 插件 2.1.20：构建侧工具，不因被构建使用就当然成为 APK 内的再分发内容。Gradle 与 AGP 通常以 Apache-2.0 提供，其具体分发中的第三方声明仍需核验。
- JDK：当前 CI 选择 Temurin 17。JDK 使用/分发条款与 AndroidX 许可不同，不应统一标记为 Apache-2.0。
- Android SDK Platform 35、Build Tools 35.0.0：属于开发环境；需要执行者遵守对应 SDK 条款。[工作流](<.github/workflows/android.yml>)只检查 runner 现有组件，不自动接受 SDK 许可证。
- `actions/checkout@v4`、`actions/setup-java@v4`、`gradle/actions/setup-gradle@v4`、`actions/upload-artifact@v4`：CI 工作流引用，不是声明随 APK 分发的运行库；实际复用/再分发这些工具时另行核验其许可与依赖。

设备端实现调用设备已有的 `su`、系统 Toybox 和 Android 平台服务；工程没有因此取得或附带 Root 管理器、`su` 或完整 Android 系统的许可证。设备工具是否可用、是否被允许使用及其许可应单独确认。

## 发布前必须补齐的工作

1. 依据对应提交的**实际解析依赖**生成直接和传递组件清单，区分发布、Debug、测试及构建工具，记录精确版本与来源。
2. 核验每个实际再分发组件的许可证、版权声明、上游 NOTICE 和附加义务；需要保留的全文与 NOTICE 应按适用条款随分发提供。
3. [应用打包配置](<app/build.gradle.kts>)已将 `META-INF/AL2.0`、`META-INF/LGPL2.1` 改为资源合并而非排除；仍须检查实际 APK，不能仅凭合并规则宣称材料完整或存在某个 LGPL 组件。
4. 如存在 EPL、LGPL 或其他特定义务组件，依据实际使用/修改/再分发方式逐项评估；本清单不是法律合规结论。
5. 补齐并提供可访问的完整许可材料；同步更新关于页及版本清单。
6. 经项目所有者确认，原创源码采用 GPL-3.0-only，见 [LICENSE](<LICENSE>)。第三方组件仍遵守各自许可。工程附 [Apache-2.0 全文](<licenses/Apache-2.0.txt>)，应用 assets 内同时提供 GPL v3 和 Apache-2.0 全文，但这不等于完整传递依赖审计。

本文件不提供虚构项目仓库、官网或下载地址，也不把尚未取得的上游文件描述为已经包含在工程中。
