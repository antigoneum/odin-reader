# 0.2.0 控制器焦点与 PDF 验证

日期：2026-10-22；设备：Odin 3 / Android 15，横屏 1920 × 1080。

## 结果

- Debug APK 和 Android 仪器测试 APK 构建成功。
- JVM：38 项通过，0 失败、0 跳过。
- Android 仪器测试：28 项通过，0 失败，运行耗时 16.27 秒。
- Android Lint：0 错误，22 项警告。新增 PDF 位图创建提示为可选 KTX 写法建议。
- 同签名覆盖安装为 0.2.0（versionCode 2）。原书库仍在；reader_settings 和 reading_progress 的 XML 键值与测试前备份一致。
- 真机截图确认“导入书籍”获得可见焦点边框，侧栏背景延伸至底部，状态栏隐藏。

## 覆盖

保留原 TXT / EPUB 导入、章节切换、实际文字分页、触摸翻页、阅读位置恢复、主题和按键映射测试。

新增 8 项设备测试：

1. 触摸后使用十字键到达导入按钮、进入书卡并返回，A 确认真正发起系统文件选择合约（测试拦截系统对话框）。
2. 触摸后使用软件注入的摇杆脉冲完成同样焦点路线。
3. PDF 原文件复制后删除源文件，重新创建仓库仍可渲染，阅读进度保留，删除仅影响该测试书籍。
4. PDF 图形页和仅图像页渲染正确，保持比例、白色页底，并限制位图边长与像素数量。
5. 没有 PDF 扩展名时通过文件头识别。
6. 损坏 PDF 拒绝导入且不留下部分书库条目。
7. 超过 20 MB 的 PDF 拒绝导入。
8. PDF 阅读界面翻页、页列表跳转、缩放控件、设置返回，以及重开和 Activity 重建后的页码恢复。

同时修正旧字号测试在设置覆盖层打开时读取已隐藏语义树的断言；仍保留原生正文与字符锚点检查。

## 复验

```powershell
.\scripts\dev.ps1 assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug --console=plain
.\scripts\device-test.ps1 -SkipBuild
```

设备测试日志在本地忽略目录 `artifacts/device-validation/instrumentation-latest.txt`，本轮备份和日志在 `artifacts/controller-pdf-2026-10-22/`。

## 边界

控制器事件由软件注入，不替代人工握持设备验证实际轴、键帽映射和操作手感。PDF 以原始版面显示，支持扫描件，不做文字重排；不支持密码输入、搜索或批注。单文件最多 20 MB、5000 页，每页渲染限制最大边 4096、约 4M 像素。数据测试使用独立缓存目录和偏好文件；UI 测试只创建并清理自己的 PDF 测试书，原有书籍不删除。
