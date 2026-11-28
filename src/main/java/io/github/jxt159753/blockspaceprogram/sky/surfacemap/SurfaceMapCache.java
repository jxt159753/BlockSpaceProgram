package io.github.jxt159753.blockspaceprogram.sky.surfacemap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jetbrains.annotations.Nullable;

final class SurfaceMapCache {
    private SurfaceMapCache() {}

    @Nullable
    static Cached read(Path file, int width) throws IOException {
        try (var data = new DataInputStream(Files.newInputStream(file))) {
            String hash = data.readUTF();
            int length = data.readInt();
            if (length < 1 || length > SurfaceMapTransfer.MAX_COMPRESSED_BYTES) {
                throw new IOException("Invalid surface map cache size");
            }
            byte[] compressed = data.readNBytes(length);
            if (compressed.length != length || data.read() != -1) {
                throw new IOException("Unexpected surface map cache length");
            }
            return new Cached(BiomeGrid.decode(width, hash, compressed), new BiomeGrid.Encoded(hash, compressed));
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    static void write(Path file, BiomeGrid.Encoded encoded) throws IOException {
        Path parent = Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(parent, "surface_map_", ".tmp");
        try {
            try (var data = new DataOutputStream(Files.newOutputStream(temporary))) {
                data.writeUTF(encoded.hash());
                data.writeInt(encoded.compressed().length);
                data.write(encoded.compressed());
            }
            SurfaceMapSampler.checkInterrupted();
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record Cached(BiomeGrid grid, BiomeGrid.Encoded encoded) {}
}
