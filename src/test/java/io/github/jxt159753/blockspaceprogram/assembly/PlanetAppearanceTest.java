package io.github.jxt159753.blockspaceprogram.assembly;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties.Appearance;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties.Profile;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PlanetAppearanceTest {
    @Test
    public void readsPartialAppearanceAndLeavesOtherValuesAtTheirDefaults() {
        var appearance = profile("""
                {"surface_map": {"enabled": false, "blend_start": 20000, "blend_end": 40000},
                 "cloud_opacity": 0.3}
                """).appearance();
        assertFalse(appearance.surfaceMap().enabled());
        assertEquals(20_000, appearance.surfaceMap().blendStart(), 0);
        assertEquals(40_000, appearance.surfaceMap().blendEnd(), 0);
        assertEquals(256, appearance.surfaceMap().sampleY());
        assertEquals(0.3F, appearance.cloudOpacity(), 0);
        assertEquals(Appearance.DEFAULT.moonSurface(), appearance.moonSurface());
    }

    @Test
    public void discardsInvalidAppearanceWithoutChangingFlightAndHandoff() {
        for (String invalid : new String[] {
                "true",
                "{\"moon_surface\": {\"mare_color\": [2, 0, 0]}, \"cloud_opacity\": 0.3}",
                "{\"moon_surface\": {\"mare_color\": [0, 0]}}",
                "{\"water_glint_strength\": -1}",
                "{\"surface_map\": {\"blend_start\": 200000}}"}) {
            var profile = profile(invalid);
            assertEquals(Appearance.DEFAULT, profile.appearance());
            assertEquals(60_000, profile.flight().radius(), 0);
            assertEquals(6_000, profile.handoff().orElseThrow().ascent(), 0);
        }
    }

    private static Profile profile(String appearance) {
        var json = JsonParser.parseString("""
                {"sea_level": 63, "radius": 60000, "surface_gravity": 9.8, "sea_level_density": 1,
                 "atmosphere_top": 4000, "scale_height": 500,
                 "orbit_handoff": {"ascent_altitude": 6000, "descent_altitude": 5000}}
                """).getAsJsonObject();
        json.add("appearance", JsonParser.parseString(appearance));
        return Profile.codec(ResourceLocation.withDefaultNamespace("overworld")).parse(JsonOps.INSTANCE, json).getOrThrow();
    }
}
