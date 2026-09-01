# ZVS optimization configuration

All server options are under `optimizations` in `config/paper-global.yml`.
They are read at startup; restart after changing them. The defaults below are
the beta defaults used to build the candidate jar.

## `pathetic-mob-pathfinding`

- `enabled: true`
- `marker-tag: zvs_managed`
- `reverse-flow-field-build-after-requests: 4`
- `reverse-flow-field-max-cells: 4096`
- `reverse-flow-field-cache-entries: 64`

Only tagged, exact flat `WalkNodeEvaluator` requests use the 2D engine. Unsupported
terrain, vertical movement, fluids, other evaluators, and pre-evaluation
rejections use upstream Paper. Once 2D evaluates the world, its bounded result
is authoritative; vanilla is not run a second time. A blocked search returns
the closest discovered node as `reached=false`, matching vanilla partial-path
behavior. Successful fixed-objective routes merge into one reverse next-hop
field without a synchronous Dijkstra build or a duplicate suffix cache. Block
changes invalidate only overlapping dependency sections.

## `zvs-managed-damage`

- `enabled: true`
- `marker-tag: zvs_managed`
- `event-mode: compatibility`
- `coalesce-hurt-status: true`
- `trusted-spawn-events: true`
- `trusted-death-handler: true`

`compatibility` dispatches normal Bukkit events. `hybrid` dispatches the first
managed damage event per target/tick. `trusted` skips managed damage events and
uses the owner-checked registered plugin death handler. Trusted hits bypass
damage-event and modifier-map allocation after vanilla damage modifiers have
been calculated. Only main-thread calls for tagged
entities enter this path; all other calls fall back to Bukkit. Use `trusted`
only when every plugin that relies on per-hit Bukkit events has been audited;
the server logs a warning when suppression is first used. The versioned
`@ApiStatus.Internal` contract is `io.papermc.paper.zvs.ZvsOptimization`
(`API_VERSION = 2`).

## `zvs-play-network`

- `enabled: false`
- `max-packets-per-flush: 1024`
- `max-estimated-bytes-per-flush: 0`
- `metrics-enabled: false`
- `zero-copy-decoding: true`
- `in-place-frame-prefix: true`

The queue is active only in PLAY when explicitly enabled. Login and
configuration remain on upstream paths. A caller's normal `flush=true` request
is folded into the end of the current drained burst and is not an ordering
barrier. Protocol/latency-critical packets remain barriers. Every logical
packet still performs its normal encoder/channel write; the removed
`ClientboundBundlePacket` path did not reduce physical writes because Paper's
unbundler expanded it again. Metrics therefore report actual channel writes,
not bundle groups. The heuristic byte limit is disabled by default because
encoded size is unknown at classification time. Safe in-place framing
automatically falls back to a copy for shared, wrapped, composite, sliced, or
read-only buffers.

## `zvs-entity-network-lod`

- `enabled: false`
- `marker-tag: zvs_managed`
- `near-distance: 32`
- `medium-distance: 64`
- `medium-interval: 2`
- `far-interval: 4`
- `max-recovery-ticks: 20`
- `metrics-enabled: false`
- `full-rate-tag: zvs_lod_full`

Only relative movement and head rotation of tagged mobs are throttled per
viewer. Cadence counts actual `ServerEntity` emissions per entity/viewer, so it
cannot starve through a tick-modulus GCD. Every permitted thinned movement is
an absolute position sync, and a final skipped update receives a bounded
absolute recovery even if the mob stops moving. A near/full-rate transition
after skipped deltas also starts with an absolute sync. Spawn, removal,
teleport, equipment, velocity, metadata, and other critical state remain
immediate. The controller and its per-viewer identity map are allocated lazily
only after both the feature and marker tag opt in; the default-disabled and
untagged paths do not run recovery scans or allocate LOD state.

## Plugin effect frame

The matching ZombieVsSpear plugin aggregates particles and sounds at the end of
the tick, applies per-player 32/64/96-block tiers, and keeps only the last health
display or managed-mob name update for a tick. `/zvs metrics` prints logical,
emitted, and reduced effect counts plus all fork metric snapshots.

## Rollback

Set the relevant `enabled` option to `false` and restart. To return completely
to upstream behavior, disable all four server sections and deploy an
unmodified Paper build 121 jar. Keep `event-mode: compatibility` during canary
testing unless the plugin event audit has been completed.
