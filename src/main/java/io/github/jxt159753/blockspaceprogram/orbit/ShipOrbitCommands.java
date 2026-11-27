package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class ShipOrbitCommands {
    private static final DynamicCommandExceptionType INVALID =
            new DynamicCommandExceptionType(message -> Component.literal("Ship placement failed: " + message));

    private ShipOrbitCommands() {}

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bsp_star_system")
                .requires(source -> source.hasPermission(2) || source.getEntity() instanceof ServerPlayer player
                        && source.getServer().isSingleplayerOwner(player.getGameProfile()))
                .executes(context -> showSystem(context.getSource())));
        if (!ModList.get().isLoaded("sable")) {
            return;
        }
        var position = Commands.argument("position", Vec3Argument.vec3(false))
                .then(Commands.argument("vx", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("vy", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("vz", DoubleArgumentType.doubleArg())
                                        .executes(ShipOrbitCommands::position))));
        var angles = Commands.argument("inclination", DoubleArgumentType.doubleArg(0, 180))
                .then(Commands.argument("ascending_node", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("periapsis", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("mean_anomaly", DoubleArgumentType.doubleArg())
                                        .executes(ShipOrbitCommands::orbit))));
        var body = Commands.argument("body", ResourceLocationArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                        OrbitalSavedData.get(context.getSource().getServer()).system().bodies().stream()
                                .map(CelestialBody::id), builder))
                .then(Commands.argument("p", DoubleArgumentType.doubleArg(Double.MIN_VALUE))
                        .then(Commands.argument("e", DoubleArgumentType.doubleArg(0)).then(angles)));
        event.getDispatcher().register(Commands.literal("bsp_ship").requires(source -> source.hasPermission(2))
                .then(Commands.literal("position").then(shipArgument().then(position)))
                .then(Commands.literal("orbit").then(shipArgument().then(body))));
    }

    private static int showSystem(CommandSourceStack source) {
        var data = OrbitalSavedData.get(source.getServer());
        var profile = data.starSystem();
        var orbit = profile.moonOrbit();
        source.sendSuccess(() -> Component.translatable("orbit.blockspaceprogram.star_system.profile",
                profile.spacing().id(), profile.shape().id(), Long.toString(profile.seed())), false);
        source.sendSuccess(() -> Component.translatable("orbit.blockspaceprogram.star_system.elements",
                orbit.semiMajorAxis(), orbit.semiLatusRectum(), orbit.eccentricity(), orbit.inclination(),
                orbit.longitudeOfAscendingNode(), orbit.argumentOfPeriapsis(), orbit.meanAnomalyAtEpoch(),
                orbit.epochSeconds()), false);
        source.sendSuccess(() -> Component.translatable("orbit.blockspaceprogram.star_system.transfer",
                String.format(Locale.ROOT, "%.2f", profile.hohmannSeconds(data.system()) / 60)), false);
        return 1;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, UUID> shipArgument() {
        return Commands.argument("ship", UuidArgument.uuid()).suggests((context, builder) -> {
            var source = context.getSource();
            var physical = source.getLevel().getEntities(BlockSpaceProgram.ASSEMBLED_SHIP.get(),
                    ship -> ship.activeFlight() != null);
            var orbital = OrbitalSavedData.get(source.getServer()).ships();
            return SharedSuggestionProvider.suggest(Stream.concat(physical.stream().map(Entity::getUUID),
                    orbital.stream().map(OrbitingShip::id)).map(UUID::toString), builder);
        });
    }

    private static int orbit(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var ship = UuidArgument.getUuid(context, "ship");
        var body = ResourceLocationArgument.getId(context, "body");
        double epoch = OrbitalSavedData.get(source.getServer()).orbitSeconds();
        try {
            var elements = new OrbitElements(number(context, "p"), number(context, "e"),
                    angle(context, "inclination"), angle(context, "ascending_node"),
                    angle(context, "periapsis"), angle(context, "mean_anomaly"), epoch);
            ShipOrbitHandoff.placeInOrbit(source.getLevel(), ship, new Orbit(body, elements));
        } catch (IllegalArgumentException error) {
            throw INVALID.create(error.getMessage());
        }
        source.sendSuccess(() -> Component.literal("Placed " + ship + " on an orbit around " + body), false);
        return 1;
    }

    private static int position(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var ship = UuidArgument.getUuid(context, "ship");
        var position = Vec3Argument.getVec3(context, "position");
        var velocity = new Vec3(number(context, "vx"), number(context, "vy"), number(context, "vz"));
        try {
            ShipOrbitHandoff.placeInDimension(source.getLevel(), ship, position, velocity);
        } catch (IllegalArgumentException error) {
            throw INVALID.create(error.getMessage());
        }
        source.sendSuccess(() -> Component.literal("Placed " + ship + " at " + position + " with velocity " + velocity), false);
        return 1;
    }

    private static double number(CommandContext<CommandSourceStack> context, String name) {
        return DoubleArgumentType.getDouble(context, name);
    }

    private static double angle(CommandContext<CommandSourceStack> context, String name) {
        return Math.toRadians(number(context, name));
    }
}
