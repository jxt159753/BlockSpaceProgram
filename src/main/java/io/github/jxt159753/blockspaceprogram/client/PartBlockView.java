package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/** The captured block states supply neighbors and model data during tessellation. */
final class PartBlockView implements BlockAndTintGetter, LightChunkGetter {
    private static final Biome TINT_BIOME = new Biome.BiomeBuilder()
            .temperature(0.8F).downfall(0.4F)
            .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).skyColor(0)
                    .waterColor(0x3F76E4).waterFogColor(0).build())
            .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build();

    private final Map<BlockPos, BlockState> states = new HashMap<>();
    private final LevelLightEngine lightEngine = new LevelLightEngine(this, false, false);
    private final int height;

    PartBlockView(BakedPart.Geometry geometry) {
        geometry.blocks().forEach(block -> states.put(block.pos(), block.state()));
        height = (int) geometry.size().y;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }

    @Override
    public float getShade(Direction direction, boolean directionalShade) {
        return 1.0F;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return resolver.getColor(TINT_BIOME, pos.getX(), pos.getZ());
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return lightEngine;
    }

    @Override
    @Nullable
    public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public BlockGetter getLevel() {
        return this;
    }
}
