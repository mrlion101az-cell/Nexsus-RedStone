package com.nexuscraft.nexusredstone;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/**
 * Parses config.yml's tunables. Same "config drives the shared numbers, the enums and catalogs
 * hold the fixed game-balance shape" split this whole family uses (compare NexusSmelting's own
 * {@code SmeltingConfig}): {@link ComponentType}'s unlock levels and setting ranges are the fixed
 * half; everything below is the tunable half. Every value is clamped on load so a typo in
 * config.yml can never produce a zero-length timer, a negative budget, or an unbounded loop.
 */
final class RedstoneConfig {

    private final Plugin plugin;

    boolean enabled = true;

    // -- Engineer mastery skill --
    int skillMaxLevel = 50;
    double skillXpBase = 10.0;
    double xpPlaceScale = 1.0;
    int activityXpPerInterval = 2;
    int activityXpCapPerInterval = 8;
    long activityIntervalSeconds = 120;
    int xpPerThousandItemsMoved = 4;

    // -- Simulation --
    int stepIntervalTicks = 2;
    int sensorIntervalTicks = 10;
    int saveIntervalSeconds = 60;
    int maxComponentsBase = 48;
    int maxComponentsPerLevel = 6;
    int maxComponentsCeiling = 400;
    boolean requireLoadedChunk = true;

    // -- Clocks and timers --
    int minClockPeriodBase = 10;
    int minClockPeriodFloor = 2;
    int clockLevelsPerTick = 4;

    // -- Wireless --
    int wirelessRangeLocal = 32;
    int wirelessRangeRegional = 128;
    int wirelessRangeLong = 512;
    int wirelessLevelRegional = 18;
    int wirelessLevelLong = 28;
    int wirelessLevelCross = 40;
    double wirelessBlocksPerTick = 64.0;
    int wirelessStaleTicks = 200;
    int maxChannelLength = 16;

    // -- Sensors --
    int sensorMaxRadius = 32;

    // -- Programmable block --
    int plcBaseOps = 16;
    int plcOpsPerLevel = 2;
    int plcMaxOps = 160;
    int plcMaxLength = 200;

    // -- Lag-machine protection --
    boolean lagEnabled = true;
    int lagWindowTicks = 100;
    int lagMaxTogglesPerWindow = 48;
    int lagChunkBudgetPerSecond = 600;
    double lagBudgetBonusPerLevel = 0.02;
    int lagThrottleDivisor = 4;
    int lagTripCooldownSeconds = 30;
    int lagAlertCooldownSeconds = 60;
    int vanillaRedstoneWarnPerSecond = 3000;

    // -- Hoppers --
    int hopperVacuumRadiusDiamond = 2;
    int hopperVacuumRadiusNetherite = 4;
    int hopperVacuumIntervalTicks = 8;
    int hopperVacuumMaxItemsPerRun = 8;
    double hopperBatchMultiplier = 1.0;

    // -- Item transport network --
    int portItemsBase = 8;
    double portItemsPerLevel = 0.5;
    int portItemsCeiling = 64;
    int pipeLengthBase = 24;
    int pipeLengthPerLevel = 2;
    int pipeLengthCeiling = 160;
    int networkMaxNodes = 600;
    int pathTicksPerPipe = 1;

    // -- Smart storage --
    int sorterCooldownTicks = 20;

    // -- Dispensers and droppers --
    double sprayVelocityJitter = 0.18;
    double powerVelocityMultiplier = 2.0;

    // -- Mega piston --
    int megaLimitBase = 24;
    int megaLimitPerLevel = 1;
    int megaLimitCeiling = 64;
    int megaCooldownTicks = 10;

    // -- Block mover --
    int moverBlocksBase = 32;
    int moverBlocksPerLevel = 6;
    int moverBlocksCeiling = 256;
    int moverMaxDistance = 32;
    int moverDisplayCap = 160;
    int moverCooldownTicks = 20;
    boolean moverCarryEntities = true;

    // -- Circuit blueprints --
    int blueprintBlocksBase = 48;
    int blueprintBlocksPerLevel = 8;
    int blueprintBlocksCeiling = 512;
    int blueprintMaxEncodedChars = 60000;
    boolean blueprintClaimProbe = true;

    // -- Debugger, visualizer, oscilloscope --
    int toolCooldownTicks = 6;
    int scopeSamples = 160;
    int maxProbesPerPlayer = 4;
    int visualizerSeconds = 12;
    int visualizerRadius = 24;

    // -- Dramatic messages & villager commentary --
    boolean messagesEnabled = true;
    boolean commentaryEnabled = true;
    double commentaryRadius = 10.0;
    long commentaryCooldownSeconds = 30;
    long messageCooldownSeconds = 8;

    // -- The Engineer's Manual (written book handed to every player) --
    boolean manualGiveOnJoin = true;
    String manualTitle = "Redstone Engineer's Manual";
    String manualAuthor = "NexusRedstone";

    // -- Recipes --
    boolean recipesEnabled = true;

    RedstoneConfig(Plugin plugin) {
        this.plugin = plugin;
    }

    void load(ConfigurationSection c) {
        if (c == null) {
            return;
        }
        enabled = c.getBoolean("enabled", enabled);

        skillMaxLevel = Math.max(5, c.getInt("skill.max-level", skillMaxLevel));
        skillXpBase = Math.max(1.0, c.getDouble("skill.xp-base", skillXpBase));
        xpPlaceScale = clampNonNegative(c.getDouble("skill.xp-place-scale", xpPlaceScale));
        activityXpPerInterval = Math.max(0, c.getInt("skill.activity-xp", activityXpPerInterval));
        activityXpCapPerInterval = Math.max(0, c.getInt("skill.activity-xp-cap", activityXpCapPerInterval));
        activityIntervalSeconds = Math.max(10, c.getLong("skill.activity-interval-seconds", activityIntervalSeconds));
        xpPerThousandItemsMoved = Math.max(0, c.getInt("skill.xp-per-thousand-items-moved", xpPerThousandItemsMoved));

        stepIntervalTicks = clamp(c.getInt("simulation.step-interval-ticks", stepIntervalTicks), 1, 10);
        sensorIntervalTicks = clamp(c.getInt("simulation.sensor-interval-ticks", sensorIntervalTicks), stepIntervalTicks, 200);
        saveIntervalSeconds = clamp(c.getInt("simulation.save-interval-seconds", saveIntervalSeconds), 10, 3600);
        maxComponentsBase = Math.max(1, c.getInt("simulation.max-components-base", maxComponentsBase));
        maxComponentsPerLevel = Math.max(0, c.getInt("simulation.max-components-per-level", maxComponentsPerLevel));
        maxComponentsCeiling = Math.max(maxComponentsBase, c.getInt("simulation.max-components-ceiling", maxComponentsCeiling));
        requireLoadedChunk = c.getBoolean("simulation.require-loaded-chunk", requireLoadedChunk);

        minClockPeriodBase = Math.max(2, c.getInt("clock.min-period-base", minClockPeriodBase));
        minClockPeriodFloor = Math.max(stepIntervalTicks, c.getInt("clock.min-period-floor", minClockPeriodFloor));
        clockLevelsPerTick = Math.max(1, c.getInt("clock.levels-per-tick", clockLevelsPerTick));

        wirelessRangeLocal = Math.max(1, c.getInt("wireless.range-local", wirelessRangeLocal));
        wirelessRangeRegional = Math.max(wirelessRangeLocal, c.getInt("wireless.range-regional", wirelessRangeRegional));
        wirelessRangeLong = Math.max(wirelessRangeRegional, c.getInt("wireless.range-long", wirelessRangeLong));
        wirelessLevelRegional = Math.max(1, c.getInt("wireless.level-regional", wirelessLevelRegional));
        wirelessLevelLong = Math.max(wirelessLevelRegional, c.getInt("wireless.level-long", wirelessLevelLong));
        wirelessLevelCross = Math.max(wirelessLevelLong, c.getInt("wireless.level-cross", wirelessLevelCross));
        wirelessBlocksPerTick = Math.max(1.0, c.getDouble("wireless.blocks-per-tick", wirelessBlocksPerTick));
        wirelessStaleTicks = Math.max(20, c.getInt("wireless.stale-ticks", wirelessStaleTicks));
        maxChannelLength = clamp(c.getInt("wireless.max-channel-length", maxChannelLength), 1, 32);

        sensorMaxRadius = clamp(c.getInt("sensors.max-radius", sensorMaxRadius), 1, 64);

        plcBaseOps = Math.max(2, c.getInt("plc.base-ops", plcBaseOps));
        plcOpsPerLevel = Math.max(0, c.getInt("plc.ops-per-level", plcOpsPerLevel));
        plcMaxOps = Math.max(plcBaseOps, c.getInt("plc.max-ops", plcMaxOps));
        plcMaxLength = clamp(c.getInt("plc.max-length", plcMaxLength), 16, 400);

        lagEnabled = c.getBoolean("lag.enabled", lagEnabled);
        lagWindowTicks = clamp(c.getInt("lag.window-ticks", lagWindowTicks), 20, 1200);
        lagMaxTogglesPerWindow = Math.max(4, c.getInt("lag.max-toggles-per-window", lagMaxTogglesPerWindow));
        lagChunkBudgetPerSecond = Math.max(20, c.getInt("lag.chunk-budget-per-second", lagChunkBudgetPerSecond));
        lagBudgetBonusPerLevel = clampNonNegative(c.getDouble("lag.budget-bonus-per-level", lagBudgetBonusPerLevel));
        lagThrottleDivisor = clamp(c.getInt("lag.throttle-divisor", lagThrottleDivisor), 2, 20);
        lagTripCooldownSeconds = Math.max(1, c.getInt("lag.trip-cooldown-seconds", lagTripCooldownSeconds));
        lagAlertCooldownSeconds = Math.max(1, c.getInt("lag.alert-cooldown-seconds", lagAlertCooldownSeconds));
        vanillaRedstoneWarnPerSecond = Math.max(100, c.getInt("lag.vanilla-warn-per-second", vanillaRedstoneWarnPerSecond));

        hopperVacuumRadiusDiamond = clamp(c.getInt("hoppers.vacuum-radius-diamond", hopperVacuumRadiusDiamond), 1, 8);
        hopperVacuumRadiusNetherite = clamp(c.getInt("hoppers.vacuum-radius-netherite", hopperVacuumRadiusNetherite), 1, 12);
        hopperVacuumIntervalTicks = clamp(c.getInt("hoppers.vacuum-interval-ticks", hopperVacuumIntervalTicks), 2, 100);
        hopperVacuumMaxItemsPerRun = clamp(c.getInt("hoppers.vacuum-max-items-per-run", hopperVacuumMaxItemsPerRun), 1, 64);
        hopperBatchMultiplier = Math.max(0.0, c.getDouble("hoppers.batch-multiplier", hopperBatchMultiplier));

        portItemsBase = Math.max(1, c.getInt("transport.port-items-base", portItemsBase));
        portItemsPerLevel = clampNonNegative(c.getDouble("transport.port-items-per-level", portItemsPerLevel));
        portItemsCeiling = Math.max(portItemsBase, c.getInt("transport.port-items-ceiling", portItemsCeiling));
        pipeLengthBase = Math.max(2, c.getInt("transport.pipe-length-base", pipeLengthBase));
        pipeLengthPerLevel = Math.max(0, c.getInt("transport.pipe-length-per-level", pipeLengthPerLevel));
        pipeLengthCeiling = Math.max(pipeLengthBase, c.getInt("transport.pipe-length-ceiling", pipeLengthCeiling));
        networkMaxNodes = clamp(c.getInt("transport.network-max-nodes", networkMaxNodes), 16, 5000);
        pathTicksPerPipe = Math.max(0, c.getInt("transport.path-ticks-per-pipe", pathTicksPerPipe));

        sorterCooldownTicks = Math.max(0, c.getInt("storage.sorter-cooldown-ticks", sorterCooldownTicks));

        sprayVelocityJitter = clampNonNegative(c.getDouble("dispensers.spray-jitter", sprayVelocityJitter));
        powerVelocityMultiplier = Math.max(1.0, c.getDouble("dispensers.power-multiplier", powerVelocityMultiplier));

        megaLimitBase = Math.max(1, c.getInt("mega-piston.limit-base", megaLimitBase));
        megaLimitPerLevel = Math.max(0, c.getInt("mega-piston.limit-per-level", megaLimitPerLevel));
        megaLimitCeiling = Math.max(megaLimitBase, c.getInt("mega-piston.limit-ceiling", megaLimitCeiling));
        megaCooldownTicks = Math.max(1, c.getInt("mega-piston.cooldown-ticks", megaCooldownTicks));

        moverBlocksBase = Math.max(1, c.getInt("mover.blocks-base", moverBlocksBase));
        moverBlocksPerLevel = Math.max(0, c.getInt("mover.blocks-per-level", moverBlocksPerLevel));
        moverBlocksCeiling = Math.max(moverBlocksBase, c.getInt("mover.blocks-ceiling", moverBlocksCeiling));
        moverMaxDistance = clamp(c.getInt("mover.max-distance", moverMaxDistance), 1, 64);
        moverDisplayCap = clamp(c.getInt("mover.display-cap", moverDisplayCap), 0, 512);
        moverCooldownTicks = Math.max(1, c.getInt("mover.cooldown-ticks", moverCooldownTicks));
        moverCarryEntities = c.getBoolean("mover.carry-entities", moverCarryEntities);

        blueprintBlocksBase = Math.max(1, c.getInt("blueprints.blocks-base", blueprintBlocksBase));
        blueprintBlocksPerLevel = Math.max(0, c.getInt("blueprints.blocks-per-level", blueprintBlocksPerLevel));
        blueprintBlocksCeiling = Math.max(blueprintBlocksBase, c.getInt("blueprints.blocks-ceiling", blueprintBlocksCeiling));
        blueprintClaimProbe = c.getBoolean("blueprints.claim-probe", blueprintClaimProbe);
        blueprintMaxEncodedChars = clamp(c.getInt("blueprints.max-encoded-chars", blueprintMaxEncodedChars), 1000, 200000);

        toolCooldownTicks = Math.max(0, c.getInt("tools.cooldown-ticks", toolCooldownTicks));
        scopeSamples = clamp(c.getInt("tools.scope-samples", scopeSamples), 16, 1000);
        maxProbesPerPlayer = clamp(c.getInt("tools.max-probes-per-player", maxProbesPerPlayer), 1, 16);
        visualizerSeconds = clamp(c.getInt("tools.visualizer-seconds", visualizerSeconds), 2, 120);
        visualizerRadius = clamp(c.getInt("tools.visualizer-radius", visualizerRadius), 4, 64);

        messagesEnabled = c.getBoolean("messages.enabled", messagesEnabled);
        commentaryEnabled = c.getBoolean("messages.villager-commentary", commentaryEnabled);
        commentaryRadius = Math.max(1.0, c.getDouble("messages.villager-radius", commentaryRadius));
        commentaryCooldownSeconds = Math.max(1, c.getLong("messages.villager-cooldown-seconds", commentaryCooldownSeconds));
        messageCooldownSeconds = Math.max(0, c.getLong("messages.cooldown-seconds", messageCooldownSeconds));

        manualGiveOnJoin = c.getBoolean("manual.give-on-join", manualGiveOnJoin);
        manualTitle = clipTitle(c.getString("manual.title", manualTitle), manualTitle);
        manualAuthor = clipTitle(c.getString("manual.author", manualAuthor), manualAuthor);
        recipesEnabled = c.getBoolean("recipes.enabled", recipesEnabled);
    }

    Plugin plugin() {
        return plugin;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double clampNonNegative(double v) {
        return v < 0 ? 0 : v;
    }

    /** A written book's title is limited to 32 characters; blank or missing falls back to the default. */
    private static String clipTitle(String raw, String fallback) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            return fallback;
        }
        return t.length() > 32 ? t.substring(0, 32) : t;
    }
}
