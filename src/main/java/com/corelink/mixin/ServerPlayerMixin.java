package com.corelink.mixin;

import com.corelink.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin implements BotPlayer {
    @Unique
    private boolean corelink$isBot = false;

    @Override
    public boolean corelink$isBot() {
        return this.corelink$isBot;
    }

    @Override
    public void corelink$setBot(boolean bot) {
        this.corelink$isBot = bot;
    }
}
