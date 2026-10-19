package mcj2me;

import java.util.Random;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

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
    // Immutable generated surface material; player edits must not rewrite geology below it.
    private final byte[][] originalTopTypes = new byte[SIZE][SIZE];
    private final boolean[][] waterBasins = new boolean[SIZE][SIZE];
    // Player-placed water uses a per-column surface level; zero means dry.
    private final short[][] placedWaterLevels = new short[SIZE][SIZE];
    // Sparse storage avoids a full 128x64x128 voxel array on the MIDP heap.
    private int[] removedLeafIds = new int[64];
    private int removedLeafCount;
    // Sparse per-voxel edits: mining one block never shifts the rest of its column.
    private int[] removedBlockIds = new int[64];
    private int removedBlockCount;
    private int[] placedBlockIds = new int[64];
    private byte[] placedBlockTypes = new byte[64];
    private int placedBlockCount;
    private int[] removedTreeIds = new int[16];
    private int removedTreeCount;
    // Each mined log is saved independently, using the same packed voxel ID as leaves.
    private int[] removedTreeBlockIds = new int[32];
    private int removedTreeBlockCount;
    // 0=unknown, 1=tree, -1=no tree. Tiny cache for the regular tree grid.
    private final byte[][] treeAtCache = new byte[SIZE][SIZE];
    private final int seed;

    TerrainGenerator() {
        this(makeSeed());
    }

    TerrainGenerator(int worldSeed) {
        seed = worldSeed;
        Random random = new Random(seed);

        // Generate natural terrain, cut caves first, then carve/fill lakes last.
        // This lets the basin geometry overwrite cave cuts wherever they overlap.
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
        generateCaveSystems(random);
        generateWaterBasinStructures(random);
        lowerWorldEdgesTowardSea();
        for (int x = 0; x < SIZE; x++) {
            System.arraycopy(heights[x], 0, originalHeights[x], 0, SIZE);
            System.arraycopy(topTypes[x], 0, originalTopTypes[x], 0, SIZE);
        }
    }

    private static int makeSeed() {
        long now = System.currentTimeMillis();
        return (int)(now ^ (now >>> 32) ^ new Random(now).nextInt());
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
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return false;
        return (waterBasins[x][z] && heights[x][z] < WATER_LEVEL)
            || placedWaterLevels[x][z] > heights[x][z];
    }

    /** Surface Y of the water column, or -1 for a dry column. */
    int getWaterSurfaceLevel(int x, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return -1;
        if (waterBasins[x][z] && heights[x][z] < WATER_LEVEL) return WATER_LEVEL;
        int level = placedWaterLevels[x][z];
        return level > heights[x][z] ? level : -1;
    }

    void setPlacedWaterLevel(int x, int z, int level) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return;
        if (level < 0) level = 0;
        if (level > HEIGHT - 1) level = HEIGHT - 1;
        placedWaterLevels[x][z] = (short) level;
    }

    int getPlacedWaterLevel(int x, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return 0;
        int level = placedWaterLevels[x][z];
        return level > heights[x][z] ? level : 0;
    }

    /**
     * Tests one water block cell. Water is traversable, but its occupied
     * volume is discrete: x/z selects a column and y selects a block layer.
     */
    boolean isWaterBlockAt(int x, int y, int z) {
        int surface = getWaterSurfaceLevel(x, z);
        return surface > 0 && y >= heights[x][z] && y < surface;
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
    // IDs 16-20 are reserved for WorldCanvas decorations; solid additions start at 21.
    static final int BLOCK_IRON_BLOCK = 21;
    static final int BLOCK_GOLD_BLOCK = 22;
    static final int BLOCK_DIAMOND_BLOCK = 23;
    static final int BLOCK_WOOL_WHITE = 24;
    static final int BLOCK_WOOL_ORANGE = 25;
    static final int BLOCK_WOOL_MAGENTA = 26;
    static final int BLOCK_WOOL_LIGHT_BLUE = 27;
    static final int BLOCK_WOOL_YELLOW = 28;
    static final int BLOCK_WOOL_LIME = 29;
    static final int BLOCK_WOOL_PINK = 30;
    static final int BLOCK_WOOL_GRAY = 31;
    static final int BLOCK_WOOL_LIGHT_GRAY = 32;
    static final int BLOCK_WOOL_CYAN = 33;
    static final int BLOCK_WOOL_PURPLE = 34;
    static final int BLOCK_WOOL_BLUE = 35;
    static final int BLOCK_WOOL_BROWN = 36;
    static final int BLOCK_WOOL_GREEN = 37;
    static final int BLOCK_WOOL_RED = 38;
    static final int BLOCK_WOOL_BLACK = 39;
    // Stored-world voxel ID; separate from crafts.CRAFTING_TABLE (inventory item ID).
    static final int BLOCK_CRAFTING_TABLE = 40;

    int getTopType(int x, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return BLOCK_BEDROCK;
        return topTypes[x][z];
    }

    void setTopType(int x, int z, int type) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return;
        if (type < BLOCK_GRASS || type > BLOCK_WOOL_BLACK) type = BLOCK_GRASS;
        topTypes[x][z] = (byte) type;
        invalidateTreeCache(x, z);
    }

    private void invalidateTreeCache(int x, int z) {
        for (int tx = Math.max(0, x - 4); tx <= Math.min(SIZE - 1, x + 4); tx++) {
            for (int tz = Math.max(0, z - 4); tz <= Math.min(SIZE - 1, z + 4); tz++) {
                treeAtCache[tx][tz] = 0;
            }
        }
    }

    void setHeight(int x, int z, int height) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return;
        if (height < 1) height = 1;
        if (height > HEIGHT - 3) height = HEIGHT - 3;
        heights[x][z] = (short) height;
        // Tree spacing is checked across a 7x7 neighbourhood, so terrain
        // edits invalidate every nearby cached candidate, not just adjacent cells.
        invalidateTreeCache(x, z);
    }

    int getPlacedBlockCount() { return placedBlockCount; }
    int getPlacedBlockIdAtIndex(int index) { return placedBlockIds[index]; }
    int getPlacedBlockTypeAtIndex(int index) { return placedBlockTypes[index] & 255; }
    int getPlacedBlockTypeAt(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0 || y >= HEIGHT) return -1;
        int id = blockId(x, y, z);
        for (int i = 0; i < placedBlockCount; i++) if (placedBlockIds[i] == id) return placedBlockTypes[i] & 255;
        return -1;
    }
    boolean setPlacedBlockAt(int x, int y, int z, int type) {
        if (x < 1 || z < 1 || x >= SIZE - 1 || z >= SIZE - 1 || y < 0 || y >= HEIGHT) return false;
        if ((type < BLOCK_GRASS || type > BLOCK_WOOL_BLACK) && type != BLOCK_CRAFTING_TABLE) return false;
        if (type >= 16 && type <= 20) return false;
        if (getBlockTypeAt(x, y, z) >= 0) return false;
        if (placedBlockCount == placedBlockIds.length) {
            int size = placedBlockIds.length * 2;
            int[] ids = new int[size]; byte[] types = new byte[size];
            System.arraycopy(placedBlockIds, 0, ids, 0, placedBlockCount);
            System.arraycopy(placedBlockTypes, 0, types, 0, placedBlockCount);
            placedBlockIds = ids; placedBlockTypes = types;
        }
        placedBlockIds[placedBlockCount] = blockId(x, y, z);
        placedBlockTypes[placedBlockCount++] = (byte) type;
        return true;
    }
    boolean removePlacedBlockAt(int x, int y, int z) {
        int id = blockId(x, y, z);
        for (int i = 0; i < placedBlockCount; i++) if (placedBlockIds[i] == id) {
            int last = --placedBlockCount;
            placedBlockIds[i] = placedBlockIds[last]; placedBlockTypes[i] = placedBlockTypes[last];
            return true;
        }
        return false;
    }

    /** True when this exact XYZ voxel has been mined. */
    boolean isBlockRemoved(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0 || y >= HEIGHT) return false;
        int id = blockId(x, y, z);
        for (int i = 0; i < removedBlockCount; i++)
            if (removedBlockIds[i] == id) return true;
        return false;
    }

    /** Removes one voxel without changing any other block in its column. */
    boolean removeBlockAt(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0 || y >= heights[x][z]) return false;
        int type = getBlockTypeAt(x, y, z);
        if (type < 0 || type == BLOCK_BEDROCK) return false;
        if (removedBlockCount == removedBlockIds.length) {
            int[] expanded = new int[removedBlockIds.length * 2];
            System.arraycopy(removedBlockIds, 0, expanded, 0, removedBlockCount);
            removedBlockIds = expanded;
        }
        removedBlockIds[removedBlockCount++] = blockId(x, y, z);
        return true;
    }

    private int blockId(int x, int y, int z) {
        return y * SIZE * SIZE + z * SIZE + x;
    }

    /** Returns the block occupying a wall cell, or -1 when that voxel was removed. */
    int getBlockTypeAt(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0) return BLOCK_BEDROCK;
        int placedType = getPlacedBlockTypeAt(x, y, z);
        if (placedType >= 0) return placedType;
        // Cells at or above the current column height are air, not copies of
        // the surface block. This matters for two-block player clearance.
        if (y >= heights[x][z]) return -1;
        if (isBlockRemoved(x, y, z)) return -1;
        if (y == heights[x][z] - 1) return topTypes[x][z];
        return getSubsurfaceType(x, y, z);
    }

    /** Geological block below the current top; deterministic and sparse. */
    int getSubsurfaceType(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0) return BLOCK_BEDROCK;
        int bedrockTop = 1 + (hash(x + 73, z - 41) & 3);
        if (y <= bedrockTop) return BLOCK_BEDROCK;

        int depth = originalHeights[x][z] - y;
        // Dirt layers belong only beneath a grass surface; other surface
        // materials must not turn their entire subsurface into dirt.
        if (originalTopTypes[x][z] == BLOCK_GRASS && depth > 0 && depth <= 3)
            return BLOCK_DIRT;
        int oreHash = hash(x + y * 37 + 101, z - y * 19 - 53) & 2047;
        if (y < 18 && oreHash < 4) return BLOCK_DIAMOND_ORE;
        if (y < 27 && oreHash < 15) return BLOCK_GOLD_ORE;
        if (oreHash < 58) return BLOCK_IRON_ORE;
        if (oreHash < 170) return BLOCK_COAL_ORE;
        return BLOCK_STONE;
    }
    private boolean isTreeCandidate(int x, int z) {
        if (x < 4 || z < 4 || x >= SIZE - 4 || z >= SIZE - 4) return false;
        // Tree placement is decided from the original generated terrain only.
        // Mining or replacing the supporting surface must not regenerate/move
        // the tree or make its trunk and canopy vanish.
        int h = originalHeights[x][z];
        if (h < 12 || h > HEIGHT - 9 || originalTopTypes[x][z] != BLOCK_GRASS
                || (waterBasins[x][z] && h < WATER_LEVEL)) return false;
        int min = h, max = h, n;
        n = originalHeights[x - 1][z]; if (n < min) min = n; if (n > max) max = n;
        n = originalHeights[x + 1][z]; if (n < min) min = n; if (n > max) max = n;
        n = originalHeights[x][z - 1]; if (n < min) min = n; if (n > max) max = n;
        n = originalHeights[x][z + 1]; if (n < min) min = n; if (n > max) max = n;
        return max - min <= 2 && (hash(x * 17 + 31, z * 23 - 19) & 63) < 3;
    }

    boolean isTreeAt(int x, int z) {
        if (x < 4 || z < 4 || x >= SIZE - 4 || z >= SIZE - 4) return false;
        int treeId = x * SIZE + z;
        for (int i = 0; i < removedTreeCount; i++) if (removedTreeIds[i] == treeId) return false;
        byte cached = treeAtCache[x][z];
        if (cached != 0) return cached > 0;
        boolean result = isTreeCandidate(x, z);
        if (result) {
            // Deterministic spacing, but without a visible grid: the candidate
            // with the lowest hash wins in a 5x5 neighbourhood.
            int priority = hash(x + 127, z - 83);
            for (int nx = Math.max(4, x - 3); nx <= Math.min(SIZE - 5, x + 3) && result; nx++) {
                for (int nz = Math.max(4, z - 3); nz <= Math.min(SIZE - 5, z + 3); nz++) {
                    if (nx == x && nz == z) continue;
                    if (isTreeCandidate(nx, nz) && hash(nx + 127, nz - 83) < priority) {
                        result = false;
                        break;
                    }
                }
            }
        }
        treeAtCache[x][z] = result ? (byte) 1 : (byte) -1;
        return result;
    }

    boolean removeTreeAt(int x, int z) {
        if (!isTreeAt(x, z)) return false;
        int id = x * SIZE + z;
        if (removedTreeCount == removedTreeIds.length) {
            int[] expanded = new int[removedTreeIds.length * 2];
            System.arraycopy(removedTreeIds, 0, expanded, 0, removedTreeIds.length);
            removedTreeIds = expanded;
        }
        removedTreeIds[removedTreeCount++] = id;
        treeAtCache[x][z] = -1;
        return true;
    }

    /** Fixed spawn-time Y of the procedural tree, unaffected by terrain edits. */
    int getTreeBaseHeight(int x, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) return 0;
        return originalHeights[x][z];
    }

    /** True when this exact generated log voxel still exists. */
    boolean isTreeTrunkAt(int x, int y, int z) {
        if (x < 4 || z < 4 || x >= SIZE - 4 || z >= SIZE - 4 || y < 0 || y >= HEIGHT) return false;
        if (!isTreeAt(x, z)) return false;
        int base = getTreeBaseHeight(x, z);
        if (y < base || y >= base + getTreeHeight(x, z)) return false;
        int id = leafId(x, y, z);
        for (int i = 0; i < removedTreeBlockCount; i++) if (removedTreeBlockIds[i] == id) return false;
        return true;
    }

    /** Remove only one log voxel; the rest of the trunk and canopy remain. */
    boolean removeTreeBlockAt(int x, int y, int z) {
        if (!isTreeTrunkAt(x, y, z)) return false;
        int id = leafId(x, y, z);
        if (removedTreeBlockCount == removedTreeBlockIds.length) {
            int[] expanded = new int[removedTreeBlockIds.length * 2];
            System.arraycopy(removedTreeBlockIds, 0, expanded, 0, removedTreeBlockCount);
            removedTreeBlockIds = expanded;
        }
        removedTreeBlockIds[removedTreeBlockCount++] = id;
        return true;
    }

    int getTreeHeight(int x, int z) {
        // Natural variation similar to early Indev trees: short and tall trunks.
        return 3 + ((hash(x + 19, z - 7) >>> 3) & 1)
                 + (((hash(x - 11, z + 29) >>> 5) & 7) == 0 ? 1 : 0);
    }

    /** Sparse yellow/red flowers; never on sand, dirt, stone or water. */
    int getFlowerType(int x, int z) {
        if (x < 1 || z < 1 || x >= SIZE - 1 || z >= SIZE - 1) return -1;
        if (getTopType(x, z) != BLOCK_GRASS || isWaterAt(x, z) || isTreeAt(x, z)) return -1;
        int chance = hash(x * 29 + 53, z * 31 - 17) & 63;
        if (chance > 2) return -1;
        // Avoid a flower on a steep edge or directly under a tree canopy.
        int h = getHeight(x, z);
        if (isLeafBlockAt(x, h, z)) return -1;
        if (Math.abs(h - getHeight(x - 1, z)) > 1 ||
            Math.abs(h - getHeight(x + 1, z)) > 1 ||
            Math.abs(h - getHeight(x, z - 1)) > 1 ||
            Math.abs(h - getHeight(x, z + 1)) > 1) return -1;
        return (hash(x - 41, z + 67) & 1) == 0 ? 17 : 18;
    }

    /** True when a generated canopy leaf still occupies this block cell. */
    boolean isLeafBlockAt(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0 || y >= HEIGHT) return false;
        // A canopy leaf must never occupy a cell already filled by terrain.
        if (getBlockTypeAt(x, y, z) >= 0) return false;
        int id = leafId(x, y, z);
        for (int i = 0; i < removedLeafCount; i++) if (removedLeafIds[i] == id) return false;
        for (int tx = Math.max(4, x - 2); tx <= Math.min(SIZE - 5, x + 2); tx++) {
            for (int tz = Math.max(4, z - 2); tz <= Math.min(SIZE - 5, z + 2); tz++) {
                if (!isTreeAt(tx, tz)) continue;
                int crown = getTreeBaseHeight(tx, tz) + getTreeHeight(tx, tz) - 1;
                int layer = y - (crown - 1);
                if (layer < 0 || layer > 2) continue;
                int radius = layer == 2 ? 1 : 2;
                if (Math.abs(x - tx) > radius || Math.abs(z - tz) > radius) continue;
                if (x == tx && z == tz && y <= crown) continue;
                return true;
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

    /** Serialize mutable terrain data; immutable strata are regenerated from the saved seed. */
    byte[] saveState() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) out.writeShort(heights[x][z]);
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) out.writeByte(topTypes[x][z]);
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) out.writeBoolean(waterBasins[x][z]);
        out.writeInt(removedLeafCount);
        for (int i = 0; i < removedLeafCount; i++) out.writeInt(removedLeafIds[i]);
        // Appended to keep old save records readable.
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) out.writeShort(placedWaterLevels[x][z]);
        // Optional appended sparse block edits; older saves remain compatible.
        out.writeInt(removedBlockCount);
        for (int i = 0; i < removedBlockCount; i++) out.writeInt(removedBlockIds[i]);
        out.writeInt(placedBlockCount);
        for (int i = 0; i < placedBlockCount; i++) { out.writeInt(placedBlockIds[i]); out.writeByte(placedBlockTypes[i]); }
        out.writeInt(removedTreeCount);
        for (int i = 0; i < removedTreeCount; i++) out.writeInt(removedTreeIds[i]);
        // Optional per-log removals; appended so older save formats still load.
        out.writeInt(removedTreeBlockCount);
        for (int i = 0; i < removedTreeBlockCount; i++) out.writeInt(removedTreeBlockIds[i]);
        out.flush();
        return bytes.toByteArray();
    }

    void loadState(byte[] data) throws Exception {
        if (data == null) return;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) heights[x][z] = in.readShort();
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) topTypes[x][z] = in.readByte();
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) waterBasins[x][z] = in.readBoolean();
        int count = in.readInt();
        if (count < 0 || count > SIZE * SIZE * HEIGHT) throw new Exception("Invalid saved leaf data");
        if (count > removedLeafIds.length) removedLeafIds = new int[count];
        removedLeafCount = count;
        for (int i = 0; i < count; i++) removedLeafIds[i] = in.readInt();
        // Older worlds end here; newer ones append the surface-water map.
        if (in.available() >= SIZE * SIZE * 2) {
            for (int x = 0; x < SIZE; x++)
                for (int z = 0; z < SIZE; z++) placedWaterLevels[x][z] = in.readShort();
        }
        if (in.available() >= 4) {
            int blockCount = in.readInt();
            if (blockCount < 0 || blockCount > SIZE * SIZE * HEIGHT) throw new Exception("Invalid saved block edits");
            if (blockCount > removedBlockIds.length) removedBlockIds = new int[blockCount];
            removedBlockCount = blockCount;
            for (int i = 0; i < blockCount; i++) removedBlockIds[i] = in.readInt();
        } else removedBlockCount = 0;
        placedBlockCount = 0;
        if (in.available() >= 4) {
            int placedCount = in.readInt();
            if (placedCount < 0 || placedCount > SIZE * SIZE * HEIGHT) throw new Exception("Invalid saved placed blocks");
            if (placedCount > placedBlockIds.length) { placedBlockIds = new int[placedCount]; placedBlockTypes = new byte[placedCount]; }
            for (int i = 0; i < placedCount; i++) {
                int id = in.readInt(); byte type = in.readByte();
                if (id < 0 || id >= SIZE * SIZE * HEIGHT) throw new Exception("Invalid placed block position");
                placedBlockIds[placedBlockCount] = id; placedBlockTypes[placedBlockCount++] = type;
            }
        }
        removedTreeCount = 0;
        if (in.available() >= 4) {
            int treeCount = in.readInt();
            if (treeCount < 0 || treeCount > SIZE * SIZE) throw new Exception("Invalid saved removed trees");
            if (treeCount > removedTreeIds.length) removedTreeIds = new int[treeCount];
            removedTreeCount = treeCount;
            for (int i = 0; i < treeCount; i++) removedTreeIds[i] = in.readInt();
        }
        removedTreeBlockCount = 0;
        if (in.available() >= 4) {
            int logCount = in.readInt();
            if (logCount < 0 || logCount > SIZE * SIZE * HEIGHT) throw new Exception("Invalid saved removed tree blocks");
            if (logCount > removedTreeBlockIds.length) removedTreeBlockIds = new int[logCount];
            removedTreeBlockCount = logCount;
            for (int i = 0; i < logCount; i++) removedTreeBlockIds[i] = in.readInt();
        }
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) treeAtCache[x][z] = 0;
        for (int i = 0; i < removedTreeCount; i++) {
            int tx = removedTreeIds[i] / SIZE, tz = removedTreeIds[i] % SIZE;
            if (tx >= 0 && tx < SIZE && tz >= 0 && tz < SIZE) treeAtCache[tx][tz] = -1;
        }
        in.close();
    }

    int countTreeLeaves(int tx, int tz) {
        if (!isTreeAt(tx, tz)) return 0;
        int crown = getTreeBaseHeight(tx, tz) + getTreeHeight(tx, tz) - 1;
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

        // Check nearby tree crowns once, instead of calling isLeafBlockAt four
        // times (each of which repeats the same tree-candidate search).
        int overheadLeaves = 0;
        int minTreeX = Math.max(5, x - 2);
        int maxTreeX = Math.min(SIZE - 6, x + 2);
        int minTreeZ = Math.max(5, z - 2);
        int maxTreeZ = Math.min(SIZE - 6, z + 2);
        for (int tx = minTreeX; tx <= maxTreeX; tx++) {
            
            for (int tz = minTreeZ; tz <= maxTreeZ; tz++) {
                if (!isTreeAt(tx, tz)) continue;
                int crown = getTreeBaseHeight(tx, tz) + getTreeHeight(tx, tz) - 1;
                for (int layer = 0; layer < 3; layer++) {
                    int leafY = crown - 1 + layer;
                    int radius = layer == 2 ? 1 : 2;
                    if (leafY < y + 2 || leafY > y + 5 || leafY >= HEIGHT) continue;
                    if (Math.abs(x - tx) > radius || Math.abs(z - tz) > radius) continue;
                    if (x == tx && z == tz && leafY <= crown) continue;
                    int id = leafId(x, leafY, z);
                    boolean removed = false;
                    for (int i = 0; i < removedLeafCount; i++) {
                        if (removedLeafIds[i] == id) { removed = true; break; }
                    }
                    if (!removed) overheadLeaves++;
                }
            }
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
