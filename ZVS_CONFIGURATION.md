# ZVS optimization configuration

All server options are under `optimizations` in `config/paper-global.yml` and
are read at startup. Restart after changing them. These are beta-workload
defaults, not claims that the remaining multiplayer release gates have passed.

## `pathetic-mob-pathfinding`

- `enabled: true`
- `marker-tag: zvs_managed`
- `metrics-enabled: false`
- `reverse-flow-field-build-after-requests: 4`
- `reverse-flow-field-max-cells: 16384`
- `reverse-flow-field-cache-entries: 16`
- `shared-cell-cache-entries: 65536`

Only tagged, exact flat `WalkNodeEvaluator` requests use the 2D engine.
Unsupported terrain, vertical movement, fluids, other evaluators, and
pre-evaluation rejections use upstream Paper. Once 2D evaluates the world, its
bounded result is authoritative and vanilla is not run a second time. A blocked
search returns the closest discovered node as `reached=false`. Successful and
partial fixed-objective routes merge incrementally into one reverse next-hop
field without a second synchronous Dijkstra build, full-map copying, or a
duplicate suffix cache. Partial terminals retain `reached=false`, use remaining
target distance as their gradient, and are isolated by caller range. Evaluated
cells are shared by structural mob profile and invalidated by overlapping
section revisions. Metrics are off by default to avoid hot-path counters.

## `zvs-managed-damage`

- `enabled: true`
- `marker-tag: zvs_managed`
- `event-mode: hybrid`
- `coalesce-hurt-status: true`
- `trusted-spawn-events: true`
- `trusted-death-handler: true`
- `metrics-enabled: false`

`compatibility` dispatches every Bukkit damage event. `hybrid` dispatches the
first managed damage event per target/tick and sends later same-tick managed
hits through the allocation-light arithmetic path. `trusted` uses that path for
every managed hit. The fast path avoids modifier maps, modifier functions, and
`EntityDamageEvent` allocation while retaining armor, magic, resistance,
absorption, durability, attribution, invulnerability, and death processing.

Hybrid and trusted modes intentionally suppress some global event-bus
observations. The server logs a warning on first use. Use `compatibility` if an
anti-cheat, protection, logging, or combat plugin must inspect every hit. Only
main-thread bridge calls for tagged entities are eligible; every other call
falls back to Bukkit. Trusted death dispatch uses an owner-checked handler. The
versioned internal API is `io.papermc.paper.zvs.ZvsOptimization`
(`API_VERSION = 2`).

## `zvs-managed-mob-ai`

- `enabled: true`
- `marker-tag: zvs_managed`
- `full-rate-tag: zvs_ai_full`
- `selector-interval: 4`
- `full-rate-target-distance: 12.0`

For tagged mobs without a nearby live target, sensing, target-selector, and
goal-selector work is phased so each mob runs it every fourth tick. Navigation,
movement, look, jump, and custom AI still tick every tick. A target within 12
horizontal blocks or the `zvs_ai_full` tag restores exact full-rate selector
execution. This intentionally changes far managed-mob reaction cadence and is
therefore tag-gated; disable it if that tradeoff is unsuitable.

## `zvs-play-network`

- `enabled: true`
- `batching-mode: smart_execution`
- `flush-interval-millis: 25`
- `max-packets-per-flush: 1024`
- `max-batch-bytes: 32000`
- `safety-margin-bytes: 64`
- `write-queue: true`
- `off-thread-bypass: true`
- `chat-bypass: true`
- `packet-coalescing: true`
- `max-coalesced-packets: 4000`
- `mass-block-update-chunk-resend: true`
- `mass-block-update-threshold: 512`
- `mass-block-update-chunk-safety-bytes: 16384`
- `metrics-enabled: false`
- `zero-copy-decoding: true`
- `in-place-frame-prefix: true`

`smart_execution` drains at tick end or an early count/byte/critical limit.
`strict_tick` holds ordinary traffic until tick end. `interval` uses its Netty
event-loop timer and is not silently converted to tick mode. Login and
configuration stay on upstream paths. A normal caller `flush=true` is folded
into the batch; protocol and latency-critical packets are ordered barriers.
Packet and custom-channel instant/ignored lists are configurable.

The byte limit uses the real increase in Netty pending outbound bytes after the
packet is encoded, rather than PulseNet's unused byte counter or a class-name
estimate. Particles and sounds are actually deduplicated before encoding; no
`ClientboundBundlePacket` write reduction is claimed because Paper expands a
bundle back into delimiter + N packets + delimiter. Dense block updates become
a chunk snapshot only after both the count threshold and a conservative byte
cost comparison pass.

Metrics remain off by default so `LongAdder`/CAS accounting is not paid on every
packet. Safe retained-frame decoding and in-place prefixing have automatic copy
fallbacks for unsupported buffer ownership/layout.

## `zvs-entity-network-lod`

- `enabled: true`
- `marker-tag: zvs_managed`
- `near-distance: 32`
- `medium-distance: 64`
- `medium-interval: 2`
- `far-interval: 4`
- `max-recovery-ticks: 20`
- `metrics-enabled: false`
- `full-rate-tag: zvs_lod_full`

Only movement and head rotation of tagged mobs are thinned per viewer. Cadence
counts actual `ServerEntity` emissions, so it cannot starve through a tick
modulus GCD. Every permitted thinned movement is an absolute position sync; a
final skipped update gets bounded recovery even after movement stops. Near,
targeting, boss, and full-rate-tagged mobs are promoted. Spawn, removal,
teleport, equipment, velocity, metadata, and critical state remain immediate.
Controller/viewer maps are allocated lazily only for eligible tagged entities.
No no-client benchmark result is evidence for this feature; it still requires
one/four/sixteen-client visual validation.

## `explosion-broadcast-optimization`

- `enabled: true`

The recipient scan uses Moonrise's maintained nearby-player index, then applies
the unchanged vanilla 64-block distance test. It does not alter explosion
physics or recipient eligibility.

## Plugin effect frame

The matching ZombieVsSpear plugin routes high-frequency trails, beam/burst,
projectile, meteor, status-hit, sweep/bleed, ground-impact, shooting-star, and
sunfire effects through a tick-local frame. It aggregates particles/sounds,
applies per-player distance budgets, and coalesces display/name state.
`/zvs metrics` exposes source logical/emitted/reduced counts and fork snapshots.

## Rollback

Disable `pathetic-mob-pathfinding`, `zvs-managed-damage`, `zvs-managed-mob-ai`,
`zvs-play-network`, `zvs-entity-network-lod`, and
`explosion-broadcast-optimization`, then restart.
Also disable the nested framing and dense-update toggles if a fully upstream
network path is required. Keep `event-mode: compatibility` wherever every-hit
Bukkit observation is required.
