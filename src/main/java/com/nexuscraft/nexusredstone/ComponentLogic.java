package com.nexuscraft.nexusredstone;

/**
 * The pure behaviour of every circuit component: given the inputs it sees this step and the
 * current tick, compute its new output strength (0-15), updating whatever small runtime state it
 * keeps. Digital components drive 0 or 15; analog ones drive any 0-15. Wireless transmitters and
 * non-circuit families are never evaluated here -- see {@link CircuitEngine}.
 *
 * <p>Edge detection compares against the {@code prev*} fields stored on the component, which
 * this method refreshes at the end of every call, so a rising edge is seen exactly once even if
 * a step is skipped by the lag guard.
 */
final class ComponentLogic {

    private ComponentLogic() {
    }

    static int evaluate(Component c, Inputs in, EvalContext ctx) {
        int out = compute(c, in, ctx);
        c.prevLeft = in.left();
        c.prevRight = in.right();
        c.prevBack = in.back();
        return out;
    }

    private static boolean rising(int prev, int now) {
        return prev == 0 && now > 0;
    }

    private static int digital(boolean on) {
        return on ? 15 : 0;
    }

    private static int compute(Component c, Inputs in, EvalContext ctx) {
        boolean l = in.left() > 0;
        boolean r = in.right() > 0;
        long now = ctx.now();
        switch (c.type) {
            case AND:
                return digital(l && r);
            case OR:
                return digital(l || r);
            case XOR:
                return digital(l ^ r);
            case NOT:
                return digital(in.back() == 0);
            case NAND:
                return digital(!(l && r));
            case NOR:
                return digital(!(l || r));
            case XNOR:
                return digital(l == r);
            case SR_LATCH:
                if (l && r) {
                    c.state = c.param("setdominant") == 1;
                } else if (l) {
                    c.state = true;
                } else if (r) {
                    c.state = false;
                }
                return digital(c.state);
            case T_FLIPFLOP:
                if (l) {
                    c.state = false;
                } else if (rising(c.prevBack, in.back())) {
                    c.state = !c.state;
                }
                return digital(c.state);
            case COUNTER:
                return counter(c, in, now, l);
            case ON_DELAY:
                return onDelay(c, in, now);
            case OFF_DELAY:
                return offDelay(c, in, now);
            case PULSE:
                return pulse(c, in, now);
            case CLOCK:
                return clock(c, in, now);
            case AMPLIFIER:
                return clamp15((in.strongest() * c.param("gain") + 50) / 100);
            case ANALOG_MATH:
                return analogMath(c.param("op"), in.left(), in.right());
            case THRESHOLD:
                return threshold(c, in.back());
            case WIRELESS_RX:
                return c.param("analog") == 1 ? clamp15(in.external()) : digital(in.external() > 0);
            case SENSOR_PLAYER:
            case SENSOR_MOB:
            case SENSOR_ITEM:
            case SENSOR_LIGHT:
            case SENSOR_WEATHER:
            case SENSOR_TIME:
            case SENSOR_FILL:
                return SensorLogic.signal(c, in.external());
            case PLC:
                return plc(c, in, ctx);
            default:
                return 0;
        }
    }

    private static int clamp15(int v) {
        return Math.max(0, Math.min(15, v));
    }

    static int analogMath(int op, int a, int b) {
        switch (op) {
            case 0:
                return clamp15(a + b);
            case 1:
                return clamp15(a - b);
            case 2:
                return Math.min(a, b);
            case 3:
                return Math.max(a, b);
            default:
                return clamp15((a + b) / 2);
        }
    }

    private static int threshold(Component c, int v) {
        int level = c.param("level");
        int hyst = c.param("hyst");
        if (c.state) {
            c.state = v >= Math.max(1, level - hyst);
        } else {
            c.state = v >= level;
        }
        return digital(c.state);
    }

    private static int counter(Component c, Inputs in, long now, boolean resetHeld) {
        int target = Math.max(1, c.param("target"));
        int mode = c.param("mode");
        if (resetHeld) {
            c.counter = 0;
            c.pulseActive = false;
            return 0;
        }
        if (rising(c.prevBack, in.back())) {
            c.counter = Math.min(9999, c.counter + 1);
        }
        switch (mode) {
            case 0:
                if (c.counter >= target && !c.pulseActive) {
                    c.pulseActive = true;
                    c.pulseUntil = now + c.param("pulse");
                    c.counter = 0;
                }
                if (c.pulseActive && now >= c.pulseUntil) {
                    c.pulseActive = false;
                }
                return digital(c.pulseActive);
            case 1:
                return digital(c.counter >= target);
            default:
                if (c.counter >= target) {
                    return 15;
                }
                return clamp15(c.counter * 15 / target);
        }
    }

    private static int onDelay(Component c, Inputs in, long now) {
        if (in.back() > 0) {
            if (c.timerStart < 0) {
                c.timerStart = now;
            }
            return digital(now - c.timerStart >= c.param("delay"));
        }
        c.timerStart = -1;
        return 0;
    }

    private static int offDelay(Component c, Inputs in, long now) {
        if (in.back() > 0) {
            c.timerStart = -1;
            c.state = true;
            return 15;
        }
        if (!c.state) {
            return 0;
        }
        if (c.timerStart < 0) {
            c.timerStart = now;
        }
        if (now - c.timerStart >= c.param("delay")) {
            c.state = false;
            c.timerStart = -1;
            return 0;
        }
        return 15;
    }

    private static int pulse(Component c, Inputs in, long now) {
        if (rising(c.prevBack, in.back()) && !c.pulseActive) {
            c.pulseActive = true;
            c.pulseUntil = now + c.param("length");
        }
        if (c.pulseActive && now >= c.pulseUntil) {
            c.pulseActive = false;
        }
        return digital(c.pulseActive);
    }

    private static int clock(Component c, Inputs in, long now) {
        int period = Math.max(1, c.param("period"));
        int high = Math.max(1, Math.min(period, c.param("high")));
        int mode = c.param("mode");
        boolean running;
        switch (mode) {
            case 1:
                running = in.back() > 0;
                break;
            case 2:
                running = in.back() == 0;
                break;
            case 3:
                if (rising(c.prevBack, in.back())) {
                    c.clockEpoch = now;
                }
                running = c.clockEpoch >= 0 && now - c.clockEpoch < (long) c.param("shots") * period;
                if (!running) {
                    c.clockEpoch = -1;
                }
                break;
            default:
                running = true;
                break;
        }
        if (!running) {
            if (mode != 3) {
                c.clockEpoch = -1;
            }
            return 0;
        }
        if (c.clockEpoch < 0) {
            c.clockEpoch = now;
        }
        return digital((now - c.clockEpoch) % period < high);
    }

    private static int plc(Component c, Inputs in, EvalContext ctx) {
        if (c.compiled == null || !c.text.equals(c.compiledSource)) {
            c.compiledSource = c.text;
            Expression.Result res = Expression.compile(c.text, ctx.plcMaxOps(), ctx.plcMaxLength());
            c.compiled = res.expr();
            c.compileError = res.error();
        }
        if (c.compiled == null) {
            return 0;
        }
        long[] vars = new long[Expression.VAR_COUNT];
        vars[Expression.VAR_A] = in.left();
        vars[Expression.VAR_B] = in.right();
        vars[Expression.VAR_C] = in.back();
        vars[Expression.VAR_S] = c.output;
        vars[Expression.VAR_M] = c.memory;
        vars[Expression.VAR_T] = ctx.now() % 24000L;
        vars[Expression.VAR_X] = in.external();
        int out = c.compiled.evalOutput(vars);
        c.memory = c.compiled.evalMemory(vars);
        return c.param("analog") == 1 ? out : digital(out > 0);
    }
}
