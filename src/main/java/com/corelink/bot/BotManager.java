package com.corelink.bot;

import com.corelink.CoreLink;
import com.corelink.bot.BotPlayer;
import com.corelink.mixin.PlayerListAccessor;
import com.corelink.storage.BotRecord;

import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of bot players: creation, removal, persistence,
 * and automatic reload on server start.
 */
public class BotManager {

    private static final Map<String, ServerPlayer> ACTIVE_BOTS = new ConcurrentHashMap<>();
    private static MinecraftServer server;

    // ── Registration ──────────────────────────────────────────────────

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            loadBots();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            saveBots();
            removeAllBots();
            server = null;
        });
    }

    // ── Spawn ────────────────────────────────────────────────────────

    public static boolean spawnBot(String name, ServerLevel level,
                                    double x, double y, double z,
                                    float yRot, float xRot) {
        if (ACTIVE_BOTS.containsKey(name)) return false;

        try {
            // Standard offline-mode UUID generation (same as vanilla)
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(uuid, name);
            ClientInformation clientInfo = ClientInformation.createDefault();

            ServerPlayer bot = new ServerPlayer(server, level, profile, clientInfo);
            bot.setPos(x, y, z);
            bot.setYRot(yRot);
            bot.setXRot(xRot);
            bot.setYHeadRot(yRot);

            // Dummy connection with an in-memory Netty channel to satisfy Minecraft internals
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);

            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

            ServerGamePacketListenerImpl packetListener = new ServerGamePacketListenerImpl(server, connection, bot, cookie);
            bot.connection = packetListener;

            // Creative mode prevents fall damage and other environmental harm
            bot.setGameMode(net.minecraft.world.level.GameType.CREATIVE);

            // Mark this player as a bot so it won't be counted for sleep requirements
            ((BotPlayer) bot).corelink$setBot(true);

            var playerList = server.getPlayerList();
            var playerListAccessor = (PlayerListAccessor) playerList;

            playerListAccessor.getPlayers().add(bot);
            playerListAccessor.getPlayersByUuid().put(bot.getUUID(), bot);

            var updatePacket = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot));
            playerList.broadcastAll(updatePacket);

            level.addNewPlayer(bot);

            ACTIVE_BOTS.put(name, bot);

            CoreLink.DATABASE.saveBot(new BotRecord(name, uuid,
                level.dimension().identifier().toString(),
                x, y, z));

            CoreLink.LOGGER.info("Bot '{}' spawned successfully", name);
            return true;
        } catch (Exception e) {
            CoreLink.LOGGER.error("Failed to spawn bot '{}'", name, e);
            return false;
        }
    }

    // ── Kill ─────────────────────────────────────────────────────────

    public static boolean killBot(String name) {
        ServerPlayer bot = ACTIVE_BOTS.get(name);
        if (bot == null) return false;

        boolean removed = false;
        try {
            server.getPlayerList().remove(bot);
            removed = true;
        } catch (Exception e) {
            CoreLink.LOGGER.error("Failed to remove bot '{}' from player list", name, e);
            try {
                var playerList = server.getPlayerList();
                var playerListAccessor = (PlayerListAccessor) playerList;

                playerListAccessor.getPlayers().remove(bot);
                playerListAccessor.getPlayersByUuid().remove(bot.getUUID());

                bot.level().removePlayerImmediately(bot, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                removed = true;
            } catch (Exception e2) {
                CoreLink.LOGGER.error("Failed to directly remove bot '{}'", name, e2);
            }
        }

        if (removed) {
            ACTIVE_BOTS.remove(name);
            CoreLink.DATABASE.removeBot(name);
        }
        return removed;
    }

    // ── Queries ──────────────────────────────────────────────────────

    public static List<String> getActiveBotNames() {
        return new ArrayList<>(ACTIVE_BOTS.keySet());
    }

    public static int getActiveBotCount() {
        return ACTIVE_BOTS.size();
    }

    public static boolean isBotOnline(String name) {
        return ACTIVE_BOTS.containsKey(name);
    }

    // ── Utilities ────────────────────────────────────────────────────

    public static ServerLevel resolveLevel(String dimensionId, MinecraftServer srv) {
        Identifier dimId = Identifier.parse(dimensionId);
        for (ResourceKey<Level> key : srv.levelKeys()) {
            if (key.identifier().equals(dimId)) {
                return srv.getLevel(key);
            }
        }
        return null;
    }

    // ── Persistence ──────────────────────────────────────────────────

    private static void loadBots() {
        List<BotRecord> bots = CoreLink.DATABASE.getAllBots();
        for (BotRecord record : bots) {
            try {
                ServerLevel level = resolveLevel(record.world(), server);
                if (level == null) {
                    CoreLink.LOGGER.warn("Cannot load bot '{}': dimension '{}' not found", record.name(), record.world());
                    continue;
                }

                UUID uuid = record.uuid();
                GameProfile profile = new GameProfile(uuid, record.name());
                ClientInformation clientInfo = ClientInformation.createDefault();

                ServerPlayer bot = new ServerPlayer(server, level, profile, clientInfo);
                bot.setPos(record.x(), record.y(), record.z());

                Connection connection = new Connection(PacketFlow.SERVERBOUND);
                new EmbeddedChannel(connection);

                CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

                ServerGamePacketListenerImpl packetListener = new ServerGamePacketListenerImpl(server, connection, bot, cookie);
                bot.connection = packetListener;

                bot.setGameMode(net.minecraft.world.level.GameType.CREATIVE);

                ((BotPlayer) bot).corelink$setBot(true);

                var playerList = server.getPlayerList();
                var playerListAccessor = (PlayerListAccessor) playerList;

                playerListAccessor.getPlayers().add(bot);
                playerListAccessor.getPlayersByUuid().put(bot.getUUID(), bot);

                var updatePacket = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot));
                playerList.broadcastAll(updatePacket);

                level.addNewPlayer(bot);

                ACTIVE_BOTS.put(record.name(), bot);
                CoreLink.LOGGER.info("Loaded bot '{}' at dimension {}", record.name(), record.world());
            } catch (Exception e) {
                CoreLink.LOGGER.error("Failed to load bot '{}'", record.name(), e);
            }
        }
        if (!bots.isEmpty()) {
            CoreLink.LOGGER.info("Loaded {} bot(s) from database", bots.size());
        }
    }

    /** Persists every active bot's current position to the database. */
    private static void saveBots() {
        for (Map.Entry<String, ServerPlayer> entry : ACTIVE_BOTS.entrySet()) {
            ServerPlayer bot = entry.getValue();
            CoreLink.DATABASE.saveBot(new BotRecord(
                entry.getKey(),
                bot.getUUID(),
                bot.level().dimension().identifier().toString(),
                bot.getX(),
                bot.getY(),
                bot.getZ()
            ));
        }
    }

    /** Removes every active bot from the server on shutdown. */
    private static void removeAllBots() {
        for (ServerPlayer bot : ACTIVE_BOTS.values()) {
            try {
                server.getPlayerList().remove(bot);
            } catch (Exception e) {
                CoreLink.LOGGER.error("Failed to remove bot during cleanup", e);
            }
        }
        ACTIVE_BOTS.clear();
    }
}
