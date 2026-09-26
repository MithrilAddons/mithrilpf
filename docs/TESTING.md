# Testing

Run `gradlew.bat spotlessApply` (`sh gradlew spotlessApply` on Linux), then
`python tools/check.py`. The script verifies wrapper integrity, JSON keys,
branch policy, formatting, JVM tests and packaged-JAR metadata/entrypoints.
Only the reviewed DungeonConnectionMixin packet hook is allowed. Its class and
the adapted code's CC0 notice must be present in the JAR.

Tests use temporary directories and redirected user directories, never live
accounts or configuration. CI runs the same checks on Windows and Linux with
read-only permissions, pinned actions and no deployment credentials. Required jobs
are `Verify (ubuntu-24.04)` and `Verify (windows-2025)`.
Build/package guards are not malware scanning or proof of runtime correctness.

## Manual regression checks

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
  Paul; test death and teammate invalidation. The owner confirmed successful
  live 300-score detection on 2026-09-26; that is not coverage of every modifier.
- Complete qualifying runs and verify history, three-sample estimates and fallback.
- Sync an existing and improved PB; check account separation, restart, logout and
  temporary failure. Compare the website card. Room/history data must stay local.
- With five accounts, verify one invite round, missing-player retry, no-show
  removal, leadership changes and completion only after all game members join.
  Test conflicting parties, hidden chat, reconnects and backend restart.

Keep session-specific results and outstanding test notes local. State clearly
which checks were automated and which were performed in Minecraft; compilation
alone does not establish gameplay accuracy or performance.
