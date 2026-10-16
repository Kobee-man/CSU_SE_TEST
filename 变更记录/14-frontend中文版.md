# 14 · frontend 中文版

日期：2026-10-16
团队仓库：https://github.com/Kobee-man/CSU_SE_TEST.git
提交分支：`frontend`；基线：`c8164f2`。

## 变更概述

在现有 Android 界面上提供默认简体中文版本，增加应用内语言选择，并保留英文、繁体中文和跟随系统选项。

## 背景与动因

用户要求在 `frontend` 分支同步制作中文版。已有中文资源只随系统语言变化，缺少独立选择入口；隐藏的原生菜单也使无实体菜单键的设备难以进入设置。

## 输入

- 用户指令：“在frontend分支上，同步做一个中文版页面出来”。
- `frontend` 的 Java / XML Android 工程及现有中英文资源。

## 具体操作

1. 新增 `NotesApplication`、`AppLanguage`、`LocalizedActivity`：首次初始化默认简体中文，Android 13+ 接入系统应用语言，旧版通过配置上下文兼容。
2. 设置增加语言选择，支持简体中文、English、繁體中文及跟随系统，并保持选择。
3. 补齐编辑提示和颜色无障碍描述，统一简体字、标点与同步时间格式，更新新装欢迎便签。
4. 恢复原生标题和溢出菜单，确保设置、搜索、文件夹等功能可从页面进入。
5. 修复编辑页恢复模型过晚的问题，避免语言变化重建页面时原生菜单读取空模型而崩溃。
6. 在独立测试模拟器中验证；保留原有模拟器及其用户数据。已有便签不进行内容迁移或翻译。

## 产物

- `app/src/main/java/net/micode/notes/NotesApplication.java`
- `app/src/main/java/net/micode/notes/tool/AppLanguage.java`
- `app/src/main/java/net/micode/notes/ui/LocalizedActivity.java` 及相关 Activity 改动。
- `app/src/main/AndroidManifest.xml`、`app/src/main/res/` 中语言、设置、布局与主题资源。
- `README.zh-CN.md`：仓库、分支、环境、启动方法和验收说明。
- `docs/screenshots/zh-CN-{list,editor,settings}.png`。
- 构建输出：`app/build/outputs/apk/debug/app-debug.apk`（忽略的本地构建产物）。

## 验证

- `assembleDebug` 成功，XML 可解析，中文格式占位符与基础资源一致。
- 英文系统新装直接显示中文，包括欢迎便签、菜单和新建按钮。
- 应用内三种语言及跟随系统可切换，重启后保持选择。
- 新建便签保存、重新打开、切换语言后正文保留；日期显示随语言更新。
- 编辑时通过系统切换应用语言，页面重建后正文保留，最终复测无 `AndroidRuntime` 崩溃。
- 运行环境：Windows、JDK 25.0.2、Gradle 9.6.0、AGP 9.4.0、Android API 37 模拟器。
- 受限环境中使用工作区内的 Gradle 缓存和 Android 用户目录构建，未将本机配置或缓存纳入提交。

## 遗留与后续

- API 21–32 的兼容实现已编译，运行行为仍需旧版设备补测。
- 云同步、导出、提醒及桌面小组件未纳入本次回归。
- 代码提交至 `frontend`，远程推送结果以交付消息为准；不合并到 `master`。
