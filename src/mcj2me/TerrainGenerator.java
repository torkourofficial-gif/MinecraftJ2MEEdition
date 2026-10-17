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

    TerrainGenerator() {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                int broad = valueNoise(x, z, 32);
                int medium = valueNoise(x, z, 12);
                int fine = valueNoise(x, z, 4);
                int h = 27 + broad * 12 / 1024
                           + medium * 5 / 1024
                           + fine * 2 / 1024;

                // Generate a small number of large, deterministic basin bowls.
                // Candidate lakes are distributed on a coarse grid; some cells
                // are skipped so land remains between lakes. Their sloped rims
                // stay above the waterline while the broad inner bowl is filled.
                int depth = basinDepthAt(x, z);
                if (depth > 0) h -= depth;
                if (h < 3) h = 3;
                if (h > HEIGHT - 8) h = HEIGHT - 8;
                heights[x][z] = (short) h;
                waterBasins[x][z] = depth >= 3 && h < WATER_LEVEL;
            }
        }
    }

    /**
     * Bowl-shaped depressions on a coarse grid create broad lakes with
     * sloping shorelines. The result is deterministic and uses no extra
     * world-sized temporary arrays.
     */
    private int basinDepthAt(int x, int z) {
        int cellX = x / 48;
        int cellZ = z / 48;
        int bestDepth = 0;
        for (int gx = cellX - 1; gx <= cellX + 1; gx++) {
            for (int gz = cellZ - 1; gz <= cellZ + 1; gz++) {
                if (gx < 0 || gz < 0 || gx > 2 || gz > 2) continue;
                int seed = hash(gx * 17 + 31, gz * 23 - 19);
                // Omit some grid cells to avoid turning the map into one ocean.
                if ((seed & 3) == 0) continue;
                int centerX = gx * 48 + 24 + ((seed >> 3) % 7);
                int centerZ = gz * 48 + 24 + ((seed >> 6) % 7);
                if (centerX < 8) centerX = 8;
                if (centerZ < 8) centerZ = 8;
                int radius = 19 + ((seed >>> 9) & 7);
                int dx = x - centerX;
                int dz = z - centerZ;
                int distanceSquared = dx * dx + dz * dz;
                int radiusSquared = radius * radius;
                if (distanceSquared >= radiusSquared) continue;
                int distance = (int) Math.sqrt(distanceSquared);
                int depth = (radius - distance) * 20 / radius;
                if (depth > bestDepth) bestDepth = depth;
            }
        }
        return bestDepth;
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
