# Implementation checklist

## Initial foundation

- [x] Standalone Fabric 26.1.2 client, no dependencies on other SkyBlock mods.
- [x] Website-matching palette, active Minecraft font, flat keyboard-accessible UI.
- [x] Client command, unbound key mapping, optional Mod Menu entry point.
- [x] Removed the initial text-shadow preference; legacy file left untouched.
- [x] Formatting, offline tests, wrapper/package checks, and CI definition.
- [ ] Complete the manual Minecraft checklist in TESTING.md.
- [ ] Initial signed commit/PR, observe CI, then require the actual check names.
- [ ] Owner decision: public distribution license and security reporting channel.

## Next: website connection

- [x] Versioned website/backend/mod messages and Mojang ownership verification.
- [x] User-initiated browser sign-in with explicit confirmation and optional remembered session.
- [ ] Verify the complete live Minecraft/browser flow.
- [ ] Show actual client presence on the website (separate from browser sign-in).
- [ ] Test disconnect, reconnect, restart, revocation, and stale/duplicate messages.
- [x] No old development authentication overrides in public builds.

## Extraction (not implemented yet)

- [ ] Independent dungeon/floor/party detection.
- [ ] Split timers, PBs, completed-run history, and estimates.
- [ ] Solo clear/room clear/secrets timing and performance records.
- [ ] Review attribution and tests before adapting any existing implementation.
- [ ] Define record provenance/validation; editable local PBs are not authoritative.
- [ ] Party browsing, queue/reservations, and synchronized state via the backend.

The website and client both connect to the backend, not directly to one another.
Keep unrelated MithrilAddons features out of this repository. Extraction does not
authorize changing or retiring the existing mod installation yet.
