package io.github.jxt159753.blockspaceprogram.assembly;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShipServerConfigTest {
    @Test
    public void fillsMissingStarSystemSettings() {
        var config = CommentedConfig.inMemory();
        var starSystem = config.createSubConfig();
        starSystem.set("seed", "18");
        config.set("star_system", starSystem);

        ShipServerConfig.SPEC.correct(config);

        assertEquals("medium", config.get("star_system.orbit_spacing"));
        assertEquals("regular", config.get("star_system.orbit_shape"));
        assertEquals("18", config.get("star_system.seed"));
        assertTrue(ShipServerConfig.SPEC.isCorrect(config));
    }
}
