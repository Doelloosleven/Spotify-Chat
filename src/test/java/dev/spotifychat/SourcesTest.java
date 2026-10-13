package dev.spotifychat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The extra lyrics sources (NetEase, Kugou) and where album, artists and cover come from */
class SourcesTest {
    @Test
    void creditsAndTitleLinesAreLeftOut() {
        List<Lyrics.Line> netease = Lyrics.clean(Lyrics.parse("""
                [00:00.000] 作词 : JPEG MAFIA
                [00:00.278] 作曲 : JPEG MAFIA
                [00:00.557]You think you know me
                [00:04.051]In the morning"""), "HAZARD DUTY PAY!", "JPEGMAFIA");
        assertEquals(List.of(new Lyrics.Line(557, "You think you know me"), new Lyrics.Line(4_051, "In the morning")), netease);

        List<Lyrics.Line> kugou = Lyrics.clean(Lyrics.parse("""
                [ti:HAZARD DUTY PAY! (Explicit)]
                [00:00.00]HAZARD DUTY PAY! (Explicit) - JPEGMAFIA
                [00:00.10]Lyrics by：JPEG MAFIA
                [00:00.21]Composed by：JPEG MAFIA
                [00:00.32]You think you know me"""), "HAZARD DUTY PAY!", "JPEGMAFIA");
        assertEquals(List.of(new Lyrics.Line(320, "You think you know me")), kugou);

        // Only credits, or "instrumental, enjoy": no lyrics at all
        assertTrue(Lyrics.clean(Lyrics.parse("[00:00.00] 作词 : X\n[00:01.00] 纯音乐，请欣赏"), "Song", "X").isEmpty());
        // A normal line that happens to have a colon stays
        assertEquals(1, Lyrics.clean(Lyrics.parse("[00:10.00] Listen: I'm here"), "Song", "X").size());
    }

    @Test
    void neteaseResultsOfThisSongClosestFirst() {
        String json = """
                {"result":{"songs":[
                 {"id":2049604884,"name":"HAZARD DUTY PAY!","ar":[{"name":"JPEGMAFIA"}],"al":{"name":"OFFLINE!"},"dt":157169},
                 {"id":1920679307,"name":"HAZARD DUTY PAY!","ar":[{"name":"JPEGMAFIA"}],"al":{"name":"single"},"dt":156900},
                 {"id":1889856020,"name":"HAZARD DUTY PAY! (feat. DJ NIC NAC) (JPEGMAFIA TYPE)","ar":[{"name":"xan"}],"dt":240000},
                 {"id":5,"name":"Other Song","ar":[{"name":"JPEGMAFIA"}],"dt":157000},
                 {"id":"1; drop","name":"HAZARD DUTY PAY!","ar":[{"name":"JPEGMAFIA"}],"dt":157169},
                 {"id":7,"name":"HAZARD DUTY PAY!","ar":[{"name":"JPEGMAFIA"}],"dt":146000}]}}""";
        List<Lyrics.Candidate> found = Lyrics.neteaseCandidates(JsonParser.parseString(json), "HAZARD DUTY PAY!", "JPEGMAFIA", 157_000);
        assertEquals(List.of("1920679307", "2049604884"), found.stream().map(Lyrics.Candidate::id).toList());
        // Length unknown: every one of this song, as listed
        assertEquals(3, Lyrics.neteaseCandidates(JsonParser.parseString(json), "HAZARD DUTY PAY!", "JPEGMAFIA", 0).size());
        assertTrue(Lyrics.neteaseCandidates(JsonParser.parseString("{\"code\":-462}"), "a", "b", 0).isEmpty());
    }

    @Test
    void kugouResultsOfThisSong() {
        String json = """
                {"candidates":[
                 {"id":"439235816","accesskey":"B8B63CC5DA8BCBAC3C4572DF55D748A0","singer":"JPEGMAFIA","song":"HAZARD DUTY PAY! (Explicit)","duration":157204},
                 {"id":"1","accesskey":"x&id=2","singer":"JPEGMAFIA","song":"HAZARD DUTY PAY!","duration":157204},
                 {"id":"2","accesskey":"ABC","singer":"Daft Punk、The Weeknd","song":"HAZARD DUTY PAY!","duration":157204}]}""";
        List<Lyrics.Candidate> found = Lyrics.kugouCandidates(JsonParser.parseString(json), "HAZARD DUTY PAY!", "JPEGMAFIA", 157_169);
        assertEquals(List.of(new Lyrics.Candidate("439235816", "B8B63CC5DA8BCBAC3C4572DF55D748A0", 157_204)), found);
        // Several singers: any of them counts
        assertEquals(1, Lyrics.kugouCandidates(JsonParser.parseString(json), "HAZARD DUTY PAY!", "The Weeknd", 0).size());
    }

    @Test
    void spotifySearchGivesAlbumArtistsAndCover() {
        String json = """
                {"tracks":{"items":[
                 {"name":"Get Lucky - Radio Edit","artists":[{"name":"Someone Else"}],"album":{"name":"Wrong"}},
                 {"name":"Get Lucky (feat. Pharrell Williams and Nile Rodgers)","artists":[{"name":"Daft Punk"},{"name":"Pharrell Williams"},{"name":"Nile Rodgers"}],
                  "album":{"name":"Random Access Memories","images":[{"url":"https://i.scdn.co/image/big","width":640},{"url":"https://i.scdn.co/image/mid","width":300},{"url":"https://i.scdn.co/image/small","width":64}]}}]}}""";
        TrackInfoLookup.Info info = TrackInfoLookup.fromSpotify(JsonParser.parseString(json), "Daft Punk", "Get Lucky",
                "Get Lucky (feat. Pharrell Williams and Nile Rodgers)");
        assertEquals(new TrackInfoLookup.Info("Random Access Memories", List.of("Daft Punk", "Pharrell Williams", "Nile Rodgers"),
                "https://i.scdn.co/image/mid"), info);
        assertNull(TrackInfoLookup.fromSpotify(JsonParser.parseString(json), "Daft Punk", "Around the World", "Around the World"));
    }

    @Test
    void neteaseGivesAlbumArtistsAndCover() {
        String json = """
                {"result":{"songs":[{"name":"Blinding Lights","ar":[{"name":"The Weeknd"}],
                 "al":{"name":"After Hours","picUrl":"http://p2.music.126.net/abc==/109951.jpg"}}]}}""";
        TrackInfoLookup.Info info = TrackInfoLookup.fromNetease(JsonParser.parseString(json).getAsJsonObject(), "The Weeknd", "Blinding Lights");
        assertEquals(new TrackInfoLookup.Info("After Hours", List.of("The Weeknd"),
                "https://p2.music.126.net/abc==/109951.jpg?param=300y300"), info);
        assertNull(TrackInfoLookup.fromNetease(JsonParser.parseString(json).getAsJsonObject(), "Someone", "Blinding Lights"));
        assertEquals("", TrackInfoLookup.neteaseCover("https://evil.example/x.jpg"));
        assertEquals("", TrackInfoLookup.neteaseCover("file:///C:/x.jpg"));
    }

    @Test
    void coversFromTheMacAndLinuxPlayer() {
        assertEquals("https://i.scdn.co/image/ab67616d00001e02aabbccdd", LocalSpotify.spotifyCover("https://i.scdn.co/image/ab67616d00001e02aabbccdd"));
        assertEquals("https://i.scdn.co/image/ab67616d00001e02aabbccdd", LocalSpotify.spotifyCover("https://open.spotify.com/image/ab67616d00001e02aabbccdd"));
        assertEquals("", LocalSpotify.spotifyCover("file:///home/me/cover.png"));
        assertEquals("", LocalSpotify.spotifyCover("https://i.scdn.co/image/../../x"));
        assertEquals("", LocalSpotify.spotifyCover(""));

        SpotifyClient.Track t = LocalSpotify.parseLines("Song\nArtist\nAlbum\nplaying\n12.5\n200000\nhttps://i.scdn.co/image/ab67616d00001e02aabbccdd",
                "playing", 1000, 1).orElseThrow();
        assertEquals("https://i.scdn.co/image/ab67616d00001e02aabbccdd", t.coverUrl());
        assertEquals("Album", t.album());
        // Without the cover line (older Spotify): still read
        assertEquals("", LocalSpotify.parseLines("Song\nArtist\nAlbum\nplaying\n12.5\n200000", "playing", 1000, 1).orElseThrow().coverUrl());
    }

    @Test
    void albumFromWindowsMediaControls() {
        WindowsMediaSession.readAlbum("S\tBUTTERMILK JESUS TYPE BEAT\t0\t0\t0\tPaused\tAll My Heroes Are Cornballs");
        assertEquals("All My Heroes Are Cornballs", WindowsMediaSession.album("BUTTERMILK JESUS TYPE BEAT"));
        assertNull(WindowsMediaSession.album("Another song"));
        WindowsMediaSession.readAlbum("S\tSong\t0\t0\t0\tPlaying\t");
        assertNull(WindowsMediaSession.album("Song")); // Spotify didn't say
        // Old scripts without the album field still give the position
        assertEquals(42_000, WindowsMediaSession.parse("S\tSong\t42000\t" + System.currentTimeMillis() + "\t200000\tPlaying").positionMs());
    }
}
