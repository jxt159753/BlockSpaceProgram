package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.part.ParachutePerformance;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Runtime canopies in the order of the design's nodes and each part's baked canopy list. */
final class ShipParachutes {
    private final List<Mount> mounts = new ArrayList<>();
    private final int[] offsets;

    ShipParachutes(ShipDesign design) {
        offsets = new int[design.nodes().size() + 1];
        for (int index = 0; index < design.nodes().size(); index++) {
            var node = design.nodes().get(index);
            if (node.part() != null) {
                for (var parachute : node.part().parachutes()) {
                    var point = node.point(parachute.center());
                    mounts.add(new Mount(index, node.parachuteStage(), new Vector3d(point.x, point.y, point.z),
                            parachute.performance()));
                }
            }
            offsets[index + 1] = mounts.size();
        }
    }

    List<Mount> mounts() {
        return mounts;
    }

    void copyNode(ShipParachutes source, int from, int to) {
        int sourceIndex = source.offsets[from];
        for (int index = offsets[to]; index < offsets[to + 1]; index++) {
            mounts.get(index).deployment = source.mounts.get(sourceIndex++).deployment;
        }
    }

    void recover() {
        for (var mount : mounts) {
            mount.deployment = ParachuteDeployment.RECOVERED;
        }
    }

    byte[] snapshot() {
        byte[] states = new byte[mounts.size()];
        for (int index = 0; index < states.length; index++) {
            states[index] = mounts.get(index).deployment.pack();
        }
        return states;
    }

    static final class Mount {
        final int node;
        final int stage;
        final Vector3dc point;
        final ParachutePerformance performance;
        ParachuteDeployment deployment = ParachuteDeployment.STOWED;

        Mount(int node, int stage, Vector3dc point, ParachutePerformance performance) {
            this.node = node;
            this.stage = stage;
            this.point = point;
            this.performance = performance;
        }
    }
}
