package dev.spotifychat;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoversTest {
    @Test
    void namesMatchLoosely() {
        assertEquals("jayz", TrackInfoLookup.loose("JAŸ-Z"));
        assertEquals("jayz", TrackInfoLookup.loose("Jay Z"));
        assertTrue(TrackInfoLookup.sameArtist("Beyonce", "Beyoncé"));
        assertTrue(TrackInfoLookup.sameArtist("A$AP Rocky", "ASAP Rocky"));
        assertTrue(TrackInfoLookup.sameArtist("Joey Valence & Brae", "Joey Valence and Brae"));
        assertFalse(TrackInfoLookup.sameArtist("Tyler, The Creator", "Tyler"));
        assertFalse(TrackInfoLookup.sameArtist("", ""));
        assertEquals("Crazy in Love", TrackInfoLookup.cleanTitle("Crazy in Love (feat. JAŸ-Z)"));
        assertEquals("Dreams", TrackInfoLookup.cleanTitle("Dreams - 2004 Remaster"));
    }

    @Test
    void spotifysOwnCoverGoesToTheOverlay() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        WindowsMediaSession.readCover("C\tCrazy in Love (feat. JAŸ-Z)\t" + Base64.getEncoder().encodeToString(png));
        String url = WindowsMediaSession.coverUrl("Crazy in Love (feat. JAŸ-Z)");
        assertTrue(url.startsWith(WindowsMediaSession.COVER_PREFIX));
        assertArrayEquals(png, WindowsMediaSession.coverBytes(url));
        assertNull(WindowsMediaSession.coverUrl("Another song"));

        // The same picture again keeps its URL (no reload); a new one gets a new URL, the old one is gone
        WindowsMediaSession.readCover("C\tCrazy in Love (feat. JAŸ-Z)\t" + Base64.getEncoder().encodeToString(png));
        assertEquals(url, WindowsMediaSession.coverUrl("Crazy in Love (feat. JAŸ-Z)"));
        WindowsMediaSession.readCover("C\tNext Song\t" + Base64.getEncoder().encodeToString(new byte[] {1}));
        assertNotEquals(url, WindowsMediaSession.coverUrl("Next Song"));
        assertNull(WindowsMediaSession.coverBytes(url));
        WindowsMediaSession.readCover("C\tbroken"); // ignored
        WindowsMediaSession.readCover("C\tSong\tnot base64!!");
        assertTrue(WindowsMediaSession.coverUrl("Next Song") != null);
    }
}
