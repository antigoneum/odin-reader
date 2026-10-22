# 0.6.0 中英文独立字体验证

日期：2026-10-22；设备：Odin 3 / Android 15，横屏 1920 × 1080。

## 行为

- 字体设置中的“中文字体 / 英文字体”分别控制两个槽位，同一预览显示组合效果；每项自动保存，改变一个不会覆盖另一个。
- 中文提供系统宋体、系统黑体、Noto 宋体、Noto 黑体与霞鹜文楷；英文可使用七款字体中任意一款的拉丁字符，包含 JetBrains Mono Nerd。
- 汉字、其他 CJK 文字、中文标点与全角符号走中文槽；英文、数字、ASCII 标点及 Nerd 私用区图标走英文槽。共用引号、间隔号等按附近文字判断，组合附加符、变体符及 emoji 连接序列保持字体分段稳定。
- 使用原生 TypefaceSpan，对章文本的每个测量块和显示页应用相同分段；不改变字符或 UTF-16 偏移，不增加空格。字体选择参与分页缓存键，原字符位置和累计阅读时长保留。
- 两个选择分别保存到 `reader_font_chinese`、`reader_font_english`。旧 `reader_font` 作为各槽缺省值，中文字体沿用到两槽；英文专用字体沿用英文槽及原系统中文回退。缺失或非法的新值独立回退，不覆盖另一项。
- TXT / EPUB 生效，PDF 保持原字体和固定版面。

## 验证结果

- Debug 应用、测试 APK 构建成功。
- JVM：65 项通过，0 失败。新增 9 项字体分段测试和 4 项偏好兼容测试。
- Android 仪器测试：49 项通过，0 失败，耗时 35.888 秒。
- Lint：0 错误、23 项警告。
- 已覆盖安装 0.6.0（versionCode 6），APK 为 81,109,932 bytes，SHA-256：`18172fc5bf7f94fd8ee7ff7fb4801b1dc3c7b187bc06d673cd20da9ab4f92c0a`；已安装文件与本地构建一致。
- 五个字体及完整授权仍按原字节打包，哈希与固定上游清单一致。
- 安装和测试前后的阅读记录、会话、迁移标记均相同，数据库完整性检查为 `ok`；原设置与旧进度文件键值相同，只新增请求中的两个字体槽位。
- 真机最终选择：中文保留霞鹜文楷，英文设为 JetBrains Mono Nerd。已检查混排预览、数字和 Nerd 图标，并留在字体选择界面供体验。

保留全部原单字体测试，新增六组“中文霞鹜文楷 / Noto 宋体 / Noto 黑体 × 英文 JetBrains / Literata”在 14 / 20 / 32 sp 的实际 TextView 测量，检查文本连续、无裁切、字符定位及跨 16K 测量块的样式一致。UI 覆盖手柄选择、独立保存、新 ViewModel 与 Activity 重建、中文切换时英文保持不变，以及字体往返后恢复同一阅读点。

首轮真机回归发现“霞鹜文楷 + Literata / 14 sp”底部裁切。分页 StaticLayout 现明确采用与正文 TextView 相同的语言环境最小字体度量，保留大字形回退行高，复测全部组合通过。相关原生 API：[TypefaceSpan](https://developer.android.com/reference/android/text/style/TypefaceSpan)、[StaticLayout 最小字体度量](https://developer.android.com/reference/android/text/StaticLayout.Builder#setMinimumFontMetrics(android.graphics.Paint.FontMetrics))、[TextView 最小行高](https://developer.android.com/reference/android/widget/TextView#setLocalePreferredLineHeightForMinimumUsed(boolean))。

本地构建日志、真机日志、升级前备份、最终预览和安装核对报告位于忽略目录 `artifacts/split-fonts-2026-10-22/`。

## 复验

```powershell
.\scripts\dev.ps1 assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
.\scripts\device-test.ps1 -Serial 1242a63f -SkipBuild
```

本轮控制器操作由软件注入；实体按键与摇杆手感仍以人工使用为准。
