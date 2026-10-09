package dev.spotifychat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HypixelGuildTest {
    private static final String RULE = "-----------------------------------------------------";
    /** Hypixel's /g list, as it shows up in chat (one message per line) */
    private static final List<String> LIST = List.of(
            RULE,
            "Guild Name: Mooi Weer Fietsers",
            "",
            "                             -- Guild Master --",
            "[MVP++] Somebody ●  ",
            "",
            "                                -- E-Biker --",
            "[MVP+] One ●  [VIP] Two ●  Three ●  ",
            "",
            "Total Members: 115",
            "Online Members: 22",
            "Offline Members: 93",
            RULE);

    /** What's left in chat after the hidden lines */
    private static List<String> shown(HypixelGuild g, List<String> lines, long now) {
        List<String> out = new ArrayList<>();
        for (String l : lines) if (!g.hide(l, now)) out.add(l);
        return out;
    }

    @Test
    void readsTheGuildName() {
        HypixelGuild g = new HypixelGuild();
        assertEquals("Mooi Weer Fietsers", g.read("Guild Name: Mooi Weer Fietsers"));
        assertEquals("Mooi Weer Fietsers", g.read("Guild Name: Mooi Weer Fietsers (2)")); // chat mod stacking
        assertEquals("", g.read("You left the guild!"));
        assertNull(g.read("Guild > [VIP] Someone: Guild Name: Fake"));
        assertNull(g.read("[MVP+] Someone: Guild Name: Fake"));
        assertNull(g.read("You must be in a guild to use this command!"), "only right after our own /g online");
        assertNull(g.read(RULE));
    }

    @Test
    void hidesOnlyTheAnswerToOurOwnCheck() {
        HypixelGuild g = new HypixelGuild();
        assertEquals(LIST, shown(g, LIST, 0), "someone typing /g list sees it");

        g.asked(1000);
        List<String> mixed = new ArrayList<>(LIST);
        mixed.add(7, "Guild > [VIP] Friend: hi"); // someone talking while the list comes in
        assertEquals(List.of("Guild > [VIP] Friend: hi"), shown(g, mixed, 1100));
        assertEquals(List.of(RULE, "after"), shown(g, List.of(RULE, "after"), 1200), "done after the closing line");
    }

    @Test
    void notInAGuild() {
        HypixelGuild g = new HypixelGuild();
        g.asked(0);
        assertEquals("", g.read("You must be in a guild to use this command!"));
        assertEquals(List.of("next"), shown(g, List.of(RULE, "You must be in a guild to use this command!", RULE,
                "next"), 10));
    }

    @Test
    void stopsHidingAfterAFewSeconds() {
        HypixelGuild g = new HypixelGuild();
        g.asked(0);
        assertEquals(List.of(RULE), shown(g, List.of(RULE), HypixelGuild.ANSWER_MS + 1));
    }

    @Test
    void guildHelloCarriesTheGuild() {
        assertEquals("\u0001SCGUILD HELLO Steve Mooi Weer Fietsers\u0001", IrcClient.guildHello("Steve", "Mooi Weer Fietsers"));
        assertEquals("\u0001SCGUILD HELLO Steve\u0001", IrcClient.guildHello("Steve", null));
    }
}
