# 测试矩阵与证据状态

[返回首页](<../README.md>) · [构建指南](<BUILD.md>) · [安全设计](<SAFETY_DESIGN.md>)

## 状态约定

- **单元模拟通过（JUnitCore）**：核心主/测试源码由 Gradle 编译，直接 JUnitCore 运行 22 项通过；只操作普通文件与 `FakeDevice`。本机 Gradle `:core:test` 匹配本机参数编码后也已 22 项通过，标准报告无失败/跳过；不能混同为 Android 整体通过。详见[验证记录](<VERIFICATION.md>)。
- **计划／未执行**：需要补充自动化、Android 仪器测试或受控真机操作，当前没有结果。
- **静态核对**：仅阅读源码确认设计，不替代运行测试。

当前[SafetyTest](<../core/src/test/kotlin/io/yu/flash/core/SafetyTest.kt>)共有 **22 个 `@Test` 方法**，本次直接 JUnitCore 运行全部通过，但不是完整覆盖证明。以下分类数不等于测试方法数：一方法可能覆盖多个输入，一个类别也可能需要多个新测试。GitHub 运行 36814142454 已通过核心测试及 Android 编译/Lint，并生成 APK；Lint 为 0 错误/23 警告。仍无 UI/仪器执行或真机证据。各次本地验证的失败与通过结果分开记录；后续 Gradle 成功不抹除先前编码故障。

另有 [UiSmokeTest](<../app/src/androidTest/java/io/yu/flash/UiSmokeTest.kt>) 的 3 个 Android 冒烟测试源码：首次进入不自动授权 Root、四页面导航与开发者信息、Activity 重建。**均已编译/未运行**。CI 配置编译仪器测试 APK，但不启动模拟器、不执行仪器测试。

## 分类别矩阵

| # | 类别 | 当前证据与状态 | 验收要点／还需验证 |
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
| 36 | Android 构建与 GitHub 证据 | Actions 36814142454 已通过；Lint 23 警告 | SDK35 检查、`:core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`，只在成功后上传 APK |
| 37 | AVB、快照、非 A/B 与恢复 | 未支持；相关拒绝验证计划／未执行 | 无适配即拒绝；没有回滚/恢复/AVB 绕过能力，不以普通文件测试替代硬件证据 |

## 证据记录模板

每次实际验证记录：提交标识、构建版本、JDK/Gradle/SDK、执行命令、开始/结束时间、测试方法通过/失败/跳过数量、日志和报告保存位置。设备项还需 Android 版本、脱敏设备标识、Root 管理器/工具版本、Bootloader 与槽位信息、存储介质和故障注入方式；不要公开指纹或完整分区数据。

失败、未完成、超时、环境缺失须原样记录。只有对应运行报告存在且可核对时，才把某项改为“通过”。JVM 文件模拟通过不自动升级 Android、UI 或真机条目。

## 发布前最低验收

1. 已取得本地与 GitHub 22 项核心测试通过证据，对应提交见[验证记录](<VERIFICATION.md>)。
2. 已取得[构建工作流](<../.github/workflows/android.yml>)的 Lint 与 APK 编译证据；23 项 Lint 警告仍待评估，不是零警告验收。
3. 受控验证读取/导入、Root 异常、生命周期、空间和提交故障，维持真实写入关闭。
4. 完成隐私与许可材料核对。若未来计划开放真实写入，另行审核设备适配、断电影响和恢复方案，不能沿用本预览版结论。
