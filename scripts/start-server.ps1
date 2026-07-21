[CmdletBinding()]
param(
    [ValidateRange(1, 512)]
    [int]$MemoryGb = 4,

    [string]$ServerDirectory,

    [string]$JarPath,

    [switch]$AcceptEula,

    [switch]$SkipHashCheck,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ServerArguments
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not $ServerDirectory) {
    $ServerDirectory = Join-Path $root 'run'
}
$ServerDirectory = [System.IO.Path]::GetFullPath($ServerDirectory)

if (-not $JarPath) {
    $releaseJar = Join-Path $root 'dist\mobopt-paper-26.1.2.jar'
    $JarPath = $releaseJar
}
$JarPath = [System.IO.Path]::GetFullPath($JarPath)

if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Server JAR not found: $JarPath. Run scripts\build-server.ps1 first."
}

$hashPath = "$JarPath.sha256"
if ($SkipHashCheck) {
    Write-Warning "Skipping server JAR SHA-256 verification by explicit request: $JarPath"
} else {
    if (-not (Test-Path -LiteralPath $hashPath)) {
        throw "Server JAR SHA-256 file not found: $hashPath. Restore it or explicitly use -SkipHashCheck for a custom development JAR."
    }
    $hashLine = Get-Content -LiteralPath $hashPath | Select-Object -First 1
    if ($hashLine -notmatch '^\s*([0-9a-fA-F]{64})\s+') {
        throw "Invalid SHA-256 file: $hashPath"
    }
    $expectedHash = $Matches[1].ToLowerInvariant()
    $actualHash = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "Server JAR SHA-256 mismatch: $JarPath"
    }
}

$javaHomeCandidates = @($env:JAVA_HOME, 'C:\Program Files\Java\jdk-25')
$javaSearchRoots = @(
    'C:\Program Files\Java',
    'C:\Program Files\Zulu',
    'C:\Program Files\Eclipse Adoptium',
    (Join-Path $env:LOCALAPPDATA 'Programs\Zulu'),
    (Join-Path $env:USERPROFILE '.gradle\jdks')
)
foreach ($searchRoot in $javaSearchRoots) {
    if (Test-Path -LiteralPath $searchRoot) {
        $javaHomeCandidates += Get-ChildItem -LiteralPath $searchRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)(jdk.?25|zulu25|25\.)' } |
            Select-Object -ExpandProperty FullName
    }
}
$javaHomeCandidates = $javaHomeCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe'))
} | Select-Object -Unique

$javaHome = $javaHomeCandidates | Where-Object {
    $version = & (Join-Path $_ 'bin\java.exe') -version 2>&1 | Select-Object -First 1
    $version -match 'version "25(?:[.\-]|")'
} | Select-Object -First 1

if (-not $javaHome) {
    throw 'JDK 25 was not found. Install it or set JAVA_HOME to a JDK 25 directory.'
}

$java = Join-Path $javaHome 'bin\java.exe'
New-Item -ItemType Directory -Path $ServerDirectory -Force | Out-Null

$eulaPath = Join-Path $ServerDirectory 'eula.txt'
$eulaAccepted = (Test-Path -LiteralPath $eulaPath) -and (Select-String -LiteralPath $eulaPath -Pattern '^\s*eula\s*=\s*true\s*$' -Quiet)
if (-not $eulaAccepted) {
    if (-not $AcceptEula) {
        throw 'Minecraft EULA has not been accepted. Review https://aka.ms/MinecraftEULA and rerun once with -AcceptEula.'
    }
    Set-Content -LiteralPath $eulaPath -Value @(
        '# Accepted through MobOpt launcher after reviewing https://aka.ms/MinecraftEULA',
        'eula=true'
    ) -Encoding ascii
}

$jvmArguments = @(
    "-Xms${MemoryGb}G",
    "-Xmx${MemoryGb}G",
    '-XX:+UseG1GC',
    '-XX:+ParallelRefProcEnabled',
    '-XX:+AlwaysPreTouch',
    '-XX:+DisableExplicitGC',
    '-Dfile.encoding=UTF-8',
    '-jar',
    $JarPath,
    'nogui'
)

if ($ServerArguments) {
    $jvmArguments += $ServerArguments
}

Write-Host "Java: $java"
Write-Host "Server directory: $ServerDirectory"
Write-Host "Server JAR: $JarPath"

Push-Location $ServerDirectory
try {
    & $java @jvmArguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
