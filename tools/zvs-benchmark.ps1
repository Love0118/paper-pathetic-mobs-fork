param(
    [Parameter(Mandatory = $true)]
    [string]$ServerJar,
    [Parameter(Mandatory = $true)]
    [string]$PluginJar,
    [Parameter(Mandatory = $true)]
    [string]$RunDirectory,
    [int]$Round = 50,
    [int]$StartupSeconds = 35,
    [int]$WarmupSeconds = 60,
    [int]$CaptureSeconds = 120,
    [string]$Heap = "8G",
    [string]$JavaExecutable = "java",
    [switch]$AcceptEula
)

$ErrorActionPreference = "Stop"
if (-not $AcceptEula) {
    throw "Pass -AcceptEula only after reviewing and accepting the Minecraft EULA."
}
if ($Round -lt 1 -or $StartupSeconds -lt 1 -or $WarmupSeconds -lt 1 -or $CaptureSeconds -lt 1) {
    throw "Round and all durations must be positive."
}

$serverPath = (Resolve-Path -LiteralPath $ServerJar).Path
$pluginPath = (Resolve-Path -LiteralPath $PluginJar).Path
$runPath = [System.IO.Path]::GetFullPath($RunDirectory)
New-Item -ItemType Directory -Path $runPath -Force | Out-Null
$pluginsPath = Join-Path $runPath "plugins"
New-Item -ItemType Directory -Path $pluginsPath -Force | Out-Null
Copy-Item -LiteralPath $pluginPath -Destination (Join-Path $pluginsPath ([System.IO.Path]::GetFileName($pluginPath))) -Force
Set-Content -LiteralPath (Join-Path $runPath "eula.txt") -Value "eula=true" -Encoding ascii

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jfrPath = Join-Path $runPath "zvs-$timestamp.jfr"
$stdoutPath = Join-Path $runPath "zvs-$timestamp.stdout.log"
$stderrPath = Join-Path $runPath "zvs-$timestamp.stderr.log"
$recordingDelay = $StartupSeconds + $WarmupSeconds

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $JavaExecutable
$startInfo.WorkingDirectory = $runPath
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.ArgumentList.Add("-Xms$Heap")
$startInfo.ArgumentList.Add("-Xmx$Heap")
$startInfo.ArgumentList.Add("-XX:+AlwaysPreTouch")
$startInfo.ArgumentList.Add("-XX:StartFlightRecording=name=zvs,delay=${recordingDelay}s,duration=${CaptureSeconds}s,filename=$jfrPath,settings=profile")
$startInfo.ArgumentList.Add("-jar")
$startInfo.ArgumentList.Add($serverPath)
$startInfo.ArgumentList.Add("nogui")

$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $startInfo
if (-not $process.Start()) {
    throw "Failed to start the Paper server."
}
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()

try {
    Start-Sleep -Seconds $StartupSeconds
    if ($process.HasExited) {
        throw "Paper exited during startup."
    }
    $process.StandardInput.WriteLine("zvs debug $Round")
    $process.StandardInput.WriteLine("zvs metrics")
    $process.StandardInput.Flush()

    Write-Host "Round $Round requested. Keep the same load clients connected for warmup and capture."
    Start-Sleep -Seconds ($WarmupSeconds + $CaptureSeconds + 5)
    if (-not $process.HasExited) {
        $process.StandardInput.WriteLine("zvs metrics")
        $process.StandardInput.WriteLine("stop")
        $process.StandardInput.Flush()
        $process.WaitForExit(30000) | Out-Null
    }
} finally {
    if (-not $process.HasExited) {
        $process.Kill($true)
        $process.WaitForExit()
    }
    Set-Content -LiteralPath $stdoutPath -Value $stdoutTask.GetAwaiter().GetResult() -Encoding utf8
    Set-Content -LiteralPath $stderrPath -Value $stderrTask.GetAwaiter().GetResult() -Encoding utf8
    $process.Dispose()
}

if (-not (Test-Path -LiteralPath $jfrPath)) {
    throw "The run ended without a JFR recording. Inspect $stderrPath."
}
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jfrPath).Hash.ToLowerInvariant()
Write-Host "JFR: $jfrPath"
Write-Host "SHA-256: $hash"
Write-Host "Console: $stdoutPath"
