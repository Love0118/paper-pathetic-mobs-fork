# ZVS Paper 26.2 Implementation Checklist

Last updated: 2026-09-02

Update this file immediately after a task is implemented and verified. A task is
not complete merely because code exists; its stated test or measurement must
also pass.

## 0. Repository and baseline

- [x] Clone official Paper into the dedicated workspace.
- [x] Pin and record Paper 26.2 build 121 STABLE commit
  `a2a42c5b12249aaba42a347327fd930a1f94af06`.
- [x] Configure `upstream` for Paper and `mobopt` for the 26.1.2 reference fork.
- [x] Create and work on the requested `26.2` implementation branch.
- [x] Add the roadmap and verification-based checklist.
- [x] Apply the upstream Paper patch stack successfully (931 source patches,
  6 resource patches, and all feature patches).
- [x] Run the unmodified baseline `gradlew build` successfully (31 tasks,
  including API/server tests and checks).
- [x] Add a reproducible round-50+ JFR benchmark harness with pre/post fork
  metric snapshots and fixed A/B ordering.
- [x] Capture superseded deterministic headless baselines at 2,000 mobs
  (median 11.84 -> 11.21 ms, 5.32%) and 5,000 mobs (28.26 -> 27.61 ms,
  2.30%), with all entities alive; mark them pre-A-H-fix and non-release.
- [ ] Capture stock build-121 results with the production arena and load clients.
- [x] Run the canonical 0040 candidate in six fresh 8 GiB processes at 10,000
  tagged zombies. Each measured run executed 100,000 fixed-objective path
  requests and 400,000 damage requests with all entities alive: stock median
  298.23 MSPT, candidate median 96.60 MSPT, 67.61% lower. Stock emitted 400,000
  damage events and candidate hybrid mode emitted 100,000.

## 1. Shared 2D navigation — mandatory

- [x] Audit the Paper 26.2 pathfinder/navigation call sites and the 26.1.2
  Pathetic feature patch.
- [x] Add a configuration master toggle plus mandatory `zvs_managed` tag gate,
  with stock fallback before any 2D world evaluation.
- [x] Add eligibility, rejection-reason, result, evaluation, and exhaustion counters.
- [x] Implement safe straight-line ground paths.
- [x] Implement bounded orthogonal detours up to eight cells.
- [x] Integrate bounded 2D A* for eligible ground mobs and return the closest
  discovered node as a vanilla-compatible `reached=false` partial path.
- [x] Prevent eligible 2D searches from invoking vanilla after any 2D world
  evaluation; verify handled-no-path dispatch with a regression test.
- [x] Replace level-wide block-change invalidation with overlapping section
  revision snapshots.
- [x] Replace the duplicate exact-route cache plus synchronous Dijkstra flow
  build with one demand-triggered reverse next-hop field accumulated from
  successful and vanilla-compatible partial paths; cache population performs
  no additional world evaluation.
- [x] Merge reverse fields incrementally instead of copying the full cell map
  per path, preserve reached/partial terminals, use a target-distance gradient,
  and isolate partial fields by caller range.
- [x] Add a section-revision-validated shared evaluated-cell cache and cache
  collision-safe structural path profiles per mob until dimensions, floor, or
  malus revision changes.
- [x] Replace the 32-bit profile identity with collision-safe structural
  equality over entity type, dimensions, evaluator flags, and malus values.
- [x] Add unit tests for flat, blocked, hazardous, vertical, fluid, and fallback
  cases.
- [x] Add and pass eligibility, range, target-candidate, path-type, evaluation
  budget, rejection-reason, and metrics unit tests.
- [x] Reapply the generated feature patch and pass the full Paper `gradlew build`.
- [x] Reapply the handled-result/cache feature patch and pass the full Paper
  `gradlew build` (31 tasks, 2026-09-01).
- [x] Add reverse-flow construction/cycle tests and pass the full 31-task Paper
  build (2026-09-01).
- [x] Add partial-path, unrelated-section retention, and concurrent route-merge
  regressions; regenerate 0035 and pass a clean `applyPatches`.
- [x] Pass final-candidate 2,000/5,000-mob alternating A/B smoke tests with all
  entities alive and JFR hashes present: median 7.85 -> 7.56 MSPT (3.69%) and
  24.24 -> 23.87 MSPT (1.53%), respectively.
- [x] Pass the final 10,000-mob fixed-objective gate with 100,000 equal path
  requests per measured run. A metrics-on diagnostic recorded 12,625 reverse
  field hits while primitive-long maps and integer coordinate lookups removed
  the observed `Long`, `HashMap.TreeNode`, and `PathPosition` allocation sites.

## 2. Managed damage, spawn, and death pipeline

- [x] Add explicit ZVS-managed entity/source markers without changing Paper API.
- [x] Add an ordered tick-local damage batch entry point.
- [x] Preserve sequential armor, absorption, resistance, attribution, and death
  semantics.
- [x] Add compatibility, hybrid, and trusted event modes.
- [x] Add one server-internal batch hook for trusted programmatic damage.
- [x] Move trusted damage onto an allocation-light `LivingEntity` path before
  `EnumMap`, modifier-function, and `EntityDamageEvent` construction.
- [x] Coalesce managed mob hurt/damage notification once per entity/tick.
- [x] Add trusted managed spawn/death paths with compatibility fallback.
- [x] Test direct damage, area damage, damage-over-time, turret contribution,
  absorption overflow, and synchronous death.
- [x] Pass ordered batch, marker fallback, hybrid event, hurt-status coalescing,
  trusted-death-handler, absorption overflow, and the plugin's 527-test
  verification suite on Paper API 26.2 build 121.
- [x] Route multi-target area attacks through the ordered fork batch bridge with
  transparent per-request Bukkit fallback on stock Paper.
- [x] Add versioned `@ApiStatus.Internal` API v2, provider installation, event
  suppression warning, and owner-checked death-handler registration.
- [x] Verify the final 10,000-mob gate executes exactly 400,000 damage requests
  per run; hybrid mode reduces observed `EntityDamageEvent` dispatch from
  400,000 to 100,000 without changing the 10,000-entity survivor count.

## 3. Combat effect frames

- [x] Add tick-local particle aggregation by data, position bucket, and recipient.
- [x] Add sound deduplication by sound, pitch bucket, position, and strongest volume.
- [x] Add per-player distance tiers and adaptive effect budgets.
- [x] Add last-write-wins coalescing for health-display and managed-mob custom
  name state updates.
- [ ] Measure logical packet reduction before adding network bundles.
- [x] Add logical request, merge, drop, and emitted packet counters and pass the
  plugin's 527-test plus PMD verification suite on Paper API build 121.
- [x] Add `/zvs metrics` for effect logical/emitted/reduced counts and fork-side
  pathfinding, damage, PLAY network, and entity-LOD snapshots.
- [x] Route the plugin's high-frequency beam/burst, projectile, meteor,
  status-hit, sweep/bleed, ground-impact, shooting-star, and sunfire effects
  through the tick-local effect frame; pass all 527 plugin tests.

## 4. PLAY network batching

- [x] Pin PulseNet Fabric `1.1.0+26.2` commit
  `f65faa13210ca193e2686d9a640c4bfb9d73393c` as the reference revision.
- [x] Record the PulseNet 26.2 behavior/configuration matrix in
  `PULSENET_26_2_MATRIX.md`.
- [x] Audit the pinned PulseNet implementation, including its unused byte
  counter, tick flush in interval mode, and Paper-incompatible bundle accounting.
- [x] Independently implement smart, strict-tick, and true interval modes; do
  not omit planned functionality solely because source code cannot be copied.
- [x] Add packet classification with critical and protocol-transition bypasses.
- [x] Add a per-connection PLAY write queue.
- [x] Submit queued writes in one Netty event-loop task.
- [x] Consolidate flushes with packet-count and actual Netty outbound-byte limits.
- [x] Preserve packet order and channel future listeners in tests.
- [x] Remove the ineffective `ClientboundBundlePacket` grouping after verifying
  Paper's unbundler expands it back into delimiter + N packets + delimiter.
- [x] Add optional logical/channel-write, write-task, flush-reason, actual
  outbound-byte, effect-coalescing, dense-update, and framing metrics; disable
  per-packet metrics by default.
- [x] Replace ineffective protocol bundles with allocation-light duplicate
  particle/sound merging before encoding, while preserving listener and barrier
  boundaries.
- [x] Add dense per-chunk block-update replacement before packet allocation,
  guarded by both the PulseNet-compatible count threshold and a conservative
  section-update versus chunk-snapshot byte comparison.
- [x] Add zero-copy complete-frame decoding with retained-buffer tests.
- [x] Add in-place frame prefixing with shared/sliced-buffer fallback tests.
- [x] Reapply the 0037 feature patch and pass 10 focused network tests plus the
  full 31-task Paper build (2026-09-01).
- [x] Separate caller flush requests from semantic barriers, route event-loop
  sends through the FIFO queue, and add overtake/reentrant-order regressions.
- [x] Enable the PLAY queue in this ZVS beta profile while keeping one master
  rollback toggle, configurable bypasses, and metrics off by default.
- [ ] Pass login, configuration, compression, disconnect, custom payload, and
  ProtocolLib compatibility tests.

## 5. Managed mob AI cadence

- [x] Add a mandatory `zvs_managed` tag gate and `zvs_ai_full` opt-out tag.
- [x] Phase far/targetless managed mobs across every fourth selector emission
  while keeping navigation, movement, look, jump, and custom AI ticking every tick.
- [x] Restore full-rate sensing and selectors within 12 horizontal blocks of a
  live target.
- [x] Add phase/distance regressions and pass the full Paper test suite.
- [x] Verify the selector cadence plus path/damage pipeline in the final
  10,000-mob A/B gate; this is an intentional tagged gameplay tradeoff, not a
  claim of vanilla-equivalent selector timing.

## 6. Managed mob network LOD

- [x] Add tracker-local per-player near/medium/far state for ZVS-managed mobs.
- [x] Promote player-attacking, boss, explicitly special, and near/soon-visible
  mobs to full rate.
- [x] Count actual `ServerEntity` emissions rather than absolute tick moduli;
  every thinned movement uses absolute position and final skipped deltas receive
  bounded recovery even after movement stops.
- [x] Add movement/head-rotation and distance-tier metrics.
- [x] Reapply the 0038 feature patch and pass focused cadence tests plus the full
  31-task Paper build (2026-09-01).
- [x] Add absolute-packet reuse, exact Nth-emission, final recovery, and cleanup
  regressions. Enable LOD only behind the mandatory `zvs_managed` tag gate in
  this beta profile; visual canary remains a release gate.
- [x] Allocate LOD controller/viewer maps lazily only for enabled, tagged
  entities and skip empty recovery scans. Confirm removal of the default-off
  `IdentityHashMap.init`/`flushRecoveries` JFR hotspots and recover a transient
  5,000-mob -4.15% regression to a final +1.53% result.
- [ ] Validate one/four/sixteen-player visual and combat behavior.

## 7. Release gates

- [x] Pass the post-A-H-fix 31-task build and clean canonical 0035-0038 patch
  reapply for the earlier candidate (2026-09-01); superseded by the current
  networking/damage/effect expansion.
- [x] Pass the latest Paper `NormalTestSuite` and all 527 plugin tests before
  canonical patch regeneration (2026-09-01).
- [x] Regenerate networking/damage as canonical 0039 and managed AI/path-profile
  NMS work as canonical 0040; pass a clean 931-source/6-resource/40-feature
  `applyPatches` (2026-09-02).
- [x] Run the full current Paper `gradlew build`, including bad-call scanning,
  checks, and all suites (31 tasks, 2026-09-02).
- [x] Smoke-start the rebuilt Paperclip with ZombieVsSpear in all six final A/B
  processes and read the fork snapshots; no-client network/LOD counters remain
  intentionally excluded from performance claims.
- [x] Repeat the deterministic no-client A/B gate on the rebuilt jar at 10,000
  mobs with equal path/damage counters and archived JFR hashes.
- [ ] Run one-feature-at-a-time round 50+ A/B benchmarks.
- [ ] Run the combined-feature soak and Netty leak test.
- [x] Document every default, fallback, known tradeoff, and rollback procedure.
- [x] Replace the superseded Paperclip candidate and SHA-256 only after the
  current canonical patch, full build, smoke start, and benchmark gates pass.
- [ ] Complete a canary run before changing production defaults.
