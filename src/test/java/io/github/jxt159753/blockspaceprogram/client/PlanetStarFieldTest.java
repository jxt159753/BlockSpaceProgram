package io.github.jxt159753.blockspaceprogram.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.TreeMap;
import org.junit.Test;

public class PlanetStarFieldTest {
    @Test
    public void seedReproducesTheWholeField() {
        var stars = PlanetStarField.generate(PlanetStarField.SEED);
        assertEquals(stars, PlanetStarField.generate(PlanetStarField.SEED));
        assertNotEquals(stars, PlanetStarField.generate(PlanetStarField.SEED + 1));
    }

    @Test
    public void fieldHasFiveMagnitudeTiersWithMoreFaintStars() {
        var stars = PlanetStarField.generate(PlanetStarField.SEED);
        assertEquals(7500, stars.size());

        var counts = new TreeMap<Float, Integer>();
        for (var star : stars) {
            counts.merge(star.brightness(), 1, Integer::sum);
        }
        assertEquals(5, counts.size());
        assertTrue(counts.lastKey() >= 4 * counts.firstKey());
        int previous = stars.size();
        for (int count : counts.values()) {
            assertTrue(count < previous);
            previous = count;
        }
    }
}
