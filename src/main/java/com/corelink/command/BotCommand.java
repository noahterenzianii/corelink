package com.corelink.command;

import com.corelink.bot.BotManager;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class BotCommand {

    // ── Error types ──────────────────────────────────────────────────

    static final DynamicCommandExceptionType ERROR_NOT_PLAYER =
        new DynamicCommandExceptionType(
            name -> Component.literal("Only players can use this command.").withStyle(ChatFormatting.RED)
        );

    private static final SimpleCommandExceptionType ERROR_BOT_ALREADY_EXISTS =
        new SimpleCommandExceptionType(
            Component.literal("A bot with that name already exists.").withStyle(ChatFormatting.RED)
        );

    private static final SimpleCommandExceptionType ERROR_SPAWN_FAILED =
        new SimpleCommandExceptionType(
            Component.literal("Failed to spawn bot. Check server logs for details.").withStyle(ChatFormatting.RED)
        );

    private static final DynamicCommandExceptionType ERROR_BOT_NOT_FOUND =
        new DynamicCommandExceptionType(
            name -> Component.literal("Bot ").withStyle(ChatFormatting.RED)
                .append(Component.literal(String.valueOf(name)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" not found.").withStyle(ChatFormatting.RED))
        );

    // ── Suggestions ──────────────────────────────────────────────────

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_BOTS = (ctx, builder) -> {
        for (String name : BotManager.getActiveBotNames()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    };

    // ── Registration ──────────────────────────────────────────────────

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var bot = dispatcher.register(literal("bot")
                .then(literal("spawn")
                    .then(argument("name", StringArgumentType.word())
                        .executes(ctx -> executeSpawn(ctx, StringArgumentType.getString(ctx, "name")))
                    )
                )
                .then(literal("kill")
                    .then(argument("name", StringArgumentType.word())
                        .suggests(SUGGEST_BOTS)
                        .executes(ctx -> executeKill(ctx, StringArgumentType.getString(ctx, "name")))
                    )
                )
                .then(literal("list")
                    .executes(ctx -> executeList(ctx))
                )
            );

            dispatcher.register(literal("corelink:bot").redirect(bot));
        });
    }

    // ── Command handlers ────────────────────────────────────────────

    // /bot spawn <name>
    private static int executeSpawn(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!source.isPlayer()) throw ERROR_NOT_PLAYER.create("spawn");
        ServerPlayer player = source.getPlayer();

        if (BotManager.isBotOnline(name)) {
            throw ERROR_BOT_ALREADY_EXISTS.create();
        }

        boolean spawned = BotManager.spawnBot(name, player);
        if (spawned) {
            source.sendSuccess(() -> Component.literal("✓ Bot ")
                .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" spawned.").withStyle(ChatFormatting.GREEN)), false);
        } else {
            throw ERROR_SPAWN_FAILED.create();
        }
        return Command.SINGLE_SUCCESS;
    }

    // /bot kill <name>
    private static int executeKill(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!source.isPlayer()) throw ERROR_NOT_PLAYER.create("kill");

        boolean killed = BotManager.killBot(name);
        if (killed) {
            source.sendSuccess(() -> Component.literal("✓ Bot ")
                .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" removed.").withStyle(ChatFormatting.GREEN)), false);
        } else {
            throw ERROR_BOT_NOT_FOUND.create(name);
        }
        return Command.SINGLE_SUCCESS;
    }

    // /bot list
    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (BotManager.getActiveBotCount() == 0) {
            source.sendSuccess(() -> Component.literal("No bots active.")
                .withStyle(ChatFormatting.GOLD), false);
            return Command.SINGLE_SUCCESS;
        }

        source.sendSuccess(() -> Component.literal("── Bots (" + BotManager.getActiveBotCount() + ") ──")
            .withStyle(ChatFormatting.GOLD), false);

        for (String name : BotManager.getActiveBotNames()) {
            source.sendSuccess(() -> {
                MutableComponent msg = Component.literal("")
                    .append(Component.literal("▸ ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(name).withStyle(ChatFormatting.YELLOW));
                return msg;
            }, false);
        }
        return Command.SINGLE_SUCCESS;
    }
}
