package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single place that creates, configures and destroys placed components, shared by the place
 * and break listeners, the wrench, the command, and blueprint pasting -- so the rules (level
 * gates, per-player limits, the empty-output-cell requirement, full cleanup on removal) live in
 * exactly one spot and cannot drift between callers.
 */
final class ComponentService {

    enum Result { OK, LEVEL, LIMIT, FRONT_BLOCKED, EXISTS }

    record Placement(Result result, Component component, String message) {
        boolean ok() {
            return result == Result.OK;
        }
    }

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final OutputCells cells;
    private final CircuitEngine engine;
    private final EngineerStore engineers;
    private final Announcer announcer;
    private final BukkitCircuitHost host;

    ComponentService(RedstoneConfig cfg, ComponentRegistry registry, OutputCells cells, CircuitEngine engine,
                     EngineerStore engineers, Announcer announcer, BukkitCircuitHost host) {
        this.cfg = cfg;
        this.registry = registry;
        this.cells = cells;
        this.engine = engine;
        this.engineers = engineers;
        this.announcer = announcer;
        this.host = host;
    }

    static boolean bypass(Player p) {
        return p.hasPermission("nexusredstone.bypass");
    }

    /** Validates and registers a component a player just placed at {@code body}. The caller has
     *  already let the vanilla block be placed; on any failure it cancels the placement. */
    Placement place(Player p, ComponentType type, Block body, Dir facing) {
        boolean bypass = bypass(p);
        int level = engineers.levelOf(p);
        if (!bypass && !EngineerSkill.canUse(type, level)) {
            return new Placement(Result.LEVEL, null, "§cThe " + type.display + " needs Engineer level " + type.minLevel
                    + " (you are level " + level + "). Build and run circuits to level up.");
        }
        if (!bypass && registry.countOwnedBy(p.getUniqueId()) >= EngineerSkill.maxComponents(level, cfg)) {
            return new Placement(Result.LIMIT, null, "§cYou already own " + EngineerSkill.maxComponents(level, cfg)
                    + " components, the most your level allows. Remove some, or level up for more.");
        }
        if (registry.at(body.getWorld().getName(), body.getX(), body.getY(), body.getZ()) != null) {
            return new Placement(Result.EXISTS, null, "§cThere is already a component there.");
        }
        Component c = new Component(body.getWorld().getName(), body.getX(), body.getY(), body.getZ(), type, p.getUniqueId(), facing);
        if (type.hasOutputCell) {
            Block front = Geo.front(c);
            if (front == null || front.getType() != Material.AIR) {
                return new Placement(Result.FRONT_BLOCKED, null, "§cThe " + type.display
                        + " needs an empty block in front of it for its output. Face it toward open space.");
            }
        }
        if (type == ComponentType.CLOCK) {
            int min = EngineerSkill.minClockPeriod(level, cfg);
            if (c.param("period") < min) {
                c.setParam("period", min);
            }
        }
        registry.add(c);
        registry.claimDerived(c);
        if (type.hasOutputCell && !cells.install(c)) {
            registry.remove(c.key());
            return new Placement(Result.FRONT_BLOCKED, null, "§cSomething is in the way of the output block.");
        }
        awardFirstPlacement(p, type);
        return new Placement(Result.OK, c, null);
    }

    private void awardFirstPlacement(Player p, ComponentType type) {
        boolean first = !engineers.hasPlacedAnything(p);
        if (!engineers.hasPlacedBefore(p, type)) {
            engineers.markPlaced(p, type);
            EngineerStore.XpResult r = engineers.addXp(p, Math.round(type.xp * cfg.xpPlaceScale));
            if (first) {
                announcer.say(p, Topic.FIRST_COMPONENT, null);
            }
            if (type.isHopper()) {
                announcer.say(p, Topic.HOPPER_UPGRADED, null);
            }
            if (r.leveledUp()) {
                announcer.say(p, Topic.LEVEL_UP, "Engineer level " + r.newLevel() + " (" + EngineerSkill.title(r.newLevel()) + ")");
            }
        }
    }

    /** Registers a component without any gating or messages (blueprint paste does its own checks). */
    Component registerRaw(UUID owner, ComponentType type, Block body, Dir facing) {
        Component c = new Component(body.getWorld().getName(), body.getX(), body.getY(), body.getZ(), type, owner, facing);
        if (!registry.add(c)) {
            return null;
        }
        registry.claimDerived(c);
        return c;
    }

    /** Completes a component registered by a blueprint paste by installing its output cell. */
    boolean finishRaw(Component c) {
        return !c.type.hasOutputCell || cells.install(c);
    }

    /** Tears a component all the way down. */
    void remove(Component c, boolean dropItem, boolean removeBody) {
        engine.forget(c);
        host.forget(c);
        if (c.type == ComponentType.MEGA_PISTON && c.extended) {
            Block body = Geo.body(c);
            if (body != null) {
                int[] v = Dir.vector(c.facing, c.param("axis"));
                for (int i = 1; i <= c.param("stroke"); i++) {
                    Block arm = body.getRelative(v[0] * i, v[1] * i, v[2] * i);
                    if (arm.getType() == MegaPistonService.ARM) {
                        arm.setType(Material.AIR, true);
                    }
                }
            }
        }
        if (c.type.hasOutputCell) {
            cells.remove(c);
        }
        for (String cellKey : registry.cellsOf(c.key())) {
            registry.releaseCell(cellKey);
        }
        registry.remove(c.key());
        Block body = Geo.body(c);
        if (body != null && removeBody && body.getType() != Material.AIR) {
            if (c.type == ComponentType.SORTER || c.type.family == Family.DISPENSER || c.type.family == Family.HOPPER) {
                Containers.clear(Containers.at(body));
            }
            body.setType(Material.AIR, true);
        }
        if (dropItem && body != null) {
            body.getWorld().dropItemNaturally(body.getLocation().add(0.5, 0.5, 0.5), itemsFor(c.type));
        }
    }

    private ComponentItems itemsRef;

    void setItems(ComponentItems items) {
        this.itemsRef = items;
    }

    private org.bukkit.inventory.ItemStack itemsFor(ComponentType type) {
        return itemsRef.create(type, 1);
    }

    /** Re-installs the output cell after a setting change that can switch digital/analog. */
    void refreshCell(Component c) {
        if (c.type.hasOutputCell) {
            cells.install(c);
        }
    }

    boolean canEdit(Player p, Component c) {
        return c.owner == null || c.owner.equals(p.getUniqueId()) || p.hasPermission("nexusredstone.admin");
    }

    /** Applies a setting with level-based limits. Returns a message for the player. */
    String applySetting(Player p, Component c, String name, int requested) {
        ParamSpec spec = c.type.spec(name);
        if (spec == null) {
            return "§c" + c.type.display + " has no setting '" + name + "'. Settings: " + settingNames(c.type);
        }
        int level = engineers.levelOf(p);
        boolean bypass = bypass(p);
        int value = spec.clamp(requested);
        String note = "";
        if (!bypass) {
            if (c.type == ComponentType.CLOCK && name.equals("period")) {
                int min = EngineerSkill.minClockPeriod(level, cfg);
                if (value < min) {
                    value = min;
                    note = " (your level's fastest clock is " + min + " ticks)";
                }
            }
            if (name.equals("tier") && (c.type == ComponentType.WIRELESS_TX || c.type == ComponentType.WIRELESS_RX)) {
                int max = EngineerSkill.maxWirelessTier(level, cfg);
                if (value > max) {
                    value = max;
                    note = " (your level allows wireless tier " + max + ")";
                }
            }
            if (c.type == ComponentType.MEGA_PISTON && name.equals("limit")) {
                int max = EngineerSkill.megaPistonLimit(level, cfg);
                if (value > max) {
                    value = max;
                    note = " (your level pushes up to " + max + " blocks)";
                }
            }
            if (c.type == ComponentType.BLOCK_MOVER && name.equals("distance") && value > cfg.moverMaxDistance) {
                value = cfg.moverMaxDistance;
                note = " (server maximum)";
            }
        }
        if (c.type == ComponentType.MEGA_PISTON && c.extended && (name.equals("stroke") || name.equals("axis"))) {
            return "§cRetract the mega piston (cut its power) before changing its " + name + ".";
        }
        if ((c.type == ComponentType.BLOCK_MOVER && name.equals("axis")) && c.memory != 0) {
            return "§cReturn the mover to its start before changing its axis.";
        }
        boolean wasAnalog = c.analogOutput();
        c.setParam(name, value);
        c.resetRuntime();
        if (wasAnalog != c.analogOutput()) {
            c.output = 0;
            refreshCell(c);
        }
        registry.markDirty();
        return "§a" + c.type.display + " " + name + " = " + c.param(name) + note;
    }

    static String settingNames(ComponentType type) {
        StringBuilder sb = new StringBuilder();
        for (ParamSpec s : type.specs()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.name());
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    /** Human-readable summary of a component, for the wrench and /redstone info. */
    List<String> describe(Component c, long nowTick) {
        List<String> out = new ArrayList<>();
        out.add("§e" + c.type.display + " §7(" + c.type.family.label + ") §8at " + c.x + ", " + c.y + ", " + c.z);
        String ownerName = c.owner == null ? "nobody" : Bukkit.getOfflinePlayer(c.owner).getName();
        out.add("§7Owner: §f" + (ownerName == null ? c.owner.toString().substring(0, 8) : ownerName)
                + "  §7Facing: §f" + c.facing);
        if (c.type.hasOutputCell) {
            out.add("§7Output: §f" + c.output + "/15 §7(" + (c.analogOutput() ? "analog cell, read it with a comparator" : "on/off cell") + ")");
        }
        if (!c.type.specs().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : c.params.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("§7, ");
                }
                sb.append("§f").append(e.getKey()).append("§7=§b").append(e.getValue());
            }
            out.add("§7Settings: " + sb);
        }
        if (c.type.usesText()) {
            String label = c.type == ComponentType.PLC ? "Program" : (c.type == ComponentType.SENSOR_ITEM ? "Item filter" : "Channel");
            out.add("§7" + label + ": §f" + (c.text.isEmpty() ? "(none)" : c.text));
        }
        if (c.type.hasFilter()) {
            out.add("§7Filter (" + (c.param("mode") == ItemFilter.BLACKLIST ? "blacklist" : "whitelist") + ", "
                    + c.filter.size() + "/" + c.type.filterSlots() + "): §f" + (c.filter.isEmpty() ? "(empty, passes everything)" : String.join(", ", c.filter)));
        }
        switch (c.type) {
            case COUNTER:
                out.add("§7Count: §f" + c.counter + "/" + c.param("target"));
                break;
            case SR_LATCH:
            case T_FLIPFLOP:
                out.add("§7Stored state: §f" + (c.state ? "ON" : "OFF"));
                break;
            case PLC:
                out.add("§7Memory register: §f" + c.memory);
                if (c.compileError != null) {
                    out.add("§cProgram error: " + c.compileError);
                }
                break;
            case MEGA_PISTON:
                out.add("§7State: §f" + (c.extended ? "extended" : "retracted"));
                break;
            case BLOCK_MOVER:
                out.add("§7Offset from start: §f" + c.memory + " blocks");
                break;
            default:
                break;
        }
        if (c.lagTrippedUntil > nowTick) {
            out.add("§c§lShut off by the lag guard §cfor " + Math.max(1, (c.lagTrippedUntil - nowTick) / 20) + " more seconds.");
        }
        return out;
    }

    /** Players who are allowed to see staff-level lag information. */
    static List<Player> staff() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("nexusredstone.admin")) {
                list.add(p);
            }
        }
        return list;
    }

    static boolean survival(Player p) {
        return p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR;
    }

    static Location centre(Block b) {
        return b.getLocation().add(0.5, 0.5, 0.5);
    }
}
