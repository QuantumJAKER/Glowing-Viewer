// Copyright (c) 2026 QuantumJAKER.
// SPDX-License-Identifier: CC-BY-NC-SA-4.0
package com.quantumjaker.glowingviewer;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
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
import java.util.HashMap;
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
 * set on the server-side entity. Instead, outgoing entity-data packets are
 * corrected per viewer by a Netty pipeline handler ({@link GlowNettyHandler}),
 * so even genuine vanilla flag broadcasts (sprint, sneak, effect end, ...)
 * carry the faked bit for viewers with an active override. Direct pushes
 * ({@link #pushToViewers(Entity)}) and a twice-per-second repair pass act as
 * fallback. This keeps the mod additive: real vanilla glowing stays visible
 * to everyone, and no bytecode manipulation is required. State is kept in
 * memory only: nothing is saved, so a server restart clears all glowing
 * overrides.</p>
 *
 * <p>All methods run on the server thread, except the Netty handler lookups
 * which only read {@link #VIEWER_TARGET_IDS}.</p>
 */
public final class GlowManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Bit mask of the glowing flag inside the shared-flags byte. */
    private static final byte GLOWING_BIT = (byte) (1 << 6); // 0x40

    /** Delay in ticks before (re-)pushing after tracking starts. */
    private static final long TRACKING_PUSH_DELAY_TICKS = 5;

    /** Target UUID -&gt; (viewer UUID -&gt; expiry game time in ticks). */
    private static final Map<UUID, Map<UUID, Long>> ENTRIES = new ConcurrentHashMap<>();

    /**
     * Viewer UUID -&gt; glowing target entity ids. Maintained on the server
     * thread only; published sets are never mutated in place, so the Netty
     * handler thread can read them safely at any time.
     */
    private static final Map<UUID, Set<Integer>> VIEWER_TARGET_IDS = new ConcurrentHashMap<>();

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
        VIEWER_TARGET_IDS.clear();
        LOGGER.info("[GlowingViewer] Server starting with a fresh glow state (nothing persisted).");
    }

    public static void onServerStopped() {
        ENTRIES.clear();
        PENDING.clear();
        VIEWER_TARGET_IDS.clear();
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
        refreshViewerTargetIds();
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
        refreshViewerTargetIds();
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
        refreshViewerTargetIds();
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
        // Players first via the player list: level scans are not guaranteed
        // to include player entities, and this is the reliable path for them.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                pushIfActive(player, viewer);
            } catch (Exception e) {
                // Ignore: housekeeping must never break event handling.
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ServerPlayer) {
                    continue; // already covered via the player list above
                }
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

    /**
     * Rebuilds the Netty-thread-readable id sets from the UUID state.
     * Server thread only. Entity ids are never reused within a session, so a
     * slightly stale set can only miss a glow briefly, never misattribute one.
     */
    private static void refreshViewerTargetIds() {
        Map<UUID, Set<Integer>> fresh = new HashMap<>();
        long current = now();
        for (Map.Entry<UUID, Map<UUID, Long>> entry : ENTRIES.entrySet()) {
            Entity target = findEntity(entry.getKey());
            if (target == null) {
                continue;
            }
            int id = target.getId();
            for (Map.Entry<UUID, Long> viewer : entry.getValue().entrySet()) {
                if (viewer.getValue() <= current) {
                    continue;
                }
                fresh.computeIfAbsent(viewer.getKey(), key -> new HashSet<>()).add(id);
            }
        }
        VIEWER_TARGET_IDS.clear();
        for (Map.Entry<UUID, Set<Integer>> entry : fresh.entrySet()) {
            Set<Integer> ids = ConcurrentHashMap.newKeySet();
            ids.addAll(entry.getValue());
            VIEWER_TARGET_IDS.put(entry.getKey(), ids);
        }
    }

    /**
     * Netty-thread entry point: returns a copy of the packet with the glowing
     * bit set when the viewer has an active override for the target, or null
     * when the packet passes through unchanged. Never touches the level.
     */
    static ClientboundSetEntityDataPacket fakeForViewer(ClientboundSetEntityDataPacket packet,
            UUID viewerId) {
        Set<Integer> ids = VIEWER_TARGET_IDS.get(viewerId);
        if (ids == null || !ids.contains(packet.id())) {
            return null;
        }
        return withGlowingBit(packet);
    }

    /**
     * Netty-thread entry point for bundles: collects faked copies of the
     * contained entity-data packets that need overriding. The caller writes
     * them right after the original bundle.
     */
    static List<ClientboundSetEntityDataPacket> fakesForBundle(ClientboundBundlePacket bundle,
            UUID viewerId) {
        Set<Integer> ids = VIEWER_TARGET_IDS.get(viewerId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ClientboundSetEntityDataPacket> fakes = null;
        for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
            if (sub instanceof ClientboundSetEntityDataPacket data && ids.contains(data.id())) {
                if (fakes == null) {
                    fakes = new ArrayList<>();
                }
                fakes.add(withGlowingBit(data));
            }
        }
        return fakes == null ? List.of() : fakes;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ClientboundSetEntityDataPacket withGlowingBit(ClientboundSetEntityDataPacket packet) {
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
        boolean found = false;
        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            if (value.id() == SHARED_FLAGS.getId()) {
                found = true;
                byte bits = (Byte) value.value();
                SynchedEntityData.DataValue<Byte> replacement = new SynchedEntityData.DataValue<>(value.id(),
                        (net.minecraft.network.syncher.EntityDataSerializer<Byte>) (net.minecraft.network.syncher.EntityDataSerializer) value.serializer(),
                        (byte) (bits | GLOWING_BIT));
                values.add(replacement);
            } else {
                values.add(value);
            }
        }
        if (!found) {
            values.add(new SynchedEntityData.DataValue<>(SHARED_FLAGS.getId(), EntityDataSerializers.BYTE,
                    GLOWING_BIT));
        }
        return new ClientboundSetEntityDataPacket(packet.id(), values);
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
        // Players first: level entity scans are not guaranteed to include
        // player entities, and almost all glow targets are players.
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            return player;
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
