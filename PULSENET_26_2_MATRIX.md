# PulseNet 26.2 Behavioral Reference Matrix

Reference: PulseNet Fabric `1.1.0+26.2`
commit `f65faa13210ca193e2686d9a640c4bfb9d73393c`.

This document defines behavior to reproduce independently in Paper 26.2. It is
not an instruction to copy Fabric mixins or source structure. A Paper feature is
complete only when its behavior and safeguards are covered by Paper-side tests.

## Batching modes

| Behavior | Reference | Paper implementation gate |
|---|---|---|
| Smart execution | Tick flush plus count/byte safety limits | Tick and limit tests |
| Strict tick | Hold normal PLAY packets until tick flush | Latency and disconnect tests |
| Interval | Timer-driven flush, reference default 25 ms | Ordering and shutdown tests |
| Maximum packet count | Reference default 1,024 | Forced-flush test |
| Maximum buffered bytes | Reference default 32,000 bytes | Encoded-size/estimate test |

Smart execution is the initial Paper default candidate. Strict-tick and interval
modes remain planned so behavior is not silently omitted.

## Write dispatch

- Queue ordinary PLAY packets on the server tick thread.
- Submit one Netty event-loop task per drained batch.
- Write packets without per-packet flush and flush the channel once.
- Preserve FIFO packet order and each packet's channel future listener.
- Fall back to Paper's direct path when batching is disabled.
- Drain or fail pending entries deterministically when a connection closes.

## Classification and bypasses

| Class | Required behavior |
|---|---|
| Login/configuration/status/handshake | Never enter the PLAY batch |
| Terminal protocol packets | Flush prior data, then send immediately |
| Keepalive/disconnect | Critical immediate path |
| Player hurt/damage | Immediate by default |
| Full chunk-with-light | Flush prior data, then direct send |
| Existing bundle packet | Flush prior data, then direct send |
| Infrastructure custom payload | Immediate configurable-channel path |
| Off-thread send | Direct by default, independently configurable |
| Chat | Immediate by default, independently configurable |

Paper does not need Fabric registration-channel special cases verbatim, but it
must provide an equivalent critical custom-payload mechanism for plugin and
proxy handshakes.

## Packet coalescing

- Coalesce particle, positional sound, and entity sound packets only after ZVS
  source-level effect aggregation.
- Wrap compatible packets in clientbound bundle packets.
- Split before the vanilla 4,096-subpacket hard limit; the reference default is
  4,000.
- Never reorder coalesced effects across critical packets.
- Retain per-player packet instances where plugins can transform output.

Bundling reduces physical writes and framing overhead, not the number of logical
effects the client executes. Logical and physical PPS are reported separately.

## Explosion block updates

- Group block updates by chunk.
- Above a configurable per-chunk threshold, compare section/chunk resend cost
  with individual updates before selecting the replacement.
- The PulseNet reference threshold is 512 block changes.
- This is lower priority for ZVS than combat traffic but remains in the parity
  matrix.

## Metrics parity

- Logical and physical PPS.
- Outbound bytes and estimated batching savings.
- Packets entering the write queue.
- Netty event-loop tasks submitted and avoided.
- Flush reason and batch-size histograms.
- Coalesced packet and bundle counts.
- Count/byte/interval/tick/critical flush reasons.
- Current queue depth and pending bytes.

Metrics must fail open: reporting failures cannot interrupt packet delivery.

## Additional Paper safety gates

- Test `ChannelFutureListener` success and failure ordering.
- Test connection close while a batch is queued.
- Test compression enabled/disabled and protocol transitions.
- Test custom payload transformation and ProtocolLib interception.
- Test Netty leak detection at paranoid level in the focused suite.
- Keep a master toggle and separate toggles for write queue, flush
  consolidation, coalescing, and explosion replacement.
