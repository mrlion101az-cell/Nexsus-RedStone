package com.nexuscraft.nexusredstone;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.PistonMoveReaction;

/**
 * The real-world {@link BlockView}: asks Bukkit how vanilla pistons would treat each block (via
 * {@link Block#getPistonMoveReaction()}) and adds two plugin rules on top. Any block that belongs
 * to a registered component (its body, its output cell, a mega piston's arm) is immovable, so a
 * long push can never shove a gate out from under its own output block; and a block in an
 * unloaded chunk is treated as immovable rather than silently loading the chunk to look at it.
 */
final class WorldBlockView implements BlockView {

    private final World world;
    private final ComponentRegistry registry;

    WorldBlockView(World world, ComponentRegistry registry) {
        this.world = world;
        this.registry = registry;
    }

    @Override
    public Reaction reaction(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return Reaction.BLOCK;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return Reaction.BLOCK;
        }
        if (registry.resolve(Component.keyOf(world.getName(), x, y, z)) != null) {
            return Reaction.BLOCK;
        }
        Block b = world.getBlockAt(x, y, z);
        if (b.getType().isAir()) {
            return Reaction.AIR;
        }
        if (b.isLiquid()) {
            return Reaction.BREAK;
        }
        PistonMoveReaction r = b.getPistonMoveReaction();
        switch (r) {
            case MOVE:
            case PUSH_ONLY:
                return Reaction.MOVE;
            case BREAK:
                return Reaction.BREAK;
            default:
                return Reaction.BLOCK;
        }
    }

    @Override
    public boolean sticky(int x, int y, int z) {
        Material m = world.getBlockAt(x, y, z).getType();
        return m == Material.HONEY_BLOCK || m == Material.SLIME_BLOCK;
    }
}
