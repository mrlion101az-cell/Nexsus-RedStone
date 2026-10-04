package com.nexuscraft.nexusredstone;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates and recognises every item this plugin hands out: component items, the three tools, and
 * saved blueprints. Identity is a string in the item's persistent data, so an item keeps its
 * meaning through anvil renames and survives having its look changed by a resource pack.
 */
final class ComponentItems {

    private final Keys keys;

    ComponentItems(Keys keys) {
        this.keys = keys;
    }

    ItemStack create(ComponentType type, int amount) {
        ItemStack item = new ItemStack(Material.valueOf(type.bodyMaterial()), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b" + type.display);
        List<String> lore = new ArrayList<>();
        lore.add("§7" + type.family.label + " component");
        lore.add("§f" + type.description);
        lore.add("§8Unlocks at Engineer level " + type.minLevel);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(keys.itemType, PersistentDataType.STRING, type.id);
        item.setItemMeta(meta);
        return item;
    }

    /** The component type an item places, or null for any other item. */
    ComponentType typeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(keys.itemType, PersistentDataType.STRING);
        return ComponentType.byId(id);
    }

    ItemStack tool(Tool tool) {
        ItemStack item = new ItemStack(Material.valueOf(tool.material), 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§e" + tool.display);
        List<String> lore = new ArrayList<>();
        lore.add("§7Engineer's tool");
        lore.add("§f" + tool.help);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(keys.itemTool, PersistentDataType.STRING, tool.id);
        item.setItemMeta(meta);
        return item;
    }

    Tool toolOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(keys.itemTool, PersistentDataType.STRING);
        return id == null ? null : Tool.byId(id);
    }

    ItemStack blueprint(String encoded, String name, int blocks, int components, int sx, int sy, int sz) {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§bBlueprint: §f" + name);
        List<String> lore = new ArrayList<>();
        lore.add("§7" + blocks + " blocks, " + components + " components");
        lore.add("§7Size " + sx + " x " + sy + " x " + sz);
        lore.add("§fRight-click a block to preview, again to build.");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(keys.itemBlueprint, PersistentDataType.STRING, encoded);
        item.setItemMeta(meta);
        return item;
    }

    /** The encoded blueprint an item carries, or null. */
    String blueprintOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(keys.itemBlueprint, PersistentDataType.STRING);
    }
}
