package com.nexuscraft.nexusredstone;

/**
 * The oscilloscope's recorder: a fixed-size ring buffer of (tick, strength) change points for one
 * probed signal. Only changes are stored, so a steady signal costs one entry however long it is
 * watched, and the buffer can hold minutes of a busy clock. Rendering samples the change points
 * into evenly spaced columns, so what the player sees is the true waveform at any zoom.
 */
final class SignalTrace {

    private final long[] ticks;
    private final int[] values;
    private int head;
    private int size;
    private long lastRecordedTick = Long.MIN_VALUE;

    SignalTrace(int capacity) {
        int cap = Math.max(4, capacity);
        this.ticks = new long[cap];
        this.values = new int[cap];
    }

    /** Records an observation; stored only if the strength differs from the last stored one. */
    void record(long tick, int value) {
        int v = Math.max(0, Math.min(15, value));
        lastRecordedTick = tick;
        if (size > 0 && values[lastIndex()] == v) {
            return;
        }
        ticks[head] = tick;
        values[head] = v;
        head = (head + 1) % ticks.length;
        if (size < ticks.length) {
            size++;
        }
    }

    private int lastIndex() {
        return (head - 1 + ticks.length) % ticks.length;
    }

    int size() {
        return size;
    }

    long lastObservedTick() {
        return lastRecordedTick;
    }

    /** Strength at an instant (the most recent change at or before it; 0 before any data). */
    int valueAt(long tick) {
        int v = 0;
        for (int i = 0; i < size; i++) {
            int idx = (head - size + i + ticks.length * 2) % ticks.length;
            if (ticks[idx] <= tick) {
                v = values[idx];
            } else {
                break;
            }
        }
        return v;
    }

    int currentValue() {
        return size == 0 ? 0 : values[lastIndex()];
    }

    /** One hex digit (0-F) per column across the last {@code spanTicks} ticks. */
    String hexLine(int columns, long spanTicks, long now) {
        StringBuilder sb = new StringBuilder(columns);
        long step = Math.max(1, spanTicks / Math.max(1, columns));
        long start = now - step * columns;
        for (int c = 0; c < columns; c++) {
            sb.append(Character.toUpperCase(Character.forDigit(valueAt(start + c * step + step), 16)));
        }
        return sb.toString();
    }

    /** A small ASCII plot: {@code rows} rows tall, with '#' where the signal reaches that row. */
    String[] graph(int columns, int rows, long spanTicks, long now) {
        String[] out = new String[rows];
        long step = Math.max(1, spanTicks / Math.max(1, columns));
        long start = now - step * columns;
        int[] col = new int[columns];
        for (int c = 0; c < columns; c++) {
            col[c] = valueAt(start + c * step + step);
        }
        for (int r = 0; r < rows; r++) {
            // Row 0 is the top. A row is "lit" if the strength reaches its threshold.
            int threshold = (int) Math.round(15.0 * (rows - r) / rows);
            StringBuilder sb = new StringBuilder(columns);
            for (int c = 0; c < columns; c++) {
                sb.append(col[c] >= Math.max(1, threshold) ? '#' : '.');
            }
            out[r] = sb.toString();
        }
        return out;
    }

    /** Rising edges (0 to nonzero) among the stored change points inside the window. */
    int risingEdges(long spanTicks, long now) {
        int n = 0;
        int prev = -1;
        for (int i = 0; i < size; i++) {
            int idx = (head - size + i + ticks.length * 2) % ticks.length;
            if (ticks[idx] >= now - spanTicks) {
                if (prev == 0 && values[idx] > 0) {
                    n++;
                }
            }
            prev = values[idx];
        }
        return n;
    }

    /** Average ticks between rising edges inside the window, or -1 if fewer than two were seen. */
    long periodTicks(long spanTicks, long now) {
        long first = -1;
        long last = -1;
        int count = 0;
        int prev = -1;
        for (int i = 0; i < size; i++) {
            int idx = (head - size + i + ticks.length * 2) % ticks.length;
            if (ticks[idx] >= now - spanTicks && prev == 0 && values[idx] > 0) {
                if (first < 0) {
                    first = ticks[idx];
                }
                last = ticks[idx];
                count++;
            }
            prev = values[idx];
        }
        return count < 2 ? -1 : (last - first) / (count - 1);
    }

    /** Percent of the window the signal was nonzero. */
    int dutyPercent(long spanTicks, long now) {
        long step = Math.max(1, spanTicks / 100);
        int on = 0;
        int total = 0;
        for (long t = now - spanTicks; t <= now; t += step) {
            total++;
            if (valueAt(t) > 0) {
                on++;
            }
        }
        return total == 0 ? 0 : (int) Math.round(100.0 * on / total);
    }
}
