package com.corelink;

import com.corelink.bot.BotManager;
import com.corelink.command.BotCommand;
import com.corelink.command.CoordCommand;
import com.corelink.command.PingCommand;
import com.corelink.command.SharedChestCommand;
import com.corelink.storage.DatabaseManager;

import net.fabricmc.api.ModInitializer;
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
        // Create config directory and initialize the database
        Path dbPath = FabricLoader.getInstance().getConfigDir().resolve("corelink/coordinates.db");
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            LOGGER.error("Failed to create database directory at {}", dbPath.getParent(), e);
            return;
        }

        // Initialize the database connection and schema
        DATABASE = new DatabaseManager(dbPath.toString());
        try {
            DATABASE.initialize();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize database at {}", dbPath, e);
            return;
        }

        // Register all commands
        CoordCommand.register();
        PingCommand.register();
        SharedChestCommand.register();
        BotCommand.register();

        // Register lifecycle handlers (bot persistence)
        BotManager.register();

        LOGGER.info("CoreLink initialized successfully");
    }
}
