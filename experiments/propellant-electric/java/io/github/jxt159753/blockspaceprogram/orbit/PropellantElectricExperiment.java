package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.experiment.propellant.PropellantBurn;
import io.github.jxt159753.blockspaceprogram.experiment.propellant.PropellantBurn.Engine;
import io.github.jxt159753.blockspaceprogram.experiment.propellant.PropellantBurn.Supply;
import io.github.jxt159753.blockspaceprogram.experiment.propellant.SablePropellantTrial;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class PropellantElectricExperiment {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static PropellantElectricExperiment active;

    private final OrbitalTrial normal = new OrbitalTrial(1);
    private final OrbitalTrial accelerated = new OrbitalTrial(100);
    private SablePropellantTrial flight;

    private PropellantElectricExperiment(ServerLevel space) {
        flight = new SablePropellantTrial(space);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bsp_propellant_experiment")
                .requires(source -> source.getEntity() == null && source.hasPermission(2))
                .executes(context -> start(context.getSource())));
    }

    private static int start(CommandSourceStack source) {
        if (active != null) {
            LOGGER.warn("PROPELLANT experiment is already running");
            return 0;
        }
        var space = source.getServer().getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        if (!ModList.get().isLoaded("sable") || space == null) {
            LOGGER.warn("PROPELLANT experiment requires Sable and the orbital dimension");
            return 0;
        }
        active = new PropellantElectricExperiment(space);
        return 1;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (active == null) {
            return;
        }
        try {
            if (active.flight != null && active.flight.tick()) {
                active.flight.close();
                active.flight = null;
            }
            var registries = event.getServer().registryAccess();
            active.normal.tick(registries);
            active.accelerated.tick(registries);
            if (active.normal.outcome != null && active.accelerated.outcome != null) {
                if (!active.normal.outcome.equals(active.accelerated.outcome)) {
                    throw new IllegalStateException("Orbital clocks produced different burn or cutoff states");
                }
                LOGGER.info("PROPELLANT PASS Sable cutoff and matching orbital outcomes");
                finish();
            }
        } catch (RuntimeException failure) {
            LOGGER.error("PROPELLANT FAIL", failure);
            finish();
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        finish();
    }

    private static void finish() {
        if (active != null && active.flight != null) {
            active.flight.close();
        }
        active = null;
    }

    private static final class OrbitalTrial {
        private static final ManeuverNode TARGET = new ManeuverNode(2.025, new Vec3(5.952883494175813, 0, 0));
        private static final double BURN_SECONDS = 120.23;
        private static final double FULL_ENERGY = 36_069_000;
        private static final PropellantBurn BURN = new PropellantBurn(Engine.OFF, new Engine(20, 2_000), 300_000, true);

        // Use a private clock for the trial so the dedicated server can exercise warp steps without changing its world.
        private final OrbitalSavedData data = new OrbitalSavedData(StarSystem.LEGACY);
        private final int rate;
        private Orbit orbit = new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(120_000, 0, 0, 0, 0, 0, 0));
        private Supply supply = new Supply(400, 4, FULL_ENERGY);
        private double firingSeconds;
        private double deliveredDeltaV;
        private Outcome outcome;

        OrbitalTrial(int rate) {
            this.rate = rate;
        }

        void tick(HolderLookup.Provider registries) {
            if (outcome != null) {
                return;
            }
            long from = data.orbitTicks();
            data.advanceTicks(registries, rate);
            // Both clocks apply impulses on the same simulation-time grid, including partial ignition and cutoff steps.
            for (long tick = from; tick < data.orbitTicks(); tick++) {
                double start = Math.max(TARGET.seconds(), tick / (double) OrbitalSavedData.TICKS_PER_SECOND);
                double end = (tick + 1) / (double) OrbitalSavedData.TICKS_PER_SECOND;
                if (end <= start) {
                    continue;
                }
                var step = BURN.advance(supply, end - start);
                supply = step.remaining();
                firingSeconds += step.electricSeconds();
                deliveredDeltaV += step.deltaV();
                double impulseTime = start + step.seconds() / 2;
                var impulse = new ManeuverNode(impulseTime, TARGET.deltaV().normalize().scale(step.deltaV()));
                orbit = impulse.preview(data.system(), orbit, orbit.elements().epochSeconds());
                if (supply.energy() == 0) {
                    double cutoff = start + step.seconds();
                    outcome = new Outcome(firingSeconds, deliveredDeltaV, cutoff, supply,
                            data.system().relativeState(orbit, cutoff));
                    verify();
                    return;
                }
            }
        }

        private void verify() {
            if (!(Math.abs(firingSeconds - BURN_SECONDS) < 1e-8
                    && Math.abs(outcome.cutoff() - TARGET.seconds() - BURN_SECONDS) < 1e-8
                    && Math.abs(deliveredDeltaV - TARGET.deltaV().length()) < 1e-8 && supply.fuel() > 0)) {
                throw new IllegalStateException("Unexpected orbital burn " + outcome);
            }
            LOGGER.info("PROPELLANT ORBIT rate={} serverTicks={} start={} cutoff={} firingSeconds={} deltaVMps={} "
                            + "initialJ={} remainingJ={} remainingKg={} cutoffState={}",
                    rate, data.orbitTicks() / rate, TARGET.seconds(), outcome.cutoff(), firingSeconds, deliveredDeltaV,
                    FULL_ENERGY, supply.energy(), supply.fuel(), outcome.state());
        }
    }

    private record Outcome(double seconds, double deltaV, double cutoff, Supply remaining, OrbitalState state) {}
}
