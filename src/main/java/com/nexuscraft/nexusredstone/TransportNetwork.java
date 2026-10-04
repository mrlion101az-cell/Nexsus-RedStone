package com.nexuscraft.nexusredstone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Routing across the item transport network: pipes and ports of the same colour that touch
 * face-to-face form a graph. From an input port, a breadth-first search finds every reachable
 * output port, never passing through another port (ports are endpoints, not junctions) and never
 * more than a path-length limit of pipes away, with a hard node cap so a sprawling or hostile
 * network can never stall the server thread.
 *
 * <p>Destinations are ordered by output priority (higher first); within one priority, they are
 * served round-robin using the input port's cursor, so several identical sinks fill evenly. An
 * input port is also allowed to touch an output port directly (path length 0) -- a one-block
 * "item teleporter" between two adjacent chests.
 */
final class TransportNetwork {

    record Destination(Component port, int pathLength) {
    }

    record Result(List<Destination> destinations, Set<String> pipesTouched, boolean truncated) {
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private TransportNetwork() {
    }

    static Result route(ComponentRegistry reg, Component input, int maxPath, int maxNodes) {
        int net = input.param("net");
        Map<String, Integer> dist = new HashMap<>();
        Set<String> pipes = new HashSet<>();
        List<Destination> found = new ArrayList<>();
        ArrayDeque<Component> queue = new ArrayDeque<>();
        boolean truncated = false;
        int visited = 0;

        dist.put(input.key(), 0);
        queue.add(input);
        while (!queue.isEmpty()) {
            Component cur = queue.poll();
            int d = dist.get(cur.key());
            for (int[] f : FACES) {
                Component n = reg.at(cur.world, cur.x + f[0], cur.y + f[1], cur.z + f[2]);
                if (n == null || n.type.family != Family.PIPE || n.param("net") != net || dist.containsKey(n.key())) {
                    continue;
                }
                if (n.type == ComponentType.PIPE) {
                    int nd = (cur.type == ComponentType.PIPE ? d : 0) + 1;
                    if (nd > maxPath) {
                        truncated = true;
                        continue;
                    }
                    if (++visited > maxNodes) {
                        return new Result(sorted(found), pipes, true);
                    }
                    dist.put(n.key(), nd);
                    pipes.add(n.key());
                    queue.add(n);
                } else if (n.type == ComponentType.PORT_OUT) {
                    // A port is an endpoint: record it, but never expand through it. A port
                    // touching the input directly has path length 0.
                    int length = cur.type == ComponentType.PIPE ? d : 0;
                    dist.put(n.key(), length);
                    found.add(new Destination(n, length));
                } else {
                    dist.put(n.key(), d);
                }
            }
        }
        return new Result(sorted(found), pipes, truncated);
    }

    /** Priority descending, then a stable positional order so round-robin rotation is
     *  deterministic across calls. */
    private static List<Destination> sorted(List<Destination> list) {
        List<Destination> out = new ArrayList<>(list);
        out.sort(Comparator.<Destination>comparingInt(d -> -d.port().param("priority"))
                .thenComparing(d -> d.port().key()));
        return out;
    }

    /** Rotates each equal-priority group by {@code cursor} so successive calls with an
     *  incrementing cursor serve the group's members evenly. */
    static List<Destination> roundRobin(List<Destination> ordered, int cursor) {
        List<Destination> out = new ArrayList<>(ordered.size());
        int i = 0;
        while (i < ordered.size()) {
            int j = i;
            int priority = ordered.get(i).port().param("priority");
            while (j < ordered.size() && ordered.get(j).port().param("priority") == priority) {
                j++;
            }
            int size = j - i;
            int shift = size == 0 ? 0 : Math.floorMod(cursor, size);
            for (int k = 0; k < size; k++) {
                out.add(ordered.get(i + (k + shift) % size));
            }
            i = j;
        }
        return out;
    }
}
