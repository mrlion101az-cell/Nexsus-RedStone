package com.nexuscraft.nexusredstone;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Helpers for reading and writing real container inventories safely. The central rule, which this
 * whole plugin leans on: bulk item movement (tiered hoppers, transport ports) only ever touches
 * <em>plain</em> containers (chests, barrels, shulker boxes, hoppers, droppers, dispensers).
 * Furnaces, brewing stands, crafters and similar have slot rules (what may go in which slot) that
 * a plain {@code addItem}/"take any matching stack" ignores, so a bulk move into one could put an
 * ingredient in the fuel slot or pull one out of the input slot. For those, this plugin either
 * leaves vanilla hopper behaviour alone or, for ports, takes only from a furnace's result slot.
 */
final class Containers {

    private Containers() {
    }

    static boolean plain(InventoryType type) {
        if (type == null) {
            return false;
        }
        switch (type) {
            case CHEST:
            case BARREL:
            case SHULKER_BOX:
            case HOPPER:
            case DROPPER:
            case DISPENSER:
                return true;
            default:
                return false;
        }
    }

    static boolean furnaceLike(InventoryType type) {
        return type == InventoryType.FURNACE || type == InventoryType.BLAST_FURNACE || type == InventoryType.SMOKER;
    }

    /** The live inventory of the container at a block, or null if it is not a container. */
    static Inventory at(Block block) {
        if (block == null) {
            return null;
        }
        BlockState state = block.getState(false);
        if (state instanceof InventoryHolder) {
            return ((InventoryHolder) state).getInventory();
        }
        return null;
    }

    static void clear(Inventory inv) {
        if (inv == null) {
            return;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) != null) {
                inv.setItem(i, null);
            }
        }
    }

    /** Total amount of items similar to the prototype across the inventory. */
    static int count(Inventory inv, ItemStack proto) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getAmount() > 0 && s.isSimilar(proto)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    /** Removes exactly {@code amount} similar items (the caller has already checked they exist). */
    static void take(Inventory inv, ItemStack proto, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getAmount() > 0 && s.isSimilar(proto)) {
                int t = Math.min(remaining, s.getAmount());
                if (t == s.getAmount()) {
                    inv.setItem(i, null);
                } else {
                    s.setAmount(s.getAmount() - t);
                    inv.setItem(i, s);
                }
                remaining -= t;
            }
        }
    }

    /** Container fullness in thousandths, by vanilla's own comparator definition. */
    static int fillPermille(Inventory inv) {
        int slots = inv.getSize();
        if (slots <= 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getAmount() > 0) {
                sum += (double) s.getAmount() / Math.max(1, s.getMaxStackSize());
            }
        }
        int permille = (int) Math.round(sum / slots * 1000.0);
        if (sum > 0 && permille == 0) {
            permille = 1;
        }
        return Math.min(1000, permille);
    }
}
