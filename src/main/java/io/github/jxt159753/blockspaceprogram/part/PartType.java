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
    FUEL_TANK;

    static final StringRepresentable.EnumCodec<PartType> CODEC = StringRepresentable.fromEnum(PartType::values);

    private static final TagKey<Block> ENGINE_NOZZLES = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "engine_nozzles"));
    private static final TagKey<Block> COMMAND_SEATS = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "command_seats"));

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
            if (BlockPartProperties.get(block).fuelCapacity().values().stream().anyMatch(capacity -> capacity > 0)) {
                types.add(FUEL_TANK);
            }
        }
        return types.isEmpty() ? Set.of(STRUCTURAL) : Set.copyOf(types);
    }
}
