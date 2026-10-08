package dev.spotifychat;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrcTextTest {
    private static BoundedLineReader reader(String text, int maxBytes) {
        return new BoundedLineReader(new BufferedInputStream(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))), maxBytes);
    }

    @Test
    void tooLongLineIsSkippedUpToItsLineBreak() throws Exception {
        BoundedLineReader in = reader("first\r\n" + "x".repeat(5000) + "\r\nnext\nlast", 2048);
        assertEquals("first", in.readLine());
        assertEquals("next", in.readLine());
        assertEquals("last", in.readLine());
        assertNull(in.readLine());
    }

    @Test
    void lineOfExactlyTheLimitIsKept() throws Exception {
        BoundedLineReader in = reader("y".repeat(2048) + "\n" + "z".repeat(2049) + "\nend\n", 2048);
        assertEquals("y".repeat(2048), in.readLine());
        assertEquals("end", in.readLine());
        assertNull(in.readLine());
    }

    @Test
    void tooLongLastLineWithoutBreakEndsTheStream() throws Exception {
        BoundedLineReader in = reader("ok\n" + "x".repeat(3000), 2048);
        assertEquals("ok", in.readLine());
        assertNull(in.readLine());
    }

    @Test
    void outgoingIsCutByUtf8BytesOnCharacterBoundaries() {
        // é is 2 bytes: 300 of them are 600 bytes, so 200 fit in 400 bytes
        assertEquals("é".repeat(200), IrcClient.outgoing("é".repeat(300)));
        // emoji are 4 bytes and two Java chars; never cut in half
        String emoji = "🎵";
        assertEquals(emoji.repeat(100), IrcClient.outgoing(emoji.repeat(150)));
        assertEquals("a".repeat(399), IrcClient.outgoing("a".repeat(399) + emoji));
        assertTrue(IrcClient.outgoing("ü".repeat(350)).getBytes(StandardCharsets.UTF_8).length <= 400);
    }

    @Test
    void outgoingHasNoLineBreaksOrControlCharacters() {
        assertEquals("hi  JOIN #x", IrcClient.outgoing("hi\r\nJOIN #x"));
        assertEquals("short", IrcClient.outgoing("short"));
    }
}
