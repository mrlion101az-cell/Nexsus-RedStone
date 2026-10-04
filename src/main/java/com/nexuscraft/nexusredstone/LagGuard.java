package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lag-machine protection. Redstone is the most common way a survival server gets taken down, so
 * every circuit component reports its output changes here, and this class decides, per step,
 * whether a component runs, is thinned out, or is shut off.
 *
 * <p>Two independent defenses, both generous by default so a normal build never notices them:
 * <ul>
 * <li><b>Per component</b>: an output that toggles more than {@code lagMaxTogglesPerWindow} times
 * inside the window (a clock fed back into itself, an oscillating loop) is TRIPPED -- forced off
 * for a cool-down -- and its owner is told why.</li>
 * <li><b>Per chunk</b>: every change in a chunk counts against a per-second budget, scaled up for
 * higher-level engineers. Over budget, every component in the chunk is THROTTLED (evaluated one
 * step in N); over budget for three seconds running, the single worst offender in that chunk is
 * tripped.</li>
 * </ul>
 * It also counts vanilla {@code BlockRedstoneEvent}s per chunk -- which a plugin can neither
 * cancel nor slow -- purely to alert about hot chunks, so staff can find the farm that is melting
 * the tick rate. That part reports; it does not (and cannot honestly claim to) throttle vanilla.
 */
final class LagGuard {

    enum Verdict { RUN, SKIP, TRIPPED }

    record Trip(String componentKey, UUID owner, String reason) {
    }

    record HotChunk(String chunkKey, int perSecond) {
    }

    private static final class ChunkLoad {
        long windowStart;
        int count;
        int lastCount;
        int maxLevel = 1;
        int overStreak;
        long throttleUntil;
        long lastVanillaAlert = Long.MIN_VALUE / 2;
        long vanillaWindow;
        int vanillaCount;
        int vanillaLast;
        final Map<String, Integer> perComponent = new HashMap<>();
    }

    private static final long WINDOW = 20L;

    private final RedstoneConfig cfg;
    private final Map<String, ChunkLoad> chunks = new HashMap<>();
    private final Map<String, long[]> toggles = new HashMap<>();
    private final Map<String, Integer> toggleIndex = new HashMap<>();
    private final List<Trip> trips = new ArrayList<>();
    private final List<HotChunk> vanillaAlerts = new ArrayList<>();

    LagGuard(RedstoneConfig cfg) {
        this.cfg = cfg;
    }

    static String chunkKey(String world, int x, int z) {
        return world + ":" + (x >> 4) + "," + (z >> 4);
    }

    static String chunkKey(Component c) {
        return chunkKey(c.world, c.x, c.z);
    }

    Verdict check(Component c, long now) {
        if (!cfg.lagEnabled) {
            return Verdict.RUN;
        }
        if (c.lagTrippedUntil > now) {
            return Verdict.TRIPPED;
        }
        ChunkLoad load = chunks.get(chunkKey(c));
        if (load != null && load.throttleUntil > now) {
            long stepIndex = now / Math.max(1, cfg.stepIntervalTicks);
            return stepIndex % cfg.lagThrottleDivisor == 0 ? Verdict.RUN : Verdict.SKIP;
        }
        return Verdict.RUN;
    }

    /** Records one output change of a component whose owner is at {@code ownerLevel}. */
    void recordChange(Component c, long now, int ownerLevel) {
        if (!cfg.lagEnabled) {
            return;
        }
        recordToggle(c, now);
        recordChunk(c, now, ownerLevel);
    }

    private void recordToggle(Component c, long now) {
        String key = c.key();
        int cap = cfg.lagMaxTogglesPerWindow + 1;
        long[] ring = toggles.computeIfAbsent(key, k -> new long[cap]);
        if (ring.length != cap) {
            ring = new long[cap];
            toggles.put(key, ring);
            toggleIndex.put(key, 0);
        }
        int idx = toggleIndex.getOrDefault(key, 0);
        ring[idx] = now + 1;
        toggleIndex.put(key, (idx + 1) % cap);
        int recent = 0;
        long cutoff = now - cfg.lagWindowTicks;
        for (long t : ring) {
            if (t != 0 && t - 1 > cutoff) {
                recent++;
            }
        }
        if (recent > cfg.lagMaxTogglesPerWindow) {
            trip(c, now, "its output flipped " + recent + " times in " + (cfg.lagWindowTicks / 20) + " seconds");
        }
    }

    private void recordChunk(Component c, long now, int ownerLevel) {
        ChunkLoad load = chunks.computeIfAbsent(chunkKey(c), k -> new ChunkLoad());
        rotate(load, now, c);
        load.count++;
        load.maxLevel = Math.max(load.maxLevel, Math.max(1, ownerLevel));
        load.perComponent.merge(c.key(), 1, Integer::sum);
    }

    private void rotate(ChunkLoad load, long now, Component hint) {
        if (now - load.windowStart < WINDOW) {
            return;
        }
        load.lastCount = load.count;
        int budget = chunkBudget(load);
        if (load.lastCount > budget) {
            load.overStreak++;
            load.throttleUntil = now + WINDOW * 2;
            if (load.overStreak >= 3) {
                String worst = null;
                int worstCount = -1;
                for (Map.Entry<String, Integer> e : load.perComponent.entrySet()) {
                    if (e.getValue() > worstCount) {
                        worstCount = e.getValue();
                        worst = e.getKey();
                    }
                }
                load.overStreak = 0;
                if (worst != null && hint != null) {
                    pendingWorst = worst;
                    pendingWorstCount = worstCount;
                    pendingWorstChunkCount = load.lastCount;
                }
            }
        } else {
            load.overStreak = 0;
        }
        load.windowStart = now;
        load.count = 0;
        load.perComponent.clear();
    }

    // The chunk-level trip needs the component object, which only the engine's registry holds;
    // rotate() remembers the key and the engine resolves it via resolveWorstOffender().
    private String pendingWorst;
    private int pendingWorstCount;
    private int pendingWorstChunkCount;

    /** Called by the engine after each step: if a chunk has been over budget long enough, trips
     *  the component that caused the most changes. Returns null if nothing is pending. */
    Component resolveWorstOffender(ComponentRegistry registry, long now) {
        if (pendingWorst == null) {
            return null;
        }
        Component c = registry.get(pendingWorst);
        String key = pendingWorst;
        int count = pendingWorstCount;
        int chunkCount = pendingWorstChunkCount;
        pendingWorst = null;
        if (c == null) {
            return null;
        }
        trip(c, now, "it caused " + count + " of its chunk's " + chunkCount + " signal changes in one second, three seconds running (chunk over budget)");
        return registry.get(key);
    }

    private int chunkBudget(ChunkLoad load) {
        return (int) Math.max(1, Math.round(cfg.lagChunkBudgetPerSecond * EngineerSkill.lagBudgetMultiplier(load.maxLevel, cfg)));
    }

    private void trip(Component c, long now, String reason) {
        if (c.lagTrippedUntil > now) {
            return;
        }
        c.lagTrippedUntil = now + cfg.lagTripCooldownSeconds * 20L;
        toggles.remove(c.key());
        toggleIndex.remove(c.key());
        trips.add(new Trip(c.key(), c.owner, reason));
    }

    /** Forgets a component's history (it was removed or its trip cooled down). */
    void forget(String componentKey) {
        toggles.remove(componentKey);
        toggleIndex.remove(componentKey);
    }

    List<Trip> drainTrips() {
        if (trips.isEmpty()) {
            return List.of();
        }
        List<Trip> copy = new ArrayList<>(trips);
        trips.clear();
        return copy;
    }

    /** Counts one vanilla redstone event in a chunk. */
    void recordVanilla(String chunkKey, long now) {
        ChunkLoad load = chunks.computeIfAbsent(chunkKey, k -> new ChunkLoad());
        if (now - load.vanillaWindow >= WINDOW) {
            if (load.vanillaCount * (20.0 / WINDOW) > cfg.vanillaRedstoneWarnPerSecond
                    && now - load.lastVanillaAlert > cfg.lagAlertCooldownSeconds * 20L) {
                load.lastVanillaAlert = now;
                vanillaAlerts.add(new HotChunk(chunkKey, (int) Math.round(load.vanillaCount * (20.0 / WINDOW))));
            }
            load.vanillaLast = load.vanillaCount;
            load.vanillaWindow = now;
            load.vanillaCount = 0;
        }
        load.vanillaCount++;
    }

    List<HotChunk> drainVanillaAlerts() {
        if (vanillaAlerts.isEmpty()) {
            return List.of();
        }
        List<HotChunk> copy = new ArrayList<>(vanillaAlerts);
        vanillaAlerts.clear();
        return copy;
    }

    /** Busiest chunks by this plugin's own components and by vanilla redstone, hottest first. */
    List<HotChunk> hottest(int limit) {
        List<HotChunk> list = new ArrayList<>();
        for (Map.Entry<String, ChunkLoad> e : chunks.entrySet()) {
            ChunkLoad l = e.getValue();
            int perSecond = Math.max(l.lastCount, (int) Math.round(l.vanillaLast * (20.0 / WINDOW)));
            if (perSecond > 0) {
                list.add(new HotChunk(e.getKey(), perSecond));
            }
        }
        list.sort(Comparator.comparingInt(HotChunk::perSecond).reversed());
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    boolean isThrottled(String chunkKey, long now) {
        ChunkLoad l = chunks.get(chunkKey);
        return l != null && l.throttleUntil > now;
    }
}
