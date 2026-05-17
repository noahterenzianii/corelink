package com.corelink.command;

/**
 * Tracks an active ping session for a player.
 */
public record PingSession(
    String displayName,
    String world,
    double x,
    double y,
    double z,
    long endTime
) {
    public boolean isExpired() {
        return System.currentTimeMillis() >= endTime;
    }
}
