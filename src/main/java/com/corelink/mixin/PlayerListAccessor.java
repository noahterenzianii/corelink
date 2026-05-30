package com.corelink.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mixin(PlayerList.class)
public interface PlayerListAccessor {

    @Accessor("players")
    List<ServerPlayer> getPlayers();

    @Accessor("playersByUUID")
    Map<UUID, ServerPlayer> getPlayersByUuid();
}
