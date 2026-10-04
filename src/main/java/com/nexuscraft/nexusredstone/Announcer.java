package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The player-facing half of the "dramatic messages & villager commentary" scope bucket: a line
 * of flavour text for a notable moment, and -- sometimes -- a nearby working villager remarking
 * on the machine too. Real vanilla villagers have no opinion about redstone; this is entirely
 * invented flavour, like every other cosmetic flourish in this family.
 *
 * <p>Circuits can fire thousands of times an hour, so ambient topics are rate-limited per owner
 * per topic and the villager chance is small; one-off moments (a level-up, a first build, a
 * safety trip) always speak. The cooldown arithmetic is a plain static method so it is unit-tested
 * even though entity lookup is not exercisable in this sandbox.
 */
final class Announcer {

    private final RedstoneConfig cfg;
    private final DialogueBank bank;
    private final Random random = new Random();
    private final Map<String, Long> lastSaid = new HashMap<>();
    private final Map<UUID, Long> villagerLastSpoke = new HashMap<>();

    Announcer(RedstoneConfig cfg, DialogueBank bank) {
        this.cfg = cfg;
        this.bank = bank;
    }

    /** True if at least {@code cooldownMillis} has passed since {@code last}; a never-spoken
     *  marker ({@code Long.MIN_VALUE}) always passes. */
    static boolean offCooldown(long last, long now, long cooldownMillis) {
        if (last == Long.MIN_VALUE) {
            return true;
        }
        return now - last >= cooldownMillis;
    }

    /** Speaks directly to a player (no rate limit: used for one-off moments). */
    void say(Player player, Topic topic, String suffix) {
        if (!cfg.messagesEnabled || player == null) {
            return;
        }
        String line = bank.line(topic, random);
        if (line == null) {
            return;
        }
        player.sendMessage(topic.prefix + line + (suffix == null || suffix.isEmpty() ? "" : " §7" + suffix));
    }

    /** Speaks to a component's owner if they are online; ambient topics are rate-limited, and a
     *  nearby villager may chime in. */
    void sayAt(UUID owner, Topic topic, Block near) {
        if (!cfg.messagesEnabled || owner == null) {
            return;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (topic.ambient) {
            String key = owner + "|" + topic.key;
            Long last = lastSaid.get(key);
            if (!offCooldown(last == null ? Long.MIN_VALUE : last, now, cfg.messageCooldownSeconds * 1000L)) {
                return;
            }
            lastSaid.put(key, now);
        }
        say(player, topic, null);
        maybeVillager(player, near, now);
    }

    /** A plain message to the owner, if online. */
    void tell(UUID owner, String message) {
        if (owner == null) {
            return;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    private void maybeVillager(Player player, Block near, long now) {
        if (!cfg.commentaryEnabled || near == null || random.nextDouble() > 0.4) {
            return;
        }
        double r = cfg.commentaryRadius;
        for (Entity e : near.getWorld().getNearbyEntities(near.getLocation(), r, r, r)) {
            if (!(e instanceof Villager)) {
                continue;
            }
            Villager villager = (Villager) e;
            String profession = villager.getProfession() == null ? null : villager.getProfession().getKey().getKey();
            if (profession == null || !DialogueBank.commentingProfessions().contains(profession)) {
                continue;
            }
            Long last = villagerLastSpoke.get(villager.getUniqueId());
            if (!offCooldown(last == null ? Long.MIN_VALUE : last, now, cfg.commentaryCooldownSeconds * 1000L)) {
                continue;
            }
            String line = bank.villagerLine(profession, random);
            if (line == null) {
                return;
            }
            villagerLastSpoke.put(villager.getUniqueId(), now);
            player.sendMessage("§7[Villager] §f" + line);
            return;
        }
    }
}
