# PulseNet 26.2 Behavioral Reference Matrix

Reference: PulseNet Fabric `1.1.0+26.2`, commit
`f65faa13210ca193e2686d9a640c4bfb9d73393c`.

The Paper implementation is independent code. This matrix records both the
reference behavior and the equivalent Paper path; it does not treat a config
name or an unverified metric as completed optimization work.

## Batching and dispatch

| Behavior | Paper 26.2 implementation | Verification |
|---|---|---|
| `smart_execution` | Tick-end drain, with early packet-count, actual outbound-byte, and critical-packet drains | Count, byte, FIFO, and barrier tests |
| `strict_tick` | Normal traffic waits for tick end; semantic barriers still drain immediately | Tick hold and barrier tests |
| `interval` | Per-connection event-loop timer; tick-end flush does not defeat the selected interval | Timer, ordering, and close tests |
| Packet limit | Default 1,024 writes per physical flush | Forced-flush test |
| Byte limit | Default 32,000 minus a 64-byte safety margin, measured from the real Netty outbound-buffer increase after encoding | Byte-limit test |
| Write queue | One event-loop task drains an ordered burst instead of one task per off-thread packet | Burst/task and re-entrant-order tests |

The pinned PulseNet revision declares `currentBatchBytes` but never increments
it, and its unconditional tick-tail flush also runs in interval mode. Those are
reference defects, not behavior reproduced here. Paper measures encoded
outbound-buffer growth and keeps interval mode independent from tick draining.

## Classification and bypasses

| Class | Paper behavior |
|---|---|
| Login/configuration/status/handshake | Never enters the PLAY queue |
| Terminal protocol, keepalive, disconnect, player position | Ordered critical barrier |
| Player hurt/damage and block-entity data | Configurable ordered immediate path; enabled by default |
| Full chunk-with-light and existing bundle | Ordered barrier |
| Infrastructure/custom payload channels | `register`/`unregister` plus configurable instant and ignored channel sets |
| Off-thread sends | Configurable ordered immediate path; cannot overtake queued writes |
| Chat/resource-pack | Configurable ordered immediate path |
| Ordinary caller `flush=true` | Latency request folded into the batch, not misclassified as a semantic barrier |

Ignored and instant packet lists accept simple or fully-qualified class names.
All direct/immediate paths first preserve FIFO order; an event-loop send cannot
overtake a packet already queued by the tick thread.

## Effect coalescing

The reference wraps particles and sounds in `BundlePacket`. Paper's outbound
bundle unpacks to delimiter + N packets + delimiter before normal encoding, so
copying that mechanism would add two protocol packets without reducing encoder
writes. This fork implements the useful behavior instead:

- Identical positive-count particle packets in one ordered effect segment merge
  by summing their counts.
- Identical positional/entity sounds retain one packet with the strongest
  volume.
- Packets with a channel listener or packet finish listener never merge.
- Critical or non-effect packets end the coalescing segment, so effects cannot
  move across ordering boundaries.
- The plugin aggregates and budgets effects before they reach `Connection`;
  the queue is a second duplicate-removal layer, not a substitute for source
  reduction.

Metrics separately report logical requests, actual channel writes, and effect
packets removed. No fake “one bundle equals one physical write” accounting is
used.

## Dense block changes

Paper already emits one section-update packet for multiple changes in a section.
The fork therefore replaces updates with a full chunk-with-light snapshot only
when all of these are true:

- the feature is enabled;
- the per-chunk change count meets the configurable threshold (default 512);
- the conservative incremental payload estimate is at least the current chunk
  section payload plus a configurable 16 KiB allowance for heightmaps, block
  entities, and lighting.

The decision happens in `ChunkHolder`, before per-player block-update packet
allocation. This preserves PulseNet's dense-change option without blindly
turning a smaller section update into a larger chunk resend.

## Framing and recipient lookup

- Complete uncompressed frames use retained slices instead of payload copies.
- Exclusively owned output buffers with sufficient headroom receive their
  VarInt prefix in place; shared, sliced, composite, wrapped, or read-only
  buffers use the normal copy fallback.
- Explosion packet recipients are obtained from Moonrise's maintained nearby
  player index and still pass vanilla's unchanged 64-block distance check.

These safe framing and explosion-recipient optimizations remain independently
configurable rather than disappearing when PLAY write batching is disabled.

## Metrics and remaining release gates

Metrics are disabled by default to avoid per-packet atomic contention. When
enabled they include logical packets, channel writes, actual outbound bytes,
write tasks/avoided tasks, queue depth, critical/count/byte/batch-end flushes,
coalesced effects, chunk replacements, retained/copied frame bytes, and
in-place/copied prefixes.

Unit coverage exists for ordering, re-entrancy, close rejection, all three
modes, count/byte limits, classification, particle merging, mass-update cost
selection, and framing fallbacks. ProtocolLib transformation, real login and
compression transitions, paranoid Netty leak detection, and 1/4/16-client
canaries remain release gates and are not claimed complete.
