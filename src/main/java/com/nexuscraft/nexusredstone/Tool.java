package com.nexuscraft.nexusredstone;

/** The three hand tools. Their material is only how they look; identity lives in item data. */
enum Tool {
    WRENCH("wrench", "Engineer's Wrench", "BLAZE_ROD",
            "Right-click a component to select and inspect it. Sneak + right-click cycles its main setting."),
    MULTIMETER("multimeter", "Multimeter", "SPYGLASS",
            "Right-click any block to read its redstone power and start a scope probe on it. Sneak + right-click in the air shows your scopes."),
    BLUEPRINT_WAND("wand", "Blueprint Wand", "BREEZE_ROD",
            "Left-click one corner, right-click the opposite corner, then /redstone blueprint save <name>.");

    final String id;
    final String display;
    final String material;
    final String help;

    Tool(String id, String display, String material, String help) {
        this.id = id;
        this.display = display;
        this.material = material;
        this.help = help;
    }

    static Tool byId(String id) {
        for (Tool t : values()) {
            if (t.id.equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }
}
