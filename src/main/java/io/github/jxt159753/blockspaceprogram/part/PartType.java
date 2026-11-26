package io.github.jxt159753.blockspaceprogram.part;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public enum PartType implements StringRepresentable {
    STRUCTURAL,
    ENGINE,
    COMMAND_POD,
    FUEL_TANK,
    SEPARATOR,
    LANDING_LEG;

    static final StringRepresentable.EnumCodec<PartType> CODEC = StringRepresentable.fromEnum(PartType::values);

    public static final TagKey<Block> ENGINE_NOZZLES = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "engine_nozzles"));
    public static final TagKey<Block> COMMAND_SEATS = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "command_seats"));
    private static final TagKey<Block> LANDING_LEGS = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "landing_legs"));

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Classifies the scanned structure on the logical server. A part can serve multiple functions. */
    public static Set<PartType> classify(Iterable<BlockState> blocks) {
        Set<PartType> types = EnumSet.noneOf(PartType.class);
        for (BlockState block : blocks) {
            if (block.isAir()) {
                continue;
            }
            if (block.is(ENGINE_NOZZLES)) {
                types.add(ENGINE);
            }
            if (block.is(COMMAND_SEATS)) {
                types.add(COMMAND_POD);
            }
            if (block.is(LANDING_LEGS)) {
                types.add(LANDING_LEG);
            }
            if (block.getBlock() instanceof PartConnectorBlock marker && marker.kind().separable()) {
                types.add(SEPARATOR);
            }
            if (BlockPartProperties.get(block).fuelCapacity().values().stream().anyMatch(capacity -> capacity > 0)) {
                types.add(FUEL_TANK);
            }
        }
        return types.isEmpty() ? Set.of(STRUCTURAL) : Set.copyOf(types);
    }
}
