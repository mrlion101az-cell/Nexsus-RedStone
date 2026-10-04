package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The circuit debugger's oscilloscope. A multimeter right-click drops a <em>probe</em> on a block;
 * from then on, every simulation step records that block's signal into a {@link SignalTrace}, and
 * {@code /redstone scope} (or sneak-clicking the multimeter in the air) prints the waveform, its
 * measured period and its duty cycle. Redstone is hard to debug because you can only ever see one
 * instant of it; a recorded trace makes a clock's real speed, a stuck latch or a one-tick glitch
 * visible at last.
 */
final class ScopeService {

    static final long WINDOW_TICKS = 200;

    private static final class Probe {
        final String world;
        final int x;
        final int y;
        final int z;
        final SignalTrace trace;
        final String label;

        Probe(String world, int x, int y, int z, int samples, String label) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.trace = new SignalTrace(samples);
            this.label = label;
        }

        boolean at(Block b) {
            return b.getX() == x && b.getY() == y && b.getZ() == z && b.getWorld().getName().equals(world);
        }
    }

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final Map<UUID, List<Probe>> probes = new HashMap<>();

    ScopeService(RedstoneConfig cfg, ComponentRegistry registry) {
        this.cfg = cfg;
        this.registry = registry;
    }

    /** The strength this plugin reports for any block: a component reports its emitted level;
     *  dust reports its own strength; levers and buttons 0 or 15; anything else the power it
     *  receives. */
    int signalAt(Block b) {
        Component c = registry.resolve(Geo.key(b));
        if (c != null && c.type.hasOutputCell) {
            return c.output;
        }
        BlockData data = b.getBlockData();
        if (data instanceof AnaloguePowerable) {
            return ((AnaloguePowerable) data).getPower();
        }
        if (data instanceof Powerable) {
            return ((Powerable) data).isPowered() ? 15 : 0;
        }
        return b.getBlockPower();
    }

    /** Adds a probe; returns the message to show, or an error. Probing the same block twice
     *  removes the probe instead. */
    String toggle(Player p, Block b) {
        List<Probe> list = probes.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        for (Iterator<Probe> it = list.iterator(); it.hasNext();) {
            Probe existing = it.next();
            if (existing.at(b)) {
                it.remove();
                return "§7Probe removed from " + b.getX() + ", " + b.getY() + ", " + b.getZ() + ".";
            }
        }
        if (list.size() >= cfg.maxProbesPerPlayer) {
            return "§cYou already have " + cfg.maxProbesPerPlayer + " probes. Click an existing probe to remove it, or /redstone scope clear.";
        }
        Component c = registry.resolve(Geo.key(b));
        String label = c != null ? c.type.display : b.getType().name().toLowerCase().replace('_', ' ');
        list.add(new Probe(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), cfg.scopeSamples * 4, label));
        return "§aProbe " + list.size() + " attached to " + label + " at " + b.getX() + ", " + b.getY() + ", " + b.getZ()
                + ". Strength now §f" + signalAt(b) + "§a. Sneak-click the multimeter in the air, or /redstone scope, to read it.";
    }

    void clear(Player p) {
        probes.remove(p.getUniqueId());
    }

    void forget(UUID player) {
        probes.remove(player);
    }

    int probeCount() {
        int n = 0;
        for (List<Probe> l : probes.values()) {
            n += l.size();
        }
        return n;
    }

    /** Records one sample on every live probe. */
    void sample(long tick) {
        if (probes.isEmpty()) {
            return;
        }
        for (List<Probe> list : probes.values()) {
            for (Probe pr : list) {
                World w = Bukkit.getWorld(pr.world);
                if (w == null || !w.isChunkLoaded(pr.x >> 4, pr.z >> 4)) {
                    continue;
                }
                pr.trace.record(tick, signalAt(w.getBlockAt(pr.x, pr.y, pr.z)));
            }
        }
    }

    /** Chat lines for a player's scopes. */
    List<String> render(Player p, long now) {
        List<String> out = new ArrayList<>();
        List<Probe> list = probes.get(p.getUniqueId());
        if (list == null || list.isEmpty()) {
            out.add("§7You have no probes. Right-click a block with the Multimeter to attach one.");
            return out;
        }
        int columns = 40;
        out.add("§e§lScope §7(last " + (WINDOW_TICKS / 20) + " seconds, " + (WINDOW_TICKS / columns) + " ticks per column)");
        int n = 1;
        for (Probe pr : list) {
            out.add("§b" + n + ". " + pr.label + " §8" + pr.x + ", " + pr.y + ", " + pr.z + " §7now §f" + pr.trace.currentValue());
            out.add("§f  " + pr.trace.hexLine(columns, WINDOW_TICKS, now));
            for (String row : pr.trace.graph(columns, 3, WINDOW_TICKS, now)) {
                out.add("§a  " + row);
            }
            long period = pr.trace.periodTicks(WINDOW_TICKS, now);
            out.add("§7  pulses §f" + pr.trace.risingEdges(WINDOW_TICKS, now) + "§7, period §f"
                    + (period < 0 ? "n/a" : period + " ticks (" + String.format("%.2f", period / 20.0) + " s)")
                    + "§7, on §f" + pr.trace.dutyPercent(WINDOW_TICKS, now) + "%");
            n++;
        }
        return out;
    }
}
