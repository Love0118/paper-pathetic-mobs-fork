param(
    [Parameter(Mandatory = $true)]
    [string]$StockJar,
    [Parameter(Mandatory = $true)]
    [string]$CandidateJar,
    [Parameter(Mandatory = $true)]
    [string]$PluginJar,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [ValidateRange(1, 10000)]
    [int]$EntityCount = 2000,
    [ValidateRange(1, 1000000)]
    [int]$WarmupTicks = 1200,
    [ValidateRange(1, 1000000)]
    [int]$MeasureTicks = 2400,
    [ValidateRange(1, 10)]
    [int]$Repetitions = 3,
    [ValidateRange(0, 100000)]
    [int]$DamageIntervalTicks = 0,
    [ValidateRange(1, 64)]
    [int]$DamageHitsPerTarget = 4,
    [ValidateRange(0.000001, 1000.0)]
    [double]$DamageAmount = 0.0001,
    [string]$Heap = "4G",
    [string]$JavaExecutable = "java",
    [string]$JcmdExecutable = "jcmd",
    [string]$JfrExecutable = "jfr",
    [string]$StockPaperGlobalConfig = "",
    [string]$CandidatePaperGlobalConfig = "",
    [switch]$AcceptEula
)

$ErrorActionPreference = "Stop"
if (-not $AcceptEula) {
    throw "Pass -AcceptEula only after reviewing and accepting the Minecraft EULA."
}

$stockPath = (Resolve-Path -LiteralPath $StockJar).Path
$candidatePath = (Resolve-Path -LiteralPath $CandidateJar).Path
$pluginPath = (Resolve-Path -LiteralPath $PluginJar).Path
$stockConfigPath = if ([string]::IsNullOrWhiteSpace($StockPaperGlobalConfig)) { $null } else { (Resolve-Path -LiteralPath $StockPaperGlobalConfig).Path }
$candidateConfigPath = if ([string]::IsNullOrWhiteSpace($CandidatePaperGlobalConfig)) { $null } else { (Resolve-Path -LiteralPath $CandidatePaperGlobalConfig).Path }
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
$sessionPath = Join-Path $outputRoot (Get-Date -Format "yyyyMMdd-HHmmss")
New-Item -ItemType Directory -Path $sessionPath -Force | Out-Null

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Read-SharedText {
    param([string]$Path)
    $stream = [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::ReadWrite
    )
    try {
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true)
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Send-ServerCommand {
    param([System.Diagnostics.Process]$Process, [string]$Command)
    if ($Process.HasExited) {
        throw "Paper exited before command: $Command"
    }
    $Process.StandardInput.WriteLine($Command)
}

function Wait-LogMatchCount {
    param(
        [System.Diagnostics.Process]$Process,
        [string]$LogPath,
        [string]$Pattern,
        [int]$PreviousCount,
        [int]$TimeoutSeconds
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "Paper exited while waiting for log pattern: $Pattern"
        }
        if (Test-Path -LiteralPath $LogPath) {
            $content = Read-SharedText -Path $LogPath
            $count = [regex]::Matches($content, $Pattern).Count
            if ($count -gt $PreviousCount) {
                return $count
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out waiting for log pattern: $Pattern"
}

function Invoke-Jcmd {
    param([int]$ProcessId, [string[]]$Arguments)
    $output = & $JcmdExecutable $ProcessId @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "jcmd failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function New-BenchmarkFiles {
    param([string]$RunPath, [string]$Variant)
    New-Item -ItemType Directory -Path $RunPath -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $RunPath "plugins") -Force | Out-Null
    Write-Utf8File -Path (Join-Path $RunPath "eula.txt") -Content "eula=true`n"
    Write-Utf8File -Path (Join-Path $RunPath "server.properties") -Content @"
allow-flight=true
difficulty=hard
enable-command-block=false
enable-query=false
enable-rcon=false
enforce-secure-profile=false
force-gamemode=false
gamemode=survival
generate-structures=false
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","features":false,"lakes":false,"structure_overrides":[]}
hardcore=false
level-name=world
level-seed=621121
level-type=minecraft\:flat
max-players=1
motd=ZVS headless A/B benchmark
online-mode=false
pause-when-empty-seconds=-1
server-port=0
simulation-distance=10
spawn-animals=false
spawn-monsters=false
spawn-npcs=false
spawn-protection=0
sync-chunk-writes=true
view-distance=10
white-list=false
"@
    Write-Utf8File -Path (Join-Path $RunPath "spigot.yml") -Content @"
config-version: 13
settings:
  debug: false
world-settings:
  default:
    entity-activation-range:
      monsters: 0
      villagers: 0
"@
    Copy-Item -LiteralPath $pluginPath -Destination (Join-Path $RunPath "plugins\ZombieVsSpear.jar") -Force
    $paperConfigPath = if ($Variant -eq "stock") { $stockConfigPath } else { $candidateConfigPath }
    if ($null -ne $paperConfigPath) {
        $configDirectory = Join-Path $RunPath "config"
        New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
        Copy-Item -LiteralPath $paperConfigPath -Destination (Join-Path $configDirectory "paper-global.yml") -Force
    }
}

function Add-SyntheticLoad {
    param([System.Diagnostics.Process]$Process)
    $setupCommands = @(
        "gamerule mob_griefing false",
        "gamerule max_entity_cramming 0",
        "time set midnight",
        "difficulty hard",
        "forceload add -96 -96 96 96",
        "kill @e[type=!minecraft:player]",
        "gamerule send_command_feedback false",
        "gamerule log_admin_commands false",
        "team add zvsbench",
        "team modify zvsbench collisionRule never",
        "scoreboard objectives add zvs_count dummy",
        "fill -2 -60 -2 2 -58 -2 minecraft:bedrock",
        "fill -2 -60 2 2 -58 2 minecraft:bedrock",
        "fill -2 -60 -1 -2 -58 1 minecraft:bedrock",
        "fill 2 -60 -1 2 -58 1 minecraft:bedrock",
        'summon minecraft:villager 0 -60 0 {NoAI:1b,Invulnerable:1b,Silent:1b,PersistenceRequired:1b,Tags:["zvs_bench_target"]}'
    )
    foreach ($command in $setupCommands) {
        Send-ServerCommand -Process $Process -Command $command
    }

    $goldenAngle = 2.399963229728653
    for ($index = 0; $index -lt $EntityCount; $index++) {
        $angle = $index * $goldenAngle
        $radius = 12.0 + (($index * 37) % 69)
        $x = [Math]::Cos($angle) * $radius
        $z = [Math]::Sin($angle) * $radius
        $xText = $x.ToString("0.000", [Globalization.CultureInfo]::InvariantCulture)
        $zText = $z.ToString("0.000", [Globalization.CultureInfo]::InvariantCulture)
        $command = 'summon minecraft:zombie {0} -60 {1} {{PersistenceRequired:1b,Silent:1b,CanPickUpLoot:0b,Tags:["zvs_managed","zvs_bench"]}}' -f $xText, $zText
        Send-ServerCommand -Process $Process -Command $command
        if (($index + 1) % 100 -eq 0) {
            $Process.StandardInput.Flush()
        }
    }
    Send-ServerCommand -Process $Process -Command "team join zvsbench @e[tag=zvs_bench]"
    Send-ServerCommand -Process $Process -Command "gamerule send_command_feedback true"
    Send-ServerCommand -Process $Process -Command "execute store result score #alive zvs_count run execute if entity @e[tag=zvs_bench]"
    Send-ServerCommand -Process $Process -Command "scoreboard players get #alive zvs_count"
    Send-ServerCommand -Process $Process -Command "say ZVS_BENCH_SETUP_DONE"
    $Process.StandardInput.Flush()
}

function Get-SprintResult {
    param([string]$Text)
    $matches = [regex]::Matches(
        $Text,
        "Sprint completed.*?(?:average\s+|or\s+)([0-9.,]+)\s+ms per tick",
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )
    if ($matches.Count -lt 2) {
        throw "Expected warmup and measured sprint results, found $($matches.Count)."
    }
    $match = $matches[$matches.Count - 1]
    $mspt = [double]::Parse($match.Groups[1].Value.Replace(",", ""), [Globalization.CultureInfo]::InvariantCulture)
    $line = [regex]::Replace($match.Value, "\s+", " ").Trim()
    [pscustomobject]@{ line = $line; averageMspt = $mspt }
}

function Invoke-BenchmarkRun {
    param(
        [string]$Variant,
        [string]$ServerPath,
        [int]$Repetition,
        [int]$Sequence
    )
    $runName = "{0:D2}-{1}-r{2}" -f $Sequence, $Variant, $Repetition
    $runPath = Join-Path $sessionPath $runName
    New-BenchmarkFiles -RunPath $runPath -Variant $Variant
    $stdoutPath = Join-Path $runPath "console.stdout.log"
    $stderrPath = Join-Path $runPath "console.stderr.log"
    $jfrPath = Join-Path $runPath "measure.jfr"
    $jfrSummaryPath = Join-Path $runPath "jfr-summary.txt"
    $latestLog = Join-Path $runPath "logs\latest.log"

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
    $startInfo.ArgumentList.Add("-Dterminal.jline=false")
    $startInfo.ArgumentList.Add("-Dterminal.ansi=false")
    $startInfo.ArgumentList.Add("-jar")
    $startInfo.ArgumentList.Add($ServerPath)
    $startInfo.ArgumentList.Add("nogui")

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    Write-Host "[$runName] starting"
    if (-not $process.Start()) {
        throw "Failed to start $Variant Paper."
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    try {
        Wait-LogMatchCount -Process $process -LogPath $latestLog -Pattern "Done \(" -PreviousCount 0 -TimeoutSeconds 180 | Out-Null
        Add-SyntheticLoad -Process $process
        Wait-LogMatchCount -Process $process -LogPath $latestLog -Pattern "ZVS_BENCH_SETUP_DONE" -PreviousCount 0 -TimeoutSeconds 180 | Out-Null

        if ($DamageIntervalTicks -gt 0) {
            $amountText = $DamageAmount.ToString("R", [Globalization.CultureInfo]::InvariantCulture)
            Send-ServerCommand -Process $process -Command "zvs benchdamage start zvs_bench $DamageIntervalTicks $DamageHitsPerTarget $amountText"
            $process.StandardInput.Flush()
            Wait-LogMatchCount -Process $process -LogPath $latestLog -Pattern "ZVS_DAMAGE_BENCH_STARTED" -PreviousCount 0 -TimeoutSeconds 180 | Out-Null
        }

        Send-ServerCommand -Process $process -Command "tick sprint $WarmupTicks"
        $process.StandardInput.Flush()
        Wait-LogMatchCount -Process $process -LogPath $latestLog -Pattern "Sprint completed" -PreviousCount 0 -TimeoutSeconds 600 | Out-Null

        if ($DamageIntervalTicks -gt 0) {
            Send-ServerCommand -Process $process -Command "zvs benchdamage status"
            $process.StandardInput.Flush()
            Wait-LogMatchCount -Process $process -LogPath $latestLog -Pattern "ZVS_DAMAGE_BENCH targets=" -PreviousCount 0 -TimeoutSeconds 60 | Out-Null
        }
        Send-ServerCommand -Process $process -Command "zvs metrics"
        $process.StandardInput.Flush()
        Invoke-Jcmd -ProcessId $process.Id -Arguments @("JFR.start", "name=zvs", "settings=profile") | Out-Null
        Send-ServerCommand -Process $process -Command "tick sprint $MeasureTicks"
        $process.StandardInput.Flush()
        Wait-LogMatchCount -Process $process -LogPath $latestLog -Pattern "Sprint completed" -PreviousCount 1 -TimeoutSeconds 1200 | Out-Null
        Invoke-Jcmd -ProcessId $process.Id -Arguments @("JFR.dump", "name=zvs", "filename=$jfrPath") | Out-Null
        Invoke-Jcmd -ProcessId $process.Id -Arguments @("JFR.stop", "name=zvs") | Out-Null
        Send-ServerCommand -Process $process -Command "execute store result score #alive zvs_count run execute if entity @e[tag=zvs_bench]"
        Send-ServerCommand -Process $process -Command "scoreboard players get #alive zvs_count"
        if ($DamageIntervalTicks -gt 0) {
            Send-ServerCommand -Process $process -Command "zvs benchdamage status"
            $process.StandardInput.Flush()
            Wait-LogMatchCount -Process $process -LogPath $latestLog -Pattern "ZVS_DAMAGE_BENCH targets=" -PreviousCount 1 -TimeoutSeconds 60 | Out-Null
        }
        Send-ServerCommand -Process $process -Command "zvs metrics"
        Send-ServerCommand -Process $process -Command "stop"
        $process.StandardInput.Flush()
        if (-not $process.WaitForExit(60000)) {
            throw "$Variant Paper did not stop within 60 seconds."
        }
    } finally {
        if (-not $process.HasExited) {
            $process.Kill($true)
            $process.WaitForExit()
        }
        Write-Utf8File -Path $stdoutPath -Content $stdoutTask.GetAwaiter().GetResult()
        Write-Utf8File -Path $stderrPath -Content $stderrTask.GetAwaiter().GetResult()
        $process.Dispose()
    }

    if (-not (Test-Path -LiteralPath $jfrPath)) {
        throw "$Variant run ended without a JFR recording."
    }
    $jfrSummary = & $JfrExecutable summary $jfrPath 2>&1
    Write-Utf8File -Path $jfrSummaryPath -Content ($jfrSummary -join [Environment]::NewLine)
    $consoleText = [System.IO.File]::ReadAllText($stdoutPath)
    $sprint = Get-SprintResult -Text $consoleText
    $aliveMatches = [regex]::Matches($consoleText, "#alive has ([0-9]+) \[zvs_count\]")
    if ($aliveMatches.Count -lt 2) {
        throw "$Variant run did not record both entity-count checks."
    }
    $initialAlive = [int]$aliveMatches[0].Groups[1].Value
    $finalAlive = [int]$aliveMatches[$aliveMatches.Count - 1].Groups[1].Value
    if ($initialAlive -ne $EntityCount -or $finalAlive -ne $EntityCount) {
        throw "$Variant entity count changed: initial=$initialAlive final=$finalAlive expected=$EntityCount"
    }
    $metricLines = $consoleText -split "`r?`n" | Where-Object {
        $_ -match "effects logical=|pathfinding Snapshot|network Snapshot|damage Snapshot|entity-lod Snapshot|ZVS_DAMAGE_BENCH targets="
    }
    $damagePulses = 0L
    $damageRequests = 0L
    $damageEvents = 0L
    $pathRequests = 0L
    if ($DamageIntervalTicks -gt 0) {
        $damageMatches = [regex]::Matches(
            $consoleText,
            "ZVS_DAMAGE_BENCH targets=[0-9]+ hitsPerTarget=[0-9]+ pulses=([0-9]+) requests=([0-9]+) events=([0-9]+).*?pathRequests=([0-9]+)"
        )
        if ($damageMatches.Count -lt 2) {
            throw "$Variant run did not record both damage-load snapshots."
        }
        $warmupDamage = $damageMatches[0]
        $measuredDamage = $damageMatches[$damageMatches.Count - 1]
        $damagePulses = [long]$measuredDamage.Groups[1].Value - [long]$warmupDamage.Groups[1].Value
        $damageRequests = [long]$measuredDamage.Groups[2].Value - [long]$warmupDamage.Groups[2].Value
        $damageEvents = [long]$measuredDamage.Groups[3].Value - [long]$warmupDamage.Groups[3].Value
        $pathRequests = [long]$measuredDamage.Groups[4].Value - [long]$warmupDamage.Groups[4].Value
    }
    $jfrHash = (Get-FileHash -LiteralPath $jfrPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "[$runName] average MSPT $($sprint.averageMspt)"
    [pscustomobject]@{
        variant = $Variant
        repetition = $Repetition
        sequence = $Sequence
        averageMspt = $sprint.averageMspt
        initialAlive = $initialAlive
        finalAlive = $finalAlive
        damagePulses = $damagePulses
        damageRequests = $damageRequests
        damageEvents = $damageEvents
        pathRequests = $pathRequests
        sprintLine = $sprint.line
        metrics = @($metricLines)
        jfr = $jfrPath
        jfrSha256 = $jfrHash
        console = $stdoutPath
    }
}

$variants = @{
    stock = $stockPath
    candidate = $candidatePath
}
$runs = [System.Collections.Generic.List[object]]::new()
$sequence = 0
for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
    $order = if ($repetition % 2 -eq 1) { @("stock", "candidate") } else { @("candidate", "stock") }
    foreach ($variant in $order) {
        $sequence++
        $runs.Add((Invoke-BenchmarkRun -Variant $variant -ServerPath $variants[$variant] -Repetition $repetition -Sequence $sequence))
    }
}

$stockValues = @($runs | Where-Object variant -eq "stock" | ForEach-Object averageMspt | Sort-Object)
$candidateValues = @($runs | Where-Object variant -eq "candidate" | ForEach-Object averageMspt | Sort-Object)
function Get-Median {
    param([double[]]$Values)
    if ($Values.Count % 2 -eq 1) {
        return $Values[[int][Math]::Floor($Values.Count / 2)]
    }
    return ($Values[$Values.Count / 2 - 1] + $Values[$Values.Count / 2]) / 2.0
}
$stockMedian = Get-Median -Values $stockValues
$candidateMedian = Get-Median -Values $candidateValues
$improvement = if ($stockMedian -eq 0.0) { 0.0 } else { (($stockMedian - $candidateMedian) / $stockMedian) * 100.0 }
$report = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    entityCount = $EntityCount
    warmupTicks = $WarmupTicks
    measureTicks = $MeasureTicks
    repetitions = $Repetitions
    damageIntervalTicks = $DamageIntervalTicks
    damageHitsPerTarget = $DamageHitsPerTarget
    damageAmount = $DamageAmount
    stockJar = $stockPath
    stockSha256 = (Get-FileHash -LiteralPath $stockPath -Algorithm SHA256).Hash.ToLowerInvariant()
    candidateJar = $candidatePath
    candidateSha256 = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
    pluginJar = $pluginPath
    pluginSha256 = (Get-FileHash -LiteralPath $pluginPath -Algorithm SHA256).Hash.ToLowerInvariant()
    stockMedianMspt = $stockMedian
    candidateMedianMspt = $candidateMedian
    improvementPercent = $improvement
    runs = @($runs)
}
$reportPath = Join-Path $sessionPath "report.json"
Write-Utf8File -Path $reportPath -Content ($report | ConvertTo-Json -Depth 8)
Write-Host "Report: $reportPath"
Write-Host ("Median MSPT: stock={0:N3}, candidate={1:N3}, improvement={2:N2}%" -f $stockMedian, $candidateMedian, $improvement)
