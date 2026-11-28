package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.BiomeGrid;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapNetworking.Entry;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapNetworking.Fragment;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapNetworking.Manifest;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapNetworking.Request;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapTransfer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class SurfaceMapClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    // Keep completed hashes for the connection, including maps withdrawn by a newer manifest.
    private static final Map<Entry, Download> DOWNLOADS = new HashMap<>();
    private static Map<ResourceLocation, Entry> manifest = Map.of();
    private static long loginNanos;

    private SurfaceMapClient() {}

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        loginNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        for (var download : DOWNLOADS.values()) {
            if (download.completed != null) {
                TextureUtil.releaseTextureId(download.completed.texture());
            }
        }
        DOWNLOADS.clear();
        manifest = Map.of();
    }

    public static void receiveManifest(Manifest payload) {
        var previous = manifest;
        var next = new HashMap<ResourceLocation, Entry>();
        for (var entry : payload.entries()) {
            if (BiomeGrid.supportedWidth(entry.width()) && entry.hash().length() == 64) {
                next.put(entry.dimension(), entry);
            }
        }
        manifest = next;
        for (var former : previous.values()) {
            if (!former.equals(next.get(former.dimension()))) {
                DOWNLOADS.get(former).assembly = null;
            }
        }
        for (var entry : next.values()) {
            var download = DOWNLOADS.computeIfAbsent(entry, unused -> new Download());
            if (!entry.equals(previous.get(entry.dimension())) && download.completed == null && !download.decoding) {
                download.assembly = new SurfaceMapTransfer.Assembler();
                PacketDistributor.sendToServer(new Request(entry));
            }
        }
    }

    public static void receiveFragment(Fragment fragment) {
        var entry = manifest.get(fragment.entry().dimension());
        var download = DOWNLOADS.get(entry);
        if (download == null || download.assembly == null) {
            return;
        }
        var assembly = download.assembly;
        try {
            if (!entry.equals(fragment.entry())) {
                throw new IOException("Fragment metadata differs from the requested manifest entry");
            }
            if (!assembly.add(fragment.index(), entry.fragmentCount(), fragment.data())) {
                return;
            }
        } catch (IOException e) {
            download.assembly = null;
            LOGGER.warn("Rejected surface map for {}: {}", entry.dimension(), e.getMessage());
            return;
        }
        download.assembly = null;
        var game = Minecraft.getInstance();
        var connection = game.getConnection();
        if (connection != null) {
            var biomes = connection.registryAccess().registryOrThrow(Registries.BIOME);
            download.decoding = true;
            Util.backgroundExecutor().execute(() -> decode(game, biomes, entry, download, assembly));
        }
    }

    @Nullable
    static Integer texture(ResourceLocation dimension) {
        var download = DOWNLOADS.get(manifest.get(dimension));
        return download == null || download.completed == null ? null : download.completed.texture();
    }

    private static void decode(Minecraft game, Registry<Biome> biomes, Entry entry, Download download,
                               SurfaceMapTransfer.Assembler assembly) {
        try {
            var grid = BiomeGrid.decode(entry.width(), entry.hash(), assembly.assemble());
            var image = colorGrid(grid, biomes);
            game.execute(() -> {
                try (image) {
                    if (DOWNLOADS.get(entry) == download) {
                        download.decoding = false;
                        download.completed = new Completed(grid, upload(image));
                        LOGGER.info("Surface map uploaded: dimension={} grid={}x{} hash={} login_ms={}",
                                entry.dimension(), grid.width(), grid.height(), entry.hash(),
                                (System.nanoTime() - loginNanos) / 1e6);
                    }
                }
            });
        } catch (IOException | RuntimeException e) {
            game.execute(() -> download.decoding = false);
            LOGGER.warn("Could not decode surface map for {}: {}", entry.dimension(), e.getMessage());
        }
    }

    private static NativeImage colorGrid(BiomeGrid grid, Registry<Biome> biomes) throws IOException {
        var colors = new int[grid.palette().size()];
        for (int index = 0; index < colors.length; index++) {
            String name = grid.palette().get(index);
            var id = ResourceLocation.tryParse(name);
            if (id == null) {
                throw new IOException("Invalid biome ID " + name);
            }
            var biome = biomes.getHolder(ResourceKey.create(Registries.BIOME, id))
                    .orElseThrow(() -> new IOException("Biome missing from client registry: " + id));
            var value = biome.value();
            boolean water = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
            int rgb = water ? value.getWaterColor()
                    : value.hasPrecipitation() && value.getBaseTemperature() < 0.15F ? 0xf2f7fa
                    : value.getGrassColor(0, 0);
            // NativeImage stores ABGR. The alpha byte is the shader's water mask.
            colors[index] = (water ? 0xff000000 : 0) | (rgb & 0xff) << 16 | (rgb & 0xff00) | (rgb >>> 16 & 0xff);
        }
        var image = new NativeImage(grid.width(), grid.height(), false);
        for (int row = 0; row < grid.height(); row++) {
            for (int column = 0; column < grid.width(); column++) {
                image.setPixelRGBA(column, row, colors[grid.index(column, row)]);
            }
        }
        return image;
    }

    private static int upload(NativeImage image) {
        int id = TextureUtil.generateTextureId();
        int target = GL11.GL_TEXTURE_2D;
        try {
            TextureUtil.prepareImage(id, image.getWidth(), image.getHeight());
            image.upload(0, 0, 0, false);
            GlStateManager._texParameter(target, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(target, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(target, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GlStateManager._texParameter(target, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            return id;
        } catch (RuntimeException e) {
            TextureUtil.releaseTextureId(id);
            throw e;
        }
    }

    private static final class Download {
        private SurfaceMapTransfer.Assembler assembly;
        private boolean decoding;
        private Completed completed;
    }

    private record Completed(BiomeGrid grid, int texture) {}
}
