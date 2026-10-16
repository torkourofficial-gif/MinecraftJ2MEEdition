package mcj2me;

/**
 * Small deterministic height-map generator. No world-sized block array is
 * needed: each column stores only its surface height.
 */
final class TerrainGenerator {
    static final int SIZE = 128;
    static final int HEIGHT = 64;
    private final short[][] heights = new short[SIZE][SIZE];
    private final byte[][] topTypes = new byte[SIZE][SIZE];

    TerrainGenerator() {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                int broad = valueNoise(x, z, 32);
                int medium = valueNoise(x, z, 12);
                int fine = valueNoise(x, z, 4);
                int h = 27 + broad * 12 / 1024
                           + medium * 5 / 1024
                           + fine * 2 / 1024;
                if (h < 8) h = 8;
                if (h > HEIGHT - 8) h = HEIGHT - 8;
                heights[x][z] = (short) h;
            }
        }
    }

    int getHeight(int x, int z) {
        if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
            return 0;
        }
        return heights[x][z];
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
