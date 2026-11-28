package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.BitSet;

/** Operations that preserve node membership when docking or cutting a ship graph. */
final class ShipNodeBits {
    private ShipNodeBits() {}

    static BitSet shifted(BitSet nodes, int offset) {
        var shifted = new BitSet();
        for (int node = nodes.nextSetBit(0); node >= 0; node = nodes.nextSetBit(node + 1)) {
            shifted.set(node + offset);
        }
        return shifted;
    }

    static BitSet remap(BitSet nodes, int[] indices) {
        var retained = new BitSet();
        for (int node = nodes.nextSetBit(0); node >= 0 && node < indices.length; node = nodes.nextSetBit(node + 1)) {
            if (indices[node] >= 0) {
                retained.set(indices[node]);
            }
        }
        return retained;
    }

    static int workingCount(BitSet nodes, BitSet damaged) {
        int count = 0;
        for (int node = nodes.nextSetBit(0); node >= 0; node = nodes.nextSetBit(node + 1)) {
            if (!damaged.get(node)) {
                count++;
            }
        }
        return count;
    }
}
