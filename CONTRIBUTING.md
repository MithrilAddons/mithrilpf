# Contributing

Setup is in docs/DEVELOPMENT.md. Keep PRs focused and explain behavior, tests, and
compatibility changes. Use `feat/`, `fix/`, `chore/`, `refactor/`, or `docs/` followed
by a short lowercase kebab-case description. CI checks PR branch names, with an
exception only for branches authored by dependabot[bot].

Use signed commits; SSH push access alone does not enable signing. Configure an
owner-approved public signing key locally and register it with GitHub. Never commit
keys, tokens, recordings, logs, or real player data. Use normal pull_request CI,
not privileged workflows that run untrusted PR code with secrets.

Kotlin uses ktfmt 0.64. Apply `gradlew spotlessApply`, then `python tools/check.py`.
Put user-facing text in assets/mithrilpf/lang/en_us.json. Keep calculations and
parsers independent of Minecraft for offline tests. Keep optional Mod Menu access
inside compat; the mod must load without it. New required dependencies, mixins,
network destinations, and security-sensitive code need explicit review.

No framework, license, telemetry, or broad cleanup as a side effect of a feature.
Metadata retains All-Rights-Reserved pending the owner's distribution-license
decision; this setup does not grant third-party relicensing permission.
