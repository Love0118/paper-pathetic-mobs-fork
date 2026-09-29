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
  -PluginJar "E:\projects\zombie vs spear\target\ZombieVsSpear-1.12.6.jar" `
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
counts, PLAY logical/channel writes and flushes, outbound bytes, and LOD
sent/skipped counts. A feature is retained only when its isolated run improves
the late-round bottleneck without correctness or visual regressions.

## Superseded headless baseline

Before the A-H correctness review, the deterministic no-client harness measured
5.32% lower median MSPT at 2,000 mobs (11.84 to 11.21 ms) and 2.30% at 5,000
mobs (28.26 to 27.61 ms), with all entities alive. These figures are retained
only as a pre-fix reference: the path cache, partial-path, LOD, damage and PLAY
write implementations changed afterward. They are not release evidence; the
final candidate must rerun the same alternating A/B sequence.

## Post-A-H final headless gate

The final candidate was compared with stock Paper 26.2 build 121 in six fresh
processes per load (three runs per variant), using a 4 GiB heap, 1,200 warmup
ticks, 2,400 measured ticks, alternating A/B order, and the same plugin jar.

| Tagged mobs | Stock runs (MSPT) | Candidate runs (MSPT) | Median change |
| ---: | --- | --- | ---: |
| 2,000 | 8.06, 7.58, 7.85 | 7.56, 7.96, 7.34 | 7.85 -> 7.56 (3.69% lower) |
| 5,000 | 24.24, 24.23, 24.37 | 24.18, 23.87, 23.16 | 24.24 -> 23.87 (1.53% lower) |

Every run retained its initial entity count and produced a JFR file with a
recorded SHA-256. An earlier post-review 5,000-mob run regressed by 4.15%; JFR
showed that the default-disabled LOD path was allocating a controller and
`IdentityHashMap` per tracked entity and building an empty recovery set every
tick. Lazy controller creation and an empty-state fast return removed those
hotspots, after which the identical A/B gate produced the result above.

This is a deterministic no-client pathfinding/correctness gate, not evidence
for PLAY batching, entity LOD with viewers, managed damage, or effect-frame
reduction. Those features still require the production arena, fixed load
clients, and one-feature-at-a-time captures described above.

## Canonical 0040 10,000-mob path and damage gate

The release-candidate Paperclip rebuilt from 931 source, 6 resource, and 40
feature patches was compared with stock Paper 26.2 build 121. Six fresh Java
processes used an 8 GiB fixed heap, 100 warmup ticks, 200 measured ticks, and
the order stock/candidate/candidate/stock/stock/candidate. Ten thousand tagged
zombies were kept alive and directed at the same protected objective.

Every measured run executed exactly ten load pulses, 100,000 explicit
`Pathfinder.moveTo` requests, and 400,000 damage requests. This combines the
late-wave shared-objective pathfinding and many-hit event workload instead of
benchmarking idle mobs.

| Variant | Measured runs (MSPT) | Median | Damage events/run | Watchdog dumps |
| --- | --- | ---: | ---: | ---: |
| Stock build 121 | 298.23, 298.27, 285.22 | 298.23 | 400,000 | 3/3 |
| Canonical 0040 candidate | 98.61, 95.90, 96.60 | 96.60 | 100,000 | 0/3 |

The median MSPT reduction is 67.61%. All six runs retained all 10,000 entities,
produced a JFR with a recorded SHA-256, and completed with no Java exception.
Stock's three watchdog dumps were captured in vanilla `WalkNodeEvaluator` /
`PathFinder`; the candidate did not trigger the watchdog.

A metrics-on diagnostic verified that the reverse field was actually used
(12,625 flow-field hits) and that shared evaluated cells prevented repeated
world evaluation. JFR then identified boxed-long `HashMap` nodes and temporary
`PathPosition` objects in the candidate; primitive-long maps and integer cell
lookups removed those allocation sites before the final six-process run.

This gate directly supports the 2D path, managed AI cadence, and damage-event
claims. It has no connected client, so it does not support PLAY write batching,
effect packet coalescing, or per-viewer entity LOD claims. Those release gates
remain open.

## Rollback

Set the affected `enabled` field to `false` and restart. Disabling all
server-side ZVS sections restores the upstream execution paths. Keep an
unmodified build 121 jar beside the candidate for immediate binary rollback.
