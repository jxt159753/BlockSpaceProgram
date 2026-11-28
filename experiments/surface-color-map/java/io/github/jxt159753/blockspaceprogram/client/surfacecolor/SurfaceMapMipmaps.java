package io.github.jxt159753.blockspaceprogram.client.surfacecolor;

final class SurfaceMapMipmaps {
    private static final double[] LINEAR = new double[256];

    static {
        for (int i = 0; i < LINEAR.length; i++) {
            double srgb = i / 255.0;
            LINEAR[i] = srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
        }
    }

    private SurfaceMapMipmaps() {}

    static int[][] build(int width, int height, int[] base) {
        int[][] levels = new int[Integer.numberOfTrailingZeros(width) + 1][];
        levels[0] = base;
        for (int level = 1; level < levels.length; level++) {
            int nextWidth = width / 2;
            int nextHeight = Math.max(1, height / 2);
            int[] pixels = levels[level - 1];
            int[] next = new int[nextWidth * nextHeight];
            for (int y = 0; y < nextHeight; y++) {
                int row = 2 * y * width;
                int nextRow = Math.min(2 * y + 1, height - 1) * width;
                for (int x = 0; x < nextWidth; x++) {
                    int column = 2 * x;
                    next[y * nextWidth + x] = average(pixels[row + column], pixels[row + column + 1],
                            pixels[nextRow + column], pixels[nextRow + column + 1]);
                }
            }
            levels[level] = next;
            width = nextWidth;
            height = nextHeight;
        }
        return levels;
    }

    private static int average(int a, int b, int c, int d) {
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
