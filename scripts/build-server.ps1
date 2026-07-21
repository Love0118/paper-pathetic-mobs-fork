[CmdletBinding()]
param(
    [switch]$Clean,

    [switch]$AllowDirty
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($null -ne [System.Environment]::GetEnvironmentVariable('BUILD_NUMBER')) {
    throw 'BUILD_NUMBER must be unset when using this release script; it only packages the verified local-SNAPSHOT artifact.'
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
    ($null -ne $_) -and
        (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) -and
        (Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe'))
} | Select-Object -Unique

$javaHome = $javaHomeCandidates | Where-Object {
    $version = & (Join-Path $_ 'bin\java.exe') -version 2>&1 | Select-Object -First 1
    $version -match 'version "25(?:[.\-]|")'
} | Select-Object -First 1

if (-not $javaHome) {
    throw 'JDK 25 was not found. Install it or set JAVA_HOME to a JDK 25 directory.'
}

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path

function Get-SourceState {
    $statusLines = @(& git status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the Git worktree.' }
    $commit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the source commit.' }
    $branch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the source branch.' }
    return [pscustomobject]@{
        Status = $statusLines -join "`n"
        Commit = $commit
        Branch = $branch
    }
}

function Assert-SourceState([object]$Expected, [object]$Actual) {
    if ($Actual.Commit -ne $Expected.Commit -or
        $Actual.Branch -ne $Expected.Branch -or
        $Actual.Status -ne $Expected.Status) {
        throw 'The source revision or worktree changed during the build. Discard this artifact and rebuild from a stable worktree.'
    }
}

Push-Location $root
try {
    $sourceState = Get-SourceState
    if ($sourceState.Status -and -not $AllowDirty) {
        throw 'The Git worktree is dirty. Commit the release revision first, or use -AllowDirty for a development build.'
    }

    if ($Clean) {
        & .\gradlew.bat --no-daemon clean
        if ($LASTEXITCODE -ne 0) { throw "Gradle clean failed with exit code $LASTEXITCODE" }
    }

    & .\gradlew.bat --no-daemon applyPatches --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Patch application failed with exit code $LASTEXITCODE" }

    & .\gradlew.bat --no-daemon build createPaperclipJar --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Server build failed with exit code $LASTEXITCODE" }
    Assert-SourceState $sourceState (Get-SourceState)

    $builtJar = Join-Path $root 'paper-server\build\libs\paper-paperclip-26.1.2.local-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $builtJar -PathType Leaf)) {
        throw "Expected Paper 26.1.2 Paperclip JAR was not produced: $builtJar"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $paperclipArchive = [System.IO.Compression.ZipFile]::OpenRead($builtJar)
    try {
        $manifestEntry = $paperclipArchive.GetEntry('META-INF/MANIFEST.MF')
        $patheticEntry = $paperclipArchive.GetEntry('META-INF/libraries/de/bsommerfeld/pathetic/engine/5.4.6/engine-5.4.6.jar')
        if ($null -eq $manifestEntry -or $null -eq $patheticEntry) {
            throw 'The generated JAR is missing the Paperclip manifest or embedded Pathetic engine.'
        }
        $manifestReader = [System.IO.StreamReader]::new($manifestEntry.Open())
        try {
            $manifest = $manifestReader.ReadToEnd()
        } finally {
            $manifestReader.Dispose()
        }
        if ($manifest -notmatch '(?m)^Main-Class:\s*io\.papermc\.paperclip\.Main\s*$') {
            throw 'The generated JAR is not an executable Paperclip artifact.'
        }
    } finally {
        $paperclipArchive.Dispose()
    }

    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $releaseJar = Join-Path $dist 'mobopt-paper-26.1.2.jar'
    Copy-Item -LiteralPath $builtJar -Destination $releaseJar -Force

    $hash = Get-FileHash -LiteralPath $releaseJar -Algorithm SHA256
    $hashLine = "$($hash.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($releaseJar))"
    $hashPath = Join-Path $dist 'mobopt-paper-26.1.2.jar.sha256'
    Set-Content -LiteralPath $hashPath -Value $hashLine -Encoding ascii

    $releaseStage = [System.IO.Path]::GetFullPath((Join-Path $dist 'mobopt-paper-26.1.2-release'))
    $distPrefix = [System.IO.Path]::GetFullPath($dist).TrimEnd('\') + '\'
    if (-not $releaseStage.StartsWith($distPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe release staging path: $releaseStage"
    }
    if (Test-Path -LiteralPath $releaseStage) {
        Remove-Item -LiteralPath $releaseStage -Recurse -Force
    }
    $releaseDist = Join-Path $releaseStage 'dist'
    $releaseScripts = Join-Path $releaseStage 'scripts'
    $releaseLicenses = Join-Path $releaseStage 'licenses'
    New-Item -ItemType Directory -Path $releaseDist, $releaseScripts, $releaseLicenses -Force | Out-Null
    Copy-Item -LiteralPath $releaseJar, $hashPath -Destination $releaseDist
    Copy-Item -LiteralPath (Join-Path $root 'scripts\start-server.ps1'), (Join-Path $root 'scripts\start-server.cmd') -Destination $releaseScripts
    Copy-Item -LiteralPath (Join-Path $root 'README.md'), (Join-Path $root 'MOBOPT.md'), (Join-Path $root 'THIRD_PARTY_NOTICES.md'), (Join-Path $root 'LICENSE.md') -Destination $releaseStage
    Copy-Item -LiteralPath (Join-Path $root 'licenses\GPL.md'), (Join-Path $root 'licenses\MIT.md'), (Join-Path $root 'licenses\PATHETIC-MIT.txt'), (Join-Path $root 'licenses\PATHETIC-MOBS-CC0-1.0.txt') -Destination $releaseLicenses
    Assert-SourceState $sourceState (Get-SourceState)
    Set-Content -LiteralPath (Join-Path $releaseStage 'BUILD_INFO.txt') -Encoding ascii -Value @(
        'Product: MobOpt Paper 26.1.2',
        "Source-Branch: $($sourceState.Branch)",
        "Source-Commit: $($sourceState.Commit)",
        'Paper-Base: e4e17fc90d31c3dca6de8bebc87c741749f8f3df',
        "Dirty-Development-Build: $([bool]$sourceState.Status)"
    )

    $releaseZip = Join-Path $dist 'mobopt-paper-26.1.2-release.zip'
    Compress-Archive -Path (Join-Path $releaseStage '*') -DestinationPath $releaseZip -CompressionLevel Optimal -Force
    $zipHash = Get-FileHash -LiteralPath $releaseZip -Algorithm SHA256
    $zipHashLine = "$($zipHash.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($releaseZip))"
    Set-Content -LiteralPath "$releaseZip.sha256" -Value $zipHashLine -Encoding ascii

    Write-Host "Built: $releaseJar"
    Write-Host "SHA-256: $($hash.Hash.ToLowerInvariant())"
    Write-Host "Release ZIP: $releaseZip"
} finally {
    Pop-Location
}
