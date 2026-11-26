# 打包瘦身（C017-fix，Windows 版，对应 appbuild/slim-jar.sh）：在 Spring Boot fat jar 里，
# onnxruntime 只留 win-x64、DJL 分词器只留 win-x86_64 本机库，并删掉 *.pdb / *.dSYM 调试符号。
# 原地修改传入的 jar（只给放进 App 的那份拷贝用）。
# 用法: slim-jar.ps1 -JarPath <jobapply.jar> -JarTool <JDK 的 bin\jar.exe>
param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(Mandatory = $true)][string]$JarTool
)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$JarPath = (Resolve-Path $JarPath).Path
$Work = Join-Path ([System.IO.Path]::GetTempPath()) ("jobapply-slim-" + [System.Guid]::NewGuid().ToString())
$Nested = @(
    @{ Pattern = "BOOT-INF/lib/onnxruntime-*.jar"; Root = "ai/onnxruntime/native/"; Keep = "win-x64" },
    @{ Pattern = "BOOT-INF/lib/tokenizers-*.jar";  Root = "native/lib/";            Keep = "win-x86_64" }
)

try {
    # 1. 取出 nested jar
    $names = @()
    $fat = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        foreach ($n in $Nested) {
            foreach ($e in $fat.Entries | Where-Object { $_.FullName -like $n.Pattern }) {
                $dest = Join-Path $Work $e.FullName
                New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest)
                $names += , @($e.FullName, $n)
            }
        }
    } finally { $fat.Dispose() }

    # 2. 删掉其它平台的本机库和调试符号，确认本平台还在
    foreach ($item in $names) {
        $name = $item[0]; $n = $item[1]
        $zip = [System.IO.Compression.ZipFile]::Open((Join-Path $Work $name), "Update")
        try {
            $drop = $zip.Entries | Where-Object {
                $f = $_.FullName
                ($f.StartsWith($n.Root) -and $f.Substring($n.Root.Length).Contains("/") -and -not $f.StartsWith($n.Root + $n.Keep + "/")) -or
                $f.EndsWith(".pdb") -or $f.Contains(".dSYM/")
            }
            foreach ($e in @($drop)) { $e.Delete() }
            if (-not ($zip.Entries | Where-Object { $_.FullName.StartsWith($n.Root + $n.Keep + "/") -and $_.Length -gt 0 })) {
                throw "瘦身失败: $name 里没有 $($n.Root)$($n.Keep)/"
            }
        } finally { $zip.Dispose() }
    }

    # 3. 用 JDK 的 jar 工具不压缩（STORED）放回，Spring Boot 要求 nested jar 不压缩
    # -C 只对紧跟的一个文件生效，所以每个文件都带一次
    & $JarTool --update --no-compress --file $JarPath @($names | ForEach-Object { "-C", $Work, $_[0] })
    if ($LASTEXITCODE -ne 0) { throw "jar --update 失败（退出码 $LASTEXITCODE）" }
    Write-Host ("瘦身完成 (windows): {0:N0} MB {1}" -f ((Get-Item $JarPath).Length / 1MB), $JarPath)
} finally {
    Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
