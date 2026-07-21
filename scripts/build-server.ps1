[CmdletBinding()]
param(
    [switch]$Clean,

    [switch]$AllowDirty
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$javaHomeCandidates = @(
    $env:JAVA_HOME,
    'C:\Program Files\Java\jdk-21'
) | Where-Object {
    ($null -ne $_) -and
        (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) -and
        (Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe'))
}

$javaHome = $javaHomeCandidates | Where-Object {
    $version = & (Join-Path $_ 'bin\java.exe') -version 2>&1 | Select-Object -First 1
    $version -match 'version "21[\.-]'
} | Select-Object -First 1

if (-not $javaHome) {
    throw 'JDK 21 was not found. Install it or set JAVA_HOME to a JDK 21 directory.'
}

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path

Push-Location $root
try {
    $gitStatus = & git status --porcelain --untracked-files=normal
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the Git worktree.' }
    if ($gitStatus -and -not $AllowDirty) {
        throw 'The Git worktree is dirty. Commit the release revision first, or use -AllowDirty for a development build.'
    }
    $sourceCommit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the source commit.' }
    $sourceBranch = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the source branch.' }

    if ($Clean) {
        & .\gradlew.bat --no-daemon clean
        if ($LASTEXITCODE -ne 0) { throw "Gradle clean failed with exit code $LASTEXITCODE" }
    }

    & .\gradlew.bat --no-daemon applyPatches --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Patch application failed with exit code $LASTEXITCODE" }

    & .\gradlew.bat --no-daemon build createMojmapPaperclipJar --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Server build failed with exit code $LASTEXITCODE" }

    $builtJar = Join-Path $root 'paper-server\build\libs\paper-paperclip-1.21.8-R0.1-SNAPSHOT-mojmap.jar'
    if (-not (Test-Path -LiteralPath $builtJar)) {
        throw "Expected Paperclip JAR was not created: $builtJar"
    }

    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $releaseJar = Join-Path $dist 'mobopt-paper-1.21.8.jar'
    Copy-Item -LiteralPath $builtJar -Destination $releaseJar -Force

    $hash = Get-FileHash -LiteralPath $releaseJar -Algorithm SHA256
    $hashLine = "$($hash.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($releaseJar))"
    $hashPath = Join-Path $dist 'mobopt-paper-1.21.8.jar.sha256'
    Set-Content -LiteralPath $hashPath -Value $hashLine -Encoding ascii

    $releaseStage = [System.IO.Path]::GetFullPath((Join-Path $dist 'mobopt-paper-1.21.8-release'))
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
    Set-Content -LiteralPath (Join-Path $releaseStage 'BUILD_INFO.txt') -Encoding ascii -Value @(
        'Product: MobOpt Paper 1.21.8',
        "Source-Branch: $sourceBranch",
        "Source-Commit: $sourceCommit",
        'Paper-Base: 29c8822d90899c89d2689338e81a98f690bcba12',
        "Dirty-Development-Build: $([bool]$gitStatus)"
    )

    $releaseZip = Join-Path $dist 'mobopt-paper-1.21.8-release.zip'
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
