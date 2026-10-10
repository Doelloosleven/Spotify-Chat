package dev.spotifychat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GIFs and pictures in IRC chat. When an IRC line links to a GIF / PNG / JPEG on Discord, Tenor or Giphy,
 * it's downloaded (only from those sites, so nobody can grab your IP with a link), turned into one texture
 * holding every frame, and shown as a few extra chat lines under the message. Those lines are a single
 * marked space each; ChatLineMixin draws a slice of the picture instead, so it scrolls, fades and hides
 * with the rest of the chat. Everything here runs on the game thread except the download and decoding.
 */
public final class ChatImages {
    private ChatImages() {}

    /** Discord's links are about 10 MB at most; Tenor's and Giphy's small GIFs are well under that */
    static final int MAX_BYTES = 10 * 1024 * 1024;
    /** Pixels in one picture's texture (all frames); longer GIFs keep fewer frames */
    private static final int MAX_PIXELS = 4 * 1024 * 1024;
    /** Pictures kept as textures; one that scrolls back into view after that is loaded again */
    private static final int MAX_LOADED = 8;
    /** Chat keeps the last 100 messages, so older pictures can't be seen anymore */
    private static final int MAX_PICTURES = 100;
    private static final int LINE = 9; // chat line height at normal line spacing
    private static final String MARK = "spotifychat:picture:";
    private static final Set<String> HOSTS = Set.of(
            "cdn.discordapp.com", "media.discordapp.net",
            "media.tenor.com", "media1.tenor.com", "c.tenor.com",
            "i.giphy.com", "media.giphy.com", "media0.giphy.com", "media1.giphy.com", "media2.giphy.com",
            "media3.giphy.com", "media4.giphy.com");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER) // a redirect could lead anywhere
            .build();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Spotify Chat pictures");
        t.setDaemon(true);
        return t;
    });

    private enum State { LOADING, READY, UNLOADED, FAILED }

    private static final class Picture {
        final int id;
        final String url;
        /** Texture pixels per chat pixel */
        final int detail;
        State state = State.LOADING;
        int frameWidth, frameHeight; // texture pixels, fixed by the first load
        int columns, sheetWidth, sheetHeight;
        GifDecoder.Sheet timing;     // delays only, the pixels are on the GPU
        Identifier texture;
        long start, lastDrawn;

        Picture(int id, String url, int detail) {
            this.id = id;
            this.url = url;
            this.detail = detail;
        }

        /** Chat lines the picture covers */
        int rows() {
            return Math.max(1, frameHeight / (LINE * detail));
        }
    }

    private static final Map<Integer, Picture> pictures = new LinkedHashMap<>();
    private static int nextId;

    /** "GIF" or "image" for a link we'd show, otherwise null */
    public static String kind(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
            if (!HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getPort() != -1) return null;
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (path.endsWith(".gif")) return "GIF";
            if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image";
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Game thread. Loads the picture and adds it to chat (under the message it came with) once it's ready. */
    public static void show(String url) {
        ModConfig cfg = SpotifyChatClient.get().config();
        if (!cfg.chatImages || kind(url) == null) return;
        Minecraft mc = Minecraft.getInstance();
        int lines = Math.clamp(cfg.chatImageLines, ModConfig.MIN_IMAGE_LINES, ModConfig.MAX_IMAGE_LINES);
        double chatScale = mc.options.chatScale().get();
        int maxWidth = Math.max(LINE, (int) (ChatComponent.getWidth(mc.options.chatWidth().get()) / Math.max(0.05, chatScale)) - 2);
        // Texture pixels per chat pixel: about one per screen pixel, so it's sharp without wasting memory
        int detail = Math.clamp(Math.round(mc.getWindow().getGuiScale() * chatScale), 1, 3);

        Picture p = new Picture(nextId++, url, detail);
        pictures.put(p.id, p);
        prune();
        load(p, (w, h) -> fit(w, h, lines, maxWidth, detail));
    }

    /**
     * The texture size for a w x h picture: as tall as the chosen number of chat lines (times detail),
     * or fewer lines if that would be wider than the chat. Always a whole number of lines.
     */
    static int[] fit(int w, int h, int lines, int maxWidth, int detail) {
        int shownLines = lines;
        if (Math.round((double) lines * LINE * w / h) > maxWidth) {
            shownLines = Math.max(1, (int) ((double) maxWidth * h / w / LINE));
        }
        int shownHeight = shownLines * LINE;
        int shownWidth = (int) Math.max(1, Math.min(maxWidth, Math.round((double) shownHeight * w / h)));
        return new int[] {shownWidth * detail, shownHeight * detail};
    }

    private static void load(Picture p, GifDecoder.Fit fit) {
        p.state = State.LOADING;
        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = download(p.url);
                GifDecoder.Sheet sheet = GifDecoder.decode(bytes, fit, MAX_PIXELS);
                return new Object[] {sheet, image(sheet)};
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, WORKERS).whenComplete((result, err) -> Minecraft.getInstance().execute(() -> {
            if (err != null || result == null) {
                SpotifyChatClient.LOGGER.debug("Couldn't load picture {}", p.url, err);
                p.state = State.FAILED;
                return;
            }
            install(p, (GifDecoder.Sheet) result[0], (NativeImage) result[1]);
        }));
    }

    private static byte[] download(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SpotifyChat (Minecraft mod)").GET().build();
        HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
            long length = res.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (length > MAX_BYTES) throw new IllegalStateException("Too big: " + length);
            byte[] bytes = in.readNBytes(MAX_BYTES + 1); // never more than this in memory
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("Too big");
            return bytes;
        }
    }

    /** Off the game thread: copying millions of pixels would stutter the game */
    private static NativeImage image(GifDecoder.Sheet sheet) {
        NativeImage image = new NativeImage(sheet.width(), sheet.height(), false);
        int[] argb = sheet.argb();
        for (int y = 0; y < sheet.height(); y++) {
            for (int x = 0; x < sheet.width(); x++) image.setPixel(x, y, argb[y * sheet.width() + x]);
        }
        return image;
    }

    private static void install(Picture p, GifDecoder.Sheet sheet, NativeImage image) {
        if (!pictures.containsKey(p.id)) {
            image.close(); // scrolled out of chat history while loading
            return;
        }
        DynamicTexture texture = new DynamicTexture(() -> "spotifychat chat picture", image);
        texture.upload();
        Identifier id = Identifier.fromNamespaceAndPath("spotifychat", "picture/" + p.id);
        Minecraft.getInstance().getTextureManager().register(id, texture);

        boolean first = p.timing == null;
        p.texture = id;
        p.frameWidth = sheet.frameWidth();
        p.frameHeight = sheet.frameHeight();
        p.columns = sheet.columns();
        p.sheetWidth = sheet.width();
        p.sheetHeight = sheet.height();
        p.timing = new GifDecoder.Sheet(sheet.frameWidth(), sheet.frameHeight(), sheet.columns(), sheet.delays(),
                sheet.width(), sheet.height(), new int[0]);
        p.state = State.READY;
        p.lastDrawn = System.currentTimeMillis();
        if (first) {
            p.start = p.lastDrawn;
            Mc.chat().addClientSystemMessage(lines(p.id, p.rows()));
        }
        unloadOldest();
    }

    /** The chat lines the picture is drawn on: one marked space per line */
    private static Component lines(int id, int count) {
        MutableComponent c = Component.empty();
        for (int row = 0; row < count; row++) {
            if (row > 0) c.append("\n");
            String mark = MARK + id + ":" + row;
            c.append(Component.literal(" ").withStyle(s -> s.withInsertion(mark)));
        }
        return c;
    }

    /** Keeps the texture count down: the picture drawn longest ago is unloaded (and reloaded if seen again) */
    private static void unloadOldest() {
        long loaded = pictures.values().stream().filter(p -> p.texture != null).count();
        while (loaded > MAX_LOADED) {
            Picture oldest = null;
            for (Picture p : pictures.values()) {
                if (p.texture != null && (oldest == null || p.lastDrawn < oldest.lastDrawn)) oldest = p;
            }
            if (oldest == null) return;
            release(oldest);
            oldest.state = State.UNLOADED;
            loaded--;
        }
    }

    private static void prune() {
        Iterator<Picture> it = pictures.values().iterator();
        while (pictures.size() > MAX_PICTURES && it.hasNext()) {
            release(it.next());
            it.remove();
        }
    }

    private static void release(Picture p) {
        if (p.texture == null) return;
        Minecraft.getInstance().getTextureManager().release(p.texture);
        p.texture = null;
    }

    /**
     * Called for every chat line Minecraft draws (ChatLineMixin). For one of our picture lines it draws that
     * line's slice of the current frame and returns true, so Minecraft skips the line's text.
     * y is where the line's text would go; opacity is the line's fade.
     */
    public static boolean drawLine(GuiGraphicsExtractor g, int y, float opacity, FormattedCharSequence line) {
        String mark = mark(line);
        if (mark == null) return false;
        Picture p;
        int row;
        try {
            int colon = mark.indexOf(':', MARK.length());
            p = pictures.get(Integer.parseInt(mark.substring(MARK.length(), colon)));
            row = Integer.parseInt(mark.substring(colon + 1));
        } catch (RuntimeException e) {
            return true;
        }
        if (p == null || p.timing == null || !SpotifyChatClient.get().config().chatImages) return true;

        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        p.lastDrawn = now;
        if (p.state == State.UNLOADED) {
            int w = p.frameWidth, h = p.frameHeight;
            load(p, (sw, sh) -> new int[] {w, h});
        }

        // The same line height and text offset Minecraft uses for chat lines
        double spacing = mc.options.chatLineSpacing().get();
        int lineHeight = (int) (LINE * (spacing + 1));
        int textOffset = (int) Math.round(8.0 * (spacing + 1) - 4.0 * spacing);
        int top = y + textOffset - lineHeight;
        int alpha = Math.clamp(Math.round(opacity * 255), 0, 255);
        if (alpha <= 3) return true;

        if (p.texture == null) {
            if (row == 0) {
                String text = p.state == State.FAILED ? "(the GIF is gone)" : "Loading...";
                g.text(mc.font, Component.literal(text), 0, y, (alpha << 24) | 0x808080, false);
            }
            return true;
        }
        int rows = p.rows();
        if (row >= rows) return true;
        int rowPixels = p.frameHeight / rows;
        int frame = p.timing.frameAt(now - p.start);
        int u = (frame % p.columns) * p.frameWidth;
        int v = (frame / p.columns) * p.frameHeight + row * rowPixels;
        int width = Math.max(1, Math.round(p.frameWidth * lineHeight / (float) rowPixels));
        g.blit(RenderPipelines.GUI_TEXTURED, p.texture, 0, top, u, v, width, lineHeight,
                p.frameWidth, rowPixels, p.sheetWidth, p.sheetHeight, (alpha << 24) | 0xFFFFFF);
        return true;
    }

    /** The marker on a picture line, or null for a normal line */
    private static String mark(FormattedCharSequence line) {
        String[] found = new String[1];
        line.accept((index, style, codepoint) -> {
            found[0] = style.getInsertion();
            return false; // only the first character matters
        });
        return found[0] != null && found[0].startsWith(MARK) ? found[0] : null;
    }
}
