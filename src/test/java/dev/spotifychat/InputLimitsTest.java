package dev.spotifychat;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputLimitsTest {
    @Test
    void songStartingWithSlashStaysChat() {
        assertEquals(" /kill @a", SpotifyChatClient.openChatText("/kill @a"));
        assertEquals("♫ Now playing: Song", SpotifyChatClient.openChatText("♫ Now playing: Song"));
        String longOne = "/" + "x".repeat(300);
        assertTrue(SpotifyChatClient.openChatText(longOne).length() <= 256);
        assertTrue(SpotifyChatClient.openChatText(longOne).startsWith(" /"));
    }

    @Test
    void deezerTrackIdsAreDigitsOnly() {
        assertTrue(TrackInfoLookup.isTrackId("3135556"));
        assertFalse(TrackInfoLookup.isTrackId(""));
        assertFalse(TrackInfoLookup.isTrackId("123?x=1"));
        assertFalse(TrackInfoLookup.isTrackId("../user/me"));
        assertFalse(TrackInfoLookup.isTrackId("12a"));
        assertFalse(TrackInfoLookup.isTrackId("１２３")); // full-width digits aren't plain digits
    }

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void hugeImagesAreRefusedBeforeDecoding() throws Exception {
        assertNotNull(CoverTextures.decode(png(250, 250)));
        assertNull(CoverTextures.decode(png(CoverTextures.MAX_SIDE + 1, 1)));
        assertNull(CoverTextures.decode("not an image".getBytes()));
    }
}
