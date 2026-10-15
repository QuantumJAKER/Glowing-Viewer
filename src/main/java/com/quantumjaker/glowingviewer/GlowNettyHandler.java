// Copyright (c) 2026 QuantumJAKER.
// SPDX-License-Identifier: CC-BY-NC-SA-4.0
package com.quantumjaker.glowingviewer;

import com.mojang.logging.LogUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/**
 * Per-player outbound pipeline handler that applies selective glowing inline.
 *
 * <p>Every entity-data packet leaving the server for this viewer passes
 * through {@link #write}: packets for targets with an active override get the
 * glowing bit added before they hit the wire – including genuine vanilla flag
 * broadcasts (sprint, sneak, effect end, ...). This leaves no time window in
 * which the client could show the unfaked state, so no flicker occurs.</p>
 *
 * <p>Only id-based lookups happen here (Netty thread); all state maintenance
 * stays on the server thread in {@link GlowManager}. Failures degrade to the
 * pre-existing push/repair fallback and can never break the pipeline.</p>
 */
public final class GlowNettyHandler extends ChannelOutboundHandlerAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HANDLER_NAME = "glowingviewer";

    /** Cached reflective access to the channel inside {@link Connection}. */
    private static volatile Field channelField;

    private final UUID viewerId;

    public GlowNettyHandler(UUID viewerId) {
        this.viewerId = viewerId;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object out = msg;
        try {
            if (msg instanceof ClientboundSetEntityDataPacket data) {
                ClientboundSetEntityDataPacket faked = GlowManager.fakeForViewer(data, viewerId);
                if (faked != null) {
                    out = faked;
                }
                ctx.write(out, promise);
                return;
            }
            if (msg instanceof ClientboundBundlePacket bundle) {
                ctx.write(msg, promise);
                List<ClientboundSetEntityDataPacket> fakes = GlowManager.fakesForBundle(bundle, viewerId);
                for (ClientboundSetEntityDataPacket fake : fakes) {
                    ctx.write(fake);
                }
                return;
            }
        } catch (Exception e) {
            // Never break the pipeline because of glow handling.
        }
        ctx.write(out, promise);
    }

    /** Installs the handler into the player's connection pipeline. */
    public static void attach(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        try {
            Channel channel = findChannel(player.connection.connection);
            if (channel == null || !channel.isOpen()) {
                return;
            }
            if (channel.pipeline().get(HANDLER_NAME) == null) {
                channel.pipeline().addBefore("packet_handler", HANDLER_NAME,
                        new GlowNettyHandler(player.getUUID()));
                LOGGER.debug("[GlowingViewer] Attached glow handler for {}.", player.getUUID());
            }
        } catch (Exception e) {
            LOGGER.warn("[GlowingViewer] Could not attach glow handler for {} (fallback active): {}",
                    player.getUUID(), e.toString());
        }
    }

    /** Removes the handler again (player logout). The channel closes anyway. */
    public static void detach(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return;
        }
        try {
            Channel channel = findChannel(player.connection.connection);
            if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        } catch (Exception e) {
            // Ignore: closed channels clean up on their own.
        }
    }

    @Nullable
    private static Channel findChannel(Connection connection) throws Exception {
        if (connection == null) {
            return null;
        }
        Field field = channelField;
        if (field == null) {
            // Structural lookup (no mapping-dependent field name): the only
            // Netty Channel held by the connection.
            for (Field candidate : Connection.class.getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(candidate.getType())) {
                    candidate.setAccessible(true);
                    field = candidate;
                    channelField = field;
                    break;
                }
            }
            if (field == null) {
                throw new IllegalStateException("No Channel field in Connection");
            }
        }
        return (Channel) field.get(connection);
    }
}
