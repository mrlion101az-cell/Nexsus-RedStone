package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Registers {@link RecipeBook}'s recipes with the server, and removes them again on reload or
 *  disable so a reload never leaves stale duplicates behind. */
final class RecipeRegistrar {

    private final Plugin plugin;
    private final RedstoneConfig cfg;
    private final ComponentItems items;
    private final Logger log;
    private final List<NamespacedKey> registered = new ArrayList<>();

    RecipeRegistrar(Plugin plugin, RedstoneConfig cfg, ComponentItems items, Logger log) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.items = items;
        this.log = log;
    }

    int register() {
        unregister();
        if (!cfg.recipesEnabled) {
            return 0;
        }
        int ok = 0;
        for (RecipeBook.Spec s : RecipeBook.all()) {
            try {
                ItemStack result = resultOf(s);
                if (result == null) {
                    continue;
                }
                NamespacedKey key = new NamespacedKey(plugin, "craft_" + s.id());
                ShapedRecipe recipe = new ShapedRecipe(key, result);
                recipe.shape(s.shape());
                for (Map.Entry<Character, String> e : s.ingredients().entrySet()) {
                    recipe.setIngredient(e.getKey(), choice(e.getValue()));
                }
                if (Bukkit.addRecipe(recipe)) {
                    registered.add(key);
                    ok++;
                }
            } catch (RuntimeException ex) {
                log.warning("nexusredstone: could not register recipe " + s.id() + ": " + ex.getMessage());
            }
        }
        return ok;
    }

    private ItemStack resultOf(RecipeBook.Spec s) {
        if (s.tool()) {
            Tool tool = Tool.byId(s.id());
            return tool == null ? null : items.tool(tool);
        }
        ComponentType type = ComponentType.byId(s.id());
        return type == null ? null : items.create(type, s.yield());
    }

    private RecipeChoice choice(String value) {
        if (value.startsWith(RecipeBook.COMPONENT)) {
            ComponentType type = ComponentType.byId(value.substring(RecipeBook.COMPONENT.length()));
            return new RecipeChoice.ExactChoice(items.create(type, 1));
        }
        return new RecipeChoice.MaterialChoice(Material.valueOf(value));
    }

    void unregister() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }

    void discoverFor(Player p) {
        if (!registered.isEmpty()) {
            p.discoverRecipes(registered);
        }
    }

    int count() {
        return registered.size();
    }
}
