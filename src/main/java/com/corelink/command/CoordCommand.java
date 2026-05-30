package com.corelink.command;

import com.corelink.CoreLink;
import com.corelink.storage.CoordinateRecord;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class CoordCommand {

    static final DynamicCommandExceptionType ERROR_NOT_PLAYER =
        new DynamicCommandExceptionType(
            name -> Component.literal("Only players can use this command.").withStyle(ChatFormatting.RED)
        );

    // Suggests all existing coordinate names for tab completion
    static final SuggestionProvider<CommandSourceStack> SUGGEST_COORDS = (ctx, builder) -> {
        for (CoordinateRecord c : CoreLink.DATABASE.getAllCoordinates()) {
            builder.suggest(c.name());
        }
        return builder.buildFuture();
    };

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var coord = dispatcher.register(literal("coord")
                .then(literal("set")
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> executeSet(ctx, StringArgumentType.getString(ctx, "name"), null))
                        .then(argument("description", StringArgumentType.greedyString())
                            .executes(ctx -> executeSet(ctx, StringArgumentType.getString(ctx, "name"),
                                StringArgumentType.getString(ctx, "description")))
                        )
                    )
                )
                .then(literal("del")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_COORDS)
                        .executes(ctx -> executeRemove(ctx, StringArgumentType.getString(ctx, "name")))
                    )
                )
                .then(literal("remove")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_COORDS)
                        .executes(ctx -> executeRemove(ctx, StringArgumentType.getString(ctx, "name")))
                    )
                )
                .then(literal("list")
                    .executes(ctx -> executeList(ctx))
                )
            );

            // Alias under mod namespace to avoid conflicts with other mods
            dispatcher.register(literal("corelink:coord").redirect(coord));
        });
    }

    // /coord set <name> [description]
    private static int executeSet(CommandContext<CommandSourceStack> ctx, String name,
                                   String description) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!source.isPlayer()) throw ERROR_NOT_PLAYER.create("set");
        var player = source.getPlayer();

        Vec3 pos = player.position();
        String world = player.level().dimension().identifier().toString();
        UUID uuid = player.getUUID();

        CoreLink.DATABASE.saveCoordinate(uuid, name, world, pos.x, pos.y, pos.z, description);

        source.sendSuccess(() -> Component.literal("✓ Coordinate ")
            .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" saved.").withStyle(ChatFormatting.GREEN)), false);
        return Command.SINGLE_SUCCESS;
    }

    // /coord del <name>  or  /coord remove <name>
    private static int executeRemove(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();

        boolean deleted = CoreLink.DATABASE.deleteCoordinate(name);
        if (deleted) {
            source.sendSuccess(() -> Component.literal("✓ Coordinate ")
                .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" deleted.").withStyle(ChatFormatting.GREEN)), false);
        } else {
            source.sendFailure(Component.literal("✗ Coordinate ")
                .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" not found.").withStyle(ChatFormatting.RED)));
        }
        return Command.SINGLE_SUCCESS;
    }

    // /coord list
    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<CoordinateRecord> coords = CoreLink.DATABASE.getAllCoordinates();

        if (coords.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No coordinates saved yet.")
                .withStyle(ChatFormatting.GOLD), false);
            return Command.SINGLE_SUCCESS;
        }

        // Header
        source.sendSuccess(() -> Component.literal("── Coordinates (" + coords.size() + ") ──")
            .withStyle(ChatFormatting.GOLD), false);

        // Each coordinate on its own line(s)
        for (CoordinateRecord c : coords) {
            source.sendSuccess(() -> formatCoordinate(c), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ── Formatting helpers ────────────────────────────────────────────

    private static Component formatCoordinate(CoordinateRecord c) {
        MutableComponent msg = Component.literal("")
            .append(Component.literal("▸ ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(c.name()).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal("  ").withStyle(ChatFormatting.GRAY))
            .append(formatPosition(c))
            .append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("┗ ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(c.world()).withStyle(ChatFormatting.WHITE));

        if (c.description() != null) {
            msg.append(Component.literal("  ┃ "))
               .append(Component.literal(c.description()).withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY));
        }
        return msg;
    }

    private static Component formatPosition(CoordinateRecord c) {
        return Component.literal("")
            .append(Component.literal(String.valueOf(formatCoord(c.x()))).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.valueOf(formatCoord(c.y()))).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.valueOf(formatCoord(c.z()))).withStyle(ChatFormatting.AQUA));
    }

    // Renders clean integer or 1-decimal string
    private static String formatCoord(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((int) v);
        }
        return String.format("%.1f", v);
    }
}
