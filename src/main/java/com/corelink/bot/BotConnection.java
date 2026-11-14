package com.corelink.bot;

import io.netty.channel.ChannelFutureListener;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;

/**
 * Connection used by bots: outbound packets are discarded instead of being
 * written to the underlying Netty channel.
 *
 * <p>Bots run on an in-memory {@link io.netty.channel.embedded.EmbeddedChannel}
 * that nobody ever drains: every packet written to it (chunk batches, entity
 * updates, latency broadcasts, ...) would be retained in the channel's
 * outbound queue forever, slowly leaking memory for as long as the bot lives.
 * Dropping packets on send keeps the bot fully functional while retaining
 * nothing.</p>
 *
 * <p>The disconnect packet is the single exception: forwarding it lets the
 * vanilla close-listener shut the channel down, so kicking a bot still removes
 * it from the player list exactly like with a regular connection.</p>
 */
public class BotConnection extends Connection {

    public BotConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public void send(Packet<?> packet) {
        if (packet instanceof ClientboundDisconnectPacket) {
            super.send(packet);
        }
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
        if (packet instanceof ClientboundDisconnectPacket) {
            super.send(packet, listener);
        }
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (packet instanceof ClientboundDisconnectPacket) {
            super.send(packet, listener, flush);
        }
    }
}
