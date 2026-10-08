package dev.spotifychat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

import static dev.spotifychat.SpotifyUi.*;

/** Drag the overlay to where you want it; scroll to make it bigger or smaller. */
public class OverlayPositionScreen extends Screen {
    private static final SpotifyClient.Track SAMPLE =
            new SpotifyClient.Track("Song Name", List.of("Artist", "Feature"), "Album Name", true);

    private final Screen parent;
    private final ModConfig cfg;
    private int[] box = {0, 0, 0, 0}; // x, y, w, h of the overlay as drawn last frame
    private boolean dragging;
    private double grabX, grabY;

    public OverlayPositionScreen(Screen parent) {
        super(Component.literal("Move overlay"));
        this.parent = parent;
        this.cfg = SpotifyChatClient.get().config();
    }

    @Override
    protected void init() {
        int y = height - 28;
        addRenderableWidget(new Button(width / 2 - 104, y, 100, 20, "Reset", false, () -> {
            cfg.overlayX = 0.01;
            cfg.overlayY = 0.30;
            cfg.overlayScale = 100;
            cfg.save();
        }));
        addRenderableWidget(new Button(width / 2 + 4, y, 100, 20, "Done", true, this::onClose));
    }

    private SpotifyClient.Track track() {
        SpotifyClient.Track t = SpotifyChatClient.get().overlayTrack();
        return t != null ? t : SAMPLE; // nothing playing: show an example so there's something to drag
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x88000000);
        // Center lines help to line it up
        g.fill(width / 2, 0, width / 2 + 1, height, 0x30FFFFFF);
        g.fill(0, height / 2, width, height / 2 + 1, 0x30FFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        box = SpotifyOverlay.drawAtConfiguredSpot(g, font, track(), cfg, width, height);
        boolean hover = dragging || inside(mouseX, mouseY);
        g.outline(box[0] - 1, box[1] - 1, box[2] + 2, box[3] + 2, hover ? accentLight() : accent());

        String help = "Drag to move  •  Scroll to resize (" + cfg.overlayScale + "%)  •  Esc when done";
        g.centeredText(font, help, width / 2, 10, WHITE);
    }

    private boolean inside(double mx, double my) {
        return mx >= box[0] && mx < box[0] + box[2] && my >= box[1] && my < box[1] + box[3];
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true; // buttons first
        if (event.button() == 0 && inside(event.x(), event.y())) {
            dragging = true;
            grabX = event.x() - box[0];
            grabY = event.y() - box[1];
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!dragging) return super.mouseDragged(event, dragX, dragY);
        double x = Math.max(0, Math.min(width - box[2], event.x() - grabX));
        double y = Math.max(0, Math.min(height - box[3], event.y() - grabY));
        cfg.overlayX = x / width;
        cfg.overlayY = y / height;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            cfg.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        cfg.overlayScale = Math.max(50, Math.min(200, cfg.overlayScale + (int) Math.signum(scrollY) * 10));
        cfg.save();
        return true;
    }

    @Override
    public void onClose() {
        cfg.save();
        Mc.setScreen(parent);
    }
}
