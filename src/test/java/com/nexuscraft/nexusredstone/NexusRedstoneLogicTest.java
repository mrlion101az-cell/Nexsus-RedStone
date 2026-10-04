package com.nexuscraft.nexusredstone;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;

/**
 * Plain-main, JDK-only test suite for NexusRedstone's pure logic -- same convention as every other
 * plugin in this family (no JUnit dependency, an {@code expect} helper, nonzero exit on any
 * failure). Everything exercised here is Bukkit-free by design; the Bukkit-facing glue (placing
 * blocks, reading power, spawning displays) is deliberately NOT covered and is listed in the
 * README's "known-uncertain real APIs" section as needing a real-server playtest.
 */
public final class NexusRedstoneLogicTest {

    private static int passed;
    private static int failed;

    private static void expect(boolean condition, String message) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + message);
        }
    }

    private static final UUID U1 = UUID.nameUUIDFromBytes("u1".getBytes(StandardCharsets.UTF_8));
    private static final UUID U2 = UUID.nameUUIDFromBytes("u2".getBytes(StandardCharsets.UTF_8));

    private static Component comp(ComponentType t, int x) {
        return new Component("w", x, 64, 0, t, U1, Dir.NORTH);
    }

    private static Component comp(ComponentType t, int x, int y, int z) {
        return new Component("w", x, y, z, t, U1, Dir.NORTH);
    }

    private static int eval(Component c, int l, int r, int b, long now) {
        return ComponentLogic.evaluate(c, new Inputs(l, r, b, 0), new EvalContext(now, 100, 200));
    }

    public static void main(String[] args) {
        testDir();
        testComponentTypes();
        testAnalogCell();
        testGates();
        testMemory();
        testTiming();
        testAnalogAndSensors();
        testExpression();
        testPlc();
        testWireless();
        testLagGuard();
        testEngine();
        testSkill();
        testFilterAndHoppers();
        testTransport();
        testSmartStorage();
        testDispensers();
        testPush();
        testStructure();
        testBlueprint();
        testTrace();
        testDialogue();
        testRegistry();
        testBlueprintCost();
        testRecipeBook();
        testParamCycle();
        testTransfer();
        testSorter();
        testAnnouncerCooldown();
        testStructureWithOffset();
        testManual();

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ Dir
    static void testDir() {
        expect(Dir.fromYaw(0f) == Dir.SOUTH, "yaw 0 faces south");
        expect(Dir.fromYaw(90f) == Dir.WEST, "yaw 90 faces west");
        expect(Dir.fromYaw(180f) == Dir.NORTH, "yaw 180 faces north");
        expect(Dir.fromYaw(-90f) == Dir.EAST, "yaw -90 faces east");
        expect(Dir.fromYaw(270f) == Dir.EAST, "yaw 270 faces east");
        expect(Dir.fromYaw(719f) == Dir.SOUTH, "yaw wraps");
        expect(Dir.NORTH.left() == Dir.WEST && Dir.NORTH.right() == Dir.EAST, "north left/right");
        expect(Dir.EAST.back() == Dir.WEST, "east back is west");
        for (Dir d : Dir.values()) {
            expect(d.left().right() == d && d.back().back() == d, "dir symmetries for " + d);
        }
        expect(Dir.parse("south", Dir.NORTH) == Dir.SOUTH && Dir.parse("???", Dir.EAST) == Dir.EAST && Dir.parse(null, Dir.WEST) == Dir.WEST, "Dir.parse");
    }

    // ------------------------------------------------------------------ catalog invariants
    static void testComponentTypes() {
        Set<String> ids = new HashSet<>();
        for (ComponentType t : ComponentType.values()) {
            expect(ids.add(t.id), "unique id " + t.id);
            expect(ComponentType.byId(t.id) == t, "byId round trip " + t.id);
            expect(t.minLevel >= 1 && t.minLevel <= 50, "min level in range for " + t.id);
            for (ParamSpec s : t.specs()) {
                expect(s.min() <= s.def() && s.def() <= s.max(), "default in range " + t.id + "." + s.name());
            }
            if (t.hasOutputCell) {
                expect(t.isCircuit(), "output-cell types are circuit types: " + t.id);
            }
            if (t.isHopper()) {
                expect(t.filterSlots() > 0, "hopper has filter slots: " + t.id);
            }
        }
        expect(ComponentType.byId("CLOCK") == ComponentType.CLOCK, "byId case-insensitive");
        expect(ComponentType.byId("nope") == null && ComponentType.byId(null) == null, "byId unknown");
        expect(!ComponentType.WIRELESS_TX.hasOutputCell, "tx has no output cell");
        expect(ComponentType.AMPLIFIER.analogOutput(new HashMap<>()), "amplifier is analog");
        expect(!ComponentType.AND.analogOutput(new HashMap<>()), "and is digital");
        Component counter = comp(ComponentType.COUNTER, 0);
        expect(!counter.analogOutput(), "counter default mode is digital");
        counter.setParam("mode", 2);
        expect(counter.analogOutput(), "counter mode 2 is analog");
        Component clock = comp(ComponentType.CLOCK, 0);
        clock.setParam("period", 6);
        expect(clock.param("high") <= 6, "clock high is normalised to the period");
        expect(clock.setParam("period", 99999) == 2400, "param clamps to max");
        expect(clock.setParam("bogus", 1) == -1, "unknown param rejected");
        expect(new ParamSpec("x", 1, 3, 1, "").next(3) == 1, "param cycle wraps");
        Component d = comp(ComponentType.CLOCK, 0);
        d.setParam("period", 40);
        expect(d.key().equals("w:0,64,0"), "component key format");
    }

    // ------------------------------------------------------------------ analog cell
    static void testAnalogCell() {
        int slots = 27;
        int max = 64;
        expect(AnalogCellMath.itemsForSignal(0, slots, max) == 0 && AnalogCellMath.signalFor(0, slots, max) == 0, "analog 0 is empty");
        for (int s = 0; s <= 15; s++) {
            int items = AnalogCellMath.itemsForSignal(s, slots, max);
            expect(AnalogCellMath.signalFor(items, slots, max) == s, "analog round trip for strength " + s + " (items=" + items + ")");
            int sum = 0;
            for (int n : AnalogCellMath.distribute(items, slots, max)) {
                sum += n;
                expect(n <= max, "no slot over a stack");
            }
            expect(sum == items, "distribute conserves items for " + s);
        }
        expect(AnalogCellMath.itemsForSignal(15, slots, max) == slots * max, "full strength is a full container");
        expect(AnalogCellMath.itemsForSignal(99, slots, max) == slots * max, "over-range clamps");
        expect(AnalogCellMath.itemsForSignal(-4, slots, max) == 0, "negative clamps");
        // margin: one item either side of the chosen count must still read the same strength
        for (int s = 2; s <= 14; s++) {
            int items = AnalogCellMath.itemsForSignal(s, slots, max);
            expect(AnalogCellMath.signalFor(items - 1, slots, max) >= s - 1 && AnalogCellMath.signalFor(items + 1, slots, max) <= s + 1,
                    "analog count is not razor-thin at " + s);
            expect(AnalogCellMath.signalFor(items - 1, slots, max) == s, "safety margin keeps strength " + s + " when one item is lost");
        }
    }

    // ------------------------------------------------------------------ gates
    static void testGates() {
        Object[][] table = {
                {ComponentType.AND, new boolean[] {false, false, false, true}},
                {ComponentType.OR, new boolean[] {false, true, true, true}},
                {ComponentType.XOR, new boolean[] {false, true, true, false}},
                {ComponentType.NAND, new boolean[] {true, true, true, false}},
                {ComponentType.NOR, new boolean[] {true, false, false, false}},
                {ComponentType.XNOR, new boolean[] {true, false, false, true}},
        };
        for (Object[] row : table) {
            ComponentType t = (ComponentType) row[0];
            boolean[] want = (boolean[]) row[1];
            for (int i = 0; i < 4; i++) {
                int l = (i & 2) != 0 ? 15 : 0;
                int r = (i & 1) != 0 ? 7 : 0;
                int got = eval(comp(t, 0), l, r, 0, 0);
                expect(got == (want[i] ? 15 : 0), t + " truth row " + i);
            }
        }
        expect(eval(comp(ComponentType.NOT, 0), 0, 0, 0, 0) == 15, "NOT of off is on");
        expect(eval(comp(ComponentType.NOT, 0), 0, 0, 3, 0) == 0, "NOT of any signal is off");
        expect(eval(comp(ComponentType.AND, 0), 15, 0, 15, 0) == 0, "AND ignores the back input");
    }

    // ------------------------------------------------------------------ memory
    static void testMemory() {
        Component latch = comp(ComponentType.SR_LATCH, 0);
        expect(eval(latch, 0, 0, 0, 0) == 0, "latch starts off");
        expect(eval(latch, 15, 0, 0, 2) == 15, "set turns it on");
        expect(eval(latch, 0, 0, 0, 4) == 15, "latch remembers");
        expect(eval(latch, 0, 15, 0, 6) == 0, "reset turns it off");
        expect(eval(latch, 0, 0, 0, 8) == 0, "latch remembers off");
        expect(eval(latch, 15, 15, 0, 10) == 0, "reset dominant by default");
        latch.setParam("setdominant", 1);
        expect(eval(latch, 15, 15, 0, 12) == 15, "set dominant when configured");

        Component t = comp(ComponentType.T_FLIPFLOP, 0);
        expect(eval(t, 0, 0, 15, 0) == 15, "toggle flips on at first rising edge");
        expect(eval(t, 0, 0, 15, 2) == 15, "held input does not flip again");
        expect(eval(t, 0, 0, 0, 4) == 15, "falling edge does not flip");
        expect(eval(t, 0, 0, 15, 6) == 0, "second rising edge flips off");
        expect(eval(t, 0, 0, 0, 8) == 0 && eval(t, 0, 0, 15, 10) == 15, "toggles again");
        expect(eval(t, 15, 0, 15, 12) == 0, "reset clears the toggle");

        Component c = comp(ComponentType.COUNTER, 0);
        c.setParam("target", 3);
        c.setParam("pulse", 4);
        int[] outs = new int[8];
        int[] backs = {15, 0, 15, 0, 15, 0, 0, 0};
        for (int i = 0; i < 8; i++) {
            outs[i] = eval(c, 0, 0, backs[i], i * 2L);
        }
        expect(outs[0] == 0 && outs[2] == 0, "counter quiet before target");
        expect(outs[4] == 15 && outs[5] == 15, "counter pulses when target reached (" + outs[4] + "," + outs[5] + ")");
        expect(outs[6] == 0 && outs[7] == 0, "counter pulse ends and counter restarts");
        expect(c.counter == 0, "counter restarts after firing");

        Component hold = comp(ComponentType.COUNTER, 0);
        hold.setParam("target", 2);
        hold.setParam("mode", 1);
        eval(hold, 0, 0, 15, 0);
        eval(hold, 0, 0, 0, 2);
        expect(eval(hold, 0, 0, 15, 4) == 15, "hold mode stays on at target");
        expect(eval(hold, 0, 0, 0, 6) == 15, "hold mode stays on");
        expect(eval(hold, 15, 0, 0, 8) == 0 && hold.counter == 0, "reset clears hold mode");

        Component analog = comp(ComponentType.COUNTER, 0);
        analog.setParam("target", 15);
        analog.setParam("mode", 2);
        for (int i = 0; i < 5; i++) {
            eval(analog, 0, 0, 15, i * 4L);
            eval(analog, 0, 0, 0, i * 4L + 2);
        }
        expect(eval(analog, 0, 0, 0, 100) == 5, "analog counter shows count/target * 15");
        expect(analog.analogOutput(), "analog counter uses an analog cell");
    }

    // ------------------------------------------------------------------ timing
    static void testTiming() {
        Component on = comp(ComponentType.ON_DELAY, 0);
        on.setParam("delay", 10);
        expect(eval(on, 0, 0, 15, 100) == 0, "on-delay waits");
        expect(eval(on, 0, 0, 15, 108) == 0, "on-delay still waiting");
        expect(eval(on, 0, 0, 15, 110) == 15, "on-delay fires after the delay");
        expect(eval(on, 0, 0, 0, 112) == 0, "on-delay drops immediately");
        expect(eval(on, 0, 0, 15, 114) == 0, "on-delay restarts its wait");

        Component off = comp(ComponentType.OFF_DELAY, 0);
        off.setParam("delay", 10);
        expect(eval(off, 0, 0, 15, 100) == 15, "off-delay on immediately");
        expect(eval(off, 0, 0, 0, 102) == 15, "off-delay holds");
        expect(eval(off, 0, 0, 0, 110) == 15, "off-delay still holds");
        expect(eval(off, 0, 0, 0, 112) == 0, "off-delay releases after the delay");
        expect(eval(off, 0, 0, 0, 120) == 0, "off-delay stays off");

        Component p = comp(ComponentType.PULSE, 0);
        p.setParam("length", 6);
        expect(eval(p, 0, 0, 0, 0) == 0, "pulse idle");
        expect(eval(p, 0, 0, 15, 2) == 15, "pulse starts on rising edge");
        expect(eval(p, 0, 0, 15, 6) == 15, "pulse holds for its length");
        expect(eval(p, 0, 0, 15, 8) == 0, "pulse ends even though the input stays on");
        expect(eval(p, 0, 0, 15, 10) == 0, "no retrigger while input stays on");

        Component clk = comp(ComponentType.CLOCK, 0);
        clk.setParam("period", 8);
        clk.setParam("high", 4);
        int[] want = {15, 15, 0, 0, 15, 15, 0, 0};
        for (int i = 0; i < 8; i++) {
            expect(eval(clk, 0, 0, 0, i * 2L) == want[i], "free-run clock step " + i);
        }

        Component gated = comp(ComponentType.CLOCK, 0);
        gated.setParam("period", 8);
        gated.setParam("high", 4);
        gated.setParam("mode", 1);
        expect(eval(gated, 0, 0, 0, 0) == 0, "gated clock idle with input off");
        expect(eval(gated, 0, 0, 15, 2) == 15, "gated clock starts with input on");
        expect(eval(gated, 0, 0, 15, 8) == 0, "gated clock runs its phase");
        expect(eval(gated, 0, 0, 0, 10) == 0, "gated clock stops with input off");
        Component inv = comp(ComponentType.CLOCK, 0);
        inv.setParam("mode", 2);
        expect(eval(inv, 0, 0, 0, 0) == 15 && eval(inv, 0, 0, 15, 2) == 0, "mode 2 runs only while input is off");

        Component burst = comp(ComponentType.CLOCK, 0);
        burst.setParam("period", 6);
        burst.setParam("high", 2);
        burst.setParam("mode", 3);
        burst.setParam("shots", 2);
        expect(eval(burst, 0, 0, 0, 0) == 0, "burst clock idle");
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            seen.add(eval(burst, 0, 0, i == 0 ? 15 : 0, 10 + i * 2L));
        }
        // rising edge at t=10: shots at phase 0-2 and 6-8, then done at 22
        expect(seen.get(0) == 15 && seen.get(1) == 0 && seen.get(3) == 15 && seen.get(5) == 0 && seen.get(9) == 0,
                "burst fires exactly two pulses: " + seen);
        int pulses = 0;
        for (int i = 1; i < seen.size(); i++) {
            if (seen.get(i) > 0 && seen.get(i - 1) == 0) {
                pulses++;
            }
        }
        expect(pulses == 1 && seen.get(0) == 15, "burst has two pulses total (one counted after the first): " + seen);
    }

    // ------------------------------------------------------------------ analog + sensors
    static void testAnalogAndSensors() {
        Component amp = comp(ComponentType.AMPLIFIER, 0);
        expect(eval(amp, 0, 0, 5, 0) == 10, "amplifier doubles by default");
        amp.setParam("gain", 50);
        expect(eval(amp, 0, 6, 0, 0) == 3, "amplifier halves");
        amp.setParam("gain", 1500);
        expect(eval(amp, 15, 0, 0, 0) == 15, "amplifier clamps at 15");
        expect(eval(amp, 0, 0, 0, 0) == 0, "amplifier of nothing is nothing");

        Component m = comp(ComponentType.ANALOG_MATH, 0);
        expect(eval(m, 5, 4, 0, 0) == 9 && eval(m, 10, 9, 0, 0) == 15, "math add clamps");
        m.setParam("op", 1);
        expect(eval(m, 9, 4, 0, 0) == 5 && eval(m, 2, 9, 0, 0) == 0, "math subtract floors at 0");
        m.setParam("op", 2);
        expect(eval(m, 9, 4, 0, 0) == 4, "math min");
        m.setParam("op", 3);
        expect(eval(m, 9, 4, 0, 0) == 9, "math max");
        m.setParam("op", 4);
        expect(eval(m, 9, 4, 0, 0) == 6, "math average");

        Component th = comp(ComponentType.THRESHOLD, 0);
        th.setParam("level", 8);
        th.setParam("hyst", 3);
        expect(eval(th, 0, 0, 7, 0) == 0, "threshold below level is off");
        expect(eval(th, 0, 0, 8, 2) == 15, "threshold turns on at level");
        expect(eval(th, 0, 0, 6, 4) == 15, "hysteresis holds it on");
        expect(eval(th, 0, 0, 4, 6) == 0, "threshold turns off below level-hyst");

        expect(SensorLogic.fillSignal(0) == 0 && SensorLogic.fillSignal(1) == 1 && SensorLogic.fillSignal(1000) == 15, "fill signal ends");
        expect(SensorLogic.fillSignal(500) == 8, "half full reads 8");
        expect(SensorLogic.timeSignal(0, 6000) == 15 && SensorLogic.timeSignal(0, 14000) == 0, "day sensor");
        expect(SensorLogic.timeSignal(1, 14000) == 15 && SensorLogic.timeSignal(1, 6000) == 0, "night sensor");
        expect(SensorLogic.timeSignal(2, 6000) == 15 && SensorLogic.timeSignal(2, 18000) == 0, "analog sun: noon max, midnight zero");
        expect(SensorLogic.timeSignal(2, 0) == 0, "analog sun at dawn is low");
        expect(SensorLogic.timeSignal(2, 30000) == SensorLogic.timeSignal(2, 6000), "time wraps by day");

        Component sp = comp(ComponentType.SENSOR_PLAYER, 0);
        expect(SensorLogic.signal(sp, 0) == 0 && SensorLogic.signal(sp, 3) == 15, "digital player sensor");
        sp.setParam("analog", 1);
        expect(SensorLogic.signal(sp, 3) == 3 && SensorLogic.signal(sp, 40) == 15, "analog player sensor counts");
        Component sl = comp(ComponentType.SENSOR_LIGHT, 0);
        sl.setParam("analog", 0);
        sl.setParam("level", 9);
        expect(SensorLogic.signal(sl, 8) == 0 && SensorLogic.signal(sl, 9) == 15, "digital light threshold");
        Component sw = comp(ComponentType.SENSOR_WEATHER, 0);
        expect(SensorLogic.signal(sw, SensorLogic.WEATHER_CLEAR) == 0 && SensorLogic.signal(sw, SensorLogic.WEATHER_RAIN) == 15, "weather digital");
        sw.setParam("analog", 1);
        expect(SensorLogic.signal(sw, SensorLogic.WEATHER_RAIN) == 8 && SensorLogic.signal(sw, SensorLogic.WEATHER_THUNDER) == 15, "weather analog");
        Component sf = comp(ComponentType.SENSOR_FILL, 0);
        expect(SensorLogic.signal(sf, 1) == 1 && SensorLogic.signal(sf, 0) == 0, "fill sensor analog by default");
        sf.setParam("analog", 0);
        expect(SensorLogic.signal(sf, 1) == 15, "fill sensor digital");
    }

    // ------------------------------------------------------------------ expression
    private static int ev(String src, int a, int b, int c, int s, int m) {
        Expression.Result r = Expression.compile(src, 200, 300);
        if (!r.ok()) {
            System.out.println("  (compile error for '" + src + "': " + r.error() + ")");
            return -999;
        }
        long[] v = new long[Expression.VAR_COUNT];
        v[Expression.VAR_A] = a;
        v[Expression.VAR_B] = b;
        v[Expression.VAR_C] = c;
        v[Expression.VAR_S] = s;
        v[Expression.VAR_M] = m;
        return r.expr().evalOutput(v);
    }

    static void testExpression() {
        expect(ev("a+b", 5, 3, 0, 0, 0) == 8, "add");
        expect(ev("2+3*4", 0, 0, 0, 0, 0) == 14, "precedence mul over add");
        expect(ev("(2+3)*4", 0, 0, 0, 0, 0) == 15, "parentheses and output clamp");
        expect(ev("a-b", 3, 5, 0, 0, 0) == 0, "negative clamps to 0");
        expect(ev("a>b", 5, 3, 0, 0, 0) == 15 && ev("a>b", 3, 5, 0, 0, 0) == 0, "boolean root drives 15");
        expect(ev("(a>b)+1", 5, 3, 0, 0, 0) == 2, "comparison is 1 inside arithmetic");
        expect(ev("a==b", 4, 4, 0, 0, 0) == 15 && ev("a!=b", 4, 4, 0, 0, 0) == 0, "equality");
        expect(ev("a<=b", 4, 4, 0, 0, 0) == 15 && ev("a>=b", 3, 4, 0, 0, 0) == 0, "relational");
        expect(ev("a&&b", 1, 1, 0, 0, 0) == 15 && ev("a&&b", 1, 0, 0, 0, 0) == 0, "and");
        expect(ev("a||b", 0, 1, 0, 0, 0) == 15 && ev("a||b", 0, 0, 0, 0, 0) == 0, "or");
        expect(ev("!a", 0, 0, 0, 0, 0) == 15 && ev("!a", 3, 0, 0, 0, 0) == 0, "not");
        expect(ev("a and not b", 2, 0, 0, 0, 0) == 15 && ev("a or b", 0, 0, 0, 0, 0) == 0, "word operators");
        expect(ev("min(a,b)", 9, 4, 0, 0, 0) == 4 && ev("max(a,b)", 9, 4, 0, 0, 0) == 9, "min and max");
        expect(ev("abs(a-b)", 4, 9, 0, 0, 0) == 5 && ev("abs(0-7)", 0, 0, 0, 0, 0) == 7, "abs");
        expect(ev("clamp(a,3,9)", 1, 0, 0, 0, 0) == 3 && ev("clamp(a,3,9)", 20, 0, 0, 0, 0) == 9 && ev("clamp(a,9,3)", 5, 0, 0, 0, 0) == 5, "clamp (bounds in either order)");
        expect(ev("if(a>b,a,b)", 5, 9, 0, 0, 0) == 9 && ev("if(a>b,a,b)", 11, 9, 0, 0, 0) == 11, "if");
        expect(ev("10/0", 0, 0, 0, 0, 0) == 0 && ev("10%0", 0, 0, 0, 0, 0) == 0, "division by zero is zero, not a crash");
        expect(ev("7%4", 0, 0, 0, 0, 0) == 3 && ev("9/2", 0, 0, 0, 0, 0) == 4, "mod and integer division");
        expect(ev("s", 0, 0, 0, 6, 0) == 6 && ev("m", 0, 0, 0, 0, 5) == 5, "state and memory variables");
        expect(ev("9999999*9999999", 0, 0, 0, 0, 0) == 15, "huge multiplication clamps instead of overflowing");
        expect(ev("-a", 4, 0, 0, 0, 0) == 0, "unary minus");
        expect(ev("A + B", 2, 3, 0, 0, 0) == 5, "variables are case-insensitive");

        Expression.Result mem = Expression.compile("m>=3; m+1", 50, 100);
        expect(mem.ok() && mem.expr().hasMemory(), "second expression is the memory update");
        long[] v = new long[Expression.VAR_COUNT];
        v[Expression.VAR_M] = 2;
        expect(mem.expr().evalOutput(v) == 0 && mem.expr().evalMemory(v) == 3, "memory expression evaluates");
        v[Expression.VAR_M] = 3;
        expect(mem.expr().evalOutput(v) == 15, "output sees the register");
        v[Expression.VAR_M] = 9999;
        expect(mem.expr().evalMemory(v) == 9999, "memory clamps at 9999");

        String[] bad = {"", "   ", "1+", "(1+2", "a $ b", "foo", "min(1)", "max(1,2,3)", "if(1,2)", "1 2", "a;b;c", "a b", "bogus(1)", "99999999999"};
        for (String src : bad) {
            Expression.Result r = Expression.compile(src, 100, 100);
            expect(!r.ok() && r.error() != null, "rejects '" + src + "' with a message");
        }
        expect(!Expression.compile("a+b+c+a+b+c", 5, 100).ok(), "node limit enforced");
        expect(Expression.compile("a+b", 3, 100).ok(), "exactly at node limit is fine");
        expect(!Expression.compile("a+a+a+a+a+a", 100, 5).ok(), "length limit enforced");
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            deep.append("!");
        }
        expect(!Expression.compile(deep + "a", 1000, 1000).ok(), "deep nesting is rejected");
        StringBuilder parens = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            parens.append("(");
        }
        parens.append("a");
        for (int i = 0; i < 40; i++) {
            parens.append(")");
        }
        Expression.Result nested = Expression.compile(parens.toString(), 1000, 1000);
        expect(nested.ok() || nested.error() != null, "deep parentheses never crash the compiler");
    }

    static void testPlc() {
        Component plc = comp(ComponentType.PLC, 0);
        plc.text = "a&&b";
        expect(eval(plc, 5, 5, 0, 0) == 15, "plc runs its expression");
        expect(eval(plc, 5, 0, 0, 2) == 0, "plc recomputes");
        plc.text = "a+b";
        expect(eval(plc, 2, 3, 0, 4) == 5, "plc recompiles when the text changes");
        plc.setParam("analog", 0);
        expect(eval(plc, 2, 3, 0, 6) == 15, "digital plc output is 0 or 15");
        plc.text = "oops(";
        expect(eval(plc, 2, 3, 0, 8) == 0 && plc.compileError != null, "bad program outputs 0 and records the error");
        plc.text = "m>=2; m+1";
        plc.setParam("analog", 0);
        plc.memory = 0;
        int[] seq = new int[4];
        for (int i = 0; i < 4; i++) {
            seq[i] = eval(plc, 0, 0, 0, 10 + i * 2L);
        }
        expect(seq[0] == 0 && seq[1] == 0 && seq[2] == 15 && seq[3] == 15, "plc memory register counts steps");
        EvalContext tiny = new EvalContext(0, 2, 200);
        Component limited = comp(ComponentType.PLC, 0);
        limited.text = "a+b+c+a";
        ComponentLogic.evaluate(limited, new Inputs(1, 1, 1, 0), tiny);
        expect(limited.compileError != null && limited.compileError.contains("complex"), "level-scaled operation limit reaches the player");
    }

    // ------------------------------------------------------------------ wireless
    static void testWireless() {
        RedstoneConfig cfg = new RedstoneConfig(null);
        WirelessNetwork net = new WirelessNetwork(cfg);
        Component tx = comp(ComponentType.WIRELESS_TX, 0);
        tx.text = "Gate";
        Component rx = comp(ComponentType.WIRELESS_RX, 20);
        rx.text = "gate";
        net.publish(tx, 0, 90, 0);
        net.publish(tx, 15, 100, 0);
        expect(net.read(rx, 100, 0) == 0, "signal has a propagation delay");
        net.publish(tx, 15, 101, 0);
        expect(net.read(rx, 101, 0) == 15, "signal arrives after the delay");
        expect(net.read(rx, 100000, 0) == 0, "stale transmitter falls silent");
        net.publish(tx, 15, 100000, 0);

        Component far = comp(ComponentType.WIRELESS_RX, 40);
        far.text = "gate";
        expect(net.read(far, 100001, 0) == 0, "out of local range hears nothing");
        net.publish(tx, 15, 100002, 1);
        expect(net.read(far, 100003, 0) == 0, "range limited by the weaker end");
        expect(net.read(far, 100003, 1) == 15, "regional tiers reach 40 blocks");

        Component other = new Component("w", 5, 64, 0, ComponentType.WIRELESS_RX, U2, Dir.NORTH);
        other.text = "gate";
        net.publish(tx, 15, 100004, 0);
        expect(net.read(other, 100005, 0) == 0, "private channels are scoped to their owner");
        tx.setParam("shared", 1);
        other.setParam("shared", 1);
        net.publish(tx, 15, 100006, 0);
        expect(net.read(other, 100007, 0) == 15, "shared channels cross owners");

        Component wrongChannel = comp(ComponentType.WIRELESS_RX, 3);
        wrongChannel.text = "other";
        expect(net.read(wrongChannel, 100007, 0) == 0, "different channel hears nothing");
        expect(WirelessNetwork.normalize("  ", 16).equals("default") && WirelessNetwork.normalize("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 16).length() == 16, "channel normalisation");

        Component worldRx = new Component("nether", 0, 64, 0, ComponentType.WIRELESS_RX, U1, Dir.NORTH);
        worldRx.text = "gate";
        worldRx.setParam("shared", 1);
        net.publish(tx, 15, 100008, 3);
        net.publish(tx, 15, 100009, 3);
        expect(net.read(worldRx, 100009, 3) == 0, "a cross-dimension signal takes a few ticks to arrive");
        net.publish(tx, 15, 100013, 3);
        expect(net.read(worldRx, 100013, 3) == 15, "top-tier devices cross dimensions");
        net.publish(tx, 15, 100010, 2);
        expect(net.read(worldRx, 100011, 3) == 0, "lower tiers cannot cross dimensions");

        Component tx2 = comp(ComponentType.WIRELESS_TX, 1);
        tx2.text = "gate";
        tx2.setParam("shared", 1);
        net.publish(tx, 5, 100012, 0);
        net.publish(tx2, 9, 100012, 0);
        expect(net.read(other, 100014, 0) == 9, "strongest transmitter wins");
        net.remove(tx2.key());
        expect(net.read(other, 100014, 0) == 5, "removing a transmitter silences it");
        expect(net.latencyTicks(64) == 1 && net.latencyTicks(65) == 2 && net.latencyTicks(0) == 0, "latency maths");
        expect(!net.links(List.of(rx, other), 100014, c -> 0).isEmpty(), "links are reported for the visualizer");
    }

    // ------------------------------------------------------------------ lag guard
    static void testLagGuard() {
        RedstoneConfig cfg = new RedstoneConfig(null);
        LagGuard lag = new LagGuard(cfg);
        ComponentRegistry reg = new ComponentRegistry();
        Component osc = comp(ComponentType.NOT, 0);
        reg.add(osc);
        long tripAt = -1;
        for (long now = 0; now < 400; now += 2) {
            lag.recordChange(osc, now, 1);
            if (!lag.drainTrips().isEmpty()) {
                tripAt = now;
                break;
            }
        }
        expect(tripAt > 0 && tripAt <= 110, "an output flipping every step trips within ~5 seconds (tripped at " + tripAt + ")");
        expect(lag.check(osc, tripAt + 2) == LagGuard.Verdict.TRIPPED, "tripped component stays tripped");
        expect(lag.check(osc, tripAt + cfg.lagTripCooldownSeconds * 20L + 2) != LagGuard.Verdict.TRIPPED, "trip cools down");

        Component calm = comp(ComponentType.CLOCK, 5);
        boolean calmTrip = false;
        for (long now = 0; now < 2000; now += 40) {
            lag.recordChange(calm, now, 1);
            if (!lag.drainTrips().isEmpty()) {
                calmTrip = true;
            }
        }
        expect(!calmTrip, "a sane 1 Hz clock never trips");

        RedstoneConfig tight = new RedstoneConfig(null);
        tight.lagChunkBudgetPerSecond = 5;
        tight.lagMaxTogglesPerWindow = 1000;
        LagGuard lag2 = new LagGuard(tight);
        ComponentRegistry reg2 = new ComponentRegistry();
        Component busy = comp(ComponentType.NOT, 0);
        Component quiet = comp(ComponentType.NOT, 1);
        reg2.add(busy);
        reg2.add(quiet);
        boolean throttled = false;
        Component offender = null;
        for (long now = 0; now < 400 && offender == null; now += 2) {
            lag2.recordChange(busy, now, 1);
            if (now % 20 == 0) {
                lag2.recordChange(quiet, now, 1);
            }
            if (lag2.isThrottled(LagGuard.chunkKey(busy), now)) {
                throttled = true;
            }
            offender = lag2.resolveWorstOffender(reg2, now);
        }
        expect(throttled, "an over-budget chunk is throttled");
        expect(offender == busy, "the chunk-level trip picks the worst offender");
        boolean sawSkip = false;
        RedstoneConfig t3 = new RedstoneConfig(null);
        t3.lagChunkBudgetPerSecond = 5;
        t3.lagMaxTogglesPerWindow = 1000;
        LagGuard lag3 = new LagGuard(t3);
        Component c3 = comp(ComponentType.NOT, 2);
        for (long now = 0; now < 60; now += 2) {
            lag3.recordChange(c3, now, 1);
            if (lag3.check(c3, now) == LagGuard.Verdict.SKIP) {
                sawSkip = true;
            }
        }
        expect(sawSkip, "throttled chunks skip most steps");

        RedstoneConfig hi = new RedstoneConfig(null);
        hi.lagChunkBudgetPerSecond = 100;
        LagGuard lagHi = new LagGuard(hi);
        for (int i = 0; i < 3500; i++) {
            lagHi.recordVanilla("w:0,0", 5);
        }
        lagHi.recordVanilla("w:0,0", 25);
        List<LagGuard.HotChunk> alerts = lagHi.drainVanillaAlerts();
        expect(alerts.size() == 1 && alerts.get(0).perSecond() >= 3000, "heavy vanilla redstone activity raises one alert");
        expect(lagHi.drainVanillaAlerts().isEmpty(), "alerts drain");
        expect(!lagHi.hottest(5).isEmpty() && lagHi.hottest(5).get(0).chunkKey().equals("w:0,0"), "hottest chunk is listed");

        RedstoneConfig off = new RedstoneConfig(null);
        off.lagEnabled = false;
        LagGuard disabled = new LagGuard(off);
        Component n = comp(ComponentType.NOT, 9);
        for (long now = 0; now < 400; now += 2) {
            disabled.recordChange(n, now, 1);
        }
        expect(disabled.drainTrips().isEmpty() && disabled.check(n, 10) == LagGuard.Verdict.RUN, "disabled guard never interferes");
        expect(LagGuard.chunkKey("w", 17, -1).equals("w:1,-1"), "chunk key maths");
    }

    // ------------------------------------------------------------------ engine integration
    private static final class FakeHost implements CircuitHost {
        final ComponentRegistry reg;
        final Map<String, String[]> wiring = new HashMap<>();
        final Map<String, Integer> raw = new HashMap<>();
        final Map<String, Integer> emitted = new HashMap<>();
        final List<String> events = new ArrayList<>();
        final Set<String> inactive = new HashSet<>();
        int sensorReads;
        int level = 10;

        FakeHost(ComponentRegistry reg) {
            this.reg = reg;
        }

        void wire(Component target, Object left, Object right, Object back) {
            wiring.put(target.key(), new String[] {src(left), src(right), src(back)});
        }

        private static String src(Object o) {
            if (o == null) {
                return null;
            }
            return o instanceof Component ? ((Component) o).key() : "const:" + o;
        }

        private int read(String s) {
            if (s == null) {
                return 0;
            }
            if (s.startsWith("const:")) {
                return Integer.parseInt(s.substring(6));
            }
            Component c = reg.get(s);
            return c == null ? 0 : c.output;
        }

        @Override
        public boolean isActive(Component c) {
            return !inactive.contains(c.key());
        }

        @Override
        public Inputs readInputs(Component c) {
            String[] w = wiring.get(c.key());
            if (w == null) {
                return Inputs.NONE;
            }
            return new Inputs(read(w[0]), read(w[1]), read(w[2]), 0);
        }

        @Override
        public int readSensor(Component c) {
            sensorReads++;
            return raw.getOrDefault(c.key(), 0);
        }

        @Override
        public void emit(Component c, int lvl) {
            emitted.put(c.key(), lvl);
        }

        @Override
        public int ownerLevel(Component c) {
            return level;
        }

        @Override
        public void event(Component c, CircuitEvent e, String detail) {
            events.add(e + ":" + c.type.id);
        }
    }

    private static CircuitEngine engine(RedstoneConfig cfg, ComponentRegistry reg) {
        return new CircuitEngine(cfg, reg, new WirelessNetwork(cfg), new LagGuard(cfg));
    }

    static void testEngine() {
        RedstoneConfig cfg = new RedstoneConfig(null);
        ComponentRegistry reg = new ComponentRegistry();
        FakeHost host = new FakeHost(reg);
        CircuitEngine eng = engine(cfg, reg);

        // A clock feeding a counter: after three rising edges the counter pulses.
        Component clock = comp(ComponentType.CLOCK, 0);
        clock.setParam("period", 8);
        clock.setParam("high", 4);
        Component counter = comp(ComponentType.COUNTER, 1);
        counter.setParam("target", 3);
        counter.setParam("pulse", 6);
        reg.add(clock);
        reg.add(counter);
        host.wire(counter, null, null, clock);
        int firstCounterOn = -1;
        for (int step = 0; step < 40; step++) {
            eng.step(step * 2L, host);
            if (counter.output > 0 && firstCounterOn < 0) {
                firstCounterOn = step;
            }
        }
        // clock rises at steps 0,4,8; counter sees them one step later -> fires around step 9
        expect(firstCounterOn >= 8 && firstCounterOn <= 12, "clock to counter chain fires on the third pulse (step " + firstCounterOn + ")");
        expect(host.emitted.containsKey(counter.key()), "outputs reach the host");
        expect(host.events.contains("OUTPUT_CHANGED:clock"), "output changes are reported");

        // Cross-coupled NOR latch with real one-step feedback delay: qa = NOR(reset, qb),
        // qb = NOR(set, qa). A set pulse must leave it holding "on" after the pulse ends, and a
        // reset pulse must leave it holding "off".
        RedstoneConfig cfg2 = new RedstoneConfig(null);
        ComponentRegistry reg2 = new ComponentRegistry();
        FakeHost host2 = new FakeHost(reg2);
        CircuitEngine eng2 = engine(cfg2, reg2);
        Component qa = comp(ComponentType.NOR, 0);
        Component qb = comp(ComponentType.NOR, 1);
        reg2.add(qa);
        reg2.add(qb);
        long t2 = 0;
        host2.wire(qa, "0", qb, null);
        host2.wire(qb, "0", qa, null);
        for (int i = 0; i < 6; i++) {
            eng2.step(t2, host2);
            t2 += 2;
        }
        host2.wire(qb, "15", qa, null);
        for (int i = 0; i < 6; i++) {
            eng2.step(t2, host2);
            t2 += 2;
        }
        expect(qa.output == 15 && qb.output == 0, "set pulse turns the NOR latch on");
        host2.wire(qb, "0", qa, null);
        for (int i = 0; i < 12; i++) {
            eng2.step(t2, host2);
            t2 += 2;
        }
        expect(qa.output == 15 && qb.output == 0, "the NOR latch remembers after the pulse ends");
        host2.wire(qa, "15", qb, null);
        for (int i = 0; i < 6; i++) {
            eng2.step(t2, host2);
            t2 += 2;
        }
        host2.wire(qa, "0", qb, null);
        for (int i = 0; i < 12; i++) {
            eng2.step(t2, host2);
            t2 += 2;
        }
        expect(qa.output == 0 && qb.output == 15, "reset pulse turns it off and it holds");

        // Ring oscillator (NOT fed by itself) is stopped by the lag guard and the owner is told.
        RedstoneConfig cfg3 = new RedstoneConfig(null);
        ComponentRegistry reg3 = new ComponentRegistry();
        FakeHost host3 = new FakeHost(reg3);
        CircuitEngine eng3 = engine(cfg3, reg3);
        Component ring = comp(ComponentType.NOT, 0);
        reg3.add(ring);
        host3.wire(ring, null, null, ring);
        int toggles = 0;
        int prev = ring.output;
        int tripStep = -1;
        for (int step = 0; step < 400; step++) {
            eng3.step(step * 2L, host3);
            if (ring.output != prev) {
                toggles++;
                prev = ring.output;
            }
            if (tripStep < 0 && host3.events.contains("TRIPPED:not")) {
                tripStep = step;
            }
        }
        expect(tripStep > 0 && tripStep < 80, "runaway ring oscillator is tripped (step " + tripStep + ")");
        expect(host3.emitted.get(ring.key()) != null, "the oscillator drove its output cell");
        expect(host3.events.contains("RESUMED:not"), "component resumes after the cool-down");
        expect(toggles > 20, "oscillator really oscillated before the trip");

        // Unloaded chunks are skipped; sensors are sampled on their own slower cadence.
        RedstoneConfig cfg4 = new RedstoneConfig(null);
        ComponentRegistry reg4 = new ComponentRegistry();
        FakeHost host4 = new FakeHost(reg4);
        CircuitEngine eng4 = engine(cfg4, reg4);
        Component sensor = comp(ComponentType.SENSOR_PLAYER, 0);
        reg4.add(sensor);
        host4.raw.put(sensor.key(), 2);
        for (int step = 0; step < 30; step++) {
            eng4.step(step * 2L, host4);
        }
        expect(host4.sensorReads >= 5 && host4.sensorReads <= 7, "sensor sampled about every " + cfg4.sensorIntervalTicks + " ticks (" + host4.sensorReads + " reads in 60 ticks)");
        expect(sensor.output == 15, "sensor output follows the reading");
        host4.inactive.add(sensor.key());
        host4.raw.put(sensor.key(), 0);
        int readsBefore = host4.sensorReads;
        for (int step = 30; step < 60; step++) {
            eng4.step(step * 2L, host4);
        }
        expect(host4.sensorReads == readsBefore && sensor.output == 15, "unloaded components are frozen, not evaluated");

        // Wireless through the engine, with the owner's level capping the tier.
        RedstoneConfig cfg5 = new RedstoneConfig(null);
        ComponentRegistry reg5 = new ComponentRegistry();
        FakeHost host5 = new FakeHost(reg5);
        CircuitEngine eng5 = engine(cfg5, reg5);
        Component tx = comp(ComponentType.WIRELESS_TX, 0);
        tx.text = "door";
        tx.setParam("tier", 1);
        Component rx = comp(ComponentType.WIRELESS_RX, 80);
        rx.text = "door";
        rx.setParam("tier", 1);
        reg5.add(tx);
        reg5.add(rx);
        host5.wire(tx, null, null, "15");
        host5.level = 5;
        for (int step = 0; step < 10; step++) {
            eng5.step(step * 2L, host5);
        }
        expect(rx.output == 0, "a novice's regional tier is capped to local range");
        host5.level = 20;
        for (int step = 10; step < 20; step++) {
            eng5.step(step * 2L, host5);
        }
        expect(rx.output == 15, "an engineer of level 20 reaches 80 blocks wirelessly");
        reg5.remove(tx.key());
        eng5.forget(tx);
        for (int step = 20; step < 30; step++) {
            eng5.step(step * 2L, host5);
        }
        expect(rx.output == 0, "removing the transmitter drops the receiver");
    }

    // ------------------------------------------------------------------ skill
    static void testSkill() {
        RedstoneConfig cfg = new RedstoneConfig(null);
        expect(EngineerSkill.levelFor(0, cfg) == 1, "level 1 at zero xp");
        int prev = 1;
        for (long xp = 0; xp < 200000; xp += 97) {
            int lv = EngineerSkill.levelFor(xp, cfg);
            expect(lv >= prev, "level never decreases with xp");
            prev = lv;
        }
        expect(EngineerSkill.levelFor(Long.MAX_VALUE / 4, cfg) == cfg.skillMaxLevel, "level caps at max");
        expect(EngineerSkill.xpForNextLevel(cfg.skillMaxLevel, cfg) == -1, "no next level at max");
        expect(EngineerSkill.levelFor(EngineerSkill.xpForLevel(7, cfg), cfg) == 7, "xp threshold is exact");
        expect(EngineerSkill.maxComponents(1, cfg) == cfg.maxComponentsBase && EngineerSkill.maxComponents(50, cfg) <= cfg.maxComponentsCeiling, "component allowance");
        expect(EngineerSkill.minClockPeriod(1, cfg) == 10 && EngineerSkill.minClockPeriod(50, cfg) == 2, "clock period unlocks with level");
        expect(EngineerSkill.minClockPeriod(1, cfg) >= EngineerSkill.minClockPeriod(20, cfg), "clock period never increases");
        expect(EngineerSkill.maxWirelessTier(1, cfg) == 0 && EngineerSkill.maxWirelessTier(18, cfg) == 1
                && EngineerSkill.maxWirelessTier(28, cfg) == 2 && EngineerSkill.maxWirelessTier(40, cfg) == 3, "wireless tier gates");
        expect(EngineerSkill.plcOps(1, cfg) == cfg.plcBaseOps && EngineerSkill.plcOps(50, cfg) > EngineerSkill.plcOps(1, cfg), "plc operations scale");
        expect(EngineerSkill.megaPistonLimit(1, cfg) == 24 && EngineerSkill.megaPistonLimit(50, cfg) == 64, "mega piston limit grows to its ceiling");
        expect(EngineerSkill.moverBlocks(1, cfg) == 32 && EngineerSkill.moverBlocks(50, cfg) <= cfg.moverBlocksCeiling, "mover size");
        expect(EngineerSkill.blueprintBlocks(50, cfg) > EngineerSkill.blueprintBlocks(1, cfg), "blueprint size");
        expect(EngineerSkill.pipeLength(50, cfg) > EngineerSkill.pipeLength(1, cfg), "pipe length");
        expect(EngineerSkill.portItems(1, cfg) == 8 && EngineerSkill.portItems(50, cfg) <= cfg.portItemsCeiling, "port throughput");
        expect(EngineerSkill.lagBudgetMultiplier(1, cfg) == 1.0 && EngineerSkill.lagBudgetMultiplier(50, cfg) > 1.5, "lag budget bonus");
        expect(EngineerSkill.canUse(ComponentType.PLC, 25) && !EngineerSkill.canUse(ComponentType.PLC, 24), "unlock gate");
        expect(EngineerSkill.title(1).equals("Apprentice") && EngineerSkill.title(50).equals("Grand Artificer"), "titles");
        for (ComponentType t : ComponentType.values()) {
            expect(t.minLevel <= cfg.skillMaxLevel, "everything is unlockable by max level: " + t.id);
        }
    }

    // ------------------------------------------------------------------ filters and hoppers
    static void testFilterAndHoppers() {
        List<String> f = new ArrayList<>();
        expect(ItemFilter.passes(f, ItemFilter.WHITELIST, "DIRT") && ItemFilter.passes(f, ItemFilter.BLACKLIST, "DIRT"), "empty filter passes everything");
        f.add("IRON_INGOT");
        f.add("*_ORE");
        f.add("RAW_*");
        f.add("*PICKAXE*");
        expect(ItemFilter.passes(f, ItemFilter.WHITELIST, "IRON_INGOT") && !ItemFilter.passes(f, ItemFilter.WHITELIST, "GOLD_INGOT"), "whitelist exact");
        expect(ItemFilter.passes(f, ItemFilter.WHITELIST, "DEEPSLATE_GOLD_ORE"), "suffix wildcard");
        expect(ItemFilter.passes(f, ItemFilter.WHITELIST, "raw_copper"), "prefix wildcard, case-insensitive");
        expect(ItemFilter.passes(f, ItemFilter.WHITELIST, "NETHERITE_PICKAXE"), "contains wildcard");
        expect(!ItemFilter.passes(f, ItemFilter.BLACKLIST, "IRON_INGOT") && ItemFilter.passes(f, ItemFilter.BLACKLIST, "DIRT"), "blacklist inverts");
        expect(ItemFilter.entryMatches("*", "ANYTHING") && !ItemFilter.entryMatches("", "ANYTHING"), "star and empty entries");
        List<String> small = new ArrayList<>();
        expect(ItemFilter.add(small, "dirt", 2).equals("ok") && small.contains("DIRT"), "add normalises");
        expect(ItemFilter.add(small, "DIRT", 2).equals("dup"), "duplicate rejected");
        expect(ItemFilter.add(small, "stone", 2).equals("ok") && ItemFilter.add(small, "sand", 2).equals("full"), "capacity enforced");
        expect(ItemFilter.add(small, "  ", 2).equals("empty"), "blank rejected");

        RedstoneConfig cfg = new RedstoneConfig(null);
        expect(HopperTiers.extraItems(ComponentType.HOPPER_IRON, cfg) == 3 && HopperTiers.extraItems(ComponentType.HOPPER_NETHERITE, cfg) == 63, "tier batch sizes");
        expect(HopperTiers.extraItems(ComponentType.AND, cfg) == 0, "non-hopper adds nothing");
        cfg.hopperBatchMultiplier = 0.0;
        expect(HopperTiers.extraItems(ComponentType.HOPPER_GOLD, cfg) == 0, "batch multiplier of zero disables batching");
        expect(HopperTiers.canVacuum(ComponentType.HOPPER_DIAMOND) && !HopperTiers.canVacuum(ComponentType.HOPPER_GOLD), "vacuum tiers");
        expect(HopperTiers.vacuumRadius(ComponentType.HOPPER_NETHERITE, cfg) > HopperTiers.vacuumRadius(ComponentType.HOPPER_DIAMOND, cfg), "netherite vacuum is wider");
        expect(HopperTiers.itemsPerTransfer(ComponentType.HOPPER_IRON) < HopperTiers.itemsPerTransfer(ComponentType.HOPPER_GOLD)
                && HopperTiers.itemsPerTransfer(ComponentType.HOPPER_GOLD) < HopperTiers.itemsPerTransfer(ComponentType.HOPPER_DIAMOND), "tiers increase");
        expect(ComponentType.HOPPER_IRON.filterSlots() < ComponentType.HOPPER_NETHERITE.filterSlots(), "filter capacity grows by tier");
    }

    // ------------------------------------------------------------------ transport
    static void testTransport() {
        ComponentRegistry reg = new ComponentRegistry();
        Component in = comp(ComponentType.PORT_IN, 0);
        reg.add(in);
        for (int x = 1; x <= 3; x++) {
            reg.add(comp(ComponentType.PIPE, x));
        }
        Component out = comp(ComponentType.PORT_OUT, 4);
        reg.add(out);
        Component adjacent = comp(ComponentType.PORT_OUT, 0, 65, 0);
        reg.add(adjacent);
        Component otherColour = comp(ComponentType.PIPE, 0, 63, 0);
        otherColour.setParam("net", 5);
        reg.add(otherColour);
        Component isolated = comp(ComponentType.PORT_OUT, 0, 62, 0);
        isolated.setParam("net", 5);
        reg.add(isolated);

        TransportNetwork.Result r = TransportNetwork.route(reg, in, 30, 100);
        expect(r.destinations().size() == 2, "finds two reachable outputs (" + r.destinations().size() + ")");
        boolean sawFar = false;
        boolean sawAdjacent = false;
        for (TransportNetwork.Destination d : r.destinations()) {
            if (d.port() == out) {
                sawFar = d.pathLength() == 3;
            }
            if (d.port() == adjacent) {
                sawAdjacent = d.pathLength() == 0;
            }
        }
        expect(sawFar && sawAdjacent, "path lengths: pipes counted, direct adjacency is zero");
        expect(r.pipesTouched().size() == 3, "pipes touched are reported for the visualizer");
        expect(!r.truncated(), "short network is not truncated");

        TransportNetwork.Result limited = TransportNetwork.route(reg, in, 2, 100);
        expect(limited.destinations().size() == 1 && limited.truncated(), "path length limit cuts off far outputs");
        TransportNetwork.Result capped = TransportNetwork.route(reg, in, 30, 2);
        expect(capped.truncated(), "node cap stops a sprawling search");

        out.setParam("priority", 9);
        TransportNetwork.Result pr = TransportNetwork.route(reg, in, 30, 100);
        expect(pr.destinations().get(0).port() == out, "higher priority output is offered first");

        // round robin among equals
        ComponentRegistry reg2 = new ComponentRegistry();
        Component hub = comp(ComponentType.PORT_IN, 0, 64, 0);
        reg2.add(hub);
        reg2.add(comp(ComponentType.PIPE, 1, 64, 0));
        Component a = comp(ComponentType.PORT_OUT, 2, 64, 0);
        Component b = comp(ComponentType.PORT_OUT, 1, 65, 0);
        Component c = comp(ComponentType.PORT_OUT, 1, 63, 0);
        reg2.add(a);
        reg2.add(b);
        reg2.add(c);
        List<TransportNetwork.Destination> ordered = TransportNetwork.route(reg2, hub, 30, 100).destinations();
        expect(ordered.size() == 3, "three equal outputs found");
        Set<Component> firsts = new HashSet<>();
        for (int cursor = 0; cursor < 3; cursor++) {
            firsts.add(TransportNetwork.roundRobin(ordered, cursor).get(0).port());
        }
        expect(firsts.size() == 3, "round-robin serves each equal-priority output in turn");
        expect(TransportNetwork.roundRobin(ordered, -1).size() == 3 && TransportNetwork.roundRobin(new ArrayList<>(), 5).isEmpty(), "round robin handles negatives and empties");
        a.setParam("priority", 9);
        expect(TransportNetwork.roundRobin(TransportNetwork.route(reg2, hub, 30, 100).destinations(), 7).get(0).port() == a, "round robin never overrides priority");
        // ports are endpoints, never junctions
        ComponentRegistry reg3 = new ComponentRegistry();
        Component i3 = comp(ComponentType.PORT_IN, 0, 64, 0);
        Component o3 = comp(ComponentType.PORT_OUT, 1, 64, 0);
        Component beyond = comp(ComponentType.PORT_OUT, 2, 64, 0);
        reg3.add(i3);
        reg3.add(o3);
        reg3.add(beyond);
        expect(TransportNetwork.route(reg3, i3, 30, 100).destinations().size() == 1, "a port is an endpoint, items do not pass through it");
    }

    // ------------------------------------------------------------------ smart storage
    static void testSmartStorage() {
        expect(ItemCategory.of("DIAMOND_PICKAXE") == ItemCategory.TOOLS && ItemCategory.of("DIAMOND_HELMET") == ItemCategory.ARMOR, "tools and armor before ores");
        expect(ItemCategory.of("DIAMOND") == ItemCategory.ORES && ItemCategory.of("IRON_ORE") == ItemCategory.ORES && ItemCategory.of("RAW_IRON") == ItemCategory.ORES, "ores");
        expect(ItemCategory.of("OAK_PLANKS") == ItemCategory.BUILDING && ItemCategory.of("COBBLESTONE") == ItemCategory.BUILDING, "building blocks");
        expect(ItemCategory.of("REDSTONE") == ItemCategory.REDSTONE && ItemCategory.of("REDSTONE_BLOCK") == ItemCategory.REDSTONE && ItemCategory.of("STONE_BUTTON") == ItemCategory.REDSTONE, "redstone items");
        expect(ItemCategory.of("COOKED_BEEF") == ItemCategory.FOOD && ItemCategory.of("BREAD") == ItemCategory.FOOD, "food");
        expect(ItemCategory.of("WHEAT_SEEDS") == ItemCategory.FARMING && ItemCategory.of("OAK_SAPLING") == ItemCategory.FARMING, "farming");
        expect(ItemCategory.of("POTION") == ItemCategory.MAGIC && ItemCategory.of("ENCHANTED_BOOK") == ItemCategory.MAGIC, "magic");
        expect(ItemCategory.of("PAINTING") == ItemCategory.MISC && ItemCategory.of(null) == ItemCategory.MISC, "everything else is misc");

        List<SmartStorage.StackView> stacks = new ArrayList<>();
        stacks.add(new SmartStorage.StackView(3, "COBBLESTONE", 64, 64));
        stacks.add(new SmartStorage.StackView(1, "IRON_INGOT", 10, 64));
        stacks.add(new SmartStorage.StackView(2, "DIAMOND_SWORD", 1, 1));
        stacks.add(new SmartStorage.StackView(3, "COBBLESTONE", 30, 64));
        stacks.add(new SmartStorage.StackView(1, "IRON_INGOT", 50, 64));
        stacks.add(new SmartStorage.StackView(2, "DIAMOND_SWORD", 1, 1));
        stacks.add(null);
        stacks.add(new SmartStorage.StackView(4, "DIRT", 0, 64));
        List<SmartStorage.Placed> sorted = SmartStorage.sort(stacks);
        expect(sorted.size() == 5, "merged to five stacks (" + sorted.size() + ")");
        expect(sorted.get(0).material().equals("DIAMOND_SWORD") && sorted.get(1).material().equals("DIAMOND_SWORD"), "tools first");
        expect(sorted.get(2).material().equals("IRON_INGOT") && sorted.get(2).amount() == 60, "ingots merged into one stack");
        expect(sorted.get(3).amount() == 64 && sorted.get(4).amount() == 30, "building blocks fill whole stacks first");
        Map<Integer, Integer> before = new HashMap<>();
        Map<Integer, Integer> after = new HashMap<>();
        for (SmartStorage.StackView s : stacks) {
            if (s != null) {
                before.merge(s.group(), s.amount(), Integer::sum);
            }
        }
        for (SmartStorage.Placed p : sorted) {
            after.merge(p.group(), p.amount(), Integer::sum);
        }
        before.remove(4);
        expect(before.equals(after), "sorting conserves every item");
        List<SmartStorage.StackView> again = new ArrayList<>();
        for (SmartStorage.Placed p : sorted) {
            again.add(new SmartStorage.StackView(p.group(), p.material(), p.amount(), p.material().equals("DIAMOND_SWORD") ? 1 : 64));
        }
        expect(SmartStorage.sort(again).equals(sorted), "sorting is idempotent");
        List<SmartStorage.StackView> slots = new ArrayList<>(again);
        slots.add(null);
        expect(SmartStorage.alreadySorted(slots, sorted), "alreadySorted recognises a sorted layout");
        Collections_swap(slots);
        expect(!SmartStorage.alreadySorted(slots, sorted), "alreadySorted spots a scrambled layout");
        expect(SmartStorage.sort(new ArrayList<>()).isEmpty(), "empty sorts to empty");
    }

    private static void Collections_swap(List<SmartStorage.StackView> l) {
        SmartStorage.StackView tmp = l.get(0);
        l.set(0, l.get(1));
        l.set(1, tmp);
        l.set(0, new SmartStorage.StackView(99, "X", 1, 64));
    }

    // ------------------------------------------------------------------ dispensers
    static void testDispensers() {
        RedstoneConfig cfg = new RedstoneConfig(null);
        expect(DispenserModes.burstOffsets(3, 2).equals(List.of(2, 4, 6)), "burst offsets");
        expect(DispenserModes.burstOffsets(0, 2).isEmpty() && DispenserModes.burstOffsets(2, 0).equals(List.of(1, 2)), "burst edge cases");
        double[] gentle = DispenserModes.velocity(DispenserModes.SPRAY_OR_GENTLE, true, 1, 2, 3, cfg, new Random(1));
        expect(gentle[0] == 0 && gentle[1] == 0 && gentle[2] == 0, "gentle dropper has zero velocity");
        double[] power = DispenserModes.velocity(DispenserModes.POWER, false, 1, 2, 3, cfg, new Random(1));
        expect(power[0] == 2 && power[1] == 4 && power[2] == 6, "power doubles velocity");
        double[] normal = DispenserModes.velocity(DispenserModes.NORMAL, false, 1, 2, 3, cfg, new Random(1));
        expect(normal[0] == 1 && normal[1] == 2 && normal[2] == 3, "normal is untouched");
        Random rng = new Random(7);
        boolean varied = false;
        for (int i = 0; i < 20; i++) {
            double[] sp = DispenserModes.velocity(DispenserModes.SPRAY_OR_GENTLE, false, 1, 0, 0, cfg, rng);
            expect(Math.abs(sp[0] - 1) <= cfg.sprayVelocityJitter + 1e-9, "spray stays within its jitter");
            if (sp[0] != 1) {
                varied = true;
            }
        }
        expect(varied, "spray actually varies");
        expect(DispenserModes.modeName(true, 2).equals("gentle") && DispenserModes.modeName(false, 2).equals("spray"), "mode names depend on block");
    }

    // ------------------------------------------------------------------ push planner
    private static final class Grid implements BlockView {
        final Map<String, Character> cells = new HashMap<>();

        void set(int x, int y, int z, char c) {
            cells.put(x + "," + y + "," + z, c);
        }

        char get(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, '.');
        }

        @Override
        public Reaction reaction(int x, int y, int z) {
            switch (get(x, y, z)) {
                case 'S':
                case 'H':
                    return Reaction.MOVE;
                case 'F':
                    return Reaction.BREAK;
                case 'B':
                    return Reaction.BLOCK;
                default:
                    return Reaction.AIR;
            }
        }

        @Override
        public boolean sticky(int x, int y, int z) {
            return get(x, y, z) == 'H';
        }
    }

    static void testPush() {
        Grid g = new Grid();
        for (int i = 0; i < 20; i++) {
            g.set(1 + i, 0, 0, 'S');
        }
        LinearPushPlanner.Plan p = LinearPushPlanner.push(g, 1, 0, 0, 1, 0, 0, 24, 1);
        expect(p.ok() && p.moves().size() == 20, "a 20-block line moves (vanilla stops at 12)");
        boolean allOne = true;
        for (LinearPushPlanner.Move m : p.moves()) {
            allOne &= m.tx() == m.fx() + 1 && m.ty() == m.fy() && m.tz() == m.fz();
        }
        expect(allOne, "every block moves exactly one cell");
        expect(!LinearPushPlanner.push(g, 1, 0, 0, 1, 0, 0, 12, 1).ok(), "the limit is enforced");
        expect(LinearPushPlanner.push(g, 1, 0, 0, 1, 0, 0, 12, 1).reason().contains("limit"), "limit failure explains itself");

        g.set(21, 0, 0, 'B');
        expect(!LinearPushPlanner.push(g, 1, 0, 0, 1, 0, 0, 64, 1).ok(), "an immovable block stops the push");
        g.set(21, 0, 0, 'F');
        LinearPushPlanner.Plan crush = LinearPushPlanner.push(g, 1, 0, 0, 1, 0, 0, 64, 1);
        expect(crush.ok() && crush.breaks().size() == 1 && crush.breaks().get(0)[0] == 21, "a fragile block at the end is crushed");
        g.set(21, 0, 0, '.');

        LinearPushPlanner.Plan stroke = LinearPushPlanner.push(g, 1, 0, 0, 1, 0, 0, 64, 3);
        boolean allThree = stroke.ok() && stroke.moves().size() == 20;
        for (LinearPushPlanner.Move m : stroke.moves()) {
            allThree &= m.tx() == m.fx() + 3;
        }
        expect(allThree, "a stroke of 3 moves every block 3 cells");

        Grid pocket = new Grid();
        pocket.set(1, 0, 0, 'S');
        pocket.set(2, 0, 0, 'S');
        pocket.set(4, 0, 0, 'S');
        LinearPushPlanner.Plan gap = LinearPushPlanner.push(pocket, 1, 0, 0, 1, 0, 0, 24, 2);
        expect(gap.ok(), "stroke across a gap plans");
        Map<String, String> finals = new HashMap<>();
        for (LinearPushPlanner.Move m : gap.moves()) {
            finals.put(m.fx() + "", m.tx() + "");
        }
        expect(gap.moves().size() == 3, "a long stroke carries all three blocks (" + gap.moves().size() + ")");

        Grid blocked = new Grid();
        blocked.set(1, 0, 0, 'S');
        blocked.set(2, 0, 0, 'B');
        expect(!LinearPushPlanner.push(blocked, 1, 0, 0, 1, 0, 0, 24, 1).ok(), "block directly behind the run fails");

        Grid empty = new Grid();
        LinearPushPlanner.Plan nothing = LinearPushPlanner.push(empty, 1, 0, 0, 1, 0, 0, 24, 2);
        expect(nothing.ok() && nothing.moves().isEmpty(), "pushing air is fine and moves nothing");

        Grid vertical = new Grid();
        for (int i = 0; i < 5; i++) {
            vertical.set(0, 10 + i, 0, 'S');
        }
        LinearPushPlanner.Plan up = LinearPushPlanner.push(vertical, 0, 10, 0, 0, 1, 0, 24, 1);
        expect(up.ok() && up.moves().size() == 5, "vertical pushes work");
        Grid neg = new Grid();
        neg.set(-5, -60, -3, 'S');
        LinearPushPlanner.Plan negPlan = LinearPushPlanner.push(neg, -5, -60, -3, 1, 0, 0, 24, 1);
        expect(negPlan.ok() && negPlan.moves().size() == 1 && negPlan.moves().get(0).tx() == -4 && negPlan.moves().get(0).ty() == -60
                && negPlan.moves().get(0).tz() == -3, "negative coordinates survive key packing");

        // pull
        Grid ext = new Grid();
        for (int i = 0; i < 6; i++) {
            ext.set(3 + i, 0, 0, 'S');
        }
        LinearPushPlanner.Plan pull = LinearPushPlanner.pull(ext, 1, 0, 0, 1, 0, 0, 24, 2);
        boolean backTwo = pull.ok() && pull.moves().size() == 6;
        for (LinearPushPlanner.Move m : pull.moves()) {
            backTwo &= m.tx() == m.fx() - 2;
        }
        expect(backTwo, "sticky retract pulls the whole run back by the stroke");
        ext.set(2, 0, 0, 'S');
        expect(!LinearPushPlanner.pull(ext, 1, 0, 0, 1, 0, 0, 24, 2).ok(), "pull refuses when the gap is not clear");
    }

    // ------------------------------------------------------------------ structure planner
    static void testStructure() {
        Grid g = new Grid();
        g.set(1, 0, 0, 'H');
        g.set(2, 0, 0, 'S');
        g.set(1, 1, 0, 'S');
        g.set(1, 0, 1, 'S');
        g.set(3, 0, 0, 'S');
        StructurePlanner.Plan p = StructurePlanner.collect(g, 0, 0, 0, 0, 0, 0, 64);
        expect(p.ok() && p.cells().size() == 4, "honey drags its neighbours but plain blocks do not spread the grab (" + p.cells().size() + ")");
        g.set(1, 1, 0, 'H');
        g.set(1, 2, 0, 'S');
        expect(StructurePlanner.collect(g, 0, 0, 0, 0, 0, 0, 64).cells().size() == 5, "chained honey spreads further");
        expect(!StructurePlanner.collect(g, 0, 0, 0, 0, 0, 0, 3).ok(), "block limit enforced");
        expect(StructurePlanner.collect(g, 0, 0, 0, 0, 0, 0, 3).reason().contains("ground"), "limit message hints at the usual cause");
        g.set(1, 0, -1, 'B');
        expect(!StructurePlanner.collect(g, 0, 0, 0, 0, 0, 0, 64).ok(), "glued to something immovable fails");
        expect(!StructurePlanner.collect(new Grid(), 0, 0, 0, 0, 0, 0, 64).ok(), "no honey touching the mover");

        Grid s = new Grid();
        s.set(1, 0, 0, 'H');
        s.set(2, 0, 0, 'S');
        StructurePlanner.Plan col = StructurePlanner.collect(s, 0, 0, 0, 0, 0, 0, 64);
        s.set(6, 0, 0, 'S');
        StructurePlanner.Plan sw = StructurePlanner.sweep(s, col.cells(), 1, 0, 0, 8);
        expect(sw.ok() && sw.distance() == 3, "structure stops short of an obstacle (moved " + sw.distance() + ")");
        s.set(3, 0, 0, 'S');
        expect(!StructurePlanner.sweep(s, col.cells(), 1, 0, 0, 8).ok(), "blocked immediately");
        Grid f = new Grid();
        f.set(1, 0, 0, 'H');
        f.set(4, 0, 0, 'F');
        StructurePlanner.Plan crush = StructurePlanner.sweep(f, StructurePlanner.collect(f, 0, 0, 0, 0, 0, 0, 64).cells(), 1, 0, 0, 5);
        expect(crush.ok() && crush.distance() == 5 && crush.breaks().size() == 1, "fragile blocks along the route are crushed");

        // A structure that has already travelled is found again via the offset
        Grid away = new Grid();
        away.set(5, 0, 0, 'H');
        away.set(6, 0, 0, 'S');
        StructurePlanner.Plan back = StructurePlanner.collect(away, 0, 0, 0, 4, 0, 0, 64);
        expect(back.ok() && back.cells().size() == 2, "a travelled structure is found at its offset");
    }

    // ------------------------------------------------------------------ blueprints
    static void testBlueprint() {
        Blueprint bp = new Blueprint();
        bp.name = "door\tcircuit %";
        bp.sizeX = 4;
        bp.sizeY = 2;
        bp.sizeZ = 3;
        bp.blocks.add(new Blueprint.Block(0, 0, 0, "minecraft:stone"));
        bp.blocks.add(new Blueprint.Block(1, 0, 2, "minecraft:oak_stairs[facing=north,half=bottom]"));
        bp.blocks.add(new Blueprint.Block(3, 1, 2, "minecraft:redstone_wire[power=0]"));
        Map<String, Integer> params = new LinkedHashMap<>();
        params.put("period", 40);
        params.put("high", 10);
        bp.components.add(new Blueprint.Comp(2, 0, 1, "clock", "EAST", params, "line1\nline2\ttab", List.of()));
        bp.components.add(new Blueprint.Comp(2, 1, 1, "portin", "NORTH", new LinkedHashMap<>(), "", List.of("IRON_INGOT", "*_ORE")));
        String enc = BlueprintCodec.encode(bp);
        expect(!enc.contains("\n") && !enc.contains(" ") && enc.length() < 400, "encoded blueprint is one compact token (" + enc.length() + " chars)");
        BlueprintCodec.Decoded d = BlueprintCodec.decode(enc, 100);
        expect(d.ok(), "round trip decodes: " + d.error());
        if (d.ok()) {
            Blueprint r = d.blueprint();
            expect(r.name.equals(bp.name) && r.sizeX == 4 && r.sizeY == 2 && r.sizeZ == 3, "name and size survive (including tabs and %)");
            expect(r.blocks.equals(bp.blocks), "blocks survive");
            expect(r.components.size() == 2 && r.components.get(0).text().equals("line1\nline2\ttab"), "component text survives control characters");
            expect(r.components.get(0).params().get("period") == 40 && r.components.get(0).facing().equals("EAST"), "component settings survive");
            expect(r.components.get(1).filter().equals(List.of("IRON_INGOT", "*_ORE")), "filters survive");
        }
        expect(!BlueprintCodec.decode(enc, 4).ok() && BlueprintCodec.decode(enc, 4).error().contains("more than"), "block cap enforced");
        expect(!BlueprintCodec.decode(enc.substring(0, enc.length() / 2), 100).ok(), "truncated string rejected");
        expect(!BlueprintCodec.decode("###", 100).ok() && !BlueprintCodec.decode("", 100).ok() && !BlueprintCodec.decode(null, 100).ok(), "garbage rejected");
        char[] chars = enc.toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        BlueprintCodec.Decoded tampered = BlueprintCodec.decode(new String(chars), 100);
        expect(!tampered.ok() || tampered.blueprint() != null, "tampering never throws");

        // zip bomb: 3 MB of zeros compressed small
        Deflater def = new Deflater();
        def.setInput(new byte[3_000_000]);
        def.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!def.finished()) {
            bos.write(buf, 0, def.deflate(buf));
        }
        String bomb = Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
        BlueprintCodec.Decoded bombRes = BlueprintCodec.decode(bomb, 100);
        expect(!bombRes.ok(), "decompression bomb refused");

        Blueprint bad = new Blueprint();
        bad.sizeX = 2;
        bad.sizeY = 2;
        bad.sizeZ = 2;
        bad.blocks.add(new Blueprint.Block(5, 0, 0, "minecraft:stone"));
        expect(!BlueprintCodec.decode(BlueprintCodec.encode(bad), 100).ok(), "out-of-bounds block rejected");
        Blueprint unknown = new Blueprint();
        unknown.sizeX = unknown.sizeY = unknown.sizeZ = 2;
        unknown.components.add(new Blueprint.Comp(0, 0, 0, "nonsense", "NORTH", new LinkedHashMap<>(), "", List.of()));
        expect(!BlueprintCodec.decode(BlueprintCodec.encode(unknown), 100).ok(), "unknown component type rejected");
        Blueprint wild = new Blueprint();
        wild.sizeX = wild.sizeY = wild.sizeZ = 2;
        Map<String, Integer> wildParams = new LinkedHashMap<>();
        wildParams.put("period", 999999);
        wildParams.put("hack", 5);
        wild.components.add(new Blueprint.Comp(0, 0, 0, "clock", "NORTH", wildParams, "", List.of()));
        BlueprintCodec.Decoded wd = BlueprintCodec.decode(BlueprintCodec.encode(wild), 100);
        expect(wd.ok() && wd.blueprint().components.get(0).params().get("period") == 2400 && !wd.blueprint().components.get(0).params().containsKey("hack"),
                "decoded settings are clamped and unknown settings dropped");
        expect(BlueprintCodec.unesc(BlueprintCodec.esc("a\tb\nc%09d")).equals("a\tb\nc%09d"), "escaping round trip");
    }

    // ------------------------------------------------------------------ oscilloscope
    static void testTrace() {
        SignalTrace t = new SignalTrace(64);
        int[][] data = {{0, 15}, {10, 0}, {20, 15}, {30, 0}, {40, 15}, {50, 0}};
        for (int[] d : data) {
            t.record(d[0], d[1]);
        }
        t.record(52, 0);
        expect(t.size() == 6, "only changes are stored");
        expect(t.valueAt(5) == 15 && t.valueAt(15) == 0 && t.valueAt(25) == 15 && t.valueAt(-5) == 0, "valueAt reads the waveform");
        expect(t.currentValue() == 0, "current value");
        expect(t.risingEdges(100, 52) == 2, "rising edges counted (" + t.risingEdges(100, 52) + ")");
        expect(t.periodTicks(100, 52) == 20, "period measured");
        int duty = t.dutyPercent(60, 60);
        expect(duty >= 40 && duty <= 60, "duty cycle about 50% (" + duty + ")");
        String hex = t.hexLine(10, 50, 50);
        expect(hex.length() == 10 && hex.indexOf('F') >= 0 && hex.indexOf('0') >= 0, "hex line shows both levels: " + hex);
        String[] g = t.graph(20, 4, 60, 60);
        expect(g.length == 4 && g[0].length() == 20, "graph dimensions");
        SignalTrace ring = new SignalTrace(4);
        for (int i = 0; i < 20; i++) {
            ring.record(i, i % 2 == 0 ? 15 : 0);
        }
        expect(ring.size() == 4 && ring.valueAt(19) == 0, "ring buffer overwrites oldest");
        expect(new SignalTrace(8).periodTicks(100, 100) == -1 && new SignalTrace(8).risingEdges(10, 10) == 0, "empty trace is safe");
        t.record(60, 99);
        expect(t.currentValue() == 15, "values clamp to 15");
    }

    // ------------------------------------------------------------------ dialogue
    static void testDialogue() {
        DialogueBank bank = new DialogueBank();
        Random rng = new Random(3);
        for (Topic topic : Topic.values()) {
            String line = bank.line(topic, rng);
            expect(line != null && !line.isBlank(), "default line for " + topic.key);
            expect(topic.prefix.startsWith("§"), "colour prefix for " + topic.key);
        }
        for (String prof : DialogueBank.commentingProfessions()) {
            expect(bank.villagerLine(prof, rng) != null && bank.hasVillagerPool(prof), "villager line for " + prof);
        }
        expect(bank.villagerLine("nitwit", rng) != null && !bank.hasVillagerPool("nitwit"), "unknown profession falls back to a generic line");
        expect(bank.villagerLine(null, rng) != null, "null profession is safe");
        bank.load(null);
        expect(bank.line(Topic.LEVEL_UP, rng) != null, "reload with no config keeps defaults");
        Set<String> keys = new HashSet<>();
        for (Topic topic : Topic.values()) {
            expect(keys.add(topic.key), "unique topic key " + topic.key);
        }
    }

    // ------------------------------------------------------------------ registry
    static void testRegistry() {
        ComponentRegistry reg = new ComponentRegistry();
        Component a = comp(ComponentType.AND, 0);
        Component hop = comp(ComponentType.HOPPER_IRON, 1);
        expect(reg.add(a) && reg.add(hop) && !reg.add(a), "duplicate positions rejected");
        expect(reg.size() == 2 && reg.circuitComponents().size() == 1, "circuit snapshot only holds circuit components");
        List<Component> snap = reg.circuitComponents();
        reg.add(comp(ComponentType.OR, 2));
        expect(snap.size() == 1 && reg.circuitComponents().size() == 2, "snapshot is stable during iteration and refreshes after changes");
        reg.claimCell("w:0,64,-1", a.key());
        expect(reg.resolve("w:0,64,-1") == a && reg.resolve(a.key()) == a && reg.isCell("w:0,64,-1"), "cells resolve to their owner");
        expect(reg.cellsOf(a.key()).equals(List.of("w:0,64,-1")), "cells of a component");
        reg.remove(a.key());
        expect(reg.resolve("w:0,64,-1") == null && !reg.isCell("w:0,64,-1"), "removing a component releases its cells");
        expect(reg.countOwnedBy(U1) == 2 && reg.countOwnedBy(U2) == 0 && reg.countOwnedBy(null) == 0, "ownership counts");
        expect(reg.ofFamily(Family.HOPPER).size() == 1, "family lookup");
        reg.clearDirty();
        expect(!reg.isDirty(), "dirty flag clears");
        reg.markDirty();
        expect(reg.isDirty(), "dirty flag sets");
        reg.clear();
        expect(reg.size() == 0, "clear");
    }

    // ------------------------------------------------------------------ blueprint cost
    static void testBlueprintCost() {
        java.util.function.Predicate<String> all = n -> true;
        expect(BlueprintCost.materialOf("minecraft:oak_stairs[facing=east,half=bottom]").equals("OAK_STAIRS"), "materialOf strips namespace and state");
        expect(BlueprintCost.materialOf("STONE").equals("STONE") && BlueprintCost.materialOf(null).equals(""), "materialOf plain and null");
        expect(BlueprintCost.itemFor("REDSTONE_WIRE", all).equals("REDSTONE"), "dust costs redstone");
        expect(BlueprintCost.itemFor("WALL_TORCH", all).equals("TORCH"), "wall torch costs a torch");
        expect(BlueprintCost.itemFor("REDSTONE_WALL_TORCH", all).equals("REDSTONE_TORCH"), "redstone wall torch");
        expect(BlueprintCost.itemFor("OAK_WALL_SIGN", all).equals("OAK_SIGN"), "wall sign costs a sign");
        expect(BlueprintCost.itemFor("WATER", all) == null && BlueprintCost.itemFor("FIRE", all) == null
                && BlueprintCost.itemFor("PISTON_HEAD", all) == null, "liquids, fire, piston heads are unbuildable");
        expect(BlueprintCost.itemFor("STONE", all).equals("STONE"), "ordinary block costs itself");
        expect(BlueprintCost.itemFor("STONE", n -> false) == null, "no item form means unbuildable");
        expect(BlueprintCost.attachable("REDSTONE_WIRE") && BlueprintCost.attachable("WALL_TORCH") && BlueprintCost.attachable("OAK_BUTTON")
                && BlueprintCost.attachable("POWERED_RAIL") && BlueprintCost.attachable("OAK_PRESSURE_PLATE") && BlueprintCost.attachable("REPEATER"),
                "attachable set");
        expect(!BlueprintCost.attachable("STONE") && !BlueprintCost.attachable("OBSERVER") && !BlueprintCost.attachable("HOPPER"), "solids are not attachable");

        Blueprint bp = new Blueprint();
        bp.blocks.add(new Blueprint.Block(0, 1, 0, "REDSTONE_WIRE"));
        bp.blocks.add(new Blueprint.Block(0, 0, 0, "STONE"));
        bp.blocks.add(new Blueprint.Block(1, 0, 0, "STONE"));
        bp.blocks.add(new Blueprint.Block(2, 0, 0, "minecraft:hopper[facing=down]"));
        bp.blocks.add(new Blueprint.Block(3, 5, 0, "WATER"));
        bp.components.add(new Blueprint.Comp(2, 0, 0, "hopperiron", "NORTH", new LinkedHashMap<>(), "", new ArrayList<>()));
        bp.components.add(new Blueprint.Comp(4, 0, 0, "and", "NORTH", new LinkedHashMap<>(), "", new ArrayList<>()));
        BlueprintCost.Cost cost = BlueprintCost.cost(bp, all);
        expect(cost.items().get("STONE") == 2 && cost.items().get("REDSTONE") == 1, "plain blocks counted: " + cost.items());
        expect(!cost.items().containsKey("HOPPER"), "the hopper that carries a component's facing is not billed twice");
        expect(cost.items().get("component:hopperiron") == 1 && cost.items().get("component:and") == 1, "components billed as component items");
        expect(cost.unbuildable().contains("WATER") && cost.unbuildable().size() == 1, "water flagged unbuildable");
        expect(BlueprintCost.describe(cost.items()).contains("Iron Hopper") && BlueprintCost.describe(new LinkedHashMap<>()).equals("nothing"), "describe uses display names");

        List<Blueprint.Block> order = BlueprintCost.placeOrder(bp);
        int dustAt = -1;
        int lastSolid = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).data().equals("REDSTONE_WIRE")) {
                dustAt = i;
            } else {
                lastSolid = i;
            }
        }
        expect(dustAt > lastSolid, "dust is placed after every solid block");
        expect(order.size() == bp.blocks.size(), "placeOrder keeps every block");
        List<Blueprint.Block> solids = new ArrayList<>(order);
        solids.removeIf(b -> b.data().equals("REDSTONE_WIRE"));
        boolean sorted = true;
        for (int i = 1; i < solids.size(); i++) {
            sorted &= solids.get(i - 1).dy() <= solids.get(i).dy();
        }
        expect(sorted, "solids go bottom to top");
    }

    // ------------------------------------------------------------------ recipes
    static void testRecipeBook() {
        List<RecipeBook.Spec> all = RecipeBook.all();
        Set<String> ids = new HashSet<>();
        Set<String> patterns = new HashSet<>();
        for (RecipeBook.Spec spec : all) {
            expect(ids.add(spec.id()), "unique recipe id " + spec.id());
            expect(patterns.add(spec.patternKey()), "unique crafting pattern for " + spec.id());
            expect(spec.shape().length >= 1 && spec.shape().length <= 3, "shape height for " + spec.id());
            Set<Character> used = new HashSet<>();
            for (String row : spec.shape()) {
                expect(row.length() >= 1 && row.length() <= 3, "shape width for " + spec.id());
                for (char ch : row.toCharArray()) {
                    if (ch != ' ') {
                        used.add(ch);
                    }
                }
            }
            expect(spec.ingredients().keySet().equals(used), "every shape letter has an ingredient and none is unused: " + spec.id());
            expect(spec.yield() >= 1 && spec.yield() <= 8, "yield for " + spec.id());
            if (spec.tool()) {
                expect(Tool.byId(spec.id()) != null, "tool recipe has a tool " + spec.id());
            } else {
                expect(ComponentType.byId(spec.id()) != null, "recipe makes a real component " + spec.id());
            }
            for (String ingredient : spec.ingredients().values()) {
                if (ingredient.startsWith(RecipeBook.COMPONENT)) {
                    expect(ComponentType.byId(ingredient.substring(RecipeBook.COMPONENT.length())) != null, "chained recipe ingredient exists: " + ingredient);
                } else {
                    expect(ingredient.equals(ingredient.toUpperCase()) && !ingredient.contains(":"), "plain ingredient is a material name: " + ingredient);
                }
            }
        }
        for (ComponentType t : ComponentType.values()) {
            expect(ids.contains(t.id), "every component can be crafted: " + t.id);
        }
        for (Tool t : Tool.values()) {
            expect(ids.contains(t.id), "every tool can be crafted: " + t.id);
        }
        // the hopper tier chain really chains
        expect(RecipeBook.all().stream().filter(r -> r.id().equals("hoppergold")).findFirst().get().ingredients().containsValue("component:hopperiron"), "gold hopper needs an iron hopper");
        expect(RecipeBook.all().stream().filter(r -> r.id().equals("hoppernetherite")).findFirst().get().ingredients().containsValue("component:hopperdiamond"), "netherite hopper needs a diamond hopper");
    }

    // ------------------------------------------------------------------ wrench cycling
    static void testParamCycle() {
        ParamSpec small = new ParamSpec("mode", 0, 3, 0, "");
        expect(small.cycle(0) == 1 && small.cycle(3) == 0, "small ranges step by one and wrap");
        ParamSpec wide = new ParamSpec("period", 2, 2400, 20, "");
        expect(wide.cycle(2) == 3 && wide.cycle(10) == 15 && wide.cycle(60) == 70 && wide.cycle(300) == 350 && wide.cycle(1200) == 1300, "wide ranges take bigger strides");
        expect(wide.cycle(2400) == 2 && wide.cycle(2395) == 2, "wide ranges wrap past the maximum");
        int v = 2;
        int steps = 0;
        do {
            v = wide.cycle(v);
            steps++;
        } while (v != 2 && steps < 1000);
        expect(v == 2 && steps < 200, "cycling a clock period wraps in under 200 clicks (" + steps + ")");
        for (ComponentType t : ComponentType.values()) {
            for (ParamSpec spec : t.specs()) {
                int cur = spec.def();
                boolean ok = true;
                for (int i = 0; i < 3000; i++) {
                    cur = spec.cycle(cur);
                    ok &= cur >= spec.min() && cur <= spec.max();
                }
                expect(ok, "cycle stays in range for " + t.id + "." + spec.name());
            }
        }
    }

    // ------------------------------------------------------------------ item transfer (stub inventories)
    static void testTransfer() {
        org.bukkit.inventory.Inventory from = new org.bukkit.inventory.Inventory();
        org.bukkit.inventory.Inventory to = new org.bukkit.inventory.Inventory();
        org.bukkit.inventory.ItemStack iron = new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_SWORD, 1);
        // stacks of a stackable stub material
        org.bukkit.inventory.ItemStack bread = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 50);
        from.setItem(0, bread.clone());
        from.setItem(1, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 30));
        from.setItem(5, iron.clone());
        int moved = HopperService.transfer(from, to, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1), 70);
        expect(moved == 70, "transfer moves what was asked across stacks: " + moved);
        expect(Containers.count(from, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1)) == 10, "source lost exactly the moved amount");
        expect(Containers.count(to, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1)) == 70, "destination gained exactly the moved amount");
        expect(Containers.count(from, iron) == 1, "other items are untouched");
        moved = HopperService.transfer(from, to, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1), 500);
        expect(moved == 10 && Containers.count(from, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1)) == 0, "never moves more than exists");
        expect(HopperService.transfer(from, to, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1), 5) == 0, "moving nothing moves nothing");

        // a destination that is full accepts only what fits and the source keeps the rest
        org.bukkit.inventory.Inventory full = new org.bukkit.inventory.Inventory();
        for (int i = 0; i < full.getSize(); i++) {
            full.setItem(i, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 64));
        }
        full.setItem(10, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 60));
        org.bukkit.inventory.Inventory src2 = new org.bukkit.inventory.Inventory();
        src2.setItem(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 20));
        moved = HopperService.transfer(src2, full, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1), 20);
        expect(moved == 4, "a nearly full destination takes only what fits: " + moved);
        expect(Containers.count(src2, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1)) == 16, "source keeps the refused remainder");
        // conservation across the pair: 53 full stacks + 60 + 20 before, the same total after
        int breadAfter = Containers.count(full, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1))
                + Containers.count(src2, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1));
        expect(breadAfter == 53 * 64 + 60 + 20, "nothing created or destroyed: " + breadAfter);
    }

    // ------------------------------------------------------------------ sorter conservation
    static void testSorter() {
        RedstoneConfig cfg = new RedstoneConfig(null);
        SorterService sorter = new SorterService(cfg, new ComponentRegistry(), new Announcer(cfg, new DialogueBank()), java.util.logging.Logger.getLogger("test"));
        org.bukkit.inventory.Inventory inv = new org.bukkit.inventory.Inventory();
        org.bukkit.Material[] mats = {org.bukkit.Material.BREAD, org.bukkit.Material.IRON_SWORD, org.bukkit.Material.BREAD, org.bukkit.Material.REDSTONE, org.bukkit.Material.BREAD, org.bukkit.Material.REDSTONE};
        int[] amounts = {10, 1, 20, 40, 5, 30};
        Random rng = new Random(7);
        int total = 0;
        for (int i = 0; i < mats.length; i++) {
            inv.setItem(i * 7 + rng.nextInt(3), new org.bukkit.inventory.ItemStack(mats[i], amounts[i]));
            total += amounts[i];
        }
        Component c = comp(ComponentType.SORTER, 3);
        boolean changed = sorter.sort(c, inv, null, true);
        expect(changed, "a messy barrel gets sorted");
        int after = 0;
        int breadStacks = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            org.bukkit.inventory.ItemStack st = inv.getItem(i);
            if (st != null) {
                after += st.getAmount();
                if (st.getType() == org.bukkit.Material.BREAD) {
                    breadStacks++;
                }
            }
        }
        expect(after == total, "sorting conserves every item: " + after + " vs " + total);
        expect(breadStacks == 1 && Containers.count(inv, new org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD, 1)) == 35, "bread merged into one stack");
        boolean packed = true;
        boolean seenGap = false;
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                seenGap = true;
            } else if (seenGap) {
                packed = false;
            }
        }
        expect(packed, "items are packed from the first slot");
        expect(!sorter.sort(c, inv, null, true), "sorting an already sorted barrel changes nothing");
    }

    // ------------------------------------------------------------------ announcer cooldown
    static void testAnnouncerCooldown() {
        expect(Announcer.offCooldown(Long.MIN_VALUE, 5, 1000), "never spoken passes");
        expect(!Announcer.offCooldown(1000, 1500, 1000), "inside the window is blocked");
        expect(Announcer.offCooldown(1000, 2000, 1000) && Announcer.offCooldown(1000, 9000, 1000), "at and past the window passes");
        expect(Announcer.offCooldown(100, 100, 0), "zero cooldown always passes");
    }

    // ------------------------------------------------------------------ mover structure search with an offset
    static void testStructureWithOffset() {
        final Set<String> honey = new HashSet<>();
        final Set<String> stone = new HashSet<>();
        BlockView view = new BlockView() {
            @Override
            public Reaction reaction(int x, int y, int z) {
                String k = x + "," + y + "," + z;
                if (honey.contains(k) || stone.contains(k)) {
                    return Reaction.MOVE;
                }
                if (x == 0 && y == 0 && z == 0) {
                    return Reaction.BLOCK;
                }
                return Reaction.AIR;
            }

            @Override
            public boolean sticky(int x, int y, int z) {
                return honey.contains(x + "," + y + "," + z);
            }
        };
        // mover at the origin; a 2-block platform glued on top, then the same platform carried 3 blocks up
        honey.add("0,1,0");
        stone.add("1,1,0");
        StructurePlanner.Plan home = StructurePlanner.collect(view, 0, 0, 0, 0, 0, 0, 10);
        expect(home.ok() && home.cells().size() == 2, "structure found at home: " + home.reason());
        honey.clear();
        stone.clear();
        honey.add("0,4,0");
        stone.add("1,4,0");
        StructurePlanner.Plan away = StructurePlanner.collect(view, 0, 0, 0, 0, 3, 0, 10);
        expect(away.ok() && away.cells().size() == 2, "structure found again after travelling 3 up: " + away.reason());
        StructurePlanner.Plan lost = StructurePlanner.collect(view, 0, 0, 0, 0, 0, 0, 10);
        expect(!lost.ok(), "wrong offset finds nothing");
        // the mover itself is never swept up, even when a glued block touches it from an offset
        honey.clear();
        honey.add("0,1,0");
        StructurePlanner.Plan self = StructurePlanner.collect(view, 0, 0, 0, 0, 0, 0, 10);
        expect(self.ok() && self.cells().size() == 1, "the mover is not part of its own structure");
    }

    // ------------------------------------------------------------------ the Engineer's Manual

    private static void testManual() {
        RedstoneConfig cfg = new RedstoneConfig(null);
        List<String> pages = EngineerManual.pages(cfg);
        expect(pages.size() >= 20 && pages.size() <= 100, "manual length is a sane written-book size: " + pages.size());
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i);
            expect(!page.isBlank(), "manual page " + (i + 1) + " is not blank");
            expect(EngineerManual.fits(page), "manual page " + (i + 1) + " fits a book page (lines "
                    + EngineerManual.lineCount(page) + ", chars " + EngineerManual.visible(page).length() + ")");
        }

        // the contents are generated, and every entry must land on the first page of its chapter
        List<String> titles = EngineerManual.chapterTitles(cfg);
        List<String> firsts = EngineerManual.chapterFirstPages(cfg);
        expect(titles.size() == firsts.size() && titles.size() >= 15, "manual has a chapter list: " + titles.size());
        int entry = 0;
        int contentsPages = 0;
        for (int i = 1; i < pages.size() && EngineerManual.visible(pages.get(i)).startsWith("Contents"); i++) {
            contentsPages++;
            String[] lines = EngineerManual.visible(pages.get(i)).split("\n");
            for (int l = 1; l < lines.length; l++) {
                String line = lines[l].trim();
                int space = line.lastIndexOf(' ');
                int target = Integer.parseInt(line.substring(space + 1));
                expect(target >= 1 && target <= pages.size() && pages.get(target - 1).equals(firsts.get(entry)),
                        "contents entry '" + line + "' points at the first page of chapter " + titles.get(entry));
                expect(titles.get(entry).startsWith(line.substring(0, space)), "contents entry names its chapter: " + line);
                entry++;
            }
        }
        expect(contentsPages >= 1 && entry == titles.size(), "contents lists every chapter exactly once: " + entry + "/" + titles.size());
        expect(EngineerManual.visible(pages.get(0)).contains("MANUAL"), "page one is the cover");

        // the book states what the plugin really does, and follows the config
        String all = String.join("\n", pages);
        expect(all.contains("32") && all.contains("128") && all.contains("512"), "wireless ranges appear in the book");
        expect(all.contains("" + cfg.megaLimitCeiling) && all.contains("" + cfg.skillMaxLevel), "mega piston reach and max level appear");
        for (ComponentType t : ComponentType.values()) {
            if (t.family == Family.LOGIC) {
                expect(all.contains(t.id.toUpperCase(java.util.Locale.ROOT)), "the gate chapter names " + t.display);
            }
        }
        for (Tool tool : Tool.values()) {
            expect(all.toLowerCase(java.util.Locale.ROOT).contains(tool.display.split(" ")[tool.display.split(" ").length - 1].toLowerCase(java.util.Locale.ROOT)),
                    "the book mentions " + tool.display);
        }
        RedstoneConfig tuned = new RedstoneConfig(null);
        tuned.wirelessRangeRegional = 777;
        tuned.megaLimitCeiling = 99;
        String tunedText = String.join("\n", EngineerManual.pages(tuned));
        expect(tunedText.contains("777") && tunedText.contains("99"), "the book follows a tuned config");
        expect(EngineerManual.EDITION >= 1, "the manual has an edition number");

        // size rules themselves
        expect(EngineerManual.visible("\u00a7l\u00a74Hi\u00a7r there").equals("Hi there"), "format codes are not counted");
        expect(EngineerManual.lineCount("a\nb\nc") == 3, "each newline is a line");
        expect(EngineerManual.lineCount("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") > 2, "a very long word wraps");
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            tooMany.append("x\n");
        }
        expect(!EngineerManual.fits(tooMany.toString()), "a 20-line page is rejected");

        // the title is clipped to a written book's 32-character limit
        RedstoneConfig longTitle = new RedstoneConfig(null);
        longTitle.load(new org.bukkit.configuration.file.YamlConfiguration() {
            @Override
            public String getString(String path, String def) {
                return path.equals("manual.title") ? "A title that is far longer than thirty-two characters" : def;
            }
        });
        expect(longTitle.manualTitle.length() <= 32, "a long manual title is clipped: " + longTitle.manualTitle);
    }
}
