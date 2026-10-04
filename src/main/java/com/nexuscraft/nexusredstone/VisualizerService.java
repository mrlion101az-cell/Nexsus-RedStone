package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The circuit visualizer. For a few seconds after {@code /redstone visualize}, particles (shown
 * only to that player) reveal what is normally invisible: live output cells spark, wireless links
 * draw dotted lines between transmitters and receivers, transport ports glow and trace their
 * routes, and any component the lag guard has shut off smokes. Rendering is bounded by the
 * configured radius and a hard cap on particles per pass.
 */
final class VisualizerService {

    private static final int MAX_PARTICLES_PER_PASS = 400;

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final WirelessNetwork wireless;
    private final EngineerStore engineers;
    private final LongSupplier clock;
    private final Map<UUID, Long> until = new HashMap<>();

    VisualizerService(RedstoneConfig cfg, ComponentRegistry registry, WirelessNetwork wireless, EngineerStore engineers,
                      LongSupplier clock) {
        this.cfg = cfg;
        this.registry = registry;
        this.wireless = wireless;
        this.engineers = engineers;
        this.clock = clock;
    }

    void start(Player p, int seconds) {
        int s = Math.max(2, Math.min(cfg.visualizerSeconds * 5, seconds));
        until.put(p.getUniqueId(), System.currentTimeMillis() + s * 1000L);
    }

    void stop(Player p) {
        until.remove(p.getUniqueId());
    }

    boolean active(Player p) {
        Long u = until.get(p.getUniqueId());
        return u != null && u > System.currentTimeMillis();
    }

    /** Timer entry point (every few ticks). */
    void run() {
        if (until.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Long>> it = until.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Long> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || e.getValue() <= now) {
                it.remove();
                continue;
            }
            draw(p);
        }
    }

    private void draw(Player p) {
        World w = p.getWorld();
        if (w == null) {
            return;
        }
        Location here = p.getLocation();
        double r = cfg.visualizerRadius;
        double r2 = r * r;
        int[] budget = {MAX_PARTICLES_PER_PASS};
        List<Component> receivers = new ArrayList<>();
        for (Component c : registry.all()) {
            if (!c.world.equals(w.getName())) {
                continue;
            }
            Location centre = new Location(w, c.x + 0.5, c.y + 0.5, c.z + 0.5);
            if (centre.distanceSquared(here) > r2) {
                continue;
            }
            if (c.lagTrippedUntil > 0) {
                puff(p, Particle.LARGE_SMOKE, centre.clone().add(0, 0.8, 0), budget);
            }
            if (c.type.hasOutputCell && c.output > 0) {
                Location cell = new Location(w, c.x + 0.5 + c.facing.dx, c.y + 0.5, c.z + 0.5 + c.facing.dz);
                puff(p, Particle.ELECTRIC_SPARK, cell, budget);
            }
            switch (c.type) {
                case PORT_IN:
                    puff(p, Particle.FLAME, centre.clone().add(0, 0.7, 0), budget);
                    routeLines(p, c, w, budget);
                    break;
                case PORT_OUT:
                    puff(p, Particle.HAPPY_VILLAGER, centre.clone().add(0, 0.7, 0), budget);
                    break;
                case WIRELESS_RX:
                    receivers.add(c);
                    puff(p, Particle.END_ROD, centre.clone().add(0, 0.7, 0), budget);
                    break;
                case WIRELESS_TX:
                    puff(p, Particle.END_ROD, centre.clone().add(0, 0.7, 0), budget);
                    break;
                default:
                    break;
            }
        }
        long tick = clock.getAsLong();
        for (WirelessNetwork.Link link : wireless.links(receivers, tick, rx -> Math.min(rx.param("tier"),
                EngineerSkill.maxWirelessTier(engineers.levelOfOwner(rx.owner), cfg)))) {
            Component tx = registry.get(link.txKey());
            Component rx = registry.get(link.rxKey());
            if (tx == null || rx == null) {
                continue;
            }
            line(p, w, tx, rx, link.value() > 0 ? Particle.FLAME : Particle.END_ROD, budget);
        }
    }

    private void routeLines(Player p, Component in, World w, int[] budget) {
        TransportNetwork.Result route = TransportNetwork.route(registry, in, cfg.pipeLengthCeiling, 200);
        for (TransportNetwork.Destination d : route.destinations()) {
            line(p, w, in, d.port(), Particle.NOTE, budget);
        }
    }

    private void line(Player p, World w, Component a, Component b, Particle particle, int[] budget) {
        if (!a.world.equals(b.world)) {
            return;
        }
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int pts = (int) Math.max(2, Math.min(24, len / 1.5));
        for (int i = 1; i < pts; i++) {
            double f = (double) i / pts;
            puff(p, particle, new Location(w, a.x + 0.5 + dx * f, a.y + 0.8 + dy * f, a.z + 0.5 + dz * f), budget);
        }
    }

    private void puff(Player p, Particle particle, Location at, int[] budget) {
        if (budget[0] <= 0) {
            return;
        }
        budget[0]--;
        p.spawnParticle(particle, at, 1);
    }
}
