package io.github.jxt159753.blockspaceprogram.assembly;

import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.jxt159753.blockspaceprogram.collision.CollisionCellBlock;

/** Gives Sable's collision cells a contact callback when Sable is installed. */
public final class SableCollisionCellBlock extends CollisionCellBlock implements BlockWithSubLevelCollisionCallback {
    private static final BlockSubLevelCollisionCallback CONTACT = (cell, otherCell, point, speed) -> {
        var system = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        SableShipFlight.recordImpact(system.getLevel(), cell, point, speed);
        return BlockSubLevelCollisionCallback.CollisionResult.NONE;
    };

    public static CollisionCellBlock create(Properties properties) {
        return new SableCollisionCellBlock(properties);
    }

    private SableCollisionCellBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockSubLevelCollisionCallback sable$getCallback() {
        return CONTACT;
    }
}
