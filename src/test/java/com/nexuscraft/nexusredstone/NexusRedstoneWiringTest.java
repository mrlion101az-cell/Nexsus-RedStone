package com.nexuscraft.nexusredstone;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Boots the whole plugin against the sandbox Bukkit stubs and drives the item, command and
 * description code paths. This cannot prove anything about a real server (the stubs have no real
 * world), but it does catch wiring mistakes, null pointers and bad recipe/material names in the
 * Bukkit-facing layer that the pure-logic suite never touches.
 */
public final class NexusRedstoneWiringTest {

    private static int passed;
    private static int failed;

    private static void expect(boolean ok, String message) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + message);
        }
    }

    private static class TestPlayer extends Player {
        final List<String> messages = new ArrayList<>();

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        String joined() {
            return String.join("\n", messages);
        }
    }

    public static void main(String[] args) throws Exception {
        NexusRedstonePlugin plugin = new NexusRedstonePlugin();
        plugin.onEnable();
        expect(true, "plugin enables without throwing");

        // items round trip for every component and tool
        Keys keys = new Keys(plugin);
        ComponentItems items = new ComponentItems(keys);
        for (ComponentType t : ComponentType.values()) {
            ItemStack item = items.create(t, 3);
            expect(items.typeOf(item) == t && item.getAmount() == 3, "item round trip " + t.id);
            expect(item.getType() == Material.valueOf(t.bodyMaterial()), "item material is the body block " + t.id);
        }
        for (Tool t : Tool.values()) {
            ItemStack item = items.tool(t);
            expect(items.toolOf(item) == t && items.typeOf(item) == null, "tool round trip " + t.id);
        }
        ItemStack bp = items.blueprint("abc", "demo", 5, 1, 2, 3, 4);
        expect("abc".equals(items.blueprintOf(bp)) && items.toolOf(bp) == null && items.typeOf(bp) == null, "blueprint item round trip");
        expect(items.typeOf(new ItemStack(Material.HOPPER, 1)) == null, "a plain hopper is not a component");

        // every recipe registers
        RedstoneConfig cfg = new RedstoneConfig(null);
        RecipeRegistrar registrar = new RecipeRegistrar(plugin, cfg, items, plugin.getLogger());
        expect(registrar.register() == RecipeBook.all().size(), "all " + RecipeBook.all().size() + " recipes register");
        registrar.unregister();
        expect(registrar.count() == 0, "recipes unregister");

        // command surface
        ComponentRegistry registry = new ComponentRegistry();
        RedstoneConfig c2 = new RedstoneConfig(null);
        Keys k2 = keys;
        EngineerStore engineers = new EngineerStore(k2, c2);
        OutputCells cells = new OutputCells(registry);
        LagGuard lag = new LagGuard(c2);
        WirelessNetwork wireless = new WirelessNetwork(c2);
        CircuitEngine engine = new CircuitEngine(c2, registry, wireless, lag);
        Announcer announcer = new Announcer(c2, new DialogueBank());
        BukkitCircuitHost host = new BukkitCircuitHost(c2, cells, engineers, announcer, plugin.getLogger());
        ComponentService service = new ComponentService(c2, registry, cells, engine, engineers, announcer, host);
        service.setItems(items);

        RedstoneCommand.Context ctx = new RedstoneCommand.Context();
        ctx.cfg = c2;
        ctx.registry = registry;
        ctx.service = service;
        ctx.items = items;
        ctx.engineers = engineers;
        ctx.scopes = new ScopeService(c2, registry);
        ctx.visualizer = new VisualizerService(c2, registry, wireless, engineers, () -> 0L);
        ctx.blueprints = new BlueprintService(c2, registry, service, items, engineers, announcer);
        SimulationTask sim = new SimulationTask(c2, engine, host, new MegaPistonService(c2, registry, engineers, announcer, host),
                new MoverService(plugin, c2, registry, engineers, announcer, host, plugin.getLogger()), lag,
                new ComponentStore(plugin, registry, engineers), plugin.getLogger());
        ctx.sim = sim;
        ctx.lag = lag;
        ctx.wireless = wireless;
        ctx.sorter = new SorterService(c2, registry, announcer, plugin.getLogger());
        ctx.movers = new MoverService(plugin, c2, registry, engineers, announcer, host, plugin.getLogger());
        ctx.transport = new TransportService(c2, registry, engineers, announcer, host);
        ctx.tools = new ToolListener(c2, registry, service, items, ctx.scopes, ctx.blueprints, announcer, () -> 0L);
        ManualService manual = new ManualService(plugin, c2, keys);
        ctx.manual = manual;
        ctx.reload = () -> { };
        RedstoneCommand cmd = new RedstoneCommand(ctx);

        TestPlayer p = new TestPlayer();
        Command c = new Command();
        for (String sub : new String[] {"help", "skill", "list", "list logic", "mine", "stats", "lag", "scope", "visualize 5", "visualize off", "scope clear", "info", "blueprint save x", "blueprint clear", "bogus", "set", "text a b", "filter list", "sort", "remove", "manual"}) {
            p.messages.clear();
            boolean handled = cmd.onCommand(p, c, "redstone", sub.split(" "));
            expect(handled && !p.messages.isEmpty(), "/redstone " + sub + " answers");
        }
        p.messages.clear();
        cmd.onCommand(p, c, "redstone", new String[] {"give", "and", "4"});
        expect(p.joined().contains("Gave"), "/redstone give works for an admin: " + p.joined());
        p.messages.clear();
        cmd.onCommand(p, c, "redstone", new String[] {"list"});
        expect(p.joined().contains("Logic") && p.joined().contains("Hopper") && p.joined().contains("Mover"), "list shows every family");

        // tab completion never throws and filters by prefix
        expect(cmd.onTabComplete(p, c, "rs", new String[] {""}).containsAll(List.of("skill", "give", "blueprint")), "tab completes subcommands");
        expect(cmd.onTabComplete(p, c, "rs", new String[] {"gi"}).equals(List.of("give")), "tab filters by prefix");
        expect(cmd.onTabComplete(p, c, "rs", new String[] {"give", "hopper"}).containsAll(List.of("hopperiron", "hopperdiamond")), "tab completes component ids");
        expect(cmd.onTabComplete(p, c, "rs", new String[] {"filter", "mode", "w"}).equals(List.of("whitelist")), "tab completes filter modes");


        // the Engineer's Manual: a real written book, tagged, given once, replaced on request
        ItemStack book = manual.create();
        expect(book.getType() == Material.WRITTEN_BOOK && manual.isManual(book), "the manual is a tagged written book");
        expect(book.getItemMeta() instanceof org.bukkit.inventory.meta.BookMeta, "the manual carries book data");
        org.bukkit.inventory.meta.BookMeta bm = (org.bukkit.inventory.meta.BookMeta) book.getItemMeta();
        expect(bm.getTitle().equals(c2.manualTitle) && bm.getAuthor().equals(c2.manualAuthor), "the manual has its title and author");
        expect(bm.getTitle().length() <= 32, "the manual title fits a written book");
        expect(bm.getPages().equals(EngineerManual.pages(c2)) && bm.getPages().size() <= 100, "the manual holds every page");
        expect(!manual.isManual(new ItemStack(Material.WRITTEN_BOOK, 1)), "an ordinary written book is not the manual");
        expect(!manual.isManual(new ItemStack(Material.PAPER, 1)) && !manual.isManual(null), "other items are not the manual");
        expect(!manual.isManual(items.tool(Tool.WRENCH)), "a tool is not the manual");

        TestPlayer reader = new TestPlayer();
        expect(!manual.hasReceivedCurrent(reader) && !manual.holdsManual(reader), "a new player has not received the manual");
        manual.give(reader);
        expect(manual.hasReceivedCurrent(reader), "giving the manual records the edition");
        expect(reader.joined().contains("Manual"), "the player is told they received the manual");
        reader.getInventory().setItem(3, manual.create());
        expect(manual.holdsManual(reader), "a carried manual is recognised");
        reader.messages.clear();
        cmd.onCommand(reader, c, "redstone", new String[] {"manual"});
        expect(reader.joined().contains("already"), "asking again while holding one does not duplicate it: " + reader.joined());
        TestPlayer lost = new TestPlayer();
        lost.messages.clear();
        cmd.onCommand(lost, c, "redstone", new String[] {"manual"});
        expect(lost.joined().contains("yours") && manual.hasReceivedCurrent(lost), "a player who lost theirs can get another: " + lost.joined());
        TestPlayer plain = new TestPlayer() {
            @Override
            public boolean hasPermission(String node) {
                return !node.equals("nexusredstone.admin");
            }
        };
        plain.messages.clear();
        cmd.onCommand(plain, c, "redstone", new String[] {"manual", "all"});
        expect(plain.joined().contains("Only staff") && !manual.hasReceivedCurrent(plain), "only staff can hand the manual to others");
        plain.messages.clear();
        cmd.onCommand(p, c, "redstone", new String[] {"manual", "all"});
        expect(!p.messages.isEmpty(), "staff can give the manual to everyone online");
        expect(cmd.onTabComplete(plain, c, "rs", new String[] {"manual", ""}).isEmpty(), "players are not offered staff completions");
        expect(cmd.onTabComplete(p, c, "rs", new String[] {"manual", ""}).contains("all"), "staff are offered 'all'");
        expect(cmd.onTabComplete(plain, c, "rs", new String[] {"ma"}).contains("manual"), "tab completes the manual command");

        // a joining player is only scheduled for the book when it is switched on and not yet received
        RedstoneConfig off = new RedstoneConfig(null);
        off.manualGiveOnJoin = false;
        TestPlayer quiet = new TestPlayer();
        new ManualService(plugin, off, keys).onJoin(quiet);
        expect(!manual.hasReceivedCurrent(quiet), "give-on-join can be switched off");

        // describe every component type without throwing
        int n = 0;
        for (ComponentType t : ComponentType.values()) {
            Component comp = new Component("world", n++, 64, 0, t, p.getUniqueId(), Dir.NORTH);
            List<String> lines = service.describe(comp, 100);
            expect(!lines.isEmpty() && lines.get(0).contains(t.display), "describe " + t.id);
            for (ParamSpec spec : t.specs()) {
                String msg = service.applySetting(p, comp, spec.name(), spec.max() + 5);
                expect(msg.startsWith("§a") || msg.startsWith("§c"), "applySetting answers for " + t.id + "." + spec.name());
                expect(comp.param(spec.name()) >= spec.min() && comp.param(spec.name()) <= spec.max(), "setting stays in range " + t.id + "." + spec.name());
            }
        }
        expect(service.applySetting(p, new Component("world", 0, 0, 0, ComponentType.AND, null, Dir.NORTH), "nope", 1).contains("no setting"), "unknown setting is rejected politely");

        // simulation step with an empty registry is safe, and shutdown saves
        sim.run();
        expect(sim.tick() == c2.stepIntervalTicks, "simulation advances one interval per run");
        plugin.onDisable();

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
