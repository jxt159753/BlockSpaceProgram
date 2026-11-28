package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Baked engine rating and outlet in unscaled part coordinates. */
public record PartEngine(Outlet outlet, EnginePerformance performance, double electricPower) {
    static final Codec<Double> POSITIVE = Codec.doubleRange(Double.MIN_VALUE, Double.MAX_VALUE);
    static final Codec<Double> NON_NEGATIVE = Codec.doubleRange(0, Double.MAX_VALUE);
    private static final Codec<EnginePerformance> PERFORMANCE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            POSITIVE.fieldOf("thrust").forGetter(EnginePerformance::thrust),
            POSITIVE.fieldOf("vacuum_specific_impulse").forGetter(EnginePerformance::vacuumSpecificImpulse),
            NON_NEGATIVE.fieldOf("sea_level_specific_impulse").forGetter(EnginePerformance::seaLevelSpecificImpulse)
    ).apply(instance, EnginePerformance::new));

    public static final Codec<PartEngine> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Outlet.CODEC.fieldOf("outlet").forGetter(PartEngine::outlet),
            PERFORMANCE_CODEC.fieldOf("performance").forGetter(PartEngine::performance),
            NON_NEGATIVE.optionalFieldOf("electric_power", 0.0).forGetter(PartEngine::electricPower)
    ).apply(instance, PartEngine::new));

    static final StreamCodec<FriendlyByteBuf, PartEngine> STREAM_CODEC = StreamCodec.of((buffer, engine) -> {
        buffer.writeVec3(engine.outlet.point);
        buffer.writeEnum(engine.outlet.exhaust);
        buffer.writeDouble(engine.performance.thrust());
        buffer.writeDouble(engine.performance.vacuumSpecificImpulse());
        buffer.writeDouble(engine.performance.seaLevelSpecificImpulse());
        buffer.writeDouble(engine.electricPower);
    }, buffer -> new PartEngine(new Outlet(buffer.readVec3(), buffer.readEnum(Direction.class)),
            new EnginePerformance(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readDouble()));

    public record Outlet(Vec3 point, Direction exhaust) {
        private static final Codec<Outlet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("position").forGetter(Outlet::point),
                Direction.CODEC.fieldOf("face").forGetter(Outlet::exhaust)
        ).apply(instance, Outlet::new));
    }

    static Direction exhaustDirection(BlockState state) {
        return state.getOptionalValue(BlockStateProperties.FACING)
                .or(() -> state.getOptionalValue(BlockStateProperties.HORIZONTAL_FACING)).orElse(Direction.DOWN);
    }

    static List<PartEngine> legacy(BakedPart.Geometry geometry, EnginePerformance rating, double electricPower,
            boolean keepUnmarkedEngine) {
        if (rating.thrust() <= 0 || rating.vacuumSpecificImpulse() <= 0) {
            return List.of();
        }
        var outlets = new ArrayList<Outlet>();
        for (var block : geometry.blocks()) {
            if (block.state().is(PartType.ENGINE_NOZZLES) && !(block.state().getBlock() instanceof NozzleSegmentBlock)) {
                Direction exhaust = exhaustDirection(block.state());
                outlets.add(new Outlet(Vec3.atCenterOf(block.pos()).relative(exhaust, 0.5), exhaust));
            }
        }
        if (outlets.isEmpty() && keepUnmarkedEngine) {
            var size = geometry.size();
            outlets.add(new Outlet(new Vec3(size.x / 2, 0, size.z / 2), Direction.DOWN));
        }
        if (outlets.isEmpty()) {
            return List.of();
        }
        var share = new EnginePerformance(rating.thrust() / outlets.size(),
                rating.vacuumSpecificImpulse(), rating.seaLevelSpecificImpulse());
        double draw = electricPower / outlets.size();
        return outlets.stream().map(outlet -> new PartEngine(outlet, share, draw)).toList();
    }
}
