package io.github.jxt159753.blockspaceprogram.sky.surfacemap;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig;
import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapNetworking.Entry;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapNetworking.Fragment;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapNetworking.Manifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
final class SurfaceMapServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static SurfaceMapServer active;

    private final MinecraftServer server;
    private final Path cacheDirectory;
    private final long startedNanos = System.nanoTime();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("BSP Surface Maps").daemon().factory());
    private final Map<ResourceLocation, AvailableMap> maps = new LinkedHashMap<>();
    private final Map<Connection, SurfaceMapTransfer.SendHistory<ResourceLocation>> sent = new HashMap<>();

    private SurfaceMapServer(MinecraftServer server) {
        this.server = server;
        cacheDirectory = server.getWorldPath(LevelResource.ROOT).resolve("blockspaceprogram/surface_maps").normalize();
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        active = new SurfaceMapServer(event.getServer());
        // The system's body order starts with the mother planet.
        for (var body : OrbitalSavedData.get(event.getServer()).system().bodies()) {
            active.queue(body, false);
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) {
            active.worker.shutdownNow();
            active = null;
        }
    }

    private boolean queue(CelestialBody body, boolean regenerate) {
        var id = body.id();
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        var settings = PlanetFlightProperties.appearance(id).surfaceMap();
        if (level == null) {
            return skip(id, "dimension is not loaded");
        }
        if (!settings.enabled()) {
            return skip(id, "appearance.surface_map.enabled is false");
        }
        int width = id.equals(OrbitalSystem.MOTHER_PLANET) ? ShipServerConfig.motherPlanetSurfaceMapWidth() : 512;
        var sampler = SurfaceMapSampler.capture(level, body, width, settings.sampleY());
        if (sampler.source().possibleBiomes().size() == 1) {
            return skip(id, "biome source has only one biome");
        }
        worker.execute(() -> prepare(id, sampler, regenerate));
        return true;
    }

    private boolean skip(ResourceLocation id, String reason) {
        LOGGER.info("Skipped surface map for {}: {}", id, reason);
        if (maps.remove(id) != null) {
            broadcastManifest();
        }
        return false;
    }

    private void prepare(ResourceLocation id, SurfaceMapSampler sampler, boolean regenerate) {
        long start = System.nanoTime();
        try {
            Path namespace = cacheDirectory.resolve(id.getNamespace()).normalize();
            Path file = namespace.resolve(id.getPath() + ".bin").normalize();
            if (!namespace.startsWith(cacheDirectory) || namespace.equals(cacheDirectory) || !file.startsWith(namespace)) {
                throw new IOException("Dimension ID is outside the surface map cache directory");
            }
            var cached = regenerate ? null : validatedCache(id, sampler, file);
            if (cached != null) {
                publish(id, sampler, cached.grid(), cached.encoded(), start, true);
                return;
            }
            var grid = sampler.sample();
            if (grid == null) {
                server.execute(() -> {
                    if (active == this) {
                        skip(id, "sample contains more than " + BiomeGrid.MAX_PALETTE + " biomes");
                    }
                });
                return;
            }
            var encoded = grid.encode();
            if (encoded.compressed().length > SurfaceMapTransfer.MAX_COMPRESSED_BYTES) {
                throw new IOException("Compressed surface map is larger than 3 MiB");
            }
            try {
                SurfaceMapCache.write(file, encoded);
            } catch (IOException e) {
                LOGGER.warn("Could not cache surface map for {}: {}", id, e.getMessage());
            }
            publish(id, sampler, grid, encoded, start, false);
        } catch (CancellationException ignored) {
            // Server shutdown interrupted the worker.
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not prepare surface map for {}", id, e);
        }
    }

    @Nullable
    private static SurfaceMapCache.Cached validatedCache(ResourceLocation id, SurfaceMapSampler sampler, Path file) {
        try {
            var cached = SurfaceMapCache.read(file, sampler.width());
            if (cached != null) {
                if (cached.grid().sampleY() != sampler.sampleY()) {
                    LOGGER.info("Surface map cache for {} uses a different sampling height", id);
                    return null;
                }
                long start = System.nanoTime();
                boolean matches = sampler.matches(cached.grid());
                LOGGER.info("Surface map cache check for {}: matches={} sample_ms={}", id, matches,
                        (System.nanoTime() - start) / 1e6);
                return matches ? cached : null;
            }
        } catch (IOException e) {
            LOGGER.warn("Invalid surface map cache for {}: {}", id, e.getMessage());
        }
        return null;
    }

    private void publish(ResourceLocation id, SurfaceMapSampler sampler, BiomeGrid grid, BiomeGrid.Encoded encoded,
                         long start, boolean cached) throws IOException {
        SurfaceMapSampler.checkInterrupted();
        var fragments = SurfaceMapTransfer.split(encoded.compressed());
        var entry = new Entry(id, grid.width(), encoded.hash(), fragments.size());
        var available = new AvailableMap(entry, grid, sampler.worldWidth(), fragments);
        int compressedBytes = encoded.compressed().length;
        long elapsed = System.nanoTime() - start;
        server.execute(() -> {
            if (active == this) {
                maps.put(id, available);
                broadcastManifest();
                LOGGER.info("Surface map {}: dimension={} grid={}x{} y={} gzip_bytes={} fragments={} hash={} elapsed_ms={} ready_ms={}",
                        cached ? "cache hit" : "generated", id, grid.width(), grid.height(), sampler.sampleY(),
                        compressedBytes, fragments.size(), entry.hash(), elapsed / 1e6,
                        (System.nanoTime() - startedNanos) / 1e6);
            }
        });
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (active != null && event.getEntity() instanceof ServerPlayer player && SurfaceMapNetworking.supported(player)) {
            active.sent.putIfAbsent(player.connection.getConnection(), new SurfaceMapTransfer.SendHistory<>());
            active.sendManifest(player);
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (active != null && event.getEntity() instanceof ServerPlayer player) {
            active.sent.remove(player.connection.getConnection());
        }
    }

    @SubscribeEvent
    public static void reload(OnDatapackSyncEvent event) {
        if (active != null && event.getPlayer() == null) {
            active.broadcastManifest();
        }
    }

    private void broadcastManifest() {
        server.getPlayerList().getPlayers().forEach(this::sendManifest);
    }

    private void sendManifest(ServerPlayer player) {
        if (sent.containsKey(player.connection.getConnection())) {
            var entries = maps.values().stream().map(AvailableMap::entry)
                    .filter(entry -> PlanetFlightProperties.appearance(entry.dimension()).surfaceMap().enabled()).toList();
            PacketDistributor.sendToPlayer(player, new Manifest(entries));
        }
    }

    static void request(ServerPlayer player, Entry requested) {
        if (active == null || !PlanetFlightProperties.appearance(requested.dimension()).surfaceMap().enabled()) {
            return;
        }
        var map = active.maps.get(requested.dimension());
        var history = active.sent.get(player.connection.getConnection());
        if (map != null && map.entry().equals(requested) && history != null
                && history.markSent(requested.dimension(), requested.hash())) {
            var fragments = map.fragments();
            for (int index = 0; index < fragments.size(); index++) {
                PacketDistributor.sendToPlayer(player, new Fragment(requested, index, fragments.get(index)));
            }
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bsp_surface_map").requires(source -> source.hasPermission(2))
                .then(Commands.literal("regenerate")
                        .executes(context -> regenerate(context.getSource(), null))
                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                .executes(context -> regenerate(context.getSource(),
                                        ResourceLocationArgument.getId(context, "dimension")))))
                .then(Commands.literal("cell")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer()).executes(context ->
                                        cell(context.getSource(), IntegerArgumentType.getInteger(context, "x"),
                                                IntegerArgumentType.getInteger(context, "z")))))));
    }

    private static int regenerate(CommandSourceStack source, @Nullable ResourceLocation dimension) {
        var system = OrbitalSavedData.get(source.getServer()).system();
        if (dimension != null && system.findBody(dimension) == null) {
            source.sendFailure(Component.translatable("surface_map.blockspaceprogram.unknown_body", dimension.toString()));
            return 0;
        }
        int queued = 0;
        if (active != null) {
            for (var body : system.bodies()) {
                if ((dimension == null || dimension.equals(body.id())) && active.queue(body, true)) {
                    queued++;
                }
            }
        }
        if (queued == 0) {
            source.sendFailure(Component.translatable("surface_map.blockspaceprogram.no_regeneration"));
        } else {
            int count = queued;
            source.sendSuccess(() -> Component.translatable("surface_map.blockspaceprogram.regenerate", count), false);
        }
        return queued;
    }

    private static int cell(CommandSourceStack source, int x, int z) {
        var dimension = source.getLevel().dimension().location();
        var map = active == null ? null : active.maps.get(dimension);
        if (map == null || !PlanetFlightProperties.appearance(dimension).surfaceMap().enabled()) {
            source.sendFailure(Component.translatable("surface_map.blockspaceprogram.unavailable", dimension.toString()));
            return 0;
        }
        String biome = map.grid().biomeAt(x, z, map.worldWidth());
        source.sendSuccess(() -> Component.translatable("surface_map.blockspaceprogram.cell", x, z, biome), false);
        return 1;
    }

    private record AvailableMap(Entry entry, BiomeGrid grid, double worldWidth, List<byte[]> fragments) {}
}
