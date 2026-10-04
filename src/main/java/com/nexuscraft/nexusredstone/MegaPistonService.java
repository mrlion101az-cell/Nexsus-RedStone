package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives every Mega Piston. Vanilla pistons cannot be extended past 12 blocks, and an over-limit
 * push fires no event at all, so a longer-reach piston has to be its own component with its own
 * push logic rather than a hook on the vanilla one. Each simulation step the service polls the
 * body block's redstone power; a rising edge extends (plan the push with {@link LinearPushPlanner},
 * fire a synthetic {@code BlockPistonExtendEvent} so claim plugins can veto it, then move the
 * blocks and grow an arm of {@link #ARM} blocks), and a falling edge retracts (and, if sticky,
 * pulls the line back).
 */
final class MegaPistonService {

    /** The visible piston arm while extended. Claimed in the registry, so nothing can break it. */
    static final Material ARM = Material.END_ROD;

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final EngineerStore engineers;
    private final Announcer announcer;
    private final BukkitCircuitHost host;
    private boolean firing;

    MegaPistonService(RedstoneConfig cfg, ComponentRegistry registry, EngineerStore engineers, Announcer announcer,
                      BukkitCircuitHost host) {
        this.cfg = cfg;
        this.registry = registry;
        this.engineers = engineers;
        this.announcer = announcer;
        this.host = host;
    }

    /** True while this service is firing its own synthetic piston event, so the plugin's own
     *  piston listener does not treat it as a vanilla piston pushing a protected block. */
    boolean isFiring() {
        return firing;
    }

    void poll(long now) {
        for (Component c : registry.ofFamily(Family.PISTON)) {
            if (c.type != ComponentType.MEGA_PISTON || !host.isActive(c)) {
                continue;
            }
            Block body = Geo.body(c);
            if (body == null) {
                continue;
            }
            boolean powered = body.isBlockIndirectlyPowered();
            if (now < c.cooldownUntil) {
                continue;
            }
            if (powered && !c.extended) {
                extend(c, body, now);
            } else if (!powered && c.extended) {
                retract(c, body, now);
            }
        }
    }

    private int effectiveLimit(Component c) {
        int level = engineers.levelOfOwner(c.owner);
        return Math.min(c.param("limit"), EngineerSkill.megaPistonLimit(level, cfg));
    }

    private static BlockFace faceOf(int[] v) {
        if (v[1] > 0) {
            return BlockFace.UP;
        }
        if (v[1] < 0) {
            return BlockFace.DOWN;
        }
        return Geo.face(v[0] == 0 && v[2] == 0 ? Dir.NORTH
                : v[0] > 0 ? Dir.EAST : v[0] < 0 ? Dir.WEST : v[2] > 0 ? Dir.SOUTH : Dir.NORTH);
    }

    private void extend(Component c, Block body, long now) {
        c.cooldownUntil = now + cfg.megaCooldownTicks;
        World w = body.getWorld();
        int[] v = Dir.vector(c.facing, c.param("axis"));
        int stroke = c.param("stroke");
        WorldBlockView view = new WorldBlockView(w, registry);
        LinearPushPlanner.Plan plan = LinearPushPlanner.push(view, c.x + v[0], c.y + v[1], c.z + v[2],
                v[0], v[1], v[2], effectiveLimit(c), stroke);
        if (!plan.ok()) {
            failure(c, body, plan.reason());
            return;
        }
        List<Block> moved = new ArrayList<>();
        for (LinearPushPlanner.Move m : plan.moves()) {
            moved.add(w.getBlockAt(m.fx(), m.fy(), m.fz()));
        }
        if (!clearedByOthers(body, moved, faceOf(v), true)) {
            failure(c, body, "a protection plugin refused the push");
            return;
        }
        applyMoves(w, plan);
        for (int i = 1; i <= stroke; i++) {
            Block arm = body.getRelative(v[0] * i, v[1] * i, v[2] * i);
            arm.setType(ARM, false);
        }
        c.extended = true;
        registry.claimDerived(c);
        registry.markDirty();
        w.playSound(body.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_PISTON_EXTEND, 1.0f, 0.7f);
        host.noteActivity(c);
        announcer.sayAt(c.owner, Topic.PISTON_FIRED, body);
    }

    private void retract(Component c, Block body, long now) {
        c.cooldownUntil = now + cfg.megaCooldownTicks;
        World w = body.getWorld();
        int[] v = Dir.vector(c.facing, c.param("axis"));
        int stroke = c.param("stroke");
        for (String key : ComponentRegistry.armCells(c)) {
            registry.releaseCell(key);
        }
        for (int i = 1; i <= stroke; i++) {
            Block arm = body.getRelative(v[0] * i, v[1] * i, v[2] * i);
            if (arm.getType() == ARM) {
                arm.setType(Material.AIR, false);
            }
        }
        c.extended = false;
        registry.markDirty();
        if (c.param("sticky") == 1) {
            WorldBlockView view = new WorldBlockView(w, registry);
            LinearPushPlanner.Plan plan = LinearPushPlanner.pull(view, c.x + v[0], c.y + v[1], c.z + v[2],
                    v[0], v[1], v[2], effectiveLimit(c), stroke);
            if (plan.ok()) {
                List<Block> moved = new ArrayList<>();
                for (LinearPushPlanner.Move m : plan.moves()) {
                    moved.add(w.getBlockAt(m.fx(), m.fy(), m.fz()));
                }
                if (clearedByOthers(body, moved, faceOf(new int[] {-v[0], -v[1], -v[2]}), false)) {
                    applyMoves(w, plan);
                }
            } else {
                announcer.tell(c.owner, "§7The mega piston at " + c.x + ", " + c.y + ", " + c.z
                        + " retracted without pulling: " + plan.reason() + ".");
            }
        }
        w.playSound(body.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_PISTON_CONTRACT, 1.0f, 0.7f);
        host.noteActivity(c);
    }

    private void failure(Component c, Block body, String reason) {
        body.getWorld().playSound(body.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_COMPARATOR_CLICK, 1.0f, 0.5f);
        announcer.tell(c.owner, "§c§l✖ §cThe mega piston at " + c.x + ", " + c.y + ", " + c.z + " could not push: " + reason + ".");
        // Stays retracted; the cooldown stops it re-trying every step while still powered.
    }

    /** Fires the synthetic vanilla piston event for claim/protection plugins. Returns false if
     *  anything cancelled it. */
    private boolean clearedByOthers(Block body, List<Block> moved, BlockFace direction, boolean extending) {
        firing = true;
        try {
            if (extending) {
                BlockPistonExtendEvent e = new BlockPistonExtendEvent(body, moved, direction);
                Bukkit.getPluginManager().callEvent(e);
                return !e.isCancelled();
            }
            BlockPistonRetractEvent e = new BlockPistonRetractEvent(body, moved, direction);
            Bukkit.getPluginManager().callEvent(e);
            return !e.isCancelled();
        } finally {
            firing = false;
        }
    }

    /** Applies a plan: breaks first, then lifts every moving block's data, clears all the
     *  sources, and only then writes the destinations, so overlapping moves never clobber. */
    private static void applyMoves(World w, LinearPushPlanner.Plan plan) {
        for (int[] b : plan.breaks()) {
            Block block = w.getBlockAt(b[0], b[1], b[2]);
            if (!block.getType().isAir()) {
                block.breakNaturally();
            }
        }
        List<BlockData> data = new ArrayList<>();
        for (LinearPushPlanner.Move m : plan.moves()) {
            data.add(w.getBlockAt(m.fx(), m.fy(), m.fz()).getBlockData());
        }
        for (LinearPushPlanner.Move m : plan.moves()) {
            w.getBlockAt(m.fx(), m.fy(), m.fz()).setType(Material.AIR, false);
        }
        for (int i = 0; i < plan.moves().size(); i++) {
            LinearPushPlanner.Move m = plan.moves().get(i);
            w.getBlockAt(m.tx(), m.ty(), m.tz()).setBlockData(data.get(i), false);
        }
    }
}
