package dev.mithril.mithrilpf.party

import kotlin.test.*

class GamePartyTrackerTest {
    private fun tracker(vararg lines: String) =
        GamePartyTracker().apply { lines.forEach { receive(it, "Alpha") } }

    @Test
    fun `party is unknown until a line states all of it`() {
        val tracker =
            tracker(
                "[VIP] Beta joined the party.",
                "[VIP] Beta has left the party.",
                "The party was transferred to [VIP] Beta by [MVP+] Alpha",
            )
        assertNull(tracker.roster)
        assertTrue(tracker.receive("You are not in a party right now.", "Alpha"))
        assertEquals(GameRoster("Alpha", listOf("Alpha")), tracker.roster)
        assertFalse(tracker.receive("§cYou are not currently in a party.", "Alpha"))
    }

    @Test
    fun `leading a party follows joins leaves removals and transfers`() {
        val tracker =
            tracker(
                "You left the party.",
                "§b[MVP§c+§b] Alpha §einvited §a[VIP] Beta §eto the party! They have §c60 §eseconds to accept.",
                "§a[VIP] Beta §ejoined the party.",
                "Gamma joined the party.",
                "[MVP++] Delta joined the party.",
                "[VIP] Epsilon joined the party.",
                "The party invite to [VIP] Zeta has expired.",
                "[VIP] Beta has disconnected, they have 5 minutes to rejoin before they are removed from the party.",
            )
        assertEquals(
            GameRoster("Alpha", listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon")),
            tracker.roster,
        )
        tracker.receive(
            "[VIP] Beta was removed from your party because they disconnected.",
            "Alpha",
        )
        tracker.receive("Gamma has been removed from the party.", "Alpha")
        tracker.receive("[MVP++] Delta has left the party.", "Alpha")
        tracker.receive("The party was transferred to [VIP] Epsilon by [MVP+] Alpha", "Alpha")
        assertEquals(GameRoster("Epsilon", listOf("Alpha", "Epsilon")), tracker.roster)
        tracker.receive(
            "The party was transferred to [MVP+] Alpha because [VIP] Epsilon left",
            "Alpha",
        )
        assertEquals(GameRoster("Alpha", listOf("Alpha")), tracker.roster)
    }

    @Test
    fun `joining another party records its leader and existing members`() {
        val tracker =
            tracker(
                "You have joined [MVP+] Chris' party!",
                "You'll be partying with: [VIP] Beta, Gamma, [MVP++] Delta",
            )
        assertEquals(
            GameRoster("Chris", listOf("Chris", "Alpha", "Beta", "Gamma", "Delta")),
            tracker.roster,
        )
        assertEquals(
            GameRoster("Beta", listOf("Beta", "Alpha")),
            tracker("You have joined [VIP] Beta's party!").roster,
        )
    }

    @Test
    fun `any ending returns to no party`() {
        for (ended in
            listOf(
                "[MVP+] Alpha has disbanded the party!",
                "Beta has disbanded the party!",
                "You have been kicked from the party by [MVP+] Beta",
                "The party was disbanded because all invites expired and the party was empty.",
                "The party was disbanded because the party leader disconnected.",
                "You left the party.",
            )) {
            val tracker = tracker("You have joined [VIP] Beta's party!", ended)
            assertEquals(GameRoster("Alpha", listOf("Alpha")), tracker.roster, ended)
        }
    }

    @Test
    fun `player party guild and private chat never change the party`() {
        val tracker = tracker("You left the party.")
        for (spoof in
            listOf(
                "[VIP] Mallory: Beta joined the party.",
                "Party > [VIP] Beta: Gamma joined the party.",
                "Guild > [VIP] Beta: You have joined [VIP] Mallory's party!",
                "From [VIP] Beta: You'll be partying with: Mallory",
                "[NPC] Kat: The party was transferred to [VIP] Mallory by [MVP+] Alpha",
                "[123] [VIP] Beta: [VIP] Mallory has disbanded the party!",
                "Gamma joined the party",
                "Not.A.Name joined the party.",
            )) assertFalse(tracker.receive(spoof, "Alpha"), spoof)
        assertEquals(GameRoster("Alpha", listOf("Alpha")), tracker.roster)
    }
}
