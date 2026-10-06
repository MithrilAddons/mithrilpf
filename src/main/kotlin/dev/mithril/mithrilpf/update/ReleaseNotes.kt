package dev.mithril.mithrilpf.update

data class NoteLine(val text: String, val heading: Boolean = false, val bullet: Boolean = false)

/**
 * Plain display lines from a release body written to docs/RELEASING.md. The "Updating" section and
 * changelog link describe the website flow, so the in-game prompt omits them.
 */
object ReleaseNotes {
    private const val MAX_LINES = 200

    fun parse(body: String): List<NoteLine> {
        val lines = mutableListOf<NoteLine>()
        val text = body.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val heading = Regex("\\*\\*(.+?)\\*\\*:?").matchEntire(line)?.groupValues?.get(1)
            if (heading == "Updating" || line.startsWith("[Full changelog]")) break
            lines +=
                when {
                    heading != null -> NoteLine(clean(heading), heading = true)
                    line.startsWith("- ") || line.startsWith("* ") ->
                        NoteLine(clean(line.substring(2)), bullet = true)
                    else -> NoteLine(clean(line))
                }
            if (lines.size == MAX_LINES) break
        }
        return lines
    }

    private fun clean(text: String) =
        text
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace("`", "")
            .replace(Regex("<[^>]+>"), "")
            .trim()
}
