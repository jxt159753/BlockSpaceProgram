package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import org.joml.Vector3d;

final class ModularEngines {
    private static final Codec<Double> POSITIVE = Codec.doubleRange(Double.MIN_VALUE, Double.MAX_VALUE);
    private static final DataMapType<Block, Chamber> PROFILES = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "nozzle_performance"),
            Registries.BLOCK, Chamber.CODEC).build();

    static void register(RegisterDataMapTypesEvent event) {
        event.register(PROFILES);
    }

    static boolean isComponent(BlockState state) {
        return isSegment(state) || profile(state) != null;
    }

    private static boolean isSegment(BlockState state) {
        return state.getBlock() instanceof NozzleSegmentBlock;
    }

    private static Chamber profile(BlockState state) {
        return state.getBlockHolder().getData(PROFILES);
    }

    static List<PartEngine> bake(List<BakedPart.StructureBlock> blocks) {
        var chambers = new HashMap<BlockPos, Chamber>();
        var segments = new LinkedHashSet<BlockPos>();
        for (var block : blocks) {
            if (isSegment(block.state())) {
                segments.add(block.pos());
            } else {
                Chamber chamber = profile(block.state());
                if (chamber != null) {
                    chambers.put(block.pos(), chamber);
                }
            }
        }

        var engines = new ArrayList<PartEngine>();
        while (!segments.isEmpty()) {
            var pending = new ArrayDeque<BlockPos>();
            pending.add(segments.removeFirst());
            var outlets = new ArrayList<Vector3d>();
            Chamber chamber = null;
            while (!pending.isEmpty()) {
                BlockPos segment = pending.removeFirst();
                outlets.add(new Vector3d(segment.getX() + 0.5, segment.getY(), segment.getZ() + 0.5));
                Chamber adjacent = chambers.get(segment.above());
                if (adjacent != null) {
                    if (chamber != null) {
                        throw new SharedNozzle();
                    }
                    chamber = adjacent;
                }
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbor = segment.relative(side);
                    if (segments.remove(neighbor)) {
                        pending.addLast(neighbor);
                    }
                }
            }
            if (chamber != null) {
                var center = EnginePerformance.outletCenter(outlets);
                var outlet = new PartEngine.Outlet(new Vec3(center.x, center.y, center.z), Direction.DOWN);
                engines.add(new PartEngine(outlet, chamber.rating(outlets.size()), chamber.electricPower()));
            }
        }
        return engines;
    }

    static final class SharedNozzle extends RuntimeException {}

    private record Tier(int segments, double vacuum, double seaLevel) {
        private static final Codec<Tier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, BakedPart.MAX_BLOCKS).fieldOf("segments").forGetter(Tier::segments),
                POSITIVE.fieldOf("vacuum_specific_impulse").forGetter(Tier::vacuum),
                POSITIVE.fieldOf("sea_level_specific_impulse").forGetter(Tier::seaLevel)
        ).apply(instance, Tier::new));
    }

    private record Chamber(double thrust, double electricPower, List<Tier> tiers) {
        private static final Codec<List<Tier>> TIERS = Tier.CODEC.listOf(1, BakedPart.MAX_BLOCKS).validate(tiers -> {
            if (tiers.getFirst().segments() != 1) {
                return DataResult.error(() -> "The first nozzle tier must start at one segment");
            }
            for (int i = 1; i < tiers.size(); i++) {
                if (tiers.get(i).segments() <= tiers.get(i - 1).segments()) {
                    return DataResult.error(() -> "Nozzle tiers must be ordered by increasing segment count");
                }
            }
            return DataResult.success(tiers);
        });

        private static final Codec<Chamber> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                POSITIVE.fieldOf("thrust").forGetter(Chamber::thrust),
                PartEngine.NON_NEGATIVE.optionalFieldOf("electric_power", 0.0).forGetter(Chamber::electricPower),
                TIERS.fieldOf("tiers").forGetter(Chamber::tiers)
        ).apply(instance, Chamber::new));

        EnginePerformance rating(int segments) {
            Tier result = tiers.getFirst();
            for (Tier tier : tiers) {
                if (tier.segments() > segments) {
                    break;
                }
                result = tier;
            }
            return new EnginePerformance(thrust, result.vacuum(), result.seaLevel());
        }
    }
}
