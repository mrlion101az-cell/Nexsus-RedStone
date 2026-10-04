package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Hands the Redstone Engineer's Manual to every player. The book itself is plain vanilla (a
 * written book any Java or Bedrock player can open with a right-click), tagged in item data so the
 * plugin can tell its own manual from any other book.
 *
 * <p>"Everyone" is made true by remembering, on each player, the edition of the manual they last
 * received: a player who has never had one gets it the next time they are online (new players and
 * old ones alike), and a revised edition reaches everyone again once. Nothing is given twice, a
 * lost copy is replaced on request, and a full inventory drops the book at the player's feet
 * rather than losing it.
 */
final class ManualService {

    /** Ticks to wait after a join before handing over the book, so it lands after the join flood. */
    private static final long JOIN_DELAY_TICKS = 60L;

    private final Plugin plugin;
    private final RedstoneConfig cfg;
    private final Keys keys;

    ManualService(Plugin plugin, RedstoneConfig cfg, Keys keys) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.keys = keys;
    }

    /** A fresh copy of the current edition. */
    ItemStack create() {
        List<String> pages = EngineerManual.pages(cfg);
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK, 1);
        if (book.getItemMeta() instanceof BookMeta meta) {
            meta.setTitle(cfg.manualTitle);
            meta.setAuthor(cfg.manualAuthor);
            meta.setPages(pages);
            meta.getPersistentDataContainer().set(keys.itemManual, PersistentDataType.INTEGER, EngineerManual.EDITION);
            book.setItemMeta(meta);
        }
        return book;
    }

    boolean isManual(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().get(keys.itemManual, PersistentDataType.INTEGER) != null;
    }

    /** True if the player is carrying any copy of the manual. */
    boolean holdsManual(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (isManual(item)) {
                return true;
            }
        }
        return false;
    }

    /** True if the player has already been given the current edition (whether or not they kept it). */
    boolean hasReceivedCurrent(Player p) {
        Integer got = p.getPersistentDataContainer().get(keys.playerManual, PersistentDataType.INTEGER);
        return got != null && got >= EngineerManual.EDITION;
    }

    /** Gives the book and records the edition. Never fails silently: a full inventory drops it. */
    void give(Player p) {
        ItemStack book = create();
        boolean dropped = false;
        for (ItemStack rest : p.getInventory().addItem(book).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
            dropped = true;
        }
        p.getPersistentDataContainer().set(keys.playerManual, PersistentDataType.INTEGER, EngineerManual.EDITION);
        p.sendMessage("§6§lThe Redstone Engineer's Manual §r§7is yours. Right-click it to read what is new.");
        if (dropped) {
            p.sendMessage("§7Your inventory was full, so it was dropped at your feet.");
        }
    }

    /** A joining player who has not yet received this edition gets it after a short delay. */
    void onJoin(Player p) {
        if (!cfg.manualGiveOnJoin || hasReceivedCurrent(p)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !hasReceivedCurrent(p)) {
                give(p);
            }
        }, JOIN_DELAY_TICKS);
    }

    /** Everyone online who has not received this edition (used at startup and on reload). */
    int giveToMissing() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!hasReceivedCurrent(p)) {
                give(p);
                n++;
            }
        }
        return n;
    }

    /** Staff tool: a copy for every online player who is not holding one right now. */
    int giveToAllOnline() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!holdsManual(p)) {
                give(p);
                n++;
            }
        }
        return n;
    }
}
