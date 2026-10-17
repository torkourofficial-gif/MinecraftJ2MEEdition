package mcj2me;

/**
 * Deterministic height-map terrain with water represented as vertical block
 * columns. Solid terrain remains height-map based; water occupies individual
 * integer Y cells from the terrain surface up to the shared waterline.
 */
final class TerrainGenerator {
    static final int SIZE = 128;
    static final int HEIGHT = 64;
    static final int WATER_LEVEL = 25;
    private final short[][] heights = new short[SIZE][SIZE];
    private final byte[][] topTypes = new byte[SIZE][SIZE];
    private final boolean[][] waterBasins = new boolean[SIZE][SIZE];
    private final boolean[][] visitedBasins = new boolean[SIZE][SIZE];

    TerrainGenerator() {
        // Build the land first. Lakes are discovered from the resulting
        // terrain instead of stamping circular bowls at predetermined points.
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                int broad = valueNoise(x, z, 32);
                int medium = valueNoise(x, z, 12);
                int fine = valueNoise(x, z, 4);
                int h = 27 + broad * 12 / 1024
                           + medium * 5 / 1024
                           + fine * 2 / 1024;
                if (h < 3) h = 3;
                if (h > HEIGHT - 8) h = HEIGHT - 8;
                heights[x][z] = (short) h;
            }
        }
        markEnclosedBasins();
    }

    /**
     * Finds naturally enclosed lowland regions in the generated height map.
     * A flood fill marks connected columns below the waterline. Regions that
     * reach the map edge are treated as open lowlands, not inland lakes.
     * Keeping only sizeable, sufficiently deep regions avoids tiny puddles.
     *
     * A visited mask prevents rejected open lowlands from being flood-filled
     * repeatedly. A short queue stores packed column indices to limit memory.
     */
    private void markEnclosedBasins() {
        short[] queue = new short[SIZE * SIZE];
        final int minimumArea = 18;
        final int minimumDepth = 3;

        for (int sx = 0; sx < SIZE; sx++) {
            for (int sz = 0; sz < SIZE; sz++) {
                if (visitedBasins[sx][sz] || heights[sx][sz] >= WATER_LEVEL) continue;

                int head = 0;
                int tail = 0;
                int lowest = HEIGHT;
                boolean touchesEdge = false;
                int start = sx * SIZE + sz;
                queue[tail++] = (short) start;
                visitedBasins[sx][sz] = true;

                while (head < tail) {
                    int index = queue[head++] & 0xFFFF;
                    int x = index / SIZE;
                    int z = index % SIZE;
                    int h = heights[x][z];
                    if (h < lowest) lowest = h;
                    if (x == 0 || z == 0 || x == SIZE - 1 || z == SIZE - 1) {
                        touchesEdge = true;
                    }

                    if (x > 0) tail = enqueueLowland(x - 1, z, queue, tail);
                    if (x + 1 < SIZE) tail = enqueueLowland(x + 1, z, queue, tail);
                    if (z > 0) tail = enqueueLowland(x, z - 1, queue, tail);
                    if (z + 1 < SIZE) tail = enqueueLowland(x, z + 1, queue, tail);
                }

                boolean basin = !touchesEdge && tail >= minimumArea
                    && lowest <= WATER_LEVEL - minimumDepth;
                if (basin) {
                    for (int i = 0; i < tail; i++) {
                        int index = queue[i] & 0xFFFF;
                        waterBasins[index / SIZE][index % SIZE] = true;
                    }
                }
            }
        }
    }

    private int enqueueLowland(int x, int z, short[] queue, int tail) {
        if (!visitedBasins[x][z] && heights[x][z] < WATER_LEVEL) {
            visitedBasins[x][z] = true;
            queue[tail++] = (short) (x * SIZE + z);
        }
        return tail;
    }

    int getHeight(int x, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
            return 0;
        }
        return heights[x][z];
    }

    boolean isWaterAt(int x, int z) {
        return x >= 0 && z >= 0 && x < SIZE && z < SIZE
            && waterBasins[x][z] && heights[x][z] < WATER_LEVEL;
    }

    /**
     * Tests one water block cell. Water is traversable, but its occupied
     * volume is discrete: x/z selects a column and y selects a block layer.
     */
    boolean isWaterBlockAt(int x, int y, int z) {
        return isWaterAt(x, z) && y >= heights[x][z] && y < WATER_LEVEL;
    }

    int getWaterLevel() {
        return WATER_LEVEL;
    }

    // Bilinear interpolation smooths the camera's ground height between columns.
    float getHeightSmooth(float x, float z) {
        if (x < 0.0f) x = 0.0f;
        if (z < 0.0f) z = 0.0f;
        if (x > SIZE - 1.001f) x = SIZE - 1.001f;
        if (z > SIZE - 1.001f) z = SIZE - 1.001f;
        int x0 = (int) x, z0 = (int) z;
        int x1 = x0 + 1, z1 = z0 + 1;
        float fx = x - x0, fz = z - z0;
        float a = getHeight(x0, z0) * (1.0f - fx) + getHeight(x1, z0) * fx;
        float b = getHeight(x0, z1) * (1.0f - fx) + getHeight(x1, z1) * fx;
        return a * (1.0f - fz) + b * fz;
    }

    int getTopType(int x, int z) { if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return 0; return topTypes[x][z]; }
    void setTopType(int x, int z, int type) { if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return; if (type < 0 || type > 2) type = 0; topTypes[x][z] = (byte) type; }
    void setHeight(int x, int z, int height) { if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return; if (height < 1) height = 1; if (height > HEIGHT - 3) height = HEIGHT - 3; heights[x][z] = (short) height; }
    boolean isTreeAt(int x, int z) {
        if (x < 5 || z < 5 || x >= SIZE - 5 || z >= SIZE - 5 || x % 11 != 5 || z % 13 != 6) return false;
        int h = getHeight(x, z), min = h, max = h, n; if (h < 12 || h > HEIGHT - 10) return false;
        n = getHeight(x - 1, z); if (n < min) min = n; if (n > max) max = n;
        n = getHeight(x + 1, z); if (n < min) min = n; if (n > max) max = n;
        n = getHeight(x, z - 1); if (n < min) min = n; if (n > max) max = n;
        n = getHeight(x, z + 1); if (n < min) min = n; if (n > max) max = n;
        return max - min <= 2 && (hash(x, z) & 3) != 0;
    }
    int getTreeHeight(int x, int z) { return 3 + ((hash(x + 19, z - 7) >>> 3) & 1); }

    private int valueNoise(int x, int z, int scale) {
        int gx = x / scale;
        int gz = z / scale;
        int fx = (x % scale) * 256 / scale;
        int fz = (z % scale) * 256 / scale;

        // Smooth interpolation in fixed-point arithmetic (0..256).
        fx = (fx * fx * (768 - 2 * fx)) >> 16;
        fz = (fz * fz * (768 - 2 * fz)) >> 16;

        int a = hash(gx, gz);
        int b = hash(gx + 1, gz);
        int c = hash(gx, gz + 1);
        int d = hash(gx + 1, gz + 1);
        int top = a + ((b - a) * fx >> 8);
        int bottom = c + ((d - c) * fx >> 8);
        return top + ((bottom - top) * fz >> 8);
    }

    private int hash(int x, int z) {
        int n = x * 374761393 + z * 668265263 + 0x51ED270B;
        n = (n ^ (n >>> 13)) * 1274126177;
        n ^= n >>> 16;
        return (n & 2047) - 1024;
    }
}
