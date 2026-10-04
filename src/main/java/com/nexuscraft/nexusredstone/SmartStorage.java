package com.nexuscraft.nexusredstone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sort-and-merge plan for a sorting barrel. Input is a flat list of stack views (what each
 * slot holds, with items that are "the same" -- same material, same meta -- sharing a group id
 * chosen by the Bukkit layer); output is the new slot layout: every group merged into as few
 * full stacks as possible, laid out by category, then material name, then group, with any
 * remainder stacks last. Pure and deterministic, so sorting twice never changes anything, and the
 * total of every group is provably unchanged (the test suite checks both).
 */
final class SmartStorage {

    record StackView(int group, String material, int amount, int maxStack) {
    }

    record Placed(int group, String material, int amount) {
    }

    private SmartStorage() {
    }

    /** Returns the sorted, merged stacks (no empty entries). The caller places them in slot order
     *  and clears the remaining slots. */
    static List<Placed> sort(List<StackView> stacks) {
        Map<Integer, int[]> totals = new LinkedHashMap<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        for (StackView s : stacks) {
            if (s == null || s.amount() <= 0) {
                continue;
            }
            totals.computeIfAbsent(s.group(), g -> new int[] {0, Math.max(1, s.maxStack())})[0] += s.amount();
            names.putIfAbsent(s.group(), s.material());
        }
        List<Integer> groups = new ArrayList<>(totals.keySet());
        groups.sort(Comparator
                .<Integer>comparingInt(g -> ItemCategory.of(names.get(g)).ordinal())
                .thenComparing(names::get)
                .thenComparingInt(g -> g));
        List<Placed> out = new ArrayList<>();
        for (int g : groups) {
            int total = totals.get(g)[0];
            int max = totals.get(g)[1];
            while (total > 0) {
                int take = Math.min(max, total);
                out.add(new Placed(g, names.get(g), take));
                total -= take;
            }
        }
        return out;
    }

    /** True when the layout is already exactly what {@link #sort} would produce, so the caller
     *  can skip rewriting the inventory. {@code slotsInOrder} lists the current slots including
     *  empties (as amount 0). */
    static boolean alreadySorted(List<StackView> slotsInOrder, List<Placed> sorted) {
        int i = 0;
        for (StackView s : slotsInOrder) {
            if (i < sorted.size()) {
                Placed p = sorted.get(i);
                if (s == null || s.amount() != p.amount() || s.group() != p.group()) {
                    return false;
                }
            } else if (s != null && s.amount() > 0) {
                return false;
            }
            i++;
        }
        return true;
    }
}
