package mcj2me;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

final class WorldCanvas extends Canvas implements Runnable {
    private static final int CHUNK = 16;
    private static final int RADIUS = 1;
    private static final float MOVE_SPEED = 0.20f;
    private static final float MOVE_ACCEL = 0.035f;
    private static final float FRICTION = 0.78f;
    private static final float GRAVITY = 0.035f;
    private static final float JUMP_SPEED = 0.38f;
    private static final float TURN_SPEED = 3.0f;
    private static final int WATER_LEVEL = TerrainGenerator.WATER_LEVEL;
    private static final float WATER_ALPHA = 0.66f;

    private final IndevViewerMIDlet midlet;
    private final TerrainGenerator terrain = new TerrainGenerator();
    private Graphics3D graphics3D;
    private World world;
    private Group terrainGroup;
    private Group waterGroup;
    private Texture2D waterTexture;
    private Appearance waterAppearance;
    private Image underwaterOverlay;
    private int overlayWidth = -1, overlayHeight = -1;
    private Mesh[][] activeWaterChunks = new Mesh[8][8];
    private float waterPhase = 0.0f;
    private int splashTicks = 0;
    private boolean wasInWater = false;
    private Camera camera;

    private Appearance groundAppearance;
    private Mesh[][] activeChunks = new Mesh[8][8];
    private Thread thread;
    private boolean running;
    private boolean paused;
    private boolean forward, backward, strafeLeft, strafeRight;
    private boolean lookUp, lookDown, turnLeft, turnRight;
    private boolean jumpHeld;
    private static final int[] BLOCK_TYPES = {0, 2, 1};
    private static final String[] BLOCK_NAMES = {"GRASS", "DIRT", "STONE"};
    private final int[] inventory = {64, 64, 64};
    private int selectedSlot = 0, particleTicks = 0, actionCooldown = 0;
    private float cameraX = 64.0f;
    private float cameraY;
    private float velocityX, velocityZ, verticalVelocity;
    private boolean airborne;
    private float cameraZ = 105.0f;
    private float yaw = 0.0f;
    private float pitch = -28.0f;
    private int loadedCenterX = -99;
    private int loadedCenterZ = -99;
    private String errorMessage;

    WorldCanvas(IndevViewerMIDlet owner) {
        midlet = owner;
        setFullScreenMode(true);
        try {
            setupScene();
        } catch (OutOfMemoryError e) {
            errorMessage = "Not enough Java heap for M3G.";
        } catch (Exception e) {
            errorMessage = "M3G setup failed: " + e.toString();
        }
    }

    private void setupScene() throws Exception {
        // Initialize M3G objects inside the guarded setup path so API/linkage
        // failures can be reported by the constructor instead of escaping
        // from field initializers.
        graphics3D = Graphics3D.getInstance();
        world = new World();
        terrainGroup = new Group();
        waterGroup = new Group();
        camera = new Camera();

        Image atlas = Image.createImage("/mcj2me/textures.png");
        Image2D image2D = new Image2D(Image2D.RGBA, atlas);
        Texture2D texture = new Texture2D(image2D);
        texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);

        groundAppearance = new Appearance();
        groundAppearance.setTexture(0, texture);
        PolygonMode polygonMode = new PolygonMode();
        polygonMode.setCulling(PolygonMode.CULL_NONE);
        polygonMode.setShading(PolygonMode.SHADE_FLAT);
        groundAppearance.setPolygonMode(polygonMode);

        // Extract the classic still-water tile from the existing atlas, tint it
        // with partial alpha, then repeat/scroll it independently for animation.
        int[] waterPixels = new int[16 * 16];
        atlas.getRGB(waterPixels, 0, 16, 14 * 16, 12 * 16, 16, 16);
        for (int wi = 0; wi < waterPixels.length; wi++) {
            waterPixels[wi] = (waterPixels[wi] & 0x00FFFFFF) | 0xA8000000;
        }
        Image waterImage = Image.createRGBImage(waterPixels, 16, 16, true);
        Image2D waterImage2D = new Image2D(Image2D.RGBA, waterImage);
        waterTexture = new Texture2D(waterImage2D);
        waterTexture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        waterTexture.setWrapping(Texture2D.WRAP_REPEAT, Texture2D.WRAP_REPEAT);
        waterAppearance = new Appearance();
        waterAppearance.setTexture(0, waterTexture);
        waterAppearance.setPolygonMode(polygonMode);
        CompositingMode waterBlend = new CompositingMode();
        waterBlend.setBlending(CompositingMode.ALPHA);
        waterBlend.setDepthWriteEnable(false);
        waterBlend.setAlphaWriteEnable(false);
        waterAppearance.setCompositingMode(waterBlend);

        Background background = new Background();
        background.setColor(0x87BFEA);
        world.setBackground(background);

        Light ambient = new Light();
        ambient.setMode(Light.AMBIENT);
        ambient.setColor(0xFFFFFF);
        world.addChild(ambient);

        cameraY = terrain.getHeightSmooth(64.0f, 105.0f) + 1.65f;
        camera.setPerspective(70.0f, 1.0f, 0.1f, 180.0f);
        world.addChild(camera);
        world.setActiveCamera(camera);
        world.addChild(terrainGroup);
        world.addChild(waterGroup);
        updateCamera();
        updateChunks();
    }

    synchronized void start() {
        if (errorMessage != null || running) return;
        running = true;
        paused = false;
        thread = new Thread(this);
        thread.start();
    }

    synchronized void pause() {
        paused = true;
        clearKeys();
    }

    synchronized void stop() {
        running = false;
        clearKeys();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public void run() {
        while (running) {
            if (!paused) {
                updateMovement();
                animateWater();
                repaint();
                serviceRepaints();
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                // Re-check running on the next loop.
            }
        }
    }

    protected void paint(Graphics g) {
        if (errorMessage != null) {
            g.setColor(0x101820);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(0xFFFFFF);
            g.drawString("Minecraft J2ME Edition", 4, 4, Graphics.TOP | Graphics.LEFT);
            g.drawString(errorMessage, 4, 28, Graphics.TOP | Graphics.LEFT);
            g.drawString("Requires MIDP 2.0 + M3G (JSR-184)", 4, 52,
                         Graphics.TOP | Graphics.LEFT);
            return;
        }
        boolean targetBound = false;
        try {
            camera.setPerspective(70.0f,
                (float) getWidth() / (float) Math.max(1, getHeight()),
                0.1f, 180.0f);
            graphics3D.bindTarget(g);
            targetBound = true;
            graphics3D.render(world);
        } catch (Exception e) {
            g.setColor(0x101820);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(0xFFFFFF);
            g.drawString("M3G render error", 4, 4, Graphics.TOP | Graphics.LEFT);
            g.drawString(e.toString(), 4, 24, Graphics.TOP | Graphics.LEFT);
        } finally { if (targetBound) graphics3D.releaseTarget(); }
        drawUnderwaterOverlay(g);
        drawHud(g);
    }

    // MIDP Graphics has no setAlpha, so use a cached ARGB image for the blue
    // underwater tint. The overlay follows the camera, not the player's feet.
    private void drawUnderwaterOverlay(Graphics g) {
        if (!isCameraUnderwater()) return;
        int w = getWidth(), h = getHeight();
        if (underwaterOverlay == null || overlayWidth != w || overlayHeight != h) {
            int[] pixels = new int[w * h];
            for (int i = 0; i < pixels.length; i++) pixels[i] = 0x554080D8;
            underwaterOverlay = Image.createRGBImage(pixels, w, h, true);
            overlayWidth = w;
            overlayHeight = h;
        }
        g.drawImage(underwaterOverlay, 0, 0, Graphics.TOP | Graphics.LEFT);
    }

    private boolean isCameraUnderwater() {
        return terrain.isWaterAt((int) cameraX, (int) cameraZ)
            && cameraY < WATER_LEVEL;
    }

    private boolean isSwimming() {
        return terrain.isWaterAt((int) cameraX, (int) cameraZ)
            && cameraY < WATER_LEVEL + 1.0f;
    }

    private void drawHud(Graphics g) {
        int w = getWidth(), h = getHeight(); g.setColor(0x101820); g.fillRect(3, 3, Math.min(w - 6, 112), 15);
        g.setColor(0xFFFFFF); g.drawString("MC J2ME", 7, 4, Graphics.TOP | Graphics.LEFT);
        int sy = h - 23;
        for (int i = 0; i < 3; i++) { int sx = 4 + i * 54; g.setColor(i == selectedSlot ? 0xE8D070 : 0x303840); g.fillRect(sx, sy, 50, 19); g.setColor(0x101820); g.drawRect(sx, sy, 49, 18); g.setColor(0xFFFFFF); g.drawString(BLOCK_NAMES[i] + " " + inventory[i], sx + 2, sy + 2, Graphics.TOP | Graphics.LEFT); }
        int cx = w / 2, cy = h / 2; g.setColor(0xFFFFFF); g.drawLine(cx - 3, cy, cx + 3, cy); g.drawLine(cx, cy - 3, cx, cy + 3);
        if (splashTicks > 0) {
            g.setColor(0xBDEBFF);
            for (int b = 0; b < 5; b++) {
                int bx = cx + ((b * 13 + splashTicks * 3) % 23) - 11;
                int by = cy + ((b * 7 + splashTicks * 4) % 19) - 9;
                g.drawArc(bx, by, 2 + (splashTicks % 3), 2 + (splashTicks % 3), 0, 360);
            }
        }
        if (particleTicks > 0) { g.setColor(0xB5A06A); for (int p = 0; p < 8; p++) { int px = cx + (((p * 17 + particleTicks * 7) % 19) - 9), py = cy + (((p * 11 + particleTicks * 5) % 17) - 8), size = 2 + (particleTicks & 1); g.fillRect(px, py, size, size); } }
    }

    protected synchronized void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);

        // Numeric keypad movement for emulators/phones without a QWERTY keyboard.
        // Check digits first because some MIDP implementations map 2/4/6/8
        // to directional game actions as well.
        if (keyCode == '2') {
            forward = true;
        } else if (keyCode == '8') {
            backward = true;
        } else if (keyCode == '4') {
            strafeLeft = true;
        } else if (keyCode == '6') {
            strafeRight = true;
        } else {
            // D-pad controls the camera: left/right turn, up/down look.
            if (action == UP) lookUp = true;
            if (action == DOWN) lookDown = true;
            if (action == LEFT) turnLeft = true;
            if (action == RIGHT) turnRight = true;
        }

        // Keep letter controls available for desktop emulators.
        if (keyCode == 'w' || keyCode == 'W') forward = true;
        if (keyCode == 's' || keyCode == 'S') backward = true;
        if (keyCode == 'a' || keyCode == 'A') strafeLeft = true;
        if (keyCode == 'd' || keyCode == 'D') strafeRight = true;

        if (keyCode == '1' && actionCooldown == 0) { breakBlock(); actionCooldown = 4; }
        else if (keyCode == '3' && actionCooldown == 0) { placeBlock(); actionCooldown = 4; }
        else if (keyCode == '7') { selectedSlot--; if (selectedSlot < 0) selectedSlot = 2; }
        else if (keyCode == '9') { selectedSlot++; if (selectedSlot > 2) selectedSlot = 0; }
        // In water, Fire/center is the swim-up control and can be held/re-tapped
        // while already airborne. On land it remains the normal jump button.
        if (action == FIRE || keyCode == '5' || keyCode == ' ') {
            jumpHeld = true;
            if (isSwimming()) {
                // Give the player an immediate upward stroke, then let held
                // jump keep accelerating toward and through the water surface.
                verticalVelocity = Math.max(verticalVelocity, 0.24f);
                airborne = true;
            } else if (!airborne) {
                verticalVelocity = JUMP_SPEED;
                airborne = true;
            }
        }
    }

    protected synchronized void keyReleased(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == FIRE || keyCode == '5' || keyCode == ' ') jumpHeld = false;

        if (keyCode == '2') {
            forward = false;
        } else if (keyCode == '8') {
            backward = false;
        } else if (keyCode == '4') {
            strafeLeft = false;
        } else if (keyCode == '6') {
            strafeRight = false;
        } else {
            if (action == UP) lookUp = false;
            if (action == DOWN) lookDown = false;
            if (action == LEFT) turnLeft = false;
            if (action == RIGHT) turnRight = false;
        }

        if (keyCode == 'w' || keyCode == 'W') forward = false;
        if (keyCode == 's' || keyCode == 'S') backward = false;
        if (keyCode == 'a' || keyCode == 'A') strafeLeft = false;
        if (keyCode == 'd' || keyCode == 'D') strafeRight = false;
    }

    private synchronized void clearKeys() {
        forward = backward = strafeLeft = strafeRight = false;
        lookUp = lookDown = turnLeft = turnRight = false;
        jumpHeld = false;
    }

    private void updateMovement() {
        boolean changed = false;
        boolean positionChanged = false;

        if (turnLeft) { yaw -= TURN_SPEED; changed = true; }
        if (turnRight) { yaw += TURN_SPEED; changed = true; }
        if (lookUp) {
            pitch += TURN_SPEED;
            if (pitch > 88.0f) pitch = 88.0f;
            changed = true;
        }
        if (lookDown) {
            pitch -= TURN_SPEED;
            if (pitch < -88.0f) pitch = -88.0f;
            changed = true;
        }

        float radians = (float) (yaw * Math.PI / 180.0);
        float forwardX = (float) Math.sin(radians);
        float forwardZ = -(float) Math.cos(radians);
        float inputX = 0.0f, inputZ = 0.0f;
        if (forward) { inputX += forwardX; inputZ += forwardZ; }
        if (backward) { inputX -= forwardX; inputZ -= forwardZ; }
        if (strafeLeft) { inputX -= (float) Math.cos(radians); inputZ -= (float) Math.sin(radians); }
        if (strafeRight) { inputX += (float) Math.cos(radians); inputZ += (float) Math.sin(radians); }

        // Water slows horizontal movement, giving a lightweight swimming feel.
        boolean currentlyWet = isSwimming();
        if (currentlyWet) {
            velocityX *= 0.88f;
            velocityZ *= 0.88f;
            inputX *= 0.62f;
            inputZ *= 0.62f;
        }

        // Normalize diagonal input so it isn't faster, then accelerate and coast.
        float inputLength = (float) Math.sqrt(inputX * inputX + inputZ * inputZ);
        if (inputLength > 0.0f) {
            inputX /= inputLength; inputZ /= inputLength;
            velocityX += inputX * MOVE_ACCEL;
            velocityZ += inputZ * MOVE_ACCEL;
            changed = true;
        } else {
            velocityX *= FRICTION;
            velocityZ *= FRICTION;
        }
        float speed = (float) Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        if (speed > MOVE_SPEED) { velocityX = velocityX * MOVE_SPEED / speed; velocityZ = velocityZ * MOVE_SPEED / speed; }
        if (Math.abs(velocityX) < 0.003f) velocityX = 0.0f;
        if (Math.abs(velocityZ) < 0.003f) velocityZ = 0.0f;

        // Resolve horizontal movement axis-by-axis against the height-map's
        // block columns. The player may step up one block, but cannot slide
        // through taller square walls or diagonally clip through their corners.
        if (velocityX != 0.0f || velocityZ != 0.0f) {
            float oldX = cameraX;
            float oldZ = cameraZ;
            float nextX = clampWorldX(cameraX + velocityX);
            float nextZ = clampWorldZ(cameraZ + velocityZ);

            if (canOccupy(nextX, oldZ)) {
                cameraX = nextX;
            } else {
                velocityX = 0.0f;
            }
            if (canOccupy(cameraX, nextZ)) {
                cameraZ = nextZ;
            } else {
                velocityZ = 0.0f;
            }

            if (cameraX != oldX || cameraZ != oldZ) {
                positionChanged = true;
                changed = true;
            }
        }

        float groundY = terrain.getHeight((int) cameraX, (int) cameraZ) + 1.65f;
        boolean inWater = isSwimming();
        if (inWater && !wasInWater) splashTicks = 10;
        wasInWater = inWater;
        if (splashTicks > 0) splashTicks--;

        if (inWater) {
            // Reduced gravity and buoyancy while submerged. Holding Fire/center
            // continuously strokes upward; the camera can cross the surface
            // instead of being clamped just beneath it.
            if (!airborne) airborne = true;
            // Water should not provide automatic buoyancy: without input the
            // player slowly sinks. Holding jump applies an upward swim stroke.
            if (jumpHeld) verticalVelocity += 0.035f;
            verticalVelocity -= 0.006f;
            if (verticalVelocity < -0.035f) verticalVelocity = -0.035f;
            if (verticalVelocity > 0.20f) verticalVelocity = 0.20f;
            cameraY += verticalVelocity;

            // Water never disables solid-ground collision. Keep the player's
            // feet above the terrain even while buoyancy/gravity is active.
            if (cameraY < groundY) {
                cameraY = groundY;
                if (verticalVelocity < 0.0f) verticalVelocity = 0.0f;
            }
            changed = true;
        } else if (airborne) {
            cameraY += verticalVelocity;
            verticalVelocity -= GRAVITY;
            if (cameraY <= groundY) {
                cameraY = groundY;
                verticalVelocity = 0.0f;
                airborne = false;
            }
            changed = true;
        } else {
            // Ease over height-map steps instead of snapping the camera instantly.
            if (cameraY < groundY) cameraY = Math.min(groundY, cameraY + 0.28f);
            else if (cameraY > groundY) cameraY = Math.max(groundY, cameraY - 0.28f);
            if (Math.abs(cameraY - groundY) > 0.01f) changed = true;
        }

        if (actionCooldown > 0) actionCooldown--;
        if (particleTicks > 0) particleTicks--;
        if (changed) updateCamera();
        if (positionChanged) updateChunks();
    }

    private float clampWorldX(float x) {
        if (x < 1.0f) return 1.0f;
        if (x > 126.0f) return 126.0f;
        return x;
    }

    private float clampWorldZ(float z) {
        if (z < 1.0f) return 1.0f;
        if (z > 126.0f) return 126.0f;
        return z;
    }

    /**
     * Square horizontal collision against terrain columns and tree trunks.
     * The camera/player can climb a single block, but not penetrate a taller
     * column. A small square footprint keeps collision consistent on keypad
     * movement and prevents diagonal corner clipping.
     */
    private boolean canOccupy(float x, float z) {
        if (x < 1.0f || z < 1.0f || x > 126.0f || z > 126.0f) return false;

        float feetY = cameraY - 1.65f;
        int minX = (int) Math.floor(x - 0.20f);
        int maxX = (int) Math.floor(x + 0.20f);
        int minZ = (int) Math.floor(z - 0.20f);
        int maxZ = (int) Math.floor(z + 0.20f);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                int surface = terrain.getHeight(bx, bz);
                if (surface > feetY + 1.01f) return false;

                // Trunks occupy a full square block at the player's level.
                if (terrain.isTreeAt(bx, bz)) {
                    int treeBase = terrain.getHeight(bx, bz);
                    if (feetY < treeBase + terrain.getTreeHeight(bx, bz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void updateCamera() {
        Transform transform = new Transform();
        transform.setIdentity();

        // Translate first in the transform product so the camera position
        // is not rotated around the world origin when yaw/pitch change.
        transform.postTranslate(cameraX, cameraY, cameraZ);
        transform.postRotate(-yaw, 0.0f, 1.0f, 0.0f);
        transform.postRotate(pitch, 1.0f, 0.0f, 0.0f);

        camera.setTransform(transform);
    }

    private void updateChunks() {
        int centerX = ((int) cameraX) / CHUNK;
        int centerZ = ((int) cameraZ) / CHUNK;
        if (centerX == loadedCenterX && centerZ == loadedCenterZ) return;

        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                Mesh old = activeChunks[x][z];
                if (old != null) {
                    terrainGroup.removeChild(old);
                    activeChunks[x][z] = null;
                }
                Mesh oldWater = activeWaterChunks[x][z];
                if (oldWater != null) {
                    waterGroup.removeChild(oldWater);
                    activeWaterChunks[x][z] = null;
                }
            }
        }

        for (int cx = centerX - RADIUS; cx <= centerX + RADIUS; cx++) {
            for (int cz = centerZ - RADIUS; cz <= centerZ + RADIUS; cz++) {
                if (cx >= 0 && cz >= 0 && cx < 8 && cz < 8) {
                    Mesh mesh = buildChunk(cx, cz);
                    if (mesh != null) {
                        activeChunks[cx][cz] = mesh;
                        terrainGroup.addChild(mesh);
                    }
                    Mesh waterMesh = buildWaterChunk(cx, cz);
                    if (waterMesh != null) {
                        activeWaterChunks[cx][cz] = waterMesh;
                        waterGroup.addChild(waterMesh);
                    }
                }
            }
        }
        loadedCenterX = centerX;
        loadedCenterZ = centerZ;
    }

    /**
     * Builds exposed faces of the water block volume. Each wet column contains
     * water cells from its ground surface up to WATER_LEVEL; tops and shoreline
     * walls are emitted as block faces instead of one continuous flat sheet.
     */
    private Mesh buildWaterChunk(int chunkX, int chunkZ) {
        int startX = chunkX * CHUNK;
        int startZ = chunkZ * CHUNK;
        int quads = 0;
        int x, z;
        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                if (!terrain.isWaterBlockAt(x, WATER_LEVEL - 1, z)) continue;
                quads++; // top face of the uppermost water block
                int h = terrain.getHeight(x, z);
                int nh;
                if (!terrain.isWaterAt(x - 1, z)) { nh = terrain.getHeight(x - 1, z); if (Math.max(h, nh) < WATER_LEVEL) quads++; }
                if (!terrain.isWaterAt(x + 1, z)) { nh = terrain.getHeight(x + 1, z); if (Math.max(h, nh) < WATER_LEVEL) quads++; }
                if (!terrain.isWaterAt(x, z - 1)) { nh = terrain.getHeight(x, z - 1); if (Math.max(h, nh) < WATER_LEVEL) quads++; }
                if (!terrain.isWaterAt(x, z + 1)) { nh = terrain.getHeight(x, z + 1); if (Math.max(h, nh) < WATER_LEVEL) quads++; }
            }
        }
        if (quads == 0) return null;

        int vertexCount = quads * 4;
        short[] positions = new short[vertexCount * 3];
        short[] texCoords = new short[vertexCount * 2];
        int[] indices = new int[vertexCount];
        int[] stripLengths = new int[quads];
        int vertex = 0, strip = 0;
        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                if (!terrain.isWaterBlockAt(x, WATER_LEVEL - 1, z)) continue;
                int h = terrain.getHeight(x, z);
                vertex = addWaterQuad(positions, texCoords, indices, stripLengths,
                    vertex, strip++, x, WATER_LEVEL, z,
                    x, WATER_LEVEL, z + 1,
                    x + 1, WATER_LEVEL, z,
                    x + 1, WATER_LEVEL, z + 1);

                int nh = terrain.getHeight(x - 1, z);
                if (!terrain.isWaterAt(x - 1, z) && Math.max(h, nh) < WATER_LEVEL) {
                    int y0 = Math.max(h, nh);
                    vertex = addWaterQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x, y0, z + 1, x, WATER_LEVEL, z + 1,
                        x, y0, z, x, WATER_LEVEL, z);
                }
                nh = terrain.getHeight(x + 1, z);
                if (!terrain.isWaterAt(x + 1, z) && Math.max(h, nh) < WATER_LEVEL) {
                    int y0 = Math.max(h, nh);
                    vertex = addWaterQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x + 1, y0, z, x + 1, WATER_LEVEL, z,
                        x + 1, y0, z + 1, x + 1, WATER_LEVEL, z + 1);
                }
                nh = terrain.getHeight(x, z - 1);
                if (!terrain.isWaterAt(x, z - 1) && Math.max(h, nh) < WATER_LEVEL) {
                    int y0 = Math.max(h, nh);
                    vertex = addWaterQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x, y0, z, x, WATER_LEVEL, z,
                        x + 1, y0, z, x + 1, WATER_LEVEL, z);
                }
                nh = terrain.getHeight(x, z + 1);
                if (!terrain.isWaterAt(x, z + 1) && Math.max(h, nh) < WATER_LEVEL) {
                    int y0 = Math.max(h, nh);
                    vertex = addWaterQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x + 1, y0, z + 1, x + 1, WATER_LEVEL, z + 1,
                        x, y0, z + 1, x, WATER_LEVEL, z + 1);
                }
            }
        }

        VertexArray vertexArray = new VertexArray(vertexCount, 3, 2);
        vertexArray.set(0, vertexCount, positions);
        VertexArray texArray = new VertexArray(vertexCount, 2, 2);
        texArray.set(0, vertexCount, texCoords);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(vertexArray, 1.0f, null);
        buffer.setTexCoords(0, texArray, 1.0f, null);
        TriangleStripArray triangles = new TriangleStripArray(indices, stripLengths);
        return new Mesh(buffer, triangles, waterAppearance);
    }

    private int addWaterQuad(short[] positions, short[] texCoords, int[] indices,
                             int[] stripLengths, int vertex, int strip,
                             int x0, int y0, int z0, int x1, int y1, int z1,
                             int x2, int y2, int z2, int x3, int y3, int z3) {
        vertex = addWaterVertex(positions, texCoords, indices, vertex, x0, y0, z0, 0, 0);
        vertex = addWaterVertex(positions, texCoords, indices, vertex, x1, y1, z1, 0, 1);
        vertex = addWaterVertex(positions, texCoords, indices, vertex, x2, y2, z2, 1, 0);
        vertex = addWaterVertex(positions, texCoords, indices, vertex, x3, y3, z3, 1, 1);
        stripLengths[strip] = 4;
        return vertex;
    }

    private int addWaterVertex(short[] positions, short[] texCoords, int[] indices,
                               int vertex, int x, int y, int z, int u, int v) {
        int p = vertex * 3;
        positions[p] = (short) x;
        positions[p + 1] = (short) y;
        positions[p + 2] = (short) z;
        indices[vertex] = vertex;
        int t = vertex * 2;
        texCoords[t] = (short) u;
        texCoords[t + 1] = (short) v;
        return vertex + 1;
    }

    private void animateWater() {
        waterPhase += 0.012f;
        if (waterPhase >= 1.0f) waterPhase -= 1.0f;
        // Texture2D is Transformable in M3G; repeating coordinates make this
        // a cheap scrolling-water animation without rebuilding terrain meshes.
        waterTexture.setTranslation(waterPhase, waterPhase * 0.65f, 0.0f);
    }

    private Mesh buildChunk(int chunkX, int chunkZ) {
        int startX = chunkX * CHUNK;
        int startZ = chunkZ * CHUNK;
        int quads = 0;
        int x, z, h;
        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                h = terrain.getHeight(x, z);
                quads++;
                int nh = terrain.getHeight(x - 1, z); if (h > nh) quads += h - nh;
                nh = terrain.getHeight(x + 1, z); if (h > nh) quads += h - nh;
                nh = terrain.getHeight(x, z - 1); if (h > nh) quads += h - nh;
                nh = terrain.getHeight(x, z + 1); if (h > nh) quads += h - nh;
                if (terrain.isTreeAt(x, z)) quads += (terrain.getTreeHeight(x, z) + 57) * 6;
            }
        }

        int vertexCount = quads * 4;
        short[] positions = new short[vertexCount * 3];
        short[] texCoords = new short[vertexCount * 2];
        int[] indices = new int[vertexCount];
        int[] stripLengths = new int[quads];
        int vertex = 0;
        int strip = 0;

        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                h = terrain.getHeight(x, z);
                int topType = terrain.getTopType(x, z);
                vertex = addQuad(positions, texCoords, indices, stripLengths,
                    vertex, strip++, x, h, z, x, h, z + 1,
                    x + 1, h, z, x + 1, h, z + 1, BLOCK_TYPES[topType], 0);

                int neighbor = terrain.getHeight(x - 1, z);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x, sy, z + 1, x, sy + 1, z + 1,
                        x, sy, z, x, sy + 1, z, sideTile(terrain.getTopType(x, z), sy, h), 0);
                }
                neighbor = terrain.getHeight(x + 1, z);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x + 1, sy, z, x + 1, sy + 1, z,
                        x + 1, sy, z + 1, x + 1, sy + 1, z + 1, sideTile(terrain.getTopType(x, z), sy, h), 0);
                }
                neighbor = terrain.getHeight(x, z - 1);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x, sy, z, x, sy + 1, z,
                        x + 1, sy, z, x + 1, sy + 1, z, sideTile(terrain.getTopType(x, z), sy, h), 0);
                }
                neighbor = terrain.getHeight(x, z + 1);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x + 1, sy, z + 1, x + 1, sy + 1, z + 1,
                        x, sy, z + 1, x, sy + 1, z + 1, sideTile(terrain.getTopType(x, z), sy, h), 0);
                }
                if (terrain.isTreeAt(x, z)) {
                    int base = h, th = terrain.getTreeHeight(x, z), y;
                    for (y = 0; y < th; y++) { vertex = addCube(positions, texCoords, indices, stripLengths, vertex, strip, x, base + y, z, 4, 1); strip += 6; }
                    int crown = base + th - 1;
                    for (y = crown - 1; y <= crown + 1; y++) { int radius = (y == crown + 1) ? 1 : 2, lx, lz;
                        for (lx = x - radius; lx <= x + radius; lx++) for (lz = z - radius; lz <= z + radius; lz++) {
                            if (lx == x && lz == z && y <= crown) continue;
                            vertex = addCube(positions, texCoords, indices, stripLengths, vertex, strip, lx, y, lz, 4, 3); strip += 6;
                        }
                    }
                }
            }
        }

        VertexArray vertexArray = new VertexArray(vertexCount, 3, 2);
        vertexArray.set(0, vertexCount, positions);
        VertexArray texArray = new VertexArray(vertexCount, 2, 2);
        texArray.set(0, vertexCount, texCoords);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(vertexArray, 1.0f, null);
        buffer.setTexCoords(0, texArray, 1.0f / 256.0f, null);
        TriangleStripArray triangles = new TriangleStripArray(indices, stripLengths);
        return new Mesh(buffer, triangles, groundAppearance);
    }


    private int sideTile(int topType, int y, int height) {
        if (topType == 2) return 1; // stone
        if (topType == 1) return 2; // dirt
        return y == height - 1 ? 3 : 2; // grass lip, dirt below
    }

    private int addCube(short[] positions, short[] texCoords, int[] indices, int[] stripLengths, int vertex, int strip, int x, int y, int z, int tx, int ty) {
        // The cut end of a log uses the log-top atlas tile, not bark.
        int topX = (tx == 4 && ty == 1) ? 5 : tx;
        int topY = (tx == 4 && ty == 1) ? 1 : ty;
        vertex = addQuad(positions, texCoords, indices, stripLengths, vertex, strip++, x, y+1, z, x, y+1, z+1, x+1, y+1, z, x+1, y+1, z+1, topX, topY);
        vertex = addQuad(positions, texCoords, indices, stripLengths, vertex, strip++, x, y, z+1, x, y+1, z+1, x+1, y, z+1, x+1, y+1, z+1, tx, ty);
        vertex = addQuad(positions, texCoords, indices, stripLengths, vertex, strip++, x+1, y, z, x+1, y+1, z, x, y, z, x, y+1, z, tx, ty);
        vertex = addQuad(positions, texCoords, indices, stripLengths, vertex, strip++, x, y, z, x, y+1, z, x, y, z+1, x, y+1, z+1, tx, ty);
        vertex = addQuad(positions, texCoords, indices, stripLengths, vertex, strip++, x+1, y, z+1, x+1, y+1, z+1, x+1, y, z, x+1, y+1, z, tx, ty);
        vertex = addQuad(positions, texCoords, indices, stripLengths, vertex, strip++, x, y, z, x, y+1, z, x+1, y, z, x+1, y+1, z, tx, ty);
        return vertex;
    }
    private void breakBlock() {
        int[] hit=raycastSurface(); if(hit==null)return; int x=hit[0],z=hit[1],h=terrain.getHeight(hit[0],hit[1]); if(h<=1)return;
        int type=terrain.getTopType(x,z); if(type>=0&&type<inventory.length)inventory[type]++; terrain.setHeight(x,z,h-1); terrain.setTopType(x,z,1);
        particleTicks=8; loadedCenterX=-99; updateChunks(); cameraY=terrain.getHeight((int)cameraX,(int)cameraZ)+1.65f; updateCamera();
    }
    private void placeBlock() {
        if(inventory[selectedSlot]<=0)return; int[] hit=raycastSurface(); if(hit==null)return; int x=hit[0],z=hit[1],h=terrain.getHeight(hit[0],hit[1]); if(h>=TerrainGenerator.HEIGHT-3)return;
        terrain.setHeight(x,z,h+1); terrain.setTopType(x,z,selectedSlot); inventory[selectedSlot]--; loadedCenterX=-99; updateChunks(); cameraY=terrain.getHeight((int)cameraX,(int)cameraZ)+1.65f; updateCamera();
    }
    private int[] raycastSurface() {
        double yr=Math.PI*pitch/180.0,xr=Math.PI*yaw/180.0; float dx=(float)(Math.sin(xr)*Math.cos(yr)),dz=(float)(-Math.cos(xr)*Math.cos(yr)),dy=(float)Math.sin(yr);
        for(float d=0.5f;d<=5.0f;d+=0.15f){int x=(int)(cameraX+dx*d),z=(int)(cameraZ+dz*d);if(x<1||z<1||x>=TerrainGenerator.SIZE-1||z>=TerrainGenerator.SIZE-1)continue;float y=cameraY+dy*d;int surface=terrain.getHeight(x,z);if(y<=surface+0.25f&&y>=surface-1.0f)return new int[]{x,z};}return null;
    }

    private int addQuad(short[] positions, short[] texCoords, int[] indices,
                        int[] stripLengths, int vertex, int strip,
                        int x0, int y0, int z0, int x1, int y1, int z1,
                        int x2, int y2, int z2, int x3, int y3, int z3,
                        int tileX, int tileY) {
        vertex = addVertex(positions, texCoords, indices, vertex, x0, y0, z0,
                           tileX, tileY, 0);
        vertex = addVertex(positions, texCoords, indices, vertex, x1, y1, z1,
                           tileX, tileY, 1);
        vertex = addVertex(positions, texCoords, indices, vertex, x2, y2, z2,
                           tileX, tileY, 2);
        vertex = addVertex(positions, texCoords, indices, vertex, x3, y3, z3,
                           tileX, tileY, 3);
        stripLengths[strip] = 4;
        return vertex;
    }

    private int addVertex(short[] positions, short[] texCoords, int[] indices,
                          int vertex, int x, int y, int z,
                          int tileX, int tileY, int corner) {
        int p = vertex * 3;
        positions[p] = (short) x;
        positions[p + 1] = (short) y;
        positions[p + 2] = (short) z;
        indices[vertex] = vertex;

        // Slight inset avoids sampling adjacent tiles at atlas boundaries.
        int left = tileX * 16 + 1;
        int right = (tileX + 1) * 16 - 1;
        int top = tileY * 16 + 1;
        int bottom = (tileY + 1) * 16 - 1;
        int u, v;
        if (tileX == 3 || tileX == 4) { u = (corner == 0 || corner == 1) ? left : right; v = (corner == 0 || corner == 2) ? bottom : top; }
        else { u = (corner == 0 || corner == 2) ? left : right; v = (corner == 0 || corner == 1) ? bottom : top; }
        int t = vertex * 2;
        texCoords[t] = (short) u;
        texCoords[t + 1] = (short) v;
        return vertex + 1;
    }
}
