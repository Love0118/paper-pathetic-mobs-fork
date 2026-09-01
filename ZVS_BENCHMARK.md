# ZVS late-round benchmark

Use the same world, plugin data, player count, client positions, JVM, heap, and
round seed for every A/B run. Run each variant in a separate directory so a
previous server's generated configuration or world state cannot contaminate
the comparison.

## Required inputs

- The candidate Paperclip jar and the matching ZombieVsSpear plugin jar.
- A copied arena/world and plugin configuration in the run directory.
- One, four, or sixteen fixed load clients connected before the warmup ends.
- Explicit acceptance of the Minecraft EULA through the script switch.

Example:

```powershell
.\tools\zvs-benchmark.ps1 `
  -ServerJar .\artifacts\paper-26.2-121-zvs.jar `
  -PluginJar "E:\projects\zombie vs spear\target\ZombieVsSpear-1.12.5.jar" `
  -RunDirectory E:\bench\zvs-combined-4p `
  -Round 50 -WarmupSeconds 60 -CaptureSeconds 180 -AcceptEula
```

The harness requests the selected debug round, captures JFR with the `profile`
settings, prints `/zvs metrics` before and after the capture, shuts the server
down, and records the JFR SHA-256. Keep client scripts deterministic and archive
their input trace next to the JFR.

## A/B order

Start with stock Paper build 121, then enable one fork feature per run in
`config/paper-global.yml` under `optimizations`:

1. `pathetic-mob-pathfinding`
2. `zvs-managed-damage`
3. plugin combat-effect frame
4. `zvs-play-network`
5. `zvs-entity-network-lod`
6. all features combined

For every run record p50/p95/p99 MSPT, ticks over 50/100 ms, allocation and GC,
path result counters, managed damage events skipped, effect logical/emitted
counts, PLAY logical/physical writes and flushes, outbound bytes, and LOD
sent/skipped counts. A feature is retained only when its isolated run improves
the late-round bottleneck without correctness or visual regressions.

## Rollback

Set the affected `enabled` field to `false` and restart. Disabling all four
server-side ZVS sections restores the upstream execution paths. Keep an
unmodified build 121 jar beside the candidate for immediate binary rollback.
