package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import org.joml.Vector3d;

public final class ModularEngines {
    private static final DataMapType<Block, Chamber> PROFILES = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "engine_performance"),
            Registries.BLOCK, Chamber.CODEC).build();

    private ModularEngines() {}

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(PROFILES);
    }

    static boolean isChamber(BlockState state) {
        return state.getBlock() instanceof CombustionChamberBlock || state.getBlockHolder().getData(PROFILES) != null;
    }

    static List<PartEngine> bake(List<BakedPart.StructureBlock> blocks) {
        var segments = new HashMap<BlockPos, Direction>();
        var chambers = new HashMap<BlockPos, BlockState>();
        for (var block : blocks) {
            BlockState state = block.state();
            if (state.getBlock() instanceof NozzleSegmentBlock) {
                segments.put(block.pos(), state.getValue(BlockStateProperties.FACING));
            } else if (isChamber(state)) {
                chambers.put(block.pos(), state);
            }
        }

        var engines = new ArrayList<PartEngine>();
        var visited = new HashSet<BlockPos>();
        for (var block : blocks) {
            Direction facing = segments.get(block.pos());
            if (facing == null || !visited.add(block.pos())) {
                continue;
            }
            var pending = new ArrayDeque<BlockPos>();
            var outlets = new ArrayList<Vector3d>();
            BlockState chamber = null;
            pending.add(block.pos());
            while (!pending.isEmpty()) {
                BlockPos segment = pending.removeFirst();
                Vec3 point = Vec3.atCenterOf(segment).relative(facing, 0.5);
                outlets.add(new Vector3d(point.x, point.y, point.z));
                BlockState adjacent = chambers.get(segment.relative(facing.getOpposite()));
                if (adjacent != null && PartEngine.exhaustDirection(adjacent) == facing) {
                    if (chamber != null) {
                        throw new SharedNozzle();
                    }
                    chamber = adjacent;
                }
                for (Direction side : NozzleSegmentBlock.sides(facing)) {
                    BlockPos neighbor = segment.relative(side);
                    if (segments.get(neighbor) == facing && visited.add(neighbor)) {
                        pending.addLast(neighbor);
                    }
                }
            }
            Chamber profile = chamber == null ? null : chamber.getBlockHolder().getData(PROFILES);
            if (profile != null) {
                var center = EnginePerformance.outletCenter(outlets);
                var outlet = new PartEngine.Outlet(new Vec3(center.x, center.y, center.z), facing);
                engines.add(new PartEngine(outlet, profile.rating(outlets.size()), profile.electricPower()));
            }
        }
        return engines;
    }

    static final class SharedNozzle extends RuntimeException {}

    private record Tier(int segments, double vacuum, double seaLevel) {
        private static final Codec<Tier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, BakedPart.MAX_BLOCKS).fieldOf("segments").forGetter(Tier::segments),
                PartEngine.POSITIVE.fieldOf("vacuum_specific_impulse").forGetter(Tier::vacuum),
                PartEngine.POSITIVE.fieldOf("sea_level_specific_impulse").forGetter(Tier::seaLevel)
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
                PartEngine.POSITIVE.fieldOf("thrust").forGetter(Chamber::thrust),
                PartEngine.NON_NEGATIVE.optionalFieldOf("electric_power", 0.0).forGetter(Chamber::electricPower),
                TIERS.fieldOf("tiers").forGetter(Chamber::tiers)
        ).apply(instance, Chamber::new));

        EnginePerformance rating(int segments) {
            Tier selected = tiers.getFirst();
            for (Tier tier : tiers) {
                if (tier.segments() > segments) {
                    break;
                }
                selected = tier;
            }
            return new EnginePerformance(thrust, selected.vacuum(), selected.seaLevel());
        }
    }
}
