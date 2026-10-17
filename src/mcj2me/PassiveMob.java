package mcj2me;

import java.util.Random;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Lightweight, low-poly passive animals for CLDC/M3G devices. */
final class PassiveMob {
    private static final int COUNT = 5;
    private static Appearance hurtAppearance;
    private final TerrainGenerator terrain;
    private final Group group = new Group();
    private final Random random = new Random();
    private final Mob[] mobs = new Mob[COUNT];
    private final Appearance[] appearances = new Appearance[3];
    private int tick;

    PassiveMob(Group parent, TerrainGenerator terrain, float startX, float startZ) throws Exception {
        this.terrain = terrain;
        parent.addChild(group);
        appearances[0] = textureAppearance(Image.createImage("/mcj2me/cow.png"));
        appearances[1] = textureAppearance(Image.createImage("/mcj2me/pig.png"));
        appearances[2] = textureAppearance(Image.createImage("/mcj2me/sheep.png"));
        getHurtAppearance();
        int[] types = {0, 1, 2, 0, 2};
        for (int i = 0; i < COUNT; i++) {
            int x = dryCoordinate((int)startX + random.nextInt(13) - 6);
            int z = dryCoordinate((int)startZ + random.nextInt(13) - 6);
            Appearance appearance = appearances[types[i]];
            Mesh mesh = createMobMesh(appearance, false);
            Mob mob = new Mob(mesh, appearance, x + 0.5f, z + 0.5f, random);
            mobs[i] = mob;
            group.addChild(mesh);
            mesh.setTranslation(mob.x, terrain.getHeight(x, z), mob.z);
        }
    }

    private int dryCoordinate(int value) {
        if (value < 3) value = 3;
        if (value > TerrainGenerator.SIZE - 4) value = TerrainGenerator.SIZE - 4;
        for (int n = 0; n < 8 && terrain.isWaterAt(value, value); n++) value++;
        if (value > TerrainGenerator.SIZE - 4) value = TerrainGenerator.SIZE - 4;
        return value;
    }

    void update(float playerX, float playerZ) {
        tick++;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive) continue;
            mob.update(terrain, random, tick, playerX, playerZ, false);
        }
    }

    boolean hit(float px, float py, float pz, float yaw, float pitch) {
        Mob target = null;
        float best = 4.6f;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive) continue;
            float score = hitDistance(px, py, pz, yaw, pitch, mob.x, terrain.getHeight((int)mob.x, (int)mob.z) + 0.85f, mob.z, 0.95f);
            if (score >= 0.0f && score < best) { best = score; target = mob; }
        }
        if (target == null) return false;
        target.health--;
        target.hurtTicks = 5;
        target.mesh.setAppearance(0, getHurtAppearance());
        if (target.health <= 0) {
            target.alive = false;
            group.removeChild(target.mesh);
        }
        return true;
    }

    static Appearance textureAppearance(Image source) {
        int sw = source.getWidth(), sh = source.getHeight();
        int cw = Math.min(8, sw), ch = Math.min(8, sh);
        int[] sample = new int[cw * ch];
        source.getRGB(sample, 0, cw, 0, 0, cw, ch);
        int[] pixels = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                pixels[y * 8 + x] = sample[(y * ch / 8) * cw + (x * cw / 8)];
            }
        }
        Image patch = Image.createRGBImage(pixels, 8, 8, true);
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, patch));
        texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        Appearance appearance = new Appearance();
        appearance.setTexture(0, texture);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        pm.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(pm);
        return appearance;
    }

    static Appearance getHurtAppearance() {
        if (hurtAppearance == null) {
            int[] red = {0xFFFF3030};
            Image image = Image.createRGBImage(red, 1, 1, true);
            Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, image));
            texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
            Appearance appearance = new Appearance();
            appearance.setTexture(0, texture);
            PolygonMode pm = new PolygonMode();
            pm.setCulling(PolygonMode.CULL_NONE);
            pm.setShading(PolygonMode.SHADE_FLAT);
            appearance.setPolygonMode(pm);
            hurtAppearance = appearance;
        }
        return hurtAppearance;
    }

    /** Build a single mesh from six cuboids; coordinates are in half-block units. */
    static Mesh createMobMesh(Appearance appearance, boolean humanoid) {
        int[][] boxes;
        if (humanoid) {
            boxes = new int[][] {
                {-1, 2, -1, 1, 6, 1}, {-1, 6, -1, 1, 8, 1},
                {-3, 2, -1, -2, 6, 1}, {2, 2, -1, 3, 6, 1},
                {-1, 0, -1, 0, 2, 1}, {0, 0, -1, 1, 2, 1}
            };
        } else {
            boxes = new int[][] {
                {-2, 2, -3, 2, 4, 3}, {-1, 3, -5, 1, 5, -3},
                {-2, 0, -2, -1, 2, -1}, {1, 0, -2, 2, 2, -1},
                {-2, 0, 1, -1, 2, 2}, {1, 0, 1, 2, 2, 2}
            };
        }
        int vertexCount = boxes.length * 24;
        short[] positions = new short[vertexCount * 3];
        short[] texCoords = new short[vertexCount * 2];
        int[] indices = new int[vertexCount];
        int[] strips = new int[boxes.length * 6];
        int v = 0, strip = 0;
        for (int b = 0; b < boxes.length; b++) {
            int[] q = boxes[b];
            int x0=q[0], y0=q[1], z0=q[2], x1=q[3], y1=q[4], z1=q[5];
            int[][] faces = {
                {x0,y0,z0, x0,y1,z0, x1,y0,z0, x1,y1,z0},
                {x1,y0,z1, x1,y1,z1, x0,y0,z1, x0,y1,z1},
                {x0,y0,z1, x0,y1,z1, x0,y0,z0, x0,y1,z0},
                {x1,y0,z0, x1,y1,z0, x1,y0,z1, x1,y1,z1},
                {x0,y1,z0, x0,y1,z1, x1,y1,z0, x1,y1,z1},
                {x0,y0,z1, x0,y0,z0, x1,y0,z1, x1,y0,z0}
            };
            for (int f = 0; f < faces.length; f++) {
                int[] face = faces[f];
                for (int c = 0; c < 4; c++) {
                    positions[v * 3] = (short)face[c * 3];
                    positions[v * 3 + 1] = (short)face[c * 3 + 1];
                    positions[v * 3 + 2] = (short)face[c * 3 + 2];
                    int[] uv = {0, 0, 0, 8, 8, 0, 8, 8};
                    texCoords[v * 2] = (short)uv[c * 2];
                    texCoords[v * 2 + 1] = (short)uv[c * 2 + 1];
                    indices[v] = v;
                    v++;
                }
                strips[strip++] = 4;
            }
        }
        VertexArray p = new VertexArray(vertexCount, 3, 2);
        p.set(0, vertexCount, positions);
        VertexArray t = new VertexArray(vertexCount, 2, 2);
        t.set(0, vertexCount, texCoords);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(p, 0.5f, null);
        buffer.setTexCoords(0, t, 1.0f, null);
        return new Mesh(buffer, new TriangleStripArray(indices, strips), appearance);
    }

    static float hitDistance(float px, float py, float pz, float yaw, float pitch,
                             float mx, float my, float mz, float radius) {
        float yr = (float)(yaw * Math.PI / 180.0);
        float pr = (float)(pitch * Math.PI / 180.0);
        float dx = (float)Math.sin(yr) * (float)Math.cos(pr);
        float dy = (float)Math.sin(pr);
        float dz = -(float)Math.cos(yr) * (float)Math.cos(pr);
        float tx = mx - px, ty = my - py, tz = mz - pz;
        float along = tx * dx + ty * dy + tz * dz;
        if (along < 0.0f || along > 4.6f) return -1.0f;
        float dist2 = tx * tx + ty * ty + tz * tz - along * along;
        return dist2 <= radius * radius ? along : -1.0f;
    }

    private static final class Mob {
        final Mesh mesh;
        final Appearance normal;
        float x, z, homeX, homeZ, dirX, dirZ;
        int health = 3, hurtTicks, wanderTicks;
        boolean alive = true;

        Mob(Mesh mesh, Appearance normal, float x, float z, Random random) {
            this.mesh = mesh; this.normal = normal;
            this.x = this.homeX = x; this.z = this.homeZ = z;
            chooseDirection(random);
        }

        void chooseDirection(Random random) {
            dirX = random.nextInt(3) - 1;
            dirZ = random.nextInt(3) - 1;
            if (dirX == 0.0f && dirZ == 0.0f) dirZ = 1.0f;
            wanderTicks = 25 + random.nextInt(45);
        }

        void update(TerrainGenerator terrain, Random random, int tick,
                     float playerX, float playerZ, boolean chase) {
            if (hurtTicks > 0 && --hurtTicks == 0) mesh.setAppearance(0, normal);
            if (wanderTicks-- <= 0) chooseDirection(random);
            float dx = dirX, dz = dirZ;
            float homeDx = x - homeX, homeDz = z - homeZ;
            if (homeDx * homeDx + homeDz * homeDz > 144.0f) { dx = -homeDx; dz = -homeDz; }
            float len = (float)Math.sqrt(dx * dx + dz * dz);
            if (len > 0.0f) { dx /= len; dz /= len; }
            float nx = x + dx * 0.025f, nz = z + dz * 0.025f;
            if (nx < 2 || nz < 2 || nx > TerrainGenerator.SIZE - 3 || nz > TerrainGenerator.SIZE - 3
                    || terrain.isWaterAt((int)nx, (int)nz)) {
                chooseDirection(random);
            } else {
                x = nx; z = nz;
            }
            mesh.setTranslation(x, terrain.getHeight((int)x, (int)z), z);
        }
    }
}
