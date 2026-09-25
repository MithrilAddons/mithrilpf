# Development

MithrilPF is a standalone Fabric client for Minecraft 26.1.2. This initial build
contains a minimal UI and a user-initiated browser sign-in flow.
It does not connect to a relay, collect runs, or match parties yet.

## Setup

Use JDK 25 and Python 3.14. Import this directory as a Gradle project in IntelliJ
and select JDK 25 as its Gradle JVM. Use the checked-in wrapper; no Maven/system
Gradle is needed. Toolchain/dependency versions match the established 26.1.2 build
without carrying over its feature implementations or optional mod dependencies.

```text
python tools/check.py
```

Windows: `gradlew.bat spotlessApply`, `gradlew.bat build`, `gradlew.bat runClient`.
Linux: `sh gradlew spotlessApply`, `sh gradlew build`, `sh gradlew runClient`.
The development client uses this repository's disposable `run/` directory and
does not touch the owner's normal instances or shared MithrilAddons configuration.

Runtime requirements: Fabric Loader, Fabric API, Fabric Language Kotlin. Mod Menu
is optional, compile-only, and not bundled. For testing it locally put its matching
JAR in `run/mods`; leave it absent for the baseline launch test.

Open `/mithrilpf` in a world, or assign the initially unbound **Open MithrilPF**
control. Mod Menu's configure button opens the same screen from the title screen.
Escape/Done returns to the previous screen. No browser is opened automatically.

## Structure and visual identity

- `src/main/kotlin/dev/mithril/mithrilpf`: client entry point, account linking, UI, optional compat.
- `src/test/kotlin`: pure logic tests using temporary data.
- `tools/check.py`: same local/CI verification and packaged-JAR checks.
- `docs/PLAN.md`: extraction and connection checklist.

Use the website palette: #101114 background, #17181D panels, #2A2C34 borders,
#B4B8FF accents, #D2D5FF highlights, #F0F0F3 text and #A0A2AE muted text.
Primary actions use #E8E9F3 with dark text; secondary actions use #1E2027.
Success/error feedback uses #8DC7AC/#E5A4A4. Use simple
flat controls, restrained text, keyboard focus, and the active Minecraft font.
No custom fonts, extra rendering framework, supersampling, or custom shaders.

The text-shadow preference has been removed; the UI uses the game font without
shadows. Any old config file is left untouched but is no longer read or written.

Link browser verifies ownership through Minecraft's Mojang session service and
opens a five-minute HTTPS link on mithril.foo for explicit browser confirmation.
Only Mojang receives the Minecraft access token. Mithril receives UUID, name and
a single-use proof identifier; it never receives the access token. Networking runs
on a bounded worker, with request timeouts and 16 KiB response limits. Closing the
screen invalidates pending UI results. A status-only receipt is saved per UUID in
instance-local `config/mithrilpf/link.json`; no Minecraft token or browser-session
credential is stored. The website stores its own revocable browser session,
optionally remembered for 30 days. The receipt becomes linked only after browser
confirmation and follows that session's renewal, expiry and logout. It cannot
sign in, renew a session, or authorize future gameplay submissions.

Opening the menu loads the saved receipt on the worker and checks its status.
While the menu is open, pending confirmations are checked every three seconds;
confirmed sessions and temporary failures every 30 seconds. Closing the menu
stops polling. Offline checks keep the saved confirmation but mark status as
unavailable. Never claim a cached state is a fresh server verification.
The linked menu offers Open website and Link another browser. Old clients remain
compatible; existing links need one new linking flow to obtain a receipt.
The canonical v1 protocol is documented in the web repository's
docs/ACCOUNT_LINKING.md. No old relay device-authentication overrides are included.

One gameplay JAR is produced at `build/libs/mithrilpf-0.1.0.jar`, plus sources.
There is no separate dev variant yet: no diagnostic collection exists to separate.
No automatic install, release signing, updater, or publication is configured.
Commit signatures are not JAR signatures.
