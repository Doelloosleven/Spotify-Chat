package dev.spotifychat;

import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Turns a GIF (or a PNG / JPEG) into one sprite sheet: every frame scaled to the same size and laid out in a
 * grid, plus how long each frame shows. Plain Java (ImageIO), so it runs off the game thread and in tests.
 * GIF frames often only hold what changed, so each one is drawn onto a canvas the way browsers do it
 * (disposal methods included) before it's scaled down. Everything is size-checked before it's decoded,
 * so a small file claiming a huge picture can't eat memory.
 */
final class GifDecoder {
    private GifDecoder() {}

    /** Pictures bigger than this on a side are refused (a 1080p screen recording still fits) */
    static final int MAX_SIDE = 2048;
    static final int MAX_SOURCE_FRAMES = 1000;
    /** Longer GIFs keep every 2nd / 3rd / ... frame (with the time of the skipped ones) */
    static final int MAX_FRAMES = 150;
    static final int MAX_SHEET_SIDE = 4096;
    private static final long TIME_LIMIT_NANOS = 8_000_000_000L;
    /** Browsers show frames shorter than 20 ms for 100 ms, and GIFs are made to look right there */
    static final int MIN_DELAY_MS = 20, DEFAULT_DELAY_MS = 100;

    /** The frame size for a picture of this size: {width, height} */
    interface Fit {
        int[] size(int width, int height);
    }

    /**
     * Frame f is at column f % columns, row f / columns, each frameWidth x frameHeight.
     * argb is width x height, row by row. A still picture is one frame with delay 0.
     */
    record Sheet(int frameWidth, int frameHeight, int columns, int[] delays, int width, int height, int[] argb) {
        int frames() {
            return delays.length;
        }

        int totalMs() {
            int total = 0;
            for (int d : delays) total += d;
            return total;
        }

        /** The frame showing this many ms after the GIF started (it loops forever) */
        int frameAt(long ms) {
            int total = totalMs();
            if (total <= 0 || frames() == 1) return 0;
            long t = Math.floorMod(ms, (long) total);
            for (int f = 0; f < delays.length; f++) {
                t -= delays[f];
                if (t < 0) return f;
            }
            return delays.length - 1;
        }
    }

    private enum Disposal { KEEP, CLEAR, RESTORE }

    private record FrameInfo(int left, int top, int width, int height, Disposal disposal, int delayMs) {}

    /** Throws IOException if it isn't a picture we can read or it's too big */
    static Sheet decode(byte[] bytes, Fit fit, int maxPixels) throws IOException {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("Not a picture");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, false);
                return decode(reader, fit, maxPixels);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Sheet decode(ImageReader reader, Fit fit, int maxPixels) throws IOException {
        boolean gif = "gif".equalsIgnoreCase(reader.getFormatName());
        int[] screen = gif ? logicalScreen(reader) : null;
        if (screen == null) screen = new int[] {reader.getWidth(0), reader.getHeight(0)};
        int sw = screen[0], sh = screen[1];
        if (sw < 1 || sh < 1 || sw > MAX_SIDE || sh > MAX_SIDE) throw new IOException("Picture too big: " + sw + "x" + sh);

        int[] size = fit.size(sw, sh);
        int w = size[0], h = size[1];
        if (w < 1 || h < 1 || w > MAX_SHEET_SIDE || h > MAX_SHEET_SIDE || (long) w * h > maxPixels) {
            throw new IOException("Bad frame size " + w + "x" + h);
        }

        int count;
        try {
            count = gif ? Math.min(reader.getNumImages(true), MAX_SOURCE_FRAMES) : 1;
        } catch (IOException | RuntimeException e) {
            count = MAX_SOURCE_FRAMES; // a damaged GIF: read until it breaks
        }
        int capacity = (MAX_SHEET_SIDE / w) * (MAX_SHEET_SIDE / h);
        int keep = Math.max(1, Math.min(MAX_FRAMES, Math.min(capacity, (int) Math.min(Integer.MAX_VALUE, maxPixels / ((long) w * h)))));
        int step = Math.max(1, (count + keep - 1) / keep);

        BufferedImage canvas = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
        int[] saved = null;
        FrameInfo previous = null;
        List<int[]> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        long deadline = System.nanoTime() + TIME_LIMIT_NANOS;

        for (int i = 0; i < count && System.nanoTime() < deadline; i++) {
            FrameInfo info;
            BufferedImage frame;
            try {
                info = gif ? frameInfo(reader.getImageMetadata(i), sw, sh) : new FrameInfo(0, 0, sw, sh, Disposal.KEEP, 0);
                if (info.width > MAX_SIDE || info.height > MAX_SIDE) break;
                frame = reader.read(i);
            } catch (IndexOutOfBoundsException e) {
                break; // no more frames
            } catch (IOException | RuntimeException e) {
                if (frames.isEmpty()) throw new IOException("Broken picture", e);
                break; // keep the frames that did decode
            }

            // What the previous frame asked to happen to its spot once it's done
            if (previous != null && previous.disposal == Disposal.CLEAR) {
                clear(pixels, sw, sh, previous);
            } else if (previous != null && previous.disposal == Disposal.RESTORE && saved != null) {
                System.arraycopy(saved, 0, pixels, 0, pixels.length);
            }
            if (info.disposal == Disposal.RESTORE) {
                if (saved == null) saved = new int[pixels.length];
                System.arraycopy(pixels, 0, saved, 0, pixels.length);
            }
            Graphics2D g = canvas.createGraphics();
            g.setComposite(AlphaComposite.SrcOver);
            g.drawImage(frame, info.left, info.top, null);
            g.dispose();
            previous = info;

            int delay = !gif ? 0 : info.delayMs < MIN_DELAY_MS ? DEFAULT_DELAY_MS : info.delayMs;
            if (i % step == 0) {
                frames.add(scale(canvas, w, h));
                delays.add(delay);
            } else {
                delays.set(delays.size() - 1, delays.getLast() + delay); // a skipped frame's time goes to the one shown
            }
        }
        if (frames.isEmpty()) throw new IOException("No frames");

        int n = frames.size();
        int columns = Math.min(n, MAX_SHEET_SIDE / w);
        int rows = (n + columns - 1) / columns;
        int width = columns * w, height = rows * h;
        int[] argb = new int[width * height];
        for (int f = 0; f < n; f++) {
            int[] src = frames.get(f);
            int x0 = (f % columns) * w, y0 = (f / columns) * h;
            for (int y = 0; y < h; y++) System.arraycopy(src, y * w, argb, (y0 + y) * width + x0, w);
        }
        int[] d = delays.stream().mapToInt(Integer::intValue).toArray();
        if (n == 1) d = new int[] {0};
        return new Sheet(w, h, columns, d, width, height, argb);
    }

    /** The GIF's own size (frames are drawn onto it), or null if the file doesn't say */
    private static int[] logicalScreen(ImageReader reader) throws IOException {
        IIOMetadata meta = reader.getStreamMetadata();
        if (meta == null) return null;
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_stream_1.0");
        IIOMetadataNode screen = child(root, "LogicalScreenDescriptor");
        if (screen == null) return null;
        int w = intAttr(screen, "logicalScreenWidth", 0), h = intAttr(screen, "logicalScreenHeight", 0);
        return w > 0 && h > 0 ? new int[] {w, h} : null;
    }

    private static FrameInfo frameInfo(IIOMetadata meta, int sw, int sh) {
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");
        IIOMetadataNode desc = child(root, "ImageDescriptor");
        IIOMetadataNode control = child(root, "GraphicControlExtension");
        int left = desc == null ? 0 : intAttr(desc, "imageLeftPosition", 0);
        int top = desc == null ? 0 : intAttr(desc, "imageTopPosition", 0);
        int width = desc == null ? sw : intAttr(desc, "imageWidth", sw);
        int height = desc == null ? sh : intAttr(desc, "imageHeight", sh);
        Disposal disposal = Disposal.KEEP;
        int delayMs = 0;
        if (control != null) {
            disposal = switch (control.getAttribute("disposalMethod")) {
                case "restoreToBackgroundColor" -> Disposal.CLEAR;
                case "restoreToPrevious" -> Disposal.RESTORE;
                default -> Disposal.KEEP;
            };
            delayMs = intAttr(control, "delayTime", 0) * 10;
        }
        return new FrameInfo(left, top, width, height, disposal, delayMs);
    }

    /** "Restore to background": browsers make that spot transparent */
    private static void clear(int[] pixels, int sw, int sh, FrameInfo f) {
        int x0 = Math.max(0, f.left), y0 = Math.max(0, f.top);
        int x1 = Math.min(sw, f.left + f.width), y1 = Math.min(sh, f.top + f.height);
        for (int y = y0; y < y1; y++) Arrays.fill(pixels, y * sw + x0, Math.max(y * sw + x0, y * sw + x1), 0);
    }

    /** Scales in halving steps first, so a big GIF shrunk a lot still looks smooth */
    static int[] scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        int cw = src.getWidth(), ch = src.getHeight();
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            cur = draw(cur, cw, ch);
        }
        if (cw != w || ch != h) cur = draw(cur, w, h);
        return cur.getRGB(0, 0, w, h, null, 0, w); // a copy, in plain (not premultiplied) ARGB
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        // Premultiplied, so transparent edges don't turn dark when they're blended
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equals(name)) return (IIOMetadataNode) n;
        }
        return null;
    }

    private static int intAttr(IIOMetadataNode node, String name, int fallback) {
        try {
            return Integer.parseInt(node.getAttribute(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
