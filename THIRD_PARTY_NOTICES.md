# Third-party notices

The checked-in Gradle wrapper scripts and JAR are generated Gradle tooling, licensed
under Apache License 2.0. Script copyright/license headers are retained; the wrapper
JAR contains its own notices. Source: https://github.com/gradle/gradle, version 9.7.1.
License: https://www.apache.org/licenses/LICENSE-2.0

Fabric Loader/API, Fabric Language Kotlin, Minecraft libraries, and optional Mod Menu
are resolved by Gradle/installed separately, not embedded in the gameplay JAR.
No external fonts, sounds, or artwork are bundled.

## Local QR generation

Project Nayuki's QR Code generator 1.8.0 (`io.nayuki:qrcodegen`) is bundled under
the MIT license. Source: https://github.com/nayuki/QR-Code-generator/tree/v1.8.0.
The license is in `LICENSES/LICENSE_qrcodegen` and packaged under `META-INF/licenses`.
It generates the linking QR locally; no QR service receives login links.
ZXing core 3.5.4 (Apache-2.0) is used only in tests to decode the generated QR,
not shipped: https://github.com/zxing/zxing.

## Dungeon tracking

The timer and solo-room implementation and regression tests were extracted from
the owner's MithrilAddons project. Split boundaries, boss bounds, ping selection,
room-core hashing, map calibration and participant recognition derive from
[NoammAddons](https://github.com/Noamm9/NoammAddons/tree/624065809db7c70ef7125cbe8ceb4a66f0c565e0),
by Noamm9 and contributors, under CC0-1.0. The standalone score calculation is
adapted from that revision's `ScoreCalculation.kt` and `DungeonListener.kt`.
The license is in `LICENSES/LICENSE_noamm` and packaged under `META-INF/licenses`.

`dungeon-splits.json` and `solo-rooms.json` are the same reduced factual snapshots
used by MithrilAddons (Noamm 1.2.6, September 2026). Room data contains names,
types, hashes and secret counts, not player data or secret routes. No Noamm,
SkyHanni or Skyblocker code is loaded or reflected into at runtime. Original
Mithril code retains its existing All-Rights-Reserved declaration.
