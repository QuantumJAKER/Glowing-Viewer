// Copyright (c) 2026 QuantumJAKER.
// SPDX-License-Identifier: CC-BY-NC-SA-4.0
package com.quantumjaker.glowingviewer;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core state and packet logic for selective glowing.
 *
 * <p>The glowing flag ({@code 0x40} in the shared-flags byte, slot 0) is never
 * set on the server-side entity. Instead, vanilla
 * {@link ClientboundSetEntityDataPacket}s carrying the set bit are sent
 * directly to each viewer ({@link #pushToViewers(Entity)}). Tracking
 * (re-)starts re-push with a small delay, and a one-second resync repairs
 * states overwritten by genuine vanilla flag updates. This keeps the mod
 * additive: real vanilla glowing stays visible to everyone, and no bytecode
 * manipulation is required. State is kept in memory only: nothing is saved,
 * so a server restart clears all glowing overrides.</p>
 *
 * <p>All methods run on the server thread.</p>
 */
public final class GlowManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Bit mask of the glowing flag inside the shared-flags byte. */
    private static final byte GLOWING_BIT = (byte) (1 << 6); // 0x40

    /** Delay in ticks before (re-)pushing after tracking starts. */
    private static final long TRACKING_PUSH_DELAY_TICKS = 5;

    /** Target UUID -&gt; (viewer UUID -&gt; expiry game time in ticks). */
    private static final Map<UUID, Map<UUID, Long>> ENTRIES = new ConcurrentHashMap<>();

    /** Delayed re-pushes scheduled by tracking events. */
    private static final List<PendingPush> PENDING = new ArrayList<>();

    private static MinecraftServer server;

    /**
     * Slot 0 with the BYTE serializer is the shared-flags slot for every
     * entity ({@code Entity.DATA_SHARED_FLAGS_ID}); {@code SynchedEntityData}
     * looks values up by id, so a locally constructed accessor is valid.
     */
    private static final EntityDataAccessor<Byte> SHARED_FLAGS =
            new EntityDataAccessor<>(0, EntityDataSerializers.BYTE);

    private GlowManager() {
    }

    private record PendingPush(int targetId, UUID targetUuid, UUID viewerId, long dueTick) {
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static void onServerStarting(MinecraftServer server) {
        GlowManager.server = server;
        ENTRIES.clear();
        PENDING.clear();
        LOGGER.info("[GlowingViewer] Server starting with a fresh glow state (nothing persisted).");
    }

    public static void onServerStopped() {
        ENTRIES.clear();
        PENDING.clear();
        server = null;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /**
     * Checks whether the viewer should see the target glow right now.
     * Expired entries are treated as absent (the periodic tick removes them).
     */
    public static boolean isGlowing(UUID targetUuid, UUID viewerUuid, long now) {
        Map<UUID, Long> viewers = ENTRIES.get(targetUuid);
        if (viewers == null) {
            return false;
        }
        Long expiry = viewers.get(viewerUuid);
        return expiry != null && expiry > now;
    }

    /** Current server game time, or 0 when no server is running. */
    public static long now() {
        if (server == null) {
            return 0;
        }
        return server.overworld().getGameTime();
    }

    @Nullable
    public static MinecraftServer getServer() {
        return server;
    }

    public static int targetCount() {
        return ENTRIES.size();
    }

    // ------------------------------------------------------------------
    // Mutations (called from commands and the expiry tick)
    // ------------------------------------------------------------------

    /**
     * Applies glowing to the given targets for the given viewers.
     * Existing viewers for those targets are replaced (override semantics).
     *
     * @param durationTicks glow duration in ticks (always finite)
     */
    public static void setGlowing(Collection<? extends Entity> targets, Collection<ServerPlayer> viewers,
            long durationTicks) {
        if (server == null) {
            return;
        }
        long expiry = now() + Math.max(1, durationTicks);
        Set<UUID> viewerIds = new HashSet<>();
        for (ServerPlayer viewer : viewers) {
            viewerIds.add(viewer.getUUID());
        }
        for (Entity target : targets) {
            Map<UUID, Long> map = new ConcurrentHashMap<>();
            for (UUID viewerId : viewerIds) {
                map.put(viewerId, expiry);
            }
            ENTRIES.put(target.getUUID(), map);
            pushToViewers(target);
        }
    }

    /**
     * Removes glowing overrides for the given targets.
     *
     * @param viewers null removes the override for all viewers, otherwise only for these
     */
    public static void clearGlowing(Collection<? extends Entity> targets,
            @Nullable Collection<ServerPlayer> viewers) {
        if (server == null) {
            return;
        }
        Set<UUID> viewerIds = null;
        if (viewers != null) {
            viewerIds = new HashSet<>();
            for (ServerPlayer viewer : viewers) {
                viewerIds.add(viewer.getUUID());
            }
        }
        for (Entity target : targets) {
            UUID targetUuid = target.getUUID();
            Map<UUID, Long> stored = ENTRIES.get(targetUuid);
            if (stored == null) {
                continue;
            }
            Set<UUID> affected;
            if (viewerIds == null) {
                affected = new HashSet<>(stored.keySet());
                ENTRIES.remove(targetUuid);
            } else {
                affected = new HashSet<>();
                for (UUID viewerId : viewerIds) {
                    if (stored.remove(viewerId) != null) {
                        affected.add(viewerId);
                    }
                }
                if (stored.isEmpty()) {
                    ENTRIES.remove(targetUuid);
                }
            }
            for (UUID viewerId : affected) {
                ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
                if (viewer != null) {
                    sendCorrection(target, viewer);
                }
            }
        }
        dropPendingFor(targets);
    }

    /** Removes every glowing override (used by {@code /glow clearall}). */
    public static Set<UUID> clearAll() {
        Set<UUID> removed = new HashSet<>(ENTRIES.keySet());
        if (server != null && !removed.isEmpty()) {
            for (UUID targetUuid : removed) {
                Map<UUID, Long> viewers = ENTRIES.get(targetUuid);
                if (viewers == null) {
                    continue;
                }
                Entity target = findEntity(targetUuid);
                if (target == null) {
                    continue;
                }
                for (UUID viewerId : viewers.keySet()) {
                    ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
                    if (viewer != null) {
                        sendCorrection(target, viewer);
                    }
                }
            }
        }
        ENTRIES.clear();
        PENDING.clear();
        return removed;
    }

    /**
     * Schedules a re-push shortly after tracking starts. Delayed because the
     * vanilla pairing packets are sent around the same event and would
     * otherwise overwrite the fake state on arrival.
     */
    public static void enqueuePush(Entity target, ServerPlayer viewer) {
        if (server == null) {
            return;
        }
        if (!isGlowing(target.getUUID(), viewer.getUUID(), now())) {
            return;
        }
        PENDING.add(new PendingPush(target.getId(), target.getUUID(), viewer.getUUID(),
                now() + TRACKING_PUSH_DELAY_TICKS));
    }

    /**
     * Once per second: processes delayed pushes, removes expired entries
     * (sending corrections), and re-pushes active glows to repair states
     * overwritten by genuine vanilla flag updates.
     */
    public static void tick() {
        if (server == null) {
            return;
        }
        long current = now();

        if (!PENDING.isEmpty()) {
            List<PendingPush> due = new ArrayList<>();
            PENDING.removeIf(next -> {
                if (next.dueTick <= current) {
                    due.add(next);
                    return true;
                }
                // Drop hopelessly stale entries (e.g. after long lag spikes).
                return next.dueTick < current - 1200;
            });
            for (PendingPush next : due) {
                ServerPlayer viewer = server.getPlayerList().getPlayer(next.viewerId);
                if (viewer == null) {
                    continue;
                }
                Entity target = viewer.level().getEntity(next.targetId);
                if (target != null && target.getUUID().equals(next.targetUuid)) {
                    pushIfActive(target, viewer);
                }
            }
        }

        if (ENTRIES.isEmpty()) {
            return;
        }
        List<UUID> emptyTargets = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Long>> entry : ENTRIES.entrySet()) {
            Map<UUID, Long> viewers = entry.getValue();
            List<UUID> expired = new ArrayList<>();
            for (Map.Entry<UUID, Long> viewer : viewers.entrySet()) {
                long expiry = viewer.getValue();
                if (expiry <= current) {
                    expired.add(viewer.getKey());
                }
            }
            Entity target = findEntity(entry.getKey());
            for (UUID viewerId : expired) {
                viewers.remove(viewerId);
                if (target != null) {
                    ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
                    if (viewer != null) {
                        sendCorrection(target, viewer);
                    }
                }
            }
            if (viewers.isEmpty()) {
                emptyTargets.add(entry.getKey());
            } else if (target != null) {
                // Repair pass: re-assert the fake state in case vanilla
                // broadcasts of the real flags overwrote it on clients.
                pushToViewers(target);
            }
        }
        for (UUID targetUuid : emptyTargets) {
            ENTRIES.remove(targetUuid);
        }
    }

    // ------------------------------------------------------------------
    // Direct packet pushes
    // ------------------------------------------------------------------

    /** Sends the faked glowing state for one target to all its active viewers. */
    public static void pushToViewers(Entity target) {
        if (server == null) {
            return;
        }
        Map<UUID, Long> viewers = ENTRIES.get(target.getUUID());
        if (viewers == null || viewers.isEmpty()) {
            return;
        }
        long current = now();
        byte bits;
        try {
            bits = target.getEntityData().get(SHARED_FLAGS);
        } catch (Exception e) {
            LOGGER.warn("[GlowingViewer] Could not read shared flags for {}: {}", target.getUUID(),
                    e.toString());
            return;
        }
        ClientboundSetEntityDataPacket packet = fakePacket(target.getId(), (byte) (bits | GLOWING_BIT));
        for (Map.Entry<UUID, Long> viewer : viewers.entrySet()) {
            long expiry = viewer.getValue();
            if (expiry <= current) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(viewer.getKey());
            sendSafely(player, packet);
        }
    }

    /** Pushes the fake state for one target to one viewer if an override is active. */
    public static void pushIfActive(Entity target, ServerPlayer viewer) {
        if (server == null || viewer.connection == null) {
            return;
        }
        if (!isGlowing(target.getUUID(), viewer.getUUID(), now())) {
            return;
        }
        byte bits;
        try {
            bits = target.getEntityData().get(SHARED_FLAGS);
        } catch (Exception e) {
            return;
        }
        sendSafely(viewer, fakePacket(target.getId(), (byte) (bits | GLOWING_BIT)));
    }

    /** Re-pushes every active glow for one viewer (login, respawn, dimension change). */
    public static void pushActiveFor(ServerPlayer viewer) {
        if (server == null || viewer == null || ENTRIES.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                try {
                    pushIfActive(entity, viewer);
                } catch (Exception e) {
                    // Ignore: housekeeping must never break event handling.
                }
            }
        }
    }

    /** Sends the real (unfaked) flags of one target to one viewer. */
    public static void sendCorrection(Entity target, ServerPlayer viewer) {
        byte bits;
        try {
            bits = target.getEntityData().get(SHARED_FLAGS);
        } catch (Exception e) {
            return;
        }
        sendSafely(viewer, fakePacket(target.getId(), bits));
    }

    private static ClientboundSetEntityDataPacket fakePacket(int entityId, byte bits) {
        return new ClientboundSetEntityDataPacket(entityId,
                List.of(new SynchedEntityData.DataValue<>(SHARED_FLAGS.getId(),
                        EntityDataSerializers.BYTE, bits)));
    }

    private static void sendSafely(@Nullable ServerPlayer viewer, ClientboundSetEntityDataPacket packet) {
        if (viewer == null || viewer.connection == null) {
            return;
        }
        try {
            viewer.connection.send(packet);
        } catch (Exception e) {
            LOGGER.warn("[GlowingViewer] Could not send glow packet to {}: {}", viewer.getUUID(),
                    e.toString());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @Nullable
    private static Entity findEntity(UUID uuid) {
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getUUID().equals(uuid)) {
                    return entity;
                }
            }
        }
        return null;
    }

    private static void dropPendingFor(Collection<? extends Entity> targets) {
        if (PENDING.isEmpty()) {
            return;
        }
        Set<UUID> ids = new HashSet<>();
        for (Entity target : targets) {
            ids.add(target.getUUID());
        }
        PENDING.removeIf(next -> ids.contains(next.targetUuid));
    }
}
