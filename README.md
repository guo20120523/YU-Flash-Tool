# YU-Flash-Tool · 安全预览版

面向 Android 的本地分区发现、受限 Root 备份与镜像检查工程。开发者：昱yu；QQ：3895958954。当前版本配置为 `0.1.0-safety-preview`，API 26 为最低版本，编译及目标 API 为 35；这只是设计/构建配置范围，**不是已验证设备兼容清单**。

> **尚未交付完整真实刷写能力。** 已有实际 Root 块设备读取备份代码、系统文档选择器（SAF）本地普通文件导入代码，但未提供 APK、Android 编译通过记录或真机结果。核心主/测试源码已编译，直接 JUnitCore 运行 **22 项测试全部通过**。本机 Gradle 测试执行器曾因参数文件编码失败，使用本机匹配编码后 **`:core:test` 22 项全部通过**，已有标准 XML 报告；这不是 Android 整体构建通过。另有 3 项未运行 UI 冒烟测试。详见[真实验证记录](<docs/VERIFICATION.md>)。

## 开源许可

本项目原创源码按 **GNU GPL version 3 only（GPL-3.0-only）** 授权，全文见 [LICENSE](<LICENSE>)。Copyright (C) 昱yu。你可以按该许可证复制、修改及再分发；本程序不提供任何担保，包括适销性和特定用途适用性担保。第三方组件及 Gradle Wrapper 保留各自许可证，不因本项目许可而更改。

## 可以做什么，不能做什么

| 能力 | 当前源码状态 | 验证边界 |
| --- | --- | --- |
| 主动申请 Root、工具自检、分区发现 | 有实现；检查常见 by-name 路径，合并重复设备别名 | 未真机验证；Root 不等于 Bootloader 解锁 |
| 读取分区并备份 | 有实际 `su` + Toybox 读取实现；校验容量、SHA-256 和备份元数据 | 仅允许符合读取策略的目标；不保证在线一致性或一定可恢复 |
| 本地镜像导入 | SAF `content://`，要求文件描述符为普通文件；复制到应用私有目录 | 不接受云端管道/流设备；不修改原始文档 |
| 镜像检查 | 内容签名、实际长度、boot 类结构检查、SHA-256 | 不等同于设备、AVB 或启动兼容性认证 |
| 完整写入事务 | 核心层有备份→检查→写入→读回的流程及文件模拟测试源码 | 22 项 JUnit 普通文件模拟通过；不是设备验证 |
| 实际块设备写入 | **关闭** | `NoQualifiedDevices` 为空；`RootDeviceAccess.write()` 直接抛错；界面只提供“写入检查” |
| 恢复、回滚、重启、AVB 绕过 | **未提供** | 不自动重试、续写、回滚、重启，不关闭校验或提供专家绕过 |

硬件写入关闭可见[设备访问实现](<app/src/main/java/io/yu/flash/root/RootDeviceAccess.kt>)、[空适配注册表](<core/src/main/kotlin/io/yu/flash/core/Model.kt>)和[操作控制器](<app/src/main/java/io/yu/flash/OperationController.kt>)。请勿仅删除这些拦截后宣称支持真实刷写。

## 文档导航

- [GitHub 构建指南](<docs/BUILD.md>)：把项目**内容**上传到仓库根目录，保留隐藏目录 `.github`，再运行现有工作流。当前没有仓库运行记录。
- [用户指南](<docs/USER_GUIDE.md>)：Root、筛选、备份、镜像检查、任务记录与隐私。
- [安全设计](<docs/SAFETY_DESIGN.md>)：默认拒绝原则、事务边界、残余风险。
- [测试矩阵](<docs/TEST_MATRIX.md>)：区分已写单元模拟、待执行计划、尚无设备证据。
- [第三方声明](<THIRD_PARTY_NOTICES.md>)：依赖清单与许可证核验待办，**不是已打包全部上游许可证的声明**。

## 关键安全约束

- 用户主动点击授权前不调用 `su`；应用不提供任意 Shell 输入。
- 拒绝备份 `Risk.DATA`（如 `userdata`、`metadata`、`cache`）、已挂载目标、挂载状态未知目标以及映射/持有者目标；未知状态不按“安全”处理。风险未知的目标即便可能符合只读规则，也不能写入。
- 默认备份父目录是 `/sdcard/download`。如设备仅有 `/sdcard/Download`，须在设置中明确切换；不静默回退。备份需要目标容量之外至少 64 MiB 可用空间。
- 独立任务目录避免覆盖已有备份，失败文件按设计保留 `.partial`。最终提交的恢复动作是最佳努力，突然掉电/命令失败后必须检查文件与元数据，不能只看扩展名。
- 备份可能含身份信息、密钥或个人数据，下载目录不是私有空间；防止其他应用访问或云同步。哈希一致只证明所校验字节一致。
- **异常文档提供器在打开文件时可能忽略取消；不能保证阻塞立即结束。强制结束直接 `su` 进程不保证其后代进程全部被终止。** 超时/退出后须重新检查，不要立即重复任务。

## 工程与证据

- [应用模块构建配置](<app/build.gradle.kts>)：Android UI、服务、Root 与存储适配。
- [核心模块构建配置](<core/build.gradle.kts>)：纯 JVM 策略、镜像解析和事务模拟。
- [安全单元测试源码](<core/src/test/kotlin/io/yu/flash/core/SafetyTest.kt>)：22 个方法，直接 JUnitCore 与 Gradle `:core:test` 均已通过；先前编码失败和后续修复另行记录。
- [GitHub Actions 工作流](<.github/workflows/android.yml>)：配置了 SDK 检查、核心测试、Lint、Debug APK 构建及证据上传；**配置存在不代表已运行成功**。

源码仓库：[guo20120523/YU-Flash-Tool](https://github.com/guo20120523/YU-Flash-Tool)。构建产物以该仓库 Actions 对应提交的真实结果为准。正式发布前还需补齐 Android 构建、Lint、设备验证、许可证材料和对应版本的证据记录。
