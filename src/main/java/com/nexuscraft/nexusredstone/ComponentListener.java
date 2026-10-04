package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Placement, removal and protection of components. The rule that runs through all of it: a
 * component is a normal vanilla block carrying hidden data, so the plugin's job is to notice when
 * one is placed, and to make sure nothing quietly breaks the pairing afterwards -- a player
 * mining the output cell, a piston shoving a gate, an explosion, a hopper draining the analog
 * cell's nuggets. Breaking a component's body is always allowed (it drops its own item, never a
 * plain block); breaking, moving, opening or draining one of its extra blocks never is.
 */
final class ComponentListener implements Listener {

    private final RedstoneConfig cfg;
    private final ComponentRegistry registry;
    private final ComponentService service;
    private final ComponentItems items;
    private final EngineerStore engineers;
    private final Announcer announcer;
    private final LagGuard lag;
    private final MegaPistonService pistons;
    private final MoverService movers;
    private final RecipeRegistrar recipes;
    private final ManualService manual;
    private final LongSupplier clock;

    ComponentListener(RedstoneConfig cfg, ComponentRegistry registry, ComponentService service, ComponentItems items,
                      EngineerStore engineers, Announcer announcer, LagGuard lag, MegaPistonService pistons,
                      MoverService movers, RecipeRegistrar recipes, ManualService manual, LongSupplier clock) {
        this.cfg = cfg;
        this.registry = registry;
        this.service = service;
        this.items = items;
        this.engineers = engineers;
        this.announcer = announcer;
        this.lag = lag;
        this.pistons = pistons;
        this.movers = movers;
        this.recipes = recipes;
        this.manual = manual;
        this.clock = clock;
    }

    private Component resolve(Block b) {
        return b == null ? null : registry.resolve(Geo.key(b));
    }

    // ---------------------------------------------------------------- placing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ComponentType type = items.typeOf(event.getItemInHand());
        if (type == null) {
            return;
        }
        Player p = event.getPlayer();
        Dir facing = Dir.fromYaw(p.getLocation().getYaw());
        ComponentService.Placement placement = service.place(p, type, event.getBlockPlaced(), facing);
        if (!placement.ok()) {
            event.setCancelled(true);
            p.sendMessage(placement.message());
            return;
        }
        Component c = placement.component();
        p.sendMessage("§a" + type.display + " placed. §7Wrench it to set it up: " + (c.type.specs().isEmpty() ? "it has no settings." : "settings: " + ComponentService.settingNames(type) + "."));
    }

    // ---------------------------------------------------------------- breaking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (BlueprintService.isProbing()) {
            return;
        }
        Block block = event.getBlock();
        Component c = resolve(block);
        if (c == null) {
            return;
        }
        Player p = event.getPlayer();
        if (!Geo.key(block).equals(c.key())) {
            event.setCancelled(true);
            p.sendMessage("§cThat block is part of a " + c.type.display + ". Break the component itself to remove it.");
            return;
        }
        if (c.type == ComponentType.BLOCK_MOVER) {
            movers.settle(c);
        }
        event.setDropItems(false);
        service.remove(c, p != null && ComponentService.survival(p), false);
    }

    // ---------------------------------------------------------------- explosions and monsters

    private void shield(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            if (resolve(it.next()) != null) {
                it.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        shield(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        shield(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (resolve(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- vanilla pistons

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistons.isFiring() || movers.isFiring()) {
            return;
        }
        for (Block b : event.getBlocks()) {
            if (resolve(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistons.isFiring() || movers.isFiring()) {
            return;
        }
        for (Block b : event.getBlocks()) {
            if (resolve(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- cells must not be opened or drained

    private boolean isCellInventory(Inventory inv) {
        if (inv == null || registry.size() == 0) {
            return false;
        }
        org.bukkit.Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) {
            return false;
        }
        return registry.isCell(Component.keyOf(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (isCellInventory(event.getSource()) || isCellInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (isCellInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (registry.isCell(Geo.key(event.getClickedBlock()))) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- vanilla redstone load

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVanillaRedstone(BlockRedstoneEvent event) {
        if (!cfg.lagEnabled) {
            return;
        }
        Block b = event.getBlock();
        lag.recordVanilla(LagGuard.chunkKey(b.getWorld().getName(), b.getX(), b.getZ()), clock.getAsLong());
    }

    // ---------------------------------------------------------------- players

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        engineers.levelOf(p);
        recipes.discoverFor(p);
        manual.onJoin(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        engineers.levelOf(event.getPlayer());
    }
}
