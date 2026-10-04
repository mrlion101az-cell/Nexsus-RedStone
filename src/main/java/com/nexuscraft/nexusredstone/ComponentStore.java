package com.nexuscraft.nexusredstone;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persists every placed component, with the state a machine must not lose across a restart (a
 * latch's memory, a counter's tally, whether a mega piston is extended, a block mover's offset),
 * in its own YAML file, plus each owner's last-known Engineer level. Same "runtime state gets its
 * own file, loaded at startup, saved on a timer and on disable" convention as NexusAngler's
 * {@code PondStockStore}; additionally the file is written to a temporary file and moved into
 * place, so a crash mid-save can never leave a half-written components file.
 */
final class ComponentStore {

    private static final String FILE = "components.yml";

    private final Plugin plugin;
    private final ComponentRegistry registry;
    private final EngineerStore engineers;

    ComponentStore(Plugin plugin, ComponentRegistry registry, EngineerStore engineers) {
        this.plugin = plugin;
        this.registry = registry;
        this.engineers = engineers;
    }

    int load(Logger log) {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            return 0;
        }
        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection levels = data.getConfigurationSection("levels");
        if (levels != null) {
            for (String k : levels.getKeys(false)) {
                try {
                    engineers.remember(UUID.fromString(k), levels.getInt(k, 1));
                } catch (IllegalArgumentException e) {
                    log.warning("nexusredstone: skipped a malformed owner id in " + FILE + ": " + k);
                }
            }
        }
        ConfigurationSection comps = data.getConfigurationSection("components");
        if (comps == null) {
            return 0;
        }
        int loaded = 0;
        for (String id : comps.getKeys(false)) {
            ConfigurationSection s = comps.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            ComponentType type = ComponentType.byId(s.getString("type"));
            String world = s.getString("world");
            if (type == null || world == null) {
                log.warning("nexusredstone: skipped an unreadable component entry '" + id + "' in " + FILE);
                continue;
            }
            UUID owner = null;
            String rawOwner = s.getString("owner");
            if (rawOwner != null && !rawOwner.isEmpty()) {
                try {
                    owner = UUID.fromString(rawOwner);
                } catch (IllegalArgumentException e) {
                    log.warning("nexusredstone: component '" + id + "' has a malformed owner id; loading it ownerless");
                }
            }
            Component c = new Component(world, s.getInt("x"), s.getInt("y"), s.getInt("z"), type, owner,
                    Dir.parse(s.getString("facing"), Dir.NORTH));
            ConfigurationSection params = s.getConfigurationSection("params");
            if (params != null) {
                for (String name : params.getKeys(false)) {
                    ParamSpec spec = type.spec(name);
                    if (spec != null) {
                        c.params.put(name, spec.clamp(params.getInt(name, spec.def())));
                    }
                }
            }
            c.normalizeClock();
            String text = s.getString("text");
            c.text = text == null ? "" : text;
            for (String f : s.getStringList("filter")) {
                if (c.filter.size() < Math.max(1, type.filterSlots())) {
                    c.filter.add(ItemFilter.normalize(f));
                }
            }
            c.state = s.getBoolean("state", false);
            c.counter = s.getInt("counter", 0);
            c.memory = s.getInt("memory", 0);
            c.output = Math.max(0, Math.min(15, s.getInt("output", 0)));
            c.extended = s.getBoolean("extended", false);
            if (registry.add(c)) {
                registry.claimDerived(c);
                loaded++;
            }
        }
        registry.clearDirty();
        return loaded;
    }

    void save(Logger log, boolean force) {
        if (!force && !registry.isDirty()) {
            return;
        }
        FileConfiguration data = new YamlConfiguration();
        for (java.util.Map.Entry<UUID, Integer> e : engineers.cache().entrySet()) {
            data.set("levels." + e.getKey(), e.getValue());
        }
        int n = 0;
        for (Component c : registry.all()) {
            String base = "components.c" + (n++);
            data.set(base + ".world", c.world);
            data.set(base + ".x", c.x);
            data.set(base + ".y", c.y);
            data.set(base + ".z", c.z);
            data.set(base + ".type", c.type.id);
            data.set(base + ".owner", c.owner == null ? "" : c.owner.toString());
            data.set(base + ".facing", c.facing.name());
            for (java.util.Map.Entry<String, Integer> p : c.params.entrySet()) {
                data.set(base + ".params." + p.getKey(), p.getValue());
            }
            if (!c.text.isEmpty()) {
                data.set(base + ".text", c.text);
            }
            if (!c.filter.isEmpty()) {
                data.set(base + ".filter", new java.util.ArrayList<>(c.filter));
            }
            data.set(base + ".state", c.state);
            data.set(base + ".counter", c.counter);
            data.set(base + ".memory", c.memory);
            data.set(base + ".output", c.output);
            data.set(base + ".extended", c.extended);
        }
        File dir = plugin.getDataFolder();
        File target = new File(dir, FILE);
        File tmp = new File(dir, FILE + ".tmp");
        try {
            dir.mkdirs();
            ((YamlConfiguration) data).save(tmp);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            registry.clearDirty();
        } catch (IOException e) {
            log.warning("nexusredstone: failed to save " + FILE + ": " + e.getMessage());
        }
    }
}
