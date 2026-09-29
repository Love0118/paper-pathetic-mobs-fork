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

Official 26.3 changes are retained alongside the ported ZVS optimizations.

## Client HUD and resource pack

Use the updated ZVS plugin from
[`spear-vs-zombie` revision `0368113`](https://github.com/Love0118/spear-vs-zombie/commit/0368113f45b012ef780f55ba522c1a1173f48c84)
on its `26.3-zvs` branch. This plugin update is required separately from the
server fork: it generates format-specific 26.2/26.3 overlays, ports the HUD text
shaders to 26.3 ShaderC/OIT, and converts the removed model shading field while
preserving the base 26.2 models. The original generated pack was limited to
format 88 and did not select a HUD shader overlay on 26.3.

The plugin follow-up passed 803 tests and static analysis, native 26.2/26.3 pack
metadata parsing, 96 custom shader compilations against 96 vanilla controls,
4,157 native model parses, and 215 native shading-field checks. These are
compiler/parser checks; final in-game HUD rendering and FPS remain unmeasured.
See the [resource-pack compatibility report](https://github.com/Love0118/spear-vs-zombie/blob/0368113f45b012ef780f55ba522c1a1173f48c84/docs/RESOURCE_PACK_26_3.md)
for reproducible commands and delivery checksums. No server runtime changes were
needed for this follow-up, so the server JAR and performance results below remain
unchanged.

## Verification

Full Gradle build: 481 API tests (2 skipped), 10,133 server tests (86 skipped),
3 checkstyle tests; no failures/errors. The server suite includes the inherited
ZVS regressions and new particle-axis/mode/directed-particle tests, block
traversal equivalence and A-star frontier ordering. In total, 10,529 tests
executed successfully and 88 were skipped. Counts are retained in
`artifacts/test-results.json`.

The regenerated feature patches also passed a complete `applyPatches` replay.

Disposable real-server probes pass in both `hybrid` and `trusted` modes:
armor/resistance/absorption parity, ordered damage deltas, per-mode event
cadence, synchronous death, trusted spawn suppression and untagged fallback.
Hybrid retains the global death event; trusted mode substitutes the dedicated
handler. Both probes were rerun successfully on the final delivery JAR;
`artifacts/runtime-smoke.json` records its revision, checksum and results.

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

## Performance investigation

The initial six-run port comparison measured 103.53 ms/tick for the 26.2 fork
versus 105.51 for the initial 26.3 port (1.91% higher). These measurements are
retained in `artifacts/benchmark-initial-port.json`.
Every measured run kept 10,000 entities and executed 100,000 explicit path
requests plus 400,000 damage requests; hybrid mode dispatched 100,000 events.

JFR allocation samples identified movement block-intersection scratch sets as
a recurring allocation site. The additional `zvs-block-intersections` gate
reuses a per-thread set only for tagged mobs, falling back to fresh storage for
reentrant calls. Traversal, block/fluid effects and ordering remain unchanged;
storage is cleared even on exceptions/early exits, and oversized sets are
discarded. This is an optimization of an existing vanilla path, not a claim
that block intersection was newly introduced in 26.3.

The second short comparison measured 97.34 versus 102.65 ms/tick (5.46% higher),
retained in `artifacts/benchmark-block-scratch.json`. JFR CPU samples also
identified the A-star frontier's chained comparator as a hot path. The final
implementation uses direct score/heuristic ordering with the same `Double.compare`
and `Integer.compare` semantics. An interleaved 10,000-node regression compares
the exact dequeue identity sequence against the legacy comparator, including
equal priorities and floating-point boundaries.

The short comparisons use Microsoft Java 25.0.3, a fixed 4 GiB heap, 100 warmup ticks,
200 measured ticks, JFR profile capture and ZombieVsSpear 2.4.0. Runs alternate
26.2/26.3, 26.3/26.2, 26.2/26.3. Values are medians of run-average MSPT, not
per-tick percentiles. Both versions emitted the host's existing OSHI/Windows
Perflib lookup warning during startup; neither had watchdog stalls in that gate.

### Final build: longer-running workload

The final runtime revision is `d4af8cd`. Each fresh server received 500 warmup
ticks and 500 measured ticks. This lets the same synthetic encounter advance
further than the short runs; it is a different measurement window, not an
isolated attribution of gains to one code change.

| Repetition | 26.2 ZVS ms/tick | 26.3 ZVS ms/tick |
| --- | ---: | ---: |
| 1 | 261.25 | 233.90 |
| 2 | 250.97 | 224.58 |
| 3 | 249.14 | 233.59 |
| Median of run averages | **250.97** | **233.59** |

The final build reduced median MSPT by **6.93%** in this comparison. All six
measured phases retained 10,000 mobs and each processed exactly 1,000,000 damage
requests, 250,000 hybrid damage events and 250,000 explicit path requests.
The complete measurements and artifact hashes are in
`artifacts/benchmark-final-long.json`.

### Final build: original short workload recheck

The same final JAR was then compared with the original 100-warmup/200-measure
window, again alternating order across three repetitions:

| Repetition | 26.2 ZVS ms/tick | 26.3 ZVS ms/tick |
| --- | ---: | ---: |
| 1 | 94.35 | 86.17 |
| 2 | 91.12 | 90.96 |
| 3 | 95.19 | 91.28 |
| Median of run averages | **94.35** | **90.96** |

Median MSPT decreased by **3.59%**. All six measured phases retained 10,000 mobs,
with 400,000 damage requests, 100,000 hybrid damage events and 100,000 explicit
path requests per run. See `artifacts/benchmark-final-short.json`. These small
local samples demonstrate improvement in the tested workloads, not a guarantee
of the same percentage in every arena. The short and long windows are not
combined into a single aggregate. Neither final series had post-startup server
errors or watchdog stalls.

Performance comparisons must use the same JVM, heap, plugin, synthetic world,
entity count and fixed path/damage request counts. Headless results cannot
establish client rendering quality, player-network latency or actual arena
performance. Existing production worlds are not upgraded by the test harness.

Reproduce the final comparison using the existing accepted Minecraft EULA:

```powershell
./tools/zvs-headless-ab-benchmark.ps1 `
  -StockJar E:/bench/paper-26.2-build121-zvs-beta.jar `
  -CandidateJar ./artifacts/paper-26.3-build135-zvs.jar `
  -PluginJar E:/bench/ZombieVsSpear-2.4.0.jar `
  -OutputDirectory E:/bench/zvs-26.3-comparison `
  -EntityCount 10000 -WarmupTicks 500 -MeasureTicks 500 -Repetitions 3 `
  -DamageIntervalTicks 20 -DamageHitsPerTarget 4 -Heap 4G -AcceptEula
```

The harness's `stock` label denotes the existing optimized **26.2 ZVS fork**,
not official unmodified Paper. `candidate` denotes this 26.3 ZVS migration.
Both variants use the same ZombieVsSpear plugin JAR. The generated reports
record server/plugin SHA-256 hashes, request counts, survivors and JFR hashes;
`artifacts/SHA256SUMS` identifies the local migration build artifacts.

For the short-window recheck, use `-WarmupTicks 100 -MeasureTicks 200`.
The reference 26.2 JAR's hash was also checked against the live server's existing
JAR and matches. Its embedded runtime revision is `a640fd9`; the following
`7c530dba84` commit updates only the checksum manifest.
