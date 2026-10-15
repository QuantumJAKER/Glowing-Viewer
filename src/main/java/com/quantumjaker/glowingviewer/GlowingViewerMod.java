// Copyright (c) 2026 QuantumJAKER.
// SPDX-License-Identifier: CC-BY-NC-SA-4.0
package com.quantumjaker.glowingviewer;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Glowing Viewer — selective glowing for Forge 1.20.1 (serverside-only).
 *
 * <p>Vanilla clients can join without the mod: only vanilla
 * {@code ClientboundSetEntityDataPacket}s are sent, with the glowing flag
 * faked per viewer. The outline colour comes from the target's regular
 * scoreboard team and is intentionally left untouched.</p>
 */
@Mod(GlowingViewerMod.MODID)
public class GlowingViewerMod {
    public static final String MODID = "glowingviewer";

    private static final Logger LOGGER = LogUtils.getLogger();

    public GlowingViewerMod() {
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) -> {
            GlowManager.onServerStarting(event.getServer());
            LOGGER.info("[GlowingViewer] Server starting, glow state loading.");
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            GlowManager.onServerStopped();
            LOGGER.info("[GlowingViewer] Server stopped, glow state cleared from memory.");
        });
    }
}
