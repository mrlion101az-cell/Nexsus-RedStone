package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wireless signals between transmitters and receivers. A transmitter publishes a strength on a
 * named channel; a receiver on the same channel, inside range, sees the strongest value any
 * in-range transmitter was sending {@code distance / blocksPerTick} ticks ago -- a real, felt
 * propagation delay, not just a flat range check. Channels are private to their owner unless
 * marked shared, so two players can both use "gate" without hearing each other.
 *
 * <p>Range is set by tier: the weaker of the transmitter's and receiver's tier limits the link,
 * and only a link between two top-tier devices may cross worlds. Transmitters that stop
 * publishing (their chunk unloaded) fall silent after a stale timeout rather than holding a
 * stuck signal forever.
 */
final class WirelessNetwork {

    private static final int MAX_HISTORY = 24;

    /** Stand-in distance for a cross-dimension link, which has no meaningful block distance:
     *  it simply takes a fixed few ticks, instead of an absurd latency from an "infinite" range. */
    private static final double CROSS_WORLD_DISTANCE = 256.0;

    private static final class Tx {
        final String componentKey;
        final String channel;
        final String world;
        final int x;
        final int y;
        final int z;
        final int tier;
        long lastPublish;
        final List<long[]> history = new ArrayList<>();

        Tx(String componentKey, String channel, String world, int x, int y, int z, int tier) {
            this.componentKey = componentKey;
            this.channel = channel;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tier = tier;
        }

        void record(long now, int value) {
            lastPublish = now;
            if (history.isEmpty() || history.get(history.size() - 1)[1] != value) {
                history.add(new long[] {now, value});
                while (history.size() > MAX_HISTORY) {
                    history.remove(0);
                }
            }
        }

        int valueAt(long tick) {
            int v = 0;
            for (long[] h : history) {
                if (h[0] <= tick) {
                    v = (int) h[1];
                } else {
                    break;
                }
            }
            return v;
        }

        int latest() {
            return history.isEmpty() ? 0 : (int) history.get(history.size() - 1)[1];
        }
    }

    /** A live link, for the visualizer. */
    record Link(String txKey, String rxKey, int value, double distance) {
    }

    private final Map<String, Tx> transmitters = new HashMap<>();
    private final RedstoneConfig cfg;

    WirelessNetwork(RedstoneConfig cfg) {
        this.cfg = cfg;
    }

    static String normalize(String channel, int maxLength) {
        String s = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            s = "default";
        }
        return s.length() > maxLength ? s.substring(0, maxLength) : s;
    }

    /** Scope-qualified channel id: "*" for a public channel, the owner's id for a private one. */
    String channelOf(Component c) {
        String name = normalize(c.text, cfg.maxChannelLength);
        boolean shared = c.param("shared") == 1;
        String scope = shared || c.owner == null ? "*" : c.owner.toString();
        return scope + "|" + name;
    }

    int tierRange(int tier) {
        switch (tier) {
            case 0:
                return cfg.wirelessRangeLocal;
            case 1:
                return cfg.wirelessRangeRegional;
            case 2:
                return cfg.wirelessRangeLong;
            default:
                return Integer.MAX_VALUE;
        }
    }

    /** Tick delay a signal takes to cover {@code distance} blocks. */
    int latencyTicks(double distance) {
        return (int) Math.ceil(distance / cfg.wirelessBlocksPerTick);
    }

    /** Publishes a transmitter's current value. {@code effectiveTier} is the transmitter's tier
     *  after its owner's Engineer level has capped it. */
    void publish(Component tx, int value, long now, int effectiveTier) {
        String channel = channelOf(tx);
        Tx t = transmitters.get(tx.key());
        if (t == null || !t.channel.equals(channel) || t.tier != effectiveTier) {
            t = new Tx(tx.key(), channel, tx.world, tx.x, tx.y, tx.z, effectiveTier);
            transmitters.put(tx.key(), t);
        }
        t.record(now, Math.max(0, Math.min(15, value)));
    }

    /** The strongest delayed value a receiver hears right now. */
    int read(Component rx, long now, int effectiveRxTier) {
        String channel = channelOf(rx);
        int best = 0;
        for (Tx t : transmitters.values()) {
            if (!t.channel.equals(channel) || now - t.lastPublish > cfg.wirelessStaleTicks) {
                continue;
            }
            double dist = distanceIfReachable(t, rx, effectiveRxTier);
            if (dist < 0) {
                continue;
            }
            int v = t.valueAt(now - latencyTicks(dist));
            if (v > best) {
                best = v;
            }
        }
        return best;
    }

    /** Distance to the receiver, or -1 if the link cannot reach (range or world). */
    private double distanceIfReachable(Tx t, Component rx, int rxTier) {
        int minTier = Math.min(t.tier, rxTier);
        boolean sameWorld = t.world.equals(rx.world);
        if (!sameWorld) {
            return minTier >= 3 ? CROSS_WORLD_DISTANCE : -1;
        }
        double dx = t.x - rx.x;
        double dy = t.y - rx.y;
        double dz = t.z - rx.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist <= tierRange(minTier) ? dist : -1;
    }

    void remove(String componentKey) {
        transmitters.remove(componentKey);
    }

    int transmitterCount() {
        return transmitters.size();
    }

    /** Active links from currently-live transmitters to the given receivers (same world only,
     *  since the visualizer draws in one world). */
    List<Link> links(List<Component> receivers, long now, java.util.function.ToIntFunction<Component> rxTier) {
        List<Link> out = new ArrayList<>();
        for (Component rx : receivers) {
            if (rx.type != ComponentType.WIRELESS_RX) {
                continue;
            }
            String channel = channelOf(rx);
            int tier = rxTier.applyAsInt(rx);
            for (Tx t : transmitters.values()) {
                if (!t.channel.equals(channel) || now - t.lastPublish > cfg.wirelessStaleTicks || !t.world.equals(rx.world)) {
                    continue;
                }
                double d = distanceIfReachable(t, rx, tier);
                if (d >= 0) {
                    out.add(new Link(t.componentKey, rx.key(), t.latest(), d));
                }
            }
        }
        return out;
    }
}
