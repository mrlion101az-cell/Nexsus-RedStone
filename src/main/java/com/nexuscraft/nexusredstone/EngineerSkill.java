package com.nexuscraft.nexusredstone;

/**
 * The math behind a player's Engineer mastery. XP required for level {@code n} follows {@code
 * xpBase * n^1.5} (the same fixed curve shape every mastery skill in this family uses, compare
 * NexusSmelting's {@code SmeltingSkill}), and total XP is the only thing persisted -- level is
 * always derived from it.
 *
 * <p>Unlike most skills in this family, Engineer is mostly a <em>gate</em> rather than a luck
 * modifier: a redstone contraption is deterministic by nature, so mastery unlocks bigger
 * components, longer ranges, faster clocks and larger budgets instead of rolling dice. Every
 * "how much can I do" number lives here as a small pure function of level and config, which keeps
 * the whole progression curve unit-testable.
 */
final class EngineerSkill {

    private EngineerSkill() {
    }

    static long xpForLevel(int level, RedstoneConfig cfg) {
        return Math.round(cfg.skillXpBase * Math.pow(level, 1.5));
    }

    static int levelFor(long totalXp, RedstoneConfig cfg) {
        int level = 1;
        while (level < cfg.skillMaxLevel && totalXp >= xpForLevel(level + 1, cfg)) {
            level++;
        }
        return level;
    }

    static long xpForNextLevel(int level, RedstoneConfig cfg) {
        return level >= cfg.skillMaxLevel ? -1 : xpForLevel(level + 1, cfg);
    }

    static boolean canUse(ComponentType type, int level) {
        return level >= type.minLevel;
    }

    /** Most components a player may own at once. */
    static int maxComponents(int level, RedstoneConfig cfg) {
        long v = (long) cfg.maxComponentsBase + (long) Math.max(0, level - 1) * cfg.maxComponentsPerLevel;
        return (int) Math.min(cfg.maxComponentsCeiling, v);
    }

    /** Fastest full clock cycle (in ticks) a player may configure: slower for a novice, shrinking
     *  by one tick per {@code clockLevelsPerTick} levels down to the floor. */
    static int minClockPeriod(int level, RedstoneConfig cfg) {
        int reduction = Math.max(0, level - 1) / cfg.clockLevelsPerTick;
        return Math.max(Math.max(cfg.minClockPeriodFloor, cfg.stepIntervalTicks), cfg.minClockPeriodBase - reduction);
    }

    /** Highest wireless tier (0 local .. 3 cross-dimension) this level may use. */
    static int maxWirelessTier(int level, RedstoneConfig cfg) {
        if (level >= cfg.wirelessLevelCross) {
            return 3;
        }
        if (level >= cfg.wirelessLevelLong) {
            return 2;
        }
        if (level >= cfg.wirelessLevelRegional) {
            return 1;
        }
        return 0;
    }

    /** Operations a programmable block's program may contain. */
    static int plcOps(int level, RedstoneConfig cfg) {
        long v = (long) cfg.plcBaseOps + (long) Math.max(0, level - 1) * cfg.plcOpsPerLevel;
        return (int) Math.min(cfg.plcMaxOps, v);
    }

    /** Blocks a mega piston may push at once. */
    static int megaPistonLimit(int level, RedstoneConfig cfg) {
        long v = (long) cfg.megaLimitBase + (long) Math.max(0, level - 1) * cfg.megaLimitPerLevel;
        return (int) Math.min(cfg.megaLimitCeiling, v);
    }

    /** Blocks a block-mover structure may contain. */
    static int moverBlocks(int level, RedstoneConfig cfg) {
        long v = (long) cfg.moverBlocksBase + (long) Math.max(0, level - 1) * cfg.moverBlocksPerLevel;
        return (int) Math.min(cfg.moverBlocksCeiling, v);
    }

    /** Blocks a blueprint capture may include. */
    static int blueprintBlocks(int level, RedstoneConfig cfg) {
        long v = (long) cfg.blueprintBlocksBase + (long) Math.max(0, level - 1) * cfg.blueprintBlocksPerLevel;
        return (int) Math.min(cfg.blueprintBlocksCeiling, v);
    }

    /** Longest pipe path (in pipe blocks) an item may be routed along. */
    static int pipeLength(int level, RedstoneConfig cfg) {
        long v = (long) cfg.pipeLengthBase + (long) Math.max(0, level - 1) * cfg.pipeLengthPerLevel;
        return (int) Math.min(cfg.pipeLengthCeiling, v);
    }

    /** Items a port moves per pull. */
    static int portItems(int level, RedstoneConfig cfg) {
        double v = cfg.portItemsBase + Math.max(0, level - 1) * cfg.portItemsPerLevel;
        return (int) Math.min(cfg.portItemsCeiling, Math.floor(v));
    }

    /** Multiplier on the lag guard's per-chunk update budget: a master engineer earns headroom,
     *  because their circuits are, on average, better built. */
    static double lagBudgetMultiplier(int level, RedstoneConfig cfg) {
        return 1.0 + Math.max(0, level - 1) * cfg.lagBudgetBonusPerLevel;
    }

    /** Short title for a level, used in messages and the skill readout. */
    static String title(int level) {
        if (level >= 45) {
            return "Grand Artificer";
        }
        if (level >= 35) {
            return "Master Engineer";
        }
        if (level >= 25) {
            return "Circuit Architect";
        }
        if (level >= 15) {
            return "Journeyman Engineer";
        }
        if (level >= 8) {
            return "Wire Wright";
        }
        if (level >= 3) {
            return "Tinkerer";
        }
        return "Apprentice";
    }
}
