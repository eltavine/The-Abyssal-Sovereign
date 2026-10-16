package com.eltavine.the_abyssal_sovereign.client.effect;

import com.eltavine.the_abyssal_sovereign.bridge.BossCommand;
import com.eltavine.the_abyssal_sovereign.client.SovereignClient;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTickList;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * Erases every non-player creature that is loaded, and keeps newly arriving ones out.
 *
 * <p>The native daemon emits {@code CMD_PURGE} every invoked tick. The client tick also calls
 * {@link #sweep} directly, so a dropped command does not leave a gap. {@link CreatureJoinGate}
 * cancels the join event, which is the choke point for both fresh spawns and creatures read back
 * out of a chunk. Together those cover "already here" and "about to appear".
 *
 * <p>Each creature is then removed by every layer, and a failure in one does not skip the rest:
 *
 * <ol>
 *   <li><b>Vanilla / NeoForge API</b> — detach riders (so a player on a horse is dismounted, not
 *       deleted), zero health, {@code hurt}, {@code die}, {@code kill}, {@code remove},
 *       {@code discard}, plus {@code ServerChunkCache.removeEntity} or
 *       {@code ClientLevel.removeEntity} so tracking broadcasts the disappearance.</li>
 *   <li><b>Level-storage surgery</b> — lookup, section map, both tick-list maps, navigating-mob
 *       set, known UUIDs, multipart part map, chunk-tracking map, and the server's chunk-load
 *       inbox, so a short-circuited callback cannot leave a stale reference that ticks or saves.</li>
 *   <li><b>Reflection and MethodHandle</b> — {@code setRemoved} through a MethodHandle, then the
 *       removal reason written reflectively, then the level callback cleared.</li>
 *   <li><b>{@code sun.misc.Unsafe}</b> — the same removal reason written through its object offset.
 *       The {@code level} reference is left in place; clearing it crashes whatever is still
 *       drawing the creature this frame.</li>
 * </ol>
 *
 * <p>Players are never targets. Creatures in chunks that are not loaded stay on disk until that
 * chunk loads; the join gate drops them at that moment, and the next save writes the chunk without
 * them.
 */
public final class WorldErasure {

    private static final Logger LOGGER = Logger.getLogger("AbyssalSovereign.Erasure");
    private static final AtomicBoolean SERVER_SWEEP_QUEUED = new AtomicBoolean();

    private WorldErasure() {
    }

    /** Global sweep of the client level and, in single player, every integrated-server level. */
    public static void sweep() {
        if (!SovereignClient.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientLevel = minecraft.level;
        if (clientLevel != null) {
            purgeClient(clientLevel);
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null && SERVER_SWEEP_QUEUED.compareAndSet(false, true)) {
            server.execute(() -> {
                try {
                    if (!SovereignClient.isActive()) {
                        return;
                    }
                    for (ServerLevel level : server.getAllLevels()) {
                        purgeServer(level);
                    }
                } finally {
                    SERVER_SWEEP_QUEUED.set(false);
                }
            });
        }
    }

    /** A daemon purge. Global scope sweeps everything; a local scope keeps the same layers. */
    public static void purge(BossCommand.Purge purge) {
        if (purge.global()) {
            sweep();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel clientLevel = minecraft.level;
        if (clientLevel != null) {
            eraseAll(collect(clientLevel.entitiesForRendering(), purge), clientLevel, false);
        }
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                for (ServerLevel level : server.getAllLevels()) {
                    eraseAll(collect(level.getAllEntities(), purge), level, true);
                }
            });
        }
    }

    private static void purgeServer(ServerLevel level) {
        Object manager = read(level, "entityManager");
        stripInbox(manager);
        List<Entity> targets = collect(level.getAllEntities(), null);
        addFromTickList(read(level, "entityTickList"), targets);
        addParents(read(level, "dragonParts"), targets);
        eraseAll(targets, level, true);
    }

    private static void purgeClient(ClientLevel level) {
        List<Entity> targets = collect(level.entitiesForRendering(), null);
        addFromTickList(read(level, "tickingEntities"), targets);
        addParents(read(level, "partEntities"), targets);
        eraseAll(targets, level, false);
    }

    /** {@code purge == null} means every creature. A non-global purge keeps only those inside the radius. */
    private static List<Entity> collect(Iterable<Entity> all, BossCommand.Purge purge) {
        List<Entity> targets = new ArrayList<>();
        Set<Entity> seen = newSet();
        double r2 = purge == null ? 0.0D : (double) purge.radius() * (double) purge.radius();
        for (Entity entity : all) {
            if (!CreatureCull.appliesTo(entity)) {
                continue;
            }
            if (purge != null && !purge.global() && entity.distanceToSqr(purge.x(), purge.y(), purge.z()) > r2) {
                continue;
            }
            if (seen.add(entity)) {
                targets.add(entity);
            }
        }
        return targets;
    }

    private static void addFromTickList(Object tickList, List<Entity> targets) {
        if (tickList == null) {
            return;
        }
        addMapValues(read(tickList, "active"), targets);
        addMapValues(read(tickList, "passive"), targets);
    }

    private static void addMapValues(Object map, List<Entity> targets) {
        if (!(map instanceof Int2ObjectMap<?> raw)) {
            return;
        }
        Set<Entity> seen = newSet();
        seen.addAll(targets);
        for (Object value : raw.values()) {
            if (value instanceof Entity entity && CreatureCull.appliesTo(entity) && seen.add(entity)) {
                targets.add(entity);
            }
        }
    }

    private static void addParents(Object partMap, List<Entity> targets) {
        if (!(partMap instanceof Int2ObjectMap<?> raw)) {
            return;
        }
        Set<Entity> seen = newSet();
        seen.addAll(targets);
        for (Object value : raw.values()) {
            if (value instanceof PartEntity<?> part) {
                Entity parent = part.getParent();
                if (CreatureCull.appliesTo(parent) && seen.add(parent)) {
                    targets.add(parent);
                }
            }
        }
    }

    private static void eraseAll(List<Entity> targets, Level level, boolean server) {
        int erased = 0;
        for (Entity entity : targets) {
            try {
                if (server && level instanceof ServerLevel serverLevel) {
                    eraseServer(serverLevel, entity);
                } else if (level instanceof ClientLevel clientLevel) {
                    eraseClient(clientLevel, entity);
                }
                erased++;
            } catch (Throwable t) {
                LOGGER.warning("Failed to erase " + entity.getName().getString() + ": " + t);
            }
        }
        if (erased > 0) {
            int count = erased;
            LOGGER.fine(() -> "Erased " + count + " creatures from " + level.dimension().location());
        }
    }

    private static void eraseServer(ServerLevel level, Entity entity) {
        condemn(entity);
        try {
            level.getChunkSource().removeEntity(entity);
        } catch (Throwable ignored) {
        }
        dropTracking(level, entity);
        serverStorage(level, entity);
        dropParts(read(level, "dragonParts"), entity);
        neutralise(entity);
    }

    private static void eraseClient(ClientLevel level, Entity entity) {
        try {
            level.removeEntity(entity.getId(), Entity.RemovalReason.KILLED);
        } catch (Throwable ignored) {
        }
        condemn(entity);
        clientStorage(level, entity);
        dropParts(read(level, "partEntities"), entity);
        neutralise(entity);
    }

    /** Layer 1. Riders are detached first so a player sitting on a creature is dismounted and kept. */
    private static void condemn(Entity entity) {
        try {
            entity.unRide();
        } catch (Throwable ignored) {
        }
        try {
            if (entity instanceof LivingEntity living) {
                Level level = living.level();
                living.setInvulnerable(false);
                living.invulnerableTime = 0;
                living.setHealth(0.0F);
                living.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
                living.die(level.damageSources().genericKill());
                living.kill();
            }
        } catch (Throwable ignored) {
        }
        try {
            entity.remove(Entity.RemovalReason.KILLED);
        } catch (Throwable ignored) {
        }
        try {
            entity.discard();
        } catch (Throwable ignored) {
        }
        try {
            entity.setRemoved(Entity.RemovalReason.KILLED);
        } catch (Throwable ignored) {
        }
    }

    private static void serverStorage(ServerLevel level, Entity entity) {
        Object manager = read(level, "entityManager");
        if (manager != null) {
            removeFromLookup(read(manager, "visibleEntityStorage"), entity);
            removeFromSections(read(manager, "sectionStorage"), entity);
            removeUuid(read(manager, "knownUuids"), entity);
        }
        removeFromTickList(read(level, "entityTickList"), entity);
        removeFromSet(read(level, "navigatingMobs"), entity);
    }

    private static void clientStorage(ClientLevel level, Entity entity) {
        Object manager = read(level, "entityStorage");
        if (manager != null) {
            removeFromLookup(read(manager, "entityStorage"), entity);
            removeFromSections(read(manager, "sectionStorage"), entity);
        }
        removeFromTickList(read(level, "tickingEntities"), entity);
    }

    /** Creatures still sitting in the chunk-load queue are condemned before that queue is flushed. */
    private static void stripInbox(Object manager) {
        Object inbox = read(manager, "loadingInbox");
        if (!(inbox instanceof Queue<?> queue)) {
            return;
        }
        for (Object chunk : queue) {
            Object list = read(chunk, "entities");
            if (!(list instanceof List<?> entities)) {
                continue;
            }
            for (Object value : entities) {
                if (value instanceof Entity entity && CreatureCull.appliesTo(entity)) {
                    condemn(entity);
                    neutralise(entity);
                }
            }
            try {
                entities.removeIf(value -> value instanceof Entity entity && CreatureCull.appliesTo(entity));
            } catch (UnsupportedOperationException ignored) {
                // An immutable snapshot still fails addEntity, because condemn marked them removed.
            }
        }
    }

    private static void dropTracking(ServerLevel level, Entity entity) {
        Object chunkMap = level.getChunkSource().chunkMap;
        Object entityMap = read(chunkMap, "entityMap");
        if (!(entityMap instanceof Int2ObjectMap<?> map)) {
            return;
        }
        Object tracked = map.get(entity.getId());
        if (tracked == null) {
            return;
        }
        try {
            Method broadcast = tracked.getClass().getMethod("broadcastRemoved");
            broadcast.invoke(tracked);
        } catch (Throwable ignored) {
        }
        map.remove(entity.getId());
    }

    private static void dropParts(Object partMap, Entity parent) {
        if (!(partMap instanceof Int2ObjectMap<?> map)) {
            return;
        }
        if (parent != null && parent.isMultipartEntity()) {
            PartEntity<?>[] parts = parent.getParts();
            if (parts != null) {
                for (PartEntity<?> part : parts) {
                    map.remove(part.getId());
                }
            }
        }
        List<Integer> stale = new ArrayList<>();
        for (Int2ObjectMap.Entry<?> entry : map.int2ObjectEntrySet()) {
            if (entry.getValue() instanceof PartEntity<?> part) {
                Entity owner = part.getParent();
                if (owner == parent || owner == null || (CreatureCull.appliesTo(owner) && owner.isRemoved())) {
                    stale.add(entry.getIntKey());
                }
            }
        }
        for (int id : stale) {
            map.remove(id);
        }
    }

    private static void removeFromLookup(Object lookup, Entity entity) {
        if (lookup instanceof EntityLookup<?> typed) {
            try {
                @SuppressWarnings("unchecked")
                EntityLookup<Entity> entities = (EntityLookup<Entity>) typed;
                entities.remove(entity);
            } catch (Throwable ignored) {
            }
        }
        removeFromMap(read(lookup, "byId"), entity.getId());
        Object byUuid = read(lookup, "byUuid");
        if (byUuid instanceof Map<?, ?> map) {
            try {
                map.remove(entity.getUUID());
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void removeFromSections(Object sectionStorage, Entity entity) {
        Object sections = read(sectionStorage, "sections");
        if (!(sections instanceof Map<?, ?> map)) {
            return;
        }
        for (Object section : map.values()) {
            if (section instanceof EntitySection<?> es) {
                try {
                    ((EntitySection) es).remove(entity);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void removeFromTickList(Object tickList, Entity entity) {
        if (tickList instanceof EntityTickList list) {
            try {
                list.remove(entity);
            } catch (Throwable ignored) {
            }
        }
        removeFromMap(read(tickList, "active"), entity.getId());
        removeFromMap(read(tickList, "passive"), entity.getId());
    }

    private static void removeFromMap(Object map, int id) {
        if (map instanceof Int2ObjectMap<?> raw) {
            try {
                raw.remove(id);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void removeUuid(Object set, Entity entity) {
        if (set instanceof Set<?> raw) {
            try {
                raw.remove(entity.getUUID());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void removeFromSet(Object set, Entity entity) {
        if (set instanceof Set<?> raw) {
            try {
                raw.remove(entity);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Layers 3 and 4. The callback is cleared last so the API removal above still ran the real one. */
    private static void neutralise(Entity entity) {
        if (SET_REMOVED != null) {
            try {
                SET_REMOVED.invoke(entity, Entity.RemovalReason.KILLED);
            } catch (Throwable ignored) {
            }
        }
        reflectSet(entity, REMOVAL_REASON, Entity.RemovalReason.KILLED);
        unsafeWriteReason(entity);
        try {
            entity.setLevelCallback(EntityInLevelCallback.NULL);
        } catch (Throwable ignored) {
        }
    }

    private static void reflectSet(Entity entity, Field field, Object value) {
        if (field == null) {
            return;
        }
        try {
            field.set(entity, value);
        } catch (Throwable ignored) {
        }
    }

    private static void unsafeWriteReason(Entity entity) {
        if (UNSAFE == null || REMOVAL_REASON == null || UNSAFE_OFFSET == null || UNSAFE_PUT == null) {
            return;
        }
        try {
            long off = (long) UNSAFE_OFFSET.invoke(UNSAFE, REMOVAL_REASON);
            UNSAFE_PUT.invoke(UNSAFE, entity, off, Entity.RemovalReason.KILLED);
        } catch (Throwable ignored) {
        }
    }

    private static Object read(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        String key = type.getName() + "#" + name;
        if (MISSING.contains(key)) {
            return null;
        }
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                FIELDS.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        MISSING.add(key);
        LOGGER.warning("No field " + name + " on " + type.getName());
        return null;
    }

    private static Set<Entity> newSet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static final Map<String, Field> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> MISSING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Field REMOVAL_REASON = removalReasonField();
    private static final MethodHandle SET_REMOVED = setRemovedHandle();
    private static final Object UNSAFE = resolveUnsafe();
    private static final Method UNSAFE_OFFSET = unsafeMethod("objectFieldOffset", Field.class);
    private static final Method UNSAFE_PUT = unsafeMethod("putObject", Object.class, long.class, Object.class);

    private static Field removalReasonField() {
        try {
            Field field = Entity.class.getDeclaredField("removalReason");
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            LOGGER.warning("Could not resolve Entity.removalReason: " + t);
            return null;
        }
    }

    private static MethodHandle setRemovedHandle() {
        try {
            Method method = Entity.class.getDeclaredMethod("setRemoved", Entity.RemovalReason.class);
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object resolveUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            for (Field field : unsafeClass.getDeclaredFields()) {
                if (field.getType() == unsafeClass) {
                    field.setAccessible(true);
                    return field.get(null);
                }
            }
        } catch (Throwable t) {
            LOGGER.warning("sun.misc.Unsafe unavailable; API and reflection layers still run: " + t);
        }
        return null;
    }

    private static Method unsafeMethod(String name, Class<?>... params) {
        if (UNSAFE == null) {
            return null;
        }
        try {
            Method method = UNSAFE.getClass().getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }
}
