package io.github.jxt159753.blockspaceprogram.client.surfacecolor;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
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
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
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
    private static final Map<ResourceLocation, Integer> TEXTURES = new HashMap<>();
    private static SurfaceMode mode = SurfaceMode.MAP;
    private static long loginNanos;

    private SurfaceColorMapClient() {}

    private enum SurfaceMode {
        BASELINE, MAP, BLEND
    }

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        var root = Commands.literal("bsp_surface_comparison");
        for (var choice : SurfaceMode.values()) {
            root.then(Commands.literal(choice.name().toLowerCase(Locale.ROOT)).executes(context -> {
                mode = choice;
                return 1;
            }));
        }
        event.getDispatcher().register(root);
    }

    public static float mapBlend(double altitude) {
        return switch (mode) {
            case BASELINE -> 0;
            case MAP -> 1;
            case BLEND -> {
                double fraction = Mth.clamp((altitude - 50_000) / 150_000, 0, 1);
                yield (float) (1 - fraction * fraction * (3 - 2 * fraction));
            }
        };
    }

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        loginNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        TEXTURES.values().forEach(TextureUtil::releaseTextureId);
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

    public static Integer texture(ResourceLocation id) {
        return TEXTURES.get(id);
    }

    private static int upload(BiomeGrid grid) throws IOException {
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
            colors[i] = SurfaceMapPalette.abgr(biome);
        }
        int width = grid.width();
        int height = grid.height();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = colors[grid.index(x, y)];
            }
        }
        var levels = SurfaceMapMipmaps.build(width, height, pixels);

        int target = GL11.GL_TEXTURE_2D;
        int texture = TextureUtil.generateTextureId();
        try {
            GlStateManager._bindTexture(texture);
            for (int level = 0; level < levels.length; level++) {
                GlStateManager._texImage2D(target, level, GL11.GL_RGBA8, width, height, 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
                try (var image = new NativeImage(width, height, false)) {
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            image.setPixelRGBA(x, y, levels[level][y * width + x]);
                        }
                    }
                    image.upload(level, 0, 0, false);
                }
                width = Math.max(1, width / 2);
                height = Math.max(1, height / 2);
            }
            GlStateManager._texParameter(target, GL12.GL_TEXTURE_MAX_LEVEL, levels.length - 1);
            GlStateManager._texParameter(target, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(target, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GlStateManager._texParameter(target, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GlStateManager._texParameter(target, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            return texture;
        } catch (RuntimeException e) {
            TextureUtil.releaseTextureId(texture);
            throw e;
        }
    }
}
