package mcj2me;

import java.util.Random;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Simple wandering zombies that pursue the player only at close range. */
final class HostileMob {
    private static final int COUNT = 2;
    private static final float UNLOAD_DISTANCE_SQUARED = 1600.0f;
    private final TerrainGenerator terrain;
    private final Group group = new Group();
    private final Random random = new Random();
    private final Mob[] mobs = new Mob[COUNT];
    private Appearance zombieAppearance;
    private boolean nightActive;
    private boolean deathEvent;
    private float deathX, deathY, deathZ;

    HostileMob(Group parent, TerrainGenerator terrain, float startX, float startZ) throws Exception {
        this.terrain = terrain;
        parent.addChild(group);
        zombieAppearance = PassiveMob.textureAppearance(ResourceImages.load("/mcj2me/zombie.png"));
        for (int i = 0; i < COUNT; i++) {
            int x = startX < 8 ? 12 + i * 3 : (int)startX + (i == 0 ? -9 : 9);
            int z = (int)startZ + (i == 0 ? -8 : 8);
            if (x < 3) x = 3;
            if (x > TerrainGenerator.SIZE - 4) x = TerrainGenerator.SIZE - 4;
            if (z < 3) z = 3;
            if (z > TerrainGenerator.SIZE - 4) z = TerrainGenerator.SIZE - 4;
            if (terrain.isWaterAt(x, z)) z = Math.min(TerrainGenerator.SIZE - 4, z + 3);
            Mesh mesh = PassiveMob.createMobMesh(zombieAppearance, PassiveMob.MOB_ZOMBIE);
            Mob mob = new Mob(mesh, zombieAppearance, x + 0.5f, z + 0.5f, random);
            mobs[i] = mob;
            mesh.setTranslation(mob.x, terrain.getHeight(x, z), mob.z);
        }
    }

    void setEnabled(boolean enabled) { group.setRenderingEnable(enabled); }

    void update(float playerX, float playerZ, boolean isNight) {
        if (isNight != nightActive) {
            nightActive = isNight;
            for (int i = 0; i < mobs.length; i++) {
                Mob mob = mobs[i];
                if (!isNight) {
                    if (mob.rendered) group.removeChild(mob.mesh);
                    mob.rendered = false;
                } else if (!mob.alive) {
                    // The existing nightly zombie respawn behavior is preserved.
                    mob.alive = true;
                    mob.health = 3;
                    mob.hurtTicks = 0;
                    mob.x = mob.homeX;
                    mob.z = mob.homeZ;
                    mob.jumpY = 0.0f;
                    mob.jumpVelocity = 0.0f;
                    mob.mesh.setAppearance(0, mob.normal);
                    mob.mesh.setTranslation(mob.x, terrain.getHeight((int)mob.x, (int)mob.z), mob.z);
                }
            }
        }
        if (!nightActive) return;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive) continue;
            float dx = mob.x - playerX, dz = mob.z - playerZ;
            if (dx * dx + dz * dz > UNLOAD_DISTANCE_SQUARED) {
                if (mob.rendered) {
                    group.removeChild(mob.mesh);
                    mob.rendered = false;
                }
                continue; // Suspend zombie AI and terrain sampling while unloaded.
            }
            if (!mob.rendered) {
                group.addChild(mob.mesh);
                mob.rendered = true;
                mob.mesh.setTranslation(mob.x, terrain.getHeight((int)mob.x, (int)mob.z), mob.z);
            }
            mob.update(terrain, random, playerX, playerZ);
        }
    }

    boolean hit(float px, float py, float pz, float yaw, float pitch) {
        if (!nightActive) return false;
        Mob target = null;
        float best = 4.6f;
        for (int i = 0; i < mobs.length; i++) {
            Mob mob = mobs[i];
            if (!mob.alive || !mob.rendered) continue;
            // Match the ray-hit target to the classic zombie's 2x8x2
            // overall silhouette instead of the old oversized 0.9 radius.
            // Zombie hitbox: 0.6 blocks wide and about 1.95 blocks tall.
            float score = PassiveMob.hitDistance(px, py, pz, yaw, pitch, mob.x,
                terrain.getHeight((int)mob.x, (int)mob.z) + 0.98f, mob.z, 0.30f);
            if (score >= 0.0f && score < best) { best = score; target = mob; }
        }
        if (target == null) return false;
        target.health--;
        target.hurtTicks = 5;
        target.mesh.setAppearance(0, PassiveMob.getHurtAppearance());
        if (target.health <= 0) {
            target.alive = false;
            if (target.rendered) group.removeChild(target.mesh);
            target.rendered = false;
            deathX = target.x;
            deathZ = target.z;
            deathY = terrain.getHeight((int)target.x, (int)target.z) + target.jumpY + 0.5f;
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

    private static final class Mob {
        final Mesh mesh;
        final Appearance normal;
        float x, z, homeX, homeZ, dirX, dirZ;
        float jumpY = 0.0f, jumpVelocity = 0.0f;
        int health = 3, hurtTicks, wanderTicks, hopCooldown;
        boolean alive = true;
        boolean rendered;
        final Random random;

        Mob(Mesh mesh, Appearance normal, float x, float z, Random random) {
            this.mesh = mesh; this.normal = normal; this.random = random;
            this.x = this.homeX = x; this.z = this.homeZ = z;
            hopCooldown = 8 + random.nextInt(35);
            chooseDirection();
        }

        void chooseDirection() {
            dirX = random.nextInt(3) - 1;
            dirZ = random.nextInt(3) - 1;
            if (dirX == 0.0f && dirZ == 0.0f) dirZ = 1.0f;
            wanderTicks = 25 + random.nextInt(45);
        }

        void update(TerrainGenerator terrain, Random random, float playerX, float playerZ) {
            if (hurtTicks > 0 && --hurtTicks == 0) mesh.setAppearance(0, normal);
            float dx = playerX - x, dz = playerZ - z;
            float dist2 = dx * dx + dz * dz;
            if (dist2 < 64.0f) {
                float dist = (float)Math.sqrt(dist2);
                if (dist > 0.01f) { dx /= dist; dz /= dist; }
                move(terrain, dx, dz, 0.032f, random);
            } else {
                if (wanderTicks-- <= 0) chooseDirection();
                dx = dirX; dz = dirZ;
                float homeDx = x - homeX, homeDz = z - homeZ;
                if (homeDx * homeDx + homeDz * homeDz > 144.0f) { dx = -homeDx; dz = -homeDz; }
                float len = (float)Math.sqrt(dx * dx + dz * dz);
                if (len > 0.0f) { dx /= len; dz /= len; }
                move(terrain, dx, dz, 0.022f, random);
            }
            // Even the zombies inherit Indev's chaotic little hops.
            if (hopCooldown > 0) hopCooldown--;
            if (hopCooldown <= 0 && jumpY <= 0.001f && random.nextInt(6) != 0) {
                jumpVelocity = 0.095f + random.nextInt(55) * 0.001f;
                hopCooldown = 10 + random.nextInt(38);
            }
            if (jumpY > 0.0f || jumpVelocity > 0.0f) {
                jumpY += jumpVelocity;
                jumpVelocity -= 0.017f;
                if (jumpY <= 0.0f) {
                    jumpY = 0.0f;
                    jumpVelocity = 0.0f;
                }
            }
            float bob = (float)Math.sin(wanderTicks * 0.25f) * 0.008f;
            mesh.setTranslation(x, terrain.getHeight((int)x, (int)z) + bob + jumpY, z);
        }

        void move(TerrainGenerator terrain, float dx, float dz, float speed, Random random) {
            float nx = x + dx * speed, nz = z + dz * speed;
            if (nx < 2 || nz < 2 || nx > TerrainGenerator.SIZE - 3 || nz > TerrainGenerator.SIZE - 3
                    || terrain.isWaterAt((int)nx, (int)nz)) {
                chooseDirection();
            } else { x = nx; z = nz; }
            if (dx * dx + dz * dz > 0.0001f) {
                mesh.setOrientation(PassiveMob.directionAngle(dx, dz), 0.0f, 1.0f, 0.0f);
            }
        }
    }
}
