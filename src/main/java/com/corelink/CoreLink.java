package com.corelink;

import com.corelink.bot.BotManager;
import com.corelink.command.BotCommand;
import com.corelink.command.CoordCommand;
import com.corelink.command.PingCommand;
import com.corelink.command.SharedChestCommand;
import com.corelink.storage.DatabaseManager;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class CoreLink implements ModInitializer {
    public static final String MOD_ID = "corelink";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Singleton database manager shared across the mod
    public static DatabaseManager DATABASE;

    @Override
    public void onInitialize() {
        // Create config directory; the database itself is opened per server
        Path dbPath = FabricLoader.getInstance().getConfigDir().resolve("corelink/corelink.db");
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            LOGGER.error("Failed to create database directory at {}", dbPath.getParent(), e);
            return;
        }

        DatabaseManager db = new DatabaseManager(dbPath.toString());
        DATABASE = db;

        // Open the database on every server start, before any other listener
        // reads it (shared chest load, bot reload). Closing it on stop — after
        // all the savers below have run — keeps the lifecycle clean on
        // dedicated servers and on every singleplayer world.
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            try {
                db.initialize();
            } catch (Exception e) {
                LOGGER.error("Failed to initialize database at {}", dbPath, e);
            }
        });

        // Register all commands
        CoordCommand.register();
        PingCommand.register();
        SharedChestCommand.register();
        BotCommand.register();

        // Register lifecycle handlers (bot persistence)
        BotManager.register();

        // Registered last, so it runs after every SERVER_STOPPING saver
        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> db.close());

        LOGGER.info("CoreLink initialized successfully");
    }
}
