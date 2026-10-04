package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every placed component, keyed by its body block's position, plus an index of the extra blocks a
 * component owns (its output cell, a mega piston's arm) so the Bukkit layer can protect and
 * resolve them. Pure data -- no server types -- so the circuit engine and every test can build a
 * whole registry in memory.
 *
 * <p>A circuit-component snapshot list is cached and rebuilt lazily whenever the set of
 * components changes, so the simulation task can iterate without allocating each step and event
 * handlers that add or remove components mid-step can never corrupt an iteration in progress.
 */
final class ComponentRegistry {

    private final Map<String, Component> byKey = new LinkedHashMap<>();
    private final Map<String, String> cellOwner = new HashMap<>();
    private List<Component> circuitSnapshot = Collections.emptyList();
    private boolean snapshotDirty = true;
    private boolean dirty;

    Component get(String key) {
        return byKey.get(key);
    }

    Component at(String world, int x, int y, int z) {
        return byKey.get(Component.keyOf(world, x, y, z));
    }

    boolean add(Component c) {
        if (byKey.containsKey(c.key())) {
            return false;
        }
        byKey.put(c.key(), c);
        snapshotDirty = true;
        dirty = true;
        return true;
    }

    Component remove(String key) {
        Component c = byKey.remove(key);
        if (c != null) {
            cellOwner.values().removeIf(v -> v.equals(key));
            snapshotDirty = true;
            dirty = true;
        }
        return c;
    }

    /** Registers an extra block (output cell, piston arm) as belonging to a component. */
    void claimCell(String cellKey, String componentKey) {
        cellOwner.put(cellKey, componentKey);
        dirty = true;
    }

    /** Claims every extra block a component owns purely from its position, facing and settings:
     *  its output cell, and a mega piston's arm while it is extended. Used when a component is
     *  placed, loaded, or changes mode. */
    void claimDerived(Component c) {
        if (c.type.hasOutputCell) {
            claimCell(Component.keyOf(c.world, c.x + c.facing.dx, c.y, c.z + c.facing.dz), c.key());
        }
        if (c.type == ComponentType.MEGA_PISTON && c.extended) {
            for (String arm : armCells(c)) {
                claimCell(arm, c.key());
            }
        }
    }

    /** Positions of a mega piston's arm blocks (the cells between its body and the pushed line). */
    static List<String> armCells(Component c) {
        List<String> out = new ArrayList<>();
        int[] v = Dir.vector(c.facing, c.param("axis"));
        for (int i = 1; i <= c.param("stroke"); i++) {
            out.add(Component.keyOf(c.world, c.x + v[0] * i, c.y + v[1] * i, c.z + v[2] * i));
        }
        return out;
    }

    void releaseCell(String cellKey) {
        cellOwner.remove(cellKey);
        dirty = true;
    }

    /** Component that owns a block position, whether as its body or as one of its extra cells. */
    Component resolve(String blockKey) {
        Component c = byKey.get(blockKey);
        if (c != null) {
            return c;
        }
        String owner = cellOwner.get(blockKey);
        return owner == null ? null : byKey.get(owner);
    }

    boolean isCell(String blockKey) {
        return cellOwner.containsKey(blockKey);
    }

    List<String> cellsOf(String componentKey) {
        List<String> cells = new ArrayList<>();
        for (Map.Entry<String, String> e : cellOwner.entrySet()) {
            if (e.getValue().equals(componentKey)) {
                cells.add(e.getKey());
            }
        }
        return cells;
    }

    Map<String, String> cellIndex() {
        return Collections.unmodifiableMap(cellOwner);
    }

    int size() {
        return byKey.size();
    }

    Collection<Component> all() {
        return Collections.unmodifiableCollection(byKey.values());
    }

    List<Component> snapshotAll() {
        return new ArrayList<>(byKey.values());
    }

    /** Components the circuit engine evaluates, as a cached snapshot. */
    List<Component> circuitComponents() {
        if (snapshotDirty) {
            List<Component> list = new ArrayList<>();
            for (Component c : byKey.values()) {
                if (c.type.isCircuit()) {
                    list.add(c);
                }
            }
            circuitSnapshot = list;
            snapshotDirty = false;
        }
        return circuitSnapshot;
    }

    int countOwnedBy(UUID owner) {
        if (owner == null) {
            return 0;
        }
        int n = 0;
        for (Component c : byKey.values()) {
            if (owner.equals(c.owner)) {
                n++;
            }
        }
        return n;
    }

    List<Component> ofFamily(Family family) {
        List<Component> list = new ArrayList<>();
        for (Component c : byKey.values()) {
            if (c.type.family == family) {
                list.add(c);
            }
        }
        return list;
    }

    void markDirty() {
        dirty = true;
    }

    boolean isDirty() {
        return dirty;
    }

    void clearDirty() {
        dirty = false;
    }

    void clear() {
        byKey.clear();
        cellOwner.clear();
        snapshotDirty = true;
        dirty = true;
    }
}
