package io.github.jxt159753.blockspaceprogram.part;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

/** Data pack definition of a factory-made part in unscaled block coordinates. */
public record PartTemplate(Component name, BlockPos size, List<PartConnector> connectors, List<PartEngine> engines,
                           BlockPartProperties properties, Optional<ResourceLocation> displayModel) {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIRECTORY = "part_template";
    private static final String LARGE_ENGINE_RESOURCE = "/data/blockspaceprogram/part_template/large_engine.json";
    private static volatile Map<ResourceLocation, BakedPart> templates = Map.of();

    static final Codec<PartTemplate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ComponentSerialization.CODEC.fieldOf("name").forGetter(PartTemplate::name),
            BlockPos.CODEC.fieldOf("size").forGetter(PartTemplate::size),
            PartConnector.CODEC.listOf(0, BakedPart.MAX_BLOCKS).fieldOf("connectors").forGetter(PartTemplate::connectors),
            PartEngine.CODEC.listOf(0, BakedPart.MAX_BLOCKS).fieldOf("engines").forGetter(PartTemplate::engines),
            BlockPartProperties.CODEC.fieldOf("properties").forGetter(PartTemplate::properties),
            ResourceLocation.CODEC.optionalFieldOf("display_model").forGetter(PartTemplate::displayModel)
    ).apply(instance, PartTemplate::new));

    public static void register(AddReloadListenerEvent event) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, event.getRegistryAccess());
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), DIRECTORY) {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> json, ResourceManager resources, ProfilerFiller profiler) {
                var loaded = new HashMap<ResourceLocation, BakedPart>();
                json.forEach((id, entry) -> CODEC.parse(ops, entry).flatMap(PartTemplate::bake)
                        .resultOrPartial(error -> LOGGER.error("Invalid part template {}: {}", id, error))
                        .ifPresent(part -> loaded.put(id, part)));
                templates = Map.copyOf(loaded);
            }
        });
    }

    static Optional<BakedPart> find(ResourceLocation id) {
        return Optional.ofNullable(templates.get(id)).flatMap(PartTemplate::atAllowedScale);
    }

    public static ItemStack creativeLargeEngine(HolderLookup.Provider registries) {
        // The creative tab can run on a remote client, where the mod's bundled data is available.
        try (var input = PartTemplate.class.getResourceAsStream(LARGE_ENGINE_RESOURCE)) {
            if (input == null) {
                LOGGER.error("Missing built-in part template {}", LARGE_ENGINE_RESOURCE);
                return ItemStack.EMPTY;
            }
            var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            return CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), JsonParser.parseReader(reader))
                    .flatMap(PartTemplate::bake)
                    .resultOrPartial(error -> LOGGER.error("Invalid built-in part template: {}", error))
                    .flatMap(PartTemplate::atAllowedScale).map(PartTemplate::stack).orElse(ItemStack.EMPTY);
        } catch (IOException | JsonParseException error) {
            LOGGER.error("Could not read built-in part template {}", LARGE_ENGINE_RESOURCE, error);
            return ItemStack.EMPTY;
        }
    }

    private static Optional<BakedPart> atAllowedScale(BakedPart part) {
        return BakedPart.SCALES.stream().filter(scale -> part.connectors().stream().allMatch(c -> c.allowsScale(scale)))
                .findFirst().map(scale -> scale == part.scale() ? part : part.withScale(scale));
    }

    static ItemStack stack(BakedPart part) {
        var stack = new ItemStack(BlockSpaceProgram.BAKED_PART.get());
        stack.set(BlockSpaceProgram.PART_DATA, part);
        return stack;
    }

    DataResult<BakedPart> bake() {
        if (!validSide(size.getX()) || !validSide(size.getY()) || !validSide(size.getZ())) {
            return DataResult.error(() -> "Part template sides must be in [1, " + BakedPart.MAX_SIDE + "]");
        }
        if (displayModel.isPresent() && !displayModel.get().getPath().startsWith("part/")) {
            return DataResult.error(() -> "Part display models must be in models/part/");
        }

        Vec3 max = Vec3.atLowerCornerOf(size);
        for (PartConnector connector : connectors) {
            if (!onFace(connector.modelFace(), connector.normal(), max)
                    || connector.kind().isStack() && connector.width() != connector.height()) {
                return DataResult.error(() -> "Connectors must fit on their face and stack connectors must be square");
            }
        }
        for (PartEngine engine : engines) {
            var outlet = engine.outlet();
            if (!onFace(new AABB(outlet.point(), outlet.point()), outlet.exhaust(), max)) {
                return DataResult.error(() -> "An engine outlet must be on the specified face of the part");
            }
        }

        var rating = EnginePerformance.combine(engines.stream().map(PartEngine::performance).toList());
        double power = engines.stream().mapToDouble(PartEngine::electricPower).sum();
        double mass = properties.mass();
        double factor = mass / 12;
        double xx = factor * (max.y * max.y + max.z * max.z);
        double yy = factor * (max.x * max.x + max.z * max.z);
        double zz = factor * (max.x * max.x + max.y * max.y);
        if (!BakedPart.finite(rating.thrust(), rating.vacuumSpecificImpulse(), rating.seaLevelSpecificImpulse(), power, xx, yy, zz)) {
            return DataResult.error(() -> "The part template's combined properties exceed the supported range");
        }

        var bakedProperties = new BlockPartProperties(mass, properties.fuelCapacity(), rating.thrust(),
                rating.vacuumSpecificImpulse(), rating.seaLevelSpecificImpulse(), BlockPartProperties.DEFAULT_DRAG_COEFFICIENT,
                properties.heatResistance(), properties.electricCapacity(), power, properties.impactResistance());
        var inertia = new BakedPart.InertiaTensor(xx, yy, zz, 0, 0, 0);
        return DataResult.success(new BakedPart(BakedPart.Geometry.filledBox(size), bakedProperties, max.scale(0.5), inertia,
                types(), 1.0F, connectors, engines, displayModel, Optional.of(name)));
    }

    private Set<PartType> types() {
        var types = EnumSet.noneOf(PartType.class);
        if (!engines.isEmpty()) {
            types.add(PartType.ENGINE);
        }
        if (properties.fuelCapacity().values().stream().anyMatch(capacity -> capacity > 0)) {
            types.add(PartType.FUEL_TANK);
        }
        if (connectors.stream().anyMatch(connector -> connector.kind().separable())) {
            types.add(PartType.SEPARATOR);
        }
        return types.isEmpty() ? Set.of(PartType.STRUCTURAL) : types;
    }

    private static boolean validSide(int side) {
        return side >= 1 && side <= BakedPart.MAX_SIDE;
    }

    private static boolean onFace(AABB face, Direction normal, Vec3 max) {
        var axis = normal.getAxis();
        double plane = normal.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max.get(axis) : 0;
        return face.min(axis) == plane && face.max(axis) == plane
                && face.minX >= 0 && face.minY >= 0 && face.minZ >= 0
                && face.maxX <= max.x && face.maxY <= max.y && face.maxZ <= max.z;
    }
}
