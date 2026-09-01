# ZVS Paper 26.2 Implementation Checklist

Last updated: 2026-09-01

Update this file immediately after a task is implemented and verified. A task is
not complete merely because code exists; its stated test or measurement must
also pass.

## 0. Repository and baseline

- [x] Clone official Paper into the dedicated workspace.
- [x] Pin and record Paper 26.2 STABLE commit
  `a2a42c5b12249aaba42a347327fd930a1f94af06`.
- [x] Configure `upstream` for Paper and `mobopt` for the 26.1.2 reference fork.
- [x] Create the `beta` implementation branch.
- [x] Add the roadmap and verification-based checklist.
- [x] Apply the upstream Paper patch stack successfully (931 source patches,
  6 resource patches, and all feature patches).
- [x] Run the unmodified baseline `gradlew build` successfully (31 tasks,
  including API/server tests and checks).
- [ ] Add a reproducible late-round benchmark harness and capture stock results.

## 1. Shared 2D navigation — mandatory

- [x] Audit the Paper 26.2 pathfinder/navigation call sites and the 26.1.2
  Pathetic feature patch.
- [x] Add a configuration master toggle, enabled by default, with stock fallback.
- [x] Add eligibility, rejection-reason, result, evaluation, and exhaustion counters.
- [x] Implement safe straight-line ground paths.
- [x] Implement bounded orthogonal detours up to eight cells.
- [x] Integrate a bounded synchronous Pathetic 2D fallback for eligible ground mobs.
- [x] Prevent eligible 2D searches from invoking vanilla after any 2D world
  evaluation; verify handled-no-path dispatch with a regression test.
- [x] Add navigation revision invalidation for relevant block changes.
- [x] Implement shared fixed-objective route-suffix caching.
- [ ] Implement a bounded reverse flow field for dense fixed-objective traffic.
- [x] Implement exact-target cached moving-objective route suffixes.
- [ ] Add unit tests for flat, blocked, hazardous, vertical, fluid, and fallback
  cases.
- [x] Add and pass eligibility, range, target-candidate, path-type, evaluation
  budget, rejection-reason, and metrics unit tests.
- [x] Reapply the generated feature patch and pass the full Paper `gradlew build`.
- [x] Reapply the handled-result/cache feature patch and pass the full Paper
  `gradlew build` (31 tasks, 2026-09-01).
- [ ] Pass 2,000/5,000-mob correctness and performance smoke tests.

## 2. Managed damage, spawn, and death pipeline

- [x] Add explicit ZVS-managed entity/source markers without changing Paper API.
- [x] Add an ordered tick-local damage batch entry point.
- [x] Preserve sequential armor, absorption, resistance, attribution, and death
  semantics.
- [x] Add compatibility, hybrid, and trusted event modes.
- [x] Add one server-internal batch hook for trusted programmatic damage.
- [x] Coalesce managed mob hurt/damage notification once per entity/tick.
- [x] Add trusted managed spawn/death paths with compatibility fallback.
- [ ] Test direct damage, area damage, damage-over-time, turret contribution,
  absorption overflow, and synchronous death.
- [x] Pass ordered batch, marker fallback, hybrid event, hurt-status coalescing,
  trusted-death-handler, and the plugin's 521-test verification suite on Paper
  API 26.2.

## 3. Combat effect frames

- [x] Add tick-local particle aggregation by data, position bucket, and recipient.
- [x] Add sound deduplication by sound, pitch bucket, position, and strongest volume.
- [x] Add per-player distance tiers and adaptive effect budgets.
- [ ] Add last-write-wins coalescing for safe HUD/display/entity state updates.
- [ ] Measure logical packet reduction before adding network bundles.
- [x] Add logical request, merge, drop, and emitted packet counters and pass the
  plugin's 523-test plus PMD verification suite.

## 4. PLAY network batching

- [x] Pin PulseNet Fabric `1.1.0+26.2` commit
  `f65faa13210ca193e2686d9a640c4bfb9d73393c` as the reference revision.
- [x] Record the PulseNet 26.2 behavior/configuration matrix in
  `PULSENET_26_2_MATRIX.md`.
- [ ] Independently implement every selected PulseNet 26.2 behavior; do not omit
  planned functionality solely because source code cannot be copied.
- [ ] Add packet classification with critical and protocol-transition bypasses.
- [ ] Add a per-connection PLAY write queue.
- [ ] Submit queued writes in one Netty event-loop task.
- [ ] Consolidate flushes with packet-count and byte limits.
- [ ] Preserve packet order and channel future listeners in tests.
- [ ] Bundle reduced particle and sound packets within the client limit.
- [ ] Add logical/physical PPS, write-task, flush, and byte metrics.
- [ ] Add zero-copy complete-frame decoding with retained-buffer tests.
- [ ] Add in-place frame prefixing with shared/sliced-buffer fallback tests.
- [ ] Pass login, configuration, compression, disconnect, custom payload, and
  ProtocolLib compatibility tests.

## 5. Managed mob network LOD

- [ ] Add per-player near/medium/far update tiers for ZVS-managed mobs.
- [ ] Promote attacking, boss, special, and soon-visible mobs to full rate.
- [ ] Keep spawn, removal, teleport, equipment, and critical state immediate.
- [ ] Add packet-type and distance-tier metrics.
- [ ] Validate one/four/sixteen-player visual and combat behavior.

## 6. Release gates

- [ ] Run the full relevant Paper test suite.
- [ ] Run one-feature-at-a-time round 50+ A/B benchmarks.
- [ ] Run the combined-feature soak and Netty leak test.
- [ ] Document every default, fallback, known tradeoff, and rollback command.
- [ ] Produce a checksummed candidate Paperclip jar.
- [ ] Complete a canary run before changing production defaults.
