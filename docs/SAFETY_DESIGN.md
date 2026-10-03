# 安全设计与未解决边界

[返回首页](<../README.md>) · [用户指南](<USER_GUIDE.md>) · [测试矩阵](<TEST_MATRIX.md>)

## 1. 定位：有真实写入能力，不是设备认证

当前源码实现分区发现、读取备份、本地导入及**受限的真实 raw 写入**。旧版“UI 不提交、空适配注册表、硬件方法直接抛错”的永久阻断已经不是当前实现。源码设计、普通文件/内存模拟、Android 编译和真实设备验收是不同层次，不能相互替代；没有真实硬件写入/故障测试结果。

错误目标、镜像、供电故障或进程中断可造成部分改写、数据丢失、无法启动或变砖。**AVB、回滚索引和设备兼容性未被证明。** 用户勾选与准确输入目标名只表示显式接受风险，不会生成兼容性证据。备份和哈希一致均不保证可启动或可恢复。

各版本变化、实际验证与已知问题见对应 [Releases 页面](https://github.com/guo20120523/YU-Flash-Tool/releases)；旧证据见[历史验证记录](<VERIFICATION.md>)。

## 2. 策略：明确的范围限制，不伪装成适配白名单

[核心模型](<../core/src/main/kotlin/io/yu/flash/core/Model.kt>)用 `YES / NO / UNKNOWN` 表达未知状态。[安全策略](<../core/src/main/kotlin/io/yu/flash/core/SafetyPolicy.kt>)分离读取和写入；未知的必要资格不能当作安全。

### 读取策略

要求正容量、`/dev/block/` 路径及格式有效的 `major:minor` 身份；拒绝 DATA、挂载不是 `NO`、映射不是 `NO` 的目标。`userdata`、`metadata`、`cache` 归为 DATA，避免把承载下载目录的数据分区回读到自身。

风险分类为 UNKNOWN 或 CRITICAL 并非单独的备份拒绝条件。若读取的其余条件均满足，源码可能允许其进入备份；不能宣传成“所有高风险分区都不可读取”。这些备份仍可能含敏感数据。

### 写入资格

写入首先满足读取策略，并要求：

- 工具自检成功，目标 `physical=YES`、`writable=YES`；拒绝整盘、RPMB、只读和未知物理身份。
- boot 类仅 `boot`、`init_boot`、`vendor_boot`、`recovery`，风险为 BOOT_CHAIN 且内容为 BOOT/VENDOR_BOOT。
- 系统文件系统仅 `system`、`vendor`、`product`、`odm`、`system_ext`、`vendor_dlkm`、`odm_dlkm`、`system_dlkm`，风险为 FILESYSTEM 且内容为 EXT4/F2FS/EROFS。
- 名称可带 `_a` / `_b`。非 A/B 要求设备与目标均无槽位；只要任一有槽位，就要求两者均明确为 a/b 且目标是非当前槽位。A/B 无槽别名不放行。
- Bootloader 已确认解锁；动态分区与 Virtual A/B 环境拒绝。环境中的 `snapshotSafe` 在此仅表示限定场景排除，不是通用快照或 OTA 安全证明。
- 电量已知且不低于 `minBattery.coerceIn(50, 100)`；温度已知且在 0–42°C；默认要求充电。设置只能按其定义调整要求，不能取消强制备份或哈希。
- 镜像类型与目标一致，长度严格等于整个分区，SHA-256 格式有效；不补零、截断、清尾或转换 sparse。

`vbmeta`、关键固件、用户数据、动态/映射和未知目标不在写入范围。**不再要求空的设备认证注册表，也不将 `avbCompatible` 当作证明：其环境值仍为 UNKNOWN。** 构建指纹用于检测前后变化，而不是认证机型；名称和格式白名单只是范围约束。事务另强制 `compatibilityRiskAccepted` 与准确目标名。

[环境采集](<../app/src/main/java/io/yu/flash/root/PartitionRepository.kt>)要求解锁属性一致；动态属性不能为 `true`，Virtual A/B 要为 `false`，或仅在 API 29 以前允许缺失。较新系统缺失 Virtual A/B 信息拒绝。属性与厂商实现仍未做设备级验证，不应据此声称所有 OTA 风险被排除。

## 3. Root、RPMB 与目标身份

[Root 执行器](<../app/src/main/java/io/yu/flash/root/RootShell.kt>)只接受包内受控模板，UI 不接收 Shell。用户主动授权后验证 uid 0。Toybox 和 `RootWriter --probe` 自检写入应用自有普通临时文件并做同步/读回，**不以块设备为输出，也不是零文件写入**。

[分区发现](<../app/src/main/java/io/yu/flash/root/PartitionRepository.kt>)只查常见 by-name 路径。解析别名后先以不区分大小写方式排除别名或真实设备名中的 `rpmb`，在 `od` 内容探测前停止；这修正普通探测 RPMB 的路径，不是 RPMB 协议支持。之后读取 sysfs 身份/容量/物理拓扑、只读状态、init 挂载信息、映射/holders 与内容头，按设备号合并展示别名。

稳定目标比较包含名称、所选别名、规范路径、设备号、容量、槽位、类型、风险和物理身份；展示别名列表不属于稳定身份。可写、挂载、映射和环境条件在执行前重新按策略检查。

发现与预检查不是原子操作。真实写入因此另由固定已打开设备 FD 的 Root 助手执行身份、容量及拓扑复查，而不是只相信 UI 选中的路径。

## 4. 备份协议与其边界

[RootDeviceAccess](<../app/src/main/java/io/yu/flash/root/RootDeviceAccess.kt>)先刷新并比对目标、重新核对用户选择的下载目录与空间，保留至少 64 MiB 余量。只支持明确选择 `/sdcard/download` 或 `/sdcard/Download`，不静默回退。

1. 用时间戳和 UUID 建任务叶目录；叶目录 `mkdir` 不带 `-p`，碰撞失败，不复用旧任务。
2. 校验别名解析和块设备类型，用 `dd` **读取**到 `.partial` 普通文件；随后 `sync`、检查实际长度和 SHA-256。
3. 再查目标身份，再哈希持久化文件。
4. 写入含身份、长度、哈希与警告的元数据，提交镜像及元数据。
5. 调用方复核文件长度、哈希与元数据 `state=VERIFIED`、`bytes`、`sha256`；写入事务还会重复复核。

边界：

- 独立备份操作的哈希检查对象是备份文件，**不是独立原设备再次全量一致性证明**。真实写入路径另外在固定 FD 上、首字节之前将原目标全量哈希与该备份摘要比较。
- 元数据不是签名，`verifyBackup()` 不核验全部身份字段，不提供抗恶意 Root 篡改保证。
- 镜像与元数据分开重命名，不是跨文件原子事务；提交失败尝试将镜像降回 `.partial`，仅最佳努力。孤立 `.img` 不能证明提交成功。
- 空间检查不预留容量，共享下载目录仍可能被外部进程耗尽、修改或同步；唯一任务目录不是对恶意 Root 或所有文件系统故障的绝对保证。
- 在线块读取不是一致性快照，备份存在不意味着有可用恢复路径。

## 5. 导入、格式与哈希

[导入器](<../app/src/main/java/io/yu/flash/storage/ImageImporter.kt>)仅接受 `content://`，通过 `fstat` 要求普通文件，不信任提供器展示名或声明长度。UUID 私有目录存放副本；按实际读取量限制目标容量，每块写入前检查 64 MiB 余量，完成后同步与长度复核。原始文档不改写，成功后不自动清理暂存。

[镜像检查器](<../core/src/main/kotlin/io/yu/flash/core/ImageInspector.kt>)识别 BOOT、VENDOR_BOOT、AVB、EXT4、F2FS、EROFS 等签名；拒绝 sparse、压缩格式、未知内容；boot 类做头版本、页大小及载荷范围等基础检查。**不等于完整格式认证、签名验证、文件系统检查、AVB 或硬件匹配**。识别 AVB 结构不使其成为可写目标。

取消通过协程、`CancellationSignal` 与关闭描述符尽力处理；提供器在 `openFileDescriptor()` 阻塞时可能忽略取消。普通文件检查只能在打开返回后完成，不承诺及时安全取消。

## 6. 真实写入事务与固定 FD

[FlashTransaction](<../core/src/main/kotlin/io/yu/flash/core/FlashTransaction.kt>)的主要阶段：

```text
IMAGE_CHECK → TARGET_CHECK → CONFIRMED → BACKUP → BACKUP_VERIFY
→ NORMALIZE → RECHECK → WRITING → SYNCING → READBACK → SUCCESS
```

事务要求显式确认、准确目标名和兼容性风险接受；重新解析私有镜像并核对原导入结果；刷新目标、槽位和构建；强制完整备份和复核；只重命名私有副本且拒绝重名；再次复核备份与环境。写入调用前持久记录阶段，成功回执必须精确匹配设备身份、整个镜像长度和摘要。Root 助手会另报告自己的 RECHECK/WRITING 等阶段，因此日志不只是上述单线性示意。

### RootWriter 与 VerifiedCopy

[RootWriter](<../app/src/main/java/io/yu/flash/root/RootWriter.java>)是安装 APK 内的 Java 入口，经 Root `app_process` 启动；没有任意脚本或任意二进制写入入口。

- 源普通文件与目标设备均固定打开，带 `O_CLOEXEC | O_NOFOLLOW`；目标使用 **`O_RDWR | O_EXCL`**，同一 FD 贯穿原目标哈希、写入、`fsync` 和全量读回，不在阶段之间按路径重开。
- 以 `fstat` 的块设备类型与 `st_rdev` 核对请求设备号，并对照路径、别名、sysfs `/dev` 及设备图；比较 sysfs 容量和已打开 FD 的 seek-end 容量。
- 要求真实物理分区而非虚拟对象、非只读/非 dm；检查分区及父设备 holders/slaves。扫描可读进程的挂载命名空间，持续存在而无法读取的进程导致拒绝，并检查 swap 使用。
- 再核对当前 boot ID/token/目标名、解锁、动态与 Virtual A/B 属性、槽位和允许的分区名。电量、温度与镜像类型由应用/核心写前策略检查，不宣称在整个传输中持续监控。

[VerifiedCopy](<../core/src/main/java/io/yu/flash/core/VerifiedCopy.java>)执行：

1. 检查长度和摘要格式，运行 guard。
2. 对源全量哈希；对固定目标 FD 全量哈希，要求其与**完整原分区备份**的摘要相同。任何不符都在首字节前拒绝。
3. 再运行 guard，回到起点；先由 observer 写入并同步 WRITING 状态，然后才输出首字节。
4. 有界传输整个分区，处理合法短读/短写以完成当前块；零进展、提前 EOF 或异常直接失败，不重新执行整次刷写。
5. 同一目标 FD `fsync`，核对实际写出源字节的哈希，再从同一 FD 全量读回 SHA-256。
6. 引擎返回读回摘要；调用它的 RootWriter 仅在源/目标描述符成功关闭后，发出包含 token、身份、长度与摘要的精确回执。

短写后的剩余字节循环不是失败自动重试。哈希读回不证明闪存介质在断电后必然持久，也不证明兼容、AVB 或启动能力。固定 FD 与独占打开降低路径竞态，并不能防御所有内核、驱动、恶意 Root 或设备固件问题；这些真实语义尚未硬件验证。

## 7. Root 锁、一次性 token 与持久意图

### Root 助手侧

锁和已用 token 位于设备上的 **`/data/yu-flash-tool-writer`**，不是 shell 可写临时目录。助手检查 `/data` 父目录可信性，创建/验证 root 所有、权限精确 `0700` 的目录并拒绝别名路径。

稳定的 `writer.lock` inode 以 `O_NOFOLLOW` 打开，检查普通文件、root 所有和单链接，再用 `FileLock.tryLock()` 拒绝并发助手；不 unlink 此锁。持有锁后用 `O_CREAT | O_EXCL` 创建 `used-<token>` 并同步，失败后也不重用 token。它约束本实现的助手，不是所有 Root 工具的全系统锁；不能手工删锁/token 来解除不确定状态。

### 应用侧 WriteInterlock

[WriteInterlock](<../app/src/main/java/io/yu/flash/root/WriteInterlock.kt>)在应用私有目录中使用 `AtomicFile` 保存写入意图（设备端 `write-uncertain.json`）。记录包含 boot ID、token 和分区名；在启动 `su` 前写入、同步文件、同步父目录并读回核对。持久化失败使当前进程阻止设备操作与清理。

**记录始终保留；成功不删除，也不持久化“已成功可解锁”的授权。** 收到精确回执后只设置当前进程内的 `verifiedToken`。规则为：

| 情况 | 后续资格 |
| --- | --- |
| 主意图记录及 AtomicFile 恢复备份均不存在 | 可继续其他检查，不等于已许可任意写入 |
| 同一进程已验证当前记录 token | 可继续其他检查，持久记录保留 |
| 同一次设备启动的新进程 | 即使原写入曾成功，也因没有内存回执而阻止设备操作、导入与暂存清理 |
| 可读取且有效的记录属于不同 boot ID | 旧记录不再阻挡资格检查；不是恢复，不删除记录，不保证设备正常 |
| 记录损坏/无法可信解析 | 持续拒绝，需要人工支持，不能靠盲目重启或删记录处理 |

不同启动解除旧记录的资格限制，是在内核启动身份改变后不再沿用旧助手状态，不是自动恢复或推荐重启。**不要为了绕过互锁而盲目重启、清数据、卸载或删除记录。**

## 8. 并发、日志与生命周期

[MainViewModel](<../app/src/main/java/io/yu/flash/ui/MainViewModel.kt>)的 Root 授权/发现/刷新、备份条件、写入条件和清理共享 `TransactionGate` 并检查互锁；[操作控制器](<../app/src/main/java/io/yu/flash/OperationController.kt>)的提交、读、导入与写入路径也受门闩/互锁约束。`tryLock` 拒绝重复任务而不排队。设置与日志导出不访问设备，可在互锁时使用；清理虽然只删暂存，也可能影响未退出的助手，故不能绕过。

[任务日志](<../app/src/main/java/io/yu/flash/storage/TaskJournal.kt>)为 SQLite `synchronous=FULL`。重开进程把未结束任务标为 INTERRUPTED，不重新执行。日志满盘、损坏或写终态失败不证明没有写过设备，意图记录独立保守。列表/导出仅最新最多 1000 条事件，不是无限审计档案。

[前台服务](<../app/src/main/java/io/yu/flash/TaskService.kt>)使用 `START_NOT_STICKY`、dataSync 通知、5 小时唤醒锁/预算与系统超时处理。系统杀进程、掉电、提供器不配合仍可能中断或拖延。源码异常记 FAILED、协程取消记 INTERRUPTED，不自动续写、重试、回滚、切槽或重启。

Root 输出重定向到临时普通文件，并有限额与超时。退出时只能尽力强制结束**直接 `su` 进程**；不能保证后代 RootWriter 已停。后台失败或界面结束绝不是安全取消回执，仍可能继续改写或留下部分目标。意图不清除，必须保留备份与证据并人工判断。

## 9. 隐私与待验证事项

[应用清单](<../app/src/main/AndroidManifest.xml>)不声明 INTERNET 权限，关闭系统应用数据备份，不约束外部提供器或下载目录同步。日志导出采用字段白名单，排除路径、指纹、内容和错误原文，仍含分区名、时间及任务 ID。

当前没有硬件读写验收；新增测试源码和普通文件自检不能替代 Root/SELinux、独占块设备打开、跨命名空间检查、AtomicFile 持久化、跨进程互锁、进程/断电故障及外部恢复路径的证据。完整当前待验项目见[测试矩阵](<TEST_MATRIX.md>)。发布应如实附运行报告、限制与第三方许可材料，不能以旧测试、编译或用户基础反馈宣称新写入路径已经安全认证。
