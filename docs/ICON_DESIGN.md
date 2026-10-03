# 图标设计与来源

[返回首页](<../README.md>) · [第三方声明](<../THIRD_PARTY_NOTICES.md>)

## 规范与资源

依据 [Material 3 Icons](https://m3.material.io/styles/icons/overview) 使用 **Google Material Symbols Rounded**，不是旧版 Material Icons、Emoji 或字形替代图标。所有矢量由 [Google 官方仓库](https://github.com/google/material-design-icons/tree/737e3324305806514d7909874fa1818ae1808232/symbols/android)固定提交取得；逐文件来源与原始/本地 SHA-256 见[资源清单](<material-symbols.json>)。

- 统一 Rounded、weight 400、grade 0；常规资源 optical size 24，品牌闪电 optical size 48。
- 四页导航使用 `home`、`assignment`、`settings`、`info`：未选中 FILL=0，选中 FILL=1。文本标签保留。
- 搜索、刷新、下载/备份、上传/写入、下拉、复制、删除、文档、目录与状态图标共用同一套 Symbols。
- 所有资源是 APK 内置 Android VectorDrawable；不下载字体，不添加运行时网络权限或图标服务依赖。
- 从上游移除 `colorControlNormal` 平台 tint，原始路径不变。Compose 使用 MaterialTheme/LocalContentColor；通知使用系统着色的白色 alpha 矢量。

## 尺寸、主题与语义

[统一组件](<../app/src/main/java/io/yu/flash/ui/Symbols.kt>)默认 24dp，带文字按钮的装饰图标 18dp，关于页品牌图标 48dp。按钮/导航仍由 Material 3 组件处理触控区域，不用图标尺寸代替触控目标尺寸。

浅色、深色、系统主题与动态取色继续沿用原主题；错误/警告使用语义错误色，不只依赖颜色表达状态。带文字标签的图标不重复设置朗读描述；警告图标保留“警告”语义。未添加无标签的独立图标按钮。

## 应用启动图标

以官方 `flash_on` 填充路径作为工具标记，不宣称原创商标或 Google 背书。采用 Android 8+ 108dp 自适应画布，图形放在中央 48dp 区域内，可由启动器裁切成圆形或圆角矩形等形状。

- 普通图标：M3 基线 `primaryContainer` / `onPrimaryContainer` 色对。
- Android 13+：提供 monochrome 层，支持主题图标；是否使用取决于启动器及用户开关。
- 启动图标不会直接读取 Compose 动态主题；不承诺所有启动器、通知栏或 OEM 显示相同。

## 维护与验证

- [刷新脚本](<../scripts/vendor_symbols.py>)是维护者显式联网工具，**不是构建步骤**，只取固定提交下的 23 个 XML。
- [离线验证脚本](<../scripts/verify_symbols.py>)检查 SHA-256、资源映射、导航成对状态、启动图标路径/安全区、许可证及旧图标依赖残留；CI 构建前运行。
- [图标仪器测试](<../app/src/androidTest/java/io/yu/flash/SymbolResourceTest.kt>)用于浅/深色绘制资源、启动图标与许可检查。测试 APK 编译不代表执行，实际状态以 Release 的报告为准。
- 仍需设备/模拟器检查主题启动图标、各启动器裁切、通知单色显示、TalkBack、字体放大与窄屏布局；静态验证不能替代截图或视觉验收。

## 许可

Material Symbols 为 Google 提供的 Apache-2.0 资源；已在资源文件记录修改说明，并在应用“关于”页提供离线[来源及完整许可证](<../app/src/main/assets/Material-Symbols-NOTICE.txt>)。项目原创代码仍为 GPL-3.0-only；这不改变其他依赖的许可。
