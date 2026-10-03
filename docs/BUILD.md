# 构建说明：优先使用 GitHub Actions

[返回首页](<../README.md>) · [测试矩阵](<TEST_MATRIX.md>)

## 构建结果在哪里查看

本指南说明工具链、构建步骤与发布流程，不维护某次构建的结果快照。各版本的提交标识、APK、源码、测试与 Lint 结果以对应 [Releases 页面](https://github.com/guo20120523/YU-Flash-Tool/releases)及其附件为准。早期环境排障可查阅[历史验证记录](<VERIFICATION.md>)；历史成功不等于当前提交或设备验收通过。

## 1. 将项目内容上传到仓库根目录

已建立公开源码仓库：[guo20120523/YU-Flash-Tool](https://github.com/guo20120523/YU-Flash-Tool)。以下上传说明也适用于您自己的 Fork。

上传 `YU-Flash-Tool` 目录**里面的内容**，而不是让仓库根目录再套一层 `YU-Flash-Tool` 文件夹。需要保留隐藏目录 `.github`。特别检查上传工具是否遗漏点开头的目录。根目录应直接包含以下文件及目录：

- [settings.gradle.kts](<../settings.gradle.kts>)、[build.gradle.kts](<../build.gradle.kts>)、[gradle.properties](<../gradle.properties>)；
- 应用与核心模块（可用[应用构建配置](<../app/build.gradle.kts>)、[核心构建配置](<../core/build.gradle.kts>)确认层级）；
- [README.md](<../README.md>)、本说明等文档；
- **[.github/workflows/android.yml](<../.github/workflows/android.yml>) 必须位于仓库根目录对应路径。**

不上传本机 `.gradle` 缓存、模块 `build` 输出、SDK、个人签名密钥、密码或本机 `local.properties`。忽略规则见[.gitignore](<../.gitignore>)。工程已用 Gradle 8.11.1 生成 [Gradle Wrapper 配置](<../gradle/wrapper/gradle-wrapper.properties>)、[Linux/macOS 启动脚本](<../gradlew>) 和 [Windows 启动脚本](<../gradlew.bat>)。CI 仍用 `setup-gradle` 提供 `gradle` 命令；本地可用 Wrapper（Linux 上传后可能需要 `chmod +x gradlew`）。

## 2. 启动当前工作流

1. 打开该仓库的 **Actions**，按仓库策略启用工作流。
2. 找到 **Android safety-preview build**。
3. 默认分支上已有工作流后，可用 **Run workflow** 手动触发（`workflow_dispatch`）。当前也配置了对 `main`、`master` 的 push，以及 pull request 触发。
4. 展开各步骤，确认 SDK 检查、核心测试、Android Lint 和 APK 构建的实际结论。仅看到工作流文件或排队状态不算通过。

工作流当前顺序：

| 阶段 | 配置行为 |
| --- | --- |
| Runner | `ubuntu-24.04`，任务超时 30 分钟，仓库权限 `contents: read` |
| 检出源码 | `actions/checkout@v4` |
| Java | `actions/setup-java@v4`，Temurin 17 |
| Gradle | `gradle/actions/setup-gradle@v4`，Gradle **8.11.1** |
| 独立核心验证 | 先运行 `gradle -PcoreOnly=true :core:test --no-daemon --console=plain`，避免 SDK 缺失掩盖核心测试证据 |
| SDK 前置检查 | 检查 `ANDROID_HOME`、`platforms/android-35/android.jar`、`build-tools/35.0.0`，写入 runner 的 `local.properties` |
| Android 验证与构建 | `gradle :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain` |
| 验证证据 | `if: always()` 上传 `YU-Flash-Tool-verification`；无文件时警告，不凭空生成报告 |
| APK 与对应材料 | 前序成功后上传 `YU-Flash-Tool-debug-apk`；包含主/测试 APK、对应源码、报告 ZIP、构建身份、发布说明、许可与摘要 |
| 独立发布任务 | 依赖构建成功，仅 main/master 非 PR 运行；此任务单独授予 `contents: write`，核对摘要后创建草稿预发布、上传全部附件再公开 |

**SDK 检查步骤不安装 SDK、不自动接受许可证。** runner 环境不满足要求时应失败；不要将它描述为会自动补全环境。须由维护者检查 runner 镜像实际内容及 SDK 许可条件后处理。

## 3. 下载产物并核验证据

成功运行后，在该次运行页面的 Artifacts 中查找：

- `YU-Flash-Tool-verification`：配置收集核心测试 HTML/XML 报告与 Android Debug Lint 报告。失败发生太早时可能不存在部分报告；上传步骤执行过不等于测试通过。
- `YU-Flash-Tool-debug-apk`：成功构建才上传，包含主/测试 APK、对应提交源码 ZIP、验证报告 ZIP、构建身份、发布说明、许可与摘要；测试 APK 不是主应用。

成功的主分支构建还会在 [Releases](https://github.com/guo20120523/YU-Flash-Tool/releases) 创建 `preview-<运行序号>-<尝试次数>` 独立预发布，便于直接下载，无需只依赖有保存期限的 Actions artifact。发布说明由[说明源文档](<RELEASE_NOTES.md>)与[生成脚本](<../scripts/release_notes.py>)组合，附该次真实测试/Lint统计、提交和附件哈希。维护者每次功能更新须同步说明源文档，不能把自动统计误当成自动生成完整功能变更。

下载前记录提交标识、运行编号、任务结论、测试实际执行/失败/跳过数量及 Lint 结果。测试数量可能随源码变化，应以对应提交的报告为准。Debug APK 仅用于受控验证，不是发布签名包，不构成真机安全认证。安装后也不会开放真实写入。

## 4. 本地构建（可选，前置工具已具备时）

版本依据[根构建配置](<../build.gradle.kts>)、[应用构建配置](<../app/build.gradle.kts>)和[工作流](<../.github/workflows/android.yml>)：

- JDK 17；Gradle 8.11.1。
- Android Gradle Plugin 8.9.2；Kotlin / Compose 编译插件 2.1.20。
- Android SDK Platform 35；CI 明确检查 Build Tools 35.0.0。
- 能访问配置的 Google Maven、Maven Central 与 Gradle Plugin Portal。实际依赖下载及工具许可由执行者处理。

在工程根目录，使用已有环境执行与 CI 一致的命令：

```text
gradle :core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

若只运行 JVM 核心测试，可利用[设置脚本](<../settings.gradle.kts>)中的 `coreOnly` 开关排除 Android 模块：

```text
gradle -PcoreOnly=true :core:test --no-daemon --console=plain
```

本机 Windows 原生 GBK 编码且工程路径含中文时，Java 17 读取 Gradle UTF-8 参数文件曾导致 worker 类找不到；本次已验证的命令行解决方案如下（仅本机使用，不改变项目默认 UTF-8 或 Linux CI）：

```text
gradle -PcoreOnly=true :core:test "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK" --no-daemon --console=plain
```

此命令仍需要 JDK、Gradle 及核心依赖；**核心测试成功也不能证明 Android 编译或硬件行为正确**。构建任务只编译 Android 仪器测试 APK，不执行其中的 UI 测试，也不执行真机测试。要运行仪器测试，须另有模拟器或设备并执行 `gradle :app:connectedDebugAndroidTest`；测试不申请 Root、不执行设备读取或写入。

## 5. 常见失败的解释

- **Actions 中没有工作流**：检查上传层级、是否遗漏 `.github`、工作流是否在所需分支、仓库权限/Actions 设置。
- **SDK 检查失败**：核实 runner SDK 和环境变量，不跳过检查来伪造通过。
- **依赖/插件下载失败**：区分网络、仓库或版本解析问题；保存日志，不报告编译成功。
- **核心测试失败或挂起**：收集已有测试报告与日志，标记失败/待确认，不以方法数代替通过数。
- **Lint 或 Android 编译失败**：保留实际错误，由维护者修复；不能把只通过 JVM 测试的状态写成已出包。
- **验证产物存在但无 APK**：这是可能的失败运行状态，必须查看构建步骤。

发布前还需完成[第三方声明中的许可证核验](<../THIRD_PARTY_NOTICES.md>)及[测试矩阵](<TEST_MATRIX.md>)。本指南不虚构项目主页、下载站或仓库地址。
