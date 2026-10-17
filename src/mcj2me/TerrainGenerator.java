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
    // Original generated surface is retained so strata do not reset when mined.
    private final short[][] originalHeights = new short[SIZE][SIZE];
    private final byte[][] topTypes = new byte[SIZE][SIZE];
    private final boolean[][] waterBasins = new boolean[SIZE][SIZE];
    // Sparse storage avoids a full 128x64x128 voxel array on the MIDP heap.
    private int[] removedLeafIds = new int[64];
    private int removedLeafCount;
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
        generateCaveSystems(random);
        lowerWorldEdgesTowardSea();
        for (int x = 0; x < SIZE; x++) {
            System.arraycopy(heights[x], 0, originalHeights[x], 0, SIZE);
        }
    }

    /**
     * Adds winding, stone-sided cave ravines and entrances to the height map.
     * The current renderer stores one surface height per X/Z column, so these
     * are open cave cuts, not fully enclosed voxel tunnels.
     */
    private void generateCaveSystems(Random random) {
        int systems = 5 + random.nextInt(4);
        for (int c=0; c<systems; c++) {
            int x=10+random.nextInt(SIZE-20), z=10+random.nextInt(SIZE-20);
            if (Math.abs(x-64)<15 && Math.abs(z-105)<18) z=72;
            float angle=random.nextFloat()*6.2831853f;
            int length=18+random.nextInt(25), floor=13+random.nextInt(8);
            for (int step=0; step<length; step++) {
                int cx=x+(int)(Math.cos(angle)*step), cz=z+(int)(Math.sin(angle)*step);
                if (cx<5 || cz<5 || cx>=SIZE-5 || cz>=SIZE-5) break;
                if (step%5==0) angle+=(random.nextInt(3)-1)*0.24f;
                int radius=2+(((step+c)%3==0)?1:0);
                for (int dx=-radius; dx<=radius; dx++) for (int dz=-radius; dz<=radius; dz++) {
                    int px=cx+dx, pz=cz+dz;
                    if (px<2 || pz<2 || px>=SIZE-2 || pz>=SIZE-2) continue;
                    int d2=dx*dx+dz*dz;
                    if (d2>radius*radius+1) continue;
                    int target=floor+Math.max(0,radius-(int)Math.sqrt(d2));
                    if (heights[px][pz]>target) heights[px][pz]=(short)target;
                    waterBasins[px][pz]=false;
                }
            }
        }
    }

    /**
     * Lower the outer ring into the sea so the horizon water plane can be
     * seen beyond the finite 128x128 terrain instead of ending at a cliff.
     * The last 12 columns slope down toward the shared waterline.
     */
    private void lowerWorldEdgesTowardSea() {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                int edge = x;
                if (z < edge) edge = z;
                int oppositeX = SIZE - 1 - x;
                int oppositeZ = SIZE - 1 - z;
                if (oppositeX < edge) edge = oppositeX;
                if (oppositeZ < edge) edge = oppositeZ;
                if (edge >= 12) continue;
                int target = WATER_LEVEL - 7 + edge;
                if (heights[x][z] > target) heights[x][z] = (short) target;
            }
        }
    }

    /**
     * Places lake structures directly into the height map. Each structure
     * carves a bowl, creates a raised irregular shoreline, and marks its
     * flooded interior. Position, shape and depth vary by world seed.
     */
    private void generateWaterBasinStructures(Random random) {
        int basinCount = 7 + random.nextInt(6);
        for (int b = 0; b < basinCount; b++) {
            // Keep one test lake near spawn; the rest are distributed randomly.
            int cx;
            int cz;
            if (b == 0) {
                cx = 64;
                cz = 91;
            } else {
                cx = 12 + random.nextInt(SIZE - 24);
                cz = 12 + random.nextInt(SIZE - 24);
            }

            int radiusX = 6 + random.nextInt(6);
            int radiusZ = 6 + random.nextInt(6);
            int depth = 3 + random.nextInt(5);
            // Extend the outer bank beyond the pool itself so it joins the
            // surrounding landscape instead of ending as a floating ledge.
            int bankRadiusX = radiusX * 13 / 10 + 2;
            int bankRadiusZ = radiusZ * 13 / 10 + 2;
            int minX = Math.max(1, cx - bankRadiusX);
            int maxX = Math.min(SIZE - 2, cx + bankRadiusX);
            int minZ = Math.max(1, cz - bankRadiusZ);
            int maxZ = Math.min(SIZE - 2, cz + bankRadiusZ);
            int floor = WATER_LEVEL - depth;
            if (floor < 3) floor = 3;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    float dx = (float) (x - cx) / (float) radiusX;
                    float dz = (float) (z - cz) / (float) radiusZ;
                    float distance = dx * dx + dz * dz;
                    int edgeJitter = hash(x + b * 17, z - b * 11) & 3;
                    float edge = 1.0f + (edgeJitter - 1) * 0.045f;
                    float normalized = (float) Math.sqrt(distance) / edge;
                    if (normalized > 1.30f) continue;
                    int originalHeight = heights[x][z];
                    int h;

                    if (normalized <= 0.62f) {
                        // A rounded, submerged bowl. Keep the water's edge
                        // inside the bank, with no exposed ground at water height.
                        float t = normalized / 0.62f;
                        float smooth = t * t * (3.0f - 2.0f * t);
                        h = floor + (int) ((WATER_LEVEL - 1 - floor) * smooth);
                        if (h >= WATER_LEVEL) h = WATER_LEVEL - 1;
                        if (h < 2) h = 2;
                        heights[x][z] = (short) h;
                        waterBasins[x][z] = true;
                    } else {
                        // Build a real shoreline: its inner edge stands above
                        // the water surface, then slopes gradually back into
                        // the local terrain over a broad ring (not a volcano rim).
                        float t = (normalized - 0.62f) / 0.68f;
                        if (t < 0.0f) t = 0.0f;
                        if (t > 1.0f) t = 1.0f;
                        float smooth = t * t * (3.0f - 2.0f * t);
                        int bankBase = WATER_LEVEL + 1;
                        int bankTarget = Math.max(bankBase, originalHeight);
                        h = bankBase + (int) ((bankTarget - bankBase) * smooth);
                        if (h < 2) h = 2;
                        if (!waterBasins[x][z]) heights[x][z] = (short) h;
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

    static final int BLOCK_GRASS = 0;
    static final int BLOCK_DIRT = 1;
    static final int BLOCK_STONE = 2;
    static final int BLOCK_WOOD = 3;
    static final int BLOCK_LEAVES = 4;
    static final int BLOCK_COAL_ORE = 5;
    static final int BLOCK_IRON_ORE = 6;
    static final int BLOCK_GOLD_ORE = 7;
    static final int BLOCK_DIAMOND_ORE = 8;
    static final int BLOCK_BEDROCK = 9;
    static final int BLOCK_TNT = 10;
    static final int BLOCK_PLANKS = 11;
    static final int BLOCK_SAND = 12;
    static final int BLOCK_GRAVEL = 13;
    static final int BLOCK_BRICK = 14;
    static final int BLOCK_OBSIDIAN = 15;

    int getTopType(int x, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return BLOCK_BEDROCK;
        return topTypes[x][z];
    }

    void setTopType(int x, int z, int type) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return;
        if (type < BLOCK_GRASS || type > BLOCK_OBSIDIAN) type = BLOCK_GRASS;
        topTypes[x][z] = (byte) type;
    }

    void setHeight(int x, int z, int height) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return;
        if (height < 1) height = 1;
        if (height > HEIGHT - 3) height = HEIGHT - 3;
        heights[x][z] = (short) height;
    }

    /** Returns the block occupying a vertical side-wall cell. */
    int getBlockTypeAt(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0) return BLOCK_BEDROCK;
        if (y >= heights[x][z] - 1) return topTypes[x][z];
        return getSubsurfaceType(x, y, z);
    }

    /** Geological block below the current top; deterministic and sparse. */
    int getSubsurfaceType(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0) return BLOCK_BEDROCK;
        int bedrockTop = 1 + (hash(x + 73, z - 41) & 3);
        if (y <= bedrockTop) return BLOCK_BEDROCK;

        int depth = originalHeights[x][z] - y;
        if (depth <= 3) return BLOCK_DIRT;
        int oreHash = hash(x + y * 37 + 101, z - y * 19 - 53) & 2047;
        if (y < 18 && oreHash < 4) return BLOCK_DIAMOND_ORE;
        if (y < 27 && oreHash < 15) return BLOCK_GOLD_ORE;
        if (oreHash < 58) return BLOCK_IRON_ORE;
        if (oreHash < 170) return BLOCK_COAL_ORE;
        return BLOCK_STONE;
    }
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

    /** True when a generated canopy leaf still occupies this block cell. */
    boolean isLeafBlockAt(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0 || y >= HEIGHT) return false;
        int id = leafId(x, y, z);
        for (int i = 0; i < removedLeafCount; i++) if (removedLeafIds[i] == id) return false;
        for (int tx = Math.max(5, x - 2); tx <= Math.min(SIZE - 6, x + 2); tx++) {
            if (tx % 11 != 5) continue;
            for (int tz = Math.max(5, z - 2); tz <= Math.min(SIZE - 6, z + 2); tz++) {
                if (tz % 13 != 6 || !isTreeAt(tx, tz)) continue;
                int crown = getHeight(tx, tz) + getTreeHeight(tx, tz) - 1;
                for (int layer = 0; layer < 3; layer++) {
                    int leafY = crown - 1 + layer;
                    int radius = layer == 2 ? 1 : 2;
                    if (y != leafY || Math.abs(x - tx) > radius || Math.abs(z - tz) > radius) continue;
                    if (x == tx && z == tz && y <= crown) continue;
                    return true;
                }
            }
        }
        return false;
    }

    boolean removeLeafBlock(int x, int y, int z) {
        if (!isLeafBlockAt(x, y, z)) return false;
        if (removedLeafCount == removedLeafIds.length) {
            int[] expanded = new int[removedLeafIds.length * 2];
            System.arraycopy(removedLeafIds, 0, expanded, 0, removedLeafCount);
            removedLeafIds = expanded;
        }
        removedLeafIds[removedLeafCount++] = leafId(x, y, z);
        return true;
    }

    private int leafId(int x, int y, int z) {
        return y * SIZE * SIZE + z * SIZE + x;
    }

    int countTreeLeaves(int tx, int tz) {
        if (!isTreeAt(tx, tz)) return 0;
        int crown = getHeight(tx, tz) + getTreeHeight(tx, tz) - 1;
        int count = 0;
        for (int layer = 0; layer < 3; layer++) {
            int y = crown - 1 + layer;
            int radius = layer == 2 ? 1 : 2;
            for (int x = tx - radius; x <= tx + radius; x++) {
                for (int z = tz - radius; z <= tz + radius; z++) {
                    if (isLeafBlockAt(x, y, z)) count++;
                }
            }
        }
        return count;
    }

    /** Lightweight Indev-like shadow levels used as M3G vertex colors. */
    int getShadowShade(int x, int z, int y) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return 220;
        int overheadLeaves = 0;
        for (int above = y + 2; above <= y + 5 && above < HEIGHT; above++) {
            if (isLeafBlockAt(x, above, z)) overheadLeaves++;
        }
        int shade = 255;
        if (overheadLeaves >= 2) shade = 142;
        else if (overheadLeaves == 1) shade = 190;
        int h;
        h = getHeight(x - 1, z); if (h >= y + 2 && shade > 178) shade = 178;
        h = getHeight(x + 1, z); if (h >= y + 2 && shade > 178) shade = 178;
        h = getHeight(x, z - 1); if (h >= y + 2 && shade > 178) shade = 178;
        h = getHeight(x, z + 1); if (h >= y + 2 && shade > 178) shade = 178;
        return shade;
    }

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
