package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.BiomeGrid;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapTransfer;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

final class SurfaceMapDiskCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final String EXTENSION = ".bin";
    private static final Pattern CONTENT_HASH = Pattern.compile("[0-9a-f]{64}");

    private SurfaceMapDiskCache() {}

    static boolean isContentHash(String hash) {
        return CONTENT_HASH.matcher(hash).matches();
    }

    @Nullable
    static synchronized BiomeGrid read(Path directory, int width, String hash) throws IOException {
        Path file = file(directory, hash);
        byte[] compressed;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            compressed = input.readNBytes(SurfaceMapTransfer.MAX_COMPRESSED_BYTES + 1);
        } catch (NoSuchFileException e) {
            return null;
        }

        BiomeGrid grid;
        try {
            grid = BiomeGrid.decode(width, hash, compressed);
        } catch (IOException e) {
            Files.deleteIfExists(file);
            return null;
        }
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException e) {
            LOGGER.warn("Could not update surface map cache timestamp for {}: {}", file, e.getMessage());
        }
        return grid;
    }

    static synchronized void write(Path directory, String computedHash, byte[] compressed) throws IOException {
        Path file = file(directory, computedHash);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "surface_map_", ".tmp");
        try {
            Files.write(temporary, compressed);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        trim(directory, MAX_BYTES);
    }

    static void trim(Path directory, long maxBytes) throws IOException {
        var files = new ArrayList<CachedFile>();
        long totalBytes = 0;
        try (var paths = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!isContentHash(name.substring(0, name.length() - EXTENSION.length()))) {
                    continue;
                }
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) {
                    files.add(new CachedFile(path, attributes.size(), attributes.lastModifiedTime()));
                    totalBytes += attributes.size();
                }
            }
        }
        files.sort(Comparator.comparing(CachedFile::lastUsed).thenComparing(CachedFile::path));
        for (var file : files) {
            if (totalBytes <= maxBytes) {
                break;
            }
            Files.deleteIfExists(file.path());
            totalBytes -= file.bytes();
        }
    }

    private static Path file(Path directory, String hash) {
        if (!isContentHash(hash)) {
            throw new IllegalArgumentException("Invalid surface map content hash");
        }
        return directory.resolve(hash + EXTENSION);
    }

    private record CachedFile(Path path, long bytes, FileTime lastUsed) {}
}
