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
import java.util.List;
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
                    name        TEXT    NOT NULL UNIQUE,
                    world       TEXT    NOT NULL,
                    x           REAL    NOT NULL,
                    y           REAL    NOT NULL,
                    z           REAL    NOT NULL,
                    description TEXT,
                    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
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
        String sql = """
            INSERT OR REPLACE INTO coordinates (player_uuid, name, world, x, y, z, description)
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
        String sql = "DELETE FROM coordinates WHERE name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            CoreLink.LOGGER.error("Failed to delete coordinate '{}'", name, e);
            return false;
        }
    }

    /**
     * Returns all saved coordinates, ordered by creation date.
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
}
