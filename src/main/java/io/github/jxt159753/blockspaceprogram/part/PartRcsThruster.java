package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import org.jetbrains.annotations.Nullable;

/** A single thrust outlet with ratings fixed when the part was created. */
public record PartRcsThruster(PartEngine.Outlet outlet, Performance performance) {
    public record Performance(double thrust, double isp, double electricPower) {
        private static final Codec<Performance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                PartEngine.POSITIVE.fieldOf("thrust").forGetter(Performance::thrust),
                PartEngine.POSITIVE.fieldOf("isp").forGetter(Performance::isp),
                PartEngine.NON_NEGATIVE.optionalFieldOf("electric_power", 0.0).forGetter(Performance::electricPower)
        ).apply(instance, Performance::new));
    }

    private static final DataMapType<Block, Performance> PROFILES = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "rcs_performance"),
            Registries.BLOCK, Performance.CODEC).build();
    static final Codec<PartRcsThruster> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PartEngine.Outlet.CODEC.fieldOf("outlet").forGetter(PartRcsThruster::outlet),
            Performance.CODEC.fieldOf("performance").forGetter(PartRcsThruster::performance)
    ).apply(instance, PartRcsThruster::new));
    static final StreamCodec<FriendlyByteBuf, PartRcsThruster> STREAM_CODEC = StreamCodec.of((buffer, thruster) -> {
        PartEngine.Outlet.STREAM_CODEC.encode(buffer, thruster.outlet);
        buffer.writeDouble(thruster.performance.thrust);
        buffer.writeDouble(thruster.performance.isp);
        buffer.writeDouble(thruster.performance.electricPower);
    }, buffer -> new PartRcsThruster(PartEngine.Outlet.STREAM_CODEC.decode(buffer),
            new Performance(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())));

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(PROFILES);
    }

    @Nullable
    static Performance lookup(BlockState state) {
        return state.getBlockHolder().getData(PROFILES);
    }

    static List<PartRcsThruster> bake(List<BakedPart.StructureBlock> blocks, Function<BlockState, Performance> lookup) {
        var thrusters = new ArrayList<PartRcsThruster>();
        for (var block : blocks) {
            var performance = lookup.apply(block.state());
            if (performance != null) {
                var outlet = PartEngine.Outlet.at(block.pos(), PartEngine.exhaustDirection(block.state()));
                thrusters.add(new PartRcsThruster(outlet, performance));
            }
        }
        return thrusters;
    }
}
