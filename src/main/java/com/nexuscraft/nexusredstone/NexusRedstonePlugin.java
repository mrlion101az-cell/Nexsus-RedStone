package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * NexusRedstone: the first real overhaul of redstone and hoppers, built as new components that
 * sit on top of vanilla redstone rather than replacing it -- so every existing contraption keeps
 * working exactly as before. Wiring only lives here; each system is its own class.
 */
public final class NexusRedstonePlugin extends JavaPlugin {

    private RedstoneConfig cfg;
    private ComponentRegistry registry;
    private ComponentStore store;
    private MoverService movers;
    private RecipeRegistrar recipes;
    private SimulationTask sim;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = new RedstoneConfig(this);
        cfg.load(getConfig());
        if (!cfg.enabled) {
            getLogger().info("nexusredstone: disabled in config.yml");
            return;
        }

        Keys keys = new Keys(this);
        registry = new ComponentRegistry();
        OutputCells cells = new OutputCells(registry);
        EngineerStore engineers = new EngineerStore(keys, cfg);
        store = new ComponentStore(this, registry, engineers);
        int loaded = store.load(getLogger());

        DialogueBank bank = new DialogueBank();
        bank.load(getConfig());
        Announcer announcer = new Announcer(cfg, bank);

        BukkitCircuitHost host = new BukkitCircuitHost(cfg, cells, engineers, announcer, getLogger());
        LagGuard lag = new LagGuard(cfg);
        WirelessNetwork wireless = new WirelessNetwork(cfg);
        CircuitEngine engine = new CircuitEngine(cfg, registry, wireless, lag);

        ComponentItems items = new ComponentItems(keys);
        ComponentService service = new ComponentService(cfg, registry, cells, engine, engineers, announcer, host);
        service.setItems(items);
        host.setOrphanHandler(c -> service.remove(c, false, false));

        MegaPistonService pistons = new MegaPistonService(cfg, registry, engineers, announcer, host);
        movers = new MoverService(this, cfg, registry, engineers, announcer, host, getLogger());
        int recovered = movers.recover();
        if (recovered > 0) {
            getLogger().warning("nexusredstone: finished " + recovered + " block-mover trip(s) interrupted by the last shutdown");
        }

        sim = new SimulationTask(cfg, engine, host, pistons, movers, lag, store, getLogger());
        ScopeService scopes = new ScopeService(cfg, registry);
        VisualizerService visualizer = new VisualizerService(cfg, registry, wireless, engineers, sim::tick);
        BlueprintService blueprints = new BlueprintService(cfg, registry, service, items, engineers, announcer);
        recipes = new RecipeRegistrar(this, cfg, items, getLogger());
        int recipeCount = recipes.register();

        HopperService hoppers = new HopperService(this, cfg, registry, host);
        DispenserService dispensers = new DispenserService(this, cfg, registry, host);
        SorterService sorter = new SorterService(cfg, registry, announcer, getLogger());
        TransportService transport = new TransportService(cfg, registry, engineers, announcer, host);

        ToolListener tools = new ToolListener(cfg, registry, service, items, scopes, blueprints, announcer, sim::tick);
        ManualService manual = new ManualService(this, cfg, keys);
        ComponentListener components = new ComponentListener(cfg, registry, service, items, engineers, announcer, lag,
                pistons, movers, recipes, manual, sim::tick);
        Bukkit.getPluginManager().registerEvents(components, this);
        Bukkit.getPluginManager().registerEvents(tools, this);
        Bukkit.getPluginManager().registerEvents(hoppers, this);
        Bukkit.getPluginManager().registerEvents(dispensers, this);
        Bukkit.getPluginManager().registerEvents(sorter, this);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            sim.run();
            scopes.sample(sim.tick());
        }, 20L, cfg.stepIntervalTicks);
        Bukkit.getScheduler().runTaskTimer(this, movers::animate, 20L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> transport.run(sim.tick()), 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, hoppers::vacuum, 40L, cfg.hopperVacuumIntervalTicks);
        Bukkit.getScheduler().runTaskTimer(this, visualizer::run, 20L, 5L);

        RedstoneCommand.Context ctx = new RedstoneCommand.Context();
        ctx.cfg = cfg;
        ctx.registry = registry;
        ctx.service = service;
        ctx.items = items;
        ctx.engineers = engineers;
        ctx.scopes = scopes;
        ctx.visualizer = visualizer;
        ctx.blueprints = blueprints;
        ctx.tools = tools;
        ctx.sorter = sorter;
        ctx.movers = movers;
        ctx.manual = manual;
        ctx.transport = transport;
        ctx.sim = sim;
        ctx.lag = lag;
        ctx.wireless = wireless;
        ctx.reload = () -> {
            reloadConfig();
            cfg.load(getConfig());
            bank.load(getConfig());
            recipes.register();
        };
        RedstoneCommand command = new RedstoneCommand(ctx);
        PluginCommand pc = getCommand("redstone");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        if (cfg.manualGiveOnJoin) {
            // Players already online (a reload, or a plugin added to a running server) get theirs too.
            Bukkit.getScheduler().runTaskLater(this, manual::giveToMissing, 100L);
        }

        getLogger().info("nexusredstone: " + loaded + " components loaded, " + recipeCount + " recipes registered");
    }

    @Override
    public void onDisable() {
        if (movers != null) {
            movers.finishAll();
        }
        if (store != null) {
            store.save(getLogger(), true);
        }
        if (recipes != null) {
            recipes.unregister();
        }
    }
}
