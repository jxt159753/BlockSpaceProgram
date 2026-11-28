package io.github.jxt159753.blockspaceprogram.sky.surfacemap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SurfaceMapTransfer {
    static final int FRAGMENT_BYTES = 768 * 1024;
    public static final int MAX_COMPRESSED_BYTES = 3 * 1024 * 1024;
    private static final int MAX_FRAGMENTS = Math.ceilDiv(MAX_COMPRESSED_BYTES, FRAGMENT_BYTES);

    private SurfaceMapTransfer() {}

    static List<byte[]> split(byte[] compressed) throws IOException {
        if (compressed.length == 0 || compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Invalid compressed surface map size");
        }
        var fragments = new ArrayList<byte[]>(Math.ceilDiv(compressed.length, FRAGMENT_BYTES));
        for (int start = 0; start < compressed.length; start += FRAGMENT_BYTES) {
            fragments.add(Arrays.copyOfRange(compressed, start, Math.min(start + FRAGMENT_BYTES, compressed.length)));
        }
        return fragments;
    }

    public static final class Assembler {
        private byte[][] fragments;
        private int received;
        private int bytes;

        public boolean add(int index, int count, byte[] data) throws IOException {
            if (count < 1 || count > MAX_FRAGMENTS || index < 0 || index >= count
                    || data.length < 1 || data.length > FRAGMENT_BYTES) {
                throw new IOException("Invalid surface map fragment");
            }
            if (fragments == null) {
                fragments = new byte[count][];
            }
            if (fragments.length != count || fragments[index] != null) {
                throw new IOException("Inconsistent surface map fragments");
            }
            fragments[index] = data;
            received++;
            bytes += data.length;
            return received == count;
        }

        public byte[] assemble() throws IOException {
            if (fragments == null || received != fragments.length) {
                throw new IOException("Missing surface map fragments");
            }
            var compressed = new byte[bytes];
            int offset = 0;
            for (byte[] fragment : fragments) {
                System.arraycopy(fragment, 0, compressed, offset, fragment.length);
                offset += fragment.length;
            }
            return compressed;
        }
    }

    static final class SendHistory<D> {
        private final Set<Delivery<D>> sent = new HashSet<>();

        boolean markSent(D dimension, String hash) {
            return sent.add(new Delivery<>(dimension, hash));
        }

        private record Delivery<D>(D dimension, String hash) {}
    }
}
