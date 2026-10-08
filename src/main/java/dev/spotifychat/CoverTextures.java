package dev.spotifychat;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads album covers for the overlay and turns them into a Minecraft texture, plus the cover's colors.
 * Only the current cover is kept in memory; the previous one is released when the next one is ready.
 * Covers are JPEGs, so they're decoded with Java's ImageIO and copied pixel by pixel.
 */
public final class CoverTextures {
    private CoverTextures() {}

    /** Covers are scaled down to this; the overlay draws them at 32 GUI pixels */
    private static final int SIZE = 96;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private record Pixels(int[] argb, CoverColors.Palette palette) {}

    // Only touched on the render thread
    private static String requestedUrl = "";
    private static String readyUrl = "";
    private static String failedUrl = "";
    private static Identifier readyId;
    private static CoverColors.Palette readyPalette;
    private static int counter;

    /** Render thread. The texture for this cover if it's loaded; otherwise starts loading it and returns null. */
    public static Identifier get(String url) {
        if (url == null || url.isBlank()) return null;
        if (!url.equals(requestedUrl)) {
            requestedUrl = url;
            CompletableFuture.supplyAsync(() -> download(url)).whenComplete((pixels, err) -> {
                if (err != null || pixels == null) {
                    SpotifyChatClient.LOGGER.debug("Cover download failed: {}", url, err);
                    Minecraft.getInstance().execute(() -> failedUrl = url);
                    return;
                }
                Minecraft.getInstance().execute(() -> install(url, pixels));
            });
        }
        return url.equals(readyUrl) ? readyId : null;
    }

    /** Render thread. Colors from this cover; null while it loads, if it failed, or for a black-and-white cover. */
    public static CoverColors.Palette palette(String url) {
        if (get(url) == null) return null;
        return readyPalette;
    }

    /** Render thread. True while this cover is still downloading. */
    public static boolean loading(String url) {
        return url != null && !url.isBlank() && !url.equals(readyUrl) && !url.equals(failedUrl);
    }

    private static Pixels download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) return null;
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(res.body()));
            if (src == null) return null;

            BufferedImage scaled = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, SIZE, SIZE, null);
            g.dispose();
            int[] argb = scaled.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
            return new Pixels(argb, CoverColors.from(argb));
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.debug("Could not load cover {}", url, e);
            return null;
        }
    }

    private static void install(String url, Pixels pixels) {
        if (!url.equals(requestedUrl)) return; // the song changed while downloading
        NativeImage image = new NativeImage(SIZE, SIZE, false);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setPixel(x, y, pixels.argb()[y * SIZE + x] | 0xFF000000);
            }
        }
        DynamicTexture texture = new DynamicTexture(() -> "spotifychat album cover", image);
        texture.upload();
        Identifier id = Identifier.fromNamespaceAndPath("spotifychat", "cover/" + counter++);
        Minecraft.getInstance().getTextureManager().register(id, texture);

        Identifier old = readyId;
        readyId = id;
        readyUrl = url;
        readyPalette = pixels.palette();
        if (old != null) Minecraft.getInstance().getTextureManager().release(old);
    }
}
