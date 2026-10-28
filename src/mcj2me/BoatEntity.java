package mcj2me;

import javax.microedition.m3g.*;

/**
 * Lightweight single-player boat based on the Alpha 1.2.6 EntityBoat model.
 * Geometry and appearance are shared by every boat; only this node's transform
 * and its small physics state change while playing.
 */
final class BoatEntity {
    private final Mesh mesh;
    private float x, y, z;
    private float velocityX, velocityZ;
    private float yaw;
    private boolean alive = true;
    private int damage;

    BoatEntity(World world, Appearance appearance, float x, float y, float z, float yaw) {
        mesh = createMesh(appearance);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        mesh.setTranslation(x, y, z);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
        world.addChild(mesh);
    }

    boolean isAlive() { return alive; }
    float getX() { return x; }
    float getY() { return y; }
    float getZ() { return z; }
    float getYaw() { return yaw; }

    /** Alpha-style buoyancy, inertia, a 0.4 block/tick speed cap and steering. */
    void tick(TerrainGenerator terrain, boolean forward, boolean backward,
              boolean left, boolean right, float playerYaw) {
        if (!alive) return;

        int ix = (int)x;
        int iz = (int)z;
        float water = terrain.getWaterSurfaceLevel(ix, iz);
        if (water > 0 && water > terrain.getHeight(ix, iz)) {
            // The original samples five horizontal slices; a compact surface
            // approximation gives the same stable floating behavior in this engine.
            float targetY = water - 0.22f;
            velocityYStep(targetY);
        } else {
            y -= 0.035f;
            if (y < terrain.getHeight(ix, iz) + 0.25f) {
                y = terrain.getHeight(ix, iz) + 0.25f;
                velocityX *= 0.5f;
                velocityZ *= 0.5f;
            }
        }

        if (forward || backward) {
            float direction = forward ? 1.0f : -1.0f;
            float radians = playerYaw * 0.0174532925f;
            velocityX += (float)Math.sin(radians) * direction * 0.035f;
            velocityZ -= (float)Math.cos(radians) * direction * 0.035f;
        }
        float speed = (float)Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        if (speed > 0.4f) {
            velocityX = velocityX / speed * 0.4f;
            velocityZ = velocityZ / speed * 0.4f;
        }

        float nx = x + velocityX;
        float nz = z + velocityZ;
        if (nx > 1.0f && nz > 1.0f &&
            nx < TerrainGenerator.SIZE - 1.0f && nz < TerrainGenerator.SIZE - 1.0f &&
            terrain.getHeight((int)nx, (int)nz) < y + 0.35f) {
            x = nx;
            z = nz;
        } else {
            velocityX *= 0.25f;
            velocityZ *= 0.25f;
        }
        velocityX *= 0.99f;
        velocityZ *= 0.99f;

        float desiredYaw = playerYaw;
        if (speed > 0.015f) {
            desiredYaw = (float)(Math.atan2(-velocityX, velocityZ) * 57.29578);
        }
        if (left) desiredYaw -= 2.0f;
        if (right) desiredYaw += 2.0f;
        float delta = desiredYaw - yaw;
        while (delta >= 180.0f) delta -= 360.0f;
        while (delta < -180.0f) delta += 360.0f;
        if (delta > 20.0f) delta = 20.0f;
        if (delta < -20.0f) delta = -20.0f;
        yaw += delta;

        mesh.setTranslation(x, y, z);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
    }

    private void velocityYStep(float targetY) {
        float difference = targetY - y;
        y += difference * 0.18f;
        if (difference > 0.0f) y += 0.04f;
        else y -= 0.02f;
    }

    void push(float amount) {
        damage += (int)(amount * 10.0f);
        if (damage > 40) alive = false;
    }

    void remove(World world) {
        if (alive) {
            alive = false;
            world.removeChild(mesh);
        }
    }

    /**
     * Five low-poly panels reproduce ModelBoat's long hull and four raised
     * sides. The texture is supplied by boat.png; UVs cover the complete sheet.
     */
    private static Mesh createMesh(Appearance appearance) {
        short[] p = {
            -12, -2, -10,  12, -2, -10, -12, -2, 10,  12, -2, 10,
            -12, -2, -10, -9, 4, -8,  -12, -2, 10, -9, 4, 8,
            12, -2, 10, 9, 4, 8,  12, -2, -10, 9, 4, -8,
            -12, -2, -10, 12, -2, -10, -9, 4, -8, 9, 4, -8,
            12, -2, 10, -12, -2, 10, 9, 4, 8, -9, 4, 8
        };
        short[] uv = {
            0,0, 1,0, 0,1, 1,1,
            0,0, 1,0, 0,1, 1,1,
            0,0, 1,0, 0,1, 1,1,
            0,0, 1,0, 0,1, 1,1,
            0,0, 1,0, 0,1, 1,1
        };
        VertexArray positions = new VertexArray(20, 3, 2);
        positions.set(0, 20, p);
        VertexArray texCoords = new VertexArray(20, 2, 2);
        texCoords.set(0, 20, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(positions, 1.0f / 16.0f, null);
        vb.setTexCoords(0, texCoords, 1.0f, null);
        TriangleStripArray strips = new TriangleStripArray(0, new int[] {4,4,4,4,4});
        return new Mesh(vb, strips, appearance);
    }
}
