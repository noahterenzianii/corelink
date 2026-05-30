package com.corelink.command;

import com.corelink.CoreLink;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Map;

import static net.minecraft.commands.Commands.literal;

/**
 * /sharedchest — opens a double chest shared by all players.
 *
 * Every player sees the same inventory (shared instance), so changes
 * are reflected in real-time on every open GUI.
 *
 * Persistence is backed by SQLite; saves are batched to the next tick.
 */
public class SharedChestCommand {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private static SharedChestInventory inventory;
    private static MinecraftServer server;
    private static boolean dirty;

    // ── Registration ──────────────────────────────────────────────────

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("sharedchest")
                .executes(SharedChestCommand::executeOpen)
            );
            dispatcher.register(literal("corelink:sharedchest")
                .requires(CommandSourceStack::isPlayer)
                .executes(SharedChestCommand::executeOpen)
            );
        });

        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            inventory = new SharedChestInventory();
            loadInventory();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (dirty) flushSave();
            inventory = null;
            server = null;
        });

        ServerTickEvents.START_SERVER_TICK.register(srv -> {
            if (dirty) {
                dirty = false;
                flushSave();
            }
        });
    }

    // ── Handler ───────────────────────────────────────────────────────

    private static int executeOpen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!source.isPlayer()) throw CoordCommand.ERROR_NOT_PLAYER.create("sharedchest");
        ServerPlayer player = source.getPlayer();

        player.openMenu(new SimpleMenuProvider(
            (syncId, playerInventory, p) ->
                ChestMenu.sixRows(syncId, playerInventory, inventory),
            Component.literal("Shared Chest")
        ));

        source.sendSuccess(
            () -> Component.literal("Opened shared chest.").withStyle(ChatFormatting.GREEN),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    // ── Persistence ───────────────────────────────────────────────────

    /** Load all slots from the database on server start. */
    private static void loadInventory() {
        if (server == null) return;
        var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());

        inventory.loading = true;
        try {
            Map<Integer, byte[]> data = CoreLink.DATABASE.loadSharedChestSlots();
            for (int i = 0; i < SIZE; i++) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
            for (var entry : data.entrySet()) {
                try {
                    var dis = new DataInputStream(new ByteArrayInputStream(entry.getValue()));
                    CompoundTag tag = NbtIo.read(dis);
                    ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, tag)
                        .getOrThrow(IllegalStateException::new);
                    int slot = entry.getKey();
                    if (slot >= 0 && slot < SIZE) {
                        inventory.setItem(slot, stack);
                    }
                } catch (Exception e) {
                    CoreLink.LOGGER.error("Failed to decode shared chest slot {}", entry.getKey(), e);
                }
            }
        } finally {
            inventory.loading = false;
        }
    }

    /** Write all non-empty slots to the database. */
    private static synchronized void flushSave() {
        if (server == null || inventory == null) return;
        var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());

        Map<Integer, byte[]> data = new HashMap<>();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                try {
                    CompoundTag tag = (CompoundTag) ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack)
                        .getOrThrow(IllegalStateException::new);
                    var baos = new ByteArrayOutputStream();
                    NbtIo.write(tag, new DataOutputStream(baos));
                    data.put(i, baos.toByteArray());
                } catch (Exception e) {
                    CoreLink.LOGGER.error("Failed to encode shared chest slot {}", i, e);
                }
            }
        }

        CoreLink.DATABASE.saveSharedChestSlots(data);
    }

    // ── Custom inventory ──────────────────────────────────────────────

    /**
     * SimpleContainer with deferred auto-save.
     * setChanged() sets the dirty flag; the actual save runs
     * on the next server tick (batching multiple changes).
     */
    private static class SharedChestInventory extends SimpleContainer {
        boolean loading = false;

        SharedChestInventory() {
            super(SIZE);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (!loading) {
                dirty = true;
            }
        }
    }
}
