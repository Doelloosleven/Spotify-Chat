package dev.spotifychat.mixin;

import com.mojang.brigadier.suggestion.Suggestions;
import dev.spotifychat.SongSuggestions;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/** Tab after "!queue " in chat suggests songs instead of player names (see SongSuggestions) */
@Mixin(value = CommandSuggestions.class, remap = false) // Minecraft 26.x isn't obfuscated
abstract class ChatSuggestionsMixin {
    @Shadow
    @Final
    private EditBox input;

    @Shadow
    private CompletableFuture<Suggestions> pendingSuggestions;

    @Inject(method = "updateCommandInfo", at = @At("TAIL"))
    private void spotifychat$suggestSongs(CallbackInfo ci) {
        CompletableFuture<Suggestions> songs = SongSuggestions.suggest(input.getValue(), input.getCursorPosition());
        if (songs != null) pendingSuggestions = songs;
    }
}
