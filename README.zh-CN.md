# 便签中文版（frontend）

2026-10-23 问题修复：保存改为事务批处理并保留失败草稿，提醒通过通知展示，导出使用系统文档选择器；旧 Google Tasks 同步已明确停用。设备回归入口为 `app/src/androidTest/java/net/micode/notes/ui/RepairInstrumentation.java`。本轮修复为工作区改动，尚未创建提交。SmartTodo 是独立工程，主便签没有 AI 集成入口。

团队仓库：https://github.com/Kobee-man/CSU_SE_TEST.git
开发分支：[`frontend`](https://github.com/Kobee-man/CSU_SE_TEST/tree/frontend)

这是 Java / XML 原生 Android 应用。中文版沿用当前分支的界面与笔记数据结构。

## 使用方式

- 首次使用语言功能时，默认简体中文；已由 Android 保存的应用语言会保留。
- 点击右上角菜单 → **设置 → 界面语言 / Language**，可选择简体中文、English、繁體中文或跟随系统。
- 跟随系统后，应用重启仍保留这一选择；系统为其他不支持的语言时，界面回退到英文。
- Android 13 及以上还支持在系统的应用语言设置中选择语言。
- 新装时自动创建中文使用说明便签。已有便签属于用户内容，切换语言不会翻译、替换或删除它们。

## 环境与启动

本次验证环境：Windows、JDK 25.0.2、Gradle 9.6.0、Android Gradle Plugin 9.4.0、Android SDK 37、Build Tools 36.0.0。Java 源码目标为 17，最低支持 Android 5.0（API 21）。

```powershell
git clone --branch frontend https://github.com/Kobee-man/CSU_SE_TEST.git
cd CSU_SE_TEST
```

用 Android Studio 打开仓库根目录，安装项目所需 SDK，并将 Gradle JDK 指向 JDK 25。首次同步需要联网下载 Gradle 与构建依赖。

在仓库根目录创建 `local.properties`，路径替换为自己的 Android SDK 位置，不要提交该文件：

```properties
sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk
```

命令行构建、安装和启动（将 SDK 的 `platform-tools` 加入 PATH）：

```powershell
.\gradlew.bat assembleDebug
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n net.micode.notes/.ui.NotesListActivity
```

也可在 Android Studio 中启动模拟器，选择 `app`，点击 Run。连接多台设备时，在 adb 命令中增加 `-s 设备序列号`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## Git 提交与合入检查

首次克隆后运行 `python scripts/install_hooks.py` 启用 `pre-commit` 和 `pre-push`。提交前对暂存快照运行 Android Lint；推送前获取最新集成分支，检查合并冲突，并对待推送代码及模拟合并结果运行 Lint、JVM 单元测试和 Debug/Release 构建。安装要求、目标分支配置与报告位置见 [Git hooks 使用说明](docs/git-hooks.md)。

## 验证与截图

2026-10-16 在独立 Android API 37 模拟器中验证：

| 场景 | 结果 |
|---|---|
| 英文系统中新装后首次启动 | 中文标题、欢迎便签、新建按钮与菜单 |
| 设置中选择简体中文、English、繁體中文 | 页面与选择摘要更新 |
| 跟随英文系统、强制停止后再启动 | 保持跟随系统，显示英文 |
| 中文选择后重新启动 | 保持中文 |
| 创建、保存、重新打开便签，往返切换语言 | 正文保留，日期跟随界面语言 |
| 编辑时从系统更改应用语言 | 页面重建正常，笔记正文保留，无崩溃日志 |
| XML 解析与翻译格式占位符检查 | 通过 |
| `assembleDebug` | 通过 |

界面预览：[中文列表](docs/screenshots/zh-CN-list.png)、[中文编辑页](docs/screenshots/zh-CN-editor.png)、[中文设置](docs/screenshots/zh-CN-settings.png)。

API 21–32 使用兼容语言上下文与页面重建方案，本次没有相应系统镜像，尚未完成运行验证。云端同步、导出、桌面小组件和提醒不在本次验收范围。

## 最新分析与修复记录

2026-10-25 归档 2026-10-23 的分析报告和中文版便签修复代码。报告中“尚未提交”的描述保留其编写时状态。分析报告描述修复前的源码及 APK，当前修复结果以验收记录为准。

- [REA 完整分析报告](交付物/REA分析/完整分析报告.md)、[源码审查](交付物/REA分析/源码审查.md)、[补充项目与工程化审查](交付物/REA分析/补充项目与工程化审查.md)
- [证据清单](交付物/REA分析/证据清单.md)、[方法反编译摘录](交付物/REA分析/方法反编译摘录.md)
- [24 项问题清单](交付物/项目问题清单.md)、[修复与验收记录](交付物/问题修复与验收记录.md)
- 变更记录：[17-REA 项目分析](变更记录/17-REA项目分析.md)、[18-问题清单](变更记录/18-项目问题清单.md)、[19-修复与回归](变更记录/19-问题清单修复与回归.md)

本次上传包含主便签工程、Git 检查工具、分析文档和已脱敏的 REA 静态证据。报告引用的独立 SmartTodo 工程、浏览器预览工具、APK、源码 ZIP 和运行验证日志属于原本地工作区交付物；本仓库未纳入这些交付物，文中对应本地路径及链接须在完整工作区查看。
