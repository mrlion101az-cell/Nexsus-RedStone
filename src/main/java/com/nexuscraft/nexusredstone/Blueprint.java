package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A captured circuit: ordinary blocks (as their block-data strings) and NexusRedstone
 *  components (type, facing, settings), all as offsets from the capture's minimum corner. */
final class Blueprint {

    record Block(int dx, int dy, int dz, String data) {
    }

    record Comp(int dx, int dy, int dz, String type, String facing, Map<String, Integer> params,
                String text, List<String> filter) {
    }

    String name = "circuit";
    int sizeX;
    int sizeY;
    int sizeZ;
    final List<Block> blocks = new ArrayList<>();
    final List<Comp> components = new ArrayList<>();

    int totalBlocks() {
        return blocks.size() + components.size();
    }
}
