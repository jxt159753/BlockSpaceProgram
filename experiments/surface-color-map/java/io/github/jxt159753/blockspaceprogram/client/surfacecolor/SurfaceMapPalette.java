package io.github.jxt159753.blockspaceprogram.client.surfacecolor;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.neoforge.common.Tags;

/** Temporary category colors and glint mask for the surface comparison. */
final class SurfaceMapPalette {
    private SurfaceMapPalette() {}

    static int abgr(Holder<Biome> biome) {
        int argb = argb(biome);
        return (argb & 0xff00ff00) | (argb & 0xff) << 16 | (argb >>> 16 & 0xff);
    }

    private static int argb(Holder<Biome> biome) {
        if (biome.is(Tags.Biomes.IS_SNOWY) || biome.is(Tags.Biomes.IS_ICY) || biome.is(Tags.Biomes.IS_AQUATIC_ICY)) {
            return 0xe8eef2;
        }
        if (biome.is(BiomeTags.IS_DEEP_OCEAN)) {
            return 0xff13325a;
        }
        if (biome.is(BiomeTags.IS_OCEAN)) {
            return 0xff1f4b78;
        }
        if (biome.is(Tags.Biomes.IS_BADLANDS)) {
            return 0xa5653f;
        }
        if (biome.is(Tags.Biomes.IS_STONY_SHORES) || biome.is(Biomes.STONY_PEAKS)) {
            return 0x86817a;
        }
        if (biome.is(Tags.Biomes.IS_SANDY)) {
            return 0xcdb88a;
        }
        if (biome.is(BiomeTags.IS_FOREST)) {
            return 0x3f5b2f;
        }
        if (biome.is(BiomeTags.IS_JUNGLE)) {
            return 0x2f5a26;
        }
        if (biome.is(BiomeTags.IS_TAIGA)) {
            return 0x34483a;
        }
        if (biome.is(BiomeTags.IS_SAVANNA)) {
            return 0x9a8d55;
        }
        if (biome.is(Tags.Biomes.IS_SWAMP)) {
            return 0x3d4a30;
        }
        if (biome.is(Tags.Biomes.IS_MUSHROOM)) {
            return 0x8a7a86;
        }
        return 0x7c8a4f;
    }
}
