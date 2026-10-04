package com.nexuscraft.nexusredstone;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the item transport network. Input ports pull items from the container beside them and
 * route each one across connected pipes to an output port, which pushes them into the container
 * beside <em>it</em>. Routing is {@link TransportNetwork}; this class does the container work.
 *
 * <p>The central safety rule is the same one the tiered hoppers use: items are inserted into the
 * destination first and exactly the accepted amount is then removed from the source, on the main
 * thread in one go, so a full destination, a hostile filter, or an exception can never lose or
 * duplicate an item. Only plain containers are bulk-moved; a furnace-like container may only be
 * pulled from, and only from its result slot.
 */
final class TransportService {

    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final EngineerStore engineers;
    private final Announcer announcer;
    private final BukkitCircuitHost host;
    private long itemsMovedTotal;

    TransportService(RedstoneConfig cfg, ComponentRegistry registry, EngineerStore engineers, Announcer announcer,
                     BukkitCircuitHost host) {
        this.cfg = cfg;
        this.registry = registry;
        this.engineers = engineers;
        this.announcer = announcer;
        this.host = host;
    }

    long totalItemsMoved() {
        return itemsMovedTotal;
    }

    /** Called on a short timer with the simulation tick; each input port honours its own interval. */
    void run(long now) {
        List<Component> ports = new ArrayList<>();
        for (Component c : registry.ofFamily(Family.PIPE)) {
            if (c.type == ComponentType.PORT_IN) {
                ports.add(c);
            }
        }
        for (Component in : ports) {
            if (now < in.cooldownUntil || !host.isActive(in)) {
                continue;
            }
            int cooldown = in.param("interval");
            try {
                cooldown += pull(in);
            } catch (RuntimeException e) {
                // A bad container must never take the whole network down with it.
                cooldown += 100;
            }
            in.cooldownUntil = now + cooldown;
        }
    }

    /** Containers adjacent to a port (never another port, pipe or cell). */
    private List<Block> adjacentContainers(Component port) {
        List<Block> list = new ArrayList<>();
        Block body = Geo.body(port);
        if (body == null) {
            return list;
        }
        for (BlockFace f : FACES) {
            Block n = body.getRelative(f);
            if (registry.resolve(Geo.key(n)) != null && registry.resolve(Geo.key(n)).type.family == Family.PIPE) {
                continue;
            }
            if (registry.isCell(Geo.key(n))) {
                continue;
            }
            Inventory inv = Containers.at(n);
            if (inv != null && (Containers.plain(inv.getType()) || Containers.furnaceLike(inv.getType()))) {
                list.add(n);
            }
        }
        return list;
    }

    /** One pull cycle for an input port. Returns extra cooldown ticks (path travel time). */
    private int pull(Component in) {
        int level = engineers.levelOfOwner(in.owner);
        int budget = EngineerSkill.portItems(level, cfg);
        List<Block> sources = adjacentContainers(in);
        if (sources.isEmpty()) {
            return 0;
        }
        TransportNetwork.Result route = TransportNetwork.route(registry, in, EngineerSkill.pipeLength(level, cfg), cfg.networkMaxNodes);
        if (route.destinations().isEmpty()) {
            return 0;
        }
        List<TransportNetwork.Destination> order = TransportNetwork.roundRobin(route.destinations(), in.counter);
        int moved = 0;
        int farthest = 0;
        Set<String> sourceKeys = new HashSet<>();
        for (Block s : sources) {
            sourceKeys.add(Geo.key(s));
        }
        for (Block sourceBlock : sources) {
            Inventory from = Containers.at(sourceBlock);
            if (from == null) {
                continue;
            }
            boolean furnace = Containers.furnaceLike(from.getType());
            int firstSlot = furnace ? 2 : 0;
            int lastSlot = furnace ? 2 : from.getSize() - 1;
            for (int slot = firstSlot; slot <= lastSlot && budget > 0; slot++) {
                ItemStack stack = from.getItem(slot);
                if (stack == null || stack.getAmount() <= 0) {
                    continue;
                }
                if (!ItemFilter.passes(in.filter, in.param("mode"), stack.getType().name())) {
                    continue;
                }
                for (int d = 0; d < order.size() && budget > 0; d++) {
                    TransportNetwork.Destination dest = order.get(d);
                    Component out = dest.port();
                    if (!ItemFilter.passes(out.filter, out.param("mode"), stack.getType().name())) {
                        continue;
                    }
                    int n = deliver(out, sourceKeys, from, stack, Math.min(budget, stack.getAmount()));
                    if (n > 0) {
                        budget -= n;
                        moved += n;
                        farthest = Math.max(farthest, dest.pathLength());
                        in.counter = (in.counter + 1) & 0xFFFF;
                        stack = from.getItem(slot);
                        if (stack == null || stack.getAmount() <= 0) {
                            break;
                        }
                    }
                }
            }
        }
        if (moved > 0) {
            record(in, moved);
        }
        return farthest * cfg.pathTicksPerPipe;
    }

    /** Pushes up to {@code max} of an item into one output port's neighbouring containers. */
    private int deliver(Component out, Set<String> sourceKeys, Inventory from, ItemStack proto, int max) {
        int total = 0;
        for (Block destBlock : adjacentContainers(out)) {
            if (total >= max) {
                break;
            }
            if (sourceKeys.contains(Geo.key(destBlock))) {
                continue;
            }
            Inventory to = Containers.at(destBlock);
            if (to == null || !Containers.plain(to.getType())) {
                continue; // furnaces and friends are never bulk-filled
            }
            total += HopperService.transfer(from, to, proto, max - total);
        }
        return total;
    }

    private void record(Component in, int moved) {
        itemsMovedTotal += moved;
        int before = in.memory;
        in.memory = Math.min(Integer.MAX_VALUE - 1, in.memory + moved);
        registry.markDirty();
        host.noteActivity(in);
        if (in.memory / 1000 > before / 1000) {
            announcer.sayAt(in.owner, Topic.TRANSPORT_MILESTONE, Geo.body(in));
            if (cfg.xpPerThousandItemsMoved > 0 && in.owner != null) {
                org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(in.owner);
                if (p != null) {
                    EngineerStore.XpResult r = engineers.addXp(p, cfg.xpPerThousandItemsMoved);
                    if (r.leveledUp()) {
                        announcer.say(p, Topic.LEVEL_UP, "Engineer level " + r.newLevel() + " (" + EngineerSkill.title(r.newLevel()) + ")");
                    }
                }
            }
        }
    }
}
