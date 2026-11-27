package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class BiomeGrid {
    private static final int FORMAT_VERSION = 1;
    static final int MAX_PALETTE = 256;
    static final int MAX_COMPRESSED_BYTES = 3 * 1024 * 1024;
    private final int width;
    private final List<String> palette;
    private final byte[] cells;

    BiomeGrid(int width, List<String> palette, byte[] cells) {
        this.width = width;
        this.palette = List.copyOf(palette);
        this.cells = cells;
    }

    public int width() {
        return width;
    }

    public int height() {
        return width / 2;
    }

    public List<String> palette() {
        return palette;
    }

    public int index(int x, int y) {
        return Byte.toUnsignedInt(cells[y * width + x]);
    }

    static boolean supportedWidth(int width) {
        return width == 512 || width == 1024 || width == 2048;
    }

    static int sampleCoordinate(int cell, int cellCount, double span) {
        return (int) Math.floor(((cell + 0.5) / cellCount - 0.5) * span);
    }

    String biomeAt(double worldX, double worldZ, double worldWidth) {
        double u = worldX / worldWidth + 0.5;
        int column = (int) ((u - Math.floor(u)) * width);
        int row = Math.clamp((int) ((worldZ / (worldWidth / 2) + 0.5) * height()), 0, height() - 1);
        return palette.get(index(column, row));
    }

    Encoded encode() throws IOException {
        var raw = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(raw)) {
            data.writeInt(FORMAT_VERSION);
            data.writeShort(width);
            data.writeShort(palette.size());
            for (String name : palette) {
                data.writeUTF(name);
            }
            data.write(cells);
        }
        byte[] bytes = raw.toByteArray();
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(bytes);
        }
        return new Encoded(hash(bytes), bytes.length, compressed.toByteArray());
    }

    public static BiomeGrid decode(int expectedWidth, String expectedHash, byte[] compressed) throws IOException {
        if (!supportedWidth(expectedWidth)) {
            throw new IOException("Unsupported surface map resolution");
        }
        // One byte per cell, plus the header and a bounded allowance for biome IDs.
        int maxBytes = expectedWidth * (expectedWidth / 2) + MAX_PALETTE * 256 + 8;
        byte[] bytes;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            bytes = gzip.readNBytes(maxBytes + 1);
        }
        if (bytes.length > maxBytes || !expectedHash.equals(hash(bytes))) {
            throw new IOException("Surface map content does not match its manifest hash");
        }
        try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (data.readInt() != FORMAT_VERSION || data.readUnsignedShort() != expectedWidth) {
                throw new IOException("Unexpected surface map format or resolution");
            }
            int paletteSize = data.readUnsignedShort();
            if (paletteSize < 1 || paletteSize > MAX_PALETTE) {
                throw new IOException("Invalid surface map palette size");
            }
            var palette = new ArrayList<String>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                palette.add(data.readUTF());
            }
            int size = expectedWidth * (expectedWidth / 2);
            byte[] cells = data.readNBytes(size);
            if (cells.length != size || data.read() != -1) {
                throw new IOException("Invalid surface map grid size");
            }
            for (byte cell : cells) {
                if (Byte.toUnsignedInt(cell) >= paletteSize) {
                    throw new IOException("Unknown surface map palette index");
                }
            }
            return new BiomeGrid(expectedWidth, palette, cells);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record Encoded(String hash, int rawBytes, byte[] compressed) {}
}
