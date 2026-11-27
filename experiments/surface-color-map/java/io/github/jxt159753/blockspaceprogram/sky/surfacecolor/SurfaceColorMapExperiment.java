package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(SurfaceColorMapExperiment.MOD_ID)
public final class SurfaceColorMapExperiment {
    public static final String MOD_ID = "bsp_surface_color_map";

    public SurfaceColorMapExperiment(IEventBus modBus) {
        modBus.addListener(SurfaceColorMapNetworking::register);
    }
}
