# Odin Reader

为 AYN Odin 3 打造的 Android 原生风格离线电子书阅读器。Kotlin + Jetpack Compose / Material 3，正文使用 Android 原生 TextView，优先适配掌机横屏与实体控制器。

目前为可运行的 0.1.0 初始化版本。

Native Android e-book reader for AYN Odin 3, with offline TXT/EPUB reading and controller navigation.

## 已实现

- 原生书库、内置原创示例书、系统文件选择器导入。
- TXT（UTF-8 / UTF-16 BOM / GB18030）和无 DRM EPUB 2/3。
- 按实际字体和视口测量分页，支持中文、段落和混合文字。
- 阅读位置保存为章节与字符偏移，调整字号后保持当前段落。
- 章节目录、14–32 sp 字号、日间 / 纸张 / 夜间背景。
- 触摸边缘翻页、左右滑动、点击中间切换控制栏。
- 摇杆、十字键、肩键、模拟扳机及实体确认键。
- 无联网权限，书籍复制到应用私有目录后可离线阅读。

## 控制器

| 按键 | 书库 / 菜单 | 阅读 |
| --- | --- | --- |
| 左摇杆 / 十字键 | 移动焦点 | 隐藏控制栏时翻页；显示时移动焦点 |
| A | 确认 | 显示控制栏 / 确认 |
| B | 返回 | 关闭面板 / 隐藏控制栏 / 返回书库 |
| L1 / R1 | — | 上一页 / 下一页 |
| L2 / R2 | — | 上一章 / 下一章 |
| X / SELECT | 操作指南 | 切换控制栏 |
| Y | — | 章节目录 |
| START | 操作指南 | 阅读设置 |

输入层包含摇杆死区与迟滞、按住重复、HAT / 十字键去重，以及模拟和数字扳机合并。应用失焦或暂停时停止重复。按键遵循 Android 标准键码；设备系统若切换 ABXY 布局，实际键帽与键码可能不同，仍需在 Odin 3 上确认。

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

EPUB 先提取文字并统一排版，不保留原 CSS、插图、表格或固定版式；不支持 DRM、PDF、MOBI、在线书源、同步和书签管理。为限制解析内存，导入文件最多 20 MB，EPUB 解压总量最多 32 MB、单文本条目最多 2 MB、正文总量最多 400 万字符。开始导入后暂不提供中途取消；云端文件提供者的读取速度由其自身决定。

JVM 测试覆盖输入判定、文本解析、路径安全和分页边界；Android 仪器测试覆盖实际字形测量、界面焦点、触摸与恢复。已在 Odin 3 上完成本轮验证，详情见下方真机验证记录；更换固件、ABXY 配置或设备后应重新核对实体输入。

设计依据：[Android 控制器输入](https://developer.android.com/games/sdk/game-controller/controller-input)、[Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)、[AGP 8.13 兼容性](https://developer.android.com/build/releases/agp-8-13-0-release-notes)。

## 真机验证

2026-10-22 已在 Odin 3 / Android 15 上完成 18 项仪器测试，24 项 JVM 测试通过，Lint 无错误。用户同时完成了实体摇杆、十字键、肩键与确认/返回操作，反馈正常。验证中修复了暗色文字对比度和字号往返时阅读位置倒退的问题。

```powershell
.\scripts\device-test.ps1
```

完整环境、覆盖范围与限制见 [真机验证记录](docs/device-validation-2026-10-22.md)。复验脚本将构建、安装并运行测试；已有对应 APK 时可加 `-SkipBuild`。

## 许可证

项目原创代码和示例文字采用 [MIT License](LICENSE)。Gradle Wrapper 等第三方组件保留原许可证，见 [第三方说明](THIRD_PARTY_NOTICES.md)。本项目与 AYN 无隶属或背书关系。
