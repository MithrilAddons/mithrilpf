# Testing

Run `gradlew.bat spotlessApply` (`sh gradlew spotlessApply` on Linux), then
`python tools/check.py`. The script verifies wrapper integrity, JSON keys,
branch policy, formatting, JVM tests and packaged-JAR metadata/entrypoints.
The package guard allows only the DungeonConnectionMixin packet hook. Its class
and the adapted code's CC0 notice must be present in the JAR. The guard also
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

- Updates: test with disposable instances first. Check stable-only and pre-release
  selection, turn updates off during/after download, quit normally and verify the
  new internal version plus backup. Test locked/read-only targets and an interrupted
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
- Clear a room, leave, return for secrets: only time in that room counts.
- Compare solo 300-score detection against server observations with and without
  Paul; test death and teammate invalidation.
- Complete qualifying runs and verify history, three-sample estimates and fallback.
- Sync an existing and improved PB; check account separation, restart, logout and
  temporary failure. Compare the website card. Room/history data must stay local.
- With five accounts, verify one invite round, missing-player retry, no-show
  removal, leadership changes and completion only after all game members join.
  Test conflicting parties, hidden chat, reconnects and backend restart.
- Verify `/mpf` opens the same menu as `/mithrilpf`. In a linked party, exchange
  `/mpc` messages with another mod client and a website/phone browser, including
  after the full party has joined Minecraft. Check one echo per send, server hops,
  logout/removal, account switches, slow requests and rate limits. Retry a failed
  send by clicking its notice. Confirm `/pc` and ordinary chat are not relayed,
  and command-looking incoming text never executes. Automated relay tests use fakes only.

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
