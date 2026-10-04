package com.nexuscraft.nexusredstone;

import java.util.Locale;

/**
 * Coarse item categories for the sorting barrel, decided purely from the material's name so the
 * whole thing is testable and works for every item including ones added in later game versions
 * (an unknown name simply falls into MISC). The order of the constants is the order a sorted
 * container lays them out in.
 */
enum ItemCategory {
    TOOLS, ARMOR, ORES, BUILDING, REDSTONE, FOOD, FARMING, MAGIC, MISC;

    static ItemCategory of(String material) {
        String m = material == null ? "" : material.toUpperCase(Locale.ROOT);
        if (endsWithAny(m, "_SWORD", "_AXE", "_PICKAXE", "_SHOVEL", "_HOE")
                || isAny(m, "BOW", "CROSSBOW", "TRIDENT", "SHIELD", "FISHING_ROD", "SHEARS", "FLINT_AND_STEEL", "MACE", "SPYGLASS")) {
            return TOOLS;
        }
        if (endsWithAny(m, "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS") || isAny(m, "ELYTRA", "TURTLE_HELMET")) {
            return ARMOR;
        }
        if (isRedstone(m)) {
            return REDSTONE;
        }
        if (endsWithAny(m, "_ORE", "_INGOT", "_NUGGET") || m.startsWith("RAW_")
                || isAny(m, "COAL", "CHARCOAL", "DIAMOND", "EMERALD", "LAPIS_LAZULI", "QUARTZ", "NETHERITE_SCRAP",
                "AMETHYST_SHARD", "ANCIENT_DEBRIS", "COPPER_INGOT", "GLOWSTONE_DUST")) {
            return ORES;
        }
        if (isFood(m)) {
            return FOOD;
        }
        if (endsWithAny(m, "_SEEDS", "_SAPLING") || isAny(m, "WHEAT", "BONE_MEAL", "SUGAR_CANE", "BAMBOO", "CACTUS", "KELP", "WHEAT_SEEDS")) {
            return FARMING;
        }
        if (m.contains("POTION") || m.contains("ENCHANTED") || isAny(m, "BOOK", "EXPERIENCE_BOTTLE", "ENDER_PEARL", "ENDER_EYE", "BLAZE_ROD", "GHAST_TEAR")) {
            return MAGIC;
        }
        if (endsWithAny(m, "_PLANKS", "_LOG", "_WOOD", "_BRICKS", "_SLAB", "_STAIRS", "_WALL", "_FENCE", "_WOOL", "_CONCRETE",
                "_TERRACOTTA", "_GLASS", "_BLOCK", "_DOOR", "_TRAPDOOR")
                || isAny(m, "STONE", "COBBLESTONE", "DIRT", "GRAVEL", "SAND", "GLASS", "DEEPSLATE", "COBBLED_DEEPSLATE", "ANDESITE",
                "DIORITE", "GRANITE", "OBSIDIAN", "NETHERRACK", "CLAY", "BRICK", "GRASS_BLOCK")) {
            return BUILDING;
        }
        return MISC;
    }

    private static boolean isRedstone(String m) {
        return endsWithAny(m, "_BUTTON", "_PRESSURE_PLATE", "_RAIL")
                || isAny(m, "REDSTONE", "REDSTONE_BLOCK", "REDSTONE_TORCH", "REPEATER", "COMPARATOR", "PISTON", "STICKY_PISTON",
                "OBSERVER", "HOPPER", "DISPENSER", "DROPPER", "LEVER", "TNT", "DAYLIGHT_DETECTOR", "TARGET", "REDSTONE_LAMP",
                "NOTE_BLOCK", "TRIPWIRE_HOOK", "SCULK_SENSOR", "SLIME_BLOCK", "HONEY_BLOCK", "RAIL");
    }

    private static boolean isFood(String m) {
        return m.startsWith("COOKED_") || endsWithAny(m, "_STEW", "_PIE", "_SOUP")
                || isAny(m, "BREAD", "APPLE", "GOLDEN_APPLE", "BEEF", "PORKCHOP", "CHICKEN", "MUTTON", "RABBIT", "COD", "SALMON",
                "CARROT", "POTATO", "BAKED_POTATO", "BEETROOT", "MELON_SLICE", "COOKIE", "CAKE", "PUMPKIN_PIE", "DRIED_KELP",
                "SWEET_BERRIES", "GLOW_BERRIES", "HONEY_BOTTLE", "ROTTEN_FLESH", "SPIDER_EYE", "GOLDEN_CARROT");
    }

    private static boolean endsWithAny(String m, String... suffixes) {
        for (String s : suffixes) {
            if (m.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAny(String m, String... names) {
        for (String s : names) {
            if (m.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
