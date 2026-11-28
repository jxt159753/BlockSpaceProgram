package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.sky.surfacemap.BiomeGrid;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SurfaceMapDiskCacheTest {
    private static final int WIDTH = 512;
    private static final String PLAINS = "minecraft:plains";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void storesTransferredGridUnderComputedHashAndRefreshesItsTimestamp() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("surface_maps");
        var encoded = encodeGrid(256);
        assertNull(SurfaceMapDiskCache.read(directory, WIDTH, encoded.hash()));
        assertFalse(Files.exists(directory));

        var decoded = BiomeGrid.decodeWithHash(WIDTH, encoded.hash(), encoded.compressed());
        SurfaceMapDiskCache.write(directory, decoded.hash(), encoded.compressed());
        Path file = directory.resolve(encoded.hash() + ".bin");
        assertArrayEquals(encoded.compressed(), Files.readAllBytes(file));
        var previousUse = FileTime.fromMillis(1_000);
        Files.setLastModifiedTime(file, previousUse);

        var cached = SurfaceMapDiskCache.read(directory, WIDTH, encoded.hash());
        assertEquals(WIDTH, cached.width());
        assertEquals(List.of(PLAINS), cached.palette());
        assertTrue(Files.getLastModifiedTime(file).compareTo(previousUse) > 0);
    }

    @Test
    public void deletesGridWhenItsContentHashDoesNotMatchTheFileName() throws Exception {
        Path directory = temporary.getRoot().toPath();
        var expected = encodeGrid(256);
        var different = encodeGrid(128);
        Path file = Files.write(directory.resolve(expected.hash() + ".bin"), different.compressed());

        assertNull(SurfaceMapDiskCache.read(directory, WIDTH, expected.hash()));
        assertFalse(Files.exists(file));

        SurfaceMapDiskCache.write(directory, expected.hash(), expected.compressed());
        assertArrayEquals(expected.compressed(), Files.readAllBytes(file));
    }

    @Test
    public void rejectsNonHashFileNames() {
        String invalidHash = "../" + "a".repeat(61);
        assertFalse(SurfaceMapDiskCache.isContentHash(invalidHash));
        assertThrows(IllegalArgumentException.class,
                () -> SurfaceMapDiskCache.read(temporary.getRoot().toPath(), WIDTH, invalidHash));
    }

    @Test
    public void evictsLeastRecentlyUsedEntriesUntilWithinBudget() throws IOException {
        Path directory = temporary.getRoot().toPath();
        Path newer = cacheFile(directory, "a", 4, 3_000);
        Path newest = cacheFile(directory, "b", 5, 4_000);
        Path oldest = cacheFile(directory, "c", 2, 1_000);
        Path older = cacheFile(directory, "d", 3, 2_000);
        Path unrelated = Files.write(directory.resolve("unrelated.bin"), new byte[10]);

        SurfaceMapDiskCache.trim(directory, 9);

        assertFalse(Files.exists(oldest));
        assertFalse(Files.exists(older));
        assertTrue(Files.exists(newer));
        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(unrelated));
    }

    private static Path cacheFile(Path directory, String hexDigit, int bytes, long lastUsed) throws IOException {
        Path file = Files.write(directory.resolve(hexDigit.repeat(64) + ".bin"), new byte[bytes]);
        return Files.setLastModifiedTime(file, FileTime.fromMillis(lastUsed));
    }

    private static EncodedGrid encodeGrid(int sampleY) throws Exception {
        var raw = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(raw)) {
            data.writeInt(1); // BiomeGrid format version
            data.writeShort(WIDTH);
            data.writeInt(sampleY);
            data.writeShort(1);
            data.writeUTF(PLAINS);
            data.write(new byte[WIDTH * WIDTH / 2]);
        }
        byte[] bytes = raw.toByteArray();
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(bytes);
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new EncodedGrid(hash, compressed.toByteArray());
    }

    private record EncodedGrid(String hash, byte[] compressed) {}
}
