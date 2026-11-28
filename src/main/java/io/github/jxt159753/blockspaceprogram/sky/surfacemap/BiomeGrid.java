package io.github.jxt159753.blockspaceprogram.sky.surfacemap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class BiomeGrid {
    static final int MAX_PALETTE = 256;
    private static final int FORMAT_VERSION = 1;
    private static final int CHECK_COLUMNS = 64;
    private static final int CHECK_ROWS = 32;

    private final int width;
    private final int sampleY;
    private final List<String> palette;
    private final byte[] cells;

    BiomeGrid(int width, int sampleY, List<String> palette, byte[] cells) {
        this.width = width;
        this.sampleY = sampleY;
        this.palette = List.copyOf(palette);
        this.cells = cells;
    }

    public int width() {
        return width;
    }

    public int height() {
        return width / 2;
    }

    int sampleY() {
        return sampleY;
    }

    public List<String> palette() {
        return palette;
    }

    public int index(int column, int row) {
        return Byte.toUnsignedInt(cells[row * width + column]);
    }

    public static boolean supportedWidth(int width) {
        return width == 512 || width == 1024 || width == 2048;
    }

    static int sampleCoordinate(int cell, int cellCount, double span) {
        return (int) Math.floor(((cell + 0.5) / cellCount - 0.5) * span);
    }

    String biomeAt(double worldX, double worldZ, double worldWidth) {
        double longitude = worldX / worldWidth + 0.5;
        int column = (int) ((longitude - Math.floor(longitude)) * width);
        int row = Math.clamp((int) ((worldZ / (worldWidth / 2) + 0.5) * height()), 0, height() - 1);
        return palette.get(index(column, row));
    }

    boolean matchesSamples(CellSampler sampler) {
        int columnStride = width / CHECK_COLUMNS;
        int rowStride = height() / CHECK_ROWS;
        // Sample the cell on the east/south side of each validation tile's center.
        for (int row = rowStride / 2; row < height(); row += rowStride) {
            for (int column = columnStride / 2; column < width; column += columnStride) {
                if (!palette.get(index(column, row)).equals(sampler.biomeAt(column, row))) {
                    return false;
                }
            }
        }
        return true;
    }

    Encoded encode() throws IOException {
        var digest = sha256();
        var compressed = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(new DigestOutputStream(new GZIPOutputStream(compressed), digest))) {
            data.writeInt(FORMAT_VERSION);
            data.writeShort(width);
            data.writeInt(sampleY);
            data.writeShort(palette.size());
            for (String id : palette) {
                data.writeUTF(id);
            }
            data.write(cells);
        }
        return new Encoded(HexFormat.of().formatHex(digest.digest()), compressed.toByteArray());
    }

    public static BiomeGrid decode(int width, String hash, byte[] compressed) throws IOException {
        return decodeWithHash(width, hash, compressed).grid();
    }

    public static Decoded decodeWithHash(int width, String expectedHash, byte[] compressed) throws IOException {
        if (!supportedWidth(width) || compressed.length > SurfaceMapTransfer.MAX_COMPRESSED_BYTES) {
            throw new IOException("Unsupported surface map size");
        }
        var digest = sha256();
        try (var data = new DataInputStream(new DigestInputStream(
                new GZIPInputStream(new ByteArrayInputStream(compressed)), digest))) {
            if (data.readInt() != FORMAT_VERSION || data.readUnsignedShort() != width) {
                throw new IOException("Unexpected surface map format or resolution");
            }
            int sampleY = data.readInt();
            int paletteSize = data.readUnsignedShort();
            if (paletteSize < 1 || paletteSize > MAX_PALETTE) {
                throw new IOException("Invalid surface map palette size");
            }
            var palette = new ArrayList<String>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                palette.add(data.readUTF());
            }
            int size = width * (width / 2);
            byte[] cells = data.readNBytes(size);
            if (cells.length != size || data.read() != -1) {
                throw new IOException("Invalid surface map grid size");
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            if (!hash.equals(expectedHash)) {
                throw new IOException("Surface map content does not match its SHA-256 hash");
            }
            for (byte cell : cells) {
                if (Byte.toUnsignedInt(cell) >= paletteSize) {
                    throw new IOException("Unknown surface map palette index");
                }
            }
            return new Decoded(new BiomeGrid(width, sampleY, palette, cells), hash);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface CellSampler {
        String biomeAt(int column, int row);
    }

    record Encoded(String hash, byte[] compressed) {}

    public record Decoded(BiomeGrid grid, String hash) {}
}
