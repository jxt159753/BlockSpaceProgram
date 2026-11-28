package io.github.jxt159753.blockspaceprogram.sky.surfacemap;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BiomeGridTest {
    private static final int WIDTH = 512;
    private static final String PLAINS = "minecraft:plains";
    private static final String OCEAN = "minecraft:ocean";

    @Test
    public void encodesGridAndHashesMetadataAndCells() throws IOException {
        var cells = new byte[WIDTH * WIDTH / 2];
        cells[17 * WIDTH + 32] = 1;
        var grid = grid(256, cells);
        var encoded = grid.encode();
        var decoded = BiomeGrid.decode(WIDTH, encoded.hash(), encoded.compressed());

        assertEquals(64, encoded.hash().length());
        assertEquals(encoded.hash(), decoded.encode().hash());
        assertEquals(256, decoded.sampleY());
        assertEquals(List.of(PLAINS, OCEAN), decoded.palette());
        assertEquals(1, decoded.index(32, 17));
        assertEquals(0, decoded.index(33, 17));
        assertNotEquals(encoded.hash(), grid(128, cells).encode().hash());
        cells[17 * WIDTH + 32] = 0;
        assertNotEquals(encoded.hash(), grid(256, cells).encode().hash());
    }

    @Test
    public void rejectsCorruptOrMismatchedContent() throws IOException {
        var encoded = grid(256, new byte[WIDTH * WIDTH / 2]).encode();
        byte[] truncated = Arrays.copyOf(encoded.compressed(), encoded.compressed().length / 2);
        assertThrows(IOException.class, () -> BiomeGrid.decode(WIDTH, encoded.hash(), truncated));
        assertThrows(IOException.class, () -> BiomeGrid.decode(WIDTH, "0".repeat(64), encoded.compressed()));
        assertThrows(IOException.class, () -> BiomeGrid.decode(1024, encoded.hash(), encoded.compressed()));

        var cells = new byte[WIDTH * WIDTH / 2];
        cells[0] = (byte) 255;
        var invalidIndex = grid(256, cells).encode();
        assertThrows(IOException.class, () -> BiomeGrid.decode(WIDTH, invalidIndex.hash(), invalidIndex.compressed()));
    }

    @Test
    public void validatesUniformlySpacedCellsAtFullGridCenters() {
        int width = 2048;
        int height = width / 2;
        int worldWidth = 377_088;
        var cells = new byte[width * height];
        for (int row = 16; row < height; row += 32) {
            for (int column = 16; column < width; column += 32) {
                cells[row * width + column] = 1;
            }
        }
        var grid = new BiomeGrid(width, 256, List.of(PLAINS, OCEAN), cells);
        int[] sampled = {0};
        assertTrue(grid.matchesSamples((x, z) -> {
            sampled[0]++;
            assertEquals(16, x % 32);
            assertEquals(16, z % 32);
            return grid.biomeAt(BiomeGrid.sampleCoordinate(x, width, worldWidth),
                    BiomeGrid.sampleCoordinate(z, height, worldWidth / 2.0), worldWidth);
        }));
        assertEquals(64 * 32, sampled[0]);
        assertFalse(grid.matchesSamples((x, z) -> x == 16 && z == 16 ? PLAINS : OCEAN));
    }

    private static BiomeGrid grid(int sampleY, byte[] cells) {
        return new BiomeGrid(WIDTH, sampleY, List.of(PLAINS, OCEAN), cells);
    }
}
