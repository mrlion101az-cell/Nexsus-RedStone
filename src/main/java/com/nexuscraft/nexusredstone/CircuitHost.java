package com.nexuscraft.nexusredstone;

/**
 * Everything the circuit engine needs from the outside world, as a narrow interface. The real
 * implementation reads redstone power and entities from Bukkit and writes the output cells; the
 * test suite implements it with a few lambdas and a fake clock, which is how the whole
 * multi-component simulation (a clock feeding a counter feeding a latch, a wireless link, a
 * lag-guard trip) gets exercised without a server.
 */
interface CircuitHost {

    /** False for a component in an unloaded chunk; it is simply skipped. */
    boolean isActive(Component c);

    /** The left/right/back strengths the component currently sees. */
    Inputs readInputs(Component c);

    /** Raw reading for a sensor (count, light, weather code, world time, fill permille). */
    int readSensor(Component c);

    /** Drive the component's output cell to this strength. */
    void emit(Component c, int level);

    /** Engineer level of the component's owner (1 if unknown/offline). */
    int ownerLevel(Component c);

    void event(Component c, CircuitEvent event, String detail);
}
