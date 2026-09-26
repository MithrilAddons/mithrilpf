# Development

MithrilPF is a standalone Fabric client for Minecraft 26.1.2. Required dependencies
are Fabric Loader, Fabric API and Fabric Language Kotlin; Mod Menu is optional.
No Noamm, SkyHanni or MithrilAddons dependency is required.

Original MithrilPF code is licensed under [MIT](../LICENSE).
Third-party licenses are documented in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)
and included in the gameplay JAR.

## Install the beta

1. Use Minecraft **26.1.2**, Java **25**, and Fabric Loader **0.19.3 or newer**.
2. Install Fabric API **0.154.2+26.1.2 or newer for this Minecraft version** and
   Fabric Language Kotlin **1.13.12+kotlin.2.4.0 or newer** in the instance's `mods` folder.
3. Download the gameplay JAR from the
   [GitHub releases](https://github.com/MithrilAddons/mithrilpf/releases), remove
   any older MithrilPF JAR from `mods`, and put the new one there. Do not install
   the sources JAR. Restart Minecraft.
4. Open `/mpf` (or `/mithrilpf`), choose Link browser, then use
   [the party finder](https://mithril.foo/party-finder). Another browser or phone
   can be linked using the QR/link/code options.

`/mpc <message>` chats with your linked party on the website and in Minecraft.
This beta is for feedback: five-player handoff and the new in-game chat still
need broader real-game testing. SS tracking is not included. Party listings and
chat are temporary and reset when the backend restarts; linked accounts and PBs
are stored separately. Back up `config/mithrilpf/` before trying new builds.

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
JAR is not installable. The updater is disabled for development/class-directory launches.
See [CONTRIBUTING.md](../CONTRIBUTING.md) and [TESTING.md](TESTING.md).

Keep Kotlin feature logic separate from thin Java mixins. Client state belongs
to the client thread; bounded workers handle disk/network work. Tests use fakes
and temporary storage, never real Minecraft sessions or user configuration.
Use the active game font and the shared Palette constants; no bundled fonts.
Public documentation is limited to this guide, testing, contribution rules and
required license notices. Plans and historical verification notes stay local.

## Use and storage

`/mithrilpf` (or `/mpf`) opens the menu; its Controls keybind starts unbound. Escape returns
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

## Automatic updates

Open `/mpf → Updates`. Auto-update defaults on; **Include pre-releases** defaults
off. Each game launch checks GitHub; Check now retries at most once per minute.
Checks/downloads run on a background worker. No GitHub login or Minecraft token
is sent. With pre-releases off, only the latest published stable release is
considered. With it on, the newest 50 published releases are searched by semantic
version (up to five newer candidates inspected). Drafts, equal/older versions,
missing checksums and incompatible Minecraft/dependency requirements are rejected.
Turning either toggle off cancels an in-progress/staged update as appropriate;
it never downgrades an already-installed beta.

Downloads are restricted to this repository's HTTPS GitHub release URLs and
GitHub's release-asset host, with redirect, size and time limits. The SHA-256
digest comes from GitHub's release API. This protects integrity but is **not**
an independent signature or protection against a compromised GitHub publisher.
The updater never updates Minecraft, Fabric, Kotlin or any other mod.

A ready update is installed only after quitting Minecraft, by a tiny JDK-only
helper extracted from the running mod. It uses the same Java installation, waits
up to two minutes for the exact parent process to exit, then rechecks both old and
new hashes. The previous JAR is retained under
`config/mithrilpf/updates/backups/<sha256>.jar.backup`. Replacement is atomic;
unsupported filesystems, locks or changed files leave the installed JAR alone.
The existing JAR filename is deliberately preserved to avoid duplicate-mod or
missing-mod windows; its internal version changes. Wait a few seconds after quit
before relaunching. No PowerShell, registry changes, startup service or administrator
rights are used. Symlinked/nonstandard installations require manual updates.

Settings are in `config/mithrilpf/updates.json`; malformed/newer schemas are
not overwritten and disable installation. Staging and helper logs are confined to
`config/mithrilpf/updates/job-*`. Crashes may leave unused staging folders; they
are not automatically installed on the next launch. Backups are retained until
you remove them. To roll back with Minecraft closed, disable auto-update in
updates.json, copy a backup over the installed MithrilPF JAR and relaunch.
The first updater-enabled build must be installed manually; older releases cannot
gain this feature without an update.

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

`/mpc <message>` (also `/mithrilpfchat <message>`) sends to the linked Mithril party,
including its website members. Replies appear as `[Mithril Party] Name: message`.
Normal chat and `/pc` remain Hypixel-only; neither is copied to the relay. Messages
are plain text, never gameplay commands. The existing scoped party credential is
reused, with no new sign-in. Chat remains available after the Minecraft handoff.

The first connection displays at most the newest ten retained messages. Temporary
network reconnects resume from the last received message. History is in server
memory only (last 100 messages); restart/disband/last-member departure removes it.
A failed send offers a click-to-prefill retry, never an automatic resend. Retries
reuse an ID for just under ten minutes. Accounts/parties are isolated, stale replies
are discarded, and network work runs on bounded background workers. No chat text
or credentials are saved to mod files or explicitly logged by the mod (Minecraft
may include displayed chat in its own normal client log).
