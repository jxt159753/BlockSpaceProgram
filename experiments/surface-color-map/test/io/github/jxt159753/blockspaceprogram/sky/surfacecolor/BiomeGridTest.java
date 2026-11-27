package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.Test;

public class BiomeGridTest {
    private static final int WIDTH = 512;
    private static final int HEIGHT = WIDTH / 2;
    private static final double WORLD_WIDTH = 377_088;
    private static final List<String> PALETTE = List.of("minecraft:ocean", "minecraft:desert");

    @Test
    public void sampleCentersRoundTripThroughProjectionIncludingTheLongitudeSeam() {
        byte[] cells = new byte[WIDTH * HEIGHT];
        cells[0] = 1;
        cells[WIDTH * HEIGHT - 1] = 1;
        var grid = new BiomeGrid(WIDTH, PALETTE, cells);
        int west = BiomeGrid.sampleCoordinate(0, WIDTH, WORLD_WIDTH);
        int east = BiomeGrid.sampleCoordinate(WIDTH - 1, WIDTH, WORLD_WIDTH);
        int north = BiomeGrid.sampleCoordinate(0, HEIGHT, WORLD_WIDTH / 2);
        int south = BiomeGrid.sampleCoordinate(HEIGHT - 1, HEIGHT, WORLD_WIDTH / 2);
        assertEquals("minecraft:desert", grid.biomeAt(west + WORLD_WIDTH, north, WORLD_WIDTH));
        assertEquals("minecraft:desert", grid.biomeAt(east, south, WORLD_WIDTH));
        assertEquals("minecraft:ocean", grid.biomeAt(east, north, WORLD_WIDTH));
    }

    @Test
    public void compressionPreservesPaletteAndCellsAndChecksTheContentHash() throws IOException {
        byte[] cells = new byte[WIDTH * HEIGHT];
        var original = new BiomeGrid(WIDTH, PALETTE, cells).encode();
        cells[WIDTH * HEIGHT - 1] = 1;
        var changed = new BiomeGrid(WIDTH, PALETTE, cells).encode();
        assertNotEquals(original.hash(), changed.hash());
        assertThrows(IOException.class, () -> BiomeGrid.decode(WIDTH, original.hash(), changed.compressed()));
        var decoded = BiomeGrid.decode(WIDTH, changed.hash(), changed.compressed());
        assertEquals(PALETTE, decoded.palette());
        assertEquals(0, decoded.index(WIDTH - 2, HEIGHT - 1));
        assertEquals(1, decoded.index(WIDTH - 1, HEIGHT - 1));
    }
}
