# ZVS optimization configuration

All server options are under `optimizations` in `config/paper-global.yml`.
They are read at startup; restart after changing them. The defaults below are
the beta defaults used to build the candidate jar.

## `pathetic-mob-pathfinding`

- `enabled: true`
- `shared-route-cache-entries: 16384`
- `shared-route-max-path-nodes: 256`
- `reverse-flow-field-build-after-requests: 4`
- `reverse-flow-field-max-cells: 4096`
- `reverse-flow-field-cache-entries: 64`

Only exact flat `WalkNodeEvaluator` requests use the 2D engine. Unsupported
terrain, vertical movement, fluids, other evaluators, and pre-evaluation
rejections use upstream Paper. Once 2D evaluates the world, its bounded result
is authoritative; vanilla is not run a second time. Block changes invalidate
route and flow-field caches.

## `zvs-managed-damage`

- `enabled: true`
- `marker-tag: zvs_managed`
- `event-mode: compatibility`
- `coalesce-hurt-status: true`
- `trusted-spawn-events: true`
- `trusted-death-handler: true`

`compatibility` dispatches normal Bukkit events. `hybrid` dispatches the first
managed damage event per target/tick. `trusted` skips managed damage events and
uses the registered plugin death handler. Only main-thread calls for tagged
entities enter this path; all other calls fall back to Bukkit. Use `trusted`
only when every plugin that relies on per-hit Bukkit events has been audited.

## `zvs-play-network`

- `enabled: true`
- `max-packets-per-flush: 1024`
- `max-estimated-bytes-per-flush: 32768`
- `bundle-effects: true`
- `max-effect-bundle-packets: 4000`
- `zero-copy-decoding: true`
- `in-place-frame-prefix: true`

The queue is active only in PLAY. Login and configuration remain on upstream
paths. Keepalive, disconnect, custom payload, damage, chunks, chat, teleport,
removal, existing bundles, and caller-requested flushes are barriers. Safe
in-place framing automatically falls back to a copy for shared, wrapped,
composite, sliced, or read-only buffers.

## `zvs-entity-network-lod`

- `enabled: true`
- `marker-tag: zvs_managed`
- `near-distance: 32`
- `medium-distance: 64`
- `medium-interval: 2`
- `far-interval: 4`
- `full-rate-tag: zvs_lod_full`

Only relative movement and head rotation of tagged mobs are throttled per
viewer. Near mobs, Withers, Ender Dragons, mobs targeting that viewer, and mobs
with `zvs_lod_full` remain full rate. Spawn, removal, absolute teleport,
equipment, velocity, metadata, and other critical state remain immediate.
Throttled relative movement is replaced by an absolute resync when its cadence
allows transmission.

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
