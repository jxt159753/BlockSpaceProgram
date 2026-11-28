package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Snapshot of an engine and its exhaust outlet in unscaled part coordinates. */
public record PartEngine(Outlet outlet, EnginePerformance performance, double electricPower) {
    static final Codec<Double> NON_NEGATIVE = Codec.doubleRange(0, Double.MAX_VALUE);
    private static final Codec<EnginePerformance> PERFORMANCE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            NON_NEGATIVE.fieldOf("thrust").forGetter(EnginePerformance::thrust),
            NON_NEGATIVE.fieldOf("vacuum_specific_impulse").forGetter(EnginePerformance::vacuumSpecificImpulse),
            NON_NEGATIVE.fieldOf("sea_level_specific_impulse").forGetter(EnginePerformance::seaLevelSpecificImpulse)
    ).apply(instance, EnginePerformance::new));

    static final Codec<PartEngine> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Outlet.CODEC.fieldOf("outlet").forGetter(PartEngine::outlet),
            PERFORMANCE_CODEC.fieldOf("performance").forGetter(PartEngine::performance),
            NON_NEGATIVE.optionalFieldOf("electric_power", 0.0).forGetter(PartEngine::electricPower)
    ).apply(instance, PartEngine::new));

    public record Outlet(Vec3 point, Direction exhaust) {
        static final Codec<Outlet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("position").forGetter(Outlet::point),
                Direction.CODEC.fieldOf("face").forGetter(Outlet::exhaust)
        ).apply(instance, Outlet::new));
    }

    static EnginePerformance performance(BlockPartProperties properties) {
        return new EnginePerformance(properties.thrust(), properties.vacuumSpecificImpulse(), properties.seaLevelSpecificImpulse());
    }

    public static List<PartEngine> forPart(BakedPart part) {
        return part.engines().isEmpty() ? legacy(part.geometry(), performance(part.properties())) : part.engines();
    }

    static List<PartEngine> legacy(BakedPart.Geometry geometry, EnginePerformance rating) {
        if (rating.thrust() <= 0 || rating.vacuumSpecificImpulse() <= 0) {
            return List.of();
        }
        var outlets = new ArrayList<Outlet>();
        for (var block : geometry.blocks()) {
            if (block.state().is(PartType.ENGINE_NOZZLES)) {
                Direction exhaust = block.state().getOptionalValue(BlockStateProperties.FACING)
                        .or(() -> block.state().getOptionalValue(BlockStateProperties.HORIZONTAL_FACING)).orElse(Direction.DOWN);
                outlets.add(new Outlet(Vec3.atCenterOf(block.pos()).relative(exhaust, 0.5), exhaust));
            }
        }
        if (outlets.isEmpty()) {
            var bounds = geometry.size();
            outlets.add(new Outlet(new Vec3(bounds.x / 2, 0, bounds.z / 2), Direction.DOWN));
        }
        var share = new EnginePerformance(rating.thrust() / outlets.size(),
                rating.vacuumSpecificImpulse(), rating.seaLevelSpecificImpulse());
        return outlets.stream().map(outlet -> new PartEngine(outlet, share, 0)).toList();
    }
}
