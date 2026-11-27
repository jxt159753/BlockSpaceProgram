package io.github.jxt159753.blockspaceprogram.orbit;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import oshi.SystemInfo;

public final class BenchmarkReport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    private static final String MESSAGE = "benchmark.blockspaceprogram.";
    private static final Pattern REPORT_ID = Pattern.compile("[a-zA-Z0-9_.-]{1,150}");

    private BenchmarkReport() {}

    public static Component text(String key, Object... args) {
        return Component.translatable(MESSAGE + key, args);
    }

    public static Component write(Trial trial, boolean client, List<Double> samples, double elapsedSeconds,
            @Nullable JsonObject settings) throws IOException {
        // The client receives the report ID from the server. Keep it a single filename component.
        if (!REPORT_ID.matcher(trial.id()).matches()) {
            throw new IOException("Invalid benchmark report ID");
        }
        var summary = samples.isEmpty() ? null : BenchmarkStatistics.summarize(samples);
        String side = client ? "client" : "server";
        var report = new Data(trial, side, client ? "frame_ms" : "mspt", elapsedSeconds, samples.size(),
                summary == null ? null : summary.medianMs(), summary == null ? null : summary.p95Ms(),
                samples, environment(), settings);
        Path directory = FMLPaths.GAMEDIR.get().resolve(BlockSpaceProgram.MOD_ID).resolve("benchmarks");
        Files.createDirectories(directory);
        Path file = directory.resolve(trial.id() + "_" + side + ".json").toAbsolutePath().normalize();
        try (var writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            GSON.toJson(report, writer);
        } catch (JsonIOException error) {
            if (error.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw error;
        }
        return summary == null ? text("no_samples", text(side), file.toString())
                : text("result", text(side), format(summary.medianMs()), format(summary.p95Ms()), file.toString());
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static Environment environment() {
        var mods = new TreeMap<String, String>();
        ModList.get().getMods().forEach(mod -> mods.put(mod.getModId(), mod.getVersion().toString()));
        return new Environment(SharedConstants.getCurrentVersion().getName(), mods.get("neoforge"),
                mods, System.getProperty("java.version"), System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("os.arch"), cpuModel(),
                Runtime.getRuntime().maxMemory());
    }

    private static String cpuModel() {
        try {
            return new SystemInfo().getHardware().getProcessor().getProcessorIdentifier().getName();
        } catch (RuntimeException | LinkageError error) {
            return "unavailable";
        }
    }

    public record Camera(Vec3 position, float yaw, float pitch) {}

    public record Trial(String id, String startedUtc, String scene, String tier, int round, int objectCount,
            int partsPerShip, @Nullable String flightState, @Nullable String partLayout, String dimension,
            Vec3 origin, Camera camera, int warmupSeconds, int sampleSeconds, boolean dedicatedServer) {
        static Trial create(String scene, String tier, int round, int objectCount, int partsPerShip,
                @Nullable String flightState, @Nullable String partLayout, String dimension, Vec3 origin,
                Camera camera, int warmupSeconds, int sampleSeconds, boolean dedicatedServer) {
            String started = Instant.now().toString();
            String id = started.replace(':', '-') + "_" + scene + "_" + tier + "_r" + round + "_" + UUID.randomUUID();
            return new Trial(id, started, scene, tier, round, objectCount, partsPerShip, flightState,
                    partLayout, dimension, origin, camera, warmupSeconds, sampleSeconds, dedicatedServer);
        }
    }

    private record Environment(String minecraft, String neoforge, TreeMap<String, String> mods, String java,
            String os, String osVersion, String osArch, String cpu, long maxHeapBytes) {}

    private record Data(Trial trial, String side, String metric, double elapsedSeconds, int sampleCount,
            @Nullable Double medianMs, @Nullable Double p95Ms, List<Double> samplesMs, Environment environment,
            @Nullable JsonObject clientSettings) {}
}
