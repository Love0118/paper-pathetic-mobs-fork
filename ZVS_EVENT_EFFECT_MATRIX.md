# ZVS Event and Effect Reduction Matrix

This matrix defines which late-round repetitions are safe to serialize or
coalesce. Direct player actions and protocol-critical state are deliberately
kept outside the optimized paths.

| Repetition source | Current handling | Ordering / fallback rule |
| --- | --- | --- |
| Programmatic ability, DOT, splash, and turret damage | Marker-gated ordered managed batch; compatibility, hybrid, or trusted event mode | Every request still enters `hurtServer` sequentially; direct attacks and rejected bridge calls use normal Bukkit dispatch |
| Repeated managed hurt status | First status per target/tick in hybrid or trusted mode | Damage, armor wear, absorption, knockback, attribution, and death are never coalesced |
| Game-mob custom spawn | Trusted internal spawn context | Missing bridge or disabled setting calls normal `World.spawn` and `CreatureSpawnEvent` |
| Managed synchronous death | Owner-checked dedicated ZVS handler in trusted mode | Missing handler, non-managed death, or compatibility/hybrid mode uses `EntityDeathEvent`; a second owner cannot silently replace the first |
| Turret muzzle/trail/impact particles | End-of-tick spatial frame; compatible counts merge | Directional count-zero particles and cinematic particles remain immediate |
| Turret, explosion, and managed impact sounds | End-of-tick spatial/pitch frame; strongest volume wins | UI, BGM, player-only cues, and distinct pitch buckets remain separate |
| Per-player cosmetic delivery | Near 32 blocks full rate, medium 64 blocks half cadence, far 96 blocks quarter cadence | Separate near/medium/far particle and sound budgets; effects outside 96 blocks are cosmetic drops |
| HUD/display metadata, equipment, and velocity | Not yet coalesced | Only last-write-wins fields may be reduced; critical state remains immediate |
| Entity movement/tracking | Emission-count LOD with absolute movement and bounded recovery | Spawn, removal, teleport, equipment, attack, boss, and soon-visible promotion remain immediate |
| PLAY socket writes and flushes | FIFO event-loop queue and end-of-burst flush consolidation | Login/configuration/keepalive/disconnect and protocol transitions bypass batching; normal caller flushes are not barriers |

`CombatEffectFrame` exposes logical request, merge, drop, and emitted per-tier
packet counters. These counters are the source-reduction baseline that must be
captured before protocol bundles or Netty write batching are enabled.
