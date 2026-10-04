package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure helpers for smart dispenser/dropper modes. Modes: 0 normal, 1 burst (extra shots a few
 * ticks apart), 2 spray (dispenser: random spread) or gentle (dropper: zero velocity, drops
 * straight down at the block face), 3 power (a velocity multiplier).
 */
final class DispenserModes {

    static final int NORMAL = 0;
    static final int BURST = 1;
    static final int SPRAY_OR_GENTLE = 2;
    static final int POWER = 3;

    private DispenserModes() {
    }

    /** Ticks after the triggering shot at which each extra burst shot fires. */
    static List<Integer> burstOffsets(int extraShots, int gapTicks) {
        List<Integer> offsets = new ArrayList<>();
        for (int i = 1; i <= extraShots; i++) {
            offsets.add(i * Math.max(1, gapTicks));
        }
        return offsets;
    }

    /** New velocity for one dispensed item, given the vanilla velocity. */
    static double[] velocity(int mode, boolean dropper, double vx, double vy, double vz, RedstoneConfig cfg, Random rng) {
        if (mode == SPRAY_OR_GENTLE) {
            if (dropper) {
                return new double[] {0, 0, 0};
            }
            double j = cfg.sprayVelocityJitter;
            return new double[] {
                    vx + (rng.nextDouble() * 2 - 1) * j,
                    vy + (rng.nextDouble() * 2 - 1) * j * 0.5,
                    vz + (rng.nextDouble() * 2 - 1) * j};
        }
        if (mode == POWER) {
            double m = cfg.powerVelocityMultiplier;
            return new double[] {vx * m, vy * m, vz * m};
        }
        return new double[] {vx, vy, vz};
    }

    static String modeName(boolean dropper, int mode) {
        switch (mode) {
            case BURST:
                return "burst";
            case SPRAY_OR_GENTLE:
                return dropper ? "gentle" : "spray";
            case POWER:
                return "power";
            default:
                return "normal";
        }
    }
}
