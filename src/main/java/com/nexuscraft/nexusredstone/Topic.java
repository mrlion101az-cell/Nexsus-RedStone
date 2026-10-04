package com.nexuscraft.nexusredstone;

/**
 * Moments worth a line of dramatic text, with the built-in default lines for each. Defaults live
 * in code so the plugin is never silent if config.yml is missing or edited down; config.yml's
 * {@code messages.lines.<key>} lists, when present, replace a topic's defaults entirely.
 */
enum Topic {
    FIRST_COMPONENT("first-component", "§e§l⚙ ", false, new String[] {
            "Your first circuit component clicks into place. Every great machine starts with one.",
            "The first piece is down. The rest of the machine already exists in your head.",
            "A component, placed. Somewhere, a villager nods approvingly at your ambition."}),
    LEVEL_UP("level-up", "§b§l⬆ ", false, new String[] {
            "Your understanding of the wire deepens.",
            "The diagrams in your head just got a little sharper.",
            "Another rung up the engineer's ladder."}),
    CLOCK_ALIVE("clock-alive", "§6§l⏱ ", true, new String[] {
            "Your clock ticks to life -- a steady heartbeat for the machine.",
            "Tick. Tick. Tick. The sweetest sound a redstoner knows.",
            "The pulse starts. Somewhere a piston is already getting nervous."}),
    LATCH_FLIPPED("latch-flipped", "§9§l◆ ", true, new String[] {
            "The latch snaps over and remembers. It will not forget.",
            "A bit of memory, held in nothing but stone and a spark."}),
    COUNTER_FIRED("counter-fired", "§a§l✔ ", true, new String[] {
            "The counter reaches its mark and fires!",
            "Count complete. The machine takes its next step.",
            "Right on target -- the tally is done."}),
    WIRELESS_LINKED("wireless-linked", "§d§l✺ ", true, new String[] {
            "A signal crosses the distance with no wire at all. Magic? No -- engineering.",
            "The receiver hums. Far away, a transmitter has spoken.",
            "Link established. The air itself carries your signal now."}),
    PLC_RUNNING("plc-running", "§3§l⌬ ", true, new String[] {
            "Your program runs. Stone has learned to think.",
            "The logic block executes its first instructions flawlessly.",
            "Code in a block of diamond -- the redstoner's dream."}),
    PLC_ERROR("plc-error", "§c§l✖ ", false, new String[] {
            "The program won't compile. Check the expression and try again.",
            "A syntax stumble. The logic block waits patiently for a fix."}),
    LAG_TRIP("lag-trip", "§c§l⚠ ", false, new String[] {
            "The safety governor cut your circuit short before it could choke the server.",
            "That machine was running far too hot -- the governor shut it down.",
            "Easy there! A runaway signal loop was cooled off."}),
    PISTON_FIRED("piston-fired", "§7§l⇨ ", true, new String[] {
            "The mega piston heaves, and the whole line groans forward.",
            "A great shove -- the vanilla limit is a distant memory.",
            "The mega piston drives home with a satisfying thump."}),
    MOVER_TRIP("mover-trip", "§5§l⇧ ", true, new String[] {
            "The whole structure glides away, block by block as one.",
            "Heavy machinery in motion -- the contraption is on its way.",
            "A whole building slides smoothly along its track."}),
    TRANSPORT_MILESTONE("transport-milestone", "§e§l➤ ", true, new String[] {
            "The network hums: another thousand items sent without a hand on a chest.",
            "Pipes and ports, quietly doing the work of a hundred hoppers.",
            "A thousand items shipped. Logistics is a beautiful thing."}),
    SORTED("sorted", "§f§l≡ ", false, new String[] {
            "Everything snaps into neat order.",
            "Sorted. A tidy barrel is a happy barrel."}),
    BLUEPRINT_SAVED("blueprint-saved", "§b§l▣ ", false, new String[] {
            "Blueprint captured. The design is safe from now on.",
            "Your circuit is committed to paper. Build it again anywhere."}),
    BLUEPRINT_PASTED("blueprint-pasted", "§b§l▣ ", false, new String[] {
            "The blueprint unfolds into a working build.",
            "Built from plans in an instant -- the mark of a true architect."}),
    HOPPER_UPGRADED("hopper-upgraded", "§6§l▼ ", false, new String[] {
            "A proper hopper at last -- items fly instead of trickle.",
            "That hopper moves a lot more than one item at a time now."}),
    SENSOR_TRIGGERED("sensor-triggered", "§a§l● ", true, new String[] {
            "Your sensor catches something and the circuit wakes up.",
            "The sensor trips -- the machine has eyes now."});

    final String key;
    final String prefix;
    /** True for topics that fire from running circuits (rate-limited per owner, never spammed). */
    final boolean ambient;
    final String[] defaults;

    Topic(String key, String prefix, boolean ambient, String[] defaults) {
        this.key = key;
        this.prefix = prefix;
        this.ambient = ambient;
        this.defaults = defaults;
    }
}
