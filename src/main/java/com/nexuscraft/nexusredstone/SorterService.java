package com.nexuscraft.nexusredstone;

import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The Sorting Barrel: groups identical stacks, merges them into full stacks, and lays them out by
 * category. The ordering itself is {@link SmartStorage}; this class is the careful part, which is
 * reading real item stacks into groups (two stacks are "the same" exactly when {@code isSimilar}
 * says so, so named, enchanted or otherwise special items never merge into plain ones) and
 * proving that nothing was lost before it writes anything back.
 */
final class SorterService implements Listener {

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final Announcer announcer;
    private final Logger log;
    private final Map<String, Long> lastSorted = new HashMap<>();

    SorterService(RedstoneConfig cfg, ComponentRegistry registry, Announcer announcer, Logger log) {
        this.cfg = cfg;
        this.registry = registry;
        this.announcer = announcer;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (inv == null || inv.getLocation() == null || inv.getLocation().getWorld() == null) {
            return;
        }
        org.bukkit.Location l = inv.getLocation();
        Component c = registry.at(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (c == null || c.type != ComponentType.SORTER || c.param("auto") != 1) {
            return;
        }
        HumanEntity who = event.getPlayer();
        sort(c, inv, who instanceof Player ? (Player) who : null, false);
    }

    /** Sorts a sorter's inventory. Returns false if it was already tidy, on cooldown, or unsafe. */
    boolean sort(Component c, Inventory inv, Player player, boolean ignoreCooldown) {
        long now = System.currentTimeMillis();
        Long last = lastSorted.get(c.key());
        if (!ignoreCooldown && last != null && now - last < cfg.sorterCooldownTicks * 50L) {
            return false;
        }
        lastSorted.put(c.key(), now);

        int size = inv.getSize();
        List<ItemStack> prototypes = new ArrayList<>();
        List<SmartStorage.StackView> views = new ArrayList<>();
        Map<Integer, Integer> before = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getAmount() <= 0 || s.getType().isAir()) {
                views.add(null);
                continue;
            }
            int group = -1;
            for (int g = 0; g < prototypes.size(); g++) {
                if (prototypes.get(g).isSimilar(s)) {
                    group = g;
                    break;
                }
            }
            if (group < 0) {
                group = prototypes.size();
                prototypes.add(s.clone());
            }
            before.merge(group, s.getAmount(), Integer::sum);
            views.add(new SmartStorage.StackView(group, s.getType().name(), s.getAmount(), s.getMaxStackSize()));
        }
        List<SmartStorage.StackView> nonNull = new ArrayList<>();
        for (SmartStorage.StackView v : views) {
            if (v != null) {
                nonNull.add(v);
            }
        }
        List<SmartStorage.Placed> placed = SmartStorage.sort(nonNull);

        Map<Integer, Integer> after = new HashMap<>();
        for (SmartStorage.Placed p : placed) {
            after.merge(p.group(), p.amount(), Integer::sum);
        }
        if (!before.equals(after) || placed.size() > size) {
            log.severe("nexusredstone: refused to sort " + c + " because the plan did not conserve its items");
            return false;
        }
        // views with gaps compare as "not already sorted" unless the slot layout is identical
        List<SmartStorage.StackView> slots = new ArrayList<>();
        for (SmartStorage.StackView v : views) {
            slots.add(v == null ? new SmartStorage.StackView(-1, "", 0, 1) : v);
        }
        if (SmartStorage.alreadySorted(slots, placed)) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (i < placed.size()) {
                SmartStorage.Placed p = placed.get(i);
                ItemStack out = prototypes.get(p.group()).clone();
                out.setAmount(p.amount());
                inv.setItem(i, out);
            } else if (inv.getItem(i) != null) {
                inv.setItem(i, null);
            }
        }
        if (player != null) {
            announcer.say(player, Topic.SORTED, null);
        }
        return true;
    }

    void forget(Component c) {
        lastSorted.remove(c.key());
    }
}
