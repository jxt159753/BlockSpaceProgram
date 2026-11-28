package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.PortKey;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import org.jetbrains.annotations.Nullable;

/** Original ship and stage records shared by docking edges. Part membership survives alternate paths in a loop. */
record ShipDockingState(List<StageBlock> stages, Set<PortKey> released) {
    static final ShipDockingState EMPTY = new ShipDockingState(List.of(), Set.of());

    ShipDockingState {
        stages = List.copyOf(stages);
        released = Set.copyOf(released);
    }

    record Identity(UUID id, Optional<UUID> owner, Optional<Component> name) {}

    record StageBlock(BitSet parts, int root, int count, int progress, Optional<Identity> identity) {
        StageBlock {
            parts = (BitSet) parts.clone();
        }

        @Override
        public BitSet parts() {
            return (BitSet) parts.clone();
        }

        int progressAt(int stageInBlock) {
            return Math.max(progress, Math.min(stageInBlock, count - 1));
        }

        StageBlock project(int[] indices, int nextProgress) {
            var retained = ShipNodeBits.remap(parts, indices);
            int nextRoot = root < 0 ? -1 : indices[root];
            return new StageBlock(retained, nextRoot, count, nextProgress, nextRoot < 0 ? Optional.empty() : identity);
        }
    }

    ShipDockingState snapshot(ShipDesign design, int stage, Identity ship) {
        if (stages.isEmpty()) {
            var parts = new BitSet();
            parts.set(0, design.nodes().size());
            return new ShipDockingState(List.of(new StageBlock(parts, 0, design.stageCount(), stage, Optional.of(ship))), released);
        }
        var snapshot = new ArrayList<StageBlock>(stages.size());
        int start = 0;
        for (var block : stages) {
            snapshot.add(new StageBlock(block.parts, block.root(), block.count(), block.progressAt(stage - start),
                    block.identity().map(identity -> identity.id().equals(ship.id()) ? ship : identity)));
            start += block.count();
        }
        return new ShipDockingState(snapshot, released);
    }

    ShipDockingState merge(ShipDockingState other, int offset) {
        var combined = new ArrayList<>(stages);
        for (var block : other.stages) {
            combined.add(new StageBlock(ShipNodeBits.shifted(block.parts, offset), block.root() < 0 ? -1 : block.root() + offset,
                    block.count(), block.progress(), block.identity()));
        }
        var ports = new HashSet<>(released);
        other.released.forEach(port -> ports.add(port.at(port.node() + offset)));
        return new ShipDockingState(combined, ports);
    }

    ShipDockingState release(PortKey port) {
        var ports = new HashSet<>(released);
        ports.add(port);
        return new ShipDockingState(stages, ports);
    }

    int nextStage(int current) {
        int next = current + 1;
        int start = 0;
        for (var block : stages) {
            if (next < start + block.count()) {
                return Math.max(next, start + block.progress());
            }
            start += block.count();
        }
        return next;
    }

    /** A docked node also retains stages already executed aboard its original ship. */
    int actionStage(int node, int current) {
        int start = 0;
        for (var block : stages) {
            if (block.parts.get(node)) {
                return Math.max(current, start + block.progress());
            }
            start += block.count();
        }
        return current;
    }

    @Nullable StageBlock rootIn(List<Integer> members) {
        return stages.stream().filter(block -> members.contains(block.root())).findFirst().orElse(null);
    }

    Projection project(int[] indices, int stage, int originalStageCount) {
        var ports = new HashSet<PortKey>();
        for (var port : released) {
            int index = indices[port.node()];
            if (index >= 0) {
                ports.add(port.at(index));
            }
        }
        var retained = new ArrayList<StageBlock>();
        int[] mapping = new int[originalStageCount];
        for (int i = 0; i < mapping.length; i++) {
            mapping[i] = i;
        }
        if (stages.isEmpty()) {
            return new Projection(new ShipDockingState(retained, ports), mapping, originalStageCount, stage);
        }

        int oldStart = 0;
        int newStart = 0;
        int nextStage = -1;
        for (var block : stages) {
            var next = block.project(indices, block.progressAt(stage - oldStart));
            if (!next.parts.isEmpty()) {
                if (retained.isEmpty() || stage >= oldStart) {
                    nextStage = newStart + next.progress();
                }
                retained.add(next);
                for (int i = 0; i < block.count(); i++) {
                    mapping[oldStart + i] = newStart + i;
                }
                newStart += block.count();
            }
            oldStart += block.count();
        }
        // Once only one pre-dock ship remains, its live entity can store its stage and identity directly.
        if (retained.size() == 1) {
            retained.clear();
        }
        return new Projection(new ShipDockingState(retained, ports), mapping, newStart, nextStage);
    }

    record Projection(ShipDockingState state, int[] stageMapping, int stageCount, int stage) {
        int mapStage(int oldStage) {
            return stageMapping[oldStage];
        }
    }

    static final class Serialization {
        private static final Codec<BitSet> PARTS = Codec.LONG_STREAM.comapFlatMap(words -> {
                    long[] encoded = words.limit(ShipDesign.MAX_NODE_WORDS + 1L).toArray();
                    return encoded.length <= ShipDesign.MAX_NODE_WORDS ? DataResult.success(BitSet.valueOf(encoded))
                            : DataResult.error(() -> "Invalid docking stage membership length");
                },
                parts -> LongStream.of(parts.toLongArray()))
                .validate(parts -> parts.length() <= ShipDesign.MAX_MERGED_NODES ? DataResult.success(parts)
                        : DataResult.error(() -> "Invalid docking stage membership"));
        private static final Codec<Identity> IDENTITY = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Identity::id),
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Identity::owner),
                ComponentSerialization.CODEC.optionalFieldOf("name").forGetter(Identity::name)
        ).apply(instance, Identity::new));
        private static final Codec<StageBlock> STAGES = RecordCodecBuilder.create(instance -> instance.group(
                PARTS.fieldOf("parts").forGetter(StageBlock::parts),
                Codec.INT.fieldOf("root").forGetter(StageBlock::root),
                Codec.intRange(1, ShipDesign.MAX_MERGED_STAGES).fieldOf("count").forGetter(StageBlock::count),
                ShipDesign.FLIGHT_STAGE.fieldOf("progress").forGetter(StageBlock::progress),
                IDENTITY.optionalFieldOf("ship").forGetter(StageBlock::identity)
        ).apply(instance, StageBlock::new));
        private static final Codec<PortKey> PORT = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("node").forGetter(PortKey::node),
                Codec.INT.fieldOf("marker").forGetter(PortKey::marker)
        ).apply(instance, PortKey::new));
        static final Codec<ShipDockingState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                STAGES.listOf(0, ShipDesign.MAX_MERGED_STAGES).optionalFieldOf("origins", List.of())
                        .forGetter(ShipDockingState::stages),
                PORT.listOf().xmap(Set::copyOf, List::copyOf)
                        .optionalFieldOf("released", Set.of()).forGetter(ShipDockingState::released)
        ).apply(instance, ShipDockingState::new));

        private Serialization() {}
    }
}
