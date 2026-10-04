package com.nexuscraft.nexusredstone;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Drives a component's signal into the world through its <em>output cell</em>, the single block
 * in front of it. Both strategies are built only from ordinary vanilla behaviour, never from
 * tricks that a server version could quietly change:
 *
 * <ul>
 * <li><b>Digital</b>: the cell is a redstone block while the output is on and a plain stone block
 * while it is off. A redstone block powers every neighbouring redstone component in the real
 * game, exactly as one placed by hand would.</li>
 * <li><b>Analog</b>: the cell is a barrel holding a calculated number of iron nuggets, so that a
 * vanilla comparator placed against it reads exactly the strength the component meant. See
 * {@link AnalogCellMath} for the arithmetic.</li>
 * </ul>
 *
 * <p>The cell is claimed in the {@link ComponentRegistry} so breaking, pushing, exploding or
 * hopper-draining it is intercepted by {@code ComponentListener}.
 */
final class OutputCells {

    static final Material ON = Material.REDSTONE_BLOCK;
    static final Material OFF = Material.SMOOTH_STONE;
    static final Material ANALOG = Material.BARREL;
    static final Material FILL = Material.IRON_NUGGET;

    private final ComponentRegistry registry;

    OutputCells(ComponentRegistry registry) {
        this.registry = registry;
    }

    private static boolean oursByMaterial(Material m) {
        return m == ON || m == OFF || m == ANALOG;
    }

    /** Places (or converts) the output cell for the component's current mode. Returns false if
     *  something that is not ours already occupies the cell. */
    boolean install(Component c) {
        Block cell = Geo.front(c);
        if (cell == null) {
            return false;
        }
        Material want = c.analogOutput() ? ANALOG : (c.output > 0 ? ON : OFF);
        Material cur = cell.getType();
        String key = Geo.key(cell);
        boolean claimed = registry.isCell(key) && registry.resolve(key) == c;
        if (cur != want) {
            if (cur != Material.AIR && !(claimed && oursByMaterial(cur))) {
                return false;
            }
            if (cur == ANALOG) {
                Containers.clear(Containers.at(cell));
            }
            cell.setType(want, true);
        }
        registry.claimCell(key, c.key());
        if (want == ANALOG) {
            fill(cell, c.output);
        }
        return true;
    }

    /** Sets the cell to show {@code level}. */
    void drive(Component c, int level) {
        Block cell = Geo.front(c);
        if (cell == null) {
            return;
        }
        String key = Geo.key(cell);
        boolean claimed = registry.isCell(key) && registry.resolve(key) == c;
        if (!claimed) {
            install(c);
            return;
        }
        if (c.analogOutput()) {
            if (cell.getType() != ANALOG) {
                install(c);
                return;
            }
            fill(cell, level);
            return;
        }
        Material want = level > 0 ? ON : OFF;
        Material cur = cell.getType();
        if (cur == want) {
            return;
        }
        if (oursByMaterial(cur) || cur == Material.AIR) {
            if (cur == ANALOG) {
                Containers.clear(Containers.at(cell));
            }
            cell.setType(want, true);
        }
    }

    private void fill(Block cell, int level) {
        Inventory inv = Containers.at(cell);
        if (inv == null) {
            return;
        }
        int slots = inv.getSize();
        int[] counts = AnalogCellMath.distribute(AnalogCellMath.itemsForSignal(level, slots, 64), slots, 64);
        for (int i = 0; i < slots; i++) {
            ItemStack cur = inv.getItem(i);
            int have = cur == null || cur.getType() != FILL ? 0 : cur.getAmount();
            if (cur != null && cur.getType() != FILL) {
                inv.setItem(i, null);
                have = 0;
            }
            if (have != counts[i]) {
                inv.setItem(i, counts[i] == 0 ? null : new ItemStack(FILL, counts[i]));
            }
        }
    }

    /** Removes the cell block (never leaving nuggets to drop) and releases its claim. */
    void remove(Component c) {
        Block cell = Geo.front(c);
        if (cell == null) {
            return;
        }
        String key = Geo.key(cell);
        if (registry.isCell(key) && registry.resolve(key) == c) {
            if (cell.getType() == ANALOG) {
                Containers.clear(Containers.at(cell));
            }
            if (oursByMaterial(cell.getType())) {
                cell.setType(Material.AIR, true);
            }
            registry.releaseCell(key);
        }
    }

    boolean isCell(Block b) {
        return registry.isCell(Geo.key(b));
    }
}
