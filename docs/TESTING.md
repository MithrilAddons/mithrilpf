# Verification

Run `gradlew.bat spotlessApply` (Linux: `sh gradlew spotlessApply`), then
`python tools/check.py`. The latter checks wrapper integrity, duplicate JSON keys,
branch-policy tests, build/format/JVM tests, and finished-JAR metadata/entrypoints.
These packaging rules are regression guards, not a malware scanner or proof of safety.
Dependencies may download during the build; tests make no live service requests.

JVM tests use temporary directories and redirected user.home/APPDATA/LOCALAPPDATA.
Do not introduce tests that read actual accounts, Minecraft sessions, or shared config.
CI uses pinned actions, read-only permissions, no checkout credentials, wrapper
validation, timeouts, and Ubuntu/Windows builds. Reports and both tested artifacts
are retained for 14 days. It does not deploy or install anything.

Required CI job names, once first observed, are `Verify (ubuntu-24.04)` and
`Verify (windows-2025)`. Changing repository rules is a separate authorized action.

## Manual Minecraft checklist

- [ ] Launch 26.1.2 with only required dependencies; no other Mithril mod required.
- [ ] `/mithrilpf` opens after chat closes; Escape/Done returns to gameplay.
- [ ] Assign and test Open MithrilPF in Controls; it starts unbound to avoid conflicts.
- [ ] Open via optional Mod Menu; Escape/Done returns to Mod Menu.
- [ ] Charcoal/periwinkle panel, light primary action, neutral secondary action,
      hover and keyboard focus; Tab/Enter activates buttons.
- [ ] GUI scale 1/3/auto, window resize/fullscreen, resource-pack font reload.
- [ ] Link browser verifies the correct Minecraft account and opens mithril.foo.
- [ ] Explicitly confirm in browser; remembered login survives browser restart.
- [ ] Used/expired links fail; logout revokes the browser session.
- [ ] Close screen during verification: no late browser opening.
- [ ] Offline/unavailable service fails visibly and permits retry.
- [ ] With no saved link, no network before clicking Link browser; no custom helper,
      automation, or recording. Saved receipts are checked only while the menu is open.
- [ ] Confirm browser link: menu changes to Linked as / Open website; reopen menu
      and restart Minecraft to verify persistence. Switch accounts: no shared link state.
- [ ] Logout/expiry clears the linked state; offline checks keep it with an unavailable label.
- [ ] Link another browser remains available; receipt alone cannot sign in anywhere.
- [ ] Text shadow toggle absent; old config left untouched.

Compilation is not a runtime or visual test. Do not claim in-game verification or
performance improvements without doing and recording those checks. Do not install
into the owner's dungeons/practice instances unless explicitly requested.
