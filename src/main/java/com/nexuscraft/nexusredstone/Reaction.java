package com.nexuscraft.nexusredstone;

/** How a block in the world responds to being pushed, independent of any server type. */
enum Reaction {
    /** Empty space. */
    AIR,
    /** Can be pushed along. */
    MOVE,
    /** Is destroyed (drops as an item) when pushed into. */
    BREAK,
    /** Cannot be moved and stops whatever pushes against it. */
    BLOCK
}
