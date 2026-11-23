# Agent 项目说明

在本项目开展工作前，优先阅读本文件，并在后续操作中遵循这些约定。本文件适用于整个项目；子目录存在额外说明时，同时遵循其适用范围内的约定。

## 改动范围

- 本分支主要用于新增用户需要的功能、适配新版本和修复 Bug。源码、构建脚本及 CI 的修改应直接服务于当前任务，保持必要且最小的范围。
- 不顺带统一格式、重构无关代码、清理历史配置、更新无关步骤名称或进行其他规范化工作。旧命名、旧注释或历史默认值未影响当前任务时，保留原状。
- 仅需完善 Agent 操作说明的问题，优先在本文件解决，不为使仓库与说明形式一致而改动原项目。

## JDK、JBR 与 IDEA SDK

- 使用通过 JetBrains Toolbox 安装的 IDE，或 IntelliJ IDEA、Rider 等 JetBrains 产品自带的 JDK、JBR 和 SDK。先检查这些产品的现有安装目录以及项目、Gradle 中的工具链配置。
- JDK/JBR 默认优先使用 Rider 自带的版本，其次使用 IntelliJ IDEA 自带的版本。当前机器对应目录为 `%LOCALAPPDATA%/Programs/Rider/jbr` 和 `%LOCALAPPDATA%/Programs/IntelliJ IDEA/jbr`；安装位置变化时以实际安装目录为准。项目开发以已安装 JetBrains 软件为前提。
- 当前标准方案与官方 CI 一致：Java 21 运行 Gradle 8.14、JDK 25 编译插件、生成 Java 21 字节码。这是两个独立的 Java 环境，不要将编译所需的 JDK 25 同时当作 Gradle 运行环境；当前 Gradle 的内置 Kotlin 无法解析本机 IDE JBR 的 `25.0.4.1` 版本。
- 用户已授权使用 JetBrains JBR 21.0.7-b1038.54 运行 Gradle。此 JBR 及下载归档统一放在项目的 `.tools/` 下，该目录已被 Git 忽略；禁止下载到 `.local` 或其他用户级工具目录。缺失时可从 JetBrains 官方来源下载上述版本到 `.tools/`。
- 编译工具链仍使用 Rider（其次 IDEA）自带的 JDK/JBR 25。IntelliJ Platform SDK 按插件目标平台选择，本项目使用已安装的 IntelliJ IDEA；JBR 优先使用 Rider 不代表将插件 SDK 改为 Rider。
- 除上述已授权的项目内兼容 JBR 外，若缺少满足要求的 JDK、JBR 或 IDE SDK，向用户说明所需产品或版本，由用户自行安装，不要擅自下载或通过 Gradle 自动下载绕过此要求。
- 尽量通过当前进程的环境变量或命令行参数指定本机工具链路径，避免将机器专属绝对路径提交到仓库。

构建前核对 JBR 的 `release` 文件和 Java 可执行文件：Gradle 运行环境应为 21，编译环境应为带 `javac` 的 25。核对本机 IDEA 的实际版本/build 与构建脚本目标一致，不能仅凭安装目录名称认定 SDK 版本。

在项目根目录的独立 PowerShell 进程中按以下示例构建。示例显式指定两个 Java 环境、本机 IDEA SDK，并禁用工具链自动发现和下载，避免依赖用户级 Gradle 配置；安装位置变化时按实际路径调整。JBR 21 应已按上述规则准备好：

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = (Resolve-Path '.tools/jbrsdk-21.0.7-windows-x64-b1038.54').Path
$compilerCandidates = @(
    (Join-Path $env:LOCALAPPDATA 'Programs/Rider/jbr'),
    (Join-Path $env:LOCALAPPDATA 'Programs/IntelliJ IDEA/jbr')
)
$compilerJbr = $compilerCandidates | Where-Object {
    (Test-Path (Join-Path $_ 'bin/javac.exe')) -and
    ((Get-Content (Join-Path $_ 'release')) -match '^JAVA_VERSION=.25[.]')
} | Select-Object -First 1
if (-not $compilerJbr) { throw '需要用户安装带 JDK 25 的 Rider 或 IDEA。' }
$env:JAVA_HOME_25_X64 = (Resolve-Path $compilerJbr).Path
$ideaSdk = (Resolve-Path (Join-Path $env:LOCALAPPDATA 'Programs/IntelliJ IDEA')).Path
# 本地构建不启用 CI 分支；仅清除当前独立进程中的变量。
Remove-Item Env:CI -ErrorAction SilentlyContinue
$buildArgs = @(
    "-Dorg.gradle.java.home=$env:JAVA_HOME",
    "-Porg.gradle.java.installations.paths=$env:JAVA_HOME_25_X64",
    '-Porg.gradle.java.installations.auto-detect=false',
    '-Porg.gradle.java.installations.auto-download=false',
    "-Porg.jetbrains.intellij.platform.localIde=$ideaSdk",
    'buildPlugin',
    '--console=plain'
)
./gradlew.bat @buildArgs
if ($LASTEXITCODE -ne 0) { throw "插件构建失败，退出码：$LASTEXITCODE" }
```

- 默认允许获取正常构建所需的 Gradle 分发包、Maven 依赖和项目调试器组件；依赖已缓存时可添加 `--offline`。离线缺依赖时可恢复联网构建，但不得绕过上述 JDK/JBR、IDE SDK 来源限制。
- 本机用户级 Gradle 配置还包含 `-Djdk.net.unixdomain.tmpdir=C:/jtmp`，用于规避 Windows 临时目录下的 AF_UNIX 问题。它是机器专属兼容设置，并非官方 CI 要求。若出现相同套接字错误，应检查并使用可写的短路径临时目录，必要时通过进程或命令行配置，同时保留现有 JVM 内存和编码参数；不要为此硬编码修改项目源码。

## 本地与 CI 的版本号

- 本地构建使用 `gradle.properties` 的版本号，并由构建脚本追加 IDEA 版本后缀；当前为 `1.4.26-fix-IDEA2026.3`，以后以实际配置为准。
- 官方标签发布从 `v*` 标签提取 `CI_BUILD_VERSION`；普通分支 CI 使用工作流中的运行编号版本。这些值会覆盖本地默认版本，源码中的旧默认值不代表商店发布版本。
- 本地构建不要为模仿官方 CI 而设置 `CI`。当前脚本只检查变量是否存在，`CI=false` 也会触发 CI 分支，覆盖版本并执行全局 Git 身份设置。若继承了 `CI`，在独立构建进程中清除；若复用会话，则构建后恢复原环境。不要修改用户的持久环境或 Git 身份。

## 功能开发与 Bug 修复后的交付

- 涉及新增功能或修复 Bug 时，完成代码修改和必要验证后，自动构建用于发布、可从磁盘安装的插件 ZIP 包，无需再次询问是否打包。
- 使用项目 Gradle Wrapper 的 `buildPlugin` 任务，按上文显式指定环境，不要省略工具链设置而依赖未知的全局配置。
- 构建成功后，确认 `build/distributions/` 下的插件 ZIP 存在，版本和内容与当前构建输入匹配，并在最终回复中给出文件链接、构建结果及影响使用的警告或未完成验证。增量构建报告 `UP-TO-DATE` 且 ZIP 时间未变属于正常情况，不单凭修改时间判断有效性；明确需要全量重建或有缓存异常证据时才使用 `--rerun-tasks`。
- 构建失败时，继续排查并修复本次改动导致的问题；若缺少合规工具链，则明确告知用户需要自行安装的组件及阻塞原因，不得将旧包当作本次构建结果交付。
- 构建 release 插件包不代表授权发布到 Marketplace、上传产物或创建远程 Release；这些操作需要用户另行要求。
- 仅修改文档或说明文件时，无需构建插件包，除非用户明确要求。

## 命令执行

- Windows 下优先使用 PowerShell，并在执行工具支持时显式指定 shell；注意 PowerShell 的引号、转义和变量展开规则。
- 执行 Python 时优先使用项目已有环境和 `uv run`。不为满足这一偏好擅自安装工具或重建环境。
- 保留用户已有的工作区改动，不为构建执行会重置或清理工作区的任务。
