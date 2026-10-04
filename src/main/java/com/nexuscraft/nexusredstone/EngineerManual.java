package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The text of the in-game Redstone Engineer's Manual, a written book handed to every player. Pure
 * content: no Bukkit types, so the page budget and the facts can both be unit-tested.
 *
 * <p>Numbers that a server owner can tune (wireless ranges, component limits, reach of the mega
 * piston, lag-guard limits) are read from {@link RedstoneConfig}, and the component directory is
 * built from {@link ComponentType}, so the book never disagrees with the plugin it documents.
 * Hand-written pages are sized to fit a written-book page; {@link #fits} is the rule the test uses.
 *
 * <p>Pages are grouped into chapters. The contents pages are generated last, from the chapters'
 * real starting pages, so inserting a page never leaves a stale page number.
 */
final class EngineerManual {

    /** Bumped whenever the book's content changes enough that players should receive the new edition. */
    static final int EDITION = 1;

    /** Conservative size of one written-book page: 14 lines, about 18 characters wide. */
    static final int MAX_LINES = 14;
    static final int LINE_WIDTH = 18;
    static final int MAX_CHARS = 256;

    private static final String H = "§l§4";
    private static final String R = "§r";

    private final RedstoneConfig cfg;
    /** Chapter title to its pages, in reading order. */
    private final Map<String, List<String>> chapters = new LinkedHashMap<>();

    private EngineerManual(RedstoneConfig cfg) {
        this.cfg = cfg;
    }

    /** Every page of the book, cover and contents first. */
    static List<String> pages(RedstoneConfig cfg) {
        EngineerManual m = new EngineerManual(cfg);
        m.writeChapters();
        return m.assemble();
    }

    /** The chapter titles, in order (used by the test and the contents). */
    static List<String> chapterTitles(RedstoneConfig cfg) {
        EngineerManual m = new EngineerManual(cfg);
        m.writeChapters();
        return new ArrayList<>(m.chapters.keySet());
    }

    /** The first page of each chapter, in order (the test checks the contents against these). */
    static List<String> chapterFirstPages(RedstoneConfig cfg) {
        EngineerManual m = new EngineerManual(cfg);
        m.writeChapters();
        List<String> out = new ArrayList<>();
        for (List<String> pages : m.chapters.values()) {
            out.add(pages.get(0));
        }
        return out;
    }

    // ------------------------------------------------------------------ size rules

    /** The page text with colour and format codes removed: what a reader actually sees. */
    static String visible(String page) {
        StringBuilder sb = new StringBuilder(page.length());
        for (int i = 0; i < page.length(); i++) {
            char c = page.charAt(i);
            if (c == '§' && i + 1 < page.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Lines a page occupies when word-wrapped at {@link #LINE_WIDTH}. */
    static int lineCount(String page) {
        int lines = 0;
        for (String raw : visible(page).split("\n", -1)) {
            lines += wrapped(raw);
        }
        return lines;
    }

    private static int wrapped(String line) {
        if (line.isEmpty()) {
            return 1;
        }
        int lines = 1;
        int col = 0;
        for (String word : line.split(" ", -1)) {
            int len = word.length();
            if (len > LINE_WIDTH) {
                // A word longer than a line is broken wherever it falls.
                int room = col == 0 ? LINE_WIDTH : LINE_WIDTH - col - 1;
                int rest = len - Math.max(0, room);
                lines += (rest + LINE_WIDTH - 1) / LINE_WIDTH;
                col = rest % LINE_WIDTH == 0 ? LINE_WIDTH : rest % LINE_WIDTH;
                continue;
            }
            if (col == 0) {
                col = len;
            } else if (col + 1 + len <= LINE_WIDTH) {
                col += 1 + len;
            } else {
                lines++;
                col = len;
            }
        }
        return lines;
    }

    /** True if the page fits both the line and the character budget of a written-book page. */
    static boolean fits(String page) {
        return lineCount(page) <= MAX_LINES && visible(page).length() <= MAX_CHARS;
    }

    // ------------------------------------------------------------------ assembly

    private List<String> assemble() {
        List<String> titles = new ArrayList<>(chapters.keySet());
        int perPage = 12;
        int contentsPages = Math.max(1, (titles.size() + perPage - 1) / perPage);
        int next = 1 + contentsPages + 1; // 1-based: cover, contents, then the first chapter

        List<String> out = new ArrayList<>();
        out.add(cover());
        List<String> contents = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int onPage = 0;
        for (String title : titles) {
            if (onPage == 0) {
                page.setLength(0);
                page.append(H).append(contents.isEmpty() ? "Contents" : "Contents 2").append(R).append('\n');
            }
            page.append(shorten(title, 15)).append(' ').append(next).append('\n');
            next += chapters.get(title).size();
            onPage++;
            if (onPage == perPage) {
                contents.add(page.toString().trim());
                onPage = 0;
            }
        }
        if (onPage > 0) {
            contents.add(page.toString().trim());
        }
        out.addAll(contents);
        for (List<String> pages : chapters.values()) {
            out.addAll(pages);
        }
        return out;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String cover() {
        return H + "REDSTONE" + R + "\n" + H + "ENGINEER'S" + R + "\n" + H + "MANUAL" + R
                + "\n\nEverything new in\nNexusRedstone: what\neach part does and\nhow to build with\nit.\n\nA copy for every\nengineer.";
    }

    private void chapter(String title, String... pages) {
        List<String> list = new ArrayList<>();
        for (String p : pages) {
            list.add(p);
        }
        chapters.put(title, list);
    }

    private static String h(String text) {
        return H + text + R + "\n";
    }

    // ------------------------------------------------------------------ content

    private void writeChapters() {
        welcome();
        tools();
        howPartsWork();
        skill();
        gates();
        memory();
        timing();
        analog();
        programs();
        wireless();
        sensors();
        hoppers();
        network();
        storageAndDispensers();
        piston();
        mover();
        blueprints();
        debugging();
        lagGuard();
        voices();
        commands();
        closing();
    }

    private void welcome() {
        chapter("Welcome",
                h("Welcome")
                        + "Redstone has barely changed in years. NexusRedstone adds a new tier of parts: gates, memory, clocks, wireless, sensors, a tiny programming language, smart hoppers, pipes, a mega piston and a block mover.",
                "Nothing you built breaks. Vanilla redstone works exactly as before.\n\nNew parts are ordinary blocks with hidden data. Wire them with dust, repeaters, levers and comparators, as always.");
    }

    private void tools() {
        chapter("Your Tools",
                h("Three tools")
                        + "Wrench (blaze rod): right-click a part to inspect it. Sneak and right-click cycles its main setting.",
                "Multimeter (spyglass): right-click any block to read its power and watch it on a scope.",
                "Blueprint Wand (breeze rod): left-click one corner, right-click the opposite corner.\n\nEvery recipe is in your recipe book. /redstone list shows all parts.");
    }

    private void howPartsWork() {
        chapter("How Parts Work",
                h("Facing")
                        + "A part faces away from you. Its two sides are inputs. The block behind it is a third input. The block in front is its output cell.",
                h("Output cells")
                        + "On: a redstone block.\nOff: smooth stone.\nAnalog: a barrel of iron nuggets. Put a comparator on it to read the exact strength.",
                "Cells are protected: no mining, pushing, blasts or hoppers. Break the part and its cell goes too.\n\nEach hop takes one step (" + cfg.stepIntervalTicks + " ticks), like a repeater.");
    }

    private void skill() {
        StringBuilder titles = new StringBuilder();
        int[] marks = {1, 3, 8, 15, 25, 35, 45};
        for (int level : marks) {
            if (titles.length() > 0) {
                titles.append(", ");
            }
            titles.append(EngineerSkill.title(level));
        }
        chapter("Engineer Skill",
                h("Engineer skill")
                        + "Build to level up, from 1 to " + cfg.skillMaxLevel + ". Titles: " + titles + ".",
                "XP comes from placing each kind of part the first time, a small capped trickle from machines that really run, and items shipped by pipe.",
                "Your level sets: which parts you may place, how many you may own (" + cfg.maxComponentsBase + " at level 1, up to " + cfg.maxComponentsCeiling
                        + "), clock speed, wireless tier, program size, piston reach, pipe length.\n/redstone skill");
    }

    private void gates() {
        chapter("Logic Gates",
                h("Logic gates")
                        + "Quartz blocks. Left and right are the two inputs.\n" + shortGateList(),
                "NOT inverts the back input.\n\nAnything vanilla feeds a gate with no adapter: dust, repeaters, levers, observers, buttons.");
    }

    private static String shortGateList() {
        Map<Integer, List<String>> byLevel = new TreeMap<>();
        for (ComponentType t : ComponentType.values()) {
            if (t.family == Family.LOGIC) {
                byLevel.computeIfAbsent(t.minLevel, k -> new ArrayList<>()).add(t.id.toUpperCase(java.util.Locale.ROOT));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, List<String>> e : byLevel.entrySet()) {
            sb.append(String.join(" ", e.getValue())).append(" (").append(e.getKey()).append(")\n");
        }
        return sb.toString().trim();
    }

    private void memory() {
        chapter("Memory",
                h("Memory")
                        + "Lapis blocks. They remember, even across restarts.\n" + shortLevelNames(Family.MEMORY),
                "SR Latch: left sets, right resets.\nT Flip-Flop: each pulse flips it.\nCounter: counts pulses to a target. Modes: pulse, hold, or analog fill. A left input resets it.");
    }

    private static String shortLevelNames(Family family) {
        StringBuilder sb = new StringBuilder();
        for (ComponentType t : ComponentType.values()) {
            if (t.family == family) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.display).append(" (").append(t.minLevel).append(')');
            }
        }
        return sb.toString();
    }

    private void timing() {
        chapter("Timing",
                h("Timing")
                        + "Gold blocks.\nOn-Delay waits, then turns on.\nOff-Delay holds, then turns off.\nPulse Shaper turns any edge into one clean pulse.",
                h("Precision Clock")
                        + "Period 2 to 2400 ticks with adjustable on-time. Modes: free-run, run while powered, run while off, or a burst of N pulses. Faster clocks need a higher level: ("
                        + EngineerSkill.minClockPeriod(1, cfg) + " ticks at level 1.)");
    }

    private void analog() {
        chapter("Analog",
                h("Analog")
                        + "Copper blocks.\nThreshold: on at a set strength, with hysteresis so it never flickers.\nAmplifier: scales a signal by a percent.\nMath: add, subtract, min, max, average.",
                "Analog outputs are barrels of nuggets, so a vanilla comparator reads exactly what the part meant.");
    }

    private void programs() {
        chapter("Programs",
                h("Programmable")
                        + "A diamond block runs a tiny program. Set it with /redstone text.\n\nVariables: a left, b right, c back, s sensor, m memory, t time.",
                "Math: + - * / %\nTests: < > == && || !\nFunctions: min max abs clamp if\n\nTrue is full power. A number is clamped to 0-15.",
                "A ; ends the output and starts a memory update:\na > 7 && !b ; m + 1\n\nNo loops, no recursion. Program size grows with level (" + cfg.plcBaseOps + " ops at level 1, up to " + cfg.plcMaxOps + ").");
    }

    private void wireless() {
        chapter("Wireless",
                h("Wireless")
                        + "Amethyst blocks. A transmitter broadcasts its strongest input on a channel. Receivers on that channel output it.\n\n/redstone text <channel>",
                "Channels are private to you, so nobody crosses your wires. Set shared to 1 for a public channel.",
                "Range tiers: local " + cfg.wirelessRangeLocal + ", regional " + cfg.wirelessRangeRegional + " (level " + cfg.wirelessLevelRegional + "), long " + cfg.wirelessRangeLong
                        + " (level " + cfg.wirelessLevelLong + "), and across dimensions (level " + cfg.wirelessLevelCross + ").",
                "A link is as strong as its weaker end. Signals take time to travel, so a far receiver hears it a moment late.");
    }

    private void sensors() {
        chapter("Sensors",
                h("Sensors")
                        + "Emerald blocks that read the world:\nPlayer, Mob, Item, Light, Weather, Time, and Fill (how full the container behind it is).",
                "Each sensor is on/off, or analog: a count or a level as strength 1-15.\n\nRadius is set per sensor. Sensors check the world every " + cfg.sensorIntervalTicks + " ticks.");
    }

    private void hoppers() {
        chapter("Smart Hoppers",
                h("Hopper tiers")
                        + "Real hoppers that move more per transfer and filter:\nIron: 4 items, 3 filters\nGold: 16, 6\nDiamond: 32, 9\nNetherite: 64, 12",
                "Each tier is crafted from the one below. Everything vanilla about a hopper still holds.",
                "Filters take whitelist or blacklist, and wildcards like *_ORE. An empty filter passes everything.\n\n/redstone filter add hand",
                "Diamond and Netherite hoppers also vacuum up dropped items nearby (" + cfg.hopperVacuumRadiusDiamond + " and " + cfg.hopperVacuumRadiusNetherite + " blocks).");
    }

    private void network() {
        chapter("Item Network",
                h("No wires")
                        + "Input Port (oxidized copper) pulls items from the container beside it.\nOutput Port (weathered copper) pushes them into the container beside it.",
                "Iron-bar Pipes join ports of the same network colour (0-15). Different colours never connect.",
                "Output ports have a priority; equal priorities share items in turn. Ports take filters too.\n\nPipe length starts at " + cfg.pipeLengthBase + " and grows with level.",
                "Input ports can also take finished goods from a furnace's result slot, never its input or fuel.");
    }

    private void storageAndDispensers() {
        chapter("Barrel, Dropper",
                h("Sorting Barrel")
                        + "Closes into order: items merge into full stacks, grouped by kind. It proves it lost nothing before writing back. /redstone sort",
                h("Smart Dispenser")
                        + "Four modes: normal, burst (extra shots), spray, and power (faster).\nThe Smart Dropper has normal, burst, gentle, and power.\nBurst count and gap are settings.");
    }

    private void piston() {
        chapter("Mega Piston",
                h("Mega Piston")
                        + "An iron block that pushes a long line of blocks, far beyond vanilla's 12.\nReach grows with level, up to " + cfg.megaLimitCeiling + ".",
                "Pick forward, up or down. Stroke is 1-8 blocks. Sticky mode pulls the line back.\n\nWhile extended it shows a rod that can't be broken. It does not push entities.");
    }

    private void mover() {
        chapter("Block Mover",
                h("Block Mover")
                        + "A lodestone that carries a whole building. Touch it with honey or slime blocks: glue spreads to every block it touches.",
                "It slides the structure up to " + cfg.moverMaxDistance + " blocks along an axis, and carries anyone standing on it.\n\nIn the way? It stops as far as is safe.",
                "Trips are written to disk first, so a crash mid-move is finished on the next start. Size: " + cfg.moverBlocksBase + " blocks at level 1, up to " + cfg.moverBlocksCeiling + ".");
    }

    private void blueprints() {
        chapter("Blueprints",
                h("Blueprints")
                        + "Mark two corners with the Wand, then /redstone blueprint save <name>. You get a paper holding the whole build and every part's settings.",
                "Right-click a block with the paper: an outline and a bill of materials appear.\n\nRight-click the same spot within 15 seconds to build.",
                "A paste is all or nothing. It checks your level, a clear area, claims, and your materials. It never overwrites anything.");
    }

    private void debugging() {
        chapter("Debugging",
                h("Wrench")
                        + "Right-click a part: owner, settings, live state, errors and lag status.",
                h("Multimeter")
                        + "Right-click any block: its power, and a scope probe starts. Sneak and right-click in the air (or /redstone scope) draws the last ten seconds as a waveform.",
                "The scope reports the measured period, pulse count and duty cycle: a clock's true speed, a stuck latch, a one-tick glitch.\n\nUp to " + cfg.maxProbesPerPlayer + " probes at once.",
                h("Visualize")
                        + "/redstone visualize shows, to you only: live outputs, wireless links, pipe routes, and anything the lag guard shut off.");
    }

    private void lagGuard() {
        chapter("Lag Guard",
                h("Lag guard")
                        + "Redstone is great until a bad clock slows the server. The guard watches your parts and tells you why it steps in.",
                "A part flipping more than " + cfg.lagMaxTogglesPerWindow + " times in " + (cfg.lagWindowTicks / 20) + " seconds is shut off for " + cfg.lagTripCooldownSeconds
                        + " seconds. A chunk over its budget slows, then the worst part trips.\n\nPlain vanilla redstone is only measured, and staff are told.");
    }

    private void voices() {
        chapter("Voices",
                h("Voices")
                        + "Build, and your work answers: first builds, level-ups, a clock coming alive, a latch flipping, a piston heaving.\n\nNearby villagers may remark on your machine.");
    }

    private void commands() {
        chapter("Commands",
                h("Commands")
                        + "/redstone skill\n/redstone list\n/redstone mine\n/redstone info\n/redstone set <x> <n>\n/redstone text <text>\n/redstone filter\n/redstone remove",
                "/redstone blueprint\n/redstone scope\n/redstone visualize\n/redstone sort\n/redstone manual\n\nShort form: /rs");
    }

    private void closing() {
        chapter("Go Build",
                h("Go build")
                        + "Start with a Wrench and an AND gate. Find out what a clock really does with the Multimeter.\n\nThe tools are new. The wiring is yours.\n\nHappy engineering.");
    }
}
