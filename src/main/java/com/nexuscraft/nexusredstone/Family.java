package com.nexuscraft.nexusredstone;

/** Broad component groupings. A family decides the visible body block, so players can tell a
 *  logic gate from a timer from a sensor at a glance without opening anything. Body block names
 *  are plain strings here and resolved to real materials in the Bukkit-facing layer, which keeps
 *  this enum (and everything built on it) free of any server type. */
enum Family {
    LOGIC("QUARTZ_BLOCK", "Logic", true),
    MEMORY("LAPIS_BLOCK", "Memory", true),
    TIMING("GOLD_BLOCK", "Timing", true),
    ANALOG("COPPER_BLOCK", "Analog", true),
    WIRELESS("AMETHYST_BLOCK", "Wireless", true),
    SENSOR("EMERALD_BLOCK", "Sensor", true),
    PROGRAMMABLE("DIAMOND_BLOCK", "Programmable", true),
    HOPPER("HOPPER", "Hopper", false),
    DISPENSER("DISPENSER", "Dispenser", false),
    PIPE("IRON_BARS", "Transport", false),
    STORAGE("BARREL", "Storage", false),
    PISTON("IRON_BLOCK", "Piston", false),
    MOVER("LODESTONE", "Mover", false);

    final String bodyMaterial;
    final String label;
    /** True for the families whose members are evaluated each simulation step by the circuit
     *  engine; the rest are driven by their own event hooks or timers instead. */
    final boolean circuit;

    Family(String bodyMaterial, String label, boolean circuit) {
        this.bodyMaterial = bodyMaterial;
        this.label = label;
        this.circuit = circuit;
    }
}
