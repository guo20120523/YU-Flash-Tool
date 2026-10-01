# 更新记录

[项目首页](<README.md>) · [Releases](https://github.com/guo20120523/YU-Flash-Tool/releases) · [验证记录](<docs/VERIFICATION.md>)

应用语义版本与 CI 发布标签分开：`preview-<运行序号>-<尝试次数>` 唯一定位一次构建；具体提交及附件以该发布页为准。

## 文档整理（不改变 APK）

- 重构项目首页，补充功能/四页面、下载安装、工作原理、配置、兼容范围、FAQ、工程导航与后续计划。
- 新增文档中心与更新记录，修正“没有 APK”“没有反馈仓库”等过时描述。
- 更新 0.1.1 构建证据和用户手表反馈的区分，保留 UI 测试未执行、RPMB 未修复、真实刷写关闭等边界。

## 0.1.1-safety-preview · preview-4-1

[发布页](https://github.com/guo20120523/YU-Flash-Tool/releases/tag/preview-4-1) · [构建](https://github.com/guo20120523/YU-Flash-Tool/actions/runs/36817798625)

提交：`5b3b01e9c44b8301d352188b95beb4f0a1ed1036`。

### 改进

- 主页使用统一纵向懒加载列表，设备信息、授权、筛选、分区与诊断都可以上下滚动，解决上方固定内容挤占短屏分区列表的问题。
- 表格继续保留五列，表头与行共享横向位置；操作触控区不缩小。
- 增加 240×160dp 小视口 UI 回归测试源码。
- 主分支成功构建自动创建独立 GitHub 预发布，包含详细说明、主/测试 APK、匹配源码、报告、许可证和摘要。

### 验证与限制

- 22 项核心测试通过；Android 主 APK 与测试 APK 编译成功；Lint 0 错误、23 警告。
- UI 测试由 3 项增加为 4 项，只编译、未在 CI 执行；新增滚动变更待真实手表验证。
- 9 个发布附件已下载核对长度与 SHA-256。
- RPMB 探测报错尚未修复；真实分区写入保持关闭。

## 0.1.0-safety-preview · 首次成功构建

[构建证据](https://github.com/guo20120523/YU-Flash-Tool/actions/runs/36814142454)，提交 `a3bf9ac6ebf1a7d5fe5d0427e2e95fa13772844f`。

- 建立原生 Kotlin / Compose Material 3 应用与纯 JVM 核心模块。
- 实现四页面、主动 Root 授权、限定 by-name 发现、受限读取备份、SAF 本地镜像检查、持久化任务与设置。
- 提供强制备份/复核/读回的核心事务模拟，硬件写入适配器直接拒绝。
- 核心 22 项测试通过，Android 编译与 Lint 通过（23 警告），3 项 UI 测试仅编译。
- 首次产物存于该次 Actions；当时尚未配置 Releases 自动发布，不能把它描述为已有历史 Release。
