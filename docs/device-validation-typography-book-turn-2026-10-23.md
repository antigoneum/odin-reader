# 0.9.0 实时排版预览与中缝翻页验证

日期：2026-10-23；设备：Odin 3 / Android 15，横屏 1920 × 1080。

## 行为

- 横屏设置左侧滚动调节，右侧固定显示正文预览；字号、行距、字间距、中英文独立字体和纸张背景实时生效。字体选择页使用相同预览与当前参数，返回设置后恢复原入口焦点。PDF 保留原版面，不显示正文排版控件。
- 预览使用原生 TextView，与正文保持相同字体分段、字号、行高、字距和断行配置。样文位于有边界的 ScrollView 内，超出可见高度时可上下滑动；大字号与大间距不会省略剩余样文，也不会绘制到预览框外。预览不打开用户书籍或增加阅读时长。
- 双页、双列的书页翻转模拟一本装订书：向前翻动右页、向后翻动左页；单张纸页绕中缝旋转，正面在前半程合拢，背面在后半程展开，静止页保持原位。正反面在 90 度处切换，避免出现镜像文字；纸面与中缝阴影随翻动变化。
- 书页翻转时长 620 毫秒，横向滑动 360 毫秒、淡入淡出 240 毫秒、移动并放大 420 毫秒。动画只改变显示，不改变正文测量尺寸、字符位置或翻页步长；每种模式继续独立保存动画选择。
- TXT / EPUB 仍按章节独立排版，章末不预取下一章；奇数页的双页右侧为空，预览显示“本章结束”，继续翻页进入下一章。PDF 保留原页码和版面。

## 验证结果

- Debug 应用与测试 APK 构建成功；JVM 测试 82 项通过、0 失败。
- Odin 3 仪器测试 88 项通过、0 失败，耗时 67.294 秒。
- Lint：0 错误、28 项警告、1 项提示。
- 已覆盖安装 0.9.0（versionCode 9）。APK 大小为 80,753,473 bytes，SHA-256：`8e87a6e8a4d947c755edc13baae4f18536b12ae632a2a2db5e15fa8bb77df106`；设备安装文件与本地构建一致。
- 安装与测试前后的 `reader_settings`、`reading_progress` 键值在语义上完全一致；SQLite 的 `reading_records`、`reading_sessions`、`reading_meta` 全部记录一致，完整性检查为 `ok`。
- 私有书库保留 6 本导入书籍，其中 5 本 EPUB 均有封面；另有内置示例书。全部原书籍文件及已有封面哈希一致，本轮未新增封面。

新增测试验证预览在左侧控件滚动时保持位置，实际字体、字号、行距和字距即时更新，字体选择页沿用这些参数并正确恢复焦点；在 32 sp、2.40 倍行距、0.20 em 字距下，完整样文仍由原生布局排版且无省略。翻页测试覆盖双页、双列的前后方向、中缝轴位置、正反面显示和动画期间的连续操作；既有导入、PDF、分页、阅读记录、焦点与模式测试保持通过。

测试采用独立夹具或保存并恢复设置与示例书记录，不翻阅用户导入书籍。控制器事件由软件注入，使用实际设备检查原生布局与截图；该结果不等同于实体按键手感测试或帧率基准测试。

本地证据位于 Git 忽略目录 `artifacts/book-turn-preview-2026-10-23/`：构建日志 `build-final.txt`、完整测试输出 `instrumentation-final-verified.txt`、安装核对报告、排版预览截图 `typography-live.png` / `typography-fonts.png` / `typography-large.png`，以及双页、双列前后翻页的正反面截图 `turn-{dual_page,dual_column}-{forward,backward}-{front,back}.png`。

## 复验

```powershell
.\scripts\dev.ps1 assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
.\scripts\device-test.ps1 -Serial 1242a63f -SkipBuild
```
