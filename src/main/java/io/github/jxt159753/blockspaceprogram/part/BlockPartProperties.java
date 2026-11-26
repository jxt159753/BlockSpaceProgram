package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** The contribution of one block to a part's baked properties. */
public record BlockPartProperties(
        double mass,
        Map<ResourceLocation, Double> fuelCapacity,
        double thrust,
        double vacuumSpecificImpulse,
        double seaLevelSpecificImpulse,
        double dragCoefficient,
        double heatResistance,
        double electricCapacity,
        double impactResistance) {

    private static final double DEFAULT_MASS = 1.0;
    private static final double DEFAULT_IMPACT_RESISTANCE = 8.0;
    private static final BlockPartProperties DEFAULT = new BlockPartProperties(
            DEFAULT_MASS, Map.of(), 0, 0, 0, 0, 0, 0, DEFAULT_IMPACT_RESISTANCE);
    private static final BlockPartProperties AIR = new BlockPartProperties(0, Map.of(), 0, 0, 0, 0, 0, 0, 0);
    private static final Codec<Double> NON_NEGATIVE = Codec.doubleRange(0, Double.MAX_VALUE);
    static final Codec<BlockPartProperties> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            NON_NEGATIVE.optionalFieldOf("mass", DEFAULT_MASS).forGetter(BlockPartProperties::mass),
            Codec.unboundedMap(ResourceLocation.CODEC, NON_NEGATIVE)
                    .optionalFieldOf("fuel_capacity", Map.of()).forGetter(BlockPartProperties::fuelCapacity),
            NON_NEGATIVE.optionalFieldOf("thrust", 0.0).forGetter(BlockPartProperties::thrust),
            NON_NEGATIVE.optionalFieldOf("vacuum_specific_impulse", 0.0).forGetter(BlockPartProperties::vacuumSpecificImpulse),
            NON_NEGATIVE.optionalFieldOf("sea_level_specific_impulse", 0.0).forGetter(BlockPartProperties::seaLevelSpecificImpulse),
            NON_NEGATIVE.optionalFieldOf("drag_coefficient", 0.0).forGetter(BlockPartProperties::dragCoefficient),
            NON_NEGATIVE.optionalFieldOf("heat_resistance", 0.0).forGetter(BlockPartProperties::heatResistance),
            NON_NEGATIVE.optionalFieldOf("electric_capacity", 0.0).forGetter(BlockPartProperties::electricCapacity),
            NON_NEGATIVE.optionalFieldOf("impact_resistance", DEFAULT_IMPACT_RESISTANCE)
                    .forGetter(BlockPartProperties::impactResistance)
    ).apply(instance, BlockPartProperties::new));

    private static final DataMapType<Block, BlockPartProperties> DATA_MAP = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "part_properties"),
            Registries.BLOCK,
            CODEC
    ).build();

    public BlockPartProperties {
        fuelCapacity = Map.copyOf(fuelCapacity);
    }

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(DATA_MAP);
    }

    /** Looks up the current data pack value on the logical server. */
    public static BlockPartProperties get(BlockState state) {
        if (state.isAir()) {
            return AIR;
        }
        return Objects.requireNonNullElse(state.getBlockHolder().getData(DATA_MAP), DEFAULT);
    }
}
