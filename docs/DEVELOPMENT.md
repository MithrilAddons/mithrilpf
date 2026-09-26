# Development

MithrilPF is a standalone Fabric client for Minecraft 26.1.2. Required dependencies
are Fabric Loader, Fabric API and Fabric Language Kotlin; Mod Menu is optional.
No Noamm, SkyHanni or MithrilAddons dependency is required.

## Build and contribute

Use JDK 25 and Python 3.14. Import the Gradle project in IntelliJ with JDK 25.
Use the checked-in wrapper, not Maven or a system Gradle installation.

```text
gradlew.bat spotlessApply
python tools/check.py
gradlew.bat runClient
```

Linux: use `sh gradlew`. The development client uses the disposable `run/`
directory. The gameplay artifact is `build/libs/mithrilpf-<mod_version>.jar`; the sources
JAR is not installable. No installer or updater is included.
See [CONTRIBUTING.md](../CONTRIBUTING.md) and [TESTING.md](TESTING.md).

Keep Kotlin feature logic separate from thin Java mixins. Client state belongs
to the client thread; bounded workers handle disk/network work. Tests use fakes
and temporary storage, never real Minecraft sessions or user configuration.
Use the active game font and the shared Palette constants; no bundled fonts.
Public documentation is limited to this guide, testing, contribution rules and
required license notices. Plans and historical verification notes stay local.

## Use and storage

`/mithrilpf` opens the menu; its Controls keybind starts unbound. Escape returns
to gameplay. Mod Menu can also open settings.
Browser linking proves ownership through Mojang and opens an HTTPS confirmation
page. Only Mojang receives the Minecraft access token. A status-only receipt is
saved per account in instance-local `config/mithrilpf/link.json`; it cannot log
into the website or upload records by itself.
The linking screen defaults to Open browser. "Use another browser or phone" reveals
Copy link, a locally generated QR and a short code for `mithril.foo/link` when the
backend supports it. Refresh link appears on expiry/failure, not alongside a valid
link. Switching views keeps the same pending sign-in. Links expire after five minutes and require browser
confirmation. Keep the screen open until confirmed. Cancelling or expiring a new
attempt leaves the previous saved connection intact. Never share codes or QR screenshots.

## Versions and milestone releases

`mod_version` in `gradle.properties` is the single version source for Fabric metadata,
Mod Menu and JAR filenames. While pre-1.0, use `0.MINOR.PATCH`: increment MINOR for
feature milestones and PATCH for fixes. Use `-alpha.N`, `-beta.N` or `-rc.N` for test
builds; this implementation starts the `0.2.0-rc.1` milestone. Do not reuse a version
for a published artifact. After 1.0, increment MAJOR for breaking changes, MINOR for
compatible features, PATCH for fixes. API/config versions are separate and must not
be bumped just because the mod version changes.

Milestone releases are deliberate, not created for every commit. After a version
bump PR is reviewed and merged into main, create and push a signed tag matching
the version exactly (for example `v0.2.0`). The Release workflow validates the tag,
main ancestry and JAR metadata, runs the full Windows/Linux checks, and creates a
**draft** GitHub release with the tested Linux-built gameplay JAR, SHA-256 checksum
and generated notes. Prerelease tags are marked accordingly. A maintainer reviews
the notes and performs the manual tests before publishing the draft. Never move an
existing release tag; fix forward with a new version. Local untagged builds do not
create releases, and uploading artifacts to Modrinth is not part of this workflow.

Dungeon tracking provides split/real-tick timers, room clear/secrets PBs, F7/M7
solo 300-score PBs, run history and estimated finish times. `/mithrilpfpbs` shows
bests; `/mithrilpfstatus` shows detection/storage status. The HUD editor supports
dragging, scaling and synthetic previews without creating records. Enable the
Paul +10 score setting before a run when applicable.

Solo attempts require an observed solo roster; a teammate or death invalidates
the attempt. Room timers count only time spent inside that room, including the
clear time in the secrets total. Normal/master floors remain separate.
Estimates use completed five-player runs with less than 20 seconds of lag and
require three samples; M7 has fallback estimates before then. Unknown/missing
observations do not create fabricated PBs. Room/map or server-format changes can
require detector updates. Records are observations, not anti-cheat attestations.

Settings, per-account PBs and run history live under `config/mithrilpf/`.
Malformed/newer settings are not overwritten; unknown fields survive edits.
PB files discourage casual manual edits but are not tamper-proof. Back up this
directory to retain records. No old MithrilAddons data is imported automatically.

## Linked services

The mod syncs solo-clear and terminal PBs using a fresh Mojang proof and a separate
15-minute upload credential. Only best timings are uploaded, not room records or
full run history. Browser logout revokes uploads but does not delete saved records.
Sync is bounded, retries outages and separates accounts. SS tracking is not implemented.

Party handoff uses a separate 30-day presence credential; it cannot upload records
or log into the browser. All five must be online before one automatic invite round.
`/mithrilpfreinvite` explicitly retries missing players. Complete English
`/party list` replies confirm membership. Conflicting game parties stop invites;
the mod never kicks, disbands or leaves automatically. These flows still need
multi-account runtime testing. The canonical protocol lives in the web repository's
[API guide](https://github.com/MithrilAddons/web/blob/main/docs/API.md).
