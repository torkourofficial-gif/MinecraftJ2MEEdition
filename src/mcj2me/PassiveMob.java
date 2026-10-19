package mcj2me;

import java.util.Random;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Lightweight, low-poly passive animals for CLDC/M3G devices. */
final class PassiveMob {
    private static final int COUNT = 5;
    private static final float UNLOAD_DISTANCE_SQUARED = 1600.0f;
    private static Appearance hurtAppearance;
    private final TerrainGenerator terrain;
    private final Group group = new Group();
    private final Random random = new Random();
    private final Mob[] mobs = new Mob[COUNT];
    private final Appearance[] appearances = new Appearance[3];
    private int tick;
    private boolean deathEvent;
    private float deathX, deathY, deathZ;
    private int deathType = -1;

    PassiveMob(Group parent, TerrainGenerator terrain, float startX, float startZ) throws Exception {
        this.terrain = terrain;
        parent.addChild(group);
        appearances[0] = textureAppearance(ResourceImages.load("/mcj2me/cow.png"));
        appearances[1] = textureAppearance(ResourceImages.load("/mcj2me/pig.png"));
        appearances[2] = textureAppearance(ResourceImages.load("/mcj2me/sheep.png"));
        getHurtAppearance();
        int[] types = {0, 1, 2, 0, 2};
        for (int i = 0; i < COUNT; i++) {
            int x = (int)startX + random.nextInt(13) - 6;
            int z = (int)startZ + random.nextInt(13) - 6;
            if (x < 3) x = 3;
            if (x > TerrainGenerator.SIZE - 4) x = TerrainGenerator.SIZE - 4;
            if (z < 3) z = 3;
            if (z > TerrainGenerator.SIZE - 4) z = TerrainGenerator.SIZE - 4;
            for (int attempt = 0; attempt < 8 && terrain.isWaterAt(x, z); attempt++) {
                x = Math.min(TerrainGenerator.SIZE - 4, x + 1);
                z = Math.min(TerrainGenerator.SIZE - 4, z + 1);
            }
            Appearance appearance = appearances[types[i]];
            Mesh mesh = createMobMesh(appearance, types[i]);
            Mob mob = new Mob(mesh, appearance, x + 0.5f, z + 0.5f, types[i], random);
            mobs[i] = mob;
            group.addChild(mesh);
            mob.rendered = true;
            mesh.setTranslation(mob.x, terrain.getHeight(x, z), mob.z);
        }
    }


    void setEnabled(boolean enabled) { group.setRenderingEnable(enabled); }

    void update(float playerX, float playerZ) {
        tick++;
        float unloadDistanceSquared = UNLOAD_DISTANCE_SQUARED;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive) continue;
            float dx = mob.x - playerX, dz = mob.z - playerZ;
            if (dx * dx + dz * dz > unloadDistanceSquared) {
                if (mob.rendered) {
                    group.removeChild(mob.mesh);
                    mob.rendered = false;
                }
                continue; // No animation, terrain queries, or AI while unloaded.
            }
            if (!mob.rendered) {
                group.addChild(mob.mesh);
                mob.rendered = true;
                mob.mesh.setTranslation(mob.x, terrain.getHeight((int)mob.x, (int)mob.z), mob.z);
            }
            mob.update(terrain, random, tick, playerX, playerZ, false);
        }
    }

    boolean hit(float px, float py, float pz, float yaw, float pitch) {
        Mob target = null;
        float best = 4.6f;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive || !mob.rendered) continue;
            float score = hitDistance(px, py, pz, yaw, pitch, mob.x, terrain.getHeight((int)mob.x, (int)mob.z) + 0.85f, mob.z, 0.95f);
            if (score >= 0.0f && score < best) { best = score; target = mob; }
        }
        if (target == null) return false;
        target.health--;
        target.hurtTicks = 5;
        target.mesh.setAppearance(0, getHurtAppearance());
        if (target.health <= 0) {
            target.alive = false;
            if (target.rendered) group.removeChild(target.mesh);
            target.rendered = false;
            deathX = target.x;
            deathZ = target.z;
            deathY = terrain.getHeight((int)target.x, (int)target.z) + target.jumpY + 0.45f;
            deathType = target.mobType;
            deathEvent = true;
        }
        return true;
    }

    boolean consumeDeathEvent() {
        if (!deathEvent) return false;
        deathEvent = false;
        return true;
    }

    float getDeathX() { return deathX; }
    float getDeathY() { return deathY; }
    float getDeathZ() { return deathZ; }
    int getDeathType() { return deathType; }

    static final int MOB_COW = 0;
    static final int MOB_PIG = 1;
    static final int MOB_SHEEP = 2;
    static final int MOB_ZOMBIE = 3;

    static Appearance textureAppearance(Image source) {
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, source));
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

    /** Build low-poly cuboids using the classic 64x32 mob skin atlas. */
    static Mesh createMobMesh(Appearance appearance, int mobType) {
        int[][] boxes;
        int[] parts;
        if (mobType == MOB_ZOMBIE) {
            // Classic 64x32 skin proportions in pixels; 16 model pixels = 1 block.
            boxes = new int[][] {
                {-4, 12, -2, 4, 24, 2}, {-4, 24, -4, 4, 32, 4},
                {-6, 12, -2, -2, 24, 2}, {2, 12, -2, 6, 24, 2},
                {-4, 0, -2, 0, 12, 2}, {0, 0, -2, 4, 12, 2}
            };
            parts = new int[] {1, 0, 4, 2, 5, 3};
        } else if (mobType == MOB_COW) {
            // Vanilla Java geometry: body is a 12x18x10 cube rotated 90 degrees on X.
            // Head uses its original 8x8x6 dimensions and sits low at the front.
            boxes = new int[][] {
                {-6, -10, -7, 6, 8, 3}, {-4, 16, -14, 4, 24, -8},
                {-6, 0, -7, -2, 12, -3}, {2, 0, -7, 6, 12, -3},
                {-6, 0, 5, -2, 12, 9}, {2, 0, 5, 6, 12, 9}
            };
            parts = new int[] {1, 0, 2, 2, 2, 2};
        } else if (mobType == MOB_PIG) {
            // Pig: classic torso is 10x8x16 after the original 90-degree body rotation.
            // Keep its short legs at the front and rear corners, not close together.
            boxes = new int[][] {
                {-5, 5, -8, 5, 13, 8}, {-4, 6, -11, 4, 14, -3},
                {-5, 0, -7, -1, 6, -3}, {1, 0, -7, 5, 6, -3},
                {-5, 0, 5, -1, 6, 9}, {1, 0, 5, 5, 6, 9}
            };
            parts = new int[] {1, 0, 2, 2, 2, 2};
        } else {
            // Vanilla sheep body plus its wool layer: wider than the bare body.
            // The original torso is rotated on X; the head is 6x6x8 and lower than the cow's.
            boxes = new int[][] {
                {-6, -10, -9, 6, 8, 1}, {-3, 16, -14, 3, 22, -6},
                {-6, 0, -7, -2, 12, -3}, {2, 0, -7, 6, 12, -3},
                {-6, 0, 5, -2, 12, 9}, {2, 0, 5, 6, 12, 9}
            };
            parts = new int[] {1, 0, 2, 2, 2, 2};
        }

        int vertexCount = boxes.length * 24;
        short[] positions = new short[vertexCount * 3];
        short[] texCoords = new short[vertexCount * 2];
        int[] indices = new int[vertexCount];
        int[] strips = new int[boxes.length * 6];
        int v = 0, strip = 0;
        for (int boxIndex = 0; boxIndex < boxes.length; boxIndex++) {
            int[] q = boxes[boxIndex];
            int x0=q[0], y0=q[1], z0=q[2], x1=q[3], y1=q[4], z1=q[5];
            int[][] faces = {
                {x0,y0,z0, x0,y1,z0, x1,y0,z0, x1,y1,z0},
                {x1,y0,z1, x1,y1,z1, x0,y0,z1, x0,y1,z1},
                {x0,y0,z1, x0,y1,z1, x0,y0,z0, x0,y1,z0},
                {x1,y0,z0, x1,y1,z0, x1,y0,z1, x1,y1,z1},
                {x0,y1,z0, x0,y1,z1, x1,y1,z0, x1,y1,z1},
                {x0,y0,z1, x0,y0,z0, x1,y0,z1, x1,y0,z0}
            };
            int[][] uv = getFaceUvs(mobType, parts[boxIndex]);
            for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
                int[] face = faces[faceIndex];
                int[] rect = uv[faceIndex];
                for (int corner = 0; corner < 4; corner++) {
                    int px = face[corner * 3];
                    int py = face[corner * 3 + 1];
                    int pz = face[corner * 3 + 2];
                    // Rotate the cow/sheep torso vertices around X, matching the original model.
                    // UVs are calculated before this transform, so their faces rotate with the cube.
                    if (boxIndex == 0 && (mobType == MOB_COW || mobType == MOB_SHEEP)) {
                        int oldY = py;
                        py = 19 + pz;
                        pz = oldY + 2;
                    }
                    // Classic Minecraft zombie pose: arms held forward horizontally.
                    if (mobType == MOB_ZOMBIE && (boxIndex == 2 || boxIndex == 3)) {
                        int oldY = py;
                        py = 24 - pz;
                        pz = oldY - 24;
                    }
                    positions[v * 3] = (short)px;
                    positions[v * 3 + 1] = (short)py;
                    positions[v * 3 + 2] = (short)pz;
                    int u, vv;
                    if (faceIndex == 4) {
                        u = (corner == 0 || corner == 1) ? rect[0] : rect[2];
                        vv = (corner == 0 || corner == 2) ? rect[3] : rect[1];
                    } else if (faceIndex == 5) {
                        u = (corner == 0 || corner == 2) ? rect[2] : rect[0];
                        vv = (corner == 0 || corner == 1) ? rect[1] : rect[3];
                    } else {
                        u = (corner == 0 || corner == 1) ? rect[0] : rect[2];
                        vv = (corner == 0 || corner == 2) ? rect[3] : rect[1];
                        if (faceIndex == 1 || faceIndex == 2) {
                            u = (corner == 0 || corner == 1) ? rect[2] : rect[0];
                        }
                    }
                    texCoords[v * 2] = (short)(u * 1024 / 64);
                    texCoords[v * 2 + 1] = (short)(vv * 1024 / 32);
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
        buffer.setPositions(p, 1.0f / 16.0f, null);
        buffer.setTexCoords(0, t, 1.0f / 1024.0f, null);
        Mesh result = new Mesh(buffer, new TriangleStripArray(indices, strips), appearance);
        // Match adult Java Edition proportions while preserving the classic pixel-model silhouettes.
        if (mobType == MOB_COW) result.setScale(1.0f, 1.0f, 1.0f);
        else if (mobType == MOB_PIG) result.setScale(1.0f, 1.03f, 1.0f);  // preserve the pig scale
        else if (mobType == MOB_SHEEP) result.setScale(1.0f, 1.0f, 1.0f);
        else if (mobType == MOB_ZOMBIE) result.setScale(0.80f, 0.975f, 0.80f); // ~0.6 x 1.95 blocks
        return result;
    }

    /** Returns UV rectangles for front, back, left, right, top and bottom faces. */
    private static int[][] getFaceUvs(int mobType, int part) {
        int x, y, w, h, d;
        if (mobType == MOB_ZOMBIE) {
            if (part == 0) { x=0; y=0; w=8; h=8; d=8; }          // head
            else if (part == 1) { x=16; y=16; w=8; h=12; d=4; } // torso
            else if (part == 2) { x=32; y=16; w=4; h=12; d=4; }// right arm: same texture as left arm
            else if (part == 4) { x=32; y=16; w=4; h=12; d=4; }// left arm
            else if (part == 5) { x=16; y=16; w=4; h=12; d=4; }// left leg
            else { x=16; y=16; w=4; h=12; d=4; }               // right leg: same texture as left leg
        } else if (mobType == MOB_COW) {
            if (part == 0) { x=0; y=0; w=8; h=8; d=6; }          // head
            else if (part == 1) { x=18; y=4; w=12; h=18; d=10; }// body
            else { x=0; y=16; w=4; h=12; d=4; }                // legs
        } else if (mobType == MOB_PIG) {
            if (part == 0) { x=0; y=0; w=8; h=8; d=8; }          // head
            else if (part == 1) { x=28; y=8; w=10; h=8; d=8; }// body
            else { x=0; y=16; w=4; h=6; d=4; }                  // legs
        } else {
            if (part == 0) { x=0; y=0; w=6; h=6; d=8; }          // classic sheep head is 6x6x8
            else if (part == 1) { x=28; y=8; w=8; h=16; d=6; }// body
            else { x=0; y=16; w=4; h=12; d=4; }                // legs
        }
        int[][] rects = new int[6][4];
        rects[0] = new int[] {x+d, y+d, x+d+w, y+d+h};
        rects[1] = new int[] {x+d+w+d, y+d, x+d+w+d+w, y+d+h};
        rects[2] = new int[] {x, y+d, x+d, y+d+h};
        rects[3] = new int[] {x+d+w, y+d, x+d+w+d, y+d+h};
        rects[4] = new int[] {x+d, y, x+d+w, y+d};
        rects[5] = new int[] {x+d+w, y, x+d+w+w, y+d};
        return rects;
    }

    static float directionAngle(float dx, float dz) {
        if (dx > -0.001f && dx < 0.001f &&
            dz > -0.001f && dz < 0.001f) {
            return 0.0f;
        }

        // CLDC 1.1 has no Math.atan2(), so approximate with eight directions.
        float ax = dx < 0.0f ? -dx : dx;
        float az = dz < 0.0f ? -dz : dz;

        if (ax < az * 0.4142f) {
            return dz < 0.0f ? 0.0f : 180.0f;
        }

        if (az < ax * 0.4142f) {
            return dx > 0.0f ? -90.0f : 90.0f;
        }

        if (dx > 0.0f) {
            return dz < 0.0f ? -45.0f : -135.0f;
        }

        return dz < 0.0f ? 45.0f : 135.0f;
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
        float jumpY = 0.0f, jumpVelocity = 0.0f;
        int health = 3, hurtTicks, wanderTicks, hopCooldown;
        final int mobType;
        boolean alive = true;
        boolean rendered;

        Mob(Mesh mesh, Appearance normal, float x, float z, int mobType, Random random) {
            this.mesh = mesh; this.normal = normal; this.mobType = mobType;
            this.x = this.homeX = x; this.z = this.homeZ = z;
            hopCooldown = 4 + random.nextInt(24);
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
            if (len > 0.0f) mesh.setOrientation(directionAngle(dx, dz), 0.0f, 1.0f, 0.0f);

            // Indev-style animal chaos: frequent little hops, with a springy landing.
            if (hopCooldown > 0) hopCooldown--;
            if (hopCooldown <= 0 && jumpY <= 0.001f && random.nextInt(5) != 0) {
                jumpVelocity = 0.105f + random.nextInt(65) * 0.001f;
                hopCooldown = 5 + random.nextInt(24);
            }
            if (jumpY > 0.0f || jumpVelocity > 0.0f) {
                jumpY += jumpVelocity;
                jumpVelocity -= 0.018f;
                if (jumpY <= 0.0f) {
                    jumpY = 0.0f;
                    jumpVelocity = 0.0f;
                }
            }
            float bob = (float)Math.sin(tick * 0.22f) * (len > 0.0f ? 0.012f : 0.003f);
            mesh.setTranslation(x, terrain.getHeight((int)x, (int)z) + bob + jumpY, z);
        }
    }
}
