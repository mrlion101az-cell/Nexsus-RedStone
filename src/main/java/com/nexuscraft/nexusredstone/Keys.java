package com.nexuscraft.nexusredstone;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Every persistent-data key this plugin uses, created once. Items carry their identity in PDC
 *  (so renaming an item in an anvil never turns a gate back into a plain block), and each player
 *  carries their Engineer XP and the set of component types they have already placed. */
final class Keys {

    final NamespacedKey itemType;
    final NamespacedKey itemTool;
    final NamespacedKey itemBlueprint;
    final NamespacedKey itemManual;
    final NamespacedKey playerXp;
    final NamespacedKey playerSeen;
    final NamespacedKey playerManual;

    Keys(Plugin plugin) {
        itemType = new NamespacedKey(plugin, "component");
        itemTool = new NamespacedKey(plugin, "tool");
        itemBlueprint = new NamespacedKey(plugin, "blueprint");
        itemManual = new NamespacedKey(plugin, "manual");
        playerXp = new NamespacedKey(plugin, "engineer_xp");
        playerSeen = new NamespacedKey(plugin, "seen_types");
        playerManual = new NamespacedKey(plugin, "manual_edition");
    }
}
