package io.github.jxt159753.blockspaceprogram.experiment.propellant;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.Cell;
import io.github.jxt159753.blockspaceprogram.experiment.propellant.PropellantBurn.Engine;
import io.github.jxt159753.blockspaceprogram.experiment.propellant.PropellantBurn.Supply;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Measures applied thrust from the native body's velocity change under a centered impulse. */
public final class SablePropellantTrial implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double TICK_SECONDS = 0.05;
    private static final PropellantBurn BURN = new PropellantBurn(new Engine(20_000, 250),
            new Engine(4_000, 2_000), 50_000_000, true);
    private static final BlockPos ORIGIN = new BlockPos(8, 88, 24);
    private final ShipCollisionBody body;
    private final Cell cell;
    private Supply supply = new Supply(1_000, 100, 11_500_000);
    private int ticks;

    public SablePropellantTrial(ServerLevel level) {
        level.getChunkAt(ORIGIN);
        cell = new Cell(BlockPos.ZERO, new BlockPos(4, 4, 4), supply.dryMass() + supply.fuel());
        body = ShipCollisionBody.spawn(level, "propellant-electric-test", Map.of(BlockPos.ZERO, cell),
                new Vector3d(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ()), new Quaterniond());
        body.container().addForceLoadTicket(body.subLevel(), SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
    }

    public boolean tick() {
        var step = BURN.advance(supply, TICK_SECONDS);
        var subLevel = body.subLevel();
        var pipeline = body.pipeline();
        // Measure against the native mass from this physics tick. The consumed fuel changes the next tick's mass.
        double mass = subLevel.getMassTracker().getMass();
        double before = pipeline.getLinearVelocity(subLevel, new Vector3d()).y();
        pipeline.applyLinearAndAngularImpulse(subLevel, new Vector3d(0, step.impulse(), 0), new Vector3d(), true);
        double after = pipeline.getLinearVelocity(subLevel, new Vector3d()).y();
        double thrust = (after - before) * mass / TICK_SECONDS;
        body.changeCellMass(BlockPos.ZERO, cell, step.remaining().fuel() - supply.fuel());
        subLevel.updateMergedMassData(0);
        supply = step.remaining();

        ticks++;
        if (ticks >= 4) {
            double expected = switch (ticks) {
                case 4 -> 24_000;
                case 5 -> 22_400;
                default -> 20_000;
            };
            LOGGER.info("PROPELLANT SABLE tick={} thrustN={} electricSeconds={} remainingJ={} remainingKg={}",
                    ticks, thrust, step.electricSeconds(), supply.energy(), supply.fuel());
            if (!(Math.abs(thrust - expected) < 2 && supply.fuel() > 0
                    && supply.energy() == (ticks == 4 ? 1_500_000 : 0))) {
                throw new IllegalStateException("Unexpected Sable thrust or propellant at tick " + ticks);
            }
        }
        return ticks == 6;
    }

    @Override
    public void close() {
        body.remove();
    }
}
