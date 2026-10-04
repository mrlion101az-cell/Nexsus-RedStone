package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Circuit blueprints: capture a region (blocks and every component with its settings) onto a
 * paper item, and build it again anywhere, paying in real materials.
 *
 * <p>Pasting is deliberately two clicks: the first shows the outline and the bill, the second (at
 * the same spot, within 15 seconds) builds. Before building, the whole job is validated -- the
 * owner's Engineer level unlocks every component in it, the region is clear, nothing in the
 * region belongs to someone else's claim, and the player actually holds the materials -- so a
 * paste either happens completely or not at all. It never overwrites an existing block.
 */
final class BlueprintService {

    private static volatile boolean probing;

    /** True while this service is firing a claim-check probe event; the plugin's own break
     *  handler must ignore those. */
    static boolean isProbing() {
        return probing;
    }

    private static final long CONFIRM_MILLIS = 15_000;
    private static final int MAX_DIMENSION = 64;
    private static final int MAX_VOLUME = 100_000;

    record Corner(String world, int x, int y, int z) {
    }

    private record Pending(String world, int ox, int oy, int oz, String encoded, long expires) {
    }

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final ComponentService service;
    private final ComponentItems items;
    private final EngineerStore engineers;
    private final Announcer announcer;
    private final Map<UUID, Corner[]> corners = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();

    BlueprintService(RedstoneConfig cfg, ComponentRegistry registry, ComponentService service, ComponentItems items,
                     EngineerStore engineers, Announcer announcer) {
        this.cfg = cfg;
        this.registry = registry;
        this.service = service;
        this.items = items;
        this.engineers = engineers;
        this.announcer = announcer;
    }

    // ------------------------------------------------------------------ corners

    void setCorner(Player p, int index, Block b) {
        Corner[] c = corners.computeIfAbsent(p.getUniqueId(), k -> new Corner[2]);
        c[index] = new Corner(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        p.sendMessage("§bCorner " + (index + 1) + " set at " + b.getX() + ", " + b.getY() + ", " + b.getZ() + "."
                + (c[0] != null && c[1] != null ? " §7" + describeBox(c[0], c[1]) + " Now /redstone blueprint save <name>." : ""));
    }

    void clearCorners(Player p) {
        corners.remove(p.getUniqueId());
        pending.remove(p.getUniqueId());
    }

    void forget(UUID id) {
        corners.remove(id);
        pending.remove(id);
    }

    private static String describeBox(Corner a, Corner b) {
        return (Math.abs(a.x() - b.x()) + 1) + " x " + (Math.abs(a.y() - b.y()) + 1) + " x " + (Math.abs(a.z() - b.z()) + 1) + " region.";
    }

    // ------------------------------------------------------------------ capture

    /** Captures the selected region onto a blueprint item. Returns a message for the player. */
    String capture(Player p, String name) {
        Corner[] c = corners.get(p.getUniqueId());
        if (c == null || c[0] == null || c[1] == null) {
            return "§cSelect two corners first with the Blueprint Wand (left-click one, right-click the other).";
        }
        if (!c[0].world().equals(c[1].world())) {
            return "§cBoth corners must be in the same world.";
        }
        World w = Bukkit.getWorld(c[0].world());
        if (w == null) {
            return "§cThat world is not loaded.";
        }
        int minX = Math.min(c[0].x(), c[1].x());
        int minY = Math.min(c[0].y(), c[1].y());
        int minZ = Math.min(c[0].z(), c[1].z());
        int sx = Math.abs(c[0].x() - c[1].x()) + 1;
        int sy = Math.abs(c[0].y() - c[1].y()) + 1;
        int sz = Math.abs(c[0].z() - c[1].z()) + 1;
        if (sx > MAX_DIMENSION || sy > MAX_DIMENSION || sz > MAX_DIMENSION || (long) sx * sy * sz > MAX_VOLUME) {
            return "§cThat region is too big (at most " + MAX_DIMENSION + " blocks on a side, " + MAX_VOLUME + " blocks in all).";
        }
        int level = engineers.levelOf(p);
        int limit = ComponentService.bypass(p) ? cfg.blueprintBlocksCeiling : EngineerSkill.blueprintBlocks(level, cfg);

        Blueprint bp = new Blueprint();
        bp.name = cleanName(name);
        bp.sizeX = sx;
        bp.sizeY = sy;
        bp.sizeZ = sz;
        int skipped = 0;
        for (int dx = 0; dx < sx; dx++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dz = 0; dz < sz; dz++) {
                    Block b = w.getBlockAt(minX + dx, minY + dy, minZ + dz);
                    if (b.getType().isAir()) {
                        continue;
                    }
                    String key = Geo.key(b);
                    Component comp = registry.resolve(key);
                    if (comp != null) {
                        if (!key.equals(comp.key())) {
                            continue; // an output cell or piston arm: rebuilt by its component
                        }
                        if (comp.type.keepsVanillaBlock()) {
                            bp.blocks.add(new Blueprint.Block(dx, dy, dz, b.getBlockData().getAsString()));
                        }
                        bp.components.add(new Blueprint.Comp(dx, dy, dz, comp.type.id, comp.facing.name(),
                                new java.util.LinkedHashMap<>(comp.params), comp.text, new ArrayList<>(comp.filter)));
                        continue;
                    }
                    if (b.isLiquid() || BlueprintCost.itemFor(BlueprintCost.materialOf(b.getBlockData().getAsString()), BlueprintService::hasItemForm) == null) {
                        skipped++;
                        continue;
                    }
                    bp.blocks.add(new Blueprint.Block(dx, dy, dz, b.getBlockData().getAsString()));
                    if (bp.totalBlocks() > limit) {
                        return "§cThat region has more than " + limit + " blocks, the most your Engineer level can capture. Level up for more.";
                    }
                }
            }
        }
        if (bp.totalBlocks() == 0) {
            return "§cThat region is empty.";
        }
        if (bp.totalBlocks() > limit) {
            return "§cThat region has more than " + limit + " blocks, the most your Engineer level can capture.";
        }
        String encoded = BlueprintCodec.encode(bp);
        if (encoded.length() > cfg.blueprintMaxEncodedChars) {
            return "§cThat blueprint is too large to store on an item (" + encoded.length() + " characters).";
        }
        ItemStack item = items.blueprint(encoded, bp.name, bp.blocks.size(), bp.components.size(), sx, sy, sz);
        giveOrDrop(p, item);
        announcer.say(p, Topic.BLUEPRINT_SAVED, "(" + bp.totalBlocks() + " blocks" + (skipped > 0 ? ", " + skipped + " unbuildable skipped" : "") + ")");
        return "§aBlueprint '" + bp.name + "' saved: " + bp.blocks.size() + " blocks, " + bp.components.size() + " components.";
    }

    private static boolean hasItemForm(String name) {
        try {
            return Material.valueOf(name).isItem();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (n.isEmpty()) {
            n = "circuit";
        }
        return n.length() > 24 ? n.substring(0, 24) : n;
    }

    private static void giveOrDrop(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------------ paste

    /** Right-click with a blueprint: first click previews, second click at the same spot builds. */
    void use(Player p, Block clicked, BlockFace face, ItemStack item) {
        String encoded = items.blueprintOf(item);
        if (encoded == null) {
            return;
        }
        int level = engineers.levelOf(p);
        int limit = ComponentService.bypass(p) ? cfg.blueprintBlocksCeiling : EngineerSkill.blueprintBlocks(level, cfg);
        BlueprintCodec.Decoded decoded = BlueprintCodec.decode(encoded, Math.max(limit, cfg.blueprintBlocksCeiling));
        if (!decoded.ok()) {
            p.sendMessage("§cThis blueprint cannot be read: " + decoded.error());
            return;
        }
        Blueprint bp = decoded.blueprint();
        Block origin = clicked.getRelative(face == null ? BlockFace.UP : face);
        long now = System.currentTimeMillis();
        Pending prev = pending.get(p.getUniqueId());
        boolean confirm = prev != null && prev.expires() > now && prev.encoded().equals(encoded)
                && prev.world().equals(origin.getWorld().getName())
                && prev.ox() == origin.getX() && prev.oy() == origin.getY() && prev.oz() == origin.getZ();

        Plan plan = plan(p, bp, origin, level);
        if (plan.error != null) {
            pending.remove(p.getUniqueId());
            p.sendMessage(plan.error);
            return;
        }
        if (!confirm) {
            pending.put(p.getUniqueId(), new Pending(origin.getWorld().getName(), origin.getX(), origin.getY(), origin.getZ(), encoded, now + CONFIRM_MILLIS));
            outline(p, origin, bp);
            p.sendMessage("§bBlueprint '" + bp.name + "' §7(" + bp.sizeX + " x " + bp.sizeY + " x " + bp.sizeZ + ")§b needs: §f" + BlueprintCost.describe(plan.cost.items())
                    + (plan.free ? " §7(free for you)" : ""));
            p.sendMessage("§7Right-click the same spot again within 15 seconds to build it.");
            return;
        }
        pending.remove(p.getUniqueId());
        build(p, bp, origin, plan);
    }

    private static final class Plan {
        String error;
        BlueprintCost.Cost cost;
        boolean free;
    }

    private boolean obstructed(Block b) {
        return !(b.getType().isAir() || b.isLiquid());
    }

    private Plan plan(Player p, Blueprint bp, Block origin, int level) {
        Plan plan = new Plan();
        plan.free = !ComponentService.survival(p) || ComponentService.bypass(p);
        boolean bypass = ComponentService.bypass(p);
        // 1. components must be unlocked and fit the owner's cap
        Set<String> locked = new HashSet<>();
        for (Blueprint.Comp c : bp.components) {
            ComponentType t = ComponentType.byId(c.type());
            if (t == null) {
                plan.error = "§cThis blueprint uses an unknown component type '" + c.type() + "'.";
                return plan;
            }
            if (!bypass && !EngineerSkill.canUse(t, level)) {
                locked.add(t.display + " (level " + t.minLevel + ")");
            }
        }
        if (!locked.isEmpty()) {
            plan.error = "§cYour Engineer level (" + level + ") cannot build: " + String.join(", ", locked) + ".";
            return plan;
        }
        if (!bypass && registry.countOwnedBy(p.getUniqueId()) + bp.components.size() > EngineerSkill.maxComponents(level, cfg)) {
            plan.error = "§cBuilding this would put you over your component limit of " + EngineerSkill.maxComponents(level, cfg) + ".";
            return plan;
        }
        if (!bypass && bp.totalBlocks() > EngineerSkill.blueprintBlocks(level, cfg)) {
            plan.error = "§cThis blueprint is larger than your Engineer level can build (" + EngineerSkill.blueprintBlocks(level, cfg) + " blocks).";
            return plan;
        }
        // 2. cost and unbuildable blocks
        plan.cost = BlueprintCost.cost(bp, BlueprintService::hasItemForm);
        if (!plan.cost.unbuildable().isEmpty()) {
            plan.error = "§cThis blueprint contains blocks that cannot be built: " + String.join(", ", plan.cost.unbuildable()) + ".";
            return plan;
        }
        // 3. the region must be clear (blocks, component bodies, and each output cell)
        World w = origin.getWorld();
        List<String> obstructions = new ArrayList<>();
        for (Blueprint.Block b : bp.blocks) {
            Block t = w.getBlockAt(origin.getX() + b.dx(), origin.getY() + b.dy(), origin.getZ() + b.dz());
            if (obstructed(t)) {
                obstructions.add(t.getX() + "," + t.getY() + "," + t.getZ());
            }
        }
        Set<String> compOffsets = new HashSet<>();
        for (Blueprint.Block b : bp.blocks) {
            compOffsets.add(b.dx() + "," + b.dy() + "," + b.dz());
        }
        for (Blueprint.Comp c : bp.components) {
            ComponentType t = ComponentType.byId(c.type());
            Block body = w.getBlockAt(origin.getX() + c.dx(), origin.getY() + c.dy(), origin.getZ() + c.dz());
            if (!compOffsets.contains(c.dx() + "," + c.dy() + "," + c.dz()) && obstructed(body)) {
                obstructions.add(body.getX() + "," + body.getY() + "," + body.getZ());
            }
            if (t.hasOutputCell) {
                Dir facing = Dir.parse(c.facing(), Dir.NORTH);
                Block cell = body.getRelative(Geo.face(facing));
                if (obstructed(cell) && !blueprintHolds(bp, c.dx() + facing.dx, c.dy(), c.dz() + facing.dz)) {
                    obstructions.add(cell.getX() + "," + cell.getY() + "," + cell.getZ());
                }
            }
        }
        if (!obstructions.isEmpty()) {
            plan.error = "§cThe space is not clear (" + obstructions.size() + " blocks in the way, first at "
                    + obstructions.get(0) + "). Blueprints never overwrite anything.";
            return plan;
        }
        // 4. claims
        if (cfg.blueprintClaimProbe && !claimsAllow(p, bp, origin)) {
            plan.error = "§cYou are not allowed to build in part of that area.";
            return plan;
        }
        // 5. materials
        if (!plan.free) {
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, Integer> e : plan.cost.items().entrySet()) {
                int have = count(p, e.getKey());
                if (have < e.getValue()) {
                    missing.add((e.getValue() - have) + "x " + BlueprintCost.describe(Map.of(e.getKey(), 1)).substring(3));
                }
            }
            if (!missing.isEmpty()) {
                plan.error = "§cYou are missing: " + String.join(", ", missing) + ".";
            }
        }
        return plan;
    }

    private static boolean blueprintHolds(Blueprint bp, int dx, int dy, int dz) {
        for (Blueprint.Block b : bp.blocks) {
            if (b.dx() == dx && b.dy() == dy && b.dz() == dz) {
                return true;
            }
        }
        return false;
    }

    private boolean claimsAllow(Player p, Blueprint bp, Block origin) {
        World w = origin.getWorld();
        Set<String> seen = new HashSet<>();
        probing = true;
        try {
            for (int dx = 0; dx < bp.sizeX; dx += 4) {
                for (int dz = 0; dz < bp.sizeZ; dz += 4) {
                    for (int dy = 0; dy < bp.sizeY; dy += 8) {
                        Block t = w.getBlockAt(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                        if (!seen.add(t.getX() + "," + t.getY() + "," + t.getZ())) {
                            continue;
                        }
                        BlockBreakEvent probe = new BlockBreakEvent(t, p);
                        Bukkit.getPluginManager().callEvent(probe);
                        if (probe.isCancelled()) {
                            return false;
                        }
                    }
                }
            }
        } finally {
            probing = false;
        }
        return true;
    }

    // ------------------------------------------------------------------ inventory accounting

    private int count(Player p, String key) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) {
            if (matches(s, key)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    private boolean matches(ItemStack s, String key) {
        if (s == null || s.getAmount() <= 0) {
            return false;
        }
        if (key.startsWith(BlueprintCost.COMPONENT_PREFIX)) {
            ComponentType t = items.typeOf(s);
            return t != null && t.id.equals(key.substring(BlueprintCost.COMPONENT_PREFIX.length()));
        }
        return s.getType().name().equals(key) && !s.hasItemMeta();
    }

    private void consume(Player p, String key, int amount) {
        int remaining = amount;
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack s = contents[i];
            if (!matches(s, key)) {
                continue;
            }
            int take = Math.min(remaining, s.getAmount());
            if (take == s.getAmount()) {
                inv.setItem(i, null);
            } else {
                s.setAmount(s.getAmount() - take);
                inv.setItem(i, s);
            }
            remaining -= take;
        }
    }

    // ------------------------------------------------------------------ building

    private void build(Player p, Blueprint bp, Block origin, Plan plan) {
        World w = origin.getWorld();
        if (!plan.free) {
            for (Map.Entry<String, Integer> e : plan.cost.items().entrySet()) {
                consume(p, e.getKey(), e.getValue());
            }
        }
        for (Blueprint.Block b : BlueprintCost.placeOrder(bp)) {
            Block t = w.getBlockAt(origin.getX() + b.dx(), origin.getY() + b.dy(), origin.getZ() + b.dz());
            try {
                t.setBlockData(Bukkit.createBlockData(b.data()), BlueprintCost.attachable(BlueprintCost.materialOf(b.data())));
            } catch (IllegalArgumentException e) {
                p.sendMessage("§cSkipped a block that could not be built: " + b.data());
            }
        }
        int built = 0;
        for (Blueprint.Comp c : bp.components) {
            ComponentType t = ComponentType.byId(c.type());
            Block body = w.getBlockAt(origin.getX() + c.dx(), origin.getY() + c.dy(), origin.getZ() + c.dz());
            Material want = Material.valueOf(t.bodyMaterial());
            if (body.getType() != want) {
                body.setType(want, false);
            }
            Component comp = service.registerRaw(p.getUniqueId(), t, body, Dir.parse(c.facing(), Dir.NORTH));
            if (comp == null) {
                continue;
            }
            for (Map.Entry<String, Integer> e : c.params().entrySet()) {
                comp.setParam(e.getKey(), e.getValue());
            }
            comp.text = c.text() == null ? "" : c.text();
            comp.filter.clear();
            for (String f : c.filter()) {
                if (comp.filter.size() < t.filterSlots()) {
                    comp.filter.add(f);
                }
            }
            service.finishRaw(comp);
            built++;
        }
        registry.markDirty();
        announcer.say(p, Topic.BLUEPRINT_PASTED, "(" + bp.totalBlocks() + " blocks, " + built + " components)");
    }

    /** Draws the outline of the would-be build for the previewing player. */
    private void outline(Player p, Block origin, Blueprint bp) {
        int x0 = origin.getX();
        int y0 = origin.getY();
        int z0 = origin.getZ();
        int x1 = x0 + bp.sizeX;
        int y1 = y0 + bp.sizeY;
        int z1 = z0 + bp.sizeZ;
        World w = origin.getWorld();
        int[][] corner = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            int[] a = corner[e[0]];
            int[] b = corner[e[1]];
            int len = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
            int pts = Math.min(len, 24);
            for (int i = 0; i <= pts; i++) {
                double f = pts == 0 ? 0 : (double) i / pts;
                p.spawnParticle(Particle.END_ROD, new Location(w, a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f), 1);
            }
        }
    }
}
