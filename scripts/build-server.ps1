[CmdletBinding()]
param(
    [switch]$Clean,

    [switch]$AllowDirty
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$paperBaseCommit = '29c8822d90899c89d2689338e81a98f690bcba12'
$paperBaseBuild = 60
$minecraftVersion = '1.21.8'
$paperApiVersion = '1.21.8-R0.1-SNAPSHOT'
$paperApiSha256 = 'aab18363ca5a1aaadd4e2716ee0de50bc26f352901af962177d86484baff7478'
$patheticVersion = '5.4.6'

if ($null -ne [System.Environment]::GetEnvironmentVariable('BUILD_NUMBER')) {
    throw 'BUILD_NUMBER must be unset when using this release script; the verified Paper base build is supplied internally.'
}

$javaHomeCandidates = @($env:JAVA_HOME, 'C:\Program Files\Java\jdk-21')
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
            Where-Object { $_.Name -match '(?i)(jdk.?21|zulu21|21\.)' } |
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
    $version -match 'version "21(?:[.\-]|")'
} | Select-Object -First 1

if (-not $javaHome) {
    throw 'JDK 21 was not found. Install it or set JAVA_HOME to a JDK 21 directory.'
}

$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path

function Get-SourceState {
    $statusLines = @(& git status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the Git worktree.' }
    $commit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the source commit.' }
    $branch = ((& git branch --show-current) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve the source branch.' }
    if (-not $branch) { $branch = '(detached)' }
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

function Read-ZipEntryText([System.IO.Compression.ZipArchiveEntry]$Entry) {
    $reader = [System.IO.StreamReader]::new($Entry.Open())
    try {
        return $reader.ReadToEnd()
    } finally {
        $reader.Dispose()
    }
}

function Get-AsciiBuildRoot([string]$SourceRoot) {
    if (-not $IsWindows -or $SourceRoot -notmatch '[^\x00-\x7F]') {
        return [pscustomobject]@{ Path = $SourceRoot; CreatedDrive = $null }
    }

    $resolvedRoot = [System.IO.Path]::GetFullPath($SourceRoot).TrimEnd('\')
    foreach ($line in @(subst)) {
        if ($line -match '^([A-Z]:)\\:\s*=>\s*(.+)$') {
            $mappedRoot = [System.IO.Path]::GetFullPath($Matches[2]).TrimEnd('\')
            if ($mappedRoot.Equals($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                return [pscustomobject]@{ Path = "$($Matches[1])\"; CreatedDrive = $null }
            }
        }
    }

    foreach ($letter in @('R', 'S', 'T', 'U', 'V', 'W')) {
        $drive = "${letter}:"
        if (-not (Test-Path -LiteralPath "$drive\")) {
            & subst $drive $resolvedRoot
            if ($LASTEXITCODE -ne 0) { continue }
            return [pscustomobject]@{ Path = "$drive\"; CreatedDrive = $drive }
        }
    }
    throw 'Unable to allocate an ASCII subst drive for the non-ASCII repository path.'
}

$buildRoot = Get-AsciiBuildRoot $root
Push-Location $buildRoot.Path
try {
    $env:BUILD_NUMBER = $paperBaseBuild.ToString([System.Globalization.CultureInfo]::InvariantCulture)
    $sourceState = Get-SourceState
    if ($sourceState.Status -and -not $AllowDirty) {
        throw 'The Git worktree is dirty. Commit the release revision first, or use -AllowDirty for a development build.'
    }

    & git merge-base --is-ancestor $paperBaseCommit HEAD
    if ($LASTEXITCODE -ne 0) {
        throw "The source does not descend from the verified Paper $minecraftVersion build $paperBaseBuild base $paperBaseCommit."
    }

    if ($Clean) {
        & .\gradlew.bat --no-daemon --no-configuration-cache clean --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle clean failed with exit code $LASTEXITCODE" }
    }

    & .\gradlew.bat --no-daemon --no-configuration-cache applyPatches --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Patch application failed with exit code $LASTEXITCODE" }

    & .\gradlew.bat --no-daemon --no-configuration-cache build createMojmapPaperclipJar --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Server build failed with exit code $LASTEXITCODE" }
    Assert-SourceState $sourceState (Get-SourceState)

    $builtJar = Join-Path $root "paper-server\build\libs\paper-paperclip-$paperApiVersion-mojmap.jar"
    if (-not (Test-Path -LiteralPath $builtJar -PathType Leaf)) {
        throw "Expected Paper $minecraftVersion Paperclip JAR was not produced: $builtJar"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $paperclipArchive = [System.IO.Compression.ZipFile]::OpenRead($builtJar)
    try {
        $manifestEntry = $paperclipArchive.GetEntry('META-INF/MANIFEST.MF')
        $librariesEntry = $paperclipArchive.GetEntry('META-INF/libraries.list')
        $versionsEntry = $paperclipArchive.GetEntry('META-INF/versions.list')
        $patheticPath = "META-INF/libraries/de/bsommerfeld/pathetic/engine/$patheticVersion/engine-$patheticVersion.jar"
        $patheticEntry = $paperclipArchive.GetEntry($patheticPath)
        if ($null -eq $manifestEntry -or $null -eq $librariesEntry -or $null -eq $versionsEntry -or $null -eq $patheticEntry) {
            throw 'The generated JAR is missing the Paperclip manifest, version list, library list, or embedded Pathetic engine.'
        }

        $manifest = Read-ZipEntryText $manifestEntry
        if ($manifest -notmatch '(?m)^Main-Class:\s*io\.papermc\.paperclip\.Main\s*$') {
            throw 'The generated JAR is not an executable Paperclip artifact.'
        }

        $versionsText = Read-ZipEntryText $versionsEntry
        $versionLines = @($versionsText -split "`r?`n" | Where-Object { $_.Trim() })
        if ($versionLines.Count -ne 1 -or $versionLines[0] -notmatch "`t$([regex]::Escape($minecraftVersion))`t") {
            throw "The Paperclip version list does not contain exactly Minecraft $minecraftVersion."
        }

        $librariesText = Read-ZipEntryText $librariesEntry
        $patheticCoordinate = "de.bsommerfeld.pathetic:engine:$patheticVersion"
        if ($librariesText -notmatch "(?m)`t$([regex]::Escape($patheticCoordinate))`t") {
            throw "The Paperclip library list does not contain $patheticCoordinate."
        }
        $apiCoordinate = "io.papermc.paper:paper-api:$paperApiVersion"
        $apiLine = @($librariesText -split "`r?`n") | Where-Object { $_ -match "`t$([regex]::Escape($apiCoordinate))`t" } | Select-Object -First 1
        if (-not $apiLine) {
            throw "The Paperclip library list does not contain $apiCoordinate."
        }
        $apiParts = $apiLine -split "`t"
        if ($apiParts.Count -ne 3) {
            throw 'The Paper API library-list entry has an unexpected format.'
        }
        if ($apiParts[0].ToLowerInvariant() -ne $paperApiSha256) {
            throw "Paper API library-list SHA-256 '$($apiParts[0])' does not match official Paper build $paperBaseBuild '$paperApiSha256'."
        }
        $apiEntry = $paperclipArchive.GetEntry("META-INF/libraries/$($apiParts[2])")
        if ($null -eq $apiEntry) {
            throw 'The generated JAR is missing its versioned Paper API library.'
        }

        $apiBuffer = [System.IO.MemoryStream]::new()
        $apiEntryStream = $apiEntry.Open()
        try {
            $apiEntryStream.CopyTo($apiBuffer)
        } finally {
            $apiEntryStream.Dispose()
        }
        $embeddedApiSha256 = [System.Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData($apiBuffer.ToArray())
        ).ToLowerInvariant()
        if ($embeddedApiSha256 -ne $paperApiSha256) {
            throw "Embedded Paper API SHA-256 '$embeddedApiSha256' does not match official Paper build $paperBaseBuild '$paperApiSha256'."
        }
        $apiBuffer.Position = 0
        $apiArchive = [System.IO.Compression.ZipArchive]::new($apiBuffer, [System.IO.Compression.ZipArchiveMode]::Read, $false)
        try {
            $pomEntry = $apiArchive.GetEntry('META-INF/maven/io.papermc.paper/paper-api/pom.properties')
            if ($null -eq $pomEntry) {
                throw 'The embedded Paper API is missing pom.properties.'
            }
            $pomProperties = Read-ZipEntryText $pomEntry | ConvertFrom-StringData
            if ($pomProperties.version -ne $paperApiVersion) {
                throw "Embedded Paper API version '$($pomProperties.version)' does not match '$paperApiVersion'."
            }
        } finally {
            $apiArchive.Dispose()
            $apiBuffer.Dispose()
        }
    } finally {
        $paperclipArchive.Dispose()
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
    Assert-SourceState $sourceState (Get-SourceState)
    Set-Content -LiteralPath (Join-Path $releaseStage 'BUILD_INFO.txt') -Encoding ascii -Value @(
        'Product: MobOpt Paper 1.21.8',
        "Source-Branch: $($sourceState.Branch)",
        "Source-Commit: $($sourceState.Commit)",
        "Paper-Base: $paperBaseCommit",
        "Paper-Base-Build: $paperBaseBuild",
        "Paper-API-Version: $paperApiVersion",
        "Server-Build-Number: $paperBaseBuild",
        "Dirty-Development-Build: $([bool]$sourceState.Status)"
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
    Remove-Item Env:BUILD_NUMBER -ErrorAction SilentlyContinue
    Pop-Location
    if ($buildRoot.CreatedDrive) {
        & subst $buildRoot.CreatedDrive /d
    }
}
