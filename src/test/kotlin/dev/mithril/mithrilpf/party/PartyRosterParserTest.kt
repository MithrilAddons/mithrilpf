package dev.mithril.mithrilpf.party

import kotlin.test.*

class PartyRosterParserTest {
    @Test
    fun `only complete requested rosters are accepted`() {
        val parser = PartyRosterParser()
        assertNull(parser.receive("You are not currently in a party.", "Alpha", 100))
        parser.request(100)
        assertNull(parser.receive("Party Members (5)", "Alpha", 100))
        assertNull(parser.receive("§eParty Leader: §b[MVP+] Alpha §a●", "Alpha", 110))
        assertNull(parser.receive("Party Moderators: [VIP] Beta ●", "Alpha", 120))
        assertEquals(
            GameRoster("Alpha", listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon")),
            parser.receive("Party Members: Gamma ● [MVP++] Delta ● Epsilon ●", "Alpha", 130),
        )
        assertNull(parser.receive("Party Leader: Other ●", "Alpha", 140))
    }

    @Test
    fun `solo requires explicit no party response`() {
        val parser = PartyRosterParser()
        parser.request(100)
        assertEquals(
            GameRoster("Alpha", listOf("Alpha")),
            parser.receive("You are not currently in a party.", "Alpha", 200),
        )
    }

    @Test
    fun `partial expired and spoofed rosters never complete`() {
        val parser = PartyRosterParser()
        parser.request(100)
        for (line in
            listOf("Alpha: Party Members (1)", "Party > Alpha: Party Leader: Alpha ●")) assertNull(
            parser.receive(line, "Alpha", 200)
        )
        assertNull(parser.receive("Party Members (2)", "Alpha", 200))
        assertNull(parser.receive("Party Leader: Alpha ●", "Alpha", 200))
        assertNull(parser.receive("Party Members: Beta ●", "Alpha", 5101))
        parser.request(5200)
        assertNull(parser.receive("Party Members (6)", "Alpha", 5200))
        assertNull(parser.receive("Party Leader: Alpha ●", "Alpha", 5200))
    }

    @Test
    fun `duplicate missing self and invalid names do not satisfy member count`() {
        for (members in listOf("Alpha ●", "Beta /evil", "Gamma ● Delta ●")) {
            val parser = PartyRosterParser()
            parser.request(100)
            parser.receive("Party Members (2)", "Alpha", 101)
            parser.receive("Party Leader: Alpha ●", "Alpha", 101)
            assertNull(parser.receive("Party Members: $members", "Alpha", 102))
        }
        val parser = PartyRosterParser()
        parser.request(100)
        parser.receive("Party Members (1)", "Alpha", 101)
        assertNull(parser.receive("Party Leader: Beta ●", "Alpha", 102))
    }

    @Test
    fun `join and leave lines update a confirmed roster without trusting player chat`() {
        val parser = PartyRosterParser()
        val roster = GameRoster("Alpha", listOf("Alpha", "Beta"))
        assertEquals(
            GameRoster("Alpha", listOf("Alpha", "Beta", "Gamma")),
            parser.membership("§b[MVP§c+§b] Gamma §ejoined the party.", roster)?.roster,
        )
        assertNull(parser.membership("beta joined the party.", roster))
        assertEquals(
            GameRoster("Alpha", listOf("Alpha")),
            parser.membership("[VIP] Beta has left the party.", roster)?.roster,
        )
        assertEquals(
            GameRoster("Alpha", listOf("Alpha")),
            parser.membership("Beta has been removed from the party.", roster)?.roster,
        )
        assertNull(parser.membership("Gamma has left the party.", roster))
        for (spoof in
            listOf(
                "[VIP] Mallory: Gamma joined the party.",
                "Party > [VIP] Beta: Gamma joined the party.",
                "From [VIP] Beta: Gamma joined the party.",
                "Gamma joined the party",
                "Gamma joined the party!",
            )) assertNull(parser.membership(spoof, roster), spoof)
        for (ended in
            listOf(
                "[MVP+] Alpha has disbanded the party!",
                "You left the party.",
                "The party was transferred to [VIP] Beta by [MVP+] Alpha",
                "The party was disbanded because all invites expired and the party was empty.",
                "Alpha has left the party.",
            )) {
            val change = assertNotNull(parser.membership(ended, roster), ended)
            assertNull(change.roster, ended)
        }
    }
}
