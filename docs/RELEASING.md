# Release announcements

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
