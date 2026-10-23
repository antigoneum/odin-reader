# 0.10.0 双列模式简化验证

日期：2026-10-23；设备：Odin 3 / Android 15，横屏 1920 × 1080。

## 行为

- 阅读模式仅保留单页、双列、半页预览和缩放预览。移除双页入口、独立书页描边、额外页边留白及页内页码，PDF 使用相同模式列表。
- 旧 `reading_mode=dual_page` 按双列打开，继续按字符位置恢复正文。有效的双列动画选择优先，否则沿用旧双页动画；旧双页未指定动画时保留书页翻转默认值。加载偏好只读取数据，用户主动切换模式时保存兼容后的选择。
- 双列保留以中缝为轴的单张纸页翻转、正反面切换与缓动，移除页边投影、纸面明暗渐变和中缝阴影，仅保留稳定的细分隔线。共用动画路径也不再绘制这些装饰。
- 字体与排版预览、阅读位置、PDF 缩放、控制器和触摸操作保持原有行为。

## 验证

- Debug 应用和仪器测试 APK 构建成功；84 项 JVM 测试、89 项 Odin 3 仪器测试通过，均为 0 失败。最终真机测试耗时 67.746 秒。
- Lint：0 错误、28 项警告、1 项提示；`git diff --check` 通过。
- 模式测试验证仅有四种选项、旧双页映射、动画继承和优先级、重新加载及界面重建。翻页测试检查双列双向轴线、正反面、快速反向操作和无障碍范围，并用边缘像素 RGB 检查原色未被渐变阴影改变。
- 已查看双列前后半程截图，确认静止页面保持原位、移动页面无边缘投影与纸面渐变。既有正文极值、触摸、PDF、主题、阅读统计和设置恢复测试保持通过。
- 测试使用独立夹具或备份并恢复设置与示例书记录。旧单页测试显式隔离阅读模式及排版参数，避免用户当前的双列模式影响单页页码断言；控制器事件由软件注入，未把此结果作为实体手感或帧率基准。

## 安装与数据

已覆盖安装 0.10.0（versionCode 10）。APK 为 80,737,089 bytes，SHA-256：`94cd7caf96e1a9315581b55f61f8d92495e84da74d387ad70ad48421dd03a9ee`，设备文件与本地构建一致。

升级前后 `reader_settings` 与 `reading_progress` 键值语义完全一致；数据库 `reading_records`、`reading_sessions`、`reading_meta` 全部记录一致，完整性检查为 `ok`。保留 6 本导入书籍及内置示例，5 本 EPUB 封面正常；原书籍元数据、正文、原文件和封面哈希一致。

本地日志、备份、安装核对报告和截图位于 Git 忽略目录 `artifacts/clean-columns-2026-10-23/`。最终日志为 `instrumentation-final-confirmed.txt`，翻页截图为 `turn-dual_column-{forward,backward}-{front,back}.png`。

```powershell
.\scripts\dev.ps1 assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
.\scripts\device-test.ps1 -Serial 1242a63f -SkipBuild
```
