# Development

MithrilPF is a standalone Fabric client for Minecraft 26.1.2. Required dependencies
are Fabric Loader, Fabric API, Fabric Language Kotlin and Hypixel's Mod API; Mod Menu
is optional.

Original MithrilPF code is licensed under [MIT](../LICENSE).
Third-party licenses are documented in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)
and included in the gameplay JAR.

## Install the beta

1. Use Minecraft **26.1.2**, Java **25**, and Fabric Loader **0.19.3 or newer**.
2. Install Fabric API **0.154.2+26.1.2 or newer for this Minecraft version**,
   Fabric Language Kotlin **1.13.12+kotlin.2.4.0 or newer** and
   [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) **1.0.2 or newer** in the
   instance's `mods` folder. Automatic updates never install it for you.
3. Download the gameplay JAR from the
   [GitHub releases](https://github.com/MithrilAddons/mithrilpf/releases), remove
   any older MithrilPF JAR from `mods`, and put the new one there. Do not install
   the sources JAR. Restart Minecraft.
4. Open `/mpf` (or `/mithrilpf`) and choose Sign in with Minecraft to use the
   in-game finder. Settings also offers browser linking for
   [the website finder](https://mithril.foo/party-finder), with QR/link/code options.

`/mpc <message>` chats with your linked party on the website and in Minecraft.
SS tracking is not included. Party listings and chat are temporary and reset when
the backend restarts; linked accounts and PBs are stored separately. Back up
`config/mithrilpf/` before trying new builds.

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

## Use and storage

`/mithrilpf` (or `/mpf`) opens the menu; its Controls keybind starts unbound. Escape returns
to the previous screen or gameplay. Mod Menu can also open the menu. Parties shows
authenticated F7/M7 listings, eligibility, class selection, automatic matching and
party creation. Your party contains the roster, handoff, chat and leader controls.
Records separates eligible records from local PBs. Settings contains account controls,
tracking, HUD editing, match sounds, Discord and updates.
Tab moves focus and Enter/Space activates the focused control. Content scrolls when
it does not fit the selected GUI scale.
Wide layouts use independent list and detail panes; compact layouts open details
with a Back action. Reservations always use an explicitly selected class. Leaving,
unlisting and removing members require confirmation. Chat supports Enter to send
and reporting a message with a reason. Requirement fields never submit on Enter.
Successful creation and edits remember requirements and role layouts per account
and floor in `config/mithrilpf/finder.json`. Blocked-player names and chat drafts
are not saved there. Invalid or newer configuration files are kept unchanged.
Sign in with Minecraft creates a separate 30-day mod session using a fresh Mojang
ownership proof. Minecraft access tokens are sent only to Mojang. The mod saves its
credential and scoped-proof receipt per account in `config/mithrilpf/device.json`.
Sign out revokes that session; the website's Manage data page can revoke other
Minecraft sessions. Account deletion revokes all sessions. Browser linking remains
available independently. Tracking and party handoff prefer the native session when
present; otherwise they use the existing browser link.
Browser linking proves ownership through Mojang and opens an HTTPS confirmation
page. Only Mojang receives the Minecraft access token. A status-only receipt is
saved per account in instance-local `config/mithrilpf/link.json`; it cannot log
into the website or upload records by itself.
Linking, record syncing and party authorization each bind fresh client and server
nonces to the account and purpose. The mod computes the proof hash locally and
rejects substituted hashes before contacting Mojang. The backend must support
this nonce exchange; there is no fallback to server-chosen proof hashes.
The linking screen defaults to Open browser. "Use another browser or phone" reveals
Copy link, a locally generated QR and a short code for `mithril.foo/link` when the
backend supports it. Refresh link appears on expiry/failure, not alongside a valid
link. Switching views keeps the same pending sign-in. Links expire after five minutes and require browser
confirmation. Keep the screen open until confirmed. Cancelling or expiring a new
attempt leaves the previous saved connection intact. Never share codes or QR screenshots.

## Versions and releases

`mod_version` in `gradle.properties` is the single version source for Fabric metadata,
Mod Menu and JAR filenames. While pre-1.0, use `0.MINOR.PATCH`: increment MINOR for
feature milestones and PATCH for fixes. Use `-alpha.N`, `-beta.N` or `-rc.N` for test
builds. Do not reuse a version for a published artifact. After 1.0, increment
MAJOR for breaking changes, MINOR for compatible features, PATCH for fixes.
API/config versions are separate and must not be bumped just because the mod
version changes.

After a version bump PR is reviewed and merged into main, create and push a signed
tag matching the version exactly (for example `v0.2.0`). The Release workflow
validates the tag, main ancestry and JAR metadata, runs the full Windows/Linux
checks, and creates a
**draft** GitHub release with the tested Linux-built gameplay JAR, Ed25519 signature, SHA-256 checksum
and reviewed notes from `docs/releases/<mod_version>.md`. Follow the
[announcement standard](RELEASING.md) when preparing that file in the version-bump
PR. Prerelease tags are marked accordingly. A maintainer reviews
the notes and performs the manual tests before publishing the draft. Never move an
existing release tag; fix forward with a new version. Local untagged builds do not
create releases.

### Modrinth publishing

Publishing a GitHub release triggers **Publish to Modrinth**, including prereleases.
It uploads the existing gameplay JAR and release notes, verifies SHA256SUMS and the
official-build marker, and reads Minecraft compatibility from the JAR. Fabric API,
Fabric Language Kotlin and Hypixel Mod API are required dependencies; Mod Menu is optional.
Stable versions use Modrinth's Release channel, `-alpha.N` uses Alpha, and
`-beta.N` / `-rc.N` use Beta. Draft GitHub releases are not uploaded.

Configure the repository Actions secret `MODRINTH_TOKEN` with a Modrinth personal
access token that has Create versions (`VERSION_CREATE`) permission and access to
the project. The optional repository variable `MODRINTH_PROJECT_ID` overrides the
default `mithrilpf` slug. Project review and visibility remain managed by Modrinth.

For a failed upload or an older published release, run **Publish to Modrinth** from
Actions with its exact GitHub tag. A matching existing version is skipped; a
conflicting file or compatibility/channel setting fails without overwriting it.
Uploads require a release with its gameplay JAR and SHA256SUMS still attached.
Publish GitHub drafts through the UI or a maintainer token: events made with a
workflow's `GITHUB_TOKEN` do not trigger another workflow. The uploader runs the
publishing code from main, so merge workflow changes before enabling uploads.

## Automatic updates

MithrilPF never installs an update without asking. On launch it only checks GitHub;
when a newer compatible release is found, a prompt over the title screen shows its
version and notes with **Install on exit**, **Remind me later**, **Skip this version**,
**View on GitHub** and **View on Modrinth**. Nothing is downloaded until **Install on
exit**; the update then downloads, is verified and installs when Minecraft quits.
**Cancel install** withdraws approval. Remind me later asks again next launch; a skipped
version is not offered again, but newer versions are, and `/mpf → Settings → Review update` can still install it. A release found while you are in a world is
announced in chat instead, and the prompt waits for the title screen.

If a release needs a mod that is not installed at all (for example Hypixel Mod API),
the prompt offers it from Modrinth with **Install all**; nothing is downloaded until
you choose that. Each signed release lists its dependencies' Modrinth projects in
`assets/mithrilpf/dependencies.json`, so only those projects are used. Only Modrinth
releases (not betas) for this Minecraft version and Fabric are offered. The file must
come from `cdn.modrinth.com`, match Modrinth's SHA-512, carry the expected mod ID and a
version meeting the requirement, and have its own requirements met. All approved
dependencies are verified before any is added to `mods/`; they are added right away as
new files (loaded next launch) and existing files are never replaced. Installed but
outdated dependencies, Minecraft, Fabric Loader and Java are only named in the prompt.

New installs (no `config/mithrilpf/` yet) are asked once whether to check on launch;
nothing is checked until they choose. Existing installs keep checking but always
ask before installing. **Check for updates on launch** and **Include pre-releases**
(off by default) are in `/mpf → Settings`. Only JARs built by this
repository's tagged **Release** workflow can self-update.
Local builds and PR artifacts are marked local: they never check, download or install
updates, even if a newer remote version exists. This protects local commits/dirty
changes without relying on developers to bump the version. Missing/mismatched build
markers also disable updating. Install an official release JAR to return to automatic
updates. The marker identifies the distribution channel; it is not a signature.
Each official-build launch checks GitHub; Check now retries at most once per minute.
Checks/downloads run on a background worker. No GitHub login or Minecraft token
is sent. With pre-releases off, only the latest published stable release is
considered. With it on, the newest 50 published releases are searched by semantic
version and the newest is offered. Drafts, equal/older versions,
missing checksums/signatures and incompatible Minecraft/dependency requirements are rejected.
Turning either toggle off cancels an in-progress/staged update as appropriate;
it never downgrades an already-installed beta. Release notes shown in the prompt are
display text only; installation is authorized solely by the signed metadata.

Downloads are restricted to this repository's HTTPS GitHub release URLs and
GitHub's release-asset host, with redirect, size and time limits. The API's SHA-256
digest, artifact size, filename, repository and version must be authenticated by
an Ed25519 signature using the public key pinned in the installed mod. The JAR
must then match that authenticated digest. Missing/invalid signatures never fall
back to checksum-only installation. See [release signing](RELEASING.md#release-signatures)
for key custody, bootstrap limitations and manual verification.
The updater never updates Minecraft, Fabric Loader, Java or an installed mod; it only
adds missing dependencies you approve, from Modrinth's public API (`api.modrinth.com`,
version lists only) and CDN (`cdn.modrinth.com`), without any login or token.

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
you remove them. To roll back with Minecraft closed, disable update checks in
updates.json, copy a backup over the installed MithrilPF JAR and relaunch.
The first updater-enabled build must be installed manually; older releases cannot
gain this feature without an update.

Dungeon tracking provides split/real-tick timers, room clear/secrets PBs, F7/M7
solo 300-score PBs, run history and estimated finish times. `/mithrilpfpbs` shows
bests; `/mithrilpfstatus` shows detection/storage status. The HUD editor supports
dragging, scaling and synthetic previews without creating records. Enable the
Paul +10 score setting before a run when applicable.

Solo replay samples also describe observed teleports without changing their
12-byte size or five-per-second sampling rate. Main-hand item/block use callbacks
pass through untouched. Vanilla custom data identifies etherwarp on merged AOTE
or AOTV, instant transmission, and fully scrolled Wither Impact blades. The
component classification is cached; only a changed component is copied for safe
NBT inspection. A recent use labels one position packet within 500 ms. Types are
observations, not proof of the server ability. Mixed chains are other/mixed and
counts saturate at four or more. Small corrections and long/unmapped gaps stay
plain breaks. The web API documents the shared flag contract and capability bit;
deploy its accepting validator before publishing this client. Older recordings
use approximate teleport inference in the website.

Solo attempts require an observed solo roster; a teammate or death invalidates
the attempt. Room PBs are tracked in solo and party runs and judged per room. A
clear counts only if no living teammate was seen in the room before it turned
white (loaded players first, then dungeon map markers). Secrets count only if the
room's counter started at 0 and no teammate was there when it went up. A room is
a midclear when half its secrets, rounded down and between 1 and 3, were found
before white; clear and secrets PBs are kept per style and Total only for rooms
finished with every secret. Secrets PBs are stored as time per secret and shown
per minute. A white room is reported when it goes green or you walk into another
room; coming back for more secrets reports it again, recounted over every visit. Room timers count only time spent inside that room. Secrets and
Total wait for the 300 gate (the solo 300 Score split, or the boss-entry estimate
with deaths ignored and per-floor secret and speed limits) and are otherwise
dropped; room tracking stops once the gate is decided. Older solo secrets PBs become Total. Normal/master floors remain separate.
Estimates use completed five-player runs with less than 20 seconds of lag and
require three samples; M7 has fallback estimates before then. Unknown/missing
observations do not create fabricated PBs. Room/map or server-format changes can
require detector updates. Records are observations, not anti-cheat attestations.

New qualifying F7/M7 solo clears capture a structured dungeon map at 300 score,
using Minecraft map pixels, loaded room columns and action-bar secret counts.
Capture runs even when room timers are disabled. Room names/types, tile shape,
doors, clear states and found/total secrets accompany the completion report;
no screenshot, individual secret coordinates or other mod's state is uploaded.
Unvisited rooms start at zero; completed markers confirm the room total. Missing
capture data stays unknown rather than inventing a count. The mod is standalone
and does not require or communicate with Noamm or another dungeon mod.
The website retains only the current best map per player/floor and the Discord
time links to it. Older PBs without map data remain valid.

Map version 2 also captures time spent in each room, including repeat visits,
and dungeon secrets collected/total plus crypts killed. Timing starts with the
solo PB and freezes at the same 300-score observation. Both elapsed milliseconds
and server ticks are partitioned exactly between rooms and transit/unmapped time;
internal joins of a multi-tile room count toward that room. The website displays
ticks to match the PB. Dungeon secret totals sum distinct rooms only when all
totals are known; missing observations remain null. No post-300 activity is added.

The map can include a lightweight 2D replay: player X/Z and facing sampled at
five Hz, plus the observed dungeon-wide secret counter. A fixed 432,024-byte
buffer bounds recording to two hours; the final sample uses the exact PB cutoff.
The sync worker encodes the detached bytes, and the backend compresses them with
the map. Teleports, large jumps, missing positions and gaps break interpolation.
Secret indicators approximate the receipt of counter updates, not exact pickup
locations or times. Replays share current-best-only map retention. There is no
world, mob or image recording, and no replay stream during the run.

Settings, per-account PBs and run history live under `config/mithrilpf/`.
Malformed/newer settings are not overwritten; unknown fields survive edits.
PB files discourage casual manual edits but are not tamper-proof. Back up this
directory to retain records.

## Linked services

Discord Rich Presence is enabled by default and can be disabled in `/mpf`.
It uses application `1553815052237152407` over the local Discord desktop client's
IPC pipe/socket (JDK only; no bot token, native SDK, HTTP destination or extra mod).
Dungeon tracking takes priority: floor/current split and real elapsed run time;
completed runs stop the elapsed clock. Tracking must be enabled for dungeon details.
Otherwise the linked finder activity shows a search or the party leader and size,
including searches made on the website while Minecraft is open. The existing
presence heartbeat supplies this summary, so changes usually take up to 25 seconds
(up to two minutes when the account was previously idle in the finder).
Outside these activities the presence is cleared. A fixed website button opens
`https://mithril.foo/party-finder`; there are no Discord join secrets or game commands.
The toggle is saved as `discordPresence` in the existing tracking configuration.
Discord must be running and allow activity sharing; its own privacy settings apply.
IPC runs off the client thread with bounded frames, five-second exchanges, rate
limiting and reconnect backoff. Windows named pipes and standard Unix sockets are
supported; sandboxed Discord clients that hide their IPC endpoint may not connect.

The mod submits live solo-clear evidence and terminal reports using a fresh Mojang
proof and a separate 15-minute credential. Local PBs survive failed qualification.
Signing out revokes credentials derived from that session without deleting saved
records. Sync is bounded, retries outages and separates accounts. SS tracking is not implemented.

Party handoff uses a separate 30-day presence credential; it cannot upload records
or log into the browser. The mod never sends a Hypixel command on its own. While on Hypixel it
follows your game party from Hypixel's English party lines (joins, leaves, removals, transfers,
disbands and "You'll be partying with"); this state is not saved across launches. Once all five
are online, the leader opens chat and clicks anywhere (or uses `/mpfinvite`, also
`/mithrilpfinvite`/`/mithrilpfreinvite`, or the finder screen's button). The click reports the
tracked party and the server's answer adds one `/p name1 name2 name3 name4` for players still
missing. When the party is unknown after a launch or reconnect, the mod asks Hypixel's Mod API
for it (at most once a minute, only while you lead a full finder party) instead of sending a
command; a click made meanwhile completes when the answer arrives. Later clicks re-invite only missing players, at most once every ten seconds, and
the listing closes once all five have joined. Conflicting game parties stop invites; the mod never
kicks, disbands or leaves automatically. The canonical protocol
lives in the web repository's
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

`/games` (also `/mpfgames`, or an unbound Controls keybind) opens the Games screen, a
separate screen from the finder. Its first game is Curator: one SkyBlock item per UTC
day, guessed in up to ten tries from ten clue columns (rarity, type, museum, stage,
requirements, soulbound, origin, market value, NPC price and name length). The backend
picks and judges everything; the mod only sends the chosen item ID with the linked
device session and never posts to Hypixel chat. Hovering a cell explains its clue;
Copy result puts a Wordle-style square grid on the clipboard. The Leaderboard view shows
the monthly season. The guessable item list is cached in `config/mithrilpf/curator.json`
and refreshed only when the backend reports a new version; a file this version can't
read is left untouched. Windows narrower than 640 GUI units show guess numbers instead
of item names. The protocol lives in the web repository's
[API guide](https://github.com/MithrilAddons/web/blob/main/docs/API.md); the shared
synthetic responses are in `src/test/resources/contracts/curator-v1.json`.
