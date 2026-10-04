package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans a straight-line push for the mega piston, with no limit of 12 like vanilla's and an
 * optional multi-block stroke. A push is the vanilla rule generalised: starting at the cell in
 * front of the piston, take the unbroken run of movable blocks; the run ends at air (fine), at a
 * fragile block (destroyed by the last pushed block), or at an immovable block (the whole push
 * fails). A multi-block stroke is simulated one block at a time against a virtual overlay of the
 * world, so a long line followed by a gap followed by more blocks behaves exactly as repeated
 * single pushes would.
 *
 * <p>Pull (the sticky retract) mirrors it: the run of movable blocks that starts right after the
 * extended stroke is drawn back by the stroke length into the gap the arm leaves behind.
 */
final class LinearPushPlanner {

    record Move(int fx, int fy, int fz, int tx, int ty, int tz) {
    }

    record Plan(boolean ok, String reason, List<Move> moves, List<int[]> breaks) {
        static Plan fail(String reason) {
            return new Plan(false, reason, List.of(), List.of());
        }
    }

    private LinearPushPlanner() {
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Virtual world: what has moved where so far. */
    private static final class Overlay {
        final BlockView view;
        // current cell -> original cell of the block now sitting there (moved blocks only)
        final Map<Long, int[]> occupant = new HashMap<>();
        final Set<Long> vacated = new HashSet<>();
        final List<int[]> breaks = new ArrayList<>();
        final Set<Long> brokenKeys = new HashSet<>();

        Overlay(BlockView view) {
            this.view = view;
        }

        Reaction at(int x, int y, int z) {
            long k = key(x, y, z);
            if (occupant.containsKey(k)) {
                return Reaction.MOVE;
            }
            if (vacated.contains(k) || brokenKeys.contains(k)) {
                return Reaction.AIR;
            }
            return view.reaction(x, y, z);
        }
    }

    /**
     * Pushes forward.
     *
     * @param sx first cell in front of the piston body
     * @param limit most blocks one push may move
     * @param stroke how many cells the line advances
     */
    static Plan push(BlockView view, int sx, int sy, int sz, int dx, int dy, int dz, int limit, int stroke) {
        Overlay ov = new Overlay(view);
        for (int step = 0; step < stroke; step++) {
            int fx = sx + dx * step;
            int fy = sy + dy * step;
            int fz = sz + dz * step;
            int n = 0;
            while (ov.at(fx + dx * n, fy + dy * n, fz + dz * n) == Reaction.MOVE) {
                n++;
                if (n > limit) {
                    return Plan.fail("too many blocks in the line (limit " + limit + ")");
                }
            }
            int ex = fx + dx * n;
            int ey = fy + dy * n;
            int ez = fz + dz * n;
            Reaction end = ov.at(ex, ey, ez);
            if (end == Reaction.BLOCK) {
                return Plan.fail("the line is blocked by something immovable");
            }
            if (end == Reaction.BREAK) {
                ov.breaks.add(new int[] {ex, ey, ez});
                ov.brokenKeys.add(key(ex, ey, ez));
            }
            // Shift the run forward by one, last block first so nothing is overwritten.
            for (int i = n - 1; i >= 0; i--) {
                int cx = fx + dx * i;
                int cy = fy + dy * i;
                int cz = fz + dz * i;
                int[] origin = ov.occupant.remove(key(cx, cy, cz));
                if (origin == null) {
                    origin = new int[] {cx, cy, cz};
                }
                ov.occupant.put(key(cx + dx, cy + dy, cz + dz), origin);
                ov.brokenKeys.remove(key(cx + dx, cy + dy, cz + dz));
            }
            if (n > 0) {
                ov.vacated.add(key(fx, fy, fz));
            }
        }
        return finish(ov);
    }

    private static Plan finish(Overlay ov) {
        List<Move> moves = new ArrayList<>();
        for (Map.Entry<Long, int[]> e : ov.occupant.entrySet()) {
            // Recover the destination cell from the key by searching the overlay's own record:
            // store destination alongside to keep this simple and exact.
            int[] from = e.getValue();
            int[] to = decode(e.getKey());
            if (from[0] != to[0] || from[1] != to[1] || from[2] != to[2]) {
                moves.add(new Move(from[0], from[1], from[2], to[0], to[1], to[2]));
            }
        }
        // Breaks only count if the destroyed cell was a real BREAK block in the original world.
        List<int[]> breaks = new ArrayList<>();
        for (int[] b : ov.breaks) {
            if (ov.view.reaction(b[0], b[1], b[2]) == Reaction.BREAK) {
                breaks.add(b);
            }
        }
        return new Plan(true, "", moves, breaks);
    }

    private static int[] decode(long k) {
        int x = (int) ((k >> 38) & 0x3FFFFFF);
        int z = (int) ((k >> 12) & 0x3FFFFFF);
        int y = (int) (k & 0xFFF);
        if (x >= 0x2000000) {
            x -= 0x4000000;
        }
        if (z >= 0x2000000) {
            z -= 0x4000000;
        }
        if (y >= 0x800) {
            y -= 0x1000;
        }
        return new int[] {x, y, z};
    }

    /**
     * Pulls back. {@code (sx, sy, sz)} is the first cell in front of the piston body (where the
     * arm began); the pulled run starts {@code stroke} cells further out.
     */
    static Plan pull(BlockView view, int sx, int sy, int sz, int dx, int dy, int dz, int limit, int stroke) {
        // The gap the arm leaves must be empty for the run to slide into it.
        for (int i = 0; i < stroke; i++) {
            if (view.reaction(sx + dx * i, sy + dy * i, sz + dz * i) != Reaction.AIR) {
                return Plan.fail("the space behind the line is not clear");
            }
        }
        int cx = sx + dx * stroke;
        int cy = sy + dy * stroke;
        int cz = sz + dz * stroke;
        List<Move> moves = new ArrayList<>();
        int n = 0;
        while (view.reaction(cx + dx * n, cy + dy * n, cz + dz * n) == Reaction.MOVE) {
            n++;
            if (n > limit) {
                return Plan.fail("too many blocks in the line (limit " + limit + ")");
            }
        }
        for (int i = 0; i < n; i++) {
            moves.add(new Move(cx + dx * i, cy + dy * i, cz + dz * i,
                    cx + dx * i - dx * stroke, cy + dy * i - dy * stroke, cz + dz * i - dz * stroke));
        }
        return new Plan(true, "", moves, List.of());
    }
}
