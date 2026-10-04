package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every placeable NexusRedstone component, with its fixed game-balance shape: which family it
 * belongs to (and so what block it looks like), the Engineer level that unlocks it, the XP its
 * first placement is worth, whether it drives a signal output cell, and the small integer
 * settings it exposes. Same "code holds the fixed shape, config holds the tunable numbers" split
 * the rest of this family uses -- the tunable half lives in {@link RedstoneConfig}.
 */
enum ComponentType {
    // -- Logic gates: left and right are the two inputs, the output cell is in front. --
    AND("and", "AND Gate", Family.LOGIC, 1, 6, true, "On only while both side inputs are on."),
    OR("or", "OR Gate", Family.LOGIC, 1, 6, true, "On while either side input is on."),
    XOR("xor", "XOR Gate", Family.LOGIC, 2, 8, true, "On while exactly one side input is on."),
    NOT("not", "NOT Gate", Family.LOGIC, 1, 6, true, "Inverts the back input."),
    NAND("nand", "NAND Gate", Family.LOGIC, 2, 8, true, "Off only while both side inputs are on."),
    NOR("nor", "NOR Gate", Family.LOGIC, 2, 8, true, "On only while both side inputs are off."),
    XNOR("xnor", "XNOR Gate", Family.LOGIC, 3, 9, true, "On while both side inputs agree."),

    // -- Memory --
    SR_LATCH("latch", "SR Latch", Family.MEMORY, 3, 12, true,
            "Left sets, right resets. Remembers its state, even across restarts.",
            new ParamSpec("setdominant", 0, 1, 0, "1 = set wins when both inputs are on")),
    T_FLIPFLOP("toggle", "T Flip-Flop", Family.MEMORY, 3, 12, true,
            "Each rising edge on the back input flips the output. A left input resets it."),
    COUNTER("counter", "Counter", Family.MEMORY, 5, 18, true,
            "Counts rising edges on the back input. A left input resets it.",
            new ParamSpec("target", 1, 999, 5, "count that fires the output"),
            new ParamSpec("mode", 0, 2, 0, "0 pulse at target, 1 hold at target, 2 analog fill"),
            new ParamSpec("pulse", 1, 40, 4, "pulse length in ticks (mode 0)")),

    // -- Timing --
    ON_DELAY("ondelay", "On-Delay Timer", Family.TIMING, 2, 10, true,
            "Turns on a while after the back input turns on; turns off immediately.",
            new ParamSpec("delay", 1, 6000, 20, "delay in ticks")),
    OFF_DELAY("offdelay", "Off-Delay Timer", Family.TIMING, 2, 10, true,
            "Turns on immediately; turns off a while after the back input turns off.",
            new ParamSpec("delay", 1, 6000, 20, "delay in ticks")),
    PULSE("pulse", "Pulse Shaper", Family.TIMING, 4, 12, true,
            "Any rising edge on the back input becomes one clean fixed-length pulse.",
            new ParamSpec("length", 1, 200, 4, "pulse length in ticks")),
    CLOCK("clock", "Precision Clock", Family.TIMING, 4, 16, true,
            "A real, configurable clock. Back input can gate it, or fire fixed bursts.",
            new ParamSpec("period", 2, 2400, 20, "full cycle in ticks"),
            new ParamSpec("high", 1, 2400, 10, "ticks on per cycle"),
            new ParamSpec("mode", 0, 3, 0, "0 free-run, 1 run while input on, 2 run while input off, 3 burst on rising edge"),
            new ParamSpec("shots", 1, 64, 3, "pulses per burst (mode 3)")),

    // -- Analog --
    AMPLIFIER("amp", "Signal Amplifier", Family.ANALOG, 8, 16, true,
            "Scales the strongest input by a percentage (output is an analog fill cell).",
            new ParamSpec("gain", 10, 1500, 200, "gain in percent")),
    ANALOG_MATH("math", "Analog Math", Family.ANALOG, 10, 20, true,
            "Combines the left and right strengths (output is an analog fill cell).",
            new ParamSpec("op", 0, 4, 0, "0 add, 1 subtract, 2 min, 3 max, 4 average")),
    THRESHOLD("threshold", "Threshold Switch", Family.ANALOG, 6, 14, true,
            "On once the back input reaches a level; hysteresis stops flicker.",
            new ParamSpec("level", 1, 15, 8, "turn-on strength"),
            new ParamSpec("hyst", 0, 7, 0, "extra strength below the level it stays on for")),

    // -- Wireless --
    WIRELESS_TX("tx", "Wireless Transmitter", Family.WIRELESS, 10, 24, false,
            "Broadcasts the strongest of its inputs on a channel.",
            new ParamSpec("tier", 0, 3, 0, "0 local, 1 regional, 2 long-range, 3 cross-dimension"),
            new ParamSpec("shared", 0, 1, 0, "1 = public channel any player can hear")),
    WIRELESS_RX("rx", "Wireless Receiver", Family.WIRELESS, 10, 24, true,
            "Outputs what transmitters on its channel are sending, delayed by distance.",
            new ParamSpec("tier", 0, 3, 0, "receiver tier (the weaker end limits the range)"),
            new ParamSpec("shared", 0, 1, 0, "1 = public channel"),
            new ParamSpec("analog", 0, 1, 0, "1 = analog fill cell, 0 = on/off")),

    // -- Sensors --
    SENSOR_PLAYER("senplayer", "Player Sensor", Family.SENSOR, 7, 14, true,
            "On while a player is within its radius.",
            new ParamSpec("radius", 1, 32, 8, "radius in blocks"),
            new ParamSpec("analog", 0, 1, 0, "1 = strength is the player count")),
    SENSOR_MOB("senmob", "Mob Sensor", Family.SENSOR, 7, 14, true,
            "On while a mob is within its radius.",
            new ParamSpec("radius", 1, 32, 8, "radius in blocks"),
            new ParamSpec("analog", 0, 1, 0, "1 = strength is the mob count"),
            new ParamSpec("hostile", 0, 1, 1, "1 = hostile mobs only")),
    SENSOR_ITEM("senitem", "Item Sensor", Family.SENSOR, 9, 16, true,
            "On while dropped items lie within its radius.",
            new ParamSpec("radius", 1, 16, 4, "radius in blocks"),
            new ParamSpec("analog", 0, 1, 0, "1 = strength is the item count")),
    SENSOR_LIGHT("senlight", "Light Sensor", Family.SENSOR, 7, 12, true,
            "Reads the light level at its block.",
            new ParamSpec("analog", 0, 1, 1, "1 = strength is the light level"),
            new ParamSpec("level", 1, 15, 8, "turn-on light level when not analog")),
    SENSOR_WEATHER("senweather", "Weather Sensor", Family.SENSOR, 7, 12, true,
            "On while it is raining (analog: rain 8, thunder 15).",
            new ParamSpec("analog", 0, 1, 0, "1 = rain 8, thunder 15")),
    SENSOR_TIME("sentime", "Time Sensor", Family.SENSOR, 7, 12, true,
            "Reports the time of day.",
            new ParamSpec("mode", 0, 2, 0, "0 day, 1 night, 2 analog sun height")),
    SENSOR_FILL("senfill", "Fill Sensor", Family.SENSOR, 9, 16, true,
            "Reads how full the container behind it is, like a comparator.",
            new ParamSpec("analog", 0, 1, 1, "1 = analog fill, 0 = on while any item is inside")),

    // -- Programmable --
    PLC("plc", "Programmable Logic Block", Family.PROGRAMMABLE, 25, 40, true,
            "Runs a small boolean/arithmetic expression over a, b, c, s, m, t.",
            new ParamSpec("analog", 0, 1, 1, "1 = analog fill cell, 0 = on/off")),

    // -- Hoppers --
    HOPPER_IRON("hopperiron", "Iron Hopper", Family.HOPPER, 5, 14, false,
            "Moves 4 items per transfer. Has a 3-item filter.",
            new ParamSpec("mode", 0, 1, 0, "0 whitelist, 1 blacklist")),
    HOPPER_GOLD("hoppergold", "Gold Hopper", Family.HOPPER, 12, 24, false,
            "Moves 16 items per transfer. Has a 6-item filter.",
            new ParamSpec("mode", 0, 1, 0, "0 whitelist, 1 blacklist")),
    HOPPER_DIAMOND("hopperdiamond", "Diamond Hopper", Family.HOPPER, 20, 36, false,
            "Moves 32 items per transfer, 9-item filter, vacuums nearby dropped items.",
            new ParamSpec("mode", 0, 1, 0, "0 whitelist, 1 blacklist"),
            new ParamSpec("vacuum", 0, 1, 1, "1 = pick up dropped items")),
    HOPPER_NETHERITE("hoppernetherite", "Netherite Hopper", Family.HOPPER, 32, 60, false,
            "Moves 64 items per transfer, 12-item filter, wide vacuum.",
            new ParamSpec("mode", 0, 1, 0, "0 whitelist, 1 blacklist"),
            new ParamSpec("vacuum", 0, 1, 1, "1 = pick up dropped items")),

    // -- Dispensers and droppers --
    DISPENSER_SMART("smartdispenser", "Smart Dispenser", Family.DISPENSER, 8, 18, false,
            "A dispenser with modes: normal, burst, spray, power.",
            new ParamSpec("mode", 0, 3, 0, "0 normal, 1 burst, 2 spray, 3 power"),
            new ParamSpec("burst", 2, 16, 4, "extra shots (burst mode)"),
            new ParamSpec("gap", 1, 10, 2, "ticks between burst shots")),
    DROPPER_SMART("smartdropper", "Smart Dropper", Family.DISPENSER, 8, 18, false,
            "A dropper with modes: normal, burst, gentle, power.",
            new ParamSpec("mode", 0, 3, 0, "0 normal, 1 burst, 2 gentle, 3 power"),
            new ParamSpec("burst", 2, 16, 4, "extra shots (burst mode)"),
            new ParamSpec("gap", 1, 10, 2, "ticks between burst shots")),

    // -- Item transport network --
    PIPE("pipe", "Transport Pipe", Family.PIPE, 10, 4, false,
            "Connects ports into an item network.",
            new ParamSpec("net", 0, 15, 0, "network colour; only the same colour connects")),
    PORT_IN("portin", "Input Port", Family.PIPE, 10, 14, false,
            "Pulls items from an adjacent container into its network.",
            new ParamSpec("net", 0, 15, 0, "network colour"),
            new ParamSpec("mode", 0, 1, 1, "0 whitelist, 1 blacklist"),
            new ParamSpec("interval", 5, 200, 20, "ticks between pulls")),
    PORT_OUT("portout", "Output Port", Family.PIPE, 10, 14, false,
            "Pushes arriving items into an adjacent container.",
            new ParamSpec("net", 0, 15, 0, "network colour"),
            new ParamSpec("mode", 0, 1, 1, "0 whitelist, 1 blacklist"),
            new ParamSpec("priority", 0, 9, 5, "higher is offered items first")),

    // -- Smart storage --
    SORTER("sorter", "Sorting Barrel", Family.STORAGE, 6, 14, false,
            "A barrel that sorts and merges its contents when closed.",
            new ParamSpec("auto", 0, 1, 1, "1 = sort whenever closed")),

    // -- Movement --
    MEGA_PISTON("megapiston", "Mega Piston", Family.PISTON, 30, 50, false,
            "Pushes a long line of blocks, far past the vanilla limit of 12.",
            new ParamSpec("stroke", 1, 8, 1, "blocks moved per push"),
            new ParamSpec("axis", 0, 2, 0, "0 forward, 1 up, 2 down"),
            new ParamSpec("limit", 1, 64, 24, "blocks it will push (capped by your level)"),
            new ParamSpec("sticky", 0, 1, 0, "1 = pulls the line back when unpowered")),
    BLOCK_MOVER("mover", "Block Mover", Family.MOVER, 35, 60, false,
            "Moves a whole glued-together structure along an axis.",
            new ParamSpec("distance", 1, 32, 4, "blocks per trip"),
            new ParamSpec("axis", 0, 2, 0, "0 forward, 1 up, 2 down"),
            new ParamSpec("speed", 1, 20, 4, "ticks per block (lower is faster)"),
            new ParamSpec("mode", 0, 1, 0, "0 out while powered, back when off; 1 flip on each pulse"));

    private static final Map<String, ComponentType> BY_ID = new LinkedHashMap<>();

    static {
        for (ComponentType t : values()) {
            BY_ID.put(t.id, t);
        }
    }

    final String id;
    final String display;
    final Family family;
    final int minLevel;
    final int xp;
    final boolean hasOutputCell;
    final String description;
    private final ParamSpec[] specs;

    ComponentType(String id, String display, Family family, int minLevel, int xp, boolean hasOutputCell,
                  String description, ParamSpec... specs) {
        this.id = id;
        this.display = display;
        this.family = family;
        this.minLevel = minLevel;
        this.xp = xp;
        this.hasOutputCell = hasOutputCell;
        this.description = description;
        this.specs = specs;
    }

    List<ParamSpec> specs() {
        return List.of(specs);
    }

    ParamSpec spec(String name) {
        for (ParamSpec s : specs) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** Evaluated every simulation step by the circuit engine (as opposed to event/timer driven). */
    boolean isCircuit() {
        return family.circuit;
    }

    boolean isSensor() {
        return family == Family.SENSOR;
    }

    boolean isHopper() {
        return family == Family.HOPPER;
    }

    /** Components that carry an item filter list. */
    boolean hasFilter() {
        return family == Family.HOPPER || this == PORT_IN || this == PORT_OUT;
    }

    /** Components with free text (channel name, expression). */
    boolean usesText() {
        return this == WIRELESS_TX || this == WIRELESS_RX || this == PLC || this == SENSOR_ITEM;
    }

    /** Name of the block this component's item places. Most follow their family's body block;
     *  a few need their own look so players can tell them apart at a glance. */
    String bodyMaterial() {
        switch (this) {
            case DROPPER_SMART:
                return "DROPPER";
            case PORT_IN:
                return "OXIDIZED_COPPER";
            case PORT_OUT:
                return "WEATHERED_COPPER";
            default:
                return family.bodyMaterial;
        }
    }

    /** Components that sit on a vanilla container block whose contents must be kept when the
     *  component is broken (the block's own contents drop normally; only the item changes). */
    boolean keepsVanillaBlock() {
        return family == Family.HOPPER || family == Family.DISPENSER || family == Family.STORAGE;
    }

    /** Components whose behaviour is driven by redstone power on the body block, polled each step. */
    boolean powerDriven() {
        return this == MEGA_PISTON || this == BLOCK_MOVER;
    }

    /** Whether this component's output cell is an analog fill cell rather than an on/off cell,
     *  given its current settings. */
    boolean analogOutput(Map<String, Integer> params) {
        switch (this) {
            case AMPLIFIER:
            case ANALOG_MATH:
                return true;
            case COUNTER:
                return params.getOrDefault("mode", 0) == 2;
            case SENSOR_TIME:
                return params.getOrDefault("mode", 0) == 2;
            case WIRELESS_RX:
            case PLC:
            case SENSOR_FILL:
            case SENSOR_LIGHT:
            case SENSOR_PLAYER:
            case SENSOR_MOB:
            case SENSOR_ITEM:
            case SENSOR_WEATHER:
                return params.getOrDefault("analog", 0) == 1;
            default:
                return false;
        }
    }

    Map<String, Integer> defaultParams() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ParamSpec s : specs) {
            m.put(s.name(), s.def());
        }
        return m;
    }

    static ComponentType byId(String id) {
        return id == null ? null : BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    static List<String> ids() {
        return new ArrayList<>(BY_ID.keySet());
    }

    /** Filter capacity for a hopper tier or port; 0 for everything without a filter. */
    int filterSlots() {
        switch (this) {
            case HOPPER_IRON:
                return 3;
            case HOPPER_GOLD:
                return 6;
            case HOPPER_DIAMOND:
                return 9;
            case HOPPER_NETHERITE:
                return 12;
            case PORT_IN:
            case PORT_OUT:
                return 9;
            default:
                return 0;
        }
    }
}
