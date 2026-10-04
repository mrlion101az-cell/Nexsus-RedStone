package com.nexuscraft.nexusredstone;

import java.util.List;

/**
 * The simulation loop for every circuit component. One call to {@link #step} advances every
 * loaded component by one step: sample sensors on their own slower cadence, exchange wireless
 * values, run each component's pure logic, drive the output cell when the strength changed, and
 * let the lag guard throttle or trip anything misbehaving.
 *
 * <p>Signals take one step (two game ticks by default) to hop from a component's output to the
 * next component's input -- the same one-redstone-tick-per-hop feel as a vanilla repeater chain,
 * which is a feature: it is what makes feedback loops (latches built from two gates, clocks
 * built from a NOT gate) behave like real redstone and not like instantaneous math.
 */
final class CircuitEngine {

    record StepStats(int evaluated, int changed, int skipped, int tripped) {
    }

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final WirelessNetwork wireless;
    private final LagGuard lag;

    CircuitEngine(RedstoneConfig cfg, ComponentRegistry registry, WirelessNetwork wireless, LagGuard lag) {
        this.cfg = cfg;
        this.registry = registry;
        this.wireless = wireless;
        this.lag = lag;
    }

    StepStats step(long now, CircuitHost host) {
        int evaluated = 0;
        int changed = 0;
        int skipped = 0;
        int tripped = 0;
        List<Component> list = registry.circuitComponents();
        for (int i = 0; i < list.size(); i++) {
            Component c = list.get(i);
            if (!host.isActive(c)) {
                continue;
            }
            int level = host.ownerLevel(c);

            if (c.lagTrippedUntil != 0) {
                if (now < c.lagTrippedUntil) {
                    if (c.output != 0) {
                        c.output = 0;
                        host.emit(c, 0);
                    }
                    tripped++;
                    continue;
                }
                c.lagTrippedUntil = 0;
                c.resetRuntime();
                host.event(c, CircuitEvent.RESUMED, "");
            }

            LagGuard.Verdict verdict = lag.check(c, now);
            if (verdict == LagGuard.Verdict.SKIP) {
                skipped++;
                continue;
            }

            Inputs in;
            if (c.type.isSensor()) {
                if (c.lastSensorTick == Long.MIN_VALUE || now - c.lastSensorTick >= cfg.sensorIntervalTicks) {
                    c.sensorRaw = host.readSensor(c);
                    c.lastSensorTick = now;
                }
                in = new Inputs(0, 0, 0, c.sensorRaw);
            } else if (c.type == ComponentType.WIRELESS_TX) {
                Inputs raw = host.readInputs(c);
                int tier = Math.min(c.param("tier"), EngineerSkill.maxWirelessTier(level, cfg));
                wireless.publish(c, raw.strongest(), now, tier);
                evaluated++;
                continue;
            } else if (c.type == ComponentType.WIRELESS_RX) {
                int tier = Math.min(c.param("tier"), EngineerSkill.maxWirelessTier(level, cfg));
                in = new Inputs(0, 0, 0, wireless.read(c, now, tier));
            } else {
                in = host.readInputs(c);
            }

            EvalContext ctx = new EvalContext(now, EngineerSkill.plcOps(level, cfg), cfg.plcMaxLength);
            String errBefore = c.compileError;
            int out = ComponentLogic.evaluate(c, in, ctx);
            evaluated++;
            if (c.type == ComponentType.PLC && c.compileError != null && !c.compileError.equals(errBefore)) {
                host.event(c, CircuitEvent.PLC_ERROR, c.compileError);
            }
            if (out != c.output) {
                c.output = out;
                changed++;
                lag.recordChange(c, now, level);
                host.emit(c, out);
                host.event(c, CircuitEvent.OUTPUT_CHANGED, String.valueOf(out));
            }
        }

        Component worst = lag.resolveWorstOffender(registry, now);
        if (worst != null && worst.output != 0) {
            worst.output = 0;
            host.emit(worst, 0);
        }
        for (LagGuard.Trip trip : lag.drainTrips()) {
            Component c = registry.get(trip.componentKey());
            if (c != null) {
                tripped++;
                if (c.output != 0) {
                    c.output = 0;
                    host.emit(c, 0);
                }
                host.event(c, CircuitEvent.TRIPPED, trip.reason());
            }
        }
        return new StepStats(evaluated, changed, skipped, tripped);
    }

    /** Drops everything the engine remembers about a removed component. */
    void forget(Component c) {
        wireless.remove(c.key());
        lag.forget(c.key());
    }
}
