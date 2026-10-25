# Git 提交与推送检查

## 安装

在团队仓库根目录执行（Windows PowerShell、Git Bash、macOS/Linux 均可）：

```sh
python scripts/install_hooks.py
git config --get core.hooksPath
```

需要 Python 3.10+、Git 2.38+，以及 README 中约定的 JDK、Android SDK 和 Gradle 环境。Windows 使用 Git for Windows 自带的 shell。首次检查需要下载依赖；检查失败会返回非零状态并阻止提交或推送。

安装器使用仓库本地配置，不修改全局 Git 配置。绝对 hooks 路径同时覆盖这个克隆已有的 worktree；移动或重新克隆仓库后重新安装。已有其他 hooks 管理器时安装器会提示先整合，避免静默覆盖。

## pre-commit：静态检查

1. `git diff --cached --check` 检查暂存内容的空白错误和新增冲突标记。
2. 导出 Git index 的完整快照，运行 `:app:lintDebug`，检查 Java、资源 XML、Manifest 与 Android API 使用。

检查的是本次将提交的内容，支持部分暂存；不会 stash、切换分支或修改暂存区。未暂存的修复不会掩盖暂存区问题。所有提交均执行 Lint，包括修改构建规则、Lint 配置和删除文件的提交。

`app/lint-baseline.xml` 记录首次接入时的历史问题；新增错误及警告都会阻止提交。不要为通过检查随意刷新 baseline 或关闭规则；修复历史问题后可运行 `./gradlew :app:updateLintBaseline` 并审查基线差异。

## pre-push：合入兼容性检查

默认集成目标为本次推送远端的 `frontend` 分支，与当前团队协作基线一致。基线变更后显式设置：

```sh
git config hooks.integrationBranch master
# fork 协作时可以从独立的 upstream 获取基线：
git config hooks.integrationRemote upstream
```

每次推送都重新 fetch 集成目标；目标缺失、网络失败或合并冲突会阻止推送。脚本读取 Git 传入的所有待推送引用和提交号，支持推送非当前分支、多个分支、指向提交的 tag；仅删除引用时直接通过。

对实际推送的提交及其与目标最新提交的模拟合并树，分别执行以下任务，相同树只检查一次：

```sh
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain
```

`git merge-tree --write-tree` 只生成 Git 对象，不执行 checkout、不创建合并提交、不改动工作区或 index。构建在临时快照中进行；只从本地 `local.properties` 借用 `sdk.dir`。快照在检查后删除，Lint/测试报告保存在忽略的 `build/hook-reports/<阶段>/<树哈希>/`。

当前工程没有 JVM 单元测试，`testDebugUnitTest` 会显示 `NO-SOURCE`，后续添加测试后自动执行。构建验证覆盖 Debug/Release 编译、资源链接和打包；设备运行及 API 21–37 的行为兼容性仍需在模拟器或真机验证。

## 手动验证与排错

```sh
python -m unittest discover -s scripts/tests -v
python scripts/git_checks.py pre-commit
```

手动执行 pre-commit 同样只检查暂存快照；新文件先 `git add`。错误输出保留 Gradle 原始诊断，失败时先查看 `build/hook-reports` 中对应树的报告。修复后重新暂存并重试。

本地 hooks 是开发反馈入口，可被 `--no-verify` 绕过。团队强制合入门禁应在 PR CI 中执行同样的 Gradle 任务，并将检查设为分支保护的 required checks。

卸载本克隆的配置：

```sh
git config --local --unset core.hooksPath
```
