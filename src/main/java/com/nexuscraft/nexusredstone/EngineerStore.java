package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A player's Engineer XP, on their own persistent data (the family's usual per-player skill
 * convention, compare NexusSmelting's {@code SmeltingSkillStore}), plus two things a circuit
 * plugin uniquely needs: the set of component types they have placed before (so the big XP award
 * is a one-time "first build" reward and cannot be farmed by placing and breaking), and a cache
 * of each owner's last-known level, because a machine keeps running -- with its owner's limits --
 * while that owner is offline and their data cannot be read.
 */
final class EngineerStore {

    record XpResult(int oldLevel, int newLevel) {
        boolean leveledUp() {
            return newLevel > oldLevel;
        }
    }

    private final Keys keys;
    private final RedstoneConfig cfg;
    private final Map<UUID, Integer> levelCache = new HashMap<>();

    EngineerStore(Keys keys, RedstoneConfig cfg) {
        this.keys = keys;
        this.cfg = cfg;
    }

    long xpOf(Player player) {
        Long v = player.getPersistentDataContainer().get(keys.playerXp, PersistentDataType.LONG);
        return v == null ? 0L : v;
    }

    int levelOf(Player player) {
        int level = EngineerSkill.levelFor(xpOf(player), cfg);
        levelCache.put(player.getUniqueId(), level);
        return level;
    }

    XpResult addXp(Player player, long amount) {
        long before = xpOf(player);
        int oldLevel = EngineerSkill.levelFor(before, cfg);
        if (amount <= 0) {
            return new XpResult(oldLevel, oldLevel);
        }
        long after = before + amount;
        player.getPersistentDataContainer().set(keys.playerXp, PersistentDataType.LONG, after);
        int newLevel = EngineerSkill.levelFor(after, cfg);
        levelCache.put(player.getUniqueId(), newLevel);
        return new XpResult(oldLevel, newLevel);
    }

    /** Level of a component's owner: live if online, otherwise the last level seen, else 1. */
    int levelOfOwner(UUID owner) {
        if (owner == null) {
            return 1;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            return levelOf(online);
        }
        return levelCache.getOrDefault(owner, 1);
    }

    Map<UUID, Integer> cache() {
        return levelCache;
    }

    void remember(UUID owner, int level) {
        levelCache.put(owner, Math.max(1, level));
    }

    private Set<String> seen(Player player) {
        String raw = player.getPersistentDataContainer().get(keys.playerSeen, PersistentDataType.STRING);
        Set<String> out = new HashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(",")) {
                out.add(s);
            }
        }
        return out;
    }

    boolean hasPlacedBefore(Player player, ComponentType type) {
        return seen(player).contains(type.id);
    }

    boolean hasPlacedAnything(Player player) {
        return !seen(player).isEmpty();
    }

    void markPlaced(Player player, ComponentType type) {
        Set<String> s = seen(player);
        if (s.add(type.id)) {
            player.getPersistentDataContainer().set(keys.playerSeen, PersistentDataType.STRING, String.join(",", s));
        }
    }
}
