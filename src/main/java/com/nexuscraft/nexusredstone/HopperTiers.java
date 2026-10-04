package com.nexuscraft.nexusredstone;

/** The fixed numbers of each hopper tier. A vanilla hopper moves one item per transfer; a
 *  tiered hopper moves its tier's batch size in the same single transfer, so a tier upgrade is a
 *  real throughput multiplier without a faster cooldown that would break vanilla timing-based
 *  designs. */
final class HopperTiers {

    private HopperTiers() {
    }

    static int itemsPerTransfer(ComponentType type) {
        switch (type) {
            case HOPPER_IRON:
                return 4;
            case HOPPER_GOLD:
                return 16;
            case HOPPER_DIAMOND:
                return 32;
            case HOPPER_NETHERITE:
                return 64;
            default:
                return 1;
        }
    }

    /** How many items beyond the one vanilla already moves a tiered hopper should add. */
    static int extraItems(ComponentType type, RedstoneConfig cfg) {
        int total = (int) Math.round(itemsPerTransfer(type) * cfg.hopperBatchMultiplier);
        return Math.max(0, total - 1);
    }

    static int vacuumRadius(ComponentType type, RedstoneConfig cfg) {
        switch (type) {
            case HOPPER_DIAMOND:
                return cfg.hopperVacuumRadiusDiamond;
            case HOPPER_NETHERITE:
                return cfg.hopperVacuumRadiusNetherite;
            default:
                return 0;
        }
    }

    static boolean canVacuum(ComponentType type) {
        return type == ComponentType.HOPPER_DIAMOND || type == ComponentType.HOPPER_NETHERITE;
    }
}
