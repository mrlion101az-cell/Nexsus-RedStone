package com.nexuscraft.nexusredstone;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The three hand tools and blueprint items. The wrench inspects and adjusts components, the
 * multimeter reads signal strength and attaches oscilloscope probes, the wand marks blueprint
 * corners, and a blueprint item previews and builds. All of them cancel the click they use, so
 * wrenching a hopper never also opens it.
 */
final class ToolListener implements Listener {

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final ComponentService service;
    private final ComponentItems items;
    private final ScopeService scopes;
    private final BlueprintService blueprints;
    private final Announcer announcer;
    private final LongSupplier clock;
    private final Map<UUID, String> selection = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    ToolListener(RedstoneConfig cfg, ComponentRegistry registry, ComponentService service, ComponentItems items,
                 ScopeService scopes, BlueprintService blueprints, Announcer announcer, LongSupplier clock) {
        this.cfg = cfg;
        this.registry = registry;
        this.service = service;
        this.items = items;
        this.scopes = scopes;
        this.blueprints = blueprints;
        this.announcer = announcer;
        this.clock = clock;
    }

    /** The component a player has selected with the wrench (if it still exists). */
    Component selected(Player p) {
        String key = selection.get(p.getUniqueId());
        return key == null ? null : registry.get(key);
    }

    void select(Player p, Component c) {
        selection.put(p.getUniqueId(), c.key());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        selection.remove(id);
        lastUse.remove(id);
        scopes.forget(id);
        blueprints.forget(id);
    }

    private boolean coolingDown(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(p.getUniqueId());
        if (last != null && now - last < cfg.toolCooldownTicks * 50L) {
            return true;
        }
        lastUse.put(p.getUniqueId(), now);
        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        Player p = event.getPlayer();
        Action action = event.getAction();
        String encoded = items.blueprintOf(item);
        if (encoded != null) {
            if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                event.setCancelled(true);
                if (!coolingDown(p)) {
                    blueprints.use(p, event.getClickedBlock(), event.getBlockFace(), item);
                }
            }
            return;
        }
        Tool tool = items.toolOf(item);
        if (tool == null) {
            return;
        }
        switch (tool) {
            case WRENCH:
                if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) {
                    event.setCancelled(true);
                    if (!coolingDown(p)) {
                        wrench(p, event.getClickedBlock(), action == Action.LEFT_CLICK_BLOCK);
                    }
                }
                break;
            case MULTIMETER:
                if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                    event.setCancelled(true);
                    if (!coolingDown(p)) {
                        Block b = event.getClickedBlock();
                        p.sendMessage("§7" + b.getType().name().toLowerCase().replace('_', ' ') + ": signal strength §f" + scopes.signalAt(b));
                        p.sendMessage(scopes.toggle(p, b));
                    }
                } else if (action == Action.RIGHT_CLICK_AIR && p.isSneaking()) {
                    if (!coolingDown(p)) {
                        for (String line : scopes.render(p, clock.getAsLong())) {
                            p.sendMessage(line);
                        }
                    }
                }
                break;
            case BLUEPRINT_WAND:
                if (event.getClickedBlock() != null && (action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK)) {
                    event.setCancelled(true);
                    if (!coolingDown(p)) {
                        blueprints.setCorner(p, action == Action.LEFT_CLICK_BLOCK ? 0 : 1, event.getClickedBlock());
                    }
                }
                break;
            default:
                break;
        }
    }

    private void wrench(Player p, Block clicked, boolean reverse) {
        Component c = clicked == null ? null : registry.resolve(Geo.key(clicked));
        if (c == null) {
            p.sendMessage("§7That is not a NexusRedstone component.");
            return;
        }
        select(p, c);
        if (!p.isSneaking()) {
            for (String line : service.describe(c, clock.getAsLong())) {
                p.sendMessage(line);
            }
            return;
        }
        if (!service.canEdit(p, c)) {
            p.sendMessage("§cThat component belongs to someone else.");
            return;
        }
        List<ParamSpec> specs = c.type.specs();
        if (specs.isEmpty()) {
            p.sendMessage("§7The " + c.type.display + " has no settings to cycle.");
            return;
        }
        ParamSpec spec = specs.get(0);
        p.sendMessage(service.applySetting(p, c, spec.name(), spec.cycle(c.param(spec.name()))));
    }
}
