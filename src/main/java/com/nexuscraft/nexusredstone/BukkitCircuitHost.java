package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The real-world half of the circuit engine's host interface: reads redstone power and entities
 * from Bukkit, writes output cells, and turns engine events into player-facing messages and XP.
 *
 * <p>Every method that touches a block first requires the component's chunk <em>and the chunks
 * on each side of it</em> to be loaded ({@link #isActive}), because a component on a chunk edge
 * reads and writes blocks one step over -- and asking Bukkit for a block in an unloaded chunk
 * silently loads it, which on a busy server would let a single gate quietly keep chunks alive.
 */
final class BukkitCircuitHost implements CircuitHost {

    private final RedstoneConfig cfg;
    private final OutputCells cells;
    private final EngineerStore engineers;
    private final Announcer announcer;
    private final Logger log;

    private final Set<String> announced = new HashSet<>();
    private final Map<UUID, Set<String>> activeThisInterval = new HashMap<>();

    BukkitCircuitHost(RedstoneConfig cfg, OutputCells cells, EngineerStore engineers, Announcer announcer, Logger log) {
        this.cfg = cfg;
        this.cells = cells;
        this.engineers = engineers;
        this.announcer = announcer;
        this.log = log;
    }

    static boolean chunksLoaded(World w, int x, int z) {
        return w.isChunkLoaded((x - 1) >> 4, (z - 1) >> 4) && w.isChunkLoaded((x + 1) >> 4, (z + 1) >> 4)
                && w.isChunkLoaded((x - 1) >> 4, (z + 1) >> 4) && w.isChunkLoaded((x + 1) >> 4, (z - 1) >> 4);
    }

    private java.util.function.Consumer<Component> orphanHandler;

    /** Installed by the plugin once the component service exists. */
    void setOrphanHandler(java.util.function.Consumer<Component> handler) {
        this.orphanHandler = handler;
    }

    @Override
    public boolean isActive(Component c) {
        World w = Geo.world(c.world);
        if (w == null) {
            return false;
        }
        if (cfg.requireLoadedChunk && !chunksLoaded(w, c.x, c.z)) {
            return false;
        }
        if (!w.isChunkLoaded(c.x >> 4, c.z >> 4)) {
            return false;
        }
        // A component whose body block was removed by something else (a world editor, a mod,
        // /setblock) must not keep running as a ghost that still drives its output cell.
        if (orphanHandler != null && !w.getBlockAt(c.x, c.y, c.z).getType().name().equals(c.type.bodyMaterial())) {
            log.info("nexusredstone: removing orphaned " + c + " (its block is gone)");
            if (orphanHandler != null) {
                orphanHandler.accept(c);
            }
            return false;
        }
        return true;
    }

    @Override
    public Inputs readInputs(Component c) {
        Block body = Geo.body(c);
        if (body == null) {
            return Inputs.NONE;
        }
        Dir f = c.facing;
        return new Inputs(
                body.getBlockPower(Geo.face(f.left())),
                body.getBlockPower(Geo.face(f.right())),
                body.getBlockPower(Geo.face(f.back())),
                0);
    }

    @Override
    public int readSensor(Component c) {
        Block body = Geo.body(c);
        if (body == null) {
            return 0;
        }
        World w = body.getWorld();
        Location center = body.getLocation().add(0.5, 0.5, 0.5);
        switch (c.type) {
            case SENSOR_PLAYER: {
                double r = Math.min(c.param("radius"), cfg.sensorMaxRadius);
                double r2 = r * r;
                int n = 0;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld() != null && p.getWorld().getName().equals(c.world) && p.getGameMode() != GameMode.SPECTATOR
                            && p.getLocation().distanceSquared(center) <= r2) {
                        n++;
                    }
                }
                return n;
            }
            case SENSOR_MOB: {
                double r = Math.min(c.param("radius"), cfg.sensorMaxRadius);
                boolean hostileOnly = c.param("hostile") == 1;
                int n = 0;
                for (Entity e : w.getNearbyEntities(center, r, r, r)) {
                    if (e instanceof Player || !(e instanceof Mob)) {
                        continue;
                    }
                    if (hostileOnly && !(e instanceof Monster)) {
                        continue;
                    }
                    if (e.getLocation().distanceSquared(center) <= r * r) {
                        n++;
                    }
                }
                return n;
            }
            case SENSOR_ITEM: {
                double r = Math.min(c.param("radius"), cfg.sensorMaxRadius);
                int n = 0;
                for (Entity e : w.getNearbyEntities(center, r, r, r)) {
                    if (e instanceof Item && e.getLocation().distanceSquared(center) <= r * r) {
                        Item item = (Item) e;
                        if (c.text.isEmpty() || ItemFilter.entryMatches(c.text, item.getItemStack().getType().name())) {
                            n++;
                        }
                    }
                }
                return n;
            }
            case SENSOR_LIGHT: {
                int best = 0;
                for (BlockFace face : BlockFace.values()) {
                    best = Math.max(best, body.getRelative(face).getLightLevel());
                }
                return best;
            }
            case SENSOR_WEATHER:
                return w.isThundering() ? SensorLogic.WEATHER_THUNDER : (w.hasStorm() ? SensorLogic.WEATHER_RAIN : SensorLogic.WEATHER_CLEAR);
            case SENSOR_TIME:
                return (int) (w.getTime() % 24000L);
            case SENSOR_FILL: {
                Inventory inv = Containers.at(body.getRelative(Geo.face(c.facing.back())));
                return inv == null ? 0 : Containers.fillPermille(inv);
            }
            default:
                return 0;
        }
    }

    @Override
    public void emit(Component c, int level) {
        if (c.type.hasOutputCell) {
            cells.drive(c, level);
        }
    }

    @Override
    public int ownerLevel(Component c) {
        return engineers.levelOfOwner(c.owner);
    }

    @Override
    public void event(Component c, CircuitEvent event, String detail) {
        switch (event) {
            case OUTPUT_CHANGED:
                if (c.owner != null) {
                    activeThisInterval.computeIfAbsent(c.owner, k -> new HashSet<>()).add(c.key());
                }
                ambient(c);
                break;
            case TRIPPED:
                log.info("nexusredstone: lag guard shut off " + c + " (owner " + c.owner + "): " + detail);
                announcer.tell(c.owner, "§c§l⚠ §cYour " + c.type.display + " at " + c.x + ", " + c.y + ", " + c.z
                        + " was shut off for " + cfg.lagTripCooldownSeconds + "s: " + detail + ".");
                announcer.sayAt(c.owner, Topic.LAG_TRIP, Geo.body(c));
                break;
            case RESUMED:
                announcer.tell(c.owner, "§a" + c.type.display + " at " + c.x + ", " + c.y + ", " + c.z + " is back online.");
                break;
            case PLC_ERROR:
                announcer.tell(c.owner, "§c§l✖ §cProgram error in your logic block at " + c.x + ", " + c.y + ", " + c.z + ": " + detail);
                break;
            default:
                break;
        }
    }

    /** One-off "this machine came alive" flourishes. Each fires at most once per component per
     *  server run, on top of the announcer's own per-owner rate limit. */
    private void ambient(Component c) {
        Topic topic = null;
        boolean oncePerComponent = true;
        switch (c.type) {
            case CLOCK:
                topic = Topic.CLOCK_ALIVE;
                break;
            case SR_LATCH:
            case T_FLIPFLOP:
                topic = Topic.LATCH_FLIPPED;
                break;
            case COUNTER:
                if (c.output > 0) {
                    topic = Topic.COUNTER_FIRED;
                    oncePerComponent = false;
                }
                break;
            case WIRELESS_RX:
                if (c.output > 0) {
                    topic = Topic.WIRELESS_LINKED;
                }
                break;
            case PLC:
                if (c.output > 0) {
                    topic = Topic.PLC_RUNNING;
                }
                break;
            default:
                if (c.type.isSensor() && c.output > 0) {
                    topic = Topic.SENSOR_TRIGGERED;
                }
                break;
        }
        if (topic == null) {
            return;
        }
        if (oncePerComponent && !announced.add(c.key() + "|" + topic.key)) {
            return;
        }
        announcer.sayAt(c.owner, topic, Geo.body(c));
    }

    /** Called on the activity interval: owners whose machines have been genuinely running earn a
     *  little XP, capped per interval so leaving a clock on can never out-earn building. */
    void settleActivity() {
        for (Map.Entry<UUID, Set<String>> e : activeThisInterval.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                continue;
            }
            int xp = Math.min(cfg.activityXpCapPerInterval, cfg.activityXpPerInterval * e.getValue().size());
            if (xp > 0) {
                EngineerStore.XpResult r = engineers.addXp(p, xp);
                if (r.leveledUp()) {
                    announcer.say(p, Topic.LEVEL_UP, "Engineer level " + r.newLevel() + " (" + EngineerSkill.title(r.newLevel()) + ")");
                }
            }
        }
        activeThisInterval.clear();
    }

    /** Counts a non-circuit machine (a piston stroke, a mover trip) as active this interval. */
    void noteActivity(Component c) {
        if (c.owner != null) {
            activeThisInterval.computeIfAbsent(c.owner, k -> new HashSet<>()).add(c.key());
        }
    }

    void forget(Component c) {
        announced.removeIf(k -> k.startsWith(c.key() + "|"));
    }
}
