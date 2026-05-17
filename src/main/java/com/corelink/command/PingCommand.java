package com.corelink.command;

import com.corelink.CoreLink;
import com.corelink.storage.CoordinateRecord;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class PingCommand {

    private static final SimpleCommandExceptionType ERROR_SAME_WORLD =
        new SimpleCommandExceptionType(
            Component.literal("You must be in the same dimension to ping.").withStyle(ChatFormatting.RED)
        );
    private static final DynamicCommandExceptionType ERROR_TARGET_NOT_FOUND =
        new DynamicCommandExceptionType(
            name -> Component.literal("Player ").withStyle(ChatFormatting.RED)
                .append(Component.literal(String.valueOf(name)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" is not online or coordinate doesn't exist.").withStyle(ChatFormatting.RED))
        );

    // Active pings: player UUID → session. Thread-safe for tick access.
    private static final Map<UUID, PingSession> ACTIVE_PINGS = new ConcurrentHashMap<>();

    // Throttle counter: update ActionBar every 2 ticks
    private static int tickCounter = 0;

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var ping = dispatcher.register(literal("ping")
                .then(literal("stop")
                    .executes(PingCommand::executeStop)
                )
                .then(argument("target", StringArgumentType.word())
                    .executes(ctx -> executePing(ctx, StringArgumentType.getString(ctx, "target"), 10))
                    .then(argument("seconds", IntegerArgumentType.integer(1, 300))
                        .executes(ctx -> executePing(ctx, StringArgumentType.getString(ctx, "target"),
                            IntegerArgumentType.getInteger(ctx, "seconds")))
                    )
                )
            );

            dispatcher.register(literal("corelink:ping").redirect(ping));
        });

        // Tick handler: updates ActionBars for all active pings
        ServerTickEvents.START_SERVER_TICK.register(PingCommand::onTick);
    }

    // ── Command handlers ────────────────────────────────────────────

    private static int executePing(CommandContext<CommandSourceStack> ctx, String target, int seconds)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!source.isPlayer()) throw CoordCommand.ERROR_NOT_PLAYER.create("ping");
        ServerPlayer player = source.getPlayer();

        long endTime = System.currentTimeMillis() + seconds * 1000L;

        // Try to resolve as a coordinate first
        CoordinateRecord coord = CoreLink.DATABASE.getCoordinate(target);
        if (coord != null) {
            if (!coord.world().equals(player.level().dimension().identifier().toString())) {
                throw ERROR_SAME_WORLD.create();
            }
            ACTIVE_PINGS.put(player.getUUID(), new PingSession(coord.name(), coord.world(),
                coord.x(), coord.y(), coord.z(), endTime));
            source.sendSuccess(() -> Component.literal("✓ Ping set to ")
                .append(Component.literal(coord.name()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" for " + seconds + "s.").withStyle(ChatFormatting.GREEN)), false);
            return Command.SINGLE_SUCCESS;
        }

        // Try to resolve as a player
        MinecraftServer server = source.getServer();
        Optional<ServerPlayer> targetPlayer = server.getPlayerList().getPlayers().stream()
            .filter(p -> p.getGameProfile().name().equalsIgnoreCase(target))
            .findFirst();

        if (targetPlayer.isEmpty()) {
            throw ERROR_TARGET_NOT_FOUND.create(target);
        }

        ServerPlayer tp = targetPlayer.get();
        if (!tp.level().dimension().identifier().toString().equals(player.level().dimension().identifier().toString())) {
            throw ERROR_SAME_WORLD.create();
        }

        Vec3 tpPos = tp.position();
          ACTIVE_PINGS.put(player.getUUID(), new PingSession(tp.getGameProfile().name(),
            tp.level().dimension().identifier().toString(),
            tpPos.x, tpPos.y, tpPos.z, endTime));
        source.sendSuccess(() -> Component.literal("✓ Ping set to ")
            .append(Component.literal(tp.getGameProfile().name()).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" for " + seconds + "s.").withStyle(ChatFormatting.GREEN)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!source.isPlayer()) throw CoordCommand.ERROR_NOT_PLAYER.create("ping stop");
        ServerPlayer player = source.getPlayer();

        PingSession removed = ACTIVE_PINGS.remove(player.getUUID());
        if (removed != null) {
            source.sendSuccess(() -> Component.literal("✓ Ping stopped.").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal("No active ping.").withStyle(ChatFormatting.GOLD), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ── Tick handler ────────────────────────────────────────────────

    private static void onTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < 2) return; // update every 2 ticks
        tickCounter = 0;

        if (ACTIVE_PINGS.isEmpty()) return;

        ACTIVE_PINGS.entrySet().removeIf(entry -> {
            UUID playerId = entry.getKey();
            PingSession ping = entry.getValue();

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !player.isAlive()) return true; // player left

            if (ping.isExpired()) {
                player.sendSystemMessage(
                    Component.literal("◆ Ping expired: ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(ping.displayName()).withStyle(ChatFormatting.YELLOW)),
                    false
                );
                return true;
            }

            // Same-world check
            if (!player.level().dimension().identifier().toString().equals(ping.world())) {
                player.sendSystemMessage(
                    Component.literal("◆ ").withStyle(ChatFormatting.RED)
                        .append(Component.literal(ping.displayName()).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" is in another dimension.").withStyle(ChatFormatting.RED)),
                    false
                );
                return true;
            }

            // Build and send the ActionBar indicator
            player.sendSystemMessage(buildIndicator(player, ping), true);
            return false;
        });
    }

    // ── Direction indicator ─────────────────────────────────────────

    private static Component buildIndicator(ServerPlayer player, PingSession ping) {
        Vec3 pos = player.position();
        double dx = ping.x() - pos.x;
        double dz = ping.z() - pos.z;
        double dy = ping.y() - pos.y;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double vertDist = Math.abs(dy);

        // Horizontal direction arrow
        String arrow = "↑";
        if (dist > 0.5) {
            float yaw = player.getYRot();
            double lx = -Math.sin(Math.toRadians(yaw));
            double lz = Math.cos(Math.toRadians(yaw));
            double tx = dx / dist;
            double tz = dz / dist;
            double cross = lx * tz - lz * tx;
            double dot = lx * tx + lz * tz;
            double angle = Math.toDegrees(Math.atan2(cross, dot));

            if (angle > 135 || angle < -135) arrow = "↓";
            else if (angle > 45) arrow = "→";
            else if (angle < -45) arrow = "←";
        }

        // Vertical indicator
        String vert;
        if (vertDist < 2) {
            vert = "";
        } else if (dy > 0) {
            vert = "  ▲ " + (int) vertDist;
        } else {
            vert = "  ▼ " + (int) vertDist;
        }

        String distStr = dist < 1 ? "< 1" : String.valueOf((int) dist);

        return Component.literal("")
            .append(Component.literal(arrow + " ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(ping.displayName()).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal("  ┃  ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(distStr + "m").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(vert).withStyle(ChatFormatting.GRAY));
    }
}
