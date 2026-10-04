package com.nexuscraft.nexusredstone;

/** What a component's logic needs to know about "now" and its owner's limits: the game tick, and
 *  the program-size limits a programmable block compiles against. */
record EvalContext(long now, int plcMaxOps, int plcMaxLength) {
}
