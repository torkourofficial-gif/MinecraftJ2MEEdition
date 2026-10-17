package mcj2me;

import java.util.Random;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Simple wandering zombies that pursue the player only at close range. */
final class HostileMob {
    private static final int COUNT = 2;
    private final TerrainGenerator terrain;
    private final Group group = new Group();
    private final Random random = new Random();
    private final Mob[] mobs = new Mob[COUNT];
    private Appearance zombieAppearance;

    HostileMob(Group parent, TerrainGenerator terrain, float startX, float startZ) throws Exception {
        this.terrain = terrain;
        parent.addChild(group);
        zombieAppearance = PassiveMob.textureAppearance(Image.createImage("/mcj2me/zombie.png"));
        Appearance hurt = PassiveMob.getHurtAppearance();
        for (int i = 0; i < COUNT; i++) {
            int x = startX < 8 ? 12 + i * 3 : (int)startX + (i == 0 ? -9 : 9);
            int z = (int)startZ + (i == 0 ? -8 : 8);
            if (x < 3) x = 3;
            if (x > TerrainGenerator.SIZE - 4) x = TerrainGenerator.SIZE - 4;
            if (z < 3) z = 3;
            if (z > TerrainGenerator.SIZE - 4) z = TerrainGenerator.SIZE - 4;
            if (terrain.isWaterAt(x, z)) z = Math.min(TerrainGenerator.SIZE - 4, z + 3);
            Mesh mesh = PassiveMob.createMobMesh(zombieAppearance, true);
            Mob mob = new Mob(mesh, zombieAppearance, x + 0.5f, z + 0.5f);
            mobs[i] = mob;
            group.addChild(mesh);
            mesh.setTranslation(mob.x, terrain.getHeight(x, z), mob.z);
        }
    }

    void update(float playerX, float playerZ) {
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive) continue;
            mob.update(terrain, random, playerX, playerZ);
        }
    }

    boolean hit(float px, float py, float pz, float yaw, float pitch) {
        Mob target = null;
        float best = 4.6f;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive) continue;
            float score = PassiveMob.hitDistance(px, py, pz, yaw, pitch, mob.x,
                terrain.getHeight((int)mob.x, (int)mob.z) + 0.9f, mob.z, 0.9f);
            if (score >= 0.0f && score < best) { best = score; target = mob; }
        }
        if (target == null) return false;
        target.health--;
        target.hurtTicks = 5;
        target.mesh.setAppearance(0, PassiveMob.getHurtAppearance());
        if (target.health <= 0) {
            target.alive = false;
            group.removeChild(target.mesh);
        }
        return true;
    }

    private static final class Mob {
        final Mesh mesh;
        final Appearance normal;
        float x, z, homeX, homeZ, dirX, dirZ;
        int health = 3, hurtTicks, wanderTicks;
        boolean alive = true;

        Mob(Mesh mesh, Appearance normal, float x, float z) {
            this.mesh = mesh; this.normal = normal;
            this.x = this.homeX = x; this.z = this.homeZ = z;
            chooseDirection();
        }

        void chooseDirection() {
            dirX = randomDirection();
            dirZ = randomDirection();
            if (dirX == 0.0f && dirZ == 0.0f) dirZ = 1.0f;
            wanderTicks = 25 + (int)(Math.random() * 45.0);
        }

        float randomDirection() { return (int)(Math.random() * 3.0) - 1; }

        void update(TerrainGenerator terrain, Random random, float playerX, float playerZ) {
            if (hurtTicks > 0 && --hurtTicks == 0) mesh.setAppearance(0, normal);
            float dx = playerX - x, dz = playerZ - z;
            float dist2 = dx * dx + dz * dz;
            if (dist2 < 64.0f) {
                float dist = (float)Math.sqrt(dist2);
                if (dist > 0.01f) { dx /= dist; dz /= dist; }
                move(terrain, dx, dz, 0.032f, random);
            } else {
                if (wanderTicks-- <= 0) chooseDirection(random);
                dx = dirX; dz = dirZ;
                float homeDx = x - homeX, homeDz = z - homeZ;
                if (homeDx * homeDx + homeDz * homeDz > 144.0f) { dx = -homeDx; dz = -homeDz; }
                float len = (float)Math.sqrt(dx * dx + dz * dz);
                if (len > 0.0f) { dx /= len; dz /= len; }
                move(terrain, dx, dz, 0.022f, random);
            }
            mesh.setTranslation(x, terrain.getHeight((int)x, (int)z), z);
        }

        void move(TerrainGenerator terrain, float dx, float dz, float speed, Random random) {
            float nx = x + dx * speed, nz = z + dz * speed;
            if (nx < 2 || nz < 2 || nx > TerrainGenerator.SIZE - 3 || nz > TerrainGenerator.SIZE - 3
                    || terrain.isWaterAt((int)nx, (int)nz)) {
                chooseDirection();
            } else { x = nx; z = nz; }
        }
    }
}
