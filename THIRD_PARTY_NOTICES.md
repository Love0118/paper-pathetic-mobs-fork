# Third-party notices

## Paper

This project is a fork of [Paper](https://github.com/PaperMC/Paper) and retains
Paper's licensing files and notices.

## Pathetic ground pathfinding

The two-dimensional ground path fast path uses
`de.bsommerfeld.pathetic:engine:5.4.6`, published under the MIT License. Its
notice is provided in `licenses/PATHETIC-MIT.txt`.

The initial Paper integration is derived from the CC0
[pathetic-mobs](https://github.com/biryeongtrain/pathetic-mobs) integration and
the GPL-compatible
[paper-pathetic-mobs-fork](https://github.com/Love0118/paper-pathetic-mobs-fork).
The CC0 dedication is provided in
`licenses/PATHETIC-MOBS-CC0-1.0.txt`.

## PulseNet 26.2 behavioral reference

PulseNet Fabric `1.1.0+26.2` commit
`f65faa13210ca193e2686d9a640c4bfb9d73393c` is used to define the expected
network-batching behavior and measurement matrix. The Paper implementation is
being written independently against Paper and Netty interfaces; the planned
feature set is not reduced because the reference implementation has different
licensing terms.
