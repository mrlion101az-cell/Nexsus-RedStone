package com.nexuscraft.nexusredstone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds and validates the structure a block mover carries. The rule is vanilla's glue rule, made
 * explicit and bounded: starting from the sticky blocks (honey or slime) touching the mover, a
 * sticky block drags along every non-air block next to it, and any of those that are themselves
 * sticky drag along their neighbours in turn; a non-sticky block is carried but never spreads
 * the grab any further. Because terrain touching a sticky block is dragged too, the search stops
 * with a clear failure the moment it exceeds the block limit or touches something immovable --
 * a misplaced honey block produces a message, not a landslide.
 *
 * <p>A trip is then checked cell by cell along its whole path; if something is in the way, the
 * structure moves as far as it safely can instead of failing outright, so a platform can stop
 * short of a ceiling rather than refusing to leave.
 */
final class StructurePlanner {

    record Plan(boolean ok, String reason, List<int[]> cells, int distance, List<int[]> breaks) {
        static Plan fail(String reason) {
            return new Plan(false, reason, List.of(), 0, List.of());
        }
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private StructurePlanner() {
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * Collects the structure. The mover sits at {@code (cx, cy, cz)}; {@code offsetX/Y/Z} shifts
     * where the search looks for the structure's anchor blocks, which is how a structure that has
     * already travelled is found again to bring it back.
     */
    static Plan collect(BlockView view, int cx, int cy, int cz, int offsetX, int offsetY, int offsetZ, int maxBlocks) {
        Set<Long> seen = new HashSet<>();
        List<int[]> cells = new ArrayList<>();
        ArrayDeque<int[]> frontier = new ArrayDeque<>();

        for (int[] f : FACES) {
            int x = cx + f[0] + offsetX;
            int y = cy + f[1] + offsetY;
            int z = cz + f[2] + offsetZ;
            if (view.sticky(x, y, z) && view.reaction(x, y, z) == Reaction.MOVE && seen.add(key(x, y, z))) {
                cells.add(new int[] {x, y, z});
                frontier.add(new int[] {x, y, z});
            }
        }
        if (cells.isEmpty()) {
            return Plan.fail("no honey or slime block is touching the mover");
        }
        while (!frontier.isEmpty()) {
            int[] cur = frontier.poll();
            // Only sticky blocks spread the grab.
            if (!view.sticky(cur[0], cur[1], cur[2])) {
                continue;
            }
            for (int[] f : FACES) {
                int x = cur[0] + f[0];
                int y = cur[1] + f[1];
                int z = cur[2] + f[2];
                if (x == cx && y == cy && z == cz) {
                    continue; // the mover itself never travels
                }
                Reaction r = view.reaction(x, y, z);
                if (r == Reaction.AIR || r == Reaction.BREAK && !view.sticky(x, y, z)) {
                    continue;
                }
                if (r == Reaction.BLOCK) {
                    return Plan.fail("the structure is attached to something immovable at " + x + ", " + y + ", " + z);
                }
                if (!seen.add(key(x, y, z))) {
                    continue;
                }
                if (cells.size() >= maxBlocks) {
                    return Plan.fail("the structure has more than " + maxBlocks + " blocks (is a honey block touching the ground?)");
                }
                cells.add(new int[] {x, y, z});
                frontier.add(new int[] {x, y, z});
            }
        }
        return new Plan(true, "", cells, 0, List.of());
    }

    /** Given a collected structure, finds how far (up to {@code distance}) it can travel along
     *  {@code (dx, dy, dz)} and which fragile blocks it crushes on the way. */
    static Plan sweep(BlockView view, List<int[]> cells, int dx, int dy, int dz, int distance) {
        Set<Long> own = new HashSet<>();
        for (int[] c : cells) {
            own.add(key(c[0], c[1], c[2]));
        }
        int safe = 0;
        for (int k = 1; k <= distance; k++) {
            boolean clear = true;
            for (int[] c : cells) {
                int x = c[0] + dx * k;
                int y = c[1] + dy * k;
                int z = c[2] + dz * k;
                if (own.contains(key(x, y, z))) {
                    continue;
                }
                Reaction r = view.reaction(x, y, z);
                if (r == Reaction.MOVE || r == Reaction.BLOCK) {
                    clear = false;
                    break;
                }
            }
            if (!clear) {
                break;
            }
            safe = k;
        }
        if (safe == 0) {
            return Plan.fail("something is in the way");
        }
        List<int[]> breaks = new ArrayList<>();
        Set<Long> seenBreaks = new HashSet<>();
        for (int k = 1; k <= safe; k++) {
            for (int[] c : cells) {
                int x = c[0] + dx * k;
                int y = c[1] + dy * k;
                int z = c[2] + dz * k;
                if (!own.contains(key(x, y, z)) && view.reaction(x, y, z) == Reaction.BREAK && seenBreaks.add(key(x, y, z))) {
                    breaks.add(new int[] {x, y, z});
                }
            }
        }
        return new Plan(true, "", cells, safe, breaks);
    }
}
