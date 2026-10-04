package com.nexuscraft.nexusredstone;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /redstone} (aliases {@code /rs}, {@code /nexusredstone}). One command with subcommands,
 * because players meet this plugin through its tools and use the command only to set up and to
 * look things up. Anything that changes a component first resolves it from what the player is
 * looking at (or last wrenched) and checks ownership.
 */
final class RedstoneCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("help", "skill", "list", "mine", "info", "set", "text",
            "filter", "blueprint", "scope", "visualize", "sort", "manual", "give", "remove", "lag", "stats", "reload");
    private static final List<String> ADMIN_ONLY = Arrays.asList("give", "lag", "stats", "reload");

    private final Context ctx;

    /** Everything the command needs, bundled so the plugin class wires it in one place. */
    static final class Context {
        RedstoneConfig cfg;
        ComponentRegistry registry;
        ComponentService service;
        ComponentItems items;
        EngineerStore engineers;
        ScopeService scopes;
        VisualizerService visualizer;
        BlueprintService blueprints;
        ToolListener tools;
        SorterService sorter;
        MoverService movers;
        ManualService manual;
        TransportService transport;
        SimulationTask sim;
        LagGuard lag;
        WirelessNetwork wireless;
        Runnable reload;
    }

    RedstoneCommand(Context ctx) {
        this.ctx = ctx;
    }

    private static boolean isAdmin(CommandSender s) {
        return s.hasPermission("nexusredstone.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (ADMIN_ONLY.contains(sub) && !isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to use /redstone " + sub + ".");
            return true;
        }
        switch (sub) {
            case "skill":
                skill(sender, rest);
                return true;
            case "list":
                list(sender, rest);
                return true;
            case "manual":
                manual(sender, rest);
                return true;
            case "give":
                give(sender, rest);
                return true;
            case "lag":
                lag(sender);
                return true;
            case "stats":
                stats(sender);
                return true;
            case "reload":
                ctx.reload.run();
                sender.sendMessage("§aNexusRedstone reloaded: config, messages and recipes.");
                return true;
            default:
                break;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThat subcommand must be used in game.");
            return true;
        }
        Player p = (Player) sender;
        switch (sub) {
            case "mine":
                mine(p);
                break;
            case "info":
                withTarget(p, c -> {
                    for (String line : ctx.service.describe(c, ctx.sim.tick())) {
                        p.sendMessage(line);
                    }
                });
                break;
            case "set":
                set(p, rest);
                break;
            case "text":
                text(p, rest);
                break;
            case "filter":
                filter(p, rest);
                break;
            case "blueprint":
                blueprint(p, rest);
                break;
            case "scope":
                if (rest.length > 0 && rest[0].equalsIgnoreCase("clear")) {
                    ctx.scopes.clear(p);
                    p.sendMessage("§7All your probes were removed.");
                } else {
                    for (String line : ctx.scopes.render(p, ctx.sim.tick())) {
                        p.sendMessage(line);
                    }
                }
                break;
            case "visualize":
                visualize(p, rest);
                break;
            case "sort":
                sort(p);
                break;
            case "remove":
                withTarget(p, c -> {
                    if (!ctx.service.canEdit(p, c)) {
                        p.sendMessage("§cThat component belongs to someone else.");
                        return;
                    }
                    if (c.type == ComponentType.BLOCK_MOVER) {
                        ctx.movers.settle(c);
                    }
                    ctx.service.remove(c, ComponentService.survival(p), true);
                    p.sendMessage("§7" + c.type.display + " removed.");
                });
                break;
            default:
                sender.sendMessage("§cUnknown subcommand. Try /redstone help.");
                break;
        }
        return true;
    }

    // ------------------------------------------------------------------ help / skill / list

    private void help(CommandSender s) {
        s.sendMessage("§6§lNexusRedstone §7- engineer's toolkit");
        s.sendMessage("§e/redstone skill §7- your Engineer level and what it unlocks");
        s.sendMessage("§e/redstone list [family] §7- every component and its unlock level");
        s.sendMessage("§e/redstone mine §7- components you own");
        s.sendMessage("§e/redstone info §7- inspect the component you are looking at");
        s.sendMessage("§e/redstone set <setting> <value> §7- change a setting");
        s.sendMessage("§e/redstone text <text> §7- set a channel, program, or item name");
        s.sendMessage("§e/redstone filter add|remove|clear|mode|list §7- hopper and port filters");
        s.sendMessage("§e/redstone blueprint save <name>|clear §7- capture a build (use the Blueprint Wand)");
        s.sendMessage("§e/redstone scope [clear] §7- read your multimeter probes");
        s.sendMessage("§e/redstone visualize [seconds|off] §7- reveal links, routes and live outputs");
        s.sendMessage("§e/redstone sort §7- sort the sorting barrel you look at");
        s.sendMessage("§e/redstone manual §7- get the Redstone Engineer's Manual (a book explaining everything)");
        s.sendMessage("§e/redstone remove §7- take a component down");
        if (isAdmin(s)) {
            s.sendMessage("§c/redstone manual all|<player> §7| §c/redstone give <item> [amount] [player] §7| §c/redstone lag §7| §c/redstone stats §7| §c/redstone reload");
        }
    }

    private void skill(CommandSender s, String[] args) {
        Player target = null;
        if (args.length > 0 && isAdmin(s)) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                s.sendMessage("§cThat player is not online.");
                return;
            }
        } else if (s instanceof Player) {
            target = (Player) s;
        }
        if (target == null) {
            s.sendMessage("§cSpecify a player.");
            return;
        }
        long xp = ctx.engineers.xpOf(target);
        int level = EngineerSkill.levelFor(xp, ctx.cfg);
        long next = EngineerSkill.xpForNextLevel(level, ctx.cfg);
        s.sendMessage("§6§lEngineer §f" + target.getName() + " §7- level §e" + level + " §7(" + EngineerSkill.title(level) + ")");
        s.sendMessage("§7XP §f" + xp + (next < 0 ? " §7(max level)" : " §7/ §f" + next + " §7for level " + (level + 1)));
        s.sendMessage("§7Components: §f" + ctx.registry.countOwnedBy(target.getUniqueId()) + "§7/§f" + EngineerSkill.maxComponents(level, ctx.cfg)
                + " §7| fastest clock §f" + EngineerSkill.minClockPeriod(level, ctx.cfg) + " ticks §7| wireless tier §f" + EngineerSkill.maxWirelessTier(level, ctx.cfg));
        s.sendMessage("§7Mega piston §f" + EngineerSkill.megaPistonLimit(level, ctx.cfg) + " blocks §7| mover §f" + EngineerSkill.moverBlocks(level, ctx.cfg)
                + " blocks §7| blueprint §f" + EngineerSkill.blueprintBlocks(level, ctx.cfg) + " blocks §7| pipes §f" + EngineerSkill.pipeLength(level, ctx.cfg)
                + " §7| ports §f" + EngineerSkill.portItems(level, ctx.cfg) + " items/pull");
        ComponentType soonest = null;
        for (ComponentType t : ComponentType.values()) {
            if (t.minLevel > level && (soonest == null || t.minLevel < soonest.minLevel)) {
                soonest = t;
            }
        }
        if (soonest != null) {
            s.sendMessage("§7Next unlock: §b" + soonest.display + " §7at level " + soonest.minLevel + ".");
        }
    }

    private void list(CommandSender s, String[] args) {
        int level = s instanceof Player ? ctx.engineers.levelOf((Player) s) : 99;
        String only = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : null;
        Map<Family, List<String>> byFamily = new EnumMap<>(Family.class);
        for (ComponentType t : ComponentType.values()) {
            if (only != null && !t.family.label.toLowerCase(Locale.ROOT).startsWith(only) && !t.id.startsWith(only)) {
                continue;
            }
            String mark = level >= t.minLevel ? "§a" : "§8";
            byFamily.computeIfAbsent(t.family, k -> new ArrayList<>()).add(mark + t.id + " §7(L" + t.minLevel + ")");
        }
        if (byFamily.isEmpty()) {
            s.sendMessage("§7Nothing matches '" + only + "'.");
            return;
        }
        s.sendMessage("§6Components §7(green = unlocked for you)");
        for (Map.Entry<Family, List<String>> e : byFamily.entrySet()) {
            s.sendMessage("§e" + e.getKey().label + ": " + String.join("§7, ", e.getValue()));
        }
    }

    private void mine(Player p) {
        List<Component> mine = new ArrayList<>();
        for (Component c : ctx.registry.all()) {
            if (p.getUniqueId().equals(c.owner)) {
                mine.add(c);
            }
        }
        p.sendMessage("§6You own §e" + mine.size() + "§6 components.");
        int shown = 0;
        for (Component c : mine) {
            if (shown++ >= 15) {
                p.sendMessage("§7... and " + (mine.size() - 15) + " more.");
                break;
            }
            p.sendMessage("§7- §f" + c.type.display + " §8" + c.world + " " + c.x + ", " + c.y + ", " + c.z
                    + (c.type.hasOutputCell ? " §7out §f" + c.output : ""));
        }
    }

    // ------------------------------------------------------------------ manual

    /** /redstone manual: your own copy; staff may also name a player or say "all". */
    private void manual(CommandSender s, String[] args) {
        if (args.length > 0) {
            if (!isAdmin(s)) {
                s.sendMessage("§cOnly staff can hand the manual to other players.");
                return;
            }
            if (args[0].equalsIgnoreCase("all")) {
                int n = ctx.manual.giveToAllOnline();
                s.sendMessage("§aGave the manual to " + n + " online player" + (n == 1 ? "" : "s") + " who did not have one.");
                return;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                s.sendMessage("§cThat player is not online.");
                return;
            }
            ctx.manual.give(target);
            s.sendMessage("§aGave the manual to " + target.getName() + ".");
            return;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("§cUsage: /redstone manual all|<player>");
            return;
        }
        Player p = (Player) s;
        if (ctx.manual.holdsManual(p)) {
            p.sendMessage("§7You already have the Redstone Engineer's Manual in your inventory. Right-click it to read.");
            return;
        }
        ctx.manual.give(p);
    }

    // ------------------------------------------------------------------ give

    private void give(CommandSender s, String[] args) {
        if (args.length < 1) {
            s.sendMessage("§cUsage: /redstone give <component|wrench|multimeter|wand> [amount] [player]");
            return;
        }
        int amount = 1;
        if (args.length > 1) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                s.sendMessage("§cThe amount must be a number.");
                return;
            }
        }
        Player target = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : (s instanceof Player ? (Player) s : null);
        if (target == null) {
            s.sendMessage("§cWho should receive it? Name an online player.");
            return;
        }
        ItemStack item;
        Tool tool = Tool.byId(args[0]);
        ComponentType type = ComponentType.byId(args[0]);
        if (tool != null) {
            item = ctx.items.tool(tool);
        } else if (type != null) {
            item = ctx.items.create(type, amount);
        } else {
            s.sendMessage("§cNo component or tool called '" + args[0] + "'. Try /redstone list.");
            return;
        }
        for (ItemStack rest : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        s.sendMessage("§aGave " + target.getName() + " " + (tool != null ? tool.display : amount + "x " + type.display) + ".");
    }

    // ------------------------------------------------------------------ editing

    private Component targetOf(Player p) {
        Block looked = p.getTargetBlockExact(6);
        if (looked != null) {
            Component c = ctx.registry.resolve(Geo.key(looked));
            if (c != null) {
                return c;
            }
        }
        return ctx.tools.selected(p);
    }

    private void withTarget(Player p, java.util.function.Consumer<Component> action) {
        Component c = targetOf(p);
        if (c == null) {
            p.sendMessage("§7Look at a component, or select one with the Engineer's Wrench first.");
            return;
        }
        action.accept(c);
    }

    private void set(Player p, String[] args) {
        withTarget(p, c -> {
            if (!ctx.service.canEdit(p, c)) {
                p.sendMessage("§cThat component belongs to someone else.");
                return;
            }
            if (args.length < 2) {
                StringBuilder sb = new StringBuilder();
                for (ParamSpec s : c.type.specs()) {
                    sb.append("\n§e").append(s.name()).append(" §7").append(s.min()).append("-").append(s.max())
                            .append(" (now §f").append(c.param(s.name())).append("§7): ").append(s.help());
                }
                p.sendMessage("§7Usage: /redstone set <setting> <value>" + (sb.length() == 0 ? "\n§7The " + c.type.display + " has no settings." : sb.toString()));
                return;
            }
            int value;
            try {
                value = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                p.sendMessage("§cThe value must be a whole number.");
                return;
            }
            p.sendMessage(ctx.service.applySetting(p, c, args[0].toLowerCase(Locale.ROOT), value));
        });
    }

    private void text(Player p, String[] args) {
        withTarget(p, c -> {
            if (!ctx.service.canEdit(p, c)) {
                p.sendMessage("§cThat component belongs to someone else.");
                return;
            }
            if (!c.type.usesText()) {
                p.sendMessage("§7The " + c.type.display + " has no text to set.");
                return;
            }
            String value = String.join(" ", args);
            if (c.type == ComponentType.WIRELESS_TX || c.type == ComponentType.WIRELESS_RX) {
                c.text = WirelessNetwork.normalize(value, ctx.cfg.maxChannelLength);
                p.sendMessage("§aChannel set to '" + c.text + "' (" + (c.param("shared") == 1 ? "public" : "private to you") + ").");
            } else if (c.type == ComponentType.SENSOR_ITEM) {
                c.text = value.isEmpty() ? "" : ItemFilter.normalize(value);
                p.sendMessage(c.text.isEmpty() ? "§aItem sensor now counts every dropped item." : "§aItem sensor now counts only " + c.text + ".");
            } else {
                int level = ctx.engineers.levelOf(p);
                Expression.Result res = Expression.compile(value, EngineerSkill.plcOps(level, ctx.cfg), ctx.cfg.plcMaxLength);
                if (!res.ok()) {
                    p.sendMessage("§c✖ " + res.error());
                    p.sendMessage("§7Variables: a (left input), b (right), c (back), s (sensor), m (memory), t (time), x. Example: §fa > 7 && !b");
                    return;
                }
                c.text = value;
                c.compiled = null;
                c.compileError = null;
                p.sendMessage("§a✔ Program accepted (" + res.expr().nodeCount + " operations of your " + EngineerSkill.plcOps(level, ctx.cfg) + " allowed).");
            }
            c.resetRuntime();
            ctx.registry.markDirty();
        });
    }

    private void filter(Player p, String[] args) {
        withTarget(p, c -> {
            if (!c.type.hasFilter()) {
                p.sendMessage("§7The " + c.type.display + " has no item filter.");
                return;
            }
            String op = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
            if (!op.equals("list") && !ctx.service.canEdit(p, c)) {
                p.sendMessage("§cThat component belongs to someone else.");
                return;
            }
            switch (op) {
                case "add": {
                    if (args.length < 2) {
                        p.sendMessage("§7Usage: /redstone filter add <MATERIAL or *_PATTERN> (or 'hand')");
                        return;
                    }
                    String entry = args[1].equalsIgnoreCase("hand") ? p.getInventory().getItemInMainHand().getType().name() : args[1];
                    switch (ItemFilter.add(c.filter, entry, c.type.filterSlots())) {
                        case "ok":
                            p.sendMessage("§aAdded " + ItemFilter.normalize(entry) + " (" + c.filter.size() + "/" + c.type.filterSlots() + ").");
                            break;
                        case "dup":
                            p.sendMessage("§7Already in the filter.");
                            break;
                        case "full":
                            p.sendMessage("§cThis " + c.type.display + " only holds " + c.type.filterSlots() + " filter entries.");
                            break;
                        default:
                            p.sendMessage("§cThat is not a valid item name.");
                            break;
                    }
                    break;
                }
                case "remove": {
                    if (args.length < 2) {
                        p.sendMessage("§7Usage: /redstone filter remove <entry>");
                        return;
                    }
                    p.sendMessage(c.filter.remove(ItemFilter.normalize(args[1])) ? "§7Removed." : "§7That entry is not in the filter.");
                    break;
                }
                case "clear":
                    c.filter.clear();
                    p.sendMessage("§7Filter cleared. It now passes everything.");
                    break;
                case "mode": {
                    if (args.length < 2) {
                        p.sendMessage("§7Usage: /redstone filter mode whitelist|blacklist");
                        return;
                    }
                    int mode = args[1].toLowerCase(Locale.ROOT).startsWith("b") ? ItemFilter.BLACKLIST : ItemFilter.WHITELIST;
                    p.sendMessage(ctx.service.applySetting(p, c, "mode", mode));
                    break;
                }
                default:
                    p.sendMessage("§7Filter (" + (c.param("mode") == ItemFilter.BLACKLIST ? "blacklist" : "whitelist") + "): §f"
                            + (c.filter.isEmpty() ? "(empty, passes everything)" : String.join(", ", c.filter)));
                    break;
            }
            ctx.registry.markDirty();
        });
    }

    private void blueprint(Player p, String[] args) {
        if (args.length == 0) {
            p.sendMessage("§7Usage: /redstone blueprint save <name> | clear. Select corners with the Blueprint Wand first.");
            return;
        }
        if (args[0].equalsIgnoreCase("clear")) {
            ctx.blueprints.clearCorners(p);
            p.sendMessage("§7Corners cleared.");
        } else if (args[0].equalsIgnoreCase("save")) {
            p.sendMessage(ctx.blueprints.capture(p, args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "circuit"));
        } else {
            p.sendMessage("§7Usage: /redstone blueprint save <name> | clear");
        }
    }

    private void visualize(Player p, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("off")) {
            ctx.visualizer.stop(p);
            p.sendMessage("§7Visualizer off.");
            return;
        }
        int seconds = ctx.cfg.visualizerSeconds;
        if (args.length > 0) {
            try {
                seconds = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                p.sendMessage("§cGive a number of seconds, or 'off'.");
                return;
            }
        }
        ctx.visualizer.start(p, seconds);
        p.sendMessage("§aVisualizer on for up to " + Math.min(seconds, ctx.cfg.visualizerSeconds * 5) + " seconds. §7Sparks = live outputs, dotted lines = wireless links and item routes, smoke = shut off by the lag guard.");
    }

    private void sort(Player p) {
        Component c = targetOf(p);
        if (c == null || c.type != ComponentType.SORTER) {
            p.sendMessage("§7Look at a Sorting Barrel.");
            return;
        }
        Block body = Geo.body(c);
        Inventory inv = body == null ? null : Containers.at(body);
        if (inv == null) {
            p.sendMessage("§cThat barrel cannot be read right now.");
            return;
        }
        p.sendMessage(ctx.sorter.sort(c, inv, p, true) ? "§aSorted." : "§7Already tidy.");
    }

    // ------------------------------------------------------------------ admin

    private void lag(CommandSender s) {
        s.sendMessage("§6§lLag report");
        CircuitEngine.StepStats st = ctx.sim.lastStats();
        s.sendMessage("§7Simulation step: §f" + String.format("%.3f", ctx.sim.lastStepMillis()) + " ms last, " + String.format("%.3f", ctx.sim.averageStepMillis())
                + " ms average §7| evaluated §f" + st.evaluated() + "§7, changed §f" + st.changed() + "§7, throttled §f" + st.skipped() + "§7, shut off §f" + st.tripped());
        int shut = 0;
        for (Component c : ctx.registry.all()) {
            if (c.lagTrippedUntil > ctx.sim.tick()) {
                shut++;
                s.sendMessage("§c  shut off: §f" + c.type.display + " §8" + c.world + " " + c.x + ", " + c.y + ", " + c.z + " §7(owner "
                        + (c.owner == null ? "none" : String.valueOf(Bukkit.getOfflinePlayer(c.owner).getName())) + ")");
            }
        }
        List<LagGuard.HotChunk> hot = ctx.lag.hottest(5);
        if (hot.isEmpty()) {
            s.sendMessage("§7No busy chunks right now.");
        }
        for (LagGuard.HotChunk h : hot) {
            s.sendMessage("§7  busy chunk §f" + h.chunkKey() + " §7- about §f" + h.perSecond() + "§7 signal changes per second");
        }
        if (shut == 0) {
            s.sendMessage("§7No components are currently shut off.");
        }
    }

    private void stats(CommandSender s) {
        Map<ComponentType, Integer> counts = new EnumMap<>(ComponentType.class);
        for (Component c : ctx.registry.all()) {
            counts.merge(c.type, 1, Integer::sum);
        }
        s.sendMessage("§6§lNexusRedstone §7- " + ctx.registry.size() + " components, " + ctx.wireless.transmitterCount() + " live transmitters, "
                + ctx.scopes.probeCount() + " probes, " + ctx.movers.activeTrips() + " mover trips in flight, "
                + ctx.transport.totalItemsMoved() + " items shipped this run");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ComponentType, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append("§7, ");
            }
            sb.append("§f").append(e.getKey().id).append(" §b").append(e.getValue());
        }
        s.sendMessage(sb.length() == 0 ? "§7Nothing placed yet." : sb.toString());
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : SUBS) {
                if (ADMIN_ONLY.contains(sub) && !isAdmin(sender)) {
                    continue;
                }
                out.add(sub);
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "manual":
                    if (isAdmin(sender)) {
                        out.add("all");
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            out.add(online.getName());
                        }
                    }
                    break;
                case "give":
                    out.addAll(ComponentType.ids());
                    for (Tool t : Tool.values()) {
                        out.add(t.id);
                    }
                    break;
                case "filter":
                    out.addAll(Arrays.asList("add", "remove", "clear", "mode", "list"));
                    break;
                case "blueprint":
                    out.addAll(Arrays.asList("save", "clear"));
                    break;
                case "scope":
                    out.add("clear");
                    break;
                case "visualize":
                    out.addAll(Arrays.asList("10", "30", "off"));
                    break;
                case "list":
                    for (Family f : Family.values()) {
                        out.add(f.label.toLowerCase(Locale.ROOT));
                    }
                    break;
                case "set":
                    if (sender instanceof Player) {
                        Component c = targetOf((Player) sender);
                        if (c != null) {
                            for (ParamSpec spec : c.type.specs()) {
                                out.add(spec.name());
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("filter") && args[1].equalsIgnoreCase("mode")) {
            out.addAll(Arrays.asList("whitelist", "blacklist"));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
