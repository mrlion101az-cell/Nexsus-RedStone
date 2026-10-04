package com.nexuscraft.nexusredstone;

import java.util.logging.Logger;

/**
 * The plugin's heartbeat: every {@code stepIntervalTicks} server ticks it advances the pure
 * circuit engine one step, polls the power-driven machines (mega pistons, block movers), reports
 * vanilla redstone hot spots to staff, and settles activity XP and persistence on their own
 * slower cadences. Everything here runs on the main server thread.
 */
final class SimulationTask implements Runnable {

    private final RedstoneConfig cfg;
    private final CircuitEngine engine;
    private final BukkitCircuitHost host;
    private final MegaPistonService pistons;
    private final MoverService movers;
    private final LagGuard lag;
    private final ComponentStore store;
    private final Logger log;

    private long tick;
    private long lastActivityTick;
    private long lastSaveTick;
    private CircuitEngine.StepStats lastStats = new CircuitEngine.StepStats(0, 0, 0, 0);
    private long totalNanos;
    private long steps;
    private long lastStepNanos;

    SimulationTask(RedstoneConfig cfg, CircuitEngine engine, BukkitCircuitHost host, MegaPistonService pistons,
                   MoverService movers, LagGuard lag, ComponentStore store, Logger log) {
        this.cfg = cfg;
        this.engine = engine;
        this.host = host;
        this.pistons = pistons;
        this.movers = movers;
        this.lag = lag;
        this.store = store;
        this.log = log;
    }

    long tick() {
        return tick;
    }

    CircuitEngine.StepStats lastStats() {
        return lastStats;
    }

    /** Average milliseconds one simulation step has cost since startup. */
    double averageStepMillis() {
        return steps == 0 ? 0.0 : totalNanos / 1.0e6 / steps;
    }

    double lastStepMillis() {
        return lastStepNanos / 1.0e6;
    }

    @Override
    public void run() {
        tick += cfg.stepIntervalTicks;
        long started = System.nanoTime();
        try {
            lastStats = engine.step(tick, host);
            pistons.poll(tick);
            movers.poll(tick);
        } catch (RuntimeException e) {
            log.severe("nexusredstone: simulation step failed: " + e);
            e.printStackTrace();
        }
        lastStepNanos = System.nanoTime() - started;
        totalNanos += lastStepNanos;
        steps++;

        for (LagGuard.HotChunk hot : lag.drainVanillaAlerts()) {
            for (org.bukkit.entity.Player p : ComponentService.staff()) {
                p.sendMessage("§c§l⚠ §cVanilla redstone is very busy in chunk " + hot.chunkKey() + " (about "
                        + hot.perSecond() + " updates per second). A clock or a runaway loop may be lagging the server.");
            }
        }
        if (tick - lastActivityTick >= cfg.activityIntervalSeconds * 20L) {
            lastActivityTick = tick;
            host.settleActivity();
        }
        if (tick - lastSaveTick >= cfg.saveIntervalSeconds * 20L) {
            lastSaveTick = tick;
            store.save(log, false);
        }
    }
}
