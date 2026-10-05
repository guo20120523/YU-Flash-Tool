# 安全设计与未解决边界

[返回首页](<../README.md>) · [用户指南](<USER_GUIDE.md>) · [测试矩阵](<TEST_MATRIX.md>)

## 1. 定位：直接写入，不是兼容性或安全认证

当前活动写入路线按用户授权改为：**选择文件、私有复制、阅读警告与免责声明、明确确认，然后直接执行固定 Toybox `dd`，成功返回后单独 `sync`。** 没有镜像检查、兼容性预检、强制备份或读回。正式发行指版本和发布包装方式，不意味着设备风险已经验证或消除。

错误目标、错误文件、仍挂载/动态存储、供电故障或中断均可能导致部分改写、数据丢失、无法启动或变砖。AVB、回滚索引、机型、槽位和可恢复性未获证明。用户确认只表示接受风险；命令成功也不代表字节验证或设备可用。

旧 `SafetyPolicy` 的读取部分仍用于手动备份，旧受限写入与传输代码及测试仍留在仓库，**不能据其存在声称活动写入具有旧保护**。各版本证据见 [Releases](https://github.com/guo20120523/YU-Flash-Tool/releases)，历史结果见[验证记录](<VERIFICATION.md>)。

## 2. 活动写入路线与明确移除的策略

[DirectFlash](<../core/src/main/kotlin/io/yu/flash/core/DirectFlash.kt>)负责明确确认、共享任务门闩、阶段记录与结果处理；[DirectDdWriter](<../app/src/main/java/io/yu/flash/root/DirectDdWriter.kt>)将固定命令交给 [RootShell](<../app/src/main/java/io/yu/flash/root/RootShell.kt>)。

```text
content URI → 私有普通文件副本 + 实际字节数
→ 单个危险确认（目标名、设备路径、文件字节数、警告与免责声明）
→ CONFIRMED → WRITING → dd
→ 仅 dd 返回 0 时 SYNCING → sync
→ 仅两者均返回 0 时 SUCCESS（无读回）
```

不再使用以下写入准入或验证：

| 项目 | 当前直接路线 |
| --- | --- |
| 输入长度、类型、结构、SHA-256 | 不作为写入检查；不要求等长、已知格式或类型匹配 |
| 目标名称/风险/物理身份白名单 | 无旧写入白名单；不要求准确输入目标名 |
| 槽位与系统环境 | 不检查当前/非当前槽、AVB、回滚、Bootloader 解锁、动态分区或 Virtual A/B |
| 使用状态 | 不做写前挂载、映射、holders、swap 或可写性策略检查 |
| 电源与温度 | 不设最低电量、温度或充电准入 |
| 备份 | 不强制、不自动；手动读取是独立路线 |
| Root 写入助手 | 不调用旧 `RootWriter` / `VerifiedCopy` 执行活动写入 |
| 写后验证 | 不读回，不计算目标摘要，不比较原备份或输入摘要 |
| 不确定写入记录 | 旧标记不是直接路线的门槛，不据其阻止直接操作 |

移除检查不等于系统一定允许访问，也不等于所有发现目标均适合写入。SELinux、内核、Root 管理器、驱动或 I/O 错误仍可能拒绝或中途失败。应用不提供 Bootloader 解锁、AVB 绕过、自动切槽/重启、批量刷写、重试或回滚。

## 3. 保留的基础约束，不是设备安全策略

### 固定命令与路径引用

`DirectDdCommand.copy(source: String, target: String): String` 生成：

```text
/system/bin/toybox dd if=<引用后的私有文件路径> of=<引用后的已发现 /dev/block 路径> bs=1048576
```

后续使用独立命令：

```text
/system/bin/toybox sync
```

源路径必须为绝对路径，不含 NUL 或换行，使用单引号引用并转义内含单引号。目标须位于 `/dev/block/`、满足受控字符集且不含 `.` / `..` 路径段。UI 不接受用户编写的 Shell 或任意目标路径；输入来自私有副本和发现结果。这些约束旨在限定命令参数，**不复核目标兼容性、挂载状态或写入时身份**。

`dd` 按输入 EOF 写入，不按分区大小补零、清尾或截断输入，不展开 sparse、不解压 archive。不限制输入与分区的相对长度：短文件可能保留原尾部，空文件不构成有效刷写，过长文件可能在部分改写后才失败。

### 明确确认与当前任务串行化

危险框展示名称、设备路径、文件字节数及警告与免责声明，确认后才提交。当前运行任务由应用内状态和 `TransactionGate` 串行化，重复提交拒绝而非排队。

**应用只检测自身当前任务，不对其他进程或其他 Root 工具加锁。** 它不是全系统设备锁，也不能可靠识别旧应用进程遗留的 `dd`。不存在旧 `RootWriter` 的固定独占 FD、一次性 token 或助手侧锁保证；写入时按设备路径打开，发现与打开间状态可能变化。

## 4. 分区发现、Root 与 RPMB

[分区发现](<../app/src/main/java/io/yu/flash/root/PartitionRepository.kt>)从限定的常见 by-name 路径解析真实设备、设备号、容量和别名，采集内容头及挂载/映射信息用于展示和手动读取策略。不是扫描全部设备，不承诺整盘目标支持，也不等于写入前复核。

Root 授权由用户在主页“一键获取分区表”主动发起，运行时显示“正在读取分区表”。授权核对 uid 0 和工具；普通文件自检不把块设备作为输出，不是零文件写入。发现所依赖的信息或工具无法访问仍会导致发现失败。

RPMB 按别名和真实设备名在 `od` 等普通内容探测前排除。这是特殊协议设备的探测边界，不是 RPMB 功能支持。不要把直接写入描述成能操作所有分区或整盘；也不要通过普通读写或写密钥来试探 RPMB。

## 5. 文件导入：字节复制而非镜像验证

[导入器](<../app/src/main/java/io/yu/flash/storage/ImageImporter.kt>)仅接收 `content://` URI，从文档提供器读取流并复制到应用私有普通文件，统计实际字节数。提供器展示名不被用作路径，原始文档不改写。

- 不要求输入源描述符必须是本地普通文件；最终写入源是私有普通副本。
- 不调用旧 [ImageInspector](<../core/src/main/kotlin/io/yu/flash/core/ImageInspector.kt>)来决定该文件能否写入，不计算写入校验哈希，不按格式、目标容量或类型匹配拒绝。
- sparse、压缩包、未知内容都原样复制。**接受输入不是该格式的刷写支持**；没有展开、解压、转换或修复。
- URI 权限、读写、私有空间和暂存提交故障仍可使复制失败；这些 I/O 检查不是镜像适配检查。
- 提供器可能在打开或读取时阻塞、不响应取消。取消不能承诺及时完成，也不能作为目标 `dd` 安全停止证明。

## 6. dd、sync 与失败语义

1. `DirectFlash` 要求明确确认，记录 CONFIRMED 和 WRITING，再调用 `DirectWriter.copy`。
2. `DirectDdWriter` 执行固定 `dd`；非零退出即失败，不调用后续 `sync`，不重试或回滚。异常/取消也不会自动补写。
3. 只有 `dd` 返回 0 才记录 SYNCING 并独立执行 `sync`。同步非零或异常不是成功。
4. 两者返回 0 后记录 SUCCESS。**没有读回摘要或实际介质字节比对。** 记录中的输入文件字节数不是独立核验的持久写入量。

`sync` 不是固定目标 FD 的 `fsync` 加读回事务，不能据它证明介质掉电后持久、内容正确或可启动。短文件、空文件、错误内容/目标即使得到命令成功，仍可能没有产生预期结果。

失败可能发生在首字节之前，也可能发生在部分写入之后；退出码不能还原完整设备状态。不要把 FAILED 或 INTERRUPTED 理解成“没有写入”。

## 7. 生命周期、旧标记与未知后台状态

[操作控制器](<../app/src/main/java/io/yu/flash/OperationController.kt>)和[前台服务](<../app/src/main/java/io/yu/flash/TaskService.kt>)管理当前任务、服务通知、唤醒锁及超时。前台运行不保证应用不会被系统终止；超时预算不等于可安全取消。

Root 执行器只尽力结束直接 `su` 进程，**不能保证后代 `dd` 已经退出**。取消、超时、页面空闲、服务停止、应用死亡或失败日志均不是安全停止回执。后代可能继续改写，也可能已经留下部分损坏。

旧 [WriteInterlock](<../app/src/main/java/io/yu/flash/root/WriteInterlock.kt>)及其持久不确定标记仍保留，手动备份等旧读取路径仍可能因它拒绝操作，但**不再拦截当前直接流程**。不应将“重开应用后还能操作”解释成旧进程已经停止，也不能期待旧 token、root 锁或同 boot 拒绝机制保护新写入。应用不锁其他工具，并发访问可能损坏挂载中或动态存储。

[任务日志](<../app/src/main/java/io/yu/flash/storage/TaskJournal.kt>)记录阶段，重开后把未结束任务标为中断而不重跑；日志缺失、满盘或终态写入失败也不证明目标未被写过。应保留日志和已有备份，停止重复操作，由有能力的人员判断进程与设备状态。不要把删除旧标记、卸载、清数据或盲目重启当作恢复。

## 8. 手动读取备份仍是保守路线

[SafetyPolicy](<../core/src/main/kotlin/io/yu/flash/core/SafetyPolicy.kt>)的读取策略要求正容量、有效 `/dev/block/` 路径及 `major:minor` 身份；拒绝 DATA、挂载不是 `NO`、映射不是 `NO` 的目标。UNKNOWN/CRITICAL 风险本身不一定拒绝读取，不能声称所有高风险对象都不可备份。

[RootDeviceAccess](<../app/src/main/java/io/yu/flash/root/RootDeviceAccess.kt>)刷新身份，核对明确选择的 `/sdcard/download` 或 `/sdcard/Download` 及至少 64 MiB 余量，在独立任务目录读取临时文件，再同步、检查长度/文件 SHA-256，提交镜像与元数据。它不自动成为直接写入的先行步骤，可能因旧读取策略而拒绝。

备份边界：

- 文件哈希不是独立原设备再次全量一致性证明，也不是签名或抵御恶意 Root 篡改的保证。
- 镜像与元数据分开提交，不是跨文件原子事务；孤立文件或改后缀不代表完整成功。
- 空间检查不是容量预留，下载目录可被其他进程修改、耗尽或同步。
- 在线块读取不是一致性快照；备份存在不等于具有外部恢复路径。

## 9. 旧核心写入代码及历史测试的适用范围

[FlashTransaction](<../core/src/main/kotlin/io/yu/flash/core/FlashTransaction.kt>)、[RootWriter](<../app/src/main/java/io/yu/flash/root/RootWriter.java>)、[VerifiedCopy](<../core/src/main/java/io/yu/flash/core/VerifiedCopy.java>)及对应旧测试保留，但**不在活动直接写入路线上**。它们曾覆盖的强制备份、名称/兼容风险确认、类型/等长限制、固定独占 FD、写前双哈希、全量读回、root 锁/token 和持久互锁均不能移植为当前保证。

旧测试继续通过也只说明那些断言对应代码通过，不证明 UI 已接入新路线或实际 Root `dd` 正确执行。新路线的确认、字节直传、命令参数、退出码、无重试、同步失败及门闩测试需要独立证据。

## 10. 隐私与待验证事项

[应用清单](<../app/src/main/AndroidManifest.xml>)不声明 INTERNET 权限；外部文档提供器、下载目录同步及其他 Root 进程不受此限制。脱敏导出仍包含时间、任务 ID 和分区名，应自行检查。

本轮本地没有已安装的 SDK/JDK；新直接写入测试和 Android 构建等待 CI 实际报告，签名和 non-debuggable 发布产物尚不能宣称完成。历史 Android 仪器测试只编译、未执行；没有当前路线的真实硬件写入/故障/启动/恢复验收。详情见[测试矩阵](<TEST_MATRIX.md>)，发布时须保留失败、未执行及无设备验证边界。
