# 测试矩阵与证据状态

[返回首页](<../README.md>) · [构建指南](<BUILD.md>) · [安全设计](<SAFETY_DESIGN.md>)

> 本表分开记录**旧版证据基线**与**当前 raw 写入覆盖及待验项**。旧版测试通过、Lint 和 APK 编译只对应旧提交，不能转移到新写入实现。各次运行的实际统计以对应 [Releases 页面](https://github.com/guo20120523/YU-Flash-Tool/releases)及附件报告为准；本轮待发布说明见[发行说明源文档](<RELEASE_NOTES.md>)。

## 状态约定

- **历史单元模拟通过**：有旧版普通文件/假设备运行证据，不代表当前源码或块设备通过。
- **已编写／正在运行，待结果**：断言已存在但结果未回收，不得填“通过”。
- **计划／未执行**：需要补充自动化、Android 仪器或受控设备验证，没有可用结果。
- **静态核对**：仅阅读实现确认路径存在，不替代编译与运行。

## 当前 raw 写入覆盖（本地核心模拟通过，设备待验证）

当前[SafetyTest](<../core/src/test/kotlin/io/yu/flash/core/SafetyTest.kt>)有 **37** 个测试方法，[VerifiedCopyTest](<../core/src/test/java/io/yu/flash/core/VerifiedCopyTest.java>)有 **39** 个，本地 Gradle 已执行并通过全部 **76 项**：0 失败、0 错误、0 跳过，退出码 0。前者用普通临时文件与假设备，后者用内存端点；都不执行 Root 助手或真实块设备写入。证据见[验证记录](<VERIFICATION.md>)；Android 和设备状态另列，不能继承单元通过结论。

以下按断言主题分组，不是独立通过数或完整覆盖保证：

| 类别 | 已编写覆盖／当前状态 | 仍需的证据 |
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

### 既有仪器测试的边界

[UiSmokeTest](<../app/src/androidTest/java/io/yu/flash/UiSmokeTest.kt>)有 3 个方法（首次进入不自动授权 Root、四页面导航与开发者信息、Activity 重建）；[HomeScrollTest](<../app/src/androidTest/java/io/yu/flash/HomeScrollTest.kt>)有 1 个 240×160dp 小视口滚动回归方法。**4 项在旧构建中编译过，未执行**；不是新增写入确认、RootWriter 或持久互锁的运行覆盖。仪器 APK 编译与执行必须分别记录。

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

## 当前预览发布的最低证据要求（仍待完成）

1. 本地 76 项核心模拟已通过；继续回收 GitHub 重跑报告并关联完整提交，分别记录失败、错误、跳过及未完成，不能用本机或旧版结果代替该次 CI。
2. 核对本次[构建工作流](<../.github/workflows/android.yml>)的 Lint、主 APK、测试 APK 和对应源码证据；旧 23 警告不代表本次数量，也不是零警告验收。
3. 执行现有仪器测试并补充危险确认/状态保持/互锁 UI 覆盖；在无执行证据前明确标注“未执行”。
4. 对真实 Root 资格、RPMB 排除、固定 FD、全量备份对照、目录/锁/token、持久意图、后台异常与跨进程拒绝制定受控验证方案，具备外部救援条件后再由有授权人员评估设备测试。本文不是立即对硬件执行写入或故障注入的指令。
5. 当前代码已经能够写入，不能再以“硬件方法永久抛错”作为验收保障；如实保留**无硬件写入/启动/恢复验收、AVB/回滚/兼容性未证明**的发布限制。有效记录跨 boot 后不阻挡资格不等于应盲目重启。
6. 完成隐私、签名和许可材料核对，保留失败与未执行证据。任何单元测试或编译通过都不自动升级为完整工程或真机认证。
