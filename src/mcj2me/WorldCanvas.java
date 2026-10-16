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

    private final IndevViewerMIDlet midlet;
    private final TerrainGenerator terrain = new TerrainGenerator();
    private Graphics3D graphics3D;
    private World world;
    private Group terrainGroup;
    private Camera camera;

    private Appearance groundAppearance;
    private Mesh[][] activeChunks = new Mesh[8][8];
    private Thread thread;
    private boolean running;
    private boolean paused;
    private boolean forward, backward, strafeLeft, strafeRight;
    private boolean lookUp, lookDown, turnLeft, turnRight;
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
        drawHud(g);
    }
    private void drawHud(Graphics g) {
        int w = getWidth(), h = getHeight(); g.setColor(0x101820); g.fillRect(3, 3, Math.min(w - 6, 112), 15);
        g.setColor(0xFFFFFF); g.drawString("MC J2ME", 7, 4, Graphics.TOP | Graphics.LEFT);
        int sy = h - 23;
        for (int i = 0; i < 3; i++) { int sx = 4 + i * 54; g.setColor(i == selectedSlot ? 0xE8D070 : 0x303840); g.fillRect(sx, sy, 50, 19); g.setColor(0x101820); g.drawRect(sx, sy, 49, 18); g.setColor(0xFFFFFF); g.drawString(BLOCK_NAMES[i] + " " + inventory[i], sx + 2, sy + 2, Graphics.TOP | Graphics.LEFT); }
        int cx = w / 2, cy = h / 2; g.setColor(0xFFFFFF); g.drawLine(cx - 3, cy, cx + 3, cy); g.drawLine(cx, cy - 3, cx, cy + 3);
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
        // Fire/center jumps, like the jump button in Minecraft.
        if (action == FIRE && !airborne) {
            verticalVelocity = JUMP_SPEED;
            airborne = true;
        }
    }

    protected synchronized void keyReleased(int keyCode) {
        int action = getGameAction(keyCode);

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
    }

    private void updateMovement() {
        boolean changed = false;
        boolean positionChanged = false;

        if (turnLeft) { yaw -= TURN_SPEED; changed = true; }
        if (turnRight) { yaw += TURN_SPEED; changed = true; }
        if (lookUp) {
            pitch += TURN_SPEED;
            if (pitch > 75.0f) pitch = 75.0f;
            changed = true;
        }
        if (lookDown) {
            pitch -= TURN_SPEED;
            if (pitch < -75.0f) pitch = -75.0f;
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

        if (velocityX != 0.0f || velocityZ != 0.0f) {
            cameraX += velocityX;
            cameraZ += velocityZ;
            positionChanged = true;
            changed = true;
        }

        if (cameraX < 1.0f) { cameraX = 1.0f; velocityX = 0.0f; }
        if (cameraZ < 1.0f) { cameraZ = 1.0f; velocityZ = 0.0f; }
        if (cameraX > 126.0f) { cameraX = 126.0f; velocityX = 0.0f; }
        if (cameraZ > 126.0f) { cameraZ = 126.0f; velocityZ = 0.0f; }

        float groundY = terrain.getHeightSmooth(cameraX, cameraZ) + 1.65f;
        if (airborne) {
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
                }
            }
        }
        loadedCenterX = centerX;
        loadedCenterZ = centerZ;
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
                if (h > terrain.getHeight(x - 1, z)) quads++;
                if (h > terrain.getHeight(x + 1, z)) quads++;
                if (h > terrain.getHeight(x, z - 1)) quads++;
                if (h > terrain.getHeight(x, z + 1)) quads++;
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
                        x, sy, z, x, sy + 1, z, 3, 0);
                }
                neighbor = terrain.getHeight(x + 1, z);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x + 1, sy, z, x + 1, sy + 1, z,
                        x + 1, sy, z + 1, x + 1, sy + 1, z + 1, 3, 0);
                }
                neighbor = terrain.getHeight(x, z - 1);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x, sy, z, x, sy + 1, z,
                        x + 1, sy, z, x + 1, sy + 1, z, 3, 0);
                }
                neighbor = terrain.getHeight(x, z + 1);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x + 1, sy, z + 1, x + 1, sy + 1, z + 1,
                        x, sy, z + 1, x, sy + 1, z + 1, 3, 0);
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

    private int addCube(short[] positions, short[] texCoords, int[] indices, int[] stripLengths, int vertex, int strip, int x, int y, int z, int tx, int ty) {
        vertex = addQuad(positions, texCoords, indices, stripLengths, vertex, strip++, x, y+1, z, x, y+1, z+1, x+1, y+1, z, x+1, y+1, z+1, tx, ty);
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
