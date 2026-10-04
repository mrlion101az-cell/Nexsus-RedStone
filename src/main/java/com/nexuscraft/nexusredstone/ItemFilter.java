package com.nexuscraft.nexusredstone;

import java.util.List;
import java.util.Locale;

/**
 * Whitelist/blacklist matching on material names, shared by tiered hoppers and transport ports.
 * An entry is an exact material name ({@code IRON_INGOT}) or a simple wildcard with one leading
 * or trailing {@code *} ({@code *_ORE}, {@code RAW_*}, {@code *_LOG}), which stands in for the
 * "tags" a full filter GUI would offer without needing one. An empty filter lets everything
 * through in either mode: an unconfigured hopper behaves like a hopper, not like a wall.
 */
final class ItemFilter {

    static final int WHITELIST = 0;
    static final int BLACKLIST = 1;

    private ItemFilter() {
    }

    static String normalize(String entry) {
        return entry == null ? "" : entry.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    static boolean entryMatches(String entry, String material) {
        String e = normalize(entry);
        String m = normalize(material);
        if (e.isEmpty()) {
            return false;
        }
        if (e.equals("*")) {
            return true;
        }
        if (e.startsWith("*") && e.endsWith("*") && e.length() > 2) {
            return m.contains(e.substring(1, e.length() - 1));
        }
        if (e.startsWith("*")) {
            return m.endsWith(e.substring(1));
        }
        if (e.endsWith("*")) {
            return m.startsWith(e.substring(0, e.length() - 1));
        }
        return e.equals(m);
    }

    static boolean passes(List<String> filter, int mode, String material) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        boolean listed = false;
        for (String entry : filter) {
            if (entryMatches(entry, material)) {
                listed = true;
                break;
            }
        }
        return mode == BLACKLIST ? !listed : listed;
    }

    /** Adds an entry if there is room and it is not already present; returns a status message
     *  key: "ok", "dup", "full", or "empty". */
    static String add(List<String> filter, String entry, int capacity) {
        String e = normalize(entry);
        if (e.isEmpty()) {
            return "empty";
        }
        if (filter.contains(e)) {
            return "dup";
        }
        if (filter.size() >= capacity) {
            return "full";
        }
        filter.add(e);
        return "ok";
    }
}
