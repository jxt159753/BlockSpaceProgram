package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.orbit.BenchmarkReport.Trial;
import io.github.jxt159753.blockspaceprogram.orbit.BenchmarkScene.Kind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class BenchmarkCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_WARMUP = 12;
    private static final int DEFAULT_SAMPLE = 25;
    private static final int MAX_SECONDS = 300;
    private static final int MAX_SHIPS = 64;
    private static final List<Kind> RENDER = List.of(Kind.EMPTY, Kind.ARMOR_STANDS, Kind.PARTS);
    private static final List<Kind> RENDER_ALL = List.of(Kind.EMPTY, Kind.ARMOR_STANDS, Kind.PARTS,
            Kind.EMPTY, Kind.ARMOR_STANDS, Kind.PARTS, Kind.EMPTY, Kind.ARMOR_STANDS, Kind.PARTS);
    private static final Map<MinecraftServer, Run> ACTIVE = new IdentityHashMap<>();

    private BenchmarkCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var render = Commands.literal("render").then(timed(Commands.literal("all"),
                (context, warmup, sample) -> start(context.getSource(), RENDER_ALL, 0, 0, warmup, sample)));
        for (Kind kind : RENDER) {
            render.then(timed(Commands.literal(kind.id()),
                    (context, warmup, sample) -> start(context.getSource(), List.of(kind), 0, 0, warmup, sample)));
        }
        var parts = Commands.argument("parts_per_ship", IntegerArgumentType.integer(1, ShipDesign.BENCHMARK_MAX_PARTS));
        for (Kind kind : List.of(Kind.LANDED, Kind.DRIFTING)) {
            parts.then(timed(Commands.literal(kind.id()), (context, warmup, sample) ->
                    start(context.getSource(), List.of(kind), argument(context, "ship_count"),
                            argument(context, "parts_per_ship"), warmup, sample)));
        }
        var ships = Commands.literal("ships")
                .then(Commands.argument("ship_count", IntegerArgumentType.integer(1, MAX_SHIPS)).then(parts));
        event.getDispatcher().register(Commands.literal("bsp_bench").requires(source -> source.hasPermission(2))
                .then(render).then(ships).then(Commands.literal("clear").executes(context -> clear(context.getSource()))));
    }

    private static <B extends ArgumentBuilder<CommandSourceStack, B>> B timed(B node, TimedCommand command) {
        return node.executes(context -> command.run(context, DEFAULT_WARMUP, DEFAULT_SAMPLE))
                .then(Commands.argument("warmup_seconds", IntegerArgumentType.integer(0, MAX_SECONDS))
                        .executes(context -> command.run(context, argument(context, "warmup_seconds"), DEFAULT_SAMPLE))
                        .then(Commands.argument("sample_seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
                                .executes(context -> command.run(context, argument(context, "warmup_seconds"),
                                        argument(context, "sample_seconds")))));
    }

    private static int argument(CommandContext<CommandSourceStack> context, String argument) {
        return IntegerArgumentType.getInteger(context, argument);
    }

    private static int start(CommandSourceStack source, List<Kind> sequence, int shipCount, int partsPerShip,
            int warmup, int sample) throws CommandSyntaxException {
        var player = onlinePlayer(source);
        var server = source.getServer();
        if (ACTIVE.containsKey(server)) {
            throw failure("busy");
        }
        if (!BenchmarkNetworking.supported(player)) {
            throw failure("client_missing");
        }
        boolean sable = ModList.get().isLoaded("sable");
        boolean drifting = sequence.getFirst() == Kind.DRIFTING;
        if (drifting && !sable) {
            throw failure("sable_required");
        }
        ServerLevel level = drifting ? server.getLevel(BlockSpaceProgram.ORBITAL_SPACE) : server.overworld();
        if (level == null) {
            throw failure("space_missing");
        }
        if (drifting && !OrbitalRegion.get(server).availableForBenchmark(level)) {
            throw failure("orbital_busy");
        }
        var scene = shipCount == 0 ? BenchmarkScene.render(level)
                : BenchmarkScene.ships(level, sable, drifting, shipCount, partsPerShip);
        var run = new Run(player, scene, sequence, shipCount, partsPerShip, warmup, sample);
        ACTIVE.put(server, run);
        try {
            run.begin();
        } catch (CommandSyntaxException error) {
            finish(run);
            throw error;
        } catch (RuntimeException error) {
            finish(run);
            LOGGER.warn("Could not set up benchmark scene", error);
            throw failure("failed");
        }
        source.sendSuccess(() -> BenchmarkReport.text("started", sequence.size(), warmup, sample), false);
        return 1;
    }

    private static int clear(CommandSourceStack source) throws CommandSyntaxException {
        var player = onlinePlayer(source);
        var run = ACTIVE.get(source.getServer());
        if (run != null) {
            finish(run);
            if (player != run.player) {
                run.player.sendSystemMessage(BenchmarkReport.text("cleared"));
            }
        }
        source.sendSuccess(() -> BenchmarkReport.text("cleared"), false);
        return 1;
    }

    private static ServerPlayer onlinePlayer(CommandSourceStack source) throws CommandSyntaxException {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || source.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !player.connection.isAcceptingMessages()) {
            throw failure("player_required");
        }
        return player;
    }

    static CommandSyntaxException failure(String key, Object... args) {
        return new SimpleCommandExceptionType(BenchmarkReport.text(key, args)).create();
    }

    static void interruptOrbital(ServerLevel space) {
        var run = ACTIVE.get(space.getServer());
        if (run != null && run.enteredOrbit) {
            abort(run, BenchmarkReport.text("orbital_busy"));
        }
    }

    @SubscribeEvent
    public static void startTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var run = ACTIVE.get(player.serverLevel().getServer());
            if (run != null && player == run.player && event.getTarget() == run.camera) {
                run.trackingCamera = true;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ServerTickEvent.Pre event) {
        var run = ACTIVE.get(event.getServer());
        if (run != null && run.trial != null) {
            run.tickStarted = System.nanoTime();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterTick(ServerTickEvent.Post event) {
        long now = System.nanoTime();
        var run = ACTIVE.get(event.getServer());
        if (run != null) {
            try {
                run.tick(now);
            } catch (CommandSyntaxException error) {
                abort(run, ComponentUtils.fromMessage(error.getRawMessage()));
            } catch (IOException error) {
                LOGGER.warn("Could not write server benchmark", error);
                abort(run, BenchmarkReport.text("write_failed", BenchmarkReport.text("server")));
            } catch (RuntimeException error) {
                LOGGER.warn("Server benchmark failed", error);
                abort(run, BenchmarkReport.text("failed"));
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var run = ACTIVE.get(player.serverLevel().getServer());
            if (run != null && run.player == player) {
                finish(run);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void stopping(ServerStoppingEvent event) {
        var run = ACTIVE.get(event.getServer());
        if (run != null) {
            finish(run);
        }
    }

    private static void abort(Run run, Component reason) {
        finish(run);
        run.player.sendSystemMessage(BenchmarkReport.text("aborted", reason));
    }

    private static void finish(Run run) {
        if (ACTIVE.remove(run.scene.level().getServer(), run)) {
            run.close();
        }
    }

    private static boolean move(ServerPlayer player, ServerLevel level, Vec3 position, float yaw, float pitch) {
        if (ModList.get().isLoaded("sable")) {
            return SableShipFlight.movePlayer(player, level, position, Vec3.ZERO, yaw, pitch);
        }
        player.teleportTo(level, position.x(), position.y(), position.z(), yaw, pitch);
        return player.serverLevel() == level;
    }

    @FunctionalInterface
    private interface TimedCommand {
        int run(CommandContext<CommandSourceStack> context, int warmup, int sample) throws CommandSyntaxException;
    }

    private static final class Run {
        private final ServerPlayer player;
        private final BenchmarkScene scene;
        private final List<Kind> sequence;
        private final int shipCount;
        private final int partsPerShip;
        private final int warmupSeconds;
        private final int sampleSeconds;
        private final ServerLevel previousLevel;
        private final Vec3 previousPosition;
        private final float previousYaw;
        private final float previousPitch;
        private final GameType previousMode;
        private final boolean previousFlying;
        private final List<Double> times = new ArrayList<>();
        @Nullable private ArmorStand camera;
        @Nullable private Trial trial;
        private boolean playerChanged;
        private boolean enteredOrbit;
        private boolean trackingCamera;
        private boolean viewing;
        private int index;
        private long cameraSpawned;
        private long warmupStarted;
        private long sampleStarted;
        private long tickStarted;

        private Run(ServerPlayer player, BenchmarkScene scene, List<Kind> sequence, int shipCount, int partsPerShip,
                int warmupSeconds, int sampleSeconds) {
            this.player = player;
            this.scene = scene;
            this.sequence = sequence;
            this.shipCount = shipCount;
            this.partsPerShip = partsPerShip;
            this.warmupSeconds = warmupSeconds;
            this.sampleSeconds = sampleSeconds;
            previousLevel = player.serverLevel();
            previousPosition = ModList.get().isLoaded("sable") ? SableShipFlight.worldPosition(player) : player.position();
            previousYaw = player.getYRot();
            previousPitch = player.getXRot();
            previousMode = player.gameMode.getGameModeForPlayer();
            previousFlying = player.getAbilities().flying;
        }

        private void begin() throws CommandSyntaxException {
            scene.prepareTerrain();
            playerChanged = true;
            player.setCamera(null);
            player.setGameMode(GameType.SPECTATOR);
            if (!player.isSpectator()) {
                throw failure("spectator_required");
            }
            player.stopRiding();
            var level = scene.level();
            var pose = scene.camera();
            if (scene.drifting()) {
                enteredOrbit = OrbitalRegion.get(level.getServer())
                        .enterBenchmark(player, level, pose.position(), pose.yaw(), pose.pitch());
                if (!enteredOrbit) {
                    throw failure("camera_failed");
                }
            } else if (!move(player, level, pose.position(), pose.yaw(), pose.pitch())) {
                throw failure("camera_failed");
            }
            scene.populate(sequence.getFirst());
            camera = scene.cameraEntity();
            level.getChunkAt(camera.blockPosition());
            if (!level.addFreshEntity(camera)) {
                throw failure("camera_failed");
            }
            cameraSpawned = System.nanoTime();
        }

        private void tick(long now) throws CommandSyntaxException, IOException {
            if (!player.isAlive() || !player.isSpectator() || player.serverLevel() != scene.level()
                    || camera == null || camera.isRemoved()) {
                throw failure("camera_failed");
            }
            if (enteredOrbit && scene.level().players().stream().anyMatch(other -> other != player)) {
                throw failure("orbital_busy");
            }
            scene.checkObjects();
            if (!trackingCamera) {
                if (now - cameraSpawned > TimeUnit.SECONDS.toNanos(15)) {
                    throw failure("camera_failed");
                }
                return;
            }
            if (player.getCamera() != camera) {
                player.setCamera(camera);
            }
            if (!viewing) {
                BenchmarkNetworking.view(player, camera.getId(), scene.camera());
                viewing = true;
                warmupStarted = System.nanoTime();
                return;
            }
            if (trial == null) {
                if (now - warmupStarted >= TimeUnit.SECONDS.toNanos(warmupSeconds)) {
                    startSample();
                }
                return;
            }
            times.add((now - tickStarted) / 1_000_000.0);
            if (now - sampleStarted >= TimeUnit.SECONDS.toNanos(sampleSeconds)) {
                finishSample(now);
            }
        }

        private void startSample() {
            var kind = sequence.get(index);
            boolean ships = shipCount != 0;
            String tier = ships ? shipCount + "x" + partsPerShip + "_" + kind.id() : kind.id();
            trial = Trial.create(ships ? "ships" : "render", tier, index / RENDER.size() + 1,
                    ships ? shipCount : kind == Kind.EMPTY ? 0 : BenchmarkScene.RENDER_OBJECTS, partsPerShip,
                    ships ? kind.id() : null, ships ? BenchmarkScene.SHIP_LAYOUT : null,
                    scene.level().dimension().location().toString(), scene.origin(), scene.camera(), warmupSeconds, sampleSeconds,
                    scene.level().getServer().isDedicatedServer());
            sampleStarted = System.nanoTime();
            BenchmarkNetworking.start(player, trial);
        }

        private void finishSample(long now) throws IOException, CommandSyntaxException {
            var completed = trial;
            BenchmarkNetworking.stop(player, completed, true);
            trial = null;
            player.sendSystemMessage(BenchmarkReport.write(completed, false, times,
                    (now - sampleStarted) / 1_000_000_000.0, null));
            if (++index == sequence.size()) {
                finish(this);
                return;
            }
            times.clear();
            scene.clearObjects();
            scene.populate(sequence.get(index));
            warmupStarted = System.nanoTime();
        }

        private void close() {
            if (player.connection.isAcceptingMessages()) {
                if (trial != null) {
                    BenchmarkNetworking.stop(player, trial, false);
                }
                if (viewing) {
                    BenchmarkNetworking.releaseView(player);
                }
            }
            try {
                scene.close();
            } finally {
                if (enteredOrbit) {
                    OrbitalRegion.get(scene.level().getServer()).endBenchmark();
                }
                if (playerChanged) {
                    player.setCamera(null);
                    move(player, previousLevel, previousPosition, previousYaw, previousPitch);
                    player.setGameMode(previousMode);
                    player.getAbilities().flying = previousFlying;
                    player.onUpdateAbilities();
                }
                if (camera != null) {
                    camera.discard();
                }
            }
        }
    }
}
