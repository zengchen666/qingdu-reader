# =====================================================================
#  轻读阅读器 —— 打包产物冒烟测试
#
#  光"打包成功"说明不了问题。有两类故障在 编译 / 单元测试 / 打包 三个阶段
#  全都不会报错，只有真跑起来才暴露：
#
#    1. 字符集被裁掉   —— jdeps 看不到 Charset.forName("GB18030") 这种反射式查找，
#                        少了 jdk.charsets，打开 GBK 小说直接抛 UnsupportedCharsetException；
#    2. 存储层跑不起来 —— sqlite-jdbc 需要 java.sql / java.naming / jdk.unsupported
#                        （最后一个是它反射用 sun.misc.Unsafe）。
#
#  所以这个脚本要在**裁剪过的运行时**上真跑一遍：
#    · 编码探测 → 建索引 → 按偏移读正文（验第 1 类）
#    · 临时目录里真开一次 SQLite，写进度 / 书签 / 设置再读回来（验第 2 类）
#
#  关键的坑都写在下面的注释里，改动打包参数后请连着这个脚本一起看。
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1
#
#  前提：先跑过 scripts\package.ps1（需要 dist\QingduReader\ 和 target\package-stage\）。
#
#  注意：本文件必须保存为「UTF-8 带 BOM」。PowerShell 5.1 读没有 BOM 的脚本时
#        会按系统 ANSI 代码页解析，中文会变成乱码，轻则报错、重则静默出错。
#        用 VS Code 保存时选「UTF-8 with BOM」。
# =====================================================================

[CmdletBinding()]
param(
    [string] $JavaHome = $env:JAVA_HOME,
    # 用来测"打开 GBK 小说"这条链路的样例文件。
    [string] $SampleFile,
    [switch] $SkipCompile
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Root   = Split-Path -Parent $PSScriptRoot
$Stage  = Join-Path $Root "qingdu-desktop\target\package-stage"
$AppDir = Join-Path $Stage "app"
$FxDir  = Join-Path $Stage "fx"
$Work   = Join-Path $Root "qingdu-desktop\target\pack-smoke"

if ([string]::IsNullOrWhiteSpace($SampleFile)) {
    $SampleFile = Join-Path $Root "samples\星尘纪-示例-GBK.txt"
}

foreach ($p in @($AppDir, $FxDir, $SampleFile)) {
    if (-not (Test-Path -LiteralPath $p)) {
        throw "找不到 $p。请先运行 scripts\package.ps1 打包。"
    }
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "请设置 JAVA_HOME，或用 -JavaHome 指定。"
}
$Java   = Join-Path $JavaHome "bin\java.exe"
$Javac  = Join-Path $JavaHome "bin\javac.exe"

# ---------------------------------------------------------------------
#  坑 1：模块清单要读 dist 里那份，不能照抄文档
#
#  runtime\release 里的 MODULES 才是 jpackage 真正编进镜像的东西。
#  照抄文档里写死的清单，改了打包参数之后两边会悄悄对不上 ——
#  而冒烟测试的意义正是"验证真实产物"，清单抄错就等于白测。
#
#  坑 2：release 里的 MODULES 是**空格分隔**的，而 --limit-modules /
#  --add-modules 要的是逗号分隔。直接拿空格那串喂给 java，会得到
#  "Error: --add-modules requires modules to be specified" —— 报错信息
#  完全看不出是分隔符的问题。
# ---------------------------------------------------------------------
$release = Get-Content -LiteralPath (Join-Path $Root "dist\QingduReader\runtime\release")
$raw = ($release | Where-Object { $_ -match '^MODULES=' }) -replace '^MODULES="', '' -replace '"$', ''
if ([string]::IsNullOrWhiteSpace($raw)) { throw "dist 里读不到 MODULES，打包产物不完整。" }
$allModules    = ($raw -split '[\s,]+' | Where-Object { $_ }) -join ','
$javafxModules = ($raw -split '[\s,]+' | Where-Object { $_ -like 'javafx.*' }) -join ','

Write-Host "==> 模块清单（来自 dist\QingduReader\runtime\release）"
Write-Host "    $allModules"
foreach ($need in @("jdk.charsets", "java.sql", "jdk.unsupported")) {
    if ($allModules -notmatch [regex]::Escape($need)) {
        Write-Warning "模块清单里缺 $need —— 裁剪过的运行时大概率会缺字符集或存储能力。"
    }
}

New-Item -ItemType Directory -Path $Work -Force | Out-Null

# ---------------------------------------------------------------------
#  坑 3：样例文件名带中文，不能直接经控制台传给 JVM
#
#  PowerShell 把参数交给子进程时按系统 ANSI 代码页编码，JVM 侧再按 UTF-8 解，
#  中文文件名就成了乱码，java.nio 会直接抛：
#      InvalidPathException: Illegal char <?> at index 63: ...鏄熷皹绾?绀轰緥-GBK.txt
#  所以先复制成纯 ASCII 名字再传。（也可以 chcp 65001，但那要改用户环境。）
# ---------------------------------------------------------------------
$fixture = Join-Path $Work "sample-gbk.txt"
Copy-Item -LiteralPath $SampleFile -Destination $fixture -Force

if (-not $SkipCompile) {
    Write-Host "==> 编译 PackSmoke.java"
    $outDir = Join-Path $Work "out"
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    # classpath 里已经包含 sqlite-jdbc，所以 PackSmoke 能直接引用存储层
    & $Javac -encoding UTF-8 -cp "$AppDir\*" -d $outDir (Join-Path $Root "scripts\PackSmoke.java")
    if ($LASTEXITCODE -ne 0) { throw "javac 失败。" }
}

$report = Join-Path $Work "smoke-report.txt"
Write-Host "==> 在裁剪过的运行时上跑冒烟测试（报告：$report）"

# --limit-modules 只认 JDK 自带模块，JavaFX 那几个必须同时挂到 --module-path 上，
# 否则直接报 "Module javafx.base not found"。
# --enable-native-access 必须带 ALL-UNNAMED：sqlite-jdbc 从 classpath（未命名模块）
# 加载本地库，不带就会刷一屏 WARNING。
& $Java `
    --module-path $FxDir `
    --add-modules $javafxModules `
    --limit-modules $allModules `
    --enable-native-access=javafx.graphics,ALL-UNNAMED `
    -cp "$(Join-Path $Work 'out');$AppDir\*" `
    PackSmoke $fixture $report
$code = $LASTEXITCODE

Write-Host ""
if (Test-Path -LiteralPath $report) { Get-Content -LiteralPath $report -Encoding UTF8 }

if ($code -ne 0) { throw "冒烟测试失败（exit $code），看上面对应的 [charset] / [store] 行。" }
Write-Host ""
Write-Host "==> 冒烟测试通过：字符集、存储层、全文检索在裁剪过的运行时上都正常。"
