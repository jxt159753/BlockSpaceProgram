package io.github.jxt159753.blockspaceprogram.part;

import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Rated thrust and specific impulses, in newtons and seconds. */
public record EnginePerformance(double thrust, double vacuumSpecificImpulse, double seaLevelSpecificImpulse) {
    /** Combines rated thrust and mass flows for the part tooltip. */
    public static EnginePerformance combine(List<EnginePerformance> engines) {
        if (engines.size() == 1) {
            return engines.getFirst();
        }
        double thrust = 0;
        double vacuumFlow = 0;
        double seaLevelFlow = 0;
        for (EnginePerformance engine : engines) {
            if (engine.thrust > 0) {
                thrust += engine.thrust;
                vacuumFlow += engine.thrust / engine.vacuumSpecificImpulse;
                seaLevelFlow += engine.thrust / engine.seaLevelSpecificImpulse;
            }
        }
        return new EnginePerformance(thrust, vacuumFlow == 0 ? 0 : thrust / vacuumFlow,
                seaLevelFlow == 0 ? 0 : thrust / seaLevelFlow);
    }

    static Vector3d outletCenter(List<? extends Vector3dc> segmentOutlets) {
        var total = new Vector3d();
        segmentOutlets.forEach(total::add);
        return total.div(segmentOutlets.size());
    }
}
