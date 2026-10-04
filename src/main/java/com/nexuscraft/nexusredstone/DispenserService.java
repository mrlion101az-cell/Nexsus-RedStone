package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Smart dispensers and droppers: burst fire, spray, gentle drop, and power shots. The mode lives
 * on the component; this class only turns it into velocity changes on the vanilla dispense event,
 * plus scheduled extra shots for burst mode. An extra shot is a real {@code dispense()}/
 * {@code drop()} call, which fires its own dispense event -- so the keys of blocks currently
 * mid-burst are remembered, and the handler never schedules a burst for a burst shot.
 */
final class DispenserService implements Listener {

    private final Plugin plugin;
    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final BukkitCircuitHost host;
    private final Random random = new Random();
    private final Set<String> bursting = new HashSet<>();

    DispenserService(Plugin plugin, RedstoneConfig cfg, ComponentRegistry registry, BukkitCircuitHost host) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.registry = registry;
        this.host = host;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (registry.size() == 0) {
            return;
        }
        Block block = event.getBlock();
        Component c = registry.at(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (c == null || c.type.family != Family.DISPENSER) {
            return;
        }
        boolean dropper = c.type == ComponentType.DROPPER_SMART;
        int mode = c.param("mode");
        Vector v = event.getVelocity();
        if (v != null && mode != DispenserModes.NORMAL && mode != DispenserModes.BURST) {
            double[] nv = DispenserModes.velocity(mode, dropper, v.getX(), v.getY(), v.getZ(), cfg, random);
            event.setVelocity(new Vector(nv[0], nv[1], nv[2]));
        }
        String key = c.key();
        if (mode == DispenserModes.BURST && !bursting.contains(key)) {
            List<Integer> offsets = DispenserModes.burstOffsets(c.param("burst"), c.param("gap"));
            for (int offset : offsets) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> shoot(c), offset);
            }
            host.noteActivity(c);
        }
    }

    private void shoot(Component c) {
        if (registry.get(c.key()) != c) {
            return;
        }
        Block block = Geo.body(c);
        if (block == null || !host.isActive(c)) {
            return;
        }
        BlockState state = block.getState(false);
        bursting.add(c.key());
        try {
            if (state instanceof Dispenser) {
                ((Dispenser) state).dispense();
            } else if (state instanceof Dropper) {
                ((Dropper) state).drop();
            }
        } finally {
            bursting.remove(c.key());
        }
    }
}
