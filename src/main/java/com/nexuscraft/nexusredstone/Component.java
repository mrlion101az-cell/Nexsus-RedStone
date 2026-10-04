package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One placed component: its identity and position, its player-set settings, and the small piece
 * of runtime state (a latch's memory, a counter's tally, a timer's start) that its logic needs
 * between simulation steps. Deliberately Bukkit-free -- the world position is just a world name
 * plus coordinates -- so the whole circuit engine can be exercised by plain unit tests.
 */
final class Component {

    final String world;
    final int x;
    final int y;
    final int z;
    final ComponentType type;
    UUID owner;
    Dir facing;
    final Map<String, Integer> params;
    String text = "";
    final List<String> filter = new ArrayList<>();

    // ---- runtime state (mostly persisted so latches/counters survive a restart) ----
    int output;
    boolean state;
    int counter;
    int memory;
    int prevLeft;
    int prevRight;
    int prevBack;

    // ---- runtime state that is intentionally NOT persisted ----
    long timerStart = -1;
    long pulseUntil;
    boolean pulseActive;
    long clockEpoch = -1;
    int sensorRaw;
    long lastSensorTick = Long.MIN_VALUE;
    long lagTrippedUntil;
    Expression compiled;
    String compiledSource;
    String compileError;
    long cooldownUntil;
    boolean extended;

    Component(String world, int x, int y, int z, ComponentType type, UUID owner, Dir facing) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.owner = owner;
        this.facing = facing == null ? Dir.NORTH : facing;
        this.params = new LinkedHashMap<>(type.defaultParams());
    }

    String key() {
        return keyOf(world, x, y, z);
    }

    static String keyOf(String world, int x, int y, int z) {
        return world + ":" + x + "," + y + "," + z;
    }

    int param(String name) {
        Integer v = params.get(name);
        if (v != null) {
            return v;
        }
        ParamSpec s = type.spec(name);
        return s == null ? 0 : s.def();
    }

    /** Sets a validated, clamped setting; returns the stored value, or -1 (never a legal stored
     *  value for any spec here) if this component has no such setting. */
    int setParam(String name, int value) {
        ParamSpec s = type.spec(name);
        if (s == null) {
            return -1;
        }
        int v = s.clamp(value);
        params.put(name, v);
        normalizeClock();
        return v;
    }

    /** Keeps a clock's "on" time within its period so the settings can never contradict. */
    void normalizeClock() {
        if (type == ComponentType.CLOCK) {
            int period = param("period");
            int high = Math.min(param("high"), period);
            params.put("high", Math.max(1, high));
        }
    }

    boolean analogOutput() {
        return type.analogOutput(params);
    }

    /** Resets only the non-persisted runtime state, e.g. after a settings change. */
    void resetRuntime() {
        timerStart = -1;
        pulseUntil = 0;
        pulseActive = false;
        clockEpoch = -1;
        compiled = null;
        compiledSource = null;
        compileError = null;
        lastSensorTick = Long.MIN_VALUE;
    }

    /** Squared distance to another component, or -1 if in different worlds. */
    double distanceSq(Component other) {
        if (!world.equals(other.world)) {
            return -1;
        }
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return type.display + "@" + key();
    }
}
