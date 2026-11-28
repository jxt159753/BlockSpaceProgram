package io.github.jxt159753.blockspaceprogram.sky;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SurfaceColorTest {
    private static final SurfaceColor LAND = new SurfaceColor(0x7C8A4F, false, 0);
    private static final SurfaceColor SAND = new SurfaceColor(0xCDB88A, false, 15);
    private static final SurfaceColor OVERRIDE = new SurfaceColor(0xFF0000, false, -100);

    @Test
    public void biomeIdWinsAgainstHigherPriorityTagsInEitherOrder() {
        assertEquals(OVERRIDE.rgb(), SurfaceColor.merge(SAND, false, OVERRIDE, true).rgb());
        assertEquals(OVERRIDE.rgb(), SurfaceColor.merge(OVERRIDE, true, SAND, false).rgb());
    }

    @Test
    public void winningBiomeIdSurvivesSubsequentTagMerges() {
        var merged = SurfaceColor.merge(OVERRIDE, true, SAND, false);
        assertEquals(OVERRIDE.rgb(), SurfaceColor.merge(merged, false, LAND, false).rgb());
    }

    @Test
    public void higherPriorityTagWinsInEitherOrder() {
        assertEquals(SAND, SurfaceColor.merge(LAND, false, SAND, false));
        assertEquals(SAND, SurfaceColor.merge(SAND, false, LAND, false));
    }

    @Test
    public void laterTagWinsAtEqualPriority() {
        var later = new SurfaceColor(0x1F4B78, true, LAND.priority());
        assertEquals(later, SurfaceColor.merge(LAND, false, later, false));
    }

    @Test
    public void laterBiomeIdWinsRegardlessOfPriority() {
        assertEquals(OVERRIDE.rgb(), SurfaceColor.merge(SAND, true, OVERRIDE, true).rgb());
    }

    @Test
    public void codecDefaultsToDryWithZeroPriority() {
        var json = JsonParser.parseString("{\"color\":\"#7c8a4f\"}");
        assertEquals(LAND, SurfaceColor.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    public void codecRequiresSixHexDigitsWithHashPrefix() {
        for (String invalid : new String[] {"7C8A4F", "#ABC", "#007C8A4F", "#7C8A4G"}) {
            var json = JsonParser.parseString("{\"color\":\"" + invalid + "\"}");
            assertTrue(invalid, SurfaceColor.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        }
    }
}
