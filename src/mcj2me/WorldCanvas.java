package mcj2me;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

final class WorldCanvas extends Canvas implements Runnable {
    private static final int CHUNK = 16;
    private static final int RADIUS = 1;
    private static final float MOVE_SPEED = 0.75f;
    private static final float TURN_SPEED = 3.0f;

    private final IndevViewerMIDlet midlet;
    private final TerrainGenerator terrain = new TerrainGenerator();
    private final Graphics3D graphics3D = Graphics3D.getInstance();
    private final World world = new World();
    private final Group terrainGroup = new Group();
    private final Camera camera = new Camera();

    private Appearance groundAppearance;
    private Mesh[][] activeChunks = new Mesh[8][8];
    private Thread thread;
    private boolean running;
    private boolean paused;
    private boolean forward, backward, strafeLeft, strafeRight;
    private boolean rise, descend, turnLeft, turnRight;
    private float cameraX = 64.0f;
    private float cameraY;
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
        } catch (Exception e) {
            errorMessage = "M3G setup failed: " + e.toString();
        } catch (OutOfMemoryError e) {
            errorMessage = "Not enough Java heap for M3G.";
        }
    }

    private void setupScene() throws Exception {
        Image atlas = Image.createImage("/textures.png");
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

        cameraY = terrain.getHeight(64, 105) + 15.0f;
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
        try {
            camera.setPerspective(70.0f,
                (float) getWidth() / (float) Math.max(1, getHeight()),
                0.1f, 180.0f);
            graphics3D.bindTarget(g);
            graphics3D.render(world);
            graphics3D.releaseTarget();
        } catch (Exception e) {
            try { graphics3D.releaseTarget(); } catch (Exception ignored) {}
            g.setColor(0x101820);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(0xFFFFFF);
            g.drawString("M3G render error", 4, 4, Graphics.TOP | Graphics.LEFT);
            g.drawString(e.toString(), 4, 24, Graphics.TOP | Graphics.LEFT);
        }
    }

    protected synchronized void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP) forward = true;
        if (action == DOWN) backward = true;
        if (action == LEFT) turnLeft = true;
        if (action == RIGHT) turnRight = true;

        if (keyCode == 'w' || keyCode == 'W') forward = true;
        if (keyCode == 's' || keyCode == 'S') backward = true;
        if (keyCode == 'a' || keyCode == 'A') strafeLeft = true;
        if (keyCode == 'd' || keyCode == 'D') strafeRight = true;
        if (keyCode == 'q' || keyCode == 'Q') descend = true;
        if (keyCode == 'e' || keyCode == 'E') rise = true;
        if (action == FIRE) {
            pitch -= 2.0f;
            if (pitch < -75.0f) pitch = -75.0f;
            updateCamera();
        }
    }

    protected synchronized void keyReleased(int keyCode) {
        int action = getGameAction(keyCode);
        if (action == UP) forward = false;
        if (action == DOWN) backward = false;
        if (action == LEFT) turnLeft = false;
        if (action == RIGHT) turnRight = false;
        if (keyCode == 'w' || keyCode == 'W') forward = false;
        if (keyCode == 's' || keyCode == 'S') backward = false;
        if (keyCode == 'a' || keyCode == 'A') strafeLeft = false;
        if (keyCode == 'd' || keyCode == 'D') strafeRight = false;
        if (keyCode == 'q' || keyCode == 'Q') descend = false;
        if (keyCode == 'e' || keyCode == 'E') rise = false;
    }

    private synchronized void clearKeys() {
        forward = backward = strafeLeft = strafeRight = false;
        rise = descend = turnLeft = turnRight = false;
    }

    private void updateMovement() {
        boolean changed = false;
        if (turnLeft) { yaw -= TURN_SPEED; changed = true; }
        if (turnRight) { yaw += TURN_SPEED; changed = true; }

        float radians = (float) (yaw * Math.PI / 180.0);
        float forwardX = (float) Math.sin(radians);
        float forwardZ = -(float) Math.cos(radians);
        if (forward) {
            cameraX += forwardX * MOVE_SPEED;
            cameraZ += forwardZ * MOVE_SPEED;
            changed = true;
        }
        if (backward) {
            cameraX -= forwardX * MOVE_SPEED;
            cameraZ -= forwardZ * MOVE_SPEED;
            changed = true;
        }
        if (strafeLeft) {
            cameraX -= (float) Math.cos(radians) * MOVE_SPEED;
            cameraZ -= (float) Math.sin(radians) * MOVE_SPEED;
            changed = true;
        }
        if (strafeRight) {
            cameraX += (float) Math.cos(radians) * MOVE_SPEED;
            cameraZ += (float) Math.sin(radians) * MOVE_SPEED;
            changed = true;
        }
        if (rise) { cameraY += MOVE_SPEED; changed = true; }
        if (descend) { cameraY -= MOVE_SPEED; changed = true; }

        if (cameraX < 1.0f) cameraX = 1.0f;
        if (cameraZ < 1.0f) cameraZ = 1.0f;
        if (cameraX > 127.0f) cameraX = 127.0f;
        if (cameraZ > 127.0f) cameraZ = 127.0f;
        if (cameraY < 2.0f) cameraY = 2.0f;
        if (cameraY > 63.0f) cameraY = 63.0f;

        if (changed) {
            updateCamera();
            updateChunks();
        }
    }

    private void updateCamera() {
        Transform transform = new Transform();
        transform.setIdentity();
        transform.postRotate(yaw, 0.0f, 1.0f, 0.0f);
        transform.postRotate(pitch, 1.0f, 0.0f, 0.0f);
        transform.setTranslation(cameraX, cameraY, cameraZ);
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
                vertex = addQuad(positions, texCoords, indices, stripLengths,
                    vertex, strip++, x, h, z, x, h, z + 1,
                    x + 1, h, z, x + 1, h, z + 1, 0, 0);

                int neighbor = terrain.getHeight(x - 1, z);
                if (h > neighbor) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x, neighbor, z + 1, x, h, z + 1,
                        x, neighbor, z, x, h, z, 3, 0);
                }
                neighbor = terrain.getHeight(x + 1, z);
                if (h > neighbor) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x + 1, neighbor, z, x + 1, h, z,
                        x + 1, neighbor, z + 1, x + 1, h, z + 1, 3, 0);
                }
                neighbor = terrain.getHeight(x, z - 1);
                if (h > neighbor) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x, neighbor, z, x, h, z,
                        x + 1, neighbor, z, x + 1, h, z, 3, 0);
                }
                neighbor = terrain.getHeight(x, z + 1);
                if (h > neighbor) {
                    vertex = addQuad(positions, texCoords, indices, stripLengths,
                        vertex, strip++, x + 1, neighbor, z + 1, x + 1, h, z + 1,
                        x, neighbor, z + 1, x, h, z + 1, 3, 0);
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
        int u = (corner == 0 || corner == 2) ? left : right;
        int v = (corner == 0 || corner == 1) ? bottom : top;
        int t = vertex * 2;
        texCoords[t] = (short) u;
        texCoords[t + 1] = (short) v;
        return vertex + 1;
    }
}
