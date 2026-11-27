package io.github.jxt159753.blockspaceprogram.facility;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FacilityLimitTest {
    @Test
    public void tierUsesItsOneBasedLimit() {
        var limits = List.of(27, 729);
        var first = FacilityLimit.resolve(limits, 1);
        assertEquals(new FacilityLimit(1, 27), first);
        assertFalse(first.exceededBy(27));
        assertTrue(first.exceededBy(28));
        assertEquals(new FacilityLimit(2, 729), FacilityLimit.resolve(limits, 2));
    }

    @Test
    public void tierBeyondTheListUsesTheLastLimit() {
        assertEquals(new FacilityLimit(2, 100.5), FacilityLimit.resolve(List.of(0, 100.5), 8));
    }

    @Test
    public void zeroDisablesTheLimit() {
        var limit = FacilityLimit.resolve(List.of(100, 0), 2);
        assertFalse(limit.exceededBy(120));
    }
}
