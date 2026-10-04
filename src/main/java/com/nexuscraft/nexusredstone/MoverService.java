package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Drives every Block Mover: a lodestone block that carries a whole honey/slime-glued structure
 * along an axis, with smooth block-display animation, entities riding along, and a crash-safe
 * journal.
 *
 * <p>Safety is the design centre. A trip lifts real blocks off the world and puts them back at
 * the end, so the one unacceptable failure is losing blocks halfway. Before a single block is
 * lifted, the <em>final</em> position and block data of every carried block is written to a
 * journal file; if the server stops or crashes mid-trip, the next start places every block at its
 * destination (and corrects the mover's recorded offset). A clean shutdown finishes every trip
 * instantly. A destination that something else has since filled drops the block as an item
 * instead of overwriting it.
 */
final class MoverService {

    private static final String JOURNAL = "mover-journal.yml";

    private final Plugin plugin;
    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final EngineerStore engineers;
    private final Announcer announcer;
    private final BukkitCircuitHost host;
    private final Logger log;

    private final Map<String, Trip> trips = new LinkedHashMap<>();
    private final Set<String> blockedNotified = new HashSet<>();
    private boolean firing;
    private int tripCounter;

    private static final class Trip {
        String id;
        Component c;
        World world;
        int[] dir;
        int steps;
        int speed;
        List<int[]> cells;
        List<BlockData> data;
        List<BlockDisplay> displays = new ArrayList<>();
        List<Entity> riders = new ArrayList<>();
        int finalOffset;
        int stepIndex;
        int tickInStep;
        int delay = 2;
    }

    MoverService(Plugin plugin, RedstoneConfig cfg, ComponentRegistry registry, EngineerStore engineers,
                 Announcer announcer, BukkitCircuitHost host, Logger log) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.registry = registry;
        this.engineers = engineers;
        this.announcer = announcer;
        this.host = host;
        this.log = log;
    }

    boolean isFiring() {
        return firing;
    }

    boolean isMoving(Component c) {
        return trips.containsKey(c.key());
    }

    int activeTrips() {
        return trips.size();
    }

    // ------------------------------------------------------------------ power polling

    void poll(long now) {
        for (Component c : registry.ofFamily(Family.MOVER)) {
            if (c.type != ComponentType.BLOCK_MOVER || !host.isActive(c)) {
                continue;
            }
            Block body = Geo.body(c);
            if (body == null) {
                continue;
            }
            boolean powered = body.isBlockIndirectlyPowered();
            boolean wasPowered = c.prevBack != 0;
            boolean rising = powered && !wasPowered;
            if (powered != wasPowered) {
                c.prevBack = powered ? 1 : 0;
                blockedNotified.remove(c.key());
            }
            int distance = c.param("distance");
            int target;
            if (c.param("mode") == 1) {
                if (rising) {
                    c.state = !c.state;
                    registry.markDirty();
                }
                target = c.state ? distance : 0;
            } else {
                target = powered ? distance : 0;
            }
            if (isMoving(c) || now < c.cooldownUntil) {
                continue;
            }
            // A changed distance setting can leave the recorded offset beyond the new target.
            if (c.memory != target) {
                start(c, body, target, now);
            }
        }
    }

    // ------------------------------------------------------------------ starting a trip

    private void start(Component c, Block body, int target, long now) {
        World w = body.getWorld();
        int level = engineers.levelOfOwner(c.owner);
        int[] v = Dir.vector(c.facing, c.param("axis"));
        int cur = c.memory;
        int delta = target - cur;
        int sign = delta > 0 ? 1 : -1;
        int want = Math.abs(delta);
        WorldBlockView view = new WorldBlockView(w, registry);

        StructurePlanner.Plan col = StructurePlanner.collect(view, c.x, c.y, c.z, v[0] * cur, v[1] * cur, v[2] * cur,
                EngineerSkill.moverBlocks(level, cfg));
        if (!col.ok()) {
            blocked(c, now, col.reason());
            return;
        }
        int[] dir = {v[0] * sign, v[1] * sign, v[2] * sign};
        StructurePlanner.Plan sweep = StructurePlanner.sweep(view, col.cells(), dir[0], dir[1], dir[2], want);
        if (!sweep.ok()) {
            blocked(c, now, sweep.reason());
            return;
        }
        List<Block> blocks = new ArrayList<>();
        for (int[] cell : col.cells()) {
            blocks.add(w.getBlockAt(cell[0], cell[1], cell[2]));
        }
        if (!claimsAllow(body, blocks, dir)) {
            blocked(c, now, "a protection plugin refused the move");
            return;
        }

        Trip t = new Trip();
        t.id = "t" + (tripCounter++);
        t.c = c;
        t.world = w;
        t.dir = dir;
        t.steps = sweep.distance();
        t.speed = c.param("speed");
        t.cells = col.cells();
        t.finalOffset = cur + sign * t.steps;
        t.data = new ArrayList<>();
        for (int[] cell : t.cells) {
            t.data.add(w.getBlockAt(cell[0], cell[1], cell[2]).getBlockData());
        }
        for (int[] b : sweep.breaks()) {
            Block block = w.getBlockAt(b[0], b[1], b[2]);
            if (!block.getType().isAir()) {
                block.breakNaturally();
            }
        }
        boolean animated = t.cells.size() <= cfg.moverDisplayCap && t.speed > 0;
        c.cooldownUntil = now + cfg.moverCooldownTicks + (animated ? (long) t.steps * t.speed : 0L);
        blockedNotified.remove(c.key());

        if (!animated) {
            for (int[] cell : t.cells) {
                w.getBlockAt(cell[0], cell[1], cell[2]).setType(Material.AIR, false);
            }
            place(t);
            complete(t);
            return;
        }

        trips.put(c.key(), t);
        saveJournal();
        for (int i = 0; i < t.cells.size(); i++) {
            int[] cell = t.cells.get(i);
            Location loc = new Location(w, cell[0], cell[1], cell[2]);
            BlockDisplay display = w.spawn(loc, BlockDisplay.class);
            display.setBlock(t.data.get(i));
            display.setTeleportDuration(Math.min(59, t.speed));
            display.setViewRange(2.0f);
            display.setPersistent(false);
            t.displays.add(display);
        }
        for (int[] cell : t.cells) {
            w.getBlockAt(cell[0], cell[1], cell[2]).setType(Material.AIR, false);
        }
        if (cfg.moverCarryEntities) {
            t.riders = findRiders(t);
        }
        w.playSound(body.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_PISTON_EXTEND, 1.0f, 0.5f);
        host.noteActivity(c);
        announcer.sayAt(c.owner, Topic.MOVER_TRIP, body);
    }

    private void blocked(Component c, long now, String reason) {
        c.cooldownUntil = now + 100;
        if (blockedNotified.add(c.key())) {
            announcer.tell(c.owner, "§c§l✖ §cThe block mover at " + c.x + ", " + c.y + ", " + c.z + " cannot move: " + reason + ".");
        }
    }

    private boolean claimsAllow(Block body, List<Block> blocks, int[] dir) {
        firing = true;
        try {
            BlockFace face = dir[1] > 0 ? BlockFace.UP : dir[1] < 0 ? BlockFace.DOWN : Geo.face(
                    dir[0] > 0 ? Dir.EAST : dir[0] < 0 ? Dir.WEST : dir[2] > 0 ? Dir.SOUTH : Dir.NORTH);
            BlockPistonExtendEvent e = new BlockPistonExtendEvent(body, blocks, face);
            Bukkit.getPluginManager().callEvent(e);
            return !e.isCancelled();
        } finally {
            firing = false;
        }
    }

    /** Entities standing on, or inside, the carried structure. */
    private List<Entity> findRiders(Trip t) {
        Set<String> own = new HashSet<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] cell : t.cells) {
            own.add(cell[0] + "," + cell[1] + "," + cell[2]);
            minX = Math.min(minX, cell[0]);
            minY = Math.min(minY, cell[1]);
            minZ = Math.min(minZ, cell[2]);
            maxX = Math.max(maxX, cell[0]);
            maxY = Math.max(maxY, cell[1]);
            maxZ = Math.max(maxZ, cell[2]);
        }
        Location centre = new Location(t.world, (minX + maxX + 1) / 2.0, (minY + maxY + 3) / 2.0, (minZ + maxZ + 1) / 2.0);
        double hx = (maxX - minX + 1) / 2.0 + 1;
        double hy = (maxY - minY + 3) / 2.0 + 1;
        double hz = (maxZ - minZ + 1) / 2.0 + 1;
        List<Entity> riders = new ArrayList<>();
        for (Entity e : t.world.getNearbyEntities(centre, hx, hy, hz)) {
            if (e instanceof Display) {
                continue;
            }
            Location l = e.getLocation();
            int bx = (int) Math.floor(l.getX());
            int bz = (int) Math.floor(l.getZ());
            int by = (int) Math.floor(l.getY());
            int below = (int) Math.floor(l.getY() - 0.05);
            if (own.contains(bx + "," + below + "," + bz) || own.contains(bx + "," + by + "," + bz)) {
                riders.add(e);
            }
        }
        return riders;
    }

    // ------------------------------------------------------------------ animation

    /** Called every server tick; does nothing unless a trip is in flight. */
    void animate() {
        if (trips.isEmpty()) {
            return;
        }
        for (Trip t : new ArrayList<>(trips.values())) {
            if (t.delay > 0) {
                t.delay--;
                continue;
            }
            if (t.tickInStep == 0) {
                int k = t.stepIndex + 1;
                for (int i = 0; i < t.displays.size(); i++) {
                    int[] cell = t.cells.get(i);
                    BlockDisplay d = t.displays.get(i);
                    if (d.isValid()) {
                        d.teleport(new Location(t.world, cell[0] + t.dir[0] * k, cell[1] + t.dir[1] * k, cell[2] + t.dir[2] * k));
                    }
                }
            }
            double per = 1.0 / t.speed;
            for (Entity rider : t.riders) {
                if (rider.isValid()) {
                    rider.teleport(rider.getLocation().add(t.dir[0] * per, t.dir[1] * per, t.dir[2] * per));
                }
            }
            t.tickInStep++;
            if (t.tickInStep >= t.speed) {
                t.tickInStep = 0;
                t.stepIndex++;
                if (t.stepIndex >= t.steps) {
                    finish(t);
                }
            }
        }
    }

    private void finish(Trip t) {
        for (BlockDisplay d : t.displays) {
            if (d.isValid()) {
                d.remove();
            }
        }
        place(t);
        trips.remove(t.c.key());
        complete(t);
        saveJournal();
    }

    /** Writes the carried blocks at their destination; anything now occupying a destination is
     *  left alone and the block drops as an item. */
    private void place(Trip t) {
        for (int i = 0; i < t.cells.size(); i++) {
            int[] cell = t.cells.get(i);
            Block dest = t.world.getBlockAt(cell[0] + t.dir[0] * t.steps, cell[1] + t.dir[1] * t.steps, cell[2] + t.dir[2] * t.steps);
            if (dest.getType().isAir() || dest.isLiquid()) {
                dest.setBlockData(t.data.get(i), false);
            } else {
                t.world.dropItemNaturally(dest.getLocation().add(0.5, 0.5, 0.5), new ItemStack(t.data.get(i).getMaterial(), 1));
            }
        }
    }

    private void complete(Trip t) {
        t.c.memory = t.finalOffset;
        registry.markDirty();
        Block body = Geo.body(t.c);
        if (body != null) {
            t.world.playSound(body.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_PISTON_CONTRACT, 1.0f, 0.5f);
        }
    }

    /** Finishes every in-flight trip immediately (server shutdown or plugin disable). */
    void finishAll() {
        for (Trip t : new ArrayList<>(trips.values())) {
            finish(t);
        }
    }

    /** Called when a mover is removed mid-trip: complete the trip first so no block is lost. */
    void settle(Component c) {
        Trip t = trips.get(c.key());
        if (t != null) {
            finish(t);
        }
    }

    // ------------------------------------------------------------------ journal

    private File journalFile() {
        return new File(plugin.getDataFolder(), JOURNAL);
    }

    private void saveJournal() {
        File file = journalFile();
        if (trips.isEmpty()) {
            if (file.exists() && !file.delete()) {
                log.warning("nexusredstone: could not delete " + JOURNAL);
            }
            return;
        }
        FileConfiguration data = new YamlConfiguration();
        for (Trip t : trips.values()) {
            String base = "trips." + t.id;
            data.set(base + ".world", t.world.getName());
            data.set(base + ".component", t.c.key());
            data.set(base + ".offset", t.finalOffset);
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < t.cells.size(); i++) {
                int[] cell = t.cells.get(i);
                lines.add((cell[0] + t.dir[0] * t.steps) + "|" + (cell[1] + t.dir[1] * t.steps) + "|"
                        + (cell[2] + t.dir[2] * t.steps) + "|" + t.data.get(i).getAsString());
            }
            data.set(base + ".blocks", lines);
        }
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(plugin.getDataFolder(), JOURNAL + ".tmp");
            data.save(tmp);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            log.severe("nexusredstone: could not write the mover journal: " + e.getMessage());
        }
    }

    /** On enable: completes any trip a crash interrupted. Returns how many were recovered. */
    int recover() {
        File file = journalFile();
        if (!file.exists()) {
            return 0;
        }
        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = data.getConfigurationSection("trips");
        if (sec == null) {
            file.delete();
            return 0;
        }
        int recovered = 0;
        boolean allDone = true;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) {
                allDone = false;
                log.warning("nexusredstone: mover journal entry " + id + " waits for world " + s.getString("world"));
                continue;
            }
            for (String line : s.getStringList("blocks")) {
                String[] f = line.split("\\|", 4);
                if (f.length < 4) {
                    continue;
                }
                try {
                    Block b = w.getBlockAt(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
                    if (b.getType().isAir()) {
                        b.setBlockData(Bukkit.createBlockData(f[3]), false);
                    }
                } catch (IllegalArgumentException e) {
                    log.warning("nexusredstone: could not restore a mover block (" + line + "): " + e.getMessage());
                }
            }
            Component c = registry.get(s.getString("component", ""));
            if (c != null) {
                c.memory = s.getInt("offset", c.memory);
                registry.markDirty();
            }
            recovered++;
        }
        if (allDone && !file.delete()) {
            log.warning("nexusredstone: could not delete the recovered mover journal");
        }
        return recovered;
    }

    Map<String, Integer> status() {
        Map<String, Integer> out = new HashMap<>();
        for (Trip t : trips.values()) {
            out.put(t.c.key(), t.steps - t.stepIndex);
        }
        return out;
    }
}
