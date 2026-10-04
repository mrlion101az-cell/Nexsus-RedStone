package com.nexuscraft.nexusredstone;

/**
 * The arithmetic behind the analog output cell: a container whose fill level a vanilla
 * comparator reads as a 0-15 signal. Vanilla's rule (per container) is: 0 if empty, otherwise
 * {@code floor(fullness * 14) + 1}, where fullness is the sum over slots of {@code count /
 * maxStack}, divided by the number of slots. This class computes how many identical items to put
 * in the cell to produce a wanted strength, and the inverse, so the cell always reads exactly
 * what the component meant to say. A one-item safety margin keeps single-precision rounding in
 * the real game from ever dropping a strength by one.
 */
final class AnalogCellMath {

    private AnalogCellMath() {
    }

    /** Strength a comparator reads from {@code items} identical items spread over a container of
     *  {@code slots} slots whose stacks hold {@code maxStack}. */
    static int signalFor(int items, int slots, int maxStack) {
        if (items <= 0) {
            return 0;
        }
        double fullness = (double) items / ((double) slots * maxStack);
        return Math.min(15, (int) Math.floor(Math.min(1.0, fullness) * 14.0) + 1);
    }

    /** Items needed to read strength {@code signal} (0-15). */
    static int itemsForSignal(int signal, int slots, int maxStack) {
        int s = Math.max(0, Math.min(15, signal));
        if (s == 0) {
            return 0;
        }
        int total = slots * maxStack;
        if (s == 15) {
            return total;
        }
        if (s == 1) {
            return 1;
        }
        int needed = (int) Math.ceil((s - 1) * (double) total / 14.0) + 1;
        return Math.min(total - 1, needed);
    }

    /** Splits {@code items} into per-slot counts (full stacks first). */
    static int[] distribute(int items, int slots, int maxStack) {
        int[] counts = new int[slots];
        int left = Math.max(0, Math.min(items, slots * maxStack));
        for (int i = 0; i < slots && left > 0; i++) {
            counts[i] = Math.min(maxStack, left);
            left -= counts[i];
        }
        return counts;
    }
}
