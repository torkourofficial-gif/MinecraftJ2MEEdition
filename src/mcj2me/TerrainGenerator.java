package mcj2me;

import java.util.Random;

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
    private final int seed;

    TerrainGenerator() {
        // Each new world receives a random seed.
        long now = System.currentTimeMillis();
        seed = (int) (now ^ (now >>> 32) ^ new Random(now).nextInt());
        Random random = new Random(seed);

        // Generate natural terrain first, then place actual lake structures
        // that carve their own basins and fill them with water.
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
        generateWaterBasinStructures(random);
    }

    /**
     * Places lake structures directly into the height map. Each structure
     * carves a bowl, creates a raised irregular shoreline, and marks its
     * flooded interior. Position, shape and depth vary by world seed.
     */
    private void generateWaterBasinStructures(Random random) {
        int basinCount = 7 + random.nextInt(6);
        for (int b = 0; b < basinCount; b++) {
            // Keep one guaranteed test lake in the spawn area's loaded
            // chunks, in front of the player. The remaining lakes stay random.
            int cx;
            int cz;
            if (b == 0) {
                cx = 64;
                cz = 91;
            } else {
                cx = 12 + random.nextInt(SIZE - 24);
                cz = 12 + random.nextInt(SIZE - 24);
            }
            int radiusX = 5 + random.nextInt(6);
            int radiusZ = 5 + random.nextInt(6);
            int depth = 3 + random.nextInt(5);
            int minX = Math.max(1, cx - radiusX - 2);
            int maxX = Math.min(SIZE - 2, cx + radiusX + 2);
            int minZ = Math.max(1, cz - radiusZ - 2);
            int maxZ = Math.min(SIZE - 2, cz + radiusZ + 2);
            int baseHeight = heights[cx][cz];
            int floor = WATER_LEVEL - depth;
            if (floor < 3) floor = 3;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    float dx = (float) (x - cx) / (float) radiusX;
                    float dz = (float) (z - cz) / (float) radiusZ;
                    float distance = dx * dx + dz * dz;
                    int edgeJitter = hash(x + b * 17, z - b * 11) & 3;
                    float edge = 1.0f + (edgeJitter - 1) * 0.055f;
                    if (distance > edge * edge) continue;

                    float normalized = (float) Math.sqrt(distance);
                    boolean insideWater = distance <= 0.72f;
                    int h;
                    if (insideWater) {
                        // Force the entire marked interior below the waterline.
                        // The previous slope formula could raise much of the
                        // supposed lake above WATER_LEVEL, leaving only a tiny
                        // central patch (or none after later structures overlap).
                        h = floor + (int) (normalized * (depth - 1));
                        h += hash(x + b * 29, z + b * 7) & 1;
                        if (h >= WATER_LEVEL) h = WATER_LEVEL - 1;
                    } else {
                        // Build the sloped bank and a lip above the waterline.
                        h = floor + (int) (normalized * (depth + 4));
                        if (normalized > 0.82f) {
                            h += (int) ((normalized - 0.82f) * 11.0f);
                        }
                        h += hash(x + b * 29, z + b * 7) & 1;
                        int rimHeight = Math.max(WATER_LEVEL + 1, baseHeight);
                        if (h > rimHeight) h = rimHeight;
                    }
                    if (h < 2) h = 2;
                    // A later shoreline must not overwrite an existing lake
                    // interior with dry ground when two structures overlap.
                    if (insideWater) {
                        heights[x][z] = (short) h;
                        waterBasins[x][z] = true;
                    } else if (!waterBasins[x][z]) {
                        heights[x][z] = (short) h;
                    }
                }
            }
        }
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
        int n = x * 374761393 + z * 668265263 + seed + 0x51ED270B;
        n = (n ^ (n >>> 13)) * 1274126177;
        n ^= n >>> 16;
        return (n & 2047) - 1024;
    }
}
