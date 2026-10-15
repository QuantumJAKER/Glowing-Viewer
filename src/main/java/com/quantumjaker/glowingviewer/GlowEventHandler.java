// Copyright (c) 2026 QuantumJAKER.
// SPDX-License-Identifier: CC-BY-NC-SA-4.0
package com.quantumjaker.glowingviewer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side event handlers: command registration, expiry ticking and
 * re-pushing the fake glow when tracking (re-)starts or the player session
 * is (re-)created (login, respawn, dimension change).
 */
@Mod.EventBusSubscriber(modid = GlowingViewerMod.MODID)
public final class GlowEventHandler {
    private GlowEventHandler() {
    }

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        GlowCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Twice per second: expiry plus repair of states overwritten by
        // genuine vanilla flag updates (sneak, sprint, effect end, ...).
        if (++tickCounter >= 10) {
            tickCounter = 0;
            try {
                GlowManager.tick();
            } catch (Exception e) {
                // Never let housekeeping crash the server tick.
            }
        }
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer viewer)) {
            return;
        }
        Entity target = event.getTarget();
        if (target == null) {
            return;
        }
        try {
            // Covers chunk (re-)loads. The push is slightly delayed so
            // vanilla pairing packets sent around the same event cannot
            // overwrite the fake state on arrival.
            GlowManager.enqueuePush(target, viewer);
        } catch (Exception e) {
            // Ignore: tracking must never break because of the glow push.
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        pushActiveFor(event);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        pushActiveFor(event);
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        pushActiveFor(event);
    }

    private static void pushActiveFor(PlayerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer viewer)) {
            return;
        }
        try {
            // Fresh client-side entity state after login/respawn/dimension
            // change: re-send every glow this player should currently see,
            // including their own (visible in third person, like vanilla).
            GlowManager.pushActiveFor(viewer);
        } catch (Exception e) {
            // Ignore.
        }
    }
}
