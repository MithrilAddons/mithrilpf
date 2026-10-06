package dev.mithril.mithrilpf.update

/** Decides when to ask: over the title screen once per launch, in a world only by chat. */
internal class UpdatePrompts {
    enum class Show {
        NONE,
        OPT_IN,
        PROMPT,
        NOTICE,
    }

    private var askedThisLaunch = false
    private var prompted = ""
    private var announced = ""
    private var dismissed = ""

    fun next(status: UpdateStatus, titleScreen: Boolean, inWorld: Boolean): Show {
        val version = status.version
        return when {
            titleScreen && status.state == "ask" && !askedThisLaunch -> {
                askedThisLaunch = true
                Show.OPT_IN
            }
            titleScreen &&
                status.state == "available" &&
                version != dismissed &&
                version != prompted -> {
                prompted = version
                Show.PROMPT
            }
            !titleScreen &&
                inWorld &&
                status.state == "available" &&
                version !in setOf(announced, prompted, dismissed) -> {
                announced = version
                Show.NOTICE
            }
            else -> Show.NONE
        }
    }

    /** "Remind me later": no prompt for this version until the next launch. */
    fun remindLater(version: String) {
        dismissed = version
    }
}
