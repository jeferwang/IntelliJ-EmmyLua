# Agent 项目说明

在本项目开展工作前，优先阅读本文件，并在后续操作中遵循这些约定。本文件适用于整个项目；子目录存在额外说明时，同时遵循其适用范围内的约定。

## JDK、JBR 与 IDEA SDK

- 使用通过 JetBrains Toolbox 安装的 IDE，或 IntelliJ IDEA、Rider 等 JetBrains 产品自带的 JDK、JBR 和 SDK。先检查这些产品的现有安装目录以及项目、Gradle 中的工具链配置。
- JDK/JBR 默认优先使用 Rider 自带的版本，其次使用 IntelliJ IDEA 自带的版本。当前机器对应目录为 `%LOCALAPPDATA%/Programs/Rider/jbr` 和 `%LOCALAPPDATA%/Programs/IntelliJ IDEA/jbr`；安装位置变化时以实际安装目录为准。项目开发以已安装 JetBrains 软件为前提。
- Gradle 运行时例外：当前 Gradle 8.14 的内置 Kotlin 无法解析现有 IDE JBR 的 `25.0.4.1` 版本，用户已授权使用兼容的 JetBrains JBR 21.0.7-b1038.54 运行 Gradle。此 JBR 及下载归档统一放在项目的 `.tools/` 下，该目录已被 Git 忽略；禁止下载到 `.local` 或其他用户级工具目录。缺失时可从 JetBrains 官方来源下载上述版本到 `.tools/`。
- 编译工具链仍使用 Rider（其次 IDEA）自带的 JDK/JBR 25。IntelliJ Platform SDK 按插件目标平台选择，本项目使用已安装的 IntelliJ IDEA；JBR 优先使用 Rider 不代表将插件 SDK 改为 Rider。
- 除上述已授权的项目内兼容 JBR 外，若缺少满足要求的 JDK、JBR 或 IDE SDK，向用户说明所需产品或版本，由用户自行安装，不要擅自下载或通过 Gradle 自动下载绕过此要求。
- 尽量通过当前进程的环境变量或命令行参数指定本机工具链路径，避免将机器专属绝对路径提交到仓库。

在项目根目录的 PowerShell 中使用兼容 JBR 运行构建（显式覆盖用户级 Gradle 的 JVM 设置）：

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jbrsdk-21.0.7-windows-x64-b1038.54').Path
./gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" buildPlugin --offline --console=plain
```

## 功能开发与 Bug 修复后的交付

- 涉及新增功能或修复 Bug 时，完成代码修改和必要验证后，自动构建用于发布、可从磁盘安装的插件 ZIP 包，无需再次询问是否打包。
- 使用项目 Gradle Wrapper 的 `buildPlugin` 任务，例如在 Windows PowerShell 中执行 `./gradlew.bat buildPlugin --console=plain`。依赖已缓存时可添加 `--offline`。构建前确认工具链符合上一节要求。
- 构建成功后，确认 `build/distributions/` 下的插件 ZIP 已由本次构建生成，并在最终回复中给出文件链接、构建结果及影响使用的警告或未完成验证。
- 构建失败时，继续排查并修复本次改动导致的问题；若缺少合规工具链，则明确告知用户需要自行安装的组件及阻塞原因，不得将旧包当作本次构建结果交付。
- 构建 release 插件包不代表授权发布到 Marketplace、上传产物或创建远程 Release；这些操作需要用户另行要求。
- 仅修改文档或说明文件时，无需构建插件包，除非用户明确要求。

## 命令执行

- Windows 下优先使用 PowerShell，并在执行工具支持时显式指定 shell；注意 PowerShell 的引号、转义和变量展开规则。
- 执行 Python 时优先使用项目已有环境和 `uv run`。不为满足这一偏好擅自安装工具或重建环境。
- 保留用户已有的工作区改动，不为构建执行会重置或清理工作区的任务。
