package io.github.jxt159753.blockspaceprogram.client.surfacecolor;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.BiomeGrid;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapExperiment;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking.Entry;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking.Manifest;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking.MapData;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking.Request;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;

@EventBusSubscriber(modid = SurfaceColorMapExperiment.MOD_ID, value = Dist.CLIENT)
public final class SurfaceColorMapClient {
    public static final boolean COMPARE_TERRAIN = Boolean.getBoolean("bsp.surfaceColorMap.compareTerrain");
    public static final int COMPARISON_DISTANCE = 4096;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, Entry> REQUESTED = new HashMap<>();
    private static final Map<ResourceLocation, DynamicTexture> TEXTURES = new HashMap<>();
    private static long loginNanos;

    private SurfaceColorMapClient() {}

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        loginNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        TEXTURES.values().forEach(DynamicTexture::close);
        TEXTURES.clear();
        REQUESTED.clear();
    }

    @SubscribeEvent
    public static void compareTerrainFog(ViewportEvent.RenderFog event) {
        if (COMPARE_TERRAIN && event.getMode() == FogRenderer.FogMode.FOG_TERRAIN
                && event.getCamera().getPosition().y >= 3000) {
            event.setNearPlaneDistance(COMPARISON_DISTANCE - 1);
            event.setFarPlaneDistance(COMPARISON_DISTANCE);
            event.setCanceled(true);
        }
    }

    public static void receiveManifest(Manifest manifest) {
        LOGGER.info("Surface map manifest: entries={} login_ms={}", manifest.entries().size(),
                (System.nanoTime() - loginNanos) / 1e6);
        for (var entry : manifest.entries()) {
            if (REQUESTED.putIfAbsent(entry.dimension(), entry) == null) {
                PacketDistributor.sendToServer(new Request(entry));
            }
        }
    }

    public static void receiveMap(MapData payload) {
        var entry = payload.entry();
        boolean probe = SurfaceColorMapNetworking.SPLIT_PROBE.equals(entry.dimension());
        if (!probe && (!entry.equals(REQUESTED.get(entry.dimension())) || TEXTURES.containsKey(entry.dimension()))) {
            return;
        }
        double receiveMillis = (System.nanoTime() - loginNanos) / 1e6;
        boolean uploaded = false;
        try {
            var grid = BiomeGrid.decode(entry.width(), entry.hash(), payload.compressed());
            if (!probe && grid.palette().size() > 1) {
                TEXTURES.put(entry.dimension(), upload(grid));
                uploaded = true;
            }
        } catch (IOException e) {
            LOGGER.warn("Rejected surface map for {}", entry.dimension(), e);
            return;
        }
        LOGGER.info("Surface map received: dimension={} width={} gzip_bytes={} hash={} login_ms={} ready_ms={} texture={}",
                entry.dimension(), entry.width(), payload.compressed().length, entry.hash(), receiveMillis,
                (System.nanoTime() - loginNanos) / 1e6, uploaded);
    }

    public static DynamicTexture texture(ResourceLocation id) {
        return TEXTURES.get(id);
    }

    private static DynamicTexture upload(BiomeGrid grid) throws IOException {
        var registry = Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BIOME);
        int[] colors = new int[grid.palette().size()];
        for (int i = 0; i < colors.length; i++) {
            String id = grid.palette().get(i);
            var biomeId = ResourceLocation.tryParse(id);
            if (biomeId == null) {
                throw new IOException("Invalid biome ID " + id);
            }
            var biome = registry.getHolder(ResourceKey.create(Registries.BIOME, biomeId))
                    .orElseThrow(() -> new IOException("Biome missing from client registry: " + id));
            int rgb = colorOf(biome);
            // NativeImage expects ABGR.
            colors[i] = 0xff000000 | (rgb & 0xff) << 16 | (rgb & 0xff00) | (rgb >>> 16 & 0xff);
        }
        var image = new NativeImage(grid.width(), grid.height(), false);
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                image.setPixelRGBA(x, y, colors[grid.index(x, y)]);
            }
        }
        var texture = new DynamicTexture(image);
        texture.bind();
        texture.setFilter(false, false);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static int colorOf(Holder<Biome> biome) {
        var value = biome.value();
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
            return value.getWaterColor();
        }
        if (value.hasPrecipitation() && value.getBaseTemperature() < 0.15F) {
            return 0xf2f7fa;
        }
        return value.getGrassColor(0, 0);
    }
}
