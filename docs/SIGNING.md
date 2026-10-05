# 持久发布签名与密钥保管

[返回首页](<../README.md>) · [构建说明](<BUILD.md>) · [应用构建配置](<../app/build.gradle.kts>) · [发布工作流](<../.github/workflows/android.yml>)

## 公开签名身份

| 项目 | 固定身份 |
| --- | --- |
| 应用包名 | `io.yu.flash` |
| 首个使用此身份的版本 | `1.0.0`，版本码 `5` |
| 密钥/证书 | RSA-4096，自签名 SHA256withRSA，PKCS12 |
| 密钥别名 | `yu-release` |
| **证书 SHA-256** | `483F4F09834E6C6156B473B51F8A8D4A6B71B36EBE332E1F9362F15532EB1F4D` |

这是**公开证书指纹**，不是 APK 文件 SHA-256。APK 文件摘要随每次构建变化，以对应 Release 的摘要表为准。固定身份用于核对后续 APK 是否由同一发布密钥签名；不证明硬件兼容性、稳定性、无漏洞或写入安全。

密钥已在维护者本机创建并做 PKCS12 解密/证书公钥匹配验证，但签名构建是否成功仍须看真实 CI 的 `apksigner` 验证结果。签名秘密不会放进仓库、源码 ZIP、工作流日志、发布附件或公开证书文件。

## GitHub Actions Secrets

只使用以下四个**仓库 Actions Secrets**。本次新建前检查已有名称，不替换同名既有秘密；配置后用 `gh secret list` 核对名称。GitHub 不允许读回 Secret 明文，因此名称存在不等于签名端到端验证通过。

| Secret 名称 | 用途 |
| --- | --- |
| `YU_RELEASE_KEYSTORE_BASE64` | 加密 PKCS12 文件的 Base64；Base64 本身不是加密 |
| `YU_RELEASE_KEYSTORE_PASSWORD` | PKCS12 解锁密码 |
| `YU_RELEASE_KEY_ALIAS` | 固定别名 `yu-release` |
| `YU_RELEASE_KEY_PASSWORD` | 私钥解锁密码；此 PKCS12 与库密码相同 |

公开指纹固定于[工作流](<../.github/workflows/android.yml>)及本说明，不放在可任意更换的秘密中。更改指纹必须经过显式密钥迁移审核，而不是在构建失败后重新生成一把密钥。

- 仅 `main`/`master` 非 PR 构建解码库到 runner 的临时目录，权限为 `0600`，不在 checkout 中。
- 只有签名构建步骤通过环境变量读取密码；不把秘密放在命令行参数、Gradle `-P`、项目配置或持久环境文件中。
- 签名步骤使用 `--no-daemon --no-configuration-cache`；`always()` 步骤删除临时库。
- PR、独立核心测试、Lint 与 Debug 构建不需要签名秘密；禁止通过 `pull_request_target` 签不受信任代码。
- 构建权限默认只读；独立发布任务仅拥有 `contents: write`，不接收签名秘密。主分支代码仍在秘密信任边界内，维护者应保护分支和工作流修改权限。

## 本地构建接口（仅已有工具链的环境）

[应用构建配置](<../app/build.gradle.kts>)从进程环境读取以下四项：

| 环境变量 | 含义 |
| --- | --- |
| `YU_RELEASE_KEYSTORE` | 本地 PKCS12 的文件路径，不是 Base64 |
| `YU_RELEASE_KEYSTORE_PASSWORD` | 库密码 |
| `YU_RELEASE_KEY_ALIAS` | 私钥别名 |
| `YU_RELEASE_KEY_PASSWORD` | 私钥密码 |

用已有安全密码管理器/受限文件在内存中设置环境，不在终端历史、聊天、日志或脚本常量中粘贴密码。然后执行：

```text
gradle :app:lintRelease :app:assembleRelease --no-daemon --no-configuration-cache --console=plain
```

Release 打包/签名任务在缺少配置或库文件不存在时明确失败，不退回 Debug 签名，也不输出可被误发布的未签名主包。无需密钥的检查命令见[构建说明](<BUILD.md>)。本项目不要求为了设置签名而在磁盘紧张的维护机重装 JDK、Gradle 或 Android SDK。

## 私密备份与恢复

维护者保管一份位于公开项目目录**外部**的 `.yu-private-signing` 目录，不能移动进项目目录或交给递归公开上传器。目录中保存加密 PKCS12、独立密码文件、别名、公开证书、签名元数据与恢复说明。本次 Windows ACL 已收窄为创建者和 SYSTEM；复制到另一磁盘/账户后应重新检查权限。

- 将**整个私密目录**另存到独立、离线且加密的备份介质；本机目录不是异地/离线灾备。密码和库同存只在文件系统权限与备份加密有效时受保护。
- 永久保留原密钥；不要用生成新密钥来修复构建、更新密码或登录问题。私钥遗失可能使已安装用户无法正常升级；GitHub Secret 无法导出恢复原值。
- 恢复到受限目录后，先读取库并比对上述公开指纹，再确认别名与密码；不要仅凭文件名判断身份。
- 设置 Secret 应经标准输入传值。遇同名秘密先停下并审计，不无声覆盖。不要把私钥或密码复制进 Issue、聊天、Release 或日志。
- 若疑似泄露，应停止签名发布并人工评估 Android 密钥迁移与用户通知；本流程不自动轮换、撤销或改签。

公开证书可以分享，私钥/密码不行。忽略规则只是防误提交，不是安全边界；公开 API 上传器仍必须只扫描项目目录并拒绝私密文件。

## 下载后复核与旧预览迁移

先检查对应 Release 的所有文件摘要，再用已有 Android Build Tools 独立核对：

```text
sha256sum --check SHA256SUMS.txt
apksigner verify --verbose --print-certs YU-Flash-Tool.apk
```

输出的唯一 signer 证书 SHA-256 必须等于本页指纹。CI 还核对 `io.yu.flash`、版本 `1.0.1`、版本码 `6` 与不可调试属性，附件保留签名与 APK 元数据输出。

旧 Debug 预览使用不同签名，通常**不能直接覆盖安装**。不要为绕过冲突贸然卸载、清数据或重启，尤其存在写入意图、未解决事务或不确定状态时。先保留日志、备份和独立救援方式，按[安全设计](<SAFETY_DESIGN.md>)处理；这不是提供清除安全状态的捷径。未来同一签名仍须遵守 Android 版本码升级规则，每次真正版本升级要提升版本码。
