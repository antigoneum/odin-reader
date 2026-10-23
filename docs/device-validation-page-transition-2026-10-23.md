# 0.10.1 翻页动画首帧验证

日期：2026-10-23；设备：Odin 3 / Android 15，横屏 1920 × 1080。

## 问题与修复

旧实现先按新页面组合界面，随后才在 `LaunchedEffect` 中建立过渡并将共享动画进度从 1 重置到 0。这使首次提交可能只有静态新页，之后才重新出现旧页并开始动画。

新实现于组合阶段同时准备目标页、旧页和独立的初始进度 0；动画协程只推进已经准备好的过渡。异步章节或 PDF 页面尚未就绪时保留旧页，且不会提前消耗翻页序号。原生 TextView 绑定不可变的页面、排版参数与显示角色，避免旧视图先改成新文字。动画结束后清空过渡引用，释放旧页及上一组 PDF 位图。

分页、阅读位置、字体、平整纸面和中缝翻转规则保持原有行为。

## 对照与回归

- 在原 0.10.0 APK 上运行新增首次提交检查，单页横向滑动立即触发预期失败：目标页 11 已提交，旧页 10 不在原生视图树中。对照日志没有 API 缺失或进程崩溃。
- 0.10.1 的同一检查通过。新增 4 项仪器测试涵盖四种模式与四种动画的双向首次提交、连续和反向请求、空帧到就绪帧的异步边界，以及已有原生 TextView 对象的文字不被改写。异步边界用夹具模拟，既有 PDF 真机测试同时保留。
- 完整构建成功；84 项 JVM、93 项真机仪器测试通过，0 失败。完整真机回归耗时 75.714 秒。
- Lint：0 错误、28 项警告、0 项提示；`git diff --check` 通过。

首次提交检查通过父级 `SideEffect` 观察已应用的原生视图树，避免等待动画空闲后遗漏早先错误提交。现有动画测试继续检查中途几何和终态；这些结果不是对每个屏幕显示帧的录像分析或实体按键帧率基准。测试采用独立夹具，不翻阅用户导入的书籍。

## 安装和数据

已覆盖安装 0.10.1（versionCode 11）。APK 大小 80,753,473 bytes，SHA-256：`2ea611d24eeeaeac6c2b83d1240034313a5bf18f2b94df87e71587fdfeaff6ee`，设备安装文件与本地构建一致。

原 `reader_settings` 与 `reading_progress` 键值语义一致；SQLite 的 `reading_records`、`reading_sessions`、`reading_meta` 全部记录一致，完整性检查为 `ok`。6 本导入书籍、5 张 EPUB 封面及内置示例保留；原书籍文件、正文、元数据及封面哈希不变。

本地构建、备份、对照和回归日志位于 Git 忽略目录 `artifacts/page-flash-2026-10-23/`：`instrumentation-baseline-first-frame.txt`、`instrumentation-first-frame-verified.txt`、`instrumentation-final-verified.txt` 和 `installation-verification.json`。

```powershell
.\scripts\dev.ps1 assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
.\scripts\device-test.ps1 -Serial 1242a63f -SkipBuild
```
