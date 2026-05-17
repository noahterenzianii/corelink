package com.corelink.bot;

import com.corelink.CoreLink;
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

import java.lang.reflect.Field;
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

    public static boolean spawnBot(String name, ServerPlayer owner) {
        if (ACTIVE_BOTS.containsKey(name)) return false;

        try {
            ServerLevel level = owner.level();
            // Standard offline-mode UUID generation (same as vanilla)
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(uuid, name);
            ClientInformation clientInfo = ClientInformation.createDefault();

            ServerPlayer bot = new ServerPlayer(server, level, profile, clientInfo);
            bot.setPos(owner.getX(), owner.getY(), owner.getZ());
            bot.setYRot(owner.getYRot());
            bot.setXRot(owner.getXRot());

            // Dummy connection with an in-memory Netty channel to satisfy Minecraft internals
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);

            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);

            ServerGamePacketListenerImpl packetListener = new ServerGamePacketListenerImpl(server, connection, bot, cookie);
            bot.connection = packetListener;

            // Creative mode prevents fall damage and other environmental harm
            bot.setGameMode(net.minecraft.world.level.GameType.CREATIVE);

            var playerList = server.getPlayerList();

            Field playersField = PlayerList.class.getDeclaredField("players");
            playersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ServerPlayer> players = (List<ServerPlayer>) playersField.get(playerList);
            players.add(bot);

            Field playersByUUIDField = PlayerList.class.getDeclaredField("playersByUUID");
            playersByUUIDField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, ServerPlayer> playersByUUID = (Map<UUID, ServerPlayer>) playersByUUIDField.get(playerList);
            playersByUUID.put(bot.getUUID(), bot);

            var updatePacket = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot));
            playerList.broadcastAll(updatePacket);

            level.addNewPlayer(bot);

            ACTIVE_BOTS.put(name, bot);

            CoreLink.DATABASE.saveBot(new BotRecord(name, uuid,
                level.dimension().identifier().toString(),
                owner.getX(), owner.getY(), owner.getZ()));

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

        try {
            // Tries the standard player removal path first
            server.getPlayerList().remove(bot);
        } catch (Exception e) {
            CoreLink.LOGGER.error("Failed to remove bot '{}' from player list", name, e);
            // Try direct removal if the method fails
            try {
                var playerList = server.getPlayerList();

                Field playersField = PlayerList.class.getDeclaredField("players");
                playersField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<ServerPlayer> players = (List<ServerPlayer>) playersField.get(playerList);
                players.remove(bot);

                Field playersByUUIDField = PlayerList.class.getDeclaredField("playersByUUID");
                playersByUUIDField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, ServerPlayer> playersByUUID = (Map<UUID, ServerPlayer>) playersByUUIDField.get(playerList);
                playersByUUID.remove(bot.getUUID());

                bot.level().removePlayerImmediately(bot, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            } catch (Exception e2) {
                CoreLink.LOGGER.error("Failed to directly remove bot '{}'", name, e2);
            }
        }

        ACTIVE_BOTS.remove(name);
        CoreLink.DATABASE.removeBot(name);
        return true;
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

    // ── Persistence ──────────────────────────────────────────────────

    private static void loadBots() {
        List<BotRecord> bots = CoreLink.DATABASE.getAllBots();
        for (BotRecord record : bots) {
            try {
                Identifier dimId = Identifier.parse(record.world());
                ServerLevel level = null;
                for (ResourceKey<Level> key : server.levelKeys()) {
                    if (key.identifier().equals(dimId)) {
                        level = server.getLevel(key);
                        break;
                    }
                }
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

                var playerList = server.getPlayerList();

                Field playersField = PlayerList.class.getDeclaredField("players");
                playersField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<ServerPlayer> players = (List<ServerPlayer>) playersField.get(playerList);
                players.add(bot);

                Field playersByUUIDField = PlayerList.class.getDeclaredField("playersByUUID");
                playersByUUIDField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, ServerPlayer> playersByUUID = (Map<UUID, ServerPlayer>) playersByUUIDField.get(playerList);
                playersByUUID.put(bot.getUUID(), bot);

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
