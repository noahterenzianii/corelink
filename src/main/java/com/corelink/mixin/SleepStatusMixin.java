package com.corelink.mixin;

import com.corelink.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SleepStatus.class)
public class SleepStatusMixin {
    @Redirect(
        method = "update",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z")
    )
    private boolean excludeBotFromSleepCount(ServerPlayer player) {
        return player.isSpectator() || ((BotPlayer) player).corelink$isBot();
    }
}
