package com.corelink.bot;

import com.corelink.CoreLink;
import com.corelink.bot.BotPlayer;
import com.corelink.mixin.PlayerListAccessor;
import com.corelink.storage.BotRecord;

import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.network.Connection;
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
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Manages the lifecycle of bot players: creation, removal, persistence,
 * and automatic reload on server start.
 */
public class BotManager {

    // Names are matched case-insensitively, consistent with the SQLite schema
    // (UNIQUE COLLATE NOCASE); the map keeps the first spelling of each name
    // for display and persistence.
    private static final Map<String, ServerPlayer> ACTIVE_BOTS =
        Collections.synchronizedMap(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
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
            UUID uuid = botUuid(name);
            GameProfile profile = new GameProfile(uuid, name);
            ClientInformation clientInfo = ClientInformation.createDefault();

            ServerPlayer bot = new ServerPlayer(server, level, profile, clientInfo);
            bot.setPos(x, y, z);
            bot.setYRot(yRot);
            bot.setXRot(xRot);
            bot.setYHeadRot(yRot);

            // Connection that discards outbound packets (see BotConnection),
            // backed by an in-memory Netty channel to satisfy Minecraft internals
            Connection connection = new BotConnection();
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
            deleteBotFiles(bot);
            ACTIVE_BOTS.remove(name);
            CoreLink.DATABASE.removeBot(name);
        }
        return removed;
    }

    // ── Queries ──────────────────────────────────────────────────────

    public static List<String> getActiveBotNames() {
        synchronized (ACTIVE_BOTS) {
            return new ArrayList<>(ACTIVE_BOTS.keySet());
        }
    }

    public static int getActiveBotCount() {
        synchronized (ACTIVE_BOTS) {
            return ACTIVE_BOTS.size();
        }
    }

    public static boolean isBotOnline(String name) {
        return ACTIVE_BOTS.containsKey(name);
    }

    // ── Utilities ────────────────────────────────────────────────────

    /**
     * Derives a bot's UUID from a dedicated namespace instead of the vanilla
     * offline-player one, so a bot can never collide with a real player on
     * an offline-mode server (vanilla would hand them the bot's saved data).
     */
    private static UUID botUuid(String name) {
        return UUID.nameUUIDFromBytes(("corelink-bot:" + name).getBytes(StandardCharsets.UTF_8));
    }

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
        cleanupLegacyBotFiles(bots);
        for (BotRecord record : bots) {
            try {
                ServerLevel level = resolveLevel(record.world(), server);
                if (level == null) {
                    CoreLink.LOGGER.warn("Cannot load bot '{}': dimension '{}' not found", record.name(), record.world());
                    continue;
                }

                // Recompute the UUID from the name: bots persisted with the
                // legacy "OfflinePlayer:" namespace migrate on the next save
                UUID uuid = botUuid(record.name());
                GameProfile profile = new GameProfile(uuid, record.name());
                ClientInformation clientInfo = ClientInformation.createDefault();

                ServerPlayer bot = new ServerPlayer(server, level, profile, clientInfo);
                bot.setPos(record.x(), record.y(), record.z());

                Connection connection = new BotConnection();
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
        synchronized (ACTIVE_BOTS) {
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
    }

    /** Removes every active bot from the server on shutdown. */
    private static void removeAllBots() {
        synchronized (ACTIVE_BOTS) {
            for (ServerPlayer bot : ACTIVE_BOTS.values()) {
                try {
                    server.getPlayerList().remove(bot);
                    deleteBotFiles(bot);
                } catch (Exception e) {
                    CoreLink.LOGGER.error("Failed to remove bot during cleanup", e);
                }
            }
            ACTIVE_BOTS.clear();
        }
    }

    // ── Storage cleanup ──────────────────────────────────────────────

    /**
     * Deletes the per-player files vanilla writes when a bot is removed
     * (playerdata, stats, advancements). No one ever reads them — bots are
     * recreated from the database — so deleting them keeps the world folder
     * clean. Best-effort: failures are logged and never abort the removal.
     */
    private static void deleteBotFiles(ServerPlayer bot) {
        deletePlayerFiles(bot.getUUID(), bot.getGameProfile().name());
    }

    /**
     * Removes the per-player files written by CoreLink 1.1.0, which derived
     * bot UUIDs from the vanilla offline-player namespace. Those UUIDs are
     * dead now, but on an offline-mode server a real player named like a bot
     * would still load the bot's stale data, so the files are deleted to give
     * such a player a clean start.
     */
    private static void cleanupLegacyBotFiles(List<BotRecord> bots) {
        for (BotRecord record : bots) {
            UUID legacyUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + record.name()).getBytes(StandardCharsets.UTF_8));
            if (server.getPlayerList().getPlayer(legacyUuid) != null) {
                CoreLink.LOGGER.warn(
                    "Skipping legacy bot cleanup for '{}': UUID is in use by an online player",
                    record.name());
                continue;
            }
            deletePlayerFiles(legacyUuid, record.name());
        }
    }

    /** Deletes the playerdata, stats and advancements files for one UUID. */
    private static void deletePlayerFiles(UUID uuid, String displayName) {
        for (Path path : playerFilePaths(uuid)) {
            try {
                if (Files.deleteIfExists(path)) {
                    CoreLink.LOGGER.info("Removed bot file {} ({})", path, displayName);
                }
            } catch (Exception e) {
                CoreLink.LOGGER.warn("Failed to remove bot file {}", path, e);
            }
        }
    }

    /** Per-player files vanilla saves under the world folder, keyed by UUID. */
    private static List<Path> playerFilePaths(UUID uuid) {
        return List.of(
            server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat"),
            server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat_old"),
            server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(uuid + ".json"),
            server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json"));
    }
}
