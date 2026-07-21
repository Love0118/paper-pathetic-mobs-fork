# Third-party notices

## Paper

This project is a fork of [Paper](https://github.com/PaperMC/Paper) and retains
Paper's licensing files and notices. Server patches are distributed under the
applicable GPL terms in this repository.

## Pathetic Mobs integration

The pathfinding integration was adapted from
[biryeongtrain/pathetic-mobs](https://github.com/biryeongtrain/pathetic-mobs),
which is dedicated under CC0-1.0. A copy is provided in
`licenses/PATHETIC-MOBS-CC0-1.0.txt`.

## Pathetic engine and API 5.4.6

The server declares `de.bsommerfeld.pathetic:engine:5.4.6`, which includes the
Pathetic API and its required runtime components. Pathetic is published under
the MIT License. Its MIT notice is provided in `licenses/PATHETIC-MIT.txt`.

Upstream source references used for this fork:

- Paper base: https://github.com/PaperMC/Paper/commit/29c8822d90899c89d2689338e81a98f690bcba12
- Pathetic Mobs: https://github.com/biryeongtrain/pathetic-mobs
- Pathetic 5.4.6: https://github.com/bsommerfeld/pathetic/tree/5.4.6

## PulseNet

No PulseNet source code, packet classification tables, or configuration code is
included. The PLAY-state flush patch is an independent implementation based on
Paper's existing GPL network patch and Netty's public `FlushConsolidationHandler`
API.
