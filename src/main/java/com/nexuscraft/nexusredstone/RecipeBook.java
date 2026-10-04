package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every crafting recipe, as plain data: a shape, what each letter stands for, and how many items
 * it yields. Ingredient values are vanilla material names, or {@code component:<id>} to demand
 * another component's item (which is how the four hopper tiers chain upward: the gold hopper is
 * crafted from an iron hopper, not from a vanilla one). Kept free of server types so a unit test
 * can prove that every recipe is well formed and that no two recipes are identical (two identical
 * shapes would silently shadow one another on a real server).
 */
final class RecipeBook {

    static final String COMPONENT = "component:";

    record Spec(String id, boolean tool, String[] shape, Map<Character, String> ingredients, int yield) {
        /** Identity of the crafting pattern itself, ignoring what it makes. */
        String patternKey() {
            return String.join("/", shape) + "|" + new TreeMap<>(ingredients);
        }
    }

    private RecipeBook() {
    }

    private static Spec spec(String id, boolean tool, int yield, String[] shape, String... pairs) {
        Map<Character, String> map = new LinkedHashMap<>();
        for (String p : pairs) {
            map.put(p.charAt(0), p.substring(2));
        }
        return new Spec(id, tool, shape, map, yield);
    }

    private static String[] rows(String... r) {
        return r;
    }

    static List<Spec> all() {
        List<Spec> out = new ArrayList<>();
        String q = "Q=QUARTZ_BLOCK";
        String r = "R=REDSTONE";
        String t = "T=REDSTONE_TORCH";
        String p = "P=REPEATER";
        String c = "C=COMPARATOR";

        // Logic gates: two per craft.
        out.add(spec("and", false, 2, rows("TRT", " Q "), q, r, t));
        out.add(spec("or", false, 2, rows("RRR", " Q "), q, r));
        out.add(spec("xor", false, 2, rows("RTR", " Q ", " P "), q, r, t, p));
        out.add(spec("not", false, 2, rows(" T ", " Q "), q, t));
        out.add(spec("nand", false, 2, rows("TRT", " Q ", " T "), q, r, t));
        out.add(spec("nor", false, 2, rows("RRR", " Q ", " T "), q, r, t));
        out.add(spec("xnor", false, 2, rows("RTR", " Q ", "TPT"), q, r, t, p));

        // Memory.
        String l = "L=LAPIS_BLOCK";
        out.add(spec("latch", false, 1, rows("TLT", "RLR"), l, r, t));
        out.add(spec("toggle", false, 1, rows("PLP", " T "), l, p, t));
        out.add(spec("counter", false, 1, rows("CLC", "RTR"), l, c, r, t));

        // Timing.
        String g = "G=GOLD_BLOCK";
        out.add(spec("ondelay", false, 1, rows("PGP", " R "), g, p, r));
        out.add(spec("offdelay", false, 1, rows("PGP", " T "), g, p, t));
        out.add(spec("pulse", false, 1, rows("RGR", " P "), g, r, p));
        out.add(spec("clock", false, 1, rows("KGK", "RPR"), g, r, p, "K=CLOCK"));

        // Analog.
        String x = "X=COPPER_BLOCK";
        out.add(spec("amp", false, 1, rows("CXC", " R "), x, c, r));
        out.add(spec("math", false, 1, rows("CXC", "RPR"), x, c, r, p));
        out.add(spec("threshold", false, 1, rows("CXC", " T "), x, c, t));

        // Wireless.
        String a = "A=AMETHYST_BLOCK";
        String e = "E=ENDER_PEARL";
        out.add(spec("tx", false, 1, rows(" E ", "RAR", " T "), a, e, r, t));
        out.add(spec("rx", false, 1, rows(" E ", "RAR", " C "), a, e, r, c));

        // Sensors.
        String y = "Y=EMERALD_BLOCK";
        out.add(spec("senplayer", false, 1, rows("CYC", " M "), y, c, "M=GOLDEN_APPLE"));
        out.add(spec("senmob", false, 1, rows("CYC", " M "), y, c, "M=ROTTEN_FLESH"));
        out.add(spec("senitem", false, 1, rows("CYC", " M "), y, c, "M=HOPPER"));
        out.add(spec("senlight", false, 1, rows("CYC", " M "), y, c, "M=GLOWSTONE_DUST"));
        out.add(spec("senweather", false, 1, rows("CYC", " M "), y, c, "M=WATER_BUCKET"));
        out.add(spec("sentime", false, 1, rows("CYC", " M "), y, c, "M=CLOCK"));
        out.add(spec("senfill", false, 1, rows("CYC", " M "), y, c, "M=CHEST"));

        // Programmable.
        out.add(spec("plc", false, 1, rows("RCR", "CDC", "RPR"), r, c, p, "D=DIAMOND_BLOCK"));

        // Hopper tiers chain upward.
        out.add(spec("hopperiron", false, 1, rows(" I ", "IHI", " I "), "I=IRON_INGOT", "H=HOPPER"));
        out.add(spec("hoppergold", false, 1, rows(" G ", "GHG", " G "), "G=GOLD_INGOT", "H=" + COMPONENT + "hopperiron"));
        out.add(spec("hopperdiamond", false, 1, rows(" D ", "DHD", " D "), "D=DIAMOND", "H=" + COMPONENT + "hoppergold"));
        out.add(spec("hoppernetherite", false, 1, rows(" N ", "NHN", " N "), "N=NETHERITE_INGOT", "H=" + COMPONENT + "hopperdiamond"));

        // Dispensers.
        out.add(spec("smartdispenser", false, 1, rows(" C ", "RDR", " P "), c, r, p, "D=DISPENSER"));
        out.add(spec("smartdropper", false, 1, rows(" C ", "RDR", " P "), c, r, p, "D=DROPPER"));

        // Transport.
        out.add(spec("pipe", false, 4, rows("BRB"), "B=IRON_BARS", r));
        out.add(spec("portin", false, 1, rows("IRI", " H ", " B "), "I=IRON_INGOT", r, "H=HOPPER", "B=IRON_BARS"));
        out.add(spec("portout", false, 1, rows("IRI", " D ", " B "), "I=IRON_INGOT", r, "D=DROPPER", "B=IRON_BARS"));
        out.add(spec("sorter", false, 1, rows(" C ", "RBR", " H "), c, r, "B=BARREL", "H=HOPPER"));

        // Movement.
        out.add(spec("megapiston", false, 1, rows("BPB", "IDI", "BRB"), "B=IRON_BLOCK", "P=PISTON", "I=IRON_INGOT", "D=DIAMOND", r));
        out.add(spec("mover", false, 1, rows("SLS", "PDP", "SRS"), "S=SLIME_BLOCK", "L=LODESTONE", "P=PISTON", "D=DIAMOND", "R=REDSTONE_BLOCK"));

        // Tools.
        out.add(spec("wrench", true, 1, rows("IRI", " S ", " S "), "I=IRON_INGOT", r, "S=STICK"));
        out.add(spec("multimeter", true, 1, rows("GPG", "IRI"), "G=GOLD_INGOT", "P=GLASS_PANE", "I=IRON_INGOT", r));
        out.add(spec("wand", true, 1, rows("A", "B", "R"), "A=AMETHYST_SHARD", "B=BREEZE_ROD", r));
        return out;
    }
}
