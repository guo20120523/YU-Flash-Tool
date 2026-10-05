# 测试矩阵与证据状态

[返回首页](<../README.md>) · [构建指南](<BUILD.md>) · [安全设计](<SAFETY_DESIGN.md>)

> 本表区分**当前直接 dd + 强制完整备份/读回的待验项**与**旧直接路线、旧受限 raw / 早期只读版本的历史证据**。既有 104 项（28 项旧直接路线 + 76 项旧受限路线）仅为历史统计，不能当作当前通过数；本次已编写 138 项核心测试（45 项 DirectFlash + 17 项 ExactRangeDigest + 保留旧 76 项），实际结果待新 CI。各次真实统计以对应 [Releases 页面](https://github.com/guo20120523/YU-Flash-Tool/releases)及附件为准；本轮说明见[发行说明源文档](<RELEASE_NOTES.md>)。

## 状态约定

- **历史单元模拟通过**：有旧版普通文件/假设备证据，不代表当前源码或块设备通过。
- **修订中／待 CI**：断言或实现正在更新，没有本次执行报告，不得填“通过”或猜测数量。
- **计划／未执行**：需要自动化、Android 仪器或受控设备证据，没有可用结果。
- **静态核对**：只说明源码已阅读，不替代编译与运行。

## 当前直接 dd + 强制备份/读回（新证据待 CI）

活动入口为 [DirectFlash](<../core/src/main/kotlin/io/yu/flash/core/DirectFlash.kt>)与 [DirectDdWriter](<../app/src/main/java/io/yu/flash/root/DirectDdWriter.kt>)，备份复用 [RootDeviceAccess](<../app/src/main/java/io/yu/flash/root/RootDeviceAccess.kt>)。[DirectFlashTest](<../core/src/test/kotlin/io/yu/flash/core/DirectFlashTest.kt>)有 45 项、[ExactRangeDigestTest](<../core/src/test/java/io/yu/flash/core/ExactRangeDigestTest.java>)有 17 项测试方法，连同保留旧 76 项共 138 项；已编写不代表已通过；本次文档工作未运行测试、构建或签名核验。假执行器、普通文件或内存端点测试不能替代 Android 导入、Root 助手和真实块设备证据。

| 类别 | 当前证据状态 | 所需断言／仍需证据 |
| --- | --- | --- |
| content URI 私有复制 | 本次运行待验证 | 私有普通副本、实际长度、源文档不改写；sparse/archive/未知内容原样复制，不解析/转换；I/O、空间、撤权及提供器取消问题 |
| 单个危险确认 | 本次 UI 未执行 | 展示目标名/路径/字节数和警告；确认前不提交；关闭、旋转、重复点击；提示确认后先自动备份，不要求准确名称输入 |
| 完整自动备份顺序 | 核心测试修订中，待 CI；设备未执行 | 确认→完整分区备份→强制 verifyBackup→写前源测量→dd；已有手动备份不能替代；备份失败零写入 |
| 备份准入 | 本次回归待 CI/设备 | 复用 SafetyPolicy.backup；拒绝 DATA、挂载/映射及其未知状态、无效容量/身份；UNKNOWN/CRITICAL 风险类别本身不等同拒绝 |
| 目录与空间 | 本次集成未执行 | 明确选择 /sdcard/download 或 /sdcard/Download，无静默回退；完整分区 + 64 MiB；边界、溢出、目录失败、空间竞争 |
| 备份提交及复核 | 核心/Android 证据待回收 | 唯一任务目录、.partial→sync→完整长度/SHA→元数据提交；已有备份不覆盖；verifyBackup 缺失/长度/摘要/元数据错误必须零写入 |
| 写前源基准 | 核心测试修订中，待 CI | 测量实际源长度和 SHA-256，不只相信提供器/导入统计；测量错误或不一致不得静默放行；不是镜像格式/等长兼容性门槛 |
| 固定命令与引用 | 新核心证据待 CI | 固定 /system/bin/toybox dd、bs=1048576、正确路径引用和 /dev/block 约束；无任意 Shell |
| dd/sync 顺序与失败 | 新核心证据待 CI | dd 仅一次；非零/异常不进入后续成功流程；dd 为 0 才独立 sync；sync 失败不成功，无重试/回滚 |
| 只读读回助手 | 新核心/Android/Root 证据待 CI/执行 | 从已安装 APK 经 app_process 启动，只读打开目标；流式精确范围；无大型额外临时镜像，无掩盖读取错误的 Shell 管道 |
| 读回错误与精确范围 | 新核心证据待 CI；设备未执行 | 实际长度/SHA 与写前源匹配；分块短读须继续累计，范围总长度不足/提前 EOF、异常、助手失败、错长度/摘要均失败；短镜像仅验证前缀，未触及尾部不验证 |
| 源修改检测 | 新核心证据待 CI | 写后再次测量源长度/SHA，与写前基准比较；修改/异常不能成功，不能仅与修改后的源比较读回 |
| SUCCESS 语义 | 本次测试/日志核对待完成 | 必须备份及复核、源测量、dd、sync、精确范围读回、源复核全部成功；不是启动、恢复或掉电持久性证明 |
| 未恢复的旧写入策略 | 本次集成待验证 | 不调用旧 FlashTransaction/RootWriter/VerifiedCopy 写入事务；未恢复格式/等长、AVB、回滚、槽位、Bootloader、电量兼容性准入；备份限制仍强制生效 |
| 当前任务串行化与互锁 | 核心待 CI；Android 竞争未执行 | 应用共享门闩拒绝排队；读取路径继承互锁可能拒绝自动备份，但不是 dd 全系统锁、固定 FD 或后代生命周期保证 |
| 前台服务、取消与死亡 | 本次设备/故障未执行 | su 结束不保证后代 dd 停止；失败可能已部分写入或继续写入；不设“安全取消/掉电持久性通过”断言 |
| 手动读取与 RPMB | 本次回归待 CI/设备 | 手动读取保守策略保留；RPMB 别名与真实名在内容探测前过滤，不推断整盘支持 |
| 主页/危险框/UI | 当前 9 项 UI/资源仪器测试计划仅编译、不执行 | 新增两项主页单一入口/加载状态不重复断言；实际备份/读回交互、导航、布局、无障碍、确认与生命周期需设备执行，APK 编译不等于 UI 通过 |
| Android 构建/Lint/签名 | 本次待 CI/产物 | 1.0.1 / code 6、Release APK、测试 APK、Lint、debuggable=false、签名验证及证书与原版一致分别核验，不更换密钥 |
| 真机写入/启动/恢复 | **未执行** | 无适配认证；目标状态变化、外部并发、部分写入和掉电风险未消除；受控试验需另行授权及救援条件 |

## 历史直接路线统计（不代表本次已通过）

此前核心合计 **104 项 = 28 项旧直接路线 + 76 项旧受限路线**。旧直接路线不要求自动备份和读回，其结果不能验证本次新增顺序、备份拒绝、源摘要及助手。**本次实际执行数量及结果必须核对 CI XML**，不得在发行说明或报告中把 104 复制为新通过数。既有 **4 项 UI 仪器测试仅编译、未执行**；本次仪器数量、编译和执行状态分别以对应报告为准。

## 历史受限 raw 证据（旧写入事务已退出活动路线）

归档时[SafetyTest](<../core/src/test/kotlin/io/yu/flash/core/SafetyTest.kt>)有 **37** 个测试方法，[VerifiedCopyTest](<../core/src/test/java/io/yu/flash/core/VerifiedCopyTest.java>)有 **39** 个，旧本地 Gradle 已执行并通过全部 **76 项**：0 失败、0 错误、0 跳过，退出码 0。前者用普通临时文件与假设备，后者用内存端点；都不执行 Root 助手或真实块设备写入。此结果只记录当时源码，**不是本轮运行结果**。

下表保留当时断言、状态和待验项，其中“当前”“本轮”“写入”均指旧受限 raw 实现。现已恢复直接路线的备份/读回要求，但实现和范围不同，不能沿用旧测试为其背书；固定 FD、root 锁/token、持久写入意图和准确名称检查并未恢复。证据见[验证记录](<VERIFICATION.md>)。以下按主题分组，不是独立通过数或完整覆盖保证：

| 历史类别 | 当时覆盖／状态 | 当时仍需的证据 |
| --- | --- | --- |
| 确认与风险接受 | SafetyTest：未确认、目标名大小写/空格不符、未显式接受兼容性风险；本地单元模拟通过 | 实际 UI 导入→条件→完整危险确认、取消、旋转与恢复状态 |
| 资格而非设备认证 | SafetyTest：物理与可写必须明确；非 A/B、两种非当前 A/B；系统文件系统名称/类型；AVB 状态不冒充兼容证明；本地单元模拟通过 | 真机属性、动态/Virtual A/B 整体拒绝、厂商差异；不存在机型启动认证 |
| 长度、环境与身份 | SafetyTest：等长/类型/哈希格式、电量下限与温度边界、未知状态、风险/物理身份及别名比较；本地单元模拟通过 | Root 设备号、sysfs/容量与固定 FD 一致性、SELinux 拒绝 |
| 备份及写前复核 | SafetyTest：备份失败/错误长度、第二次备份复核失败、可写状态/电量/槽位/构建改变；本地单元模拟通过 | 实际下载目录、哈希/元数据、空间竞争、提交中断、原目标与全量备份一致性 |
| 审计、互斥与回执 | SafetyTest：WRITING 日志失败时不写、锁释放、重复任务拒绝、取消、回执身份/长度/哈希不符、无重试；本地单元模拟通过 | Android 日志故障、MainViewModel 所有探测与清理共享门闩/互锁、服务竞争 |
| 有界完整传输 | VerifiedCopyTest：短读/短写、跨 1 MiB 缓冲边界、单字节、限定长度不越界；本地单元模拟通过 | 真实块设备固定 `O_RDWR \| O_EXCL` FD、原目标哈希/写入/fsync/全量读回的内核语义 |
| 首字节之前 | VerifiedCopyTest：源哈希改变、原目标不等于备份、两次 guard、RECHECK/WRITING observer 顺序/失败、非法参数；本地单元模拟通过 | Root guard 的挂载命名空间、swap、父拓扑、一次性 token 与状态文件同步 |
| 传输故障无自动重试 | VerifiedCopyTest：零读/EOF/异常、零/负写、源中途变化、sync/读回哈希/rewind/observer 失败，保留部分输出而不重写回滚；本地单元模拟通过 | Root 后代持续运行、应用死亡、服务超时、断电、设备 I/O 和人工救援路径 |
| RPMB 在内容读取前过滤 | 当前源码静态路径存在，设备回归未执行 | 别名/真实名过滤后不进入 `od`；不得通过普通读取或写密钥来试探 |
| Root 锁与单次 token | 静态：`/data/yu-flash-tool-writer` 为 root0700，稳定 inode 锁与独占 token 创建；未运行 | 路径/所有权/权限/链接异常、存活旧助手并发、已用 token 拒绝，非 shell tmp |
| 持久意图及冷进程 | 静态：AtomicFile 意图保留，成功许可仅内存；未运行 | 同 boot 新进程在旧成功/失败后均阻止设备操作、导入、清理；设置/导出仍可用；不同 boot 有效记录与损坏记录、写入/目录同步故障 |
| Android 与硬件 | 本轮 APK/Lint 结果待回收；既有 4 项仪器测试仅在旧构建编译，未执行；**无本轮真机或硬件写入测试** | 不将普通文件单元测试、旧编译或基础反馈升级为写入/启动/恢复验收 |

### 历史仪器测试的边界

[UiSmokeTest](<../app/src/androidTest/java/io/yu/flash/UiSmokeTest.kt>)原有 3 个方法（首次进入不自动授权 Root、四页面导航与开发者信息、Activity 重建）；[HomeScrollTest](<../app/src/androidTest/java/io/yu/flash/HomeScrollTest.kt>)原有 1 个 240×160dp 小视口滚动回归方法。**原 4 项在旧构建中编译过，未执行**。后续图标变更又新增仪器覆盖，仍不能把测试 APK 编译称为 UI 执行通过；本轮方法数量和编译状态以实际报告为准。历史测试不是新直接写入确认或真实 Root dd 的运行覆盖。仪器 APK 编译与执行必须分别记录。

## 历史证据基线（旧版 0.1.1，不代表当前实现）

旧版核心主/测试源码经 Gradle 编译，直接 JUnitCore 运行 **22 项通过**；匹配本机参数编码后 Gradle `:core:test` 也为 22 项通过，无失败/跳过。旧 GitHub 运行 **36817798625** 通过核心测试、Android 编译和 Lint，Lint 为 **0 错误/23 警告**。用户手表基础/只读反馈缺少完整机型、步骤和日志，不是完整设备验收。失败与通过分开保留，后续通过不抹除编码故障，详见[历史验证记录](<VERIFICATION.md>)。

以下表格原有方法名、状态和设计仅记录**当时**：包括已被替代的空注册表、硬件写入抛错、非 A/B 不支持等。旧断言即使同名，也不代表当前修改后版本已重新通过。旧“计划”项目也不能因新源码出现而自动标为完成。

| # | 历史类别 | 旧版证据与状态 | 当时验收要点／未完成事项 |
| --- | --- | --- | --- |
| 1 | 正常事务顺序 | 单元模拟通过（JUnitCore）：`successfulFileSimulationIsOrderedAndBackedUp` | 备份先于写入、同步后读回；只是普通文件模拟，不是实际刷写 |
| 2 | 空设备适配注册表 | 单元模拟通过（JUnitCore）：`noQualifiedDeviceCanNeverWrite` | 无合格配置时调用写入次数为 0 |
| 3 | 用户取消确认 | 单元模拟通过（JUnitCore）：`cancellationConfirmationDoesNotWrite` | 未确认不得写入；UI 取消交互另需仪器测试 |
| 4 | 目标名准确输入 | 单元模拟通过（JUnitCore）：`typedNameMustMatchExactly` | 不同目标名被拒绝；不可用类似名称替代 |
| 5 | 备份失败 | 单元模拟通过（JUnitCore）：`backupFailureStopsWrite` | 注入备份异常后不调用写入；实际 Root 错误另测 |
| 6 | 备份复核失败 | 单元模拟通过（JUnitCore）：`backupVerificationFailureStopsWrite` | 校验失败停止事务；实际文件损坏、元数据不一致另测 |
| 7 | 目标身份竞态 | 单元模拟通过（JUnitCore）：`changedTargetBeforeWriteStopsTransaction` | 写前目标改变阻断；真实符号链接切换/目标描述符绑定尚未验证 |
| 8 | 槽位变化 | 单元模拟通过（JUnitCore）：`changedSlotStopsTransaction` | 环境槽位变化不得沿用旧确认；真实 A/B 属性采集另测 |
| 9 | 暂存篡改及哈希 | 单元模拟通过（JUnitCore）：`changedStagingStopsTransaction` | 暂存长度/哈希改变被拒绝；同长篡改等输入应补充 |
| 10 | 写入失败与不重试 | 单元模拟通过（JUnitCore）：`writeErrorDoesNotRetry` | 假设备只尝试一次、记录失败；无实际写入测试 |
| 11 | 同步失败 | 单元模拟通过（JUnitCore）：`syncErrorIsFailure` | 同步异常不能报成功；实际存储故障未测 |
| 12 | 读回 SHA-256 不匹配 | 单元模拟通过（JUnitCore）：`readbackMismatchIsFailure` | 不匹配终态失败，不自动重刷/重启/回滚 |
| 13 | 未知环境、电量、温度、充电 | 单元模拟通过（JUnitCore）：`unknownAndDangerousEnvironmentsFailClosed` | 槽位/解锁/快照/AVB 未知、49% 电量、43°C、无充电及工具失败被拒；边界值与传感器缺失需扩展 |
| 14 | 数据、挂载、映射、高风险目标 | 单元模拟通过（JUnitCore）：`mountedMappedDataAndUnknownTargetsFailClosed` | 挂载/挂载未知、映射、DATA、UNKNOWN 风险、当前槽位及非法容量/路径的写入被拒；读取策略单独验证 |
| 15 | 长度和类型 | 单元模拟通过（JUnitCore）：`wrongLengthAndTypeAreRejected` | 超长、非等长、类型不一致不进入写入 |
| 16 | 并发与重复点击 | 单元模拟通过（JUnitCore）：`doubleTapDoesNotQueueSecondTask` | 核心门闩拒绝排队；实际快速点击、服务重复启动、清理竞态仍为计划 |
| 17 | 内容签名而非文件名 | 单元模拟通过（JUnitCore）：`imageSignaturesAreContentBased` | sparse、ZIP、未知、EXT4 样例；更多格式及恶意头输入需补充 |
| 18 | 重复别名与稳定身份 | 单元模拟通过（JUnitCore）：`mergedDisplayAliasesDoNotChangeStableTarget` | 展示别名合并不改变身份，设备号/路径变化要拒绝 |
| 19 | 审计日志失败与锁释放 | 单元模拟通过（JUnitCore）：`auditFailureNeverPermitsWritingAndReleasesGate` | 注入日志异常不得放行，门闩释放；真实 SQLite 满盘/损坏另测 |
| 20 | 备份期间协程取消 | 单元模拟通过（JUnitCore）：`cancellationWhileBackingUpNeverWrites` | 协作假设备取消记中断、不写入；不覆盖阻塞提供器及 su 后代 |
| 21 | 规范命名不覆盖 | 单元模拟通过（JUnitCore）：`existingNormalizedImageIsNotOverwritten` | 已存在私有目标文件内容不改变；实际备份叶目录冲突另测 |
| 22 | 空文件、截断、sparse、伪装 | 单元模拟通过（JUnitCore）：`emptyTruncatedSparseAndDisguisedImagesFail` | 拒绝不完整/非支持镜像；不宣称完整格式验证 |
| 23 | Root 缺失、拒绝与非 uid 0 | 计划／未执行 | 分别展示正确状态，不继续发现/备份，不混同为已解锁 |
| 24 | Root 超时及后代进程 | 计划／未执行 | 授权超时、长命令、输出超限；直接进程清理不等于后代全停，保留风险提示 |
| 25 | 工具、SELinux 与设备读取失败 | 计划／未执行 | 缺命令、Toybox 行为差异、权限拒绝、mountinfo/sysfs 不可读时默认阻断 |
| 26 | SAF 本地普通文件及 URI | 计划／未执行 | 本地文件成功路径；非 content、管道/流、云提供器、撤销授权、零读/短读/中断失败路径 |
| 27 | 提供器阻塞与不配合取消 | 计划／未执行 | 打开阶段忽略 CancellationSignal 时不承诺立即取消；系统/用户恢复行为记录清楚 |
| 28 | 下载目录及空间 | 计划／未执行 | 两种大小写显式选择、不静默回退；目录不可写、空间刚好/不足、并发耗尽、巨大容量溢出 |
| 29 | 真实备份不覆盖与提交故障 | 计划／未执行 | 任务叶目录碰撞；既有备份保持不变；镜像/元数据各重命名失败、掉电、部分残留不能报完整成功 |
| 30 | 实际备份哈希与隐私 | 计划／未执行 | 长度、二次哈希、元数据及调用方复核；拒绝将哈希相符标为可恢复；下载目录暴露风险 |
| 31 | UI 与无障碍 | 计划／未执行 | 窄屏横向五列、字体放大、TalkBack 标签、触控区、深浅色、动态取色、输入法与滚动 |
| 32 | 大屏、旋转与折叠 | 计划／未执行 | 导航切换、旋转状态、折痕避让、分屏尺寸；保留当前目标身份，不误导入别的分区 |
| 33 | 前台服务与生命周期 | 计划／未执行 | 后台、划除任务、进程死亡、重启、通知拒绝、Android 15 超时；未完成任务标中断，不自动续跑 |
| 34 | 日志、导出及清理 | 计划／未执行 | 最新最多 1000 条事件、脱敏字段、导出失败、卸载；清理暂存不删备份，忙碌期拒绝清理 |
| 35 | 硬件写入永久拦截 | 静态核对；运行验证计划／未执行 | 即使 UI/核心被错误调用，当前 `RootDeviceAccess.write()` 仍抛错；不得以破坏拦截作设备测试 |
| 36 | Android 构建与 GitHub 证据 | Actions 36817798625 已通过；Lint 23 警告 | SDK35 检查、`:core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，只在成功后上传 APK |
| 37 | AVB、快照、非 A/B 与恢复 | 未支持；相关拒绝验证计划／未执行 | 无适配即拒绝；没有回滚/恢复/AVB 绕过能力，不以普通文件测试替代硬件证据 |

## 证据记录模板

每次实际验证记录：提交标识、构建版本、JDK/Gradle/SDK、执行命令、开始/结束时间、测试方法通过/失败/跳过数量、日志和报告保存位置。设备项还需 Android 版本、脱敏设备标识、Root 管理器/工具版本、Bootloader 与槽位信息、存储介质和故障注入方式；不要公开指纹或完整分区数据。

失败、未完成、超时、环境缺失须原样记录。只有对应运行报告存在且可核对时，才把某项改为“通过”。JVM 文件模拟通过不自动升级 Android、UI 或真机条目。

## 当前正式发布的最低证据要求（计划，仍待完成）

1. 回收本次修订后直接路线与保留旧测试的真实 CI XML，关联完整提交；分别记录通过、失败、错误、跳过或未执行。已编写的 138 项不是通过数；历史 104（28+76）和旧 76 也都不是本轮通过数。
2. 核对本次[构建工作流](<../.github/workflows/android.yml>)的 Release Lint、主 APK、测试 APK、源码和许可材料；旧 Lint 警告数不代表本次结果。
3. 验证主 APK **1.0.1 / versionCode 6**、原发布证书、实际签名和 `debuggable=false`；记录证书与 APK 各自 SHA-256，核对证书未变，不更换密钥。CI secrets 配置存在不等于签名成功；旧 Debug 签名冲突提醒保留。
4. 核对确认→完整备份→verifyBackup→源长度/SHA→dd→sync→精确范围读回→源复核顺序；备份拒绝、目录/空间/元数据故障必须零写入；读回短读/I/O/摘要错误、源修改不能成功；不重试/回滚。
5. 补充危险确认、自动备份/读回阶段、加载文案、状态保持、互斥、导入 UI；既有 4 项仅编译未执行，不用旧 UI 数量或 APK 编译推导本次通过。
6. 对 Root 只读助手、备份提交、提供器、RPMB 和生命周期制定受控验证。明确备份限制生效，但未恢复固定目标 FD、跨进程锁或后代 dd 生命周期保证；短镜像尾部不验证，过长输入可能部分写入后失败，无安全取消或掉电持久性承诺。
7. 保留**无硬件写入/启动/恢复验收、AVB/回滚/兼容性未证明、目标状态变化和外部并发风险**。设备/故障试验须另行授权和独立救援条件，本文不授权立即执行破坏性试验。
8. 完成隐私、签名、许可与公开附件核对，保留全部失败/未执行证据。发布工作流只能追加真实证据，不能将计划、静态审阅、模拟或签名通过升级为设备安全认证。
