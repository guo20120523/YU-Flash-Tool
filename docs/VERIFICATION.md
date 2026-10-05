# 历史验证记录

[返回首页](<../README.md>) · [测试矩阵](<TEST_MATRIX.md>) · [构建说明](<BUILD.md>)

> 本文件保留早期工程、环境排障和指定实现的验证证据，不作为逐次构建状态页。**历史部分的“当前”“本次”只指当时源码。** 本次恢复直接 dd 的强制备份与精确范围读回，但不是旧固定 FD raw 事务，不能沿用旧结果为新实现背书；更早的写入关闭、空注册表与 RPMB 未修复也不是当前状态。各版本真实结果查看对应 [Releases 页面](https://github.com/guo20120523/YU-Flash-Tool/releases)。

## 当前 1.0.1 / code 6 证据边界（新结果待 CI）

本次目标为**签名 1.0.1 / versionCode 6，沿用原发布证书和密钥**，恢复自动完整备份、强制 `verifyBackup` 和写后读回，后续通过 GitHub 工作流发布。当前文档不是构建、测试、签名或发布成功报告。活动入口为 [DirectFlash](<../core/src/main/kotlin/io/yu/flash/core/DirectFlash.kt>)、[DirectDdWriter](<../app/src/main/java/io/yu/flash/root/DirectDdWriter.kt>)及[备份实现](<../app/src/main/java/io/yu/flash/root/RootDeviceAccess.kt>)，流程见[安全设计](<SAFETY_DESIGN.md>)与[发行说明](<RELEASE_NOTES.md>)。

| 当前项目 | 可以陈述的状态／缺少的证据 |
| --- | --- |
| 本次执行证据 | 新核心测试、Android 构建、Lint、签名与发布待对应 CI/产物；文档工作未执行这些操作，旧工具链记录不是本次运行 |
| 自动完整备份 | 本次要求确认后先复用现有备份实现，完整分区、明确目录、唯一任务目录、.partial/sync/长度/SHA/元数据；实际集成与故障路径待证据 |
| 备份准入与复核 | SafetyPolicy.backup 拒绝 DATA、挂载/映射及其未知状态、容量/身份未知等；整分区 + 64 MiB，不静默回退；verifyBackup 必须通过，否则不调用 dd，当前运行证据待 CI/设备 |
| 源与读回 | 写前测量实际源长度/SHA，dd→独立 sync 后只读助手精确范围流式读回，与写前源比较，再复核源；助手来自安装 APK 经 app_process，无大型额外临时镜像、无掩盖读取错误的 Shell 管道；待本次测试/Android/Root 证据 |
| 当前核心测试 | 已编写 138 项：45 项 [DirectFlashTest](<../core/src/test/kotlin/io/yu/flash/core/DirectFlashTest.kt>)、17 项 [ExactRangeDigestTest](<../core/src/test/java/io/yu/flash/core/ExactRangeDigestTest.java>)及保留旧 76 项；通过/失败/错误/跳过取自新 CI XML，不能把已编写当通过 |
| 旧代码边界 | 复用 SafetyPolicy.backup 不等于重启旧 SafetyPolicy.write / FlashTransaction / RootWriter / VerifiedCopy 写入事务；固定 FD、等长/格式、槽位/AVB/Bootloader/电量兼容性准入未恢复 |
| 任务与中断 | 自身门闩不锁外部工具；读取路径继承互锁可能拒绝自动备份，但不是当前 dd 生命周期保证；取消/超时不保证后代停止 |
| RPMB | 内容探测前过滤，设备回归未执行；不推导整盘支持 |
| Android 与仪器测试 | 本次编译/Lint 待 CI；当前 9 项 UI/资源仪器测试包含新增两项主页去重/加载回归，工作流仅编译、不执行；不证明确认、自动备份/读回阶段或加载文案运行通过 |
| 签名与 GitHub 发布 | 目标原证书不变、non-debuggable Release；需真实 APK 验证版本、签名、证书与原版一致、debuggable=false、摘要及实际上传；当前均不预报成功 |
| 真机、Root 写入、启动与恢复 | **无本次设备执行证据**，无兼容性或安全认证；模拟不能替代块设备/救援验收 |

当前成功条件是确认→完整自动备份→`verifyBackup`→写前源实际长度/SHA→`dd`→独立 `sync`→精确范围读回长度/SHA 与写前源一致→源复核一致。**短镜像仅校验前缀，未触及尾部不验证；过长输入可能部分写入后失败。** 原始字节不解析、不转换，sparse/压缩包仍危险。备份政策失败不能写，不得宣传所有目标无条件可写。无自动重试/回滚/重启，无固定目标 FD、后代 dd 生命周期、安全取消或掉电持久性保证。

## 历史直接路线统计（不是本次执行）

上一条无强制备份/读回的直接路线核心统计为 **104 项（28 项直接路线 + 76 项旧受限路线）**。此处仅归档历史数量，不将其复制为 1.0.1 的方法数或通过数；当前核心测试正在修订，应等待本次 CI XML。历史结果不能证明新增备份拒绝、verifyBackup、源修改检测或只读精确范围读回已通过。既有 **4 项 UI 仪器测试只编译、未执行**，仪器 APK 编译不等于 UI 执行。

## 历史受限 raw 证据（旧写入事务已退出活动路线）

以下归档曾对应 **0.2.0-raw-preview** 的受限 raw 实现。表格和后续本地运行记录保留当时原始结果；其中“当前”“本次”只指当时，**不是本次直接 dd + 强制备份/读回的验证状态**。恢复备份/读回要求不恢复旧固定 FD、原目标与备份同 FD 比对、root 锁/token、持久写入意图与同 boot 新进程阻断保证。

| 历史项目 | 当时状态／当时尚缺证据 |
| --- | --- |
| 核心测试源码 | [SafetyTest](<../core/src/test/kotlin/io/yu/flash/core/SafetyTest.kt>) 37 方法 + [VerifiedCopyTest](<../core/src/test/java/io/yu/flash/core/VerifiedCopyTest.java>) 39 方法，共 **76 项本地 Gradle 通过**；0 失败、0 错误、0 跳过，退出码 0 |
| 主要新增单元覆盖 | 显式风险接受、非 A/B/非当前 A/B、物理系统文件系统、环境和备份复查、回执与审计；内存端点短读短写、首字节前双哈希/guard、同步/读回/observer/rewind 故障及不重试；不是硬件或 Android 测试 |
| 实际写入链路 | 静态源码：RootWriter 经 app_process 固定 `O_RDWR \| O_EXCL` FD，先比较完整原备份哈希，再同 FD 写入/fsync/全量读回；不继承旧版“硬件写入抛错”的保障 |
| Root 锁与持久意图 | 静态源码：root0700 的 `/data/yu-flash-tool-writer` 锁/token；AtomicFile 意图成功后仍保留、成功授权仅内存；相关 Android/跨进程/断电语义未验证 |
| 操作门闩 | 静态源码：MainViewModel 设备探测与清理共享门闩/互锁，导入和实际任务同样受约束；设置/日志导出仍允许。新进程同 boot 即使先前成功仍阻止操作，需独立运行证据 |
| RPMB 探测 | 静态源码：名称/真实设备名过滤在 `od` 内容探测前；**未进行设备回归**，不冒充旧 APK 问题已由测试证实修复 |
| Android 构建/Lint | 本次结果待回收；旧 APK/Lint 结果只对应旧提交 |
| 既有仪器测试 | **4 项在旧构建中编译过，未执行**；没有新确认界面/互锁运行通过结论 |
| 真机与硬件写入 | **本轮没有进行真机测试或任何真实硬件写入/故障/恢复测试**。旧用户基础/只读反馈不能提升为本次验收 |

当时记录的风险：AVB、回滚索引、设备兼容性与可启动性没有被证明；确认框只能接受风险。旧实现的新进程同一次启动互锁不因旧成功日志而解除，有效记录仅在不同 boot ID 时不再阻挡资格；这也**不是建议盲目重启**。这些旧写入事务保证不能套用于当前直接流程；复用读取/备份路径仍可能继承互锁拒绝，但不为当前 dd 建立持久意图或后代生命周期保证。异常、超时、服务结束或取消不能证明 Root 后代已停止的风险仍存在。

### 历史受限 raw 本地运行证据（不是本轮重跑）

在 Windows 本地以 Temurin 17.0.20.1+1 / Gradle 8.11.1 运行（参数编码说明同下方历史记录）：

```text
gradle -PcoreOnly=true :core:test "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK" --no-daemon --console=plain
```

当时实际为 **BUILD SUCCESSFUL in 11m 8s**，退出码 0。原始 XML 统计：SafetyTest 37 项、8.732 秒；VerifiedCopyTest 39 项、3.484 秒；两份报告均 failures=0、errors=0、skipped=0。该记录只证明当时普通文件/内存端点的旧核心模拟通过，不执行 Root 助手或真实设备写入。它不证明 DirectFlash、DirectDdWriter、原样导入或新确认流程，也不证明本轮已执行任何测试。

当前直接路线和历史覆盖分别见[测试矩阵](<TEST_MATRIX.md>)。

---

## 历史范围与结论（以下均为旧版归档）

| 项目 | 归档时的真实结果 |
| --- | --- |
| JVM 主源码编译 | Gradle `:core:compileKotlin` 成功 |
| JVM 测试源码编译 | Gradle `:core:compileTestKotlin` 成功 |
| Gradle Wrapper | 8.11.1 生成成功；分发包 SHA-256 已固定 |
| Gradle `:core:test` | **22 项通过，退出码 0**：使用匹配本机编码的参数后成功；先前失败保留在下文 |
| 直接 JUnitCore 运行已编译测试 | **22 项通过，退出码 0**；普通临时文件/假设备模拟 |
| Android app / Lint / 测试 APK 编译 | **GitHub Actions 通过**，当前 0.1.1 见运行六；本地未安装 SDK |
| Android UI 测试 | 当前 4 项，**已编译、未运行** |
| Root、备份、SAF、生命周期真机测试 | 有用户手表基础/只读反馈，无完整独立验收；生命周期/故障测试未完成 |
| 真实刷写 | **不支持 / 未执行**；适配注册表为空，硬件写入方法直接拒绝 |
| GitHub Actions | 当前已核验 0.1.1 运行 **36817798625 成功** |
| APK | Debug 安全预览版；对应源码、报告、许可和摘要已发布到 preview-4-1 并核验附件 |

## 环境

- 本机 Windows；JDK Temurin **17.0.20.1+1**，Gradle **8.11.1**。
- Kotlin **2.1.20**；JUnit **4.13.2**；kotlinx.coroutines JVM **1.10.1**。
- 工程路径含中文。当前机器时钟显示 2026-10-01（仅作为本机时间，未做可信时间校验）。
- 工具放在工作区外层 `.yu-tools`，不随源码包再分发；SDK 未安装。

## 运行一：Gradle 编译、测试及 Wrapper

工程根目录运行：

```text
gradle -PcoreOnly=true :core:test wrapper --gradle-version 8.11.1 --distribution-type bin --no-daemon --console=plain
```

主源码、测试源码、核心 JAR 和 Wrapper 任务完成。随后测试执行器启动失败，耗时约 14 分 13 秒，退出码 **1**。关键原始错误：

```text
java.lang.ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
Could not write standard input to Gradle Test Executor 1.
Execution failed for task ':core:test'.
> Process 'Gradle Test Executor 1' finished with non-zero exit value 1
BUILD FAILED in 14m 13s
```

当时检查到 worker classpath 参数文件包含中文绝对路径，尚未定位根因。后续对照探针与匹配编码重试已确认本机参数解码问题，见运行四；GitHub 使用 Linux runner，仍应以其真实运行结果为准。

## 运行二：直接 JUnitCore

用运行一已经生成的 `core/build/classes/kotlin/main` 与 `core/build/classes/kotlin/test`，以及同一依赖缓存中的 Kotlin stdlib 2.1.20、coroutines-core-jvm 1.10.1、JUnit 4.13.2、Hamcrest 1.3，构造 Java `-cp` 后运行：

```text
java -cp <上述编译输出和实际依赖JAR，Windows用分号分隔> org.junit.runner.JUnitCore io.yu.flash.core.SafetyTest
```

以下为工具捕获的输出摘录（不是 Gradle XML 报告）：

```text
JUnit version 4.13.2
......................
Time: 3.994

OK (22 tests)
```

退出码 **0**。这些测试只操作新建的普通临时文件，不触及 Android 块设备。覆盖方法和未覆盖设备类别详见[测试矩阵](<TEST_MATRIX.md>)。该结果不是 Android 编译结果，也不是任何设备刷写资格。

## 运行三：UTF-8 环境重试（仍失败）

在同一 JDK/Gradle 环境设置进程级 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`，运行：

```text
gradle -PcoreOnly=true :core:test --rerun-tasks --no-daemon --console=plain
```

核心主源码与测试源码再次编译完成，但测试工作进程仍报 `ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain`。耗时 4 分 16 秒，退出码 1。**单独设置 UTF-8 没有解决问题**，不得将这一重试记录为通过，也不能由此证明路径就是根因。未修改机器级环境变量。

## 运行四：本机参数编码修复后标准 Gradle 测试通过

对照探针使用同一 classpath 内容：Java 17 读取原 UTF-8 参数文件时，第一个 JAR 路径 `exists=false`，中文代码点为 `U+6D93 U+5B2D U+6D47 U+5BB8 U+30E4 U+7D94 U+9356 U+7BED`；仅将参数文件编码改为本机 GBK 后 `exists=true`，代码点恢复为 `U+4E0B U+8F7D U+5DE5 U+4F5C U+533A`。这证明此前打印看似正常的 classpath 实际已被错误解码。

随后不修改源码、也不修改机器级设置，运行：

```text
gradle -PcoreOnly=true :core:test "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK" --no-daemon --console=plain
```

真实结果：**BUILD SUCCESSFUL in 2m 31s，退出码 0**。22 个测试全部 PASSED；标准 XML 为 `tests=22, skipped=0, failures=0, errors=0`，测试耗时 2.985 秒。证据副本：[Gradle JUnit XML](<evidence/core-tests.xml>)。原始报告的本机主机名已在交付副本中替换为 `redacted`，其余结果字段保持不变。

这是一条针对本机 Windows 原生 GBK 编码的命令行解决方案，不将项目默认 UTF-8 改成 GBK，也不要求 GitHub Linux 使用此覆盖。参数应匹配实际环境；不代表 Android 编译、设备测试或刷写资格。

## 交付后静态复核（未运行 Android）

- 前台服务仍要求 `startForeground` 成功；进度通知更新新增权限判断和异常隔离，避免通知权限被拒绝/撤销时把备份标记为失败。未做设备通知测试。
- Root 属性解析保留首行空值，避免非 A/B 设备空槽位使后续解锁属性错位。此变更未做真机验证。
- GitHub 工作流先以 `-PcoreOnly=true` 独立执行核心测试，再检查 SDK 和编译 Android；SDK 缺失时也可保留已执行的核心测试证据。工作流仍未实际运行。
- Root 命令启动前新增协程取消检查，避免已取消的后续探测仍启动 `su`；不改变已启动子进程的终止限制。
- 常见许可资源改为合并保留而不是排除；仍未验证最终 APK 的许可材料完整性。
- 静态交付检查退出码 0：7 份交付文档的相对链接均可解析；Manifest 无 INTERNET 权限；真实写入拒绝代码存在。该检查不是 Android 编译或运行验证。

## 运行五：GitHub Android 构建成功

- 公开仓库：https://github.com/guo20120523/YU-Flash-Tool
- 成功运行：https://github.com/guo20120523/YU-Flash-Tool/actions/runs/36814142454
- 构建提交：`a3bf9ac6ebf1a7d5fe5d0427e2e95fa13772844f`。本节是构建后的文档补记，APK 对应源码以 artifact 内源码 ZIP 为准。
- `:core:test` XML：22 tests，0 skipped，0 failures，0 errors，0.215 秒；核心 Gradle 步骤 46 秒成功。
- `:app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`：2 分 40 秒成功。3 项 UI 测试只编译，未执行。
- Lint：**0 错误、23 警告**，包括目标 API 不是最新、明确限定的 `/sdcard` 路径、依赖更新提示、DataExtractionRules、UsableSpace 和缺少应用图标；不是零警告验收。
- 两次前置失败保留：运行 36813532143 为 Kotlin 插件重复版本；36813836827 为根/子项目插件类加载器隔离导致 `com/android/build/gradle/api/BaseVariant` 缺失。统一根插件 classpath 后成功。
- APK SHA-256：`88f2780d942a24e12127c2d921663d310816c33d358f31ec3f8fd958ad1028af`。
- 对应源码 ZIP SHA-256：`4bfbd9e949aa24b0a1b8b688883d63ced76615c1383e01dfc611b5e5807bfdf1`。
- 下载构建 artifact 摘要与 GitHub API 一致：`94c004531b20ef35fd8138bd4f3abe3e873e95f19ab735104ca1f638884ddd9f`；验证报告 artifact：`3a3edb7a2527793c949aca40618f15435b3aadea5550c6ca031020869b305ddc`。
- 仅 Debug 安全预览；未安装、未真机测试，真实分区写入保持关闭。未宣称签名独立验证或完整传递许可证审计。

## 运行六：0.1.1 滚动改进与独立预发布

- 构建：[运行 36817798625](https://github.com/guo20120523/YU-Flash-Tool/actions/runs/36817798625)；提交 `5b3b01e9c44b8301d352188b95beb4f0a1ed1036`。
- 发布：[preview-4-1](https://github.com/guo20120523/YU-Flash-Tool/releases/tag/preview-4-1)，主分支构建成功后自动创建；9 个附件均已下载核对长度与 SHA-256。
- 核心 22 项通过，0 失败、0 错误、0 跳过；主 APK 与测试 APK 编译成功；Lint 0 错误、23 警告。
- 新增小视口回归测试，UI 测试共 4 项，仅编译未执行。整体主页滚动变更尚待实际手表运行验证。
- 用户此前报告 Android 手表基础/只读操作通过，同时发现 RPMB 普通探测 I/O 错误和小屏列表不可见问题；未收集完整机型、步骤与日志，不能据此把所有真机类别标为通过。
- RPMB 问题尚未修复；真实分区写入仍关闭。后续仅修改文档时不把旧 APK 冒充新构建。

## 当前直接路线后续要求（计划，不是执行记录）

1. 回收本次 CI 修订后直接路线及保留旧测试 XML，关联完整提交与运行链接，记录真实通过/失败/错误/跳过。数量待 CI，历史 104（28+76）或 76 都不能替代；未执行项如实保留。
2. 回收 Release Lint、主 APK、测试 APK 的真实结果；核验 **1.0.1 / code 6**、签名、`debuggable=false`、各产物 SHA-256、匹配源码，并将证书 SHA-256 与原发布证书比对，**不更换密钥**。配置不是产物核验，实际发布/上传另须证据。
3. 补充 content URI 原样复制、危险确认取消/重建、目录选择、自动备份/读回阶段、加载文字、重复提交和清理状态测试；既有 4 项 UI 仅编译未执行，不沿用其数量作本轮通过统计。
4. 验证确认→完整自动备份→mandatory verifyBackup→写前源长度/SHA→dd→独立 sync→精确范围读回→源复核顺序；备份策略、空间、提交或复核失败零写入，源修改、读回短读/I/O/长度/摘要错误不得成功，无重试/回滚。
5. 对只读 app_process 助手、备份/元数据、64 MiB 余量、目录不回退、SAF、RPMB 和生命周期制定另行授权的受控验证；不以 Shell 管道掩盖读错，不生成大型额外临时读回镜像。不得将旧固定 FD/root 锁/token 事务宣传为本次实现。
6. 保留短镜像未写尾部不验证、过长输入可能部分改写、原始 sparse/压缩包危险、无安全取消/掉电持久性保证；只知道自身任务，不能锁其他工具或保证后代 dd 终止。备份限制生效不等于写入期间状态始终安全。
7. 保留无硬件写入/启动/恢复验收、AVB/回滚/兼容性未证明。设备/故障试验须独立救援条件和另行授权，本文不授权立即执行破坏性试验。
8. 核对隐私、旧 Debug 签名冲突、许可和发布附件。后续工作流只追加本次真实证据，不把计划、静态审阅、历史报告、模拟、编译或签名核验升级为设备安全认证。
