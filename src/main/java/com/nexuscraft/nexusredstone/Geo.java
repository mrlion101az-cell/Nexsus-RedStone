package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/** Small translation helpers between this plugin's Bukkit-free model and real blocks. */
final class Geo {

    private Geo() {
    }

    static BlockFace face(Dir d) {
        switch (d) {
            case NORTH:
                return BlockFace.NORTH;
            case EAST:
                return BlockFace.EAST;
            case SOUTH:
                return BlockFace.SOUTH;
            default:
                return BlockFace.WEST;
        }
    }

    static int[] vector(Dir facing, int axis) {
        return Dir.vector(facing, axis);
    }

    static World world(String name) {
        return Bukkit.getWorld(name);
    }

    /** The component's body block, or null if its world is not loaded. */
    static Block body(Component c) {
        World w = world(c.world);
        return w == null ? null : w.getBlockAt(c.x, c.y, c.z);
    }

    /** The block in front of a component, where its output cell lives. */
    static Block front(Component c) {
        Block b = body(c);
        return b == null ? null : b.getRelative(face(c.facing));
    }

    static String key(Block b) {
        return Component.keyOf(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    static String key(String world, int x, int y, int z) {
        return Component.keyOf(world, x, y, z);
    }
}
