package com.nexuscraft.nexusredstone;

/** A read-only window onto the world's blocks that the push and structure planners use. The
 *  real implementation wraps Bukkit blocks; tests use a small in-memory grid. */
interface BlockView {

    Reaction reaction(int x, int y, int z);

    /** True for glue blocks (honey, slime) that drag their neighbours along. */
    boolean sticky(int x, int y, int z);
}
