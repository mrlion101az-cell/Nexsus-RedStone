package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;

/**
 * Tiered hoppers: item filters, bigger batches, and vacuuming of dropped items.
 *
 * <p>A tiered hopper is a real vanilla hopper block, so everything vanilla (the 8-tick cooldown,
 * locking by redstone, facing, comparator reading, minecart pickup) works unchanged. On top of
 * that: any item a filtered tier hopper would move or receive must pass its filter; and when the
 * hopper itself starts a transfer between two plain containers, a follow-up move one tick later
 * carries the rest of the tier's batch. Doing the extra items as a separate, explicit move --
 * rather than editing the vanilla event's item -- is deliberate: changing the amount on a vanilla
 * transfer event is a known source of item-duplication quirks, while an add-then-subtract on the
 * main thread cannot duplicate or lose anything.
 */
final class HopperService implements Listener {

    private final Plugin plugin;
    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final BukkitCircuitHost host;
    private boolean moving;

    HopperService(Plugin plugin, RedstoneConfig cfg, ComponentRegistry registry, BukkitCircuitHost host) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.registry = registry;
        this.host = host;
    }

    private Component hopperAt(Inventory inv) {
        if (inv == null) {
            return null;
        }
        Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) {
            return null;
        }
        Component c = registry.at(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        return c != null && c.type.isHopper() ? c : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (moving || registry.size() == 0) {
            return;
        }
        Component src = hopperAt(event.getSource());
        Component dst = hopperAt(event.getDestination());
        if (src == null && dst == null) {
            return;
        }
        String material = event.getItem().getType().name();
        if (src != null && !ItemFilter.passes(src.filter, src.param("mode"), material)) {
            event.setCancelled(true);
            return;
        }
        if (dst != null && !ItemFilter.passes(dst.filter, dst.param("mode"), material)) {
            event.setCancelled(true);
            return;
        }
        Component initiator = hopperAt(event.getInitiator());
        if (initiator == null) {
            return;
        }
        int extra = HopperTiers.extraItems(initiator.type, cfg);
        if (extra <= 0 || !Containers.plain(event.getSource().getType()) || !Containers.plain(event.getDestination().getType())) {
            return;
        }
        Inventory from = event.getSource();
        Inventory to = event.getDestination();
        ItemStack proto = event.getItem().clone();
        Bukkit.getScheduler().runTask(plugin, () -> moveExtra(initiator, src, dst, from, to, proto, extra));
    }

    /** Carries up to {@code extra} more items of the same kind the vanilla transfer just moved. */
    private void moveExtra(Component initiator, Component src, Component dst, Inventory from, Inventory to, ItemStack proto, int extra) {
        moving = true;
        try {
            int moved = transfer(from, to, proto, extra);
            if (moved > 0) {
                host.noteActivity(initiator);
            }
        } finally {
            moving = false;
        }
    }

    /** Moves up to {@code max} items similar to {@code proto}; returns how many actually moved.
     *  Insert first, then subtract exactly what the destination accepted. */
    static int transfer(Inventory from, Inventory to, ItemStack proto, int max) {
        int available = Containers.count(from, proto);
        int want = Math.min(max, available);
        if (want <= 0) {
            return 0;
        }
        ItemStack offer = proto.clone();
        offer.setAmount(want);
        HashMap<Integer, ItemStack> leftover = to.addItem(offer);
        int refused = 0;
        for (ItemStack rest : leftover.values()) {
            refused += rest.getAmount();
        }
        int moved = want - refused;
        if (moved > 0) {
            Containers.take(from, proto, moved);
        }
        return Math.max(0, moved);
    }

    // ------------------------------------------------------------------ vacuum

    /** Timer entry point: every diamond/netherite hopper with vacuum on pulls nearby items. */
    void vacuum() {
        for (Component c : registry.ofFamily(Family.HOPPER)) {
            if (!HopperTiers.canVacuum(c.type) || c.param("vacuum") != 1 || !host.isActive(c)) {
                continue;
            }
            Block body = Geo.body(c);
            if (body == null) {
                continue;
            }
            Inventory inv = Containers.at(body);
            if (inv == null) {
                continue;
            }
            int r = HopperTiers.vacuumRadius(c.type, cfg);
            Location centre = body.getLocation().add(0.5, 1.0, 0.5);
            int budget = cfg.hopperVacuumMaxItemsPerRun;
            for (Entity e : body.getWorld().getNearbyEntities(centre, r, r, r)) {
                if (budget <= 0) {
                    break;
                }
                if (!(e instanceof Item) || !e.isValid()) {
                    continue;
                }
                Item entity = (Item) e;
                ItemStack stack = entity.getItemStack();
                if (stack == null || stack.getAmount() <= 0 || entity.getPickupDelay() >= 32767) {
                    continue;
                }
                if (!ItemFilter.passes(c.filter, c.param("mode"), stack.getType().name())) {
                    continue;
                }
                int before = stack.getAmount();
                HashMap<Integer, ItemStack> leftover = inv.addItem(stack.clone());
                int refused = 0;
                for (ItemStack rest : leftover.values()) {
                    refused += rest.getAmount();
                }
                if (refused >= before) {
                    continue;
                }
                if (refused == 0) {
                    entity.remove();
                } else {
                    ItemStack rest = stack.clone();
                    rest.setAmount(refused);
                    entity.setItemStack(rest);
                }
                budget--;
                host.noteActivity(c);
            }
        }
    }
}
