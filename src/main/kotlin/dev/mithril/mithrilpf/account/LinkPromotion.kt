package dev.mithril.mithrilpf.account

/** An unconfirmed replacement must never erase the working connection on disk. */
object LinkPromotion {
    fun saved(
        active: LinkReceipt?,
        checked: LinkReceipt,
        status: String,
        replacement: Boolean,
    ): LinkReceipt? =
        when (status) {
            "linked" -> checked.copy(confirmed = true)
            "pending" -> if (replacement) active else checked.copy(confirmed = false)
            "expired" -> if (replacement) active else null
            else -> error("Invalid link status")
        }
}
