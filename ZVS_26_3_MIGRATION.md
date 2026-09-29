# ZVS Paper 26.3 migration

Target branch: `26.3-zvs`.

The migration starts from official Paper 26.3 build 135 (BETA), commit
`abfdaed87a695450cdaa5711ec52833b3ce8997d`. The compatibility reference is this
fork's 26.2 `beta` commit `7c530dba84`, based on Paper 26.2 build 121.
Implementation and local verification use AI coding assistance. This is an
independent ZVS fork, not an official Paper distribution.

## Scope and compatibility

The 26.2 opt-in contracts remain: scoreboard-tag gates, internal bridge API v2,
ordered synchronous damage and death, stock fallbacks for unsupported path
requests, and immediate protocol/critical-packet barriers. Existing
`optimizations` configuration keys and defaults are retained.

| 26.2 feature | 26.3 migration |
| --- | --- |
| Pathetic flat-ground navigation, shared cells and reverse fields | Port with section-revision invalidation |
| Hybrid/trusted damage, spawn and death hooks | Adapt to current damage/event implementation |
| Managed mob AI cadence | Keep navigation and controls at full rate |
| PLAY write queue and effect coalescing | Preserve FIFO and completion listeners |
| Retained decoding / frame prefix | Retain ownership checks and copy fallbacks |
| Per-viewer entity network LOD | Preserve absolute recovery and critical updates |
| Dense block updates / explosion recipient scan | Preserve packet and distance semantics |

Official 26.3 changes are retained instead of replacing its source with 26.2
files. The separate `26.3-mud` presentation engine is outside this migration.
Any additional optimization with new behavior requires user approval.

## Verification

Full Gradle build: 481 API tests (2 skipped), 10,130 server tests (86 skipped),
3 checkstyle tests; no failures/errors. The server suite includes the inherited
ZVS regressions and new particle-axis/mode/directed-particle tests.

Disposable real-server probes pass in both `hybrid` and `trusted` modes:
armor/resistance/absorption parity, ordered damage deltas, per-mode event
cadence, synchronous death, trusted spawn suppression and untagged fallback.
Hybrid retains the global death event; only trusted mode substitutes the
dedicated handler. Initial probe failures exposed fixture assumptions (incidental
spawn events and a trusted-only callback assertion in hybrid mode), not a reason
to change this existing contract.

Historical 26.2 results in `ZVS_BENCHMARK.md` are not measurements of this branch.

Build on Java 25:

```text
gradlew applyPatches
gradlew build createPaperclipJar
```

Run the disposable-server probe (requires an existing accepted EULA file):

```powershell
python tools/zvs-migration-smoke.py --server-jar paper-server/build/libs/paper-paperclip-26.3.build.135-local.jar --api-jar paper-api/build/libs/paper-api-26.3.build.135-local.jar --eula-file E:/accepted/eula.txt --output E:/bench/zvs-migration-smoke
```

## Approved 26.3 particle experiment

The client now accepts independent axis speeds and uniform-offset modes. The
user approved an additional optimization experiment. Review of 26.3 client
`ClientPacketListener.handleParticleEvent` established an exact conversion for
duplicate directed particles: `count=0` sends one particle with velocity
`axisOffset * axisSpeed` (float multiplication). One `ALTERNATIVE` packet with
zero offsets, those velocities and count N produces the same N spawn arguments.
The regression fixture merges 12 identical packets into one (91.7% fewer writes
for that burst), retaining position, velocity, particle data and limiter flags.
This is a packet-fixture result, not a claim of 91.7% savings across a ZVS match.

The change is implemented in the fork coalescer, so existing ZVS plugin calls
can benefit without rebuilding the plugin. Distinct beam/ring positions are not
approximated or merged. No in-game rendering/FPS measurement is claimed.

Performance comparisons must use the same JVM, heap, plugin, synthetic world,
entity count and fixed path/damage request counts. Headless results cannot
establish client rendering quality, player-network latency or actual arena
performance. Existing production worlds are not upgraded by the test harness.
