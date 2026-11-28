package io.github.jxt159753.blockspaceprogram.client;

final class SurfaceMapMipmaps {
    private static final double[] LINEAR = new double[256];

    static {
        for (int i = 0; i < LINEAR.length; i++) {
            double srgb = i / 255.0;
            LINEAR[i] = srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
        }
    }

    private SurfaceMapMipmaps() {}

    static int[] downsample(int width, int height, int[] pixels) {
        int nextWidth = width / 2;
        int nextHeight = Math.max(1, height / 2);
        int[] next = new int[nextWidth * nextHeight];
        for (int y = 0; y < nextHeight; y++) {
            int topRow = 2 * y * width;
            int bottomRow = Math.min(2 * y + 1, height - 1) * width;
            for (int x = 0; x < nextWidth; x++) {
                int column = 2 * x;
                next[y * nextWidth + x] = average(pixels[topRow + column], pixels[topRow + column + 1],
                        pixels[bottomRow + column], pixels[bottomRow + column + 1]);
            }
        }
        return next;
    }

    private static int average(int a, int b, int c, int d) {
        // Alpha stores water coverage and is already linear.
        int alpha = ((a >>> 24) + (b >>> 24) + (c >>> 24) + (d >>> 24) + 2) / 4;
        return alpha << 24 | channel(a, b, c, d, 16) << 16 | channel(a, b, c, d, 8) << 8 | channel(a, b, c, d, 0);
    }

    private static int channel(int a, int b, int c, int d, int shift) {
        double light = (LINEAR[a >>> shift & 255] + LINEAR[b >>> shift & 255]
                + LINEAR[c >>> shift & 255] + LINEAR[d >>> shift & 255]) / 4;
        double srgb = light <= 0.0031308 ? 12.92 * light : 1.055 * Math.pow(light, 1 / 2.4) - 0.055;
        return (int) Math.round(255 * srgb);
    }
}
