# Odin Reader

为 AYN Odin 3 打造的 Android 原生风格离线电子书阅读器。Kotlin + Jetpack Compose / Material 3，正文使用 Android 原生 TextView，优先适配掌机横屏与实体控制器。

目前为 0.3.0，采用横向掌机菜单，支持 TXT、EPUB 和原版 PDF 阅读。

Native Android e-book reader for AYN Odin 3, with offline TXT/EPUB/PDF reading and controller navigation.

## 已实现

- 原生书库、内置原创示例书、系统文件选择器导入。
- 沉浸式界面：默认隐藏状态栏，背景延伸至屏幕边缘；保留系统导航手势，边缘滑动可临时显示系统栏。
- 横向“书库 / 设置 / 操作指南”主菜单，L1 / R1 循环切换视图；横向书架用左右方向选择书籍。
- TXT（UTF-8 / UTF-16 BOM / GB18030）和无 DRM EPUB 2/3。
- 未加密 PDF：保留文字、插图和扫描页的原始版面，支持翻页、缩放和阅读位置恢复。
- 按实际字体和视口测量分页，支持中文、段落和混合文字。
- 阅读位置保存为章节与字符偏移，调整字号后保持当前段落。
- 章节目录、14–32 sp 字号、跟随主题 / 日间 / 纸张 / 夜间背景。
- Catppuccin Latte、Frappé、Macchiato、Mocha 四套主题，以及跟随系统。
- 可自定义 A / B / X / Y、肩键、扳机和 START / SELECT 的阅读功能。
- 触摸边缘翻页、左右滑动、点击中间切换控制栏。
- 摇杆、十字键、肩键、模拟扳机及实体确认键。
- 从触摸切换到控制器时恢复键盘焦点，导入按钮、横向菜单和书卡使用与自身形状一致的焦点描边。
- 无联网权限，书籍复制到应用私有目录后可离线阅读。

## 控制器

| 按键 | 书库 / 菜单 | 阅读 |
| --- | --- | --- |
| 左摇杆 / 十字键 | 移动焦点 | 隐藏控制栏时翻页；显示时移动焦点 |
| A | 确认 | 显示控制栏 / 确认 |
| B | 返回 | 关闭面板 / 隐藏控制栏 / 返回书库 |
| L1 / R1 | 循环切换书库、设置、操作指南 | 上一页 / 下一页 |
| L2 / R2 | — | 上一章 / 下一章 |
| X / SELECT | 操作指南 | 切换控制栏 |
| Y | — | 章节目录 |
| START | 设置 | 阅读设置 |

上表为默认映射。自定义映射仅用于隐藏控制栏时的阅读操作；书库、控制栏、目录与设置保留默认导航，A 确认、B 返回，十字键与摇杆继续移动焦点。

输入层包含摇杆死区与迟滞、按住重复、HAT / 十字键去重，以及模拟和数字扳机合并。应用失焦或暂停时停止重复。按键遵循 Android 标准键码；设备系统若切换 ABXY 布局，实际键帽与键码可能不同，仍需在 Odin 3 上确认。

## 设置与主题

在顶部横向菜单点击“设置”，用 L1 / R1 切换，或按 START；阅读时可从控制栏齿轮进入。十字键或摇杆先移动顶部菜单焦点，再按 A 确认；向下进入当前页内容，B 从设置和指南返回书库。设置自动保存，重启应用后仍然生效。

- **主题**：Latte（浅色）、Frappé、Macchiato、Mocha；“跟随系统”在 Latte / Mocha 间切换。界面和原生正文均使用官方 Catppuccin 配色。选择主题时阅读背景自动跟随，也可另外选择日间、纸张或夜间背景。
- **按键功能**：选择一个实体按钮，再选择上一页、下一页、上一章、下一章、确认、返回、控制栏、目录或设置。允许多个按钮执行同一功能；“恢复默认按键”仅重置按键映射。
- 即使重新分配了确认或返回键，菜单仍使用默认 A / B。阅读时也可通过触摸打开控制栏和设置。

Catppuccin 配色采用 MIT 许可，来源及许可见 [第三方说明](THIRD_PARTY_NOTICES.md)。

## PDF 阅读

使用“导入书籍”选择 PDF；文件会复制到应用私有书库，之后无需访问原文件即可离线阅读。每个文件最多 20 MB、5000 页。

- L1 / R1、十字键或摇杆翻页；Y 打开页码列表，L2 / R2 快速前后跳页。自定义阅读按键同样生效。
- 点击两侧或横向滑动翻页，点击中央或按 X 显示控制栏；使用缩放按钮或双指缩放，放大后拖动查看。
- PDF 保留原色与固定版面，界面使用所选主题；字号和纸张设置用于 TXT / EPUB，PDF 使用自己的缩放控件。
- 阅读位置按原 PDF 页码保存，重新打开和重建界面后继续阅读。暂不支持密码输入、文字重排、搜索或批注。

## 获取源码

```powershell
git clone https://github.com/antigoneum/odin-reader.git E:\odin_reader
cd E:\odin_reader
```

## 开发与构建

建议将仓库克隆到空间充足的磁盘，例如 `E:\odin_reader`。SDK、JDK、Gradle 缓存与构建产物均可放在项目所在盘。

依赖固定为 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21、Compose BOM 2025.12.00。最低 Android 9（API 28），compile / target SDK 36。

克隆后的仓库不包含 JDK、Android SDK、缓存或 APK。请先安装 JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0，并设置 `JAVA_HOME`、`ANDROID_HOME`；也可将工具放入被 Git 忽略的 `.tools/jdk-*` 和 `.tools/android-sdk`。Windows 构建（以下路径仅为示例）：

```powershell
cd E:\odin_reader
.\scripts\dev.ps1 assembleDebug testDebugUnitTest lintDebug --console=plain
```

`scripts/dev.ps1` 自动寻找本地工具，将 Gradle 用户缓存、Android 用户目录和临时目录设到项目内。首次构建需要下载 Maven 依赖。此脚本不会自动安装 SDK 或接受许可。

新电脑也可以使用 Android Studio，安装 JDK 17、Android SDK Platform 36 与 Build Tools 35.0.0，配置 `local.properties` 的 `sdk.dir` 后构建。若要节约 C 盘空间，将 Android Studio 的 Gradle user home 设为 `E:\odin_reader\.gradle-user-home`，SDK 也选 E 盘。直接调用 Gradle Wrapper 时，默认使用项目内的 `.gradle-user-home`（可由环境变量覆盖）。

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。已开启 CI 的 Debug 构建、JVM 单元测试和 Android Lint。签名与发布配置后续按实际分发渠道配置。

## 代码结构

```text
app/src/main/java/io/github/odinreader/
├── MainActivity.kt      # 生命周期、控制器事件入口
├── input/              # Android事件 → 语义动作、去重与重复逻辑
├── data/               # 书库、TXT/EPUB解析、进度、本地原创示例
├── reader/             # 实际字形测量分页、字符偏移定位
└── ui/                 # 书库、阅读、目录、设置、主题与ViewModel
```

文件解析与分页在工作线程执行；UI 与控制器共享语义动作。书库采用应用私有文件，轻量阅读位置与设置使用 SharedPreferences。新增格式时扩展数据层，新增硬件映射时扩展输入层。

## 当前边界

EPUB 先提取文字并统一排版，不保留原 CSS、插图、表格或固定版式；不支持 DRM、加密 PDF、MOBI、在线书源、同步和书签管理。为限制解析内存，导入文件最多 20 MB，EPUB 解压总量最多 32 MB、单文本条目最多 2 MB、正文总量最多 400 万字符；PDF 按页渲染，每张位图限制在约 4M 像素以内。开始导入后暂不提供中途取消；云端文件提供者的读取速度由其自身决定。

JVM 测试覆盖输入判定、文本解析、路径安全、分页边界、按键映射切换与主题对比度；Android 仪器测试覆盖实际字形测量、界面焦点、触摸、横向菜单、设置、PDF 与恢复。0.3.0 已在 Odin 3 上通过 31 项仪器测试；其中控制器事件由软件注入，实体输入手感、更换固件与 ABXY 配置仍需人工核对。

设计依据：[Android 控制器输入](https://developer.android.com/games/sdk/game-controller/controller-input)、[Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)、[AGP 8.13 兼容性](https://developer.android.com/build/releases/agp-8-13-0-release-notes)。

## 真机验证

2026-10-22 的基线版本已在 Odin 3 / Android 15 上完成 18 项仪器测试，24 项 JVM 测试通过，Lint 无错误。用户同时完成了实体摇杆、十字键、肩键与确认/返回操作，反馈正常。该轮验证修复了暗色文字对比度和字号往返时阅读位置倒退的问题。

2026-10-22 的 0.2.0 已在 Odin 3 / Android 15 上通过 28 项仪器测试、38 项 JVM 测试，Lint 无错误（22 项警告）。本轮修复触摸模式阻止控制器焦点、阅读映射更新遗漏的问题，并加入原版 PDF 支持。覆盖安装使用原调试密钥，原书库、设置与阅读进度经前后对比保持一致。测试覆盖与边界见 [0.2.0 验证记录](docs/device-validation-controller-pdf-2026-10-22.md)。

同日 0.3.0 在该设备上通过 31 项仪器测试、38 项 JVM 测试，Lint 无错误（22 项警告）。横向菜单、肩键切页、B 返回与页面重建恢复均通过，原阅读和 PDF 测试保持通过；已检查真机书架、设置和指南截图中的圆角描边。见 [0.3.0 验证记录](docs/device-validation-handheld-menu-2026-10-22.md)。

```powershell
.\scripts\device-test.ps1
```

完整环境、覆盖范围与限制见 [真机验证记录](docs/device-validation-2026-10-22.md)。复验脚本将构建、安装并运行测试；已有对应 APK 时可加 `-SkipBuild`。

## 许可证

项目原创代码和示例文字采用 [MIT License](LICENSE)。Gradle Wrapper 等第三方组件保留原许可证，见 [第三方说明](THIRD_PARTY_NOTICES.md)。本项目与 AYN 无隶属或背书关系。
