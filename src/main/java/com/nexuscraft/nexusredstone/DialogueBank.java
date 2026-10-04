package com.nexuscraft.nexusredstone;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The line bank behind the dramatic messages and the villagers' remarks. Every line is
 * hand-written (built-in defaults, optionally replaced per topic by config.yml) -- no generation,
 * no external calls -- the same scripted-line convention NexusMinds' and NexusSmelting's own
 * banks use. This class only stores and picks; the pick method takes the random source so tests
 * can be deterministic.
 *
 * <p>Villager lines are keyed by the profession's plain key string ("toolsmith"), read off the
 * villager at runtime, which keeps this class free of any server type.
 */
final class DialogueBank {

    private final Map<Topic, List<String>> lines = new HashMap<>();
    private final Map<String, List<String>> villagerLines = new HashMap<>();
    private List<String> villagerGeneric = new ArrayList<>();

    private static final Map<String, String[]> VILLAGER_DEFAULTS = new HashMap<>();

    static {
        VILLAGER_DEFAULTS.put("toolsmith", new String[] {
                "Clever contraption! I'd trade an iron pick for the secret of that one.",
                "Now THAT is craftsmanship. A tool that thinks!",
                "I've forged a thousand picks, and never once had them click like that."});
        VILLAGER_DEFAULTS.put("mason", new String[] {
                "Stone and wire, working as one. Beautiful.",
                "I lay bricks. You lay brains into the walls. Well done.",
                "Solid work. I'd build a monument around that."});
        VILLAGER_DEFAULTS.put("librarian", new String[] {
                "Fascinating! I must record this design in my ledger.",
                "A logic of stone -- I have read of such machines, but never seen one hum.",
                "Hmm, yes, the signal path is rather elegant."});
        VILLAGER_DEFAULTS.put("farmer", new String[] {
                "If that automates my harvest, I'll gladly bring you bread.",
                "Hrmm. Don't know how it works, but the crops'd love it.",
                "Wires and wheat! Never thought I'd see the day."});
    }

    private static final String[] GENERIC_DEFAULTS = {
            "Hmm, a marvelous machine!",
            "I don't understand it, but I admire it."};

    DialogueBank() {
        reset();
    }

    private void reset() {
        lines.clear();
        villagerLines.clear();
        for (Topic t : Topic.values()) {
            lines.put(t, new ArrayList<>(List.of(t.defaults)));
        }
        for (Map.Entry<String, String[]> e : VILLAGER_DEFAULTS.entrySet()) {
            villagerLines.put(e.getKey(), new ArrayList<>(List.of(e.getValue())));
        }
        villagerGeneric = new ArrayList<>(List.of(GENERIC_DEFAULTS));
    }

    /** Reloads from config; a missing or empty section leaves the built-in defaults in place. */
    void load(ConfigurationSection root) {
        reset();
        if (root == null) {
            return;
        }
        for (Topic t : Topic.values()) {
            List<String> custom = root.getStringList("messages.lines." + t.key);
            if (custom != null && !custom.isEmpty()) {
                lines.put(t, new ArrayList<>(custom));
            }
        }
        for (String profession : new ArrayList<>(VILLAGER_DEFAULTS.keySet())) {
            List<String> custom = root.getStringList("messages.villager-lines." + profession);
            if (custom != null && !custom.isEmpty()) {
                villagerLines.put(profession, new ArrayList<>(custom));
            }
        }
        List<String> generic = root.getStringList("messages.villager-lines.generic");
        if (generic != null && !generic.isEmpty()) {
            villagerGeneric = new ArrayList<>(generic);
        }
    }

    String line(Topic topic, Random random) {
        List<String> pool = lines.get(topic);
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    /** A villager remark for a profession key; falls back to a generic line for professions
     *  without their own pool, and null if there is nothing at all. */
    String villagerLine(String professionKey, Random random) {
        List<String> pool = professionKey == null ? null : villagerLines.get(professionKey);
        if (pool == null || pool.isEmpty()) {
            pool = villagerGeneric;
        }
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    boolean hasVillagerPool(String professionKey) {
        return professionKey != null && villagerLines.containsKey(professionKey);
    }

    static java.util.Set<String> commentingProfessions() {
        return VILLAGER_DEFAULTS.keySet();
    }
}
