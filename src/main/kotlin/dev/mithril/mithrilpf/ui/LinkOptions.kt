package dev.mithril.mithrilpf.ui

enum class LinkAction {
    OPEN,
    COPY,
    REFRESH,
    WEBSITE,
}

/** Opening alternatives changes presentation only, not the pending login attempt. */
data class LinkOptions(val action: LinkAction, val showAlternatives: Boolean, val code: String?) {
    companion object {
        fun from(expanded: Boolean, hasLink: Boolean, status: String, code: String?): LinkOptions {
            val show = expanded && hasLink
            return LinkOptions(
                when {
                    hasLink -> if (expanded) LinkAction.COPY else LinkAction.OPEN
                    status == "linked" -> LinkAction.WEBSITE
                    else -> LinkAction.REFRESH
                },
                show,
                code?.takeIf { show },
            )
        }
    }
}
