package dev.spotifychat.mixin;

import dev.spotifychat.ChatImages;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat draws each line through one of these two (closed chat / open chat). Lines that hold a GIF from IRC
 * get the picture drawn instead of their text (see ChatImages).
 */
@Mixin(targets = {
        "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess",
        "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess"},
        remap = false) // Minecraft 26.x isn't obfuscated, so names never need remapping
abstract class ChatLineMixin {
    @Shadow(remap = false)
    @Final
    private GuiGraphicsExtractor graphics;

    @Inject(method = "handleMessage", at = @At("HEAD"), cancellable = true)
    private void spotifychat$drawPicture(int y, float opacity, FormattedCharSequence line,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (ChatImages.drawLine(graphics, y, opacity, line)) cir.setReturnValue(false);
    }
}
