package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** A baked parachute centered on its block in unscaled model coordinates. */
public record PartParachute(Vec3 center, ParachutePerformance performance) {
    private static final Codec<Double> POSITIVE = Codec.doubleRange(Double.MIN_VALUE, Double.MAX_VALUE);
    private static final Codec<ParachutePerformance> PERFORMANCE = RecordCodecBuilder.<ParachutePerformance>create(instance -> instance.group(
            POSITIVE.fieldOf("semi_drag_area").forGetter(ParachutePerformance::semiDragArea),
            POSITIVE.fieldOf("drag_area").forGetter(ParachutePerformance::dragArea),
            Codec.doubleRange(0, 1).fieldOf("min_relative_density").forGetter(ParachutePerformance::minRelativeDensity),
            POSITIVE.fieldOf("max_deploy_speed").forGetter(ParachutePerformance::maxDeploySpeed),
            Codec.doubleRange(0, Double.MAX_VALUE).fieldOf("full_deploy_height").forGetter(ParachutePerformance::fullDeployHeight),
            POSITIVE.fieldOf("opening_time").forGetter(ParachutePerformance::openingTime)
    ).apply(instance, ParachutePerformance::new)).validate(performance -> performance.semiDragArea() <= performance.dragArea()
            ? DataResult.success(performance) : DataResult.error(() -> "semi_drag_area cannot exceed drag_area"));
    private static final DataMapType<Block, ParachutePerformance> PROFILES = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "parachute_performance"),
            Registries.BLOCK, PERFORMANCE).build();

    static final Codec<PartParachute> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Vec3.CODEC.fieldOf("center").forGetter(PartParachute::center),
            PERFORMANCE.fieldOf("performance").forGetter(PartParachute::performance)
    ).apply(instance, PartParachute::new));
    static final StreamCodec<FriendlyByteBuf, PartParachute> STREAM_CODEC = StreamCodec.of((buffer, parachute) -> {
        buffer.writeVec3(parachute.center());
        var performance = parachute.performance();
        buffer.writeDouble(performance.semiDragArea());
        buffer.writeDouble(performance.dragArea());
        buffer.writeDouble(performance.minRelativeDensity());
        buffer.writeDouble(performance.maxDeploySpeed());
        buffer.writeDouble(performance.fullDeployHeight());
        buffer.writeDouble(performance.openingTime());
    }, buffer -> new PartParachute(buffer.readVec3(), new ParachutePerformance(buffer.readDouble(), buffer.readDouble(),
            buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble())));

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(PROFILES);
    }

    static List<PartParachute> bake(List<BakedPart.StructureBlock> blocks) {
        var parachutes = new ArrayList<PartParachute>();
        for (var block : blocks) {
            var performance = block.state().getBlockHolder().getData(PROFILES);
            if (performance != null) {
                parachutes.add(new PartParachute(Vec3.atCenterOf(block.pos()), performance));
            }
        }
        return parachutes;
    }
}
