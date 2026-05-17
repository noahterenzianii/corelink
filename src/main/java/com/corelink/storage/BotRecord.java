package com.corelink.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Immutable record representing a persisted bot player.
 */
public record BotRecord(
    String name,
    UUID uuid,
    String world,
    double x,
    double y,
    double z
) {
    public static BotRecord fromResultSet(ResultSet rs) throws SQLException {
        return new BotRecord(
            rs.getString("name"),
            UUID.fromString(rs.getString("uuid")),
            rs.getString("world"),
            rs.getDouble("x"),
            rs.getDouble("y"),
            rs.getDouble("z")
        );
    }
}
