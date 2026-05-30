package com.corelink.storage;

import com.corelink.CoreLink;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the SQLite database connection and all coordinate CRUD operations.
 * Thread-safe for single-connection SQLite usage.
 */
public class DatabaseManager {
    private final String dbPath;
    private Connection connection;

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Opens the database connection and ensures the schema exists.
     */
    public void initialize() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS coordinates (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT    NOT NULL,
                    name        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                    world       TEXT    NOT NULL,
                    x           REAL    NOT NULL,
                    y           REAL    NOT NULL,
                    z           REAL    NOT NULL,
                    description TEXT,
                    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS shared_chest_items (
                    slot      INTEGER PRIMARY KEY,
                    item_data BLOB NOT NULL
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bots (
                    name  TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    uuid  TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x     REAL NOT NULL,
                    y     REAL NOT NULL,
                    z     REAL NOT NULL
                )
            """);
        }
        CoreLink.LOGGER.info("Database initialized at {}", dbPath);
    }

    /**
     * Closes the database connection. Should be called on server shutdown.
     */
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                CoreLink.LOGGER.error("Failed to close database connection", e);
            }
        }
    }

    /**
     * Inserts or replaces a coordinate. If a coordinate with the same name exists,
     * it will be overwritten (globally unique constraint on name).
     */
    public void saveCoordinate(UUID playerUuid, String name, String world,
                                double x, double y, double z, String description) {
        deleteCoordinate(name);
        String sql = """
            INSERT INTO coordinates (player_uuid, name, world, x, y, z, description)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, name);
            stmt.setString(3, world);
            stmt.setDouble(4, x);
            stmt.setDouble(5, y);
            stmt.setDouble(6, z);
            if (description != null && !description.isBlank()) {
                stmt.setString(7, description.strip());
            } else {
                stmt.setNull(7, Types.VARCHAR);
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to save coordinate '{}'", name, e);
        }
    }

    /**
     * Deletes a coordinate by its globally unique name.
     * @return true if a row was deleted
     */
    public boolean deleteCoordinate(String name) {
        String sql = "DELETE FROM coordinates WHERE name = ? COLLATE NOCASE";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to delete coordinate '{}'", name, e);
            return false;
        }
    }

    /**
     * Returns all saved coordinates, ordered alphabetically by name.
     */
    public List<CoordinateRecord> getAllCoordinates() {
        List<CoordinateRecord> coords = new ArrayList<>();
        String sql = "SELECT * FROM coordinates ORDER BY name ASC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                coords.add(CoordinateRecord.fromResultSet(rs));
            }
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to retrieve coordinates", e);
        }
        return coords;
    }

    /**
     * Looks up a single coordinate by its unique name.
     * @return the coordinate, or null if not found
     */
    public CoordinateRecord getCoordinate(String name) {
        String sql = "SELECT * FROM coordinates WHERE name = ? COLLATE NOCASE";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return CoordinateRecord.fromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to get coordinate '{}'", name, e);
        }
        return null;
    }

    /**
     * Returns the total number of saved coordinates.
     */
    public int count() {
        String sql = "SELECT COUNT(*) FROM coordinates";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.getInt(1);
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to count coordinates", e);
            return 0;
        }
    }

    // ── Bots ─────────────────────────────────────────────────────────

    public void saveBot(BotRecord bot) {
        String sql = "INSERT OR REPLACE INTO bots (name, uuid, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, bot.name());
            stmt.setString(2, bot.uuid().toString());
            stmt.setString(3, bot.world());
            stmt.setDouble(4, bot.x());
            stmt.setDouble(5, bot.y());
            stmt.setDouble(6, bot.z());
            stmt.executeUpdate();
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to save bot '{}'", bot.name(), e);
        }
    }

    public boolean removeBot(String name) {
        String sql = "DELETE FROM bots WHERE name = ? COLLATE NOCASE";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to remove bot '{}'", name, e);
            return false;
        }
    }

    public List<BotRecord> getAllBots() {
        List<BotRecord> bots = new ArrayList<>();
        String sql = "SELECT * FROM bots ORDER BY name ASC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                bots.add(BotRecord.fromResultSet(rs));
            }
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to retrieve bots", e);
        }
        return bots;
    }

    // ── Shared Chest ─────────────────────────────────────────────────

    /**
     * Persists all non-empty shared chest slots. Slot → NBT binary blob.
     * A full-snapshot approach: all existing rows are replaced.
     */
    public synchronized void saveSharedChestSlots(Map<Integer, byte[]> items) {
        String deleteSql = "DELETE FROM shared_chest_items";
        String insertSql = "INSERT INTO shared_chest_items (slot, item_data) VALUES (?, ?)";
        try {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(deleteSql);
            }
            try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
                for (Map.Entry<Integer, byte[]> entry : items.entrySet()) {
                    stmt.setInt(1, entry.getKey());
                    stmt.setBytes(2, entry.getValue());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to save shared chest items", e);
            try { connection.rollback(); } catch (SQLException ignored) {}
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    /**
     * Loads all persisted shared chest items. Returns slot → NBT binary blob map.
     */
    public synchronized Map<Integer, byte[]> loadSharedChestSlots() {
        Map<Integer, byte[]> result = new HashMap<>();
        String sql = "SELECT slot, item_data FROM shared_chest_items ORDER BY slot";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.put(rs.getInt("slot"), rs.getBytes("item_data"));
            }
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to load shared chest items", e);
        }
        return result;
    }
}
