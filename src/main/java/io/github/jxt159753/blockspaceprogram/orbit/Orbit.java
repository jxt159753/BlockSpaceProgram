package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/** One leg of a patched trajectory, relative to its current center. */
public record Orbit(ResourceLocation center, OrbitElements elements) {
    public static final Codec<Orbit> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("center").forGetter(Orbit::center),
            OrbitElements.CODEC.fieldOf("elements").forGetter(Orbit::elements)
    ).apply(instance, Orbit::new));
}
