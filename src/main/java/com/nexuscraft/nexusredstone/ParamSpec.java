package com.nexuscraft.nexusredstone;

/** One small integer setting of a component (a clock's period, a gate-less counter's target).
 *  Declared once per {@link ComponentType}, which is what lets one generic set/cycle/validate path
 *  serve the wrench, the command, and the blueprint codec instead of a hand-written setter per
 *  component. */
record ParamSpec(String name, int min, int max, int def, String help) {

    int clamp(int value) {
        return Math.max(min, Math.min(max, value));
    }

    /** Next value when cycling with the wrench: +1, wrapping from max back to min. */
    int next(int current) {
        return current >= max ? min : current + 1;
    }

    /** Next value for the wrench's cycle: small ranges step by one; wide ranges (a clock's
     *  2 to 2400 ticks) take bigger strides the further up they go, so cycling stays useful. */
    int cycle(int current) {
        if (max - min <= 16) {
            return next(current);
        }
        int step = current < 10 ? 1 : current < 50 ? 5 : current < 200 ? 10 : current < 1000 ? 50 : 100;
        int n = current + step;
        return n > max ? min : n;
    }
}
