package dev.spotifychat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsTest {
    @BeforeEach
    void freshClock() {
        PlaybackClock.reset();
    }

    @Test
    void parsesLrc() {
        List<Lyrics.Line> lines = Lyrics.parse("""
                [ar:The Weeknd]
                [00:13.42] Yeah
                [00:14.81]
                [01:02.5][02:10.05] Twice
                not a lyric line
                [0:26.950] I've been tryna call""");
        assertEquals(5, lines.size());
        assertEquals(new Lyrics.Line(13_420, "Yeah"), lines.get(0));
        assertEquals(new Lyrics.Line(14_810, ""), lines.get(1));
        assertEquals(new Lyrics.Line(26_950, "I've been tryna call"), lines.get(2));
        assertEquals(new Lyrics.Line(62_500, "Twice"), lines.get(3));
        assertEquals(new Lyrics.Line(130_050, "Twice"), lines.get(4));
    }

    @Test
    void findsTheLineAtATime() {
        List<Lyrics.Line> lines = Lyrics.parse("[00:10.00] a\n[00:20.00] b\n[00:30.00] c");
        assertEquals(-1, Lyrics.indexAt(lines, 9_999));
        assertEquals(0, Lyrics.indexAt(lines, 10_000));
        assertEquals(0, Lyrics.indexAt(lines, 19_999));
        assertEquals(1, Lyrics.indexAt(lines, 20_000));
        assertEquals(2, Lyrics.indexAt(lines, 99_000));
        assertEquals(-1, Lyrics.indexAt(List.of(), 5));
    }

    /** Versions of one song like LRCLIB has them: a radio edit, the album version twice, a sped up one */
    private static final String VERSIONS = """
            [{"id":1,"trackName":"Song","artistName":"The Band","duration":146.0,"syncedLyrics":"[00:01.00] radio edit"},
             {"id":2,"trackName":"Song","artistName":"The Band","duration":157.0,"syncedLyrics":"[00:01.00] album"},
             {"id":3,"trackName":"Song","artistName":"The Band","duration":157.4,"syncedLyrics":"[00:01.00] album too"},
             {"id":4,"trackName":"Song (Sped Up)","artistName":"Somebody","duration":120.0,"syncedLyrics":"[00:01.00] sped up"},
             {"id":5,"trackName":"Song","artistName":"The Band","instrumental":true,"duration":157.0,"syncedLyrics":null},
             {"id":6,"trackName":"Song","artistName":"The Band","duration":157.0,"syncedLyrics":null,"plainLyrics":"no times"},
             {"id":7,"trackName":"Song","artistName":"The Band","duration":null,"syncedLyrics":"[00:01.00] no length"}]""";

    @Test
    void picksTheVersionAsLongAsTheSong() {
        List<Lyrics.Version> versions = Lyrics.versions(JsonParser.parseString(VERSIONS), "Song", "The Band");
        assertEquals(5, versions.size()); // instrumental and unsynced ones are left out
        assertEquals("album", Lyrics.choose(versions, 157_169).getFirst().text());
        assertEquals("radio edit", Lyrics.choose(versions, 146_500).getFirst().text());
        assertEquals("sped up", Lyrics.choose(versions, 121_000).getFirst().text());
        assertNull(Lyrics.choose(versions, 200_000)); // no version fits: nothing beats lyrics at the wrong time
        // Length unknown: the length most versions have
        assertEquals("album", Lyrics.choose(versions, 0).getFirst().text());
        assertNull(Lyrics.choose(List.of(), 157_000));
        assertTrue(Lyrics.versions(JsonParser.parseString("{\"error\":1}"), "Song", "The Band").isEmpty());
    }

    @Test
    void playerReadingsMoveWithTime() {
        PlaybackClock.report("Song", 30_000, 1_000, true, PlaybackClock.PLAYER, 1_000);
        assertEquals(35_000, PlaybackClock.position("Song", true, 0, 6_000));
        assertEquals(-1, PlaybackClock.position("Other song", true, 0, 6_000));
        // Paused at 3 s after the reading: stays there
        assertEquals(33_000, PlaybackClock.position("song", false, 4_000, 60_000));
        PlaybackClock.report("Song", 40_000, 70_000, false, PlaybackClock.PLAYER, 70_000);
        assertEquals(40_000, PlaybackClock.position("Song", false, 0, 99_000));
    }

    @Test
    void guessStartsWhenTheSongChanges() {
        PlaybackClock.seen("First", true, 0); // already playing when the game started: unknown
        assertEquals(-1, PlaybackClock.position("First", true, 0, 1_000));
        PlaybackClock.seen("First", true, 2_000);
        PlaybackClock.seen("Second", true, 4_000); // changed while watching: it just started
        assertEquals(6_000, PlaybackClock.position("Second", true, 0, 10_000));
        PlaybackClock.seen("Second", false, 12_000); // paused: frozen at 8 s
        assertEquals(8_000, PlaybackClock.position("Second", false, 12_000, 30_000));
        PlaybackClock.seen("Second", true, 40_000); // resumed: on from 8 s
        assertEquals(13_000, PlaybackClock.position("Second", true, 0, 45_000));
    }

    @Test
    void remembersHowLongTheSongIs() {
        PlaybackClock.reportPlayer("Song", 1_000, 0, true, 157_169);
        assertEquals(157_169, PlaybackClock.length("song"));
        assertEquals(0, PlaybackClock.length("Another song"));
        PlaybackClock.reportPlayer("Another song", 1_000, 0, true, 0); // a reading without a length keeps the old one
        assertEquals(157_169, PlaybackClock.length("Song"));
    }

    @Test
    void guessNeverOverridesARealReading() {
        PlaybackClock.seen("A", true, 0);
        PlaybackClock.report("B", 90_000, 2_000, true, PlaybackClock.PLAYER, 2_000);
        PlaybackClock.seen("B", true, 2_000); // song change seen, but Spotify already said where it is
        assertEquals(93_000, PlaybackClock.position("B", true, 0, 5_000));
    }

    @Test
    void readsWindowsMediaControls() {
        long now = System.currentTimeMillis();
        WindowsMediaSession.Reading r = WindowsMediaSession.parse("S\tBlinding Lights\t42000\t" + now + "\t200000\tPlaying");
        assertEquals(new WindowsMediaSession.Reading("Blinding Lights", 42_000, now, true, 200_000), r);
        assertFalse(WindowsMediaSession.parse("S\tSong\t0\t" + now + "\t200000\tPaused").playing());
        assertNull(WindowsMediaSession.parse("N"));
        assertNull(WindowsMediaSession.parse("S\tSong\t0\t" + now + "\t0\tPaused")); // no timeline: Spotify paused
        assertNull(WindowsMediaSession.parse("S\tSong\t0\t" + (now - 600_000) + "\t200000\tPlaying")); // stale
        assertNull(WindowsMediaSession.parse("S\t\t1\t2\t3\tPlaying"));
        assertNull(WindowsMediaSession.parse("S\tSong\tx\t2\t3\tPlaying"));
    }

    @Test
    void queueRequests() {
        Matcher m = SpotifyChatClient.QUEUE.matcher("!queue never gonna give you up");
        assertTrue(m.matches());
        assertEquals("never gonna give you up", m.group(1));
        assertTrue(SpotifyChatClient.QUEUE.matcher("!QUEUE Blinding Lights").matches());
        assertTrue(SpotifyChatClient.QUEUE.matcher("!queue x").matches());
        assertFalse(SpotifyChatClient.QUEUE.matcher("!queue").matches());
        assertFalse(SpotifyChatClient.QUEUE.matcher("!queue   ").matches());
        assertFalse(SpotifyChatClient.QUEUE.matcher("!queue " + "a".repeat(121)).matches()); // fits an IRC line

        // The same in Hypixel guild / party / co-op chat
        Matcher g = SpotifyChatClient.CHANNEL_QUEUE.matcher("Guild > [MVP++] Doelloosleven [Admin]: !queue ggz");
        assertTrue(g.matches());
        assertEquals("Doelloosleven", g.group(2));
        assertEquals("!queue ggz", g.group(3));
        assertTrue(SpotifyChatClient.CHANNEL_QUEUE.matcher("Party > Doelvolleven: !queue Blinding Lights").matches());
        assertFalse(SpotifyChatClient.CHANNEL_QUEUE.matcher("Guild > [MVP++] Doelloosleven [Admin]: !queue").matches());
        assertFalse(SpotifyChatClient.CHANNEL_QUEUE.matcher("[MVP+] Someone: !queue in all chat").matches());
    }
}
