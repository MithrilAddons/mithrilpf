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
directory. The gameplay artifact is `build/libs/mithrilpf-0.1.0.jar`; the sources
JAR is not installable. No installer, updater or automatic publication is included.
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
