package com.nexuscraft.nexusredstone;

/**
 * The four horizontal compass directions, kept free of any Bukkit type so every piece of
 * facing-relative logic in the circuit engine (a gate's left/right/back inputs, a mega piston's
 * push axis) is unit-testable without a server. Order is clockwise starting at NORTH, which is
 * what makes {@link #left()}/{@link #right()} a plain index shift.
 */
enum Dir {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

    final int dx;
    final int dz;

    Dir(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    Dir right() {
        return values()[(ordinal() + 1) % 4];
    }

    Dir left() {
        return values()[(ordinal() + 3) % 4];
    }

    Dir back() {
        return values()[(ordinal() + 2) % 4];
    }

    /** Real Minecraft yaw: 0 faces SOUTH, 90 faces WEST, 180 faces NORTH, 270 (or -90) faces EAST. */
    static Dir fromYaw(float yaw) {
        float y = yaw % 360f;
        if (y < 0) {
            y += 360f;
        }
        if (y >= 315f || y < 45f) {
            return SOUTH;
        }
        if (y < 135f) {
            return WEST;
        }
        if (y < 225f) {
            return NORTH;
        }
        return EAST;
    }

    static Dir parse(String s, Dir fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Push direction as a unit vector for something with an axis setting: 0 forward along the
     *  facing, 1 straight up, 2 straight down. */
    static int[] vector(Dir facing, int axis) {
        if (axis == 1) {
            return new int[] {0, 1, 0};
        }
        if (axis == 2) {
            return new int[] {0, -1, 0};
        }
        return new int[] {facing.dx, 0, facing.dz};
    }
}
