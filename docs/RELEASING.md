# Releasing

## Release signatures

The Release workflow signs the tested gameplay JAR with a dedicated Ed25519 key
after both verification jobs pass. It publishes `mithrilpf-<version>.jar.sig`
alongside the unchanged JAR and `SHA256SUMS`. The 64-byte signature is detached;
it does not modify the JAR, its digest, or the artifact distributed on Modrinth.
Missing or mismatched signing secrets fail draft creation. No signing secret is
provided to the build/test jobs or pull request workflows.

`RELEASE_SIGNING_KEY` is a repository Actions secret containing the base64-encoded
PKCS#8 private key. Only `src/main/resources/assets/mithrilpf/release-signing.pub`
(base64 X.509 SubjectPublicKeyInfo) belongs in source control. Keep an independent,
access-restricted backup of the private key; never put it in a JAR, log, command
argument, release asset, or test fixture. The signer reads it from its environment
only in the signing step. GitHub Actions and authorized repository writers remain
part of the signing trust boundary; this is not an offline signing system.

The updater takes its public key only from the installed mod. It requires exactly
one signature asset at the expected repository/version URL and verifies the
signature before downloading the JAR. Checksum, size, version, dependency, channel,
and downgrade checks still apply. The verified digest is held in memory and passed
to the installed mod's bundled installer, which rechecks the staged JAR and the
replacement copy before replacing the old file. There is no unsigned fallback or
remotely supplied trust key. This does not protect an already compromised local
Minecraft process or operating-system account.

The signed bytes are ASCII with LF line endings, in this exact order, including
the final newline (angle-bracket values below are replaced, without brackets):

```text
MithrilPF release signature v1
repository=MithrilAddons/mithrilpf
version=<version without v>
artifact=mithrilpf-<version>.jar
size=<decimal byte count>
sha256=<lowercase 64-character digest>
```

To verify a downloaded release using a separately trusted checkout and public key,
run with JDK 25, substituting the release version and paths:

```text
java tools/dev/mithril/mithrilpf/release/ReleaseSigning.java verify <version> <path-to-mithrilpf-version.jar> <path-to-jar.sig> src/main/resources/assets/mithrilpf/release-signing.pub
```

Older clients do not verify signatures. Their first update to a signing-aware
client relies on the existing GitHub HTTPS/checksum trust; subsequent updates
require the pinned signing key. Manual bootstrap verification must obtain the
public key and verifier through an independently trusted channel. Historical
unsigned releases are not retroactively trusted by the new updater.

Do not casually replace the public key or regenerate the secret: deployed clients
would reject the new signatures. Planned rotation requires a reviewed transition
release signed by the old key that adds trust for the replacement, followed by a
release removing the old key. If the old key is compromised, stop publication and
distribute a replacement trust anchor through an independent trusted channel;
an update signed only by the compromised key cannot establish recovery trust.

For initial setup only, `java tools/dev/mithril/mithrilpf/release/ReleaseSigning.java generate <private.key>
<public.pub>` creates new files without overwriting existing keys. Create the
private file in an owner-only directory (restrict Windows ACLs, or use `umask 077`
on Unix) before transferring it to the named Actions secret.

## Announcement format

Every release uses reviewed notes in `docs/releases/<mod_version>.md`. The Release
workflow copies that text into the GitHub draft; Discord and Modrinth use the same
visible notes. Write the notes in the version-bump PR, before tagging the release.
Do not publish a release without its notes file. Historical releases need not be
rewritten.

Keep the complete body under 2,600 characters so it fits the Discord announcement.
The title is `MithrilPF <version>`; the bot supplies the release link, channel label,
checksum and download controls. Do not repeat these in the notes or wrap the body
in a code block. Use bold section labels, short bullets and inline code for commands.

Use this order:

1. One sentence describing the main player-facing outcome.
2. **Changes**: a short list of concrete features or fixes. Group related fixes;
   avoid commit lists, file names, implementation details and internal tooling work.
3. **Compatibility** (only when requirements or migration steps changed).
4. **Updating**: the standard updater instructions below, followed by any action
   needed specifically for this release, such as starting a fresh run.
5. **Known issues** (only for confirmed, relevant issues and useful workarounds).
6. A **Full changelog** link comparing the previous release tag with this one.

Do not include test counts, CI/Sonar results, approval history, development notes,
or routine pending-validation reminders. Keep actual player-impacting limitations
clear and specific. Put engineering details and completed checks in the PR instead.

The release workflow adds a hidden metadata comment derived from its Linux JUnit
and JaCoCo reports, bound to the gameplay JAR checksum. The backend strips this
comment from Discord notes and supplies the metrics for a separate native embed.
It shows JVM tests passed, line/branch coverage and the release workflow link.
The draft job requires both Linux and Windows checks to pass. Do not hand-author
these metrics or substitute a PR's coverage for the release build's results.

## Template

```markdown
<One-sentence summary.>

**Changes**
- <Player-facing change.>
- <Player-facing change.>

**Updating**
Open `/mpf → Updates`, enable **Include pre-releases**, then select **Check now**. Once the update is ready, quit Minecraft normally and wait a few seconds before relaunching.

[Full changelog](https://github.com/MithrilAddons/mithrilpf/compare/<previous-tag>...<new-tag>)
```

For stable releases, omit `enable **Include pre-releases**, then` from Updating.
Optional sections follow the order above; omit empty sections and placeholder text.
