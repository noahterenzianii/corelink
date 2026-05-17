package com.corelink.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Immutable record representing a saved coordinate.
 */
public record CoordinateRecord(
    int id,
    UUID playerUuid,
    String name,
    String world,
    double x,
    double y,
    double z,
    String description,
    String createdAt
) {
    public static CoordinateRecord fromResultSet(ResultSet rs) throws SQLException {
        return new CoordinateRecord(
            rs.getInt("id"),
            UUID.fromString(rs.getString("player_uuid")),
            rs.getString("name"),
            rs.getString("world"),
            rs.getDouble("x"),
            rs.getDouble("y"),
            rs.getDouble("z"),
            rs.getString("description"),
            rs.getString("created_at")
        );
    }
}
