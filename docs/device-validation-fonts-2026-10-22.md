# 0.5.0 内置阅读字体验证

日期：2026-10-22；设备：Odin 3 / Android 15，横屏 1920 × 1080。

## 功能

- 设置与阅读设置均可打开“正文字体”，支持触摸、方向键 / 摇杆、A 确认和 B 返回；退出字体子页后焦点返回入口。
- 保留系统宋体和黑体，并内置 JetBrainsMono Nerd Font、Literata、霞鹜文楷、Noto Serif CJK SC 和 Noto Sans CJK SC 的原版 Regular 字库。
- 显示当前字体的原生中英混排预览，JetBrains 额外显示 Nerd 图标。字体在 IO 线程首次加载并缓存。
- 选择保存在 `reader_settings.reader_font`，使用稳定 ID；缺失或未知 ID 回到系统宋体，保持旧版默认样式。
- 字体切换使分页缓存失效；StaticLayout 与正文 TextView 使用同一个 Typeface 和排版参数，按原字符偏移恢复位置。切换字体不重置累计时长，重新排版也不计入阅读速度样本。
- PDF 使用原文件的字体与版面，设置中隐藏正文字体入口。
- 五个字体文件原样内置，共 69,800,944 bytes，离线可用。固定来源、版本、哈希和完整授权见 [字体资产说明](font-assets.md)。

## 结果

- Debug 应用与仪器测试 APK 构建成功。
- JVM：52 项通过，0 失败，新增字体重新排版不污染阅读速度的回归。
- Android 仪器测试：46 项通过，0 失败，耗时 31.765 秒。
- Lint：0 错误、22 项警告；新增一项为 SharedPreferences 建议使用 KTX 的风格提示，与现有设置写法一致。
- 覆盖安装 0.5.0（versionCode 5），APK 为 81,036,580 bytes，安装文件与本地构建的 SHA-256 一致：`0eb9cce8d3de4177d443a5511dbc9c043a6fc63d1cc8172088253ca7c124a3ca`。
- 校验 APK 内五个字体的字节数和 SHA-256，均与上游清单一致；六份字体与图标授权完整打入 APK。
- 原阅读记录表、会话表、迁移标记与升级前完全一致，`PRAGMA integrity_check` 返回 `ok`。原主题、字号、按键设置和旧版进度文件键值保持一致，手动预览后回到系统宋体。

新增设备测试覆盖所有七种字体 × 三档字号（14 / 20 / 32 sp）的中文、繁体、英文、标点与 emoji 排版；检查每页文本连续、无丢失、无裁切。验证 JetBrains Nerd 私用区图标、跨字体字符定位、手柄选字、原生预览、持久化到新 ViewModel、实际正文 Typeface、字体切换往返和 Activity 重建后的阅读位置。

还使用 ADB 注入方向与确认事件，实际滚动到 JetBrains 和霞鹜文楷，检查预览、Nerd 图标和焦点圆角。字体菜单与设置截图、构建和测试日志、安装核对报告保存在本地忽略目录 `artifacts/fonts-2026-10-22/`。复核完成后保留系统宋体作为当前选择。

## 复验

```powershell
.\scripts\dev.ps1 assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
.\scripts\device-test.ps1 -Serial 1242a63f -SkipBuild
```

自动化与本轮手工导航使用软件注入事件；实体按键、摇杆手感仍由实际使用确认。英文专用字体的中文与缺字由 Android 系统字体回退。
