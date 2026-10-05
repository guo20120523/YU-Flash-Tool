# 构建说明：优先使用 GitHub Actions

[返回首页](<../README.md>) · [签名与密钥保管](<SIGNING.md>) · [测试矩阵](<TEST_MATRIX.md>)

本指南说明工具链、构建步骤与发布流程，不维护某次构建的结果快照。每个版本的提交、APK、源码、测试和 Lint 结论以对应 [Releases 页面](https://github.com/guo20120523/YU-Flash-Tool/releases)及其附件为准。历史构建成功不等于当前提交或真机验收通过。

## 1. 项目和工具链

公开仓库：[guo20120523/YU-Flash-Tool](https://github.com/guo20120523/YU-Flash-Tool)。上传或 Fork 时保留隐藏目录，仓库根应直接包含[设置脚本](<../settings.gradle.kts>)、[根构建配置](<../build.gradle.kts>)、[应用构建配置](<../app/build.gradle.kts>)和[工作流](<../.github/workflows/android.yml>)，不要再套一层项目目录。

不上传缓存、模块输出、本地 SDK、个人密钥/密码或本地 SDK 路径配置。见[忽略规则](<../.gitignore>)。私密签名备份必须在项目及公开上传器范围之外；忽略规则不能替代上传器范围检查。

| 工具 | 固定要求 |
| --- | --- |
| Runner | `ubuntu-24.04`，构建超时 40 分钟 |
| JDK | Temurin 17 |
| Gradle | 8.11.1，CI 由 `gradle/actions/setup-gradle@v4` 提供 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin / Compose 编译插件 | 2.1.20 |
| SDK | Platform 35，验证用 Build Tools 35.0.0 |
| 当前发行身份 | `io.yu.flash`，`1.0.0`，版本码 `5`；持久 RSA-4096 证书 |

本地 Wrapper 见[配置](<../gradle/wrapper/gradle-wrapper.properties>)、[Unix 启动脚本](<../gradlew>)和[Windows 启动脚本](<../gradlew.bat>)。它们仍需要 Java 并可能下载 Gradle。**磁盘紧张的维护机不需要为了设置签名重装 JDK、Gradle 或 SDK；优先云端构建。**

## 2. 工作流入口与信任边界

在仓库 Actions 找到 **Android signed release build**。触发条件为 `main`/`master` push、pull request，或手动 `workflow_dispatch`。仅 `main`/`master` 且非 PR 的运行可以解码签名密钥、产出官方签名包并发布；其他分支与 PR 只检查核心、图标、Lint 和 Debug 编译，不依赖秘密。

签名前先由维护者按[签名说明](<SIGNING.md>)配置四个 `YU_RELEASE_*` Secrets。工作流不生成、旋转或公开私钥。创建/修改工作流文件本身不算构建已运行，更不代表已出包；应查看真实运行步骤结论。

| 阶段 | 行为与失败条件 |
| --- | --- |
| 检出与工具链 | `actions/checkout@v4` 不保留 Git 凭据；配置 Java 和 Gradle |
| 离线图标校验 | `python3 scripts/verify_symbols.py`，校验固定资源及许可 |
| 独立核心测试 | `gradle -PcoreOnly=true :core:test --no-daemon --console=plain`，不因 Android SDK 缺失掩盖核心结果 |
| SDK 前置检查 | 检查 Platform 35 及 Build Tools 35.0.0 的 `apksigner`/`aapt`；只写 runner SDK 路径配置 |
| 全分支检查 | `gradle :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain` |
| 主分支签名 | 仅非 PR 的 main/master：密钥恢复至 runner 临时目录，`gradle :app:lintRelease :app:assembleRelease --no-daemon --no-configuration-cache --console=plain` |
| 私密清理 | `always()` 删除临时 PKCS12；不上传含密钥/密码的目录 |
| 证据上传 | `always()` 收集真实核心 XML/HTML 与 Debug/Release Lint 报告；早期失败时可能不齐全 |
| APK 身份复核 | `apksigner verify --verbose --print-certs` 必须通过，唯一证书指纹须匹配公开固定值；`aapt` 与 Gradle 输出元数据共同确认包名、版本和非 Debuggable |
| 发布材料 | 同一提交的源码 ZIP、APK、报告 ZIP、构建身份、动态发布说明、许可、公开指纹及 SHA-256 摘要 |
| 独立正式发布 | 依赖前序全部成功，仅发布任务拥有 `contents: write`；重新检查摘要，先草稿上传全部材料再公开为非预发布 |

**SDK 检查不安装 SDK、不自动接受许可证。** runner 不满足前置条件时应明确失败，由维护者检查镜像与许可后处理，不能描述成自动补全环境。

## 3. 每次成功主分支运行保留独立 Release

每次成功运行使用唯一标签 `release-<运行序号>-<尝试次数>`，标题标明 **YU-Flash-Tool 1.0.0 · 正式签名构建**，不是 prerelease。后续运行不覆盖已有构建；重跑产生新的尝试标签。若上传中断，保留草稿供维护者调查，不把未上传完整的构建公开。

不自动创建或移动 `v1.0.0` 别名，避免重复主分支构建、并发运行或重试争用同一版本标签。每次发行的精确身份以完整提交和独立运行标签为准；需要语义版本标签时，维护者应在验收后单独指定提交。

“正式”描述签名/发行渠道，**不是兼容性、稳定性或刷写安全认证**。本应用包含实验性真实 raw 写入；云端编译/模拟测试不等于设备安全验收。

### 可下载材料

- `YU-Flash-Tool-verification`：Actions artifact，核心测试及 Lint 的真实报告。失败运行也可能有部分证据，不能只凭 artifact 存在认定成功。
- `YU-Flash-Tool-debug-validation`：仅 PR/非发布分支的 Debug APK 和测试 APK，不作为官方 Release 发布。
- `YU-Flash-Tool-signed-release`：成功主分支签名构建的完整交付 artifact，同时发布至独立 Release：
  - `YU-Flash-Tool.apk`：主应用，持久签名且非 Debuggable；不再用 Debug 包作主附件。
  - `YU-Flash-Tool-androidTest.apk`：Debug 仪器测试包，仅编译，不是主应用，也不能直接当作签名 Release 的仪器验收证据。
  - `YU-Flash-Tool-source.zip`：同一提交通过 `git archive` 导出的源码。
  - `YU-Flash-Tool-verification.zip`：本次核心测试与 Debug/Release Lint 报告。
  - `BUILD-INFO.txt`：提交、运行、版本、签名指纹、工具链与验证边界。
  - `APKSIGNER.txt`、`APK-BADGING.txt`、`SIGNING-CERTIFICATE-SHA256.txt`：签名与 APK 元数据证据、公开证书指纹。
  - `RELEASE-NOTES.md`、`LICENSE`、`THIRD_PARTY_NOTICES.md`：本次详细说明与许可。
  - `SHA256SUMS.txt`：除摘要表自身外所有交付文件的 SHA-256。

[发布说明生成器](<../scripts/release_notes.py>)读取本次核心测试 XML，计算真实通过/失败/错误/跳过数量，并分别读取 Debug/Release Lint 实际错误和警告。缺报告、零通过、测试失败、Lint 错误、缺附件或签名/版本证据不符时拒绝生成说明。仪器测试明确记为**编译完成、未执行**，不固定测试数量，不把旧计数当成新证据。[人工说明源](<RELEASE_NOTES.md>)仅在标题与 `1.0.0` 匹配时合并，否则采用当前正式签名说明，避免混入旧 Debug 下载指引；功能变更叙述仍由维护者负责。

下载后先记录完整提交、运行结论与测试/Lint数据，然后运行：

```text
sha256sum --check SHA256SUMS.txt
apksigner verify --verbose --print-certs YU-Flash-Tool.apk
```

证书指纹必须匹配[公开签名身份](<SIGNING.md>)。旧 Debug 预览签名不同，通常不能覆盖安装；尤其有写入意图/不确定状态时，不要为绕过签名冲突卸载、清数据或盲目重启。

## 4. 本地验证（仅工具链已经具备时）

无需密钥的核心、Lint、Debug 和仪器测试编译：

```text
gradle -PcoreOnly=true :core:test --no-daemon --console=plain
gradle :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Android 构建仍需要 SDK，核心 JVM 测试仍需要 Java/Gradle 及依赖。访问 Google Maven、Maven Central 和 Gradle Plugin Portal 与下载许可由执行者负责。

Windows 原生 GBK 且项目路径含中文时，历史上 Java 17 读取 Gradle UTF-8 参数文件曾导致 worker 类找不到，可仅在遇到该已识别故障的本机命令中使用：

```text
gradle -PcoreOnly=true :core:test "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK" --no-daemon --console=plain
```

已有安全环境配置的签名构建方法见[签名说明](<SIGNING.md>)；缺少秘密的 Release 打包任务必须失败，不降级为 Debug 或未签名包。**本流水线没有执行仪器测试**。运行仪器测试需另行具备模拟器/设备与明确验收安排，不纳入本次签名构建授权，也没有设备访问。

## 5. 常见失败与发布纪律

- Actions 没有工作流：检查仓库层级、隐藏目录、分支和仓库策略。
- Secret 名称存在但签名失败：名称检查无法读回验证秘密值；核对受限备份的公开指纹、别名和 PKCS12 兼容性，不重新生成密钥试错。
- SDK/依赖/插件下载失败：记录真实错误，不跳过检查或宣称编译成功。
- 核心测试或 Lint 失败：保留实际报告，不以测试方法数、旧截图或之前运行替代当前证据。
- 签名指纹、包名、版本或 Debuggable 检查失败：停止发布，调查构建输入和签名身份。
- 有报告而无 APK：可能是部分失败运行，须看实际构建步骤。
- Release 仍是草稿：本次发布未完成，不应宣传为已公开发行；新的工作流尝试保留新的标签，不覆盖历史材料。

发布前还需核对[第三方许可](<../THIRD_PARTY_NOTICES.md>)、[安全设计](<SAFETY_DESIGN.md>)及[测试矩阵](<TEST_MATRIX.md>)。固定密钥解决发行身份连续性，不解决设备风险。
