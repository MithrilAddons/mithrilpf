# Testing

Release-signing regression tests use temporary Ed25519 keys and synthetic JARs.
They cover signer/updater interoperability, wrong keys, metadata and artifact
tampering, unsigned/malformed signature assets, fail-closed staging, and secret
redaction. They never use the production private key or a real mod installation.

Run `gradlew.bat spotlessApply` (`sh gradlew spotlessApply` on Linux), then
`python tools/check.py`. The script verifies wrapper integrity, JSON keys,
branch policy, formatting, JVM tests and packaged-JAR metadata/entrypoints.
The package guard allows only the DungeonConnectionMixin packet hook and the
read-only BossHealthOverlayAccessor. Their classes and the adapted code's CC0
notice must be present in the JAR. The guard also
checks MIT metadata and that the packaged project license matches LICENSE.

Tests use temporary directories and redirected user directories, never live
accounts or configuration. CI runs the same checks on Windows and Linux with
read-only permissions, pinned actions and no deployment credentials. Required jobs
are `Verify (ubuntu-24.04)` and `Verify (windows-2025)`.
Build/package guards are not malware scanning or proof of runtime correctness.

## SonarQube analysis

The Linux verification job runs SonarQube after the build and waits for its quality
gate. JaCoCo reports cover the client and updater source sets; HTML and XML reports
are included in the test-report artifact. The scan also includes Python tooling
and GitHub workflows. Tests are classified separately and the binary icon is excluded.
The Linux job also collects Python tooling branch coverage with coverage.py and
imports `build/reports/python/coverage.xml` into SonarQube.

Minecraft-dependent finder widgets and layout/render adapters are explicitly excluded
from the coverage metric and require the in-game checks below. Input validation,
drafts, navigation, authentication, request scheduling, protocol parsing and storage
remain included. Tests use queued client-thread callbacks to exercise account changes,
stale replies, credential failures and leader-only invite commands without live services.

Scanner output is in **Actions → Verify → Verify (ubuntu-24.04) → SonarQube analysis**.
The repository needs a `SONAR_TOKEN` Actions secret and SonarQube automatic analysis
must be off. Fork and Dependabot PRs run verification without the secret or scanner;
their merged code is analyzed on main. Release tag builds do not submit analyses.
For a local scan, supply `SONAR_TOKEN` through the environment and run
`gradlew.bat build sonar` (Linux: `sh gradlew build sonar`).

Gradle checks dependency and plugin artifacts against the SHA-256 values in
`gradle/verification-metadata.xml`. When updating dependencies, regenerate the
metadata with `gradlew.bat --write-verification-metadata sha256 build sonarResolver`
and review the changed artifacts and their provenance before committing it.
Do not disable verification or accept an unexplained checksum mismatch.
The exact locally generated Minecraft JAR is exempt because Loom's processed
output differs between builds; Loom verifies the original downloads against
Mojang's hashes. This exception does not apply to downloaded libraries or plugins.

## Manual regression checks

- Discord: start with Discord closed, then open/restart it. Check `/mpf`'s Rich
  Presence toggle and restart persistence. With activity sharing enabled, check
  floor/split/elapsed time through a dungeon, completion and leaving. Dungeon
  status must win over finder activity. Search on the website, join/leave a party,
  change leader and finish game handoff; confirm leader/member count and the
  website button (viewed from another Discord account). Disable the feature or
  close Minecraft and confirm the activity disappears. Test Windows and Unix IPC
  separately; synthetic stream tests do not verify Discord's actual rendering.

- Updates: test with disposable instances first. Check the first-run question on a
  new instance and that an existing instance is not asked. Verify the title-screen
  prompt (notes, scrolling, narrow windows), that nothing downloads before Install on
  exit, Remind me later, Skip this version, Review update, Cancel install, both web
  buttons, the in-world chat notice and a release needing a missing mod: Install all
  must add it to `mods/` only after approval, and an outdated or already-present file
  must not be replaced. Check
  stable-only and pre-release selection, turn updates off during/after download, quit
  normally and verify the new internal version plus backup. Test locked/read-only targets and an interrupted
  quit. The helper must not replace a changed installed/staged file or run downloads.
  Automated tests use synthetic releases, temporary directories and harmless Java
  child processes; they do not access GitHub, real credentials or the user's mods.
  Real Minecraft restart and launcher behavior still need manual verification.

- Launch with only required dependencies; also check optional Mod Menu.
- Open/close the menu using command, keybind and Mod Menu; check keyboard focus,
  GUI scaling, resizing and resource-pack fonts.
- Link the correct browser account; verify remembered state, restart, expiry,
  logout, cancelled/expired links, account changes and service outages.
- Test Open browser, paste Copy link into a non-default browser, scan the QR on
  a phone, and type the code at `/link`. Confirm the same account and verify that
  code/link cannot be reused. Cancel a relink and verify old record/party syncing.
  Test QR readability at different GUI scales; small windows retain copy/code access.
- Expand "Use another browser or phone" and go back without generating another
  login. Test an old backend without `user_code`: QR/copy still work, but no blank
  code instructions appear. Refresh is only offered after expiry/failure.
- Test HUD movement/scaling and settings persistence without creating preview PBs.
- Enter a fresh dungeon: verify floor/roster detection, split boundaries, real/tick
  clocks during lag, normal/master separation and abandoned-run handling.
- Clear a room, leave, return for secrets: only time in that room counts, and
  secrets found after walking into another room are ignored.
- In party runs, check that a teammate entering a room before it turns white
  voids only that room's clear, a teammate present at a secret voids its secrets,
  and midclear/regular lines match the threshold. Confirm secrets and Total show
  "needs 300" until boss entry (or the solo 300 split) and are saved or dropped
  with one line there. Compare `/mithrilpfpbs` rates and old solo room PBs.
- Compare solo 300-score detection against server observations with and without
  Paul; test death and teammate invalidation.
- Complete qualifying runs and verify history, three-sample estimates and fallback.
- Sync an existing and improved PB; check account separation, restart, logout and
  temporary failure. Compare the website card. Room/history data must stay local.
- With five accounts, verify no command is sent until the leader clicks in chat, that
  one click sends only one `/p` (also right after launching or reconnecting, when the
  party comes from the Mod API), missing-player re-invites, the ten-second cooldown, no-show removal,
  leadership changes, a pre-existing party, and completion from join messages only after
  all game members join.
  Test conflicting parties, hidden chat, reconnects and backend restart.
- Verify `/mpf` opens the same menu as `/mithrilpf`. In a linked party, exchange
  `/mpc` messages with another mod client and a website/phone browser, including
  after the full party has joined Minecraft. Check one echo per send, server hops,
  logout/removal, account switches, slow requests and rate limits. Retry a failed
  send by clicking its notice. Confirm `/pc` and ordinary chat are not relayed,
  and command-looking incoming text never executes. Automated relay tests use fakes only.

- Games: open `/games` and `/mpfgames` at wide and narrow GUI scales and with a
  resource-pack font. Check autocomplete (typing, arrows, Tab, Enter, Escape, mouse),
  rejected guesses (repeated or unknown), cell and header tooltips, the family badge,
  a solved and a failed round with the item icon, Copy result, the leaderboard with your
  pinned row, the day rollover message and Load button, and a signed-out account.

State clearly which checks were automated and which were performed in Minecraft;
compilation alone does not establish gameplay accuracy or performance.

## Native party finder

Use isolated accounts and storage for API integration tests. Development launches
may set `mithrilpf.testApi` to `http://127.0.0.1:<port>/api/v1/`; installed builds
always use the production HTTPS origin, regardless of that property. Never put
real sessions in synthetic fixtures.

Check the four tabs at wide and compact GUI scales, keyboard focus and resource-pack
fonts. Browse eligible and unavailable listings, explicitly choose a class, reserve
against a concurrent reservation, and start/stop matching with a team-PB ceiling.
Create and edit shared/class rules, duplicate roles and blocks. Restart to verify
per-floor/account presets and check malformed-file preservation. Exercise removal,
pause/resume, unlisting, confirmation cancellation and leadership changes.

Exchange chat with a website client; verify Enter sends only from the chat field,
new typing survives an earlier send, retries do not duplicate messages, and history
scrolling stays put while reading older messages. Test reporting, mutes and expiry.
Compare local and eligible records, match sounds, the search HUD, and sign-out during
pending invites. Verify browser logout and native sign-out affect only their own
session families, and account deletion revokes both.

## Qualifying PBs

For map capture, test with Noamm absent and room timers disabled. In an F7/M7
solo run, visit multi-tile rooms, collect some/all secrets and reach 300 score.
Compare the website snapshot with the in-game map at that instant, including
unvisited zero counts, doors, failed puzzles and secret totals. Continue collecting
after 300 to verify the stored map is frozen. Beat that PB and verify the old map
is retired; old-client PBs without maps must still work. The offline map/transport
tests exercise synthetic pixels and bounded data, not actual Hypixel packets.

For timed maps, revisit rooms and cross both internal joins and doors between
rooms. Compare room times plus transit/unmapped with the PB in both clocks,
including during server lag. Verify dungeon secrets collected/total and crypts
at 300 score; later movement and pickups must not change the snapshot. Confirm
old map pages show unrecorded timing rather than zero. The shared run-map-v2.json
fixture checks the client snapshot against the backend/frontend contract.

For replay, compare position/facing and secret-counter increases with gameplay,
including Etherwarp/AOTE teleports, missing-map positions and pauses. Verify
play/pause, scrubbing and the exact 300-score cutoff on the website; later activity
must not appear. Counter indicators may be delayed or grouped. Measure frame times
in Minecraft with recording active; unit tests verify cadence, buffer bounds and
encoding but do not establish live-game overhead or packet ordering.

New soloclear PBs need a live backend start, five-second progress acknowledgements
and a qualifying finish. Check F7/M7, delayed rosters, another player joining then
leaving, death, Paul changes, disconnects and server restart. Local PBs must survive
failed qualification. Verify no network or disk I/O happens in timer callbacks.
The shared solo-score-v2.json vectors check projected-score parity with the backend.

Terminal PBs remain eligible with one reporting client. Test two mod clients in the
same run: compatible timings corroborate the report; conflicting timings are flagged
for review without automatically removing either record. Backend comparison permits
one second/twenty ticks of spread and ten seconds of run-start clock disagreement.
Run timestamps and rosters remain client-reported evidence, not trusted game attestations.

Existing synced PBs remain eligible unless moderated. Stored local minima are no
longer uploaded; the previous bulk upload route rejects new submissions. Test token
expiry/renewal, logout during tracking, account switching and bounded queue overflow.
Do not enable production evidence collection before the coordinated moderation and
erasure features are available.
