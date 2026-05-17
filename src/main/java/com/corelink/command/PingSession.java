package com.corelink.command;

import java.util.UUID;

/**
 * Tracks an active ping session for a player.
 */
public record PingSession(
    String displayName,
    String world,
    double x,
    double y,
    double z,
    long endTime,
    UUID targetPlayerId  // null for coordinate pings, non-null for player pings
) {
    public boolean isExpired() {
        return System.currentTimeMillis() >= endTime;
    }
}
