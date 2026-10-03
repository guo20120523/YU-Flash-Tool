# 历史验证记录

[返回首页](<../README.md>) · [测试矩阵](<TEST_MATRIX.md>) · [构建说明](<BUILD.md>)

> 本文件保留早期工程建立、环境排障和指定提交的验证证据，不作为最新版本状态页，后续发布不在此重复维护。下文“当前”“本次”等用语均指各段记录时的状态。各版本的新变化、验证结果及已知问题统一查看对应 [Releases 页面](https://github.com/guo20120523/YU-Flash-Tool/releases)。

## 历史范围与结论

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

## 后续最低验证

1. 持续保留每个发布提交对应的核心测试、Lint、APK 和源码证据；上述构建已经完成。
2. 在模拟器执行当前 4 项 UI 测试；再补足字体、折叠、状态保持、服务与故障注入测试。
3. 对 Root、真实读取、SAF 提供器和备份提交协议做受控设备验证；**保持真实写入关闭**。
4. 补齐第三方许可全文与实际解析依赖清单。不要把此记录宣传为“完整工程已全部验收”。
