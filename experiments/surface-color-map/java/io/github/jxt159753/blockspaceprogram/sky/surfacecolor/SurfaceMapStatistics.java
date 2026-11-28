package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

final class SurfaceMapStatistics {
    static final byte LAND = 0;
    static final byte OCEAN = 1;
    static final byte DEEP_OCEAN = 2;

    private SurfaceMapStatistics() {}

    static byte[] proceduralGrid(int width) {
        int height = width / 2;
        byte[] cells = new byte[width * height];
        for (int row = 0; row < height; row++) {
            double latitude = Math.PI * (0.5 - (row + 0.5) / height);
            double y = Math.sin(latitude);
            double latitudeScale = Math.cos(latitude);
            for (int col = 0; col < width; col++) {
                double longitude = 2 * Math.PI * ((col + 0.5) / width - 0.5);
                double x = latitudeScale * Math.cos(longitude);
                double z = -latitudeScale * Math.sin(longitude);
                // Match the relief and midpoint of landFraction in atmosphericSurface.
                double relief = Math.sin(6 * x + Math.sin(8 * z)) * Math.sin(7 * z - 4 * y)
                        + 0.34 * Math.sin(19 * x - 17 * y) * Math.cos(23 * z)
                        + 0.12 * Math.cos(47 * y + 41 * z) * Math.sin(43 * x);
                cells[row * width + col] = relief >= 0.12 ? LAND : OCEAN;
            }
        }
        return cells;
    }

    static Summary summarize(byte[] cells, int width) {
        int land = 0;
        int deepOcean = 0;
        for (byte cell : cells) {
            land += cell == LAND ? 1 : 0;
            deepOcean += cell == DEEP_OCEAN ? 1 : 0;
        }

        boolean[] visited = new boolean[cells.length];
        int[] queue = new int[cells.length];
        int largestLand = 0;
        int largestWater = 0;
        for (int i = 0; i < cells.length; i++) {
            if (visited[i]) {
                continue;
            }
            boolean water = cells[i] != LAND;
            int tail = 1;
            queue[0] = i;
            visited[i] = true;
            for (int head = 0; head < tail; head++) {
                int cell = queue[head];
                int x = cell % width;
                tail = enqueue(cells, visited, queue, cell + (x == 0 ? width - 1 : -1), water, tail);
                tail = enqueue(cells, visited, queue, cell + (x == width - 1 ? 1 - width : 1), water, tail);
                if (cell >= width) {
                    tail = enqueue(cells, visited, queue, cell - width, water, tail);
                }
                if (cell + width < cells.length) {
                    tail = enqueue(cells, visited, queue, cell + width, water, tail);
                }
            }
            if (water) {
                largestWater = Math.max(largestWater, tail);
            } else {
                largestLand = Math.max(largestLand, tail);
            }
        }
        return new Summary(land, cells.length - land - deepOcean, deepOcean, largestLand, largestWater);
    }

    private static int enqueue(byte[] cells, boolean[] visited, int[] queue, int cell, boolean water, int tail) {
        if (!visited[cell] && (cells[cell] != LAND) == water) {
            visited[cell] = true;
            queue[tail++] = cell;
        }
        return tail;
    }

    record Summary(int land, int ocean, int deepOcean, int largestLand, int largestWater) {}
}
