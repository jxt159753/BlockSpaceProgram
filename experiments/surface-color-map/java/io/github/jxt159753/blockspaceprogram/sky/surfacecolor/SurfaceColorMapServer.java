package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking.Entry;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking.Manifest;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking.MapData;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

@EventBusSubscriber(modid = SurfaceColorMapExperiment.MOD_ID)
final class SurfaceColorMapServer {
    static final Logger LOGGER = LogUtils.getLogger();
    static final int MOON_WIDTH = 512;
    private static SurfaceColorMapServer active;
    private final MinecraftServer server;
    private final int sampleY = Integer.getInteger("bsp.surfaceColorMap.y", 256);
    private final int motherWidth = Integer.getInteger("bsp.surfaceColorMap.width", 2048);
    private final Map<ResourceLocation, BiomeGridSampler> samplers = new LinkedHashMap<>();
    private final Map<ResourceLocation, AvailableMap> maps = new LinkedHashMap<>();
    private final Map<Connection, Set<ResourceLocation>> sent = new HashMap<>();
    private final SurfaceColorMapBenchmark benchmark;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private boolean benchmarking;

    private SurfaceColorMapServer(MinecraftServer server) {
        this.server = server;
        if (!BiomeGrid.supportedWidth(motherWidth)) {
            throw new IllegalArgumentException("surfaceColorMapWidth must be 512, 1024 or 2048");
        }
        for (var body : OrbitalSavedData.get(server).system().bodies()) {
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, body.id()));
            if (level != null) {
                samplers.put(body.id(), BiomeGridSampler.capture(level, body));
            }
        }
        benchmark = new SurfaceColorMapBenchmark(server, samplers, sampleY, workers);
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        var session = new SurfaceColorMapServer(event.getServer());
        active = session;
        session.workers.execute(session::generate);
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && active.server == event.getServer()) {
            active.workers.shutdownNow();
            active = null;
        }
    }

    private void generate() {
        for (var sampler : samplers.values()) {
            int width = sampler.dimension().equals(OrbitalSystem.MOTHER_PLANET) ? motherWidth : MOON_WIDTH;
            try {
                var sample = sampler.sample(width, sampleY);
                LOGGER.info("Surface map generated: dimension={} grid={}x{} y={} sample_ms={} total_ms={} "
                                + "palette={} caves={} raw_bytes={} gzip_bytes={} hash={}",
                        sampler.dimension(), width, width / 2, sampleY, sample.sampleNanos() / 1e6,
                        sample.totalNanos() / 1e6, sample.grid().palette().size(), sample.caves(),
                        sample.encoded().rawBytes(), sample.encoded().compressed().length, sample.encoded().hash());
                server.execute(() -> publish(sampler.dimension(), sample.grid(), sample.encoded()));
            } catch (CancellationException ignored) {
                return;
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Surface map sampling failed for {}", sampler.dimension(), e);
            }
        }
    }

    private void publish(ResourceLocation dimension, BiomeGrid grid, BiomeGrid.Encoded encoded) {
        if (active != this) {
            return;
        }
        var entry = new Entry(dimension, grid.width(), encoded.hash());
        maps.put(dimension, new AvailableMap(grid, new MapData(entry, encoded.compressed())));
        server.getPlayerList().getPlayers().forEach(this::sendManifest);
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (active != null && event.getEntity() instanceof ServerPlayer player) {
            active.sendManifest(player);
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (active != null && event.getEntity() instanceof ServerPlayer player) {
            active.sent.remove(player.connection.getConnection());
        }
    }

    private void sendManifest(ServerPlayer player) {
        if (SurfaceColorMapNetworking.supportsMaps(player)) {
            sent.computeIfAbsent(player.connection.getConnection(), connection -> new HashSet<>());
            var entries = maps.values().stream().map(map -> map.payload().entry()).toList();
            PacketDistributor.sendToPlayer(player, new Manifest(entries));
        }
    }

    static void request(ServerPlayer player, Entry requested) {
        if (active == null) {
            return;
        }
        var available = active.maps.get(requested.dimension());
        var alreadySent = active.sent.get(player.connection.getConnection());
        if (available != null && available.payload().entry().equals(requested) && alreadySent != null
                && alreadySent.add(requested.dimension())) {
            PacketDistributor.sendToPlayer(player, available.payload());
            LOGGER.info("Surface map sent: player={} dimension={} gzip_bytes={}", player.getGameProfile().getName(),
                    requested.dimension(), available.payload().compressed().length);
        }
    }

    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post event) {
        if (active != null) {
            active.benchmark.tick();
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bsp_surface_color_map")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("benchmark").executes(context -> active.startBenchmark()))
                .then(Commands.literal("split").executes(context -> active.splitProbe(context.getSource().getPlayerOrException())))
                .then(Commands.literal("cell")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer()).executes(context -> {
                                    int x = IntegerArgumentType.getInteger(context, "x");
                                    int z = IntegerArgumentType.getInteger(context, "z");
                                    var id = context.getSource().getLevel().dimension().location();
                                    var map = active.maps.get(id);
                                    if (map == null) {
                                        return 0;
                                    }
                                    String biome = map.grid().biomeAt(x, z, active.samplers.get(id).worldWidth());
                                    context.getSource().sendSuccess(() -> Component.literal(biome), false);
                                    return 1;
                                })))));
    }

    private int startBenchmark() {
        if (benchmarking) {
            return 0;
        }
        benchmarking = true;
        workers.execute(() -> {
            try {
                benchmark.run();
            } catch (InterruptedException | CancellationException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Surface map benchmark failed", e);
            } finally {
                server.execute(() -> benchmarking = false);
            }
        });
        return 1;
    }

    private int splitProbe(ServerPlayer player) {
        var id = SurfaceColorMapNetworking.SPLIT_PROBE;
        if (!SurfaceColorMapNetworking.supportsMaps(player)) {
            return 0;
        }
        workers.execute(() -> {
            int width = 2048;
            var palette = IntStream.range(0, BiomeGrid.MAX_PALETTE).mapToObj(i -> id + "_" + i).toList();
            byte[] cells = new byte[width * (width / 2)];
            new Random(89).nextBytes(cells);
            var grid = new BiomeGrid(width, palette, cells);
            try {
                var encoded = grid.encode();
                server.execute(() -> {
                    if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
                        LOGGER.info("Surface map split probe: raw_bytes={} gzip_bytes={} hash={}",
                                encoded.rawBytes(), encoded.compressed().length, encoded.hash());
                        PacketDistributor.sendToPlayer(player, new MapData(
                                new Entry(id, width, encoded.hash()), encoded.compressed()));
                    }
                });
            } catch (IOException e) {
                LOGGER.error("Surface map split probe failed", e);
            }
        });
        return 1;
    }

    private record AvailableMap(BiomeGrid grid, MapData payload) {}
}
