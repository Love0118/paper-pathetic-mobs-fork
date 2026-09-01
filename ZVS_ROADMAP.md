# ZVS Paper 26.2 Optimization Roadmap

This repository is a dedicated Paper 26.2 build 121 fork for the
ZombieVsSpear workload. It is based on upstream Paper commit
`a2a42c5b12249aaba42a347327fd930a1f94af06` and tracks the 26.1.2
`paper-pathetic-mobs-fork` through the read-only `mobopt` remote for design
reference. PulseNet behavior is pinned to Fabric `1.1.0+26.2` commit
`f65faa13210ca193e2686d9a640c4bfb9d73393c` through the `pulsenet` remote.

The fork optimizes only paths that are measurable in late ZombieVsSpear rounds.
Every server change must be independently switchable, preserve a stock Paper
fallback, and expose counters that prove whether its fast path is used.

## Non-negotiable invariants

- Entity and Bukkit state mutations remain on the owning tick thread.
- Login, configuration, disconnect, keepalive, and player combat packets retain
  their ordering and latency guarantees.
- Vanilla navigation remains available for unsupported terrain and entities.
- Optimizations are restricted to explicitly managed ZVS entities whenever
  vanilla or plugin-visible semantics would otherwise change.
- Disabling every ZVS option returns execution to the upstream Paper path.
- Each milestone is benchmarked independently before optimizations are combined.

## Milestone 0: Reproducible baseline

Create a buildable Paper 26.2 patch workspace, record the pinned upstream
revision, and capture stock results for the workloads below.

- 2,000 and 5,000 ground mobs moving toward a fixed objective.
- Round 50+ scripted combat with area damage, damage-over-time, and turrets.
- One, four, and sixteen tracking players.
- Burst spawn and burst death of managed mobs.

Required measurements are p50/p95/p99 MSPT, ticks over 50/100 ms, path requests,
event counts and time, logical and physical packets per second, flushes, Netty
event-loop tasks, outbound bytes, allocation rate, and garbage collection.

## Milestone 1: Shared 2D navigation

Implement the mandatory navigation fast path in layers:

1. Strict eligibility and safe upstream fallback.
2. Straight-line and short orthogonal-detour checks on flat ground.
3. A world navigation revision that changes when relevant blocks change.
4. Shared 2D route caching keyed by objective cell and navigation revision.
5. A reverse flow field for fixed objectives such as the core.
6. Cached local routes for moving player objectives.
7. Per-reason counters for direct, detour, shared, and vanilla fallback paths.

Stairs, jumps, drops, fluids, flying, amphibious navigation, and unsafe nodes
must stay on the upstream Paper path. The first implementation may use the
Pathetic fast path from the reference fork while the shared flow-field layer is
built and measured.

## Milestone 2: Managed combat event pipeline

Add a trusted path for ZVS-owned entities without changing global Bukkit event
dispatch.

- Collect programmatic attacks as ordered damage requests.
- Apply requests sequentially so armor, absorption, resistance, kill credit,
  and contribution accounting remain deterministic.
- Replace per-hit Bukkit damage events with one ZVS batch hook when trusted mode
  is enabled.
- Coalesce mob hurt animation and damage notification to once per entity/tick.
- Batch managed spawn/death bookkeeping while preserving the compatibility path.
- Keep direct player damage and non-ZVS damage on normal Paper events.

Compatibility, hybrid, and trusted modes must be selectable independently.

## Milestone 3: Combat effect frames

Reduce logical packets before they reach Netty.

- Aggregate compatible particles by tick, spatial bucket, particle data, and
  recipient group.
- Deduplicate compatible sounds by tick, spatial bucket, category, and pitch
  bucket while preserving the strongest observation.
- Coalesce last-write-wins HUD, metadata, equipment, velocity, and display
  transform updates where semantics allow it.
- Enforce per-player budgets and distance tiers instead of only a world-global
  call limit.

Wrapping packets in a bundle is not considered source reduction; the number of
logical effects must also be measured.

## Milestone 4: PLAY network batching

Use PulseNet Fabric `1.1.0+26.2` as the behavioral reference and implement the
same feature set independently against Paper 26.2 internals. Licensing affects
how the implementation is produced, not which planned features are delivered.
No PulseNet behavior is omitted solely because its source cannot be copied.

- Queue normal PLAY writes and submit them through one event-loop task.
- Consolidate tick flushes with count and byte safety limits.
- Preserve packet order and every channel future listener.
- Bundle already-reduced particle and sound packets.
- Bypass buffering for protocol transitions and critical packets.
- Add zero-copy frame decoding and in-place frame-prefix writing only after the
  write/flush path passes protocol and leak tests.

The implementation must be original Paper code while retaining behavioral and
metrics parity with the selected PulseNet 26.2 reference revision.

## Milestone 5: ZVS entity network LOD

Throttle managed mob tracking per receiving player, not globally.

- Full-rate updates for nearby, attacking, boss, and special entities.
- Reduced movement/update cadence for medium and far entities.
- Immediate spawn, removal, teleport, equipment, and critical state changes.
- Promotion to full rate before a mob can affect a player.
- Metrics by distance tier and packet type.

Exact distance and cadence values are selected from round 50+ captures rather
than hard-coded from assumptions.

## Milestone 6: Validation and release

Run Paper tests, feature tests, deterministic workload replays, ProtocolLib
compatibility tests, Netty leak detection, and one-feature-at-a-time A/B runs.
Release artifacts must record the upstream revision, patch revision, enabled
defaults, test summary, and SHA-256 checksum. A stock Paper jar and feature
toggle rollback procedure must accompany every candidate build.
