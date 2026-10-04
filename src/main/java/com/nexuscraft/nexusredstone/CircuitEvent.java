package com.nexuscraft.nexusredstone;

/** Things the circuit engine reports to its host (the Bukkit layer) so it can react without the
 *  engine knowing anything about players, chat, or XP. */
enum CircuitEvent {
    /** A component's output strength changed. */
    OUTPUT_CHANGED,
    /** The lag guard shut a component off. */
    TRIPPED,
    /** A tripped component's cool-down ended and it is running again. */
    RESUMED,
    /** A programmable block's program failed to compile. */
    PLC_ERROR
}
