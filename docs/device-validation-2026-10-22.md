# Odin 3 真机验证记录 · 2026-10-22

已在实际 Odin 3 上安装并验证 0.1.0 Debug 版。本次修复后的结果：18 项 Android 仪器测试、24 项 JVM 测试通过，Android Lint 无错误；实体操作由用户完成并反馈正常，已保存硬件事件记录。

## 环境与范围

| 项目 | 实测值 |
| --- | --- |
| 设备 | AYN Odin3 |
| 系统 | Android 15 / API 35 |
| 阅读方向 | 横屏，1920 × 1080 |
| 屏幕密度 | 369 dpi |
| 输入设备 | 系统识别为 Xbox Wireless Controller 的内置控制器 |
| 应用 | io.github.odinreader / 0.1.0 Debug |
| 时间基准 | 2026-10-22，Asia/Shanghai |
| 源码与构建位置 | E:\odin_reader |

设备输入能力包括 X/Y、Z/RZ、HAT_X/HAT_Y、LTRIGGER/RTRIGGER 与 BRAKE/GAS。当前阅读导航使用左摇杆 X/Y，右摇杆不承担阅读动作。

## 自动验证

最终设备运行输出为 `OK (18 tests)`，用时约 7.6 秒。软件注入按键与实际实体操作分开记录。

| 测试组 | 数量 | 验证内容 |
| --- | ---: | --- |
| PaginatorDeviceTest | 4 | 实际 TextView 与 StaticLayout；14/20/32sp、两种视口、中文/英文/emoji、长段落跨 16K 边界、空行和字形页底裁切 |
| EpubParserDeviceTest | 6 | 实际 Android XML/HTML 解析、含 %/# 的合法路径、远程 CSS/图片引用、DRM 拒绝、路径越界与解压限制 |
| BookRepositoryDeviceTest | 3 | TXT 导入、重建 Repository 后进度保留、删除与内置示例保护 |
| ReaderUiDeviceTest | 5 | A 与十字键焦点、肩键分页/跨章、X/Y/START/B 面板路由、触摸点击/滑动、关书重开与 Activity 重建、字号往返定位 |

Repository 测试使用独立 UUID 目录和偏好设置。界面测试只临时重置示例书与两个阅读设置，并在 Activity 销毁后恢复原值，不清空用户书库。

本地 JVM 测试 24/24 通过。Lint 无错误，现有警告主要为固定版本依赖、横屏偏好、备份规则及 KTX 建议，不影响本轮功能验收。

## 实体操作与独立检查

用户按要求执行两轮操作，均反馈“已完成，均正常”：

- R1/L1 翻页、R2/L2 切章。
- 左摇杆向右推住后重复翻页，松手后停止。
- Y 打开目录、B 返回。
- 十字键选书、A 打开、十字键右翻与左返。

读取 `/dev/input/event8` 获得真实 EV_KEY 与 EV_ABS 记录。肩键为 BTN_TR/BTN_TL；扳机同时报告 BTN_TR2/BTN_TL2 和 GAS/BRAKE 轴；摇杆出现 X/Y 的连续变化和归零；十字键出现 HAT0X/HAT0Y 的按下与归零。实际键帽到 Android 键码由设备当前 ABXY 设置决定，本次按用户当前配置验收。

同步读取应用自己的进度，观察到：

| 实体动作 | 观察到的阅读位置（从 1 开始） |
| --- | --- |
| R1 | 第 1 章第 1 页 → 第 1 章第 2 页，字符偏移 167 |
| L1 | 回第 1 章第 1 页，偏移 0 |
| R2 | 第 2 章第 1 页，偏移 0；数字键和模拟轴未造成双跳章 |
| L2 | 回第 1 章第 1 页，偏移 0 |
| 左摇杆持续向右 | 连续向后翻页并跨章；释放后位置停止变化 |

其他真机检查：

- 冷启动成功，ADB 启动等待约 0.33–0.37 秒；该数值仅为本机这几次启动观察，不是性能基准。
- 通过真实系统文件选择器导入独立 TXT 样本，书库显示 2 章，能打开并切到第二章。
- 系统文件选择器取消后返回书库，已有书籍保持。
- 强制停止后重开示例书，第 2 章第 2 页、字符偏移 114 保持。
- 32sp 与夜间背景已检查截图；实际字形裁切由仪器测试逐页检查。
- 已查询当前应用进程的 AndroidRuntime 错误日志，未发现崩溃记录。

## 本轮修复

1. 暗色书库主标题和品牌继承了默认黑字。根布局改用带明确内容色的 Material Surface，补齐暗色 onPrimary/onSurface/onBackground；真机截图已复核。
2. 字号调整会把阅读锚点改成新页起点。原版 20→22→20sp 曾使偏移 325→213→167，倒退整页。现保留独立字符锚点，只有显式翻页时更新，修复后往返偏移保持 537；界面回归测试也验证页码、正文和偏移均恢复。
3. 跨章和目录选章立即保存目标位置，减少排版完成前退出时的位置丢失。

## 重复执行

```powershell
cd E:\odin_reader
.\scripts\device-test.ps1
```

脚本先构建 APK、测试 APK，运行 JVM 测试与 Lint，再安装并执行设备测试。已有对应 APK 时可加 `-SkipBuild`。多台设备连接时必须指定 `-Serial YOUR_DEVICE_SERIAL`；脚本检查 JUnit 的成功摘要，不会将 ADB 的退出码 0 误当作测试通过。

设备截图、最终仪器测试输出、实体事件和进度记录保存在被 Git 忽略的 `artifacts/device-validation/`。最终结果为 `instrumentation-latest.txt`，其他 earlier/initial 输出保留为调试过程证据，不代表修复版结果。

设备上保留修正版应用，以及专属 `odin-reader-validation` 测试书和 Download/OdinReaderValidation 文本样本，便于继续体验。本轮未进行长时间续航、超长书籍压力、多固件/ABXY 配置矩阵或云文档提供者弱网测试。
