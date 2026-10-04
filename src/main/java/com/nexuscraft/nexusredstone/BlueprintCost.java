package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The pure arithmetic of building from a blueprint: what material each captured block costs (a
 * wall torch is paid for with a torch, redstone dust with redstone), which blocks cannot be built
 * at all (water, fire, a piston head -- things with no item form), and the order to place things
 * in so supported blocks (dust, torches, repeaters) always come after what holds them up.
 * Kept free of server types, with the "does this name have an item form" question injected, so
 * the whole table is covered by plain unit tests.
 */
final class BlueprintCost {

    /** Cost key prefix for component items (paid with the component item itself). */
    static final String COMPONENT_PREFIX = "component:";

    record Cost(Map<String, Integer> items, Set<String> unbuildable) {
    }

    private BlueprintCost() {
    }

    /** "minecraft:oak_stairs[facing=east]" or "OAK_STAIRS" becomes "OAK_STAIRS". */
    static String materialOf(String blockData) {
        if (blockData == null) {
            return "";
        }
        String s = blockData.trim();
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            s = s.substring(0, bracket);
        }
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return s.toUpperCase(Locale.ROOT);
    }

    /** The item that pays for a block, or null when the block has no item form and cannot be built. */
    static String itemFor(String block, Predicate<String> hasItemForm) {
        String m = block.toUpperCase(Locale.ROOT);
        switch (m) {
            case "AIR":
            case "CAVE_AIR":
            case "VOID_AIR":
            case "WATER":
            case "LAVA":
            case "FIRE":
            case "SOUL_FIRE":
            case "MOVING_PISTON":
            case "PISTON_HEAD":
            case "NETHER_PORTAL":
            case "END_PORTAL":
            case "END_GATEWAY":
            case "BUBBLE_COLUMN":
                return null;
            case "REDSTONE_WIRE":
                return "REDSTONE";
            case "WALL_TORCH":
                return "TORCH";
            case "REDSTONE_WALL_TORCH":
                return "REDSTONE_TORCH";
            case "SOUL_WALL_TORCH":
                return "SOUL_TORCH";
            case "TRIPWIRE":
                return "STRING";
            case "WHEAT":
                return "WHEAT_SEEDS";
            case "CARROTS":
                return "CARROT";
            case "POTATOES":
                return "POTATO";
            case "BEETROOTS":
            case "BEETROOT":
                return "BEETROOT_SEEDS";
            case "MELON_STEM":
            case "ATTACHED_MELON_STEM":
                return "MELON_SEEDS";
            case "PUMPKIN_STEM":
            case "ATTACHED_PUMPKIN_STEM":
                return "PUMPKIN_SEEDS";
            case "WATER_CAULDRON":
            case "LAVA_CAULDRON":
            case "POWDER_SNOW_CAULDRON":
                return "CAULDRON";
            default:
                break;
        }
        if (m.endsWith("_WALL_SIGN")) {
            m = m.replace("_WALL_SIGN", "_SIGN");
        } else if (m.endsWith("_WALL_HANGING_SIGN")) {
            m = m.replace("_WALL_HANGING_SIGN", "_HANGING_SIGN");
        } else if (m.endsWith("_WALL_BANNER")) {
            m = m.replace("_WALL_BANNER", "_BANNER");
        } else if (m.endsWith("_WALL_HEAD")) {
            m = m.replace("_WALL_HEAD", "_HEAD");
        } else if (m.endsWith("_WALL_SKULL")) {
            m = m.replace("_WALL_SKULL", "_SKULL");
        }
        return hasItemForm.test(m) ? m : null;
    }

    /** Whether a block needs something to hold it up and so must be placed after solid blocks. */
    static boolean attachable(String block) {
        String m = block.toUpperCase(Locale.ROOT);
        return m.endsWith("TORCH") || m.equals("REDSTONE_WIRE") || m.equals("REPEATER") || m.equals("COMPARATOR")
                || m.equals("LEVER") || m.endsWith("_BUTTON") || m.endsWith("_PRESSURE_PLATE") || m.endsWith("RAIL")
                || m.endsWith("_DOOR") || m.endsWith("_SIGN") || m.endsWith("_CARPET") || m.endsWith("_BANNER")
                || m.equals("TRIPWIRE_HOOK") || m.equals("TRIPWIRE") || m.equals("LADDER") || m.equals("VINE")
                || m.equals("SCAFFOLDING") || m.equals("LANTERN") || m.equals("SOUL_LANTERN");
    }

    /** Offsets that carry both a plain block and a component (vanilla-container components keep
     *  their block data in the blueprint so their facing survives). */
    private static Set<String> componentOffsets(Blueprint bp) {
        Set<String> set = new HashSet<>();
        for (Blueprint.Comp c : bp.components) {
            set.add(c.dx() + "," + c.dy() + "," + c.dz());
        }
        return set;
    }

    /** Everything a blueprint costs to build. Component offsets are paid for once, as the
     *  component item, never also as the plain block that carries their facing. */
    static Cost cost(Blueprint bp, Predicate<String> hasItemForm) {
        Map<String, Integer> items = new LinkedHashMap<>();
        Set<String> unbuildable = new LinkedHashSet<>();
        Set<String> compAt = componentOffsets(bp);
        for (Blueprint.Block b : bp.blocks) {
            if (compAt.contains(b.dx() + "," + b.dy() + "," + b.dz())) {
                continue;
            }
            String material = materialOf(b.data());
            String item = itemFor(material, hasItemForm);
            if (item == null) {
                unbuildable.add(material);
            } else {
                items.merge(item, 1, Integer::sum);
            }
        }
        for (Blueprint.Comp c : bp.components) {
            items.merge(COMPONENT_PREFIX + c.type(), 1, Integer::sum);
        }
        return new Cost(items, unbuildable);
    }

    /** Plain blocks in placement order: everything that stands on its own first, bottom to top,
     *  then everything that needs support, bottom to top. Stable for equal heights. */
    static List<Blueprint.Block> placeOrder(Blueprint bp) {
        List<Blueprint.Block> solid = new ArrayList<>();
        List<Blueprint.Block> attached = new ArrayList<>();
        for (Blueprint.Block b : bp.blocks) {
            if (attachable(materialOf(b.data()))) {
                attached.add(b);
            } else {
                solid.add(b);
            }
        }
        Comparator<Blueprint.Block> byHeight = Comparator.comparingInt(Blueprint.Block::dy);
        solid.sort(byHeight);
        attached.sort(byHeight);
        List<Blueprint.Block> out = new ArrayList<>(solid);
        out.addAll(attached);
        return out;
    }

    /** Short "3x STONE, 12x REDSTONE" style summary. */
    static String describe(Map<String, Integer> items) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String name = e.getKey().startsWith(COMPONENT_PREFIX)
                    ? displayOfComponent(e.getKey().substring(COMPONENT_PREFIX.length()))
                    : e.getKey().toLowerCase(Locale.ROOT).replace('_', ' ');
            sb.append(e.getValue()).append("x ").append(name);
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    private static String displayOfComponent(String id) {
        ComponentType t = ComponentType.byId(id);
        return t == null ? id : t.display;
    }
}
