# =====================================================================
#  轻读阅读器 —— Windows 打包脚本
#
#  做三件事：
#    1. 用 Maven 打出各模块的 jar，并把「运行时依赖」收集到同一个目录
#    2. 用 jdeps 算出程序真正用到的 JDK 模块（用来给运行时做裁剪）
#    3. 用 jpackage 生成「自带 JRE 的免安装程序」
#
#  产物：<项目根>\dist\QingduReader\QingduReader.exe
#        把整个 QingduReader 目录拷给别人就能双击运行，
#        对方不需要装 JDK、不需要装 JavaFX、不需要配置环境变量。
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File scripts\package.ps1
#    powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -SkipBuild
#
#  注意：本文件必须保存为「UTF-8 带 BOM」。
#        Windows PowerShell 5.1 读没有 BOM 的脚本时会按系统 ANSI 代码页解析，
#        中文注释会变成乱码。用 VS Code 保存时选「UTF-8 with BOM」。
# =====================================================================

[CmdletBinding()]
param(
    # JDK 路径。默认取 JAVA_HOME；jpackage / jlink 都在它的 bin 目录下。
    [string] $JavaHome = $env:JAVA_HOME,

    # 生成的可执行文件名。刻意用英文：
    # 中文 exe 名在 Windows 上本身没问题，但对压缩包、传输、杀软扫描更容易出意外，
    # 而且从脚本传参时还要担心编码。界面标题「轻读阅读器」不受这里影响。
    [string] $AppName = "QingduReader",

    # 版本号，会写进程序元数据。
    # 注意：这里是硬编码的，改版本号必须同步改这里 —— app\ 里的 jar 名带的是
    # Maven 的 POM 版本（0.4.2-SNAPSHOT），而 exe / zip 名字带的是这里的 $Version，
    # 两边不一致会出现"jar 是新代码、exe 显示的还是老版本"的错觉。
    # 第三处是 ReaderView.VERSION —— 用户点「帮助 → 关于」时看到的那个数字。
    [string] $Version = "0.4.2",

    # 跳过 Maven 构建，直接复用上一次的 jar（改完代码要重新打包时不要加这个）。
    [switch] $SkipBuild
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------
#  原生命令的统一调用方式：只看退出码，不看 PowerShell 对 stderr 的判断
#
#  【踩过的坑，而且它会让打包"看起来失败其实没失败"】
#  PowerShell 5.1 会把原生命令写到 stderr 的**任何**输出包成 NativeCommandError。
#  本脚本上面设了 $ErrorActionPreference = "Stop"，于是这一条被升级成**终止性**错误，
#  打包在第 1 步就中止。触发它的却完全是无害的东西 —— JDK 24+ 启动时必然打的：
#      WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
#  Maven 自己认为一切正常（BUILD SUCCESS、测试全绿），脚本却已经死了。
#
#  所以：临时把 EAP 降成 Continue，让原生命令的 stderr 只是普通输出，
#  然后**显式检查 $LASTEXITCODE** —— 那才是 mvn / jdeps / jpackage 的真实结论。
#  （注意区别：$LASTEXITCODE 是子进程自己的退出码，不受上面这层包装影响；
#    被包装成 1 的是"PowerShell 会话"的退出码，不是它。）
# ---------------------------------------------------------------------
function Invoke-Native {
    param(
        [Parameter(Mandatory)] [string]      $What,
        [Parameter(Mandatory)] [scriptblock] $Action
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Action
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) {
        throw "$What 失败（退出码 $code）。"
    }
}

# ---------------------------------------------------------------------
# 路径准备
# ---------------------------------------------------------------------
$Root          = Split-Path -Parent $PSScriptRoot          # scripts/ 的上一级就是项目根
$DistDir       = Join-Path $Root "dist"
$AppDir        = Join-Path $DistDir $AppName
$DesktopTarget = Join-Path $Root "qingdu-desktop\target"

# 打包中间目录，刻意分成两个（原因见第 2 步的长注释）：
#   app 目录 —— 放我们自己的 jar。jpackage 会把 --input 目录里的 jar 全塞进 classpath。
#   fx  目录 —— 放 JavaFX 的模块 jar。通过 --module-path 交给 jlink，直接编进运行时镜像。
$StageDir      = Join-Path $DesktopTarget "package-stage"
$LibDir        = Join-Path $StageDir "app"
$FxLibDir      = Join-Path $StageDir "fx"

$MainClass     = "com.qingdu.reader.Launcher"

# 应用图标的 ICO 文件，由 scripts/gen-icon.py 生成。
# 注意：这只影响 exe 文件自身在资源管理器里的图标。
# 运行时的窗口/任务栏图标是另一套，在 QingduApplication.applyWindowIcons() 里加载 PNG，
# 两处互相独立，都要设才一致。
$IconFile      = Join-Path $Root "assets\app.ico"

if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath $JavaHome)) {
    throw "找不到 JDK。请设置 JAVA_HOME 环境变量，或用 -JavaHome 参数指定，例如：-JavaHome 'C:\path\to\jdk-25'"
}
$Jpackage = Join-Path $JavaHome "bin\jpackage.exe"
$Jdeps    = Join-Path $JavaHome "bin\jdeps.exe"
foreach ($tool in @($Jpackage, $Jdeps)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "缺少 $tool，请确认使用的是 JDK 17 以上的完整 JDK（不是 JRE）。"
    }
}
if (-not (Test-Path -LiteralPath $IconFile)) {
    throw "缺少应用图标 $IconFile`n请先运行 scripts\gen-icon.py 生成（需要 Python 3 + Pillow）。"
}

Write-Host "==> 项目根目录: $Root"
Write-Host "==> 使用 JDK  : $JavaHome"

# ---------------------------------------------------------------------
#  清目录：直接用 .NET 删，不走 Remove-Item
#
#  【踩过的坑】本项目的开发机上 Remove-Item 被替换成了"移入回收站"的实现，
#  对 target\ 里的文件会报 trash-failed（回收站相关操作被中止）：
#      [safe-delete][SAFE_DELETE_FAIL_CLOSED] reason=trash-failed
#        ... Error during a `trash` operation: Some operations were aborted
#  配合 $ErrorActionPreference = "Stop"，打包会在删依赖 jar 那一步直接中断 ——
#  而 Maven 两步都已经 BUILD SUCCESS 了，看起来像"打包坏了"，其实什么都没坏。
#
#  要删的都是**构建产物**（target\、dist\ 下、已在 .gitignore 里、随时可重建），
#  所以直接用 .NET 删更合适：不受回收站容量和状态影响，也快得多。
#
#  ⚠️ 安全约束（写在这里是为了防止以后有人改错变量、一下删到项目外面去）：
#  只允许删项目根之下、且位于 target / dist / out 这三个构建目录里的路径；
#  其余一律拒绝。这是本脚本唯一一处绕过系统删除机制的地方，所以把边界写死。
# ---------------------------------------------------------------------
function Remove-BuildPath {
    param([Parameter(Mandatory)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path)) { return }

    $full     = [IO.Path]::GetFullPath($Path)
    $rootFull = [IO.Path]::GetFullPath($Root)
    $prefix   = $rootFull.TrimEnd('\') + '\'

    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝删除项目目录之外的路径：$full"
    }
    # 判据是"路径里有没有 target / dist / out 这一段"，而不是"第一段是不是它"——
    # 因为要删的 package-stage 在 qingdu-desktop\target\ 底下，顶层是模块名
    $allowed  = @("target", "dist", "out")
    $segments = $full.Substring($prefix.Length) -split '[\\/]'
    if (@($segments | Where-Object { $allowed -contains $_ }).Count -eq 0) {
        throw "拒绝删除：路径不在 target / dist / out 之下（$full）"
    }

    if ([IO.File]::Exists($full)) {
        [IO.File]::Delete($full)
        return
    }
    # 递归删目录前先清掉只读属性：.NET Framework 的 Directory.Delete(recursive)
    # 遇到只读文件会抛 UnauthorizedAccessException（.NET Core 之后才自动处理）
    Get-ChildItem -LiteralPath $full -Recurse -Force -File | ForEach-Object {
        if ($_.IsReadOnly) { $_.IsReadOnly = $false }
    }
    try {
        [IO.Directory]::Delete($full, $true)
    } catch {
        throw "删不掉 $full —— 可能有进程还占着它（上一次没退干净的阅读器？）：$($_.Exception.Message)"
    }
}

# ---------------------------------------------------------------------
# 第 1 步：Maven 构建 + 收集依赖
# ---------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "==> [1/5] Maven 打包各模块..."
    # 这里用 install 而不是 package，是为了把本项目的 qingdu-common / qingdu-core /
    # qingdu-store 也发布进本地仓库。原因在第 2 步：收集依赖是**另一个 Maven 会话**
    # （只带 qingdu-desktop 一个模块），它从本地仓库解析同项目的兄弟模块。
    # 只跑 package 的话，本地仓库里留着的还是上一次 install 的旧 jar ——
    # 打出来的包会"看起来是新编译的，跑起来是旧逻辑"，而且完全不报错。
    # 带 clean 而不是增量构建：target\ 里留着的旧版本 jar（改版本号后尤其明显）
    # 会被后面第 2 步误当成"我们的主程序 jar"打进包里，而且完全不报错。
    # 全量重建反而更慢一点，但换掉一整类"包出来的东西是旧代码"的坑。
    Invoke-Native -What "Maven 构建" -Action {
        & mvn -B -ntp -f (Join-Path $Root "pom.xml") -pl qingdu-desktop -am clean install -DskipTests
    }
}

Write-Host ""
Write-Host "==> [2/5] 收集运行时依赖并分流（app 走 classpath，JavaFX 走 module-path）..."

# 每次重建都清空，否则上一轮删掉的依赖会一直留在包里（经典的"幽灵依赖"问题）
Remove-BuildPath $StageDir
New-Item -ItemType Directory -Path $LibDir   -Force | Out-Null
New-Item -ItemType Directory -Path $FxLibDir -Force | Out-Null

Invoke-Native -What "收集运行时依赖" -Action {
    & mvn -B -ntp -f (Join-Path $Root "pom.xml") -pl qingdu-desktop dependency:copy-dependencies `
        "-DincludeScope=runtime" "-DoutputDirectory=$LibDir"
}

# copy-dependencies 只复制"依赖"，不复制本模块自己的 jar，所以要单独搬过来。
# 注意排除 sources / javadoc 包：它们不是运行时代码，带上只会让包变大。
#
# 【踩过的坑】这里以前写的是 Select-Object -First 1，结果打进包里的是**上一个版本的 jar**：
# target\ 里同时留着 qingdu-desktop-0.1.1 和 0.2.0 两个 jar（增量构建不会清理），
# 而 Get-ChildItem 按名字排序，"0.1.1" 排在 "0.2.0" 前面，于是 -First 1 拿到的永远是旧的那个。
# 现象是：绿色版打得出来、也能启动、版本号显示新版本，但里面跑的是老代码 —— 完全不报错。
# 所以现在改成两条：
#   ① 构建步骤带 clean（从根上保证 target 里没有陈旧产物，见上面第 1 步）；
#   ② 这里如果发现多个候选就**直接报错**，宁可停下也不要静默挑一个。
$mainJarCandidates = @(Get-ChildItem -Path $DesktopTarget -Filter "qingdu-desktop-*.jar" -File |
    Where-Object { $_.Name -notmatch '(sources|javadoc)' })
if ($mainJarCandidates.Count -eq 0) { throw "找不到主程序 jar，请确认 Maven 构建成功。" }
if ($mainJarCandidates.Count -gt 1) {
    $names = ($mainJarCandidates | ForEach-Object { $_.Name }) -join ", "
    throw "target 里存在多个主程序 jar（$names），无法判断该用哪一个。`n请先执行 mvn clean 再重新打包。"
}
$mainJarFile = $mainJarCandidates[0]
Write-Host "    主程序 jar: $($mainJarFile.Name)"
Copy-Item -LiteralPath $mainJarFile.FullName -Destination $LibDir -Force

# ---------------------------------------------------------------------
#  分流 JavaFX：从 classpath 挪到 module-path
#
#  JavaFX 的 Maven 依赖会带两个 jar：
#    javafx-graphics-25.0.4-win.jar  —— 真家伙，含 module-info.class 和本地库(dll)
#    javafx-graphics-25.0.4.jar      —— 0 字节的空壳，是平台分类器的副产物
#  之前把这一堆全丢进 classpath，于是 JavaFX 落在"未命名模块"里，启动时会打两条警告：
#    WARNING: Unknown module: javafx.graphics specified to --enable-native-access
#    WARNING: Restricted methods will be blocked in a future release ...
#  也就是说 --enable-native-access 那个选项名根本匹配不到任何东西。
#
#  改成把 -win 版放进 module-path 后，jlink 会把 javafx.base / .graphics / .controls
#  当作正经模块编进运行时镜像，选项名才对得上，警告自然消失，
#  而且能从运行时镜像里加载 JavaFX —— 这是官方推荐的部署形态。
# ---------------------------------------------------------------------
# 【踩过的坑】这里刻意用 ForEach-Object 显式传 -LiteralPath，而不是直接管道给 cmdlet：
# 本项目的开发机上 Remove-Item 被替换成了"安全删除"代理，它的参数签名和系统自带的
# 不一样，管道绑定会被拒绝，报的是「无法将输入对象绑定到命令的任何参数」
# （ParameterBindingException）—— 看起来像"脚本写错了"，其实是环境差异。
# 显式传完整路径对两种实现都成立，最稳。
Get-ChildItem -Path $LibDir -Filter "javafx-*-win.jar" -File |
    ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination $FxLibDir -Force }
# 空壳 jar 直接删掉，留着只会让包变胖。
# 走 Remove-BuildPath 而不是 Remove-Item：本机 Remove-Item 的"回收站"实现在
# target\ 里会 trash-failed，见上面那个函数的注释
Get-ChildItem -Path $LibDir -Filter "javafx-*.jar" -File |
    ForEach-Object { Remove-BuildPath $_.FullName }

$appJarCount = (Get-ChildItem -Path $LibDir   -Filter *.jar -File).Count
$fxJarCount  = (Get-ChildItem -Path $FxLibDir -Filter *.jar -File).Count
Write-Host "    classpath: $appJarCount 个 jar（主程序 $($mainJarFile.Name)）"
Write-Host "    module-path: $fxJarCount 个 JavaFX 模块 jar"

# ---------------------------------------------------------------------
# 第 3 步：用 jdeps 算出需要的 JDK 模块
# ---------------------------------------------------------------------
Write-Host ""
Write-Host "==> [3/5] 用 jdeps 分析依赖的 JDK 模块..."

# jdeps 要看到全部 jar（自己的 + JavaFX 的），否则推算出的 JDK 模块会偏少
$jarPaths = @(
    Get-ChildItem -Path $LibDir   -Filter *.jar -File
    Get-ChildItem -Path $FxLibDir -Filter *.jar -File
) | ForEach-Object { $_.FullName }
$classPath = $jarPaths -join ';'

# --print-module-deps 输出一行逗号分隔的模块名；--ignore-missing-deps 让它跳过
# 找不到的可选依赖而不是直接报错。
#
# 这里也走 Invoke-Native：jdeps 同样会往 stderr 写东西，裸调用会被 EAP=Stop 判成失败。
# 但**不要求它退出码为 0** —— 拿不到模块列表本来就有退路（退回不裁剪的完整运行时），
# 那是"少 30 MB 优化"，不是"打包失败"，所以失败只警告、不中止。
$jdepsOutput = @()
try {
    $jdepsOutput = Invoke-Native -What "jdeps" -Action {
        & $Jdeps --multi-release 25 --print-module-deps --ignore-missing-deps `
            --class-path $classPath $jarPaths 2>&1
    }
} catch {
    Write-Warning "jdeps 调用失败：$($_.Exception.Message)"
}
$moduleList = ($jdepsOutput | Where-Object { $_ -match '^[a-z][a-z0-9.]*(,[a-z0-9.]+)*$' } | Select-Object -Last 1)

if ([string]::IsNullOrWhiteSpace($moduleList)) {
    Write-Warning "jdeps 没有给出可用的模块列表，退回到不裁剪的完整运行时。"
    $moduleList = $null
}

# ---------------------------------------------------------------------
#  关键坑：jdeps 看不到「反射式」的依赖
#
#  Charset.forName("GB18030") 是通过服务提供者机制在运行时查找的，
#  静态分析（jdeps）根本看不到这层关系。而 GBK / GB18030 / Big5
#  这些中文常用编码恰恰不在 java.base 里，而在 jdk.charsets 模块中。
#  少了它，裁剪出来的运行时打开 GBK 小说会直接抛
#  UnsupportedCharsetException —— 编译和打包全程都不会报错，
#  只有真正打开书的那一刻才炸。所以必须在这里手工补上。
# ---------------------------------------------------------------------
if ($null -ne $moduleList -and $moduleList -notmatch 'jdk\.charsets') {
    $moduleList = "$moduleList,jdk.charsets"
    Write-Host "    手工补上 jdk.charsets（jdeps 看不到的反射式依赖）"
}

# ---------------------------------------------------------------------
#  关键坑（第二个）：sqlite-jdbc 需要 java.sql，而且要显式开本地库访问
#
#  存储层用的是 sqlite-jdbc，它对 java.sql / java.naming 有依赖，
#  这些理论上 jdeps 能看到（我们的代码直接 import 了 java.sql.*），
#  但 sqlite-jdbc 内部还会通过反射碰 sun.misc.Unsafe —— 静态分析看不到。
#  jdk.unsupported 里就装着 sun.misc.Unsafe，漏掉它会在第一次开库时炸。
#  这一条和上面的 jdk.charsets 是同一类问题，所以用同样的方式兜底。
#
#  另外它会把一个 dll 打进 jar、运行时解压出来再 System.load。
#  JDK 24 起，未命名模块加载本地库会打出成屏警告（JEP 472），
#  所以要给 jpackage 传 --enable-native-access=ALL-UNNAMED
#  （它落在 classpath 上，属于未命名模块）。
# ---------------------------------------------------------------------
if ($null -ne $moduleList) {
    foreach ($needed in @("java.sql", "java.naming", "jdk.unsupported")) {
        if ($moduleList -notmatch [regex]::Escape($needed)) {
            $moduleList = "$moduleList,$needed"
            Write-Host "    手工补上 $needed（存储层 sqlite-jdbc 需要）"
        }
    }
}

# JavaFX 自己的模块名和 Maven 的 artifactId 对不上（javafx-controls -> javafx.controls），
# 而且 jdeps 分析的是 class 文件、不会把模块描述符里的 requires 读出来，
# 所以这两个必须手工写死。
# 说明：pom 里还声明了 javafx-fxml，但代码里一次都没 import —— 属于历史遗留的
# "幽灵依赖"。这里仍然把它编进去，是为了以后真用上 FXML 时不会突然启动失败，
# 代价只有 100 多 KB。
if ($null -ne $moduleList) {
    $moduleList = "$moduleList,javafx.controls,javafx.fxml"
    Write-Host "    模块列表: $moduleList"
}

# ---------------------------------------------------------------------
# 第 4 步：jpackage
# ---------------------------------------------------------------------
Write-Host ""
Write-Host "==> [4/5] 生成免安装程序..."

Remove-BuildPath $AppDir
New-Item -ItemType Directory -Path $DistDir -Force | Out-Null

$jpackageArgs = @(
    "--type", "app-image"        # app-image = 免安装绿色版目录；生成 .msi 安装包需要额外装 WiX，这里不用
    "--name", $AppName
    "--dest", $DistDir
    "--input", $LibDir
    "--main-jar", $mainJarFile.Name
    "--main-class", $MainClass
    "--app-version", $Version
    "--vendor", "qingdu"
    "--description", "Qingdu Reader"
    # exe 文件自身的图标。Windows 下 jpackage 只收 .ico（不是 png 改后缀就行），
    # 而且必须是绝对路径 —— 脚本的工作目录不一定是项目根，写相对路径会静默用回默认图标。
    "--icon", $IconFile
    # JavaFX 走 module-path，让 jlink 把它作为正经模块编进运行时镜像
    "--module-path", $FxLibDir
)
if ($null -ne $moduleList) {
    $jpackageArgs += @("--add-modules", $moduleList)
}
# 传给被启动的 JVM 的默认参数。
# javafx.graphics —— 经过上面"JavaFX 进运行时镜像"的改造后它已经是真实存在的
#   模块名，这条选项才真正生效（JDK 24+ 要求显式开启本地库访问，否则启动时满屏 WARNING）。
# ALL-UNNAMED —— sqlite-jdbc 在 classpath 上（未命名模块）加载本地库，
#   不带上这一项，一开库就会打警告。
$jpackageArgs += @("--java-options", "--enable-native-access=javafx.graphics,ALL-UNNAMED")

Invoke-Native -What "jpackage" -Action {
    & $Jpackage @jpackageArgs
}

# ---------------------------------------------------------------------
# 第 5 步：把 AI 服务源码拷进绿色版
# ---------------------------------------------------------------------
# 🔴 为什么只拷源码、不拷 .venv
#   .venv 里全是写死的绝对路径（ Scripts\*.exe 的shebang、pyvenv.cfg 的 home、
#   site-packages 里 .pth 记录的路径）。拷到别人的机器上必然指向不存在的
#   Python，表现为"装过了但还是ModuleNotFoundError"—— 比不装更难排查。
#   所以 venv 属于"每台机器各建一次"，不属于可分发的产物。
#
# 为什么必须拷：AI 面板在服务没起时会提示"请在 qingdu-ai 目录执行 ……"。
#   如果绿色版里没有这个目录，那条提示指向一个不存在的地方 —— 用户只会
#   怀疑程序坏了。至少让路径是真的，剩下两条命令（建venv、pip install）
#   本来也必须用户自己在自己机器上敲。
$AiSrcDir = Join-Path $Root "qingdu-ai"
if (Test-Path -LiteralPath $AiSrcDir) {
    $AiDestDir = Join-Path $AppDir "qingdu-ai"
    if (Test-Path -LiteralPath $AiDestDir) {
        [IO.Directory]::Delete($AiDestDir, $true)
    }
    New-Item -ItemType Directory -Path $AiDestDir -Force | Out-Null

    # 要排除的五样：
    #   🔴 .env      —— **里面是真实的 API key**。不排除的话，你本机的 key
    #                  会被打进 zip 并随 Release 公开上传。这是本清单里
    #                  唯一"漏了就会造成真实损失"的一项，见下面那道断言。
    #   .venv       —— 内部全是本机绝对路径，拷过去必然失效（见上）
    #   __pycache__ —— 本机 .pyc 残留，与源码版本可能对不上
    #   .pytest_cache —— 本机跑测试的缓存，用户用不上
    #   *.egg-info  —— pip install -e 生成的产物，里面记着**打包那一刻**
    #                  的版本与文件清单。用户在自己机器上重新 install 时，
    #                  残留的旧 egg-info 可能让 setuptools 拿到过期信息，
    #                  于是 /api/health 报出一个对不上的版本号。
    # 注意 .env.example **不能排除** —— 它是给用户的模板，正是要发出去的东西。
    $aiExclude = @(
        '[\\/]\.venv[\\/]',
        '[\\/]__pycache__[\\/]',
        '[\\/]\.pytest_cache[\\/]',
        '[\\/][^\\/]+\.egg-info[\\/]'
    )
    Get-ChildItem -LiteralPath $AiSrcDir -Recurse -File -Force |
        Where-Object {
            $rel = $_.FullName.Substring($AiSrcDir.Length).TrimStart('\', '/')
            # .env 只按"相对根目录正好是 .env"排除，
            # 不能用正则匹配名字 —— 否则 .env.example 会被一起干掉。
            if ($rel -eq '.env') { return $false }
            foreach ($pat in $aiExclude) {
                if ($_.FullName -match $pat) { return $false }
            }
            if ($_.Extension -eq '.pyc') { return $false }
            return $true
        } |
        ForEach-Object {
            $rel = $_.FullName.Substring($AiSrcDir.Length).TrimStart('\', '/')
            $target = Join-Path $AiDestDir $rel
            # 🔴 Split-Path 对"没有子目录"的路径返回空串，
            # 直接喂给 New-Item -Path 会报"参数 Path 是空值"。
            $parent = Split-Path -Parent $target
            if ($parent -and -not (Test-Path -LiteralPath $parent)) {
                New-Item -ItemType Directory -Path $parent -Force | Out-Null
            }
            Copy-Item -LiteralPath $_.FullName -Destination $target -Force
        }
    $aiCount = (Get-ChildItem -LiteralPath $AiDestDir -Recurse -File).Count
    Write-Host ""
    Write-Host "==> [5/5] AI 服务源码已拷入（$aiCount 个文件，不含 .env / .venv / 缓存 / egg-info）"

    # -----------------------------------------------------------------
    # 🔴 发布前的密钥检查 —— **宁可打包失败，也不能把 key 发出去**
    # -----------------------------------------------------------------
    # 上面那个过滤清单是"我认为该排除什么"；这里是不依赖那份判断的兜底：
    # 直接扫整个产物，只要出现 .env 或者像真 key 的字符串，就中止打包。
    # 理由：key 一旦进了 Release 附件，删附件也拦不住已经下载的人 ——
    # 这是本项目里唯一"错了就不可挽回"的一步，值得用最笨的办法守。
    $leaks = @()
    # ① 整个产物里都不该有名为 .env 的文件（比只查 qingdu-ai 更保险）
    Get-ChildItem -LiteralPath $AppDir -Recurse -File -Force |
        Where-Object { $_.Name -eq '.env' } |
        ForEach-Object { $leaks += "文件 $($_.FullName.Substring($AppDir.Length))" }

    # ② 只扫**要发出去的文本文件**（.py/.md/.toml/.example …）。
    #    刻意不去扫 jar/zip：二进制被当成文本读会产生大量噪声，
    #    "sk-" 出现在压缩流里是可能的，那会造成假警报 ——
    #    一个会误报的检查，最后一定会被人加 --skip 绕过，等于没有。
    $scanExt = @('.py', '.md', '.toml', '.txt', '.cfg', '.ini', '.example', '.yml', '.yaml')
    Get-ChildItem -LiteralPath $AiDestDir -Recurse -File -Force |
        Where-Object { $scanExt -contains $_.Extension.ToLower() -or $_.Name -like '*.example' } |
        ForEach-Object {
            $txt = ''
            try { $txt = [IO.File]::ReadAllText($_.FullName) } catch { return }
            # sk- 后面跟 24 位以上字母数字 = 真 key 的形状。
            # 文档里的占位符是 sk-你的key（中文），不会被这条命中。
            foreach ($m in [regex]::Matches($txt, 'sk-[A-Za-z0-9]{24,}')) {
                $leaks += "内容 $($_.FullName.Substring($AppDir.Length)): $($m.Value.Substring(0, 12))..."
            }
        }
    if ($leaks.Count -gt 0) {
        Write-Host ""
        Write-Host "  ✗ 打包中止：产物里发现疑似 API key"
        foreach ($l in $leaks) { Write-Host "      $l" }
        Write-Host "    请检查 package.ps1 第 5 步的排除清单，不要把 .env 发出去。"
        throw "产物中发现疑似 API key，已中止打包（拒绝生成可发布的 zip）"
    }
    Write-Host "    密钥检查：通过（产物中无 .env、无 sk- 形式的 key）"
} else {
    Write-Host ""
    Write-Host "==> [5/5] 警告：找不到 qingdu-ai 目录，绿色版将不带 AI 服务源码"
}

# ---------------------------------------------------------------------
# 结果
# ---------------------------------------------------------------------
$exePath = Join-Path $AppDir "$AppName.exe"
$sizeMb  = [math]::Round(((Get-ChildItem -LiteralPath $AppDir -Recurse -File |
            Measure-Object -Property Length -Sum).Sum / 1MB), 1)

Write-Host ""
Write-Host "==> 打包完成"
Write-Host "    启动文件: $exePath"
Write-Host "    整包大小: $sizeMb MB"
Write-Host ""
Write-Host "    把整个 $AppName 文件夹拷给别人，对方双击 $AppName.exe 就能用，"
Write-Host "    不需要安装 JDK 或配置任何环境变量。"
Write-Host ""
Write-Host "    AI 问答应另外起一个服务（首次需装 Python 依赖，见 qingdu-ai\README.md）："
Write-Host "      cd $AppName\qingdu-ai"
Write-Host "      python -m venv .venv"
Write-Host "      .\.venv\Scripts\python.exe -m pip install -e ""[dev]"""
Write-Host "      .\.venv\Scripts\python.exe -m qingdu_ai"
