package io.github.jxt159753.blockspaceprogram.webpreview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import io.github.jxt159753.blockspaceprogram.webpreview.WebPreviewFeed.Clock;
import io.github.jxt159753.blockspaceprogram.webpreview.WebPreviewFeed.Position;
import io.github.jxt159753.blockspaceprogram.webpreview.WebPreviewFeed.Ship;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class WebPreviewFeedTest {
    @Test
    public void newReadersReceiveCurrentTimeWithoutAnUnchangedDelta() {
        var feed = new WebPreviewFeed(List.of(), Map.of(), new Clock(2, 1));
        var original = feed.connect().frame();
        feed.publish(new Clock(3, 1), null);
        var connected = feed.connect();
        assertEquals(new Clock(3, 1), connected.clock());
        assertSame(original, connected.frame());

        feed.publish(new Clock(10, 5), null);
        var accelerated = feed.connect().frame();
        assertNotSame(original, accelerated);
        assertEquals(new Clock(10, 5), accelerated.since(original).clock());
        assertEquals(Map.of(), accelerated.since(original).updated());
    }

    @Test
    public void deltaUsesTheReadersLastSnapshotAfterSkippedChanges() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var ship = new Ship("minecraft:overworld", "\"<b>x</b>\"", null, new Position(1, 2, 3, 4), null);
        var moved = new Ship(ship.center(), ship.name(), null, new Position(5, 6, 7, 8), null);
        var feed = new WebPreviewFeed(List.of(), Map.of(first, ship), new Clock(2, 1));
        var old = feed.connect().frame();
        feed.publish(new Clock(3, 1), Map.of(first, ship, second, ship));
        feed.publish(new Clock(4, 1), Map.of(second, moved));

        var delta = feed.connect().frame().since(old);
        assertEquals(Map.of(second, moved), delta.updated());
        assertEquals(List.of(first), delta.removed());
        assertEquals(Map.of(first, ship), old.ships());
    }
}
