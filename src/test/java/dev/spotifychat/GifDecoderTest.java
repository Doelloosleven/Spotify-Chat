package dev.spotifychat;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GifDecoderTest {
    private static final int RED = 0xFFFF0000, BLUE = 0xFF0000FF, CLEAR = 0;
    private static final GifDecoder.Fit SAME = (w, h) -> new int[] {w, h};

    private record Frame(int x, int y, int w, int h, int color, String disposal, int delayCs) {}

    @Test
    void composesFramesLikeABrowser() throws IOException {
        byte[] gif = gif(new Frame(0, 0, 4, 4, RED, "none", 5),
                new Frame(2, 2, 2, 2, BLUE, "none", 1)); // 10 ms: browsers show that for 100 ms
        GifDecoder.Sheet s = GifDecoder.decode(gif, SAME, 1_000_000);
        assertEquals(2, s.frames());
        assertArrayEquals(new int[] {50, 100}, s.delays());
        assertEquals(RED, pixel(s, 0, 3, 3));
        assertEquals(RED, pixel(s, 1, 0, 0)); // the first frame stays under the second
        assertEquals(RED, pixel(s, 1, 1, 3));
        assertEquals(BLUE, pixel(s, 1, 2, 2));
        assertEquals(BLUE, pixel(s, 1, 3, 3));
    }

    @Test
    void restoreToBackgroundClearsTheSpot() throws IOException {
        byte[] gif = gif(new Frame(0, 0, 4, 4, RED, "restoreToBackgroundColor", 10),
                new Frame(0, 0, 1, 1, BLUE, "none", 10));
        GifDecoder.Sheet s = GifDecoder.decode(gif, SAME, 1_000_000);
        assertEquals(BLUE, pixel(s, 1, 0, 0));
        assertEquals(CLEAR, pixel(s, 1, 3, 3) >>> 24); // transparent where the red frame was
    }

    @Test
    void restoreToPreviousPutsTheOldPictureBack() throws IOException {
        byte[] gif = gif(new Frame(0, 0, 4, 4, RED, "none", 10),
                new Frame(0, 0, 4, 4, BLUE, "restoreToPrevious", 10),
                new Frame(0, 0, 1, 1, BLUE, "none", 10));
        GifDecoder.Sheet s = GifDecoder.decode(gif, SAME, 1_000_000);
        assertEquals(BLUE, pixel(s, 1, 3, 3));
        assertEquals(RED, pixel(s, 2, 3, 3)); // frame 2 was undone before frame 3
        assertEquals(BLUE, pixel(s, 2, 0, 0));
    }

    @Test
    void longGifsKeepFewerFramesButTheSameLength() throws IOException {
        Frame[] frames = new Frame[GifDecoder.MAX_FRAMES * 2 + 10];
        for (int i = 0; i < frames.length; i++) frames[i] = new Frame(0, 0, 2, 2, i % 2 == 0 ? RED : BLUE, "none", 4);
        GifDecoder.Sheet s = GifDecoder.decode(gif(frames), SAME, 1_000_000);
        assertTrue(s.frames() <= GifDecoder.MAX_FRAMES, "frames: " + s.frames());
        assertEquals(frames.length * 40, s.totalMs());
    }

    @Test
    void framesFitThePixelBudget() throws IOException {
        Frame[] frames = new Frame[40];
        for (int i = 0; i < frames.length; i++) frames[i] = new Frame(0, 0, 10, 10, RED, "none", 10);
        GifDecoder.Sheet s = GifDecoder.decode(gif(frames), SAME, 10 * 10 * 8);
        assertTrue(s.frames() <= 8, "frames: " + s.frames());
        assertEquals(4000, s.totalMs());
    }

    @Test
    void scalesAndLaysOutTheSheet() throws IOException {
        byte[] gif = gif(new Frame(0, 0, 40, 20, RED, "none", 10), new Frame(0, 0, 40, 20, BLUE, "none", 10),
                new Frame(0, 0, 40, 20, RED, "none", 10));
        GifDecoder.Sheet s = GifDecoder.decode(gif, (w, h) -> new int[] {w / 4, h / 4}, 1_000_000);
        assertEquals(10, s.frameWidth());
        assertEquals(5, s.frameHeight());
        assertEquals(s.width() * s.height(), s.argb().length);
        assertEquals(BLUE, pixel(s, 1, 5, 2));
    }

    @Test
    void stillPicture() throws IOException {
        BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, RED);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        GifDecoder.Sheet s = GifDecoder.decode(out.toByteArray(), SAME, 1_000_000);
        assertEquals(1, s.frames());
        assertEquals(0, s.frameAt(12345));
        assertEquals(RED, pixel(s, 0, 0, 0));
    }

    @Test
    void refusesHugeAndBrokenFiles() throws IOException {
        byte[] huge = gif(new Frame(0, 0, GifDecoder.MAX_SIDE + 1, 1, RED, "none", 10));
        assertThrows(IOException.class, () -> GifDecoder.decode(huge, SAME, Integer.MAX_VALUE));
        assertThrows(IOException.class, () -> GifDecoder.decode("not a gif".getBytes(), SAME, 1_000_000));
        byte[] gif = gif(new Frame(0, 0, 4, 4, RED, "none", 10));
        assertThrows(IOException.class, () -> GifDecoder.decode(gif, SAME, 4)); // frame bigger than the budget
        byte[] cut = java.util.Arrays.copyOf(gif, gif.length / 2);
        assertThrows(IOException.class, () -> GifDecoder.decode(cut, SAME, 1_000_000));
    }

    @Test
    void frameAtLoops() {
        GifDecoder.Sheet s = new GifDecoder.Sheet(1, 1, 3, new int[] {100, 50, 50}, 3, 1, new int[3]);
        assertEquals(0, s.frameAt(0));
        assertEquals(0, s.frameAt(99));
        assertEquals(1, s.frameAt(100));
        assertEquals(2, s.frameAt(150));
        assertEquals(0, s.frameAt(200));
        assertEquals(1, s.frameAt(1_000_000_120L));
    }

    @Test
    void onlyPicturesFromDiscordTenorAndGiphy() {
        assertEquals("GIF", ChatImages.kind("https://media.tenor.com/lfDATg4Bhc0AAAAM/happy-cat.gif"));
        assertEquals("GIF", ChatImages.kind("https://cdn.discordapp.com/attachments/1/2/cat.GIF?ex=1&is=2&hm=3"));
        assertEquals("GIF", ChatImages.kind("https://media.giphy.com/media/LptvdvZLb3KLHJLc0W/200.gif"));
        assertEquals("image", ChatImages.kind("https://media.discordapp.net/attachments/1/2/screenshot.png"));
        assertEquals("image", ChatImages.kind("https://cdn.discordapp.com/attachments/1/2/photo.jpeg"));
        assertNull(ChatImages.kind("http://media.tenor.com/x/happy-cat.gif"));        // not https
        assertNull(ChatImages.kind("https://evil.example/cat.gif"));                  // would see your IP
        assertNull(ChatImages.kind("https://media.tenor.com.evil.example/cat.gif"));
        assertNull(ChatImages.kind("https://cdn.discordapp.com:8443/cat.gif"));
        assertNull(ChatImages.kind("https://media.tenor.com/x/happy-cat.mp4"));
        assertNull(ChatImages.kind("https://tenor.com/view/happy-cat-gif-10804346947536782797"));
        assertNull(ChatImages.kind("not a url"));
    }

    @Test
    void fitsInTheChat() {
        // 6 lines tall at detail 2: 108 texture pixels high, width by the picture's shape
        assertArrayEquals(new int[] {144, 108}, ChatImages.fit(400, 300, 6, 300, 2));
        // Too wide for a 100 wide chat at 6 lines: fewer lines
        int[] wide = ChatImages.fit(1000, 100, 6, 100, 1);
        assertEquals(9, wide[1]);
        assertTrue(wide[0] <= 100);
        // Tall and thin stays thin
        assertArrayEquals(new int[] {27, 54}, ChatImages.fit(100, 200, 6, 300, 1));
    }

    private static int pixel(GifDecoder.Sheet s, int frame, int x, int y) {
        int fx = (frame % s.columns()) * s.frameWidth(), fy = (frame / s.columns()) * s.frameHeight();
        return s.argb()[(fy + y) * s.width() + fx + x];
    }

    /** A GIF with exact colors (red, blue) and the given frame positions, disposals and delays */
    private static byte[] gif(Frame... frames) throws IOException {
        byte[] r = {(byte) 255, 0}, g = {0, 0}, b = {0, (byte) 255};
        IndexColorModel palette = new IndexColorModel(1, 2, r, g, b);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null); // the GIF's size is the first frame's size
            for (Frame f : frames) {
                BufferedImage img = new BufferedImage(f.w, f.h, BufferedImage.TYPE_BYTE_BINARY, palette);
                for (int y = 0; y < f.h; y++) for (int x = 0; x < f.w; x++) img.setRGB(x, y, f.color);
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null);
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode desc = new IIOMetadataNode("ImageDescriptor");
                desc.setAttribute("imageLeftPosition", String.valueOf(f.x));
                desc.setAttribute("imageTopPosition", String.valueOf(f.y));
                desc.setAttribute("imageWidth", String.valueOf(f.w));
                desc.setAttribute("imageHeight", String.valueOf(f.h));
                desc.setAttribute("interlaceFlag", "FALSE");
                replace(root, desc);
                IIOMetadataNode control = new IIOMetadataNode("GraphicControlExtension");
                control.setAttribute("disposalMethod", f.disposal);
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", String.valueOf(f.delayCs));
                control.setAttribute("transparentColorIndex", "0");
                replace(root, control);
                IIOMetadataNode table = new IIOMetadataNode("LocalColorTable"); // the writer's default is black / white
                table.setAttribute("sizeOfLocalColorTable", "2");
                table.setAttribute("sortFlag", "FALSE");
                for (int i = 0; i < 2; i++) {
                    IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
                    entry.setAttribute("index", String.valueOf(i));
                    entry.setAttribute("red", String.valueOf(r[i] & 0xFF));
                    entry.setAttribute("green", String.valueOf(g[i] & 0xFF));
                    entry.setAttribute("blue", String.valueOf(b[i] & 0xFF));
                    table.appendChild(entry);
                }
                replace(root, table);
                meta.setFromTree("javax_imageio_gif_image_1.0", root);
                writer.writeToSequence(new IIOImage(img, null, meta), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void replace(IIOMetadataNode root, IIOMetadataNode node) {
        List<org.w3c.dom.Node> old = new ArrayList<>();
        for (org.w3c.dom.Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equals(node.getNodeName())) old.add(n);
        }
        old.forEach(root::removeChild);
        root.appendChild(node);
    }
}
