package com.corelink.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side companion for CoreLink.
 *
 * Registers a single keybinding that forwards the server-side /sharedchest
 * command. All game logic (shared chest, bots, coordinates, database) lives
 * in the CoreLink server mod; this mod only sends the command on key press.
 */
public class CoreLinkClient implements ClientModInitializer {

    public static final String MOD_ID = "corelink-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping openSharedChest;

    @Override
    public void onInitializeClient() {
        // Category shown in the Controls menu
        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "main")
        );

        openSharedChest = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.corelink-client.sharedchest",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(), // unbound by default
            category
        ));

        // Forward the command on every key press while in game
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openSharedChest.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.player.connection.sendCommand("sharedchest");
                }
            }
        });

        LOGGER.info("CoreLink Client initialized successfully");
    }
}
