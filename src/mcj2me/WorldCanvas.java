package mcj2me;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

final class WorldCanvas extends Canvas implements Runnable {
    // Keep the movement IDs local so this class does not depend on MIDlet constant visibility.
    private static final int CONTROL_FORWARD = 0, CONTROL_BACKWARD = 1;
    private static final int CONTROL_STRAFE_LEFT = 2, CONTROL_STRAFE_RIGHT = 3;
    private static final int CONTROL_LOOK_UP = 4, CONTROL_LOOK_DOWN = 5;
    private static final int CONTROL_TURN_LEFT = 6, CONTROL_TURN_RIGHT = 7;
    private static final int CONTROL_JUMP = 8, CONTROL_BREAK = 9, CONTROL_PLACE = 10;
    private static final int CHUNK = 16;
    private static final int RADIUS = 1;
    private static final float MOVE_SPEED = 0.20f;
    private static final float MOVE_ACCEL = 0.035f;
    private static final float FRICTION = 0.78f;
    private static final float GRAVITY = 0.035f;
    private static final float JUMP_SPEED = 0.38f;
    private static final int DAY_TICKS = 10500; // 7 minutes at the 40 ms game tick.
    private int dayTick = 0;
    private float daylightLevel = 1.0f;
    private Image nightOverlay;
    private int nightOverlayWidth = -1, nightOverlayHeight = -1, nightOverlayAlpha = -1;
    private static final int WATER_LEVEL = TerrainGenerator.WATER_LEVEL;
    // Keep the distant ocean below the terrain skyline; inland lakes retain WATER_LEVEL.
    private static final int HORIZON_SEA_LEVEL = 10;
    private static final float WATER_ALPHA = 0.66f;

    private final IndevViewerMIDlet midlet;
    private final TerrainGenerator terrain;
    private Graphics3D graphics3D;
    private World world;
    private Group terrainGroup;
    private Group waterGroup;
    private Group cloudGroup;
    private Group decorationGroup;
    private Texture2D terrainTexture;
    private VertexBuffer[] decorationBuffers = new VertexBuffer[5];
    private Appearance[] decorationAppearances = new Appearance[5];
    private TriangleStripArray decorationTriangles;
    private static final int MAX_DECORATIONS = 32;
    private final Mesh[] decorationMeshes = new Mesh[MAX_DECORATIONS];
    private final int[] decorationTypes = new int[MAX_DECORATIONS];
    private final float[] decorationX = new float[MAX_DECORATIONS];
    private final float[] decorationY = new float[MAX_DECORATIONS];
    private final float[] decorationZ = new float[MAX_DECORATIONS];
    private final float[] decorationYaw = new float[MAX_DECORATIONS];
    private final boolean[] decorationOpen = new boolean[MAX_DECORATIONS];
    private int decorationCount = 0;
    private final Light[] torchLights = new Light[4];
    private int torchLightUpdateTicks = 0;
    private Texture2D waterTexture;
    private Texture2D cloudTexture;
    private Image guiImage;
    private Image guiBlocksImage;
    private Image inventoryImage;
    private final Image[] blockIcons = new Image[40];
    private boolean inventoryOpen = false;
    private int inventoryCursor = 0;
    private int inventoryPageStart = 0;
    private float cloudPhase = 0.0f;
    private Appearance waterAppearance;
    private Fog distanceFog;
    private Background skyBackground;
    private Light ambientLight;
    private PassiveMob passiveMobs;
    private HostileMob hostileMobs;
    private Image underwaterOverlay;
    private int overlayWidth = -1, overlayHeight = -1;
    private Mesh[][] activeWaterChunks = new Mesh[8][8];
    private float waterPhase = 0.0f;
    private int splashTicks = 0;
    private boolean wasInWater = false;
    private Camera camera;

    private Appearance groundAppearance;
    private Image terrainAtlasImage;
    private Appearance[] terrainTileAppearances = new Appearance[256];
    private Group[][] activeChunks = new Group[8][8];
    // Tracks even empty chunks whose terrain/water builders return no mesh.
    private boolean[][] builtChunks = new boolean[8][8];
    private int loadedCenterX = -99;
    private int loadedCenterZ = -99;
    private int loadedFacingSector = -99;
    private boolean chunksDirty = true;
    private boolean lastHumbleMode = IndevViewerMIDlet.humbleMode;
    private final int[] shadeCacheKeys = new int[512];
    private final int[] shadeCacheValues = new int[512];
    // Reuse the small greedy-meshing workspace instead of allocating roughly
    // 13 KB of temporary grids/rectangle lists for every streamed chunk.
    private final int[][] greedyKeys = new int[CHUNK][CHUNK];
    private final int[][] greedyHeights = new int[CHUNK][CHUNK];
    private final int[][] greedyBases = new int[CHUNK][CHUNK];
    private final int[][] greedyGlows = new int[CHUNK][CHUNK];
    private final boolean[][] greedyUsed = new boolean[CHUNK][CHUNK];
    private final int[] greedyRectX = new int[CHUNK * CHUNK];
    private final int[] greedyRectZ = new int[CHUNK * CHUNK];
    private final int[] greedyRectW = new int[CHUNK * CHUNK];
    private final int[] greedyRectD = new int[CHUNK * CHUNK];
    private final int[] greedyRectH = new int[CHUNK * CHUNK];
    private final int[] greedyRectKey = new int[CHUNK * CHUNK];
    private final int[] greedyRectBase = new int[CHUNK * CHUNK];
    private final int[] greedyRectGlow = new int[CHUNK * CHUNK];
    private final int[] greedyPerTile = new int[256];
    private Thread thread;
    private boolean running;
    private int frameSkipCounter = 0;
    private boolean paused;
    private boolean forward, backward, strafeLeft, strafeRight;
    private boolean lookUp, lookDown, turnLeft, turnRight;
    private boolean jumpHeld;
    // Indev-era terrain atlas tiles: surface blocks, ores, then bedrock.
    private static final int[] BLOCK_TILE_X = {
        0, 2, 1, 5, 4, 2, 1, 2, 2, 1, 8, 4, 2, 3, 6, 5, 0, 13, 12, 15, 1,
        6, 7, 8,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };
    private static final int[] BLOCK_TILE_Y = {
        0, 0, 0, 1, 3, 1, 2, 2, 3, 1, 0, 0, 4, 1, 0, 3, 5, 0, 0, 0, 5,
        1, 1, 1,
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7
    };
    // 48x48 block icons in gui_blocks.png, mapped to grass/dirt/stone/log/leaves.
    // 48x48 cells verified against gui_blocks.png: grass, dirt, stone,
    // oak log, and leaves. The sheet is a block-icon atlas, not textures.png.
    private static final int[] GUI_ICON_X = {432, 144, 0, 288, 96};
    private static final int[] GUI_ICON_Y = {0, 288, 0, 48, 288};
    private static final int BLOCK_TORCH = 16, BLOCK_YELLOW_FLOWER = 17, BLOCK_RED_FLOWER = 18, BLOCK_SAPLING = 19, BLOCK_DOOR = 20;
    private static final String[] BLOCK_NAMES = {
        "GRASS", "DIRT", "STONE", "WOOD", "LEAVES", "COAL ORE", "IRON ORE", "GOLD ORE", "DIAMOND ORE",
        "BEDROCK", "TNT", "OAK PLANKS", "SAND", "GRAVEL", "BRICK", "OBSIDIAN",
        "TORCH", "YELLOW FLOWER", "RED FLOWER", "SAPLING", "DOOR",
        "IRON BLOCK", "GOLD BLOCK", "DIAMOND BLOCK",
        "WHITE WOOL", "ORANGE WOOL", "MAGENTA WOOL", "LIGHT BLUE WOOL", "YELLOW WOOL", "LIME WOOL",
        "PINK WOOL", "GRAY WOOL", "LIGHT GRAY WOOL", "CYAN WOOL", "PURPLE WOOL", "BLUE WOOL",
        "BROWN WOOL", "GREEN WOOL", "RED WOOL", "BLACK WOOL"
    };
    private final int[] inventory = {
        64, 64, 64, 64, 64, 16, 8, 8, 4, 0, 8, 64, 32, 32, 16, 8,
        16, 4, 4, 4, 2, 8, 8, 8,
        16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16
    };
    private int selectedSlot = 0, particleTicks = 0, actionCooldown = 0;
    private int tntFuseTicks = 0, tntX = -1, tntZ = -1, tntFlashCounter = 0;
    private boolean tntFlashOn = false;
    private static final int BLOCK_PARTICLE_COUNT = 12;
    private static final int BLOCK_PARTICLE_LIFE = 20;
    private final float[] blockParticleX = new float[BLOCK_PARTICLE_COUNT];
    private final float[] blockParticleY = new float[BLOCK_PARTICLE_COUNT];
    private final float[] blockParticleZ = new float[BLOCK_PARTICLE_COUNT];
    private final float[] blockParticleVX = new float[BLOCK_PARTICLE_COUNT];
    private final float[] blockParticleVY = new float[BLOCK_PARTICLE_COUNT];
    private final float[] blockParticleVZ = new float[BLOCK_PARTICLE_COUNT];
    private final float[] blockParticleSize = new float[BLOCK_PARTICLE_COUNT];
    private final Mesh[] blockParticleMeshes = new Mesh[BLOCK_PARTICLE_COUNT];
    private Group blockParticleGroup;
    private VertexBuffer blockParticleBuffer;
    private TriangleStripArray blockParticleTriangles;
    private Appearance[] blockParticleAppearances = new Appearance[BLOCK_TILE_X.length];
    private Appearance mobDeathParticleAppearance;
    private float cameraX = 64.0f;
    private float cameraY;
    private float velocityX, velocityZ, verticalVelocity;
    private boolean airborne;
    private float cameraZ = 105.0f;
    private float yaw = 0.0f;
    private float pitch = -28.0f;
    private String errorMessage;
    private final String worldName;
    private final int worldSeed;
    private final int worldId;

    WorldCanvas(IndevViewerMIDlet owner, String name, int seed) {
        this(owner, name, seed, 0, null);
    }

    WorldCanvas(IndevViewerMIDlet owner, String name, int seed, int id, byte[] savedTerrain) {
        midlet = owner;
        worldName = name;
        worldSeed = seed;
        worldId = id;
        terrain = new TerrainGenerator(seed);
        setFullScreenMode(true);
        try {
            if (savedTerrain != null) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(savedTerrain));
                int terrainLength = in.readInt();
                if (terrainLength < 0 || terrainLength > savedTerrain.length - 4)
                    throw new Exception("Invalid saved terrain");
                byte[] terrainData = new byte[terrainLength];
                in.readFully(terrainData);
                terrain.loadState(terrainData);
                in.close();
            }
            setupScene();
            if (savedTerrain != null) restorePlayerState(savedTerrain);
            ensurePlayerNotBuried();
        } catch (OutOfMemoryError e) {
            errorMessage = "Not enough Java heap for M3G.";
        } catch (Exception e) {
            errorMessage = "M3G setup failed: " + e.toString();
        }
    }

    String getWorldName() { return worldName; }
    int getWorldSeed() { return worldSeed; }
    int getWorldId() { return worldId; }
    byte[] saveTerrainState() throws Exception {
        byte[] terrainData = terrain.saveState();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(terrainData.length);
        out.write(terrainData);
        out.writeFloat(cameraX); out.writeFloat(cameraY); out.writeFloat(cameraZ);
        out.writeFloat(yaw); out.writeFloat(pitch);
        out.writeInt(dayTick); out.writeInt(selectedSlot);
        out.writeInt(inventory.length);
        for (int i = 0; i < inventory.length; i++) out.writeInt(inventory[i]);
        out.writeInt(decorationCount);
        for (int i = 0; i < decorationCount; i++) {
            out.writeInt(decorationTypes[i]);
            out.writeFloat(decorationX[i]); out.writeFloat(decorationY[i]); out.writeFloat(decorationZ[i]);
            out.writeFloat(decorationYaw[i]); out.writeBoolean(decorationOpen[i]);
        }
        out.flush();
        return bytes.toByteArray();
    }
    private void restorePlayerState(byte[] state) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(state));
        int terrainLength = in.readInt();
        if (terrainLength < 0 || terrainLength > state.length - 4)
            throw new Exception("Invalid saved world");
        byte[] terrainData = new byte[terrainLength];
        in.readFully(terrainData);
        cameraX = in.readFloat(); cameraY = in.readFloat(); cameraZ = in.readFloat();
        yaw = in.readFloat(); pitch = in.readFloat();
        float savedPlayerYaw = yaw;
        dayTick = in.readInt();
        selectedSlot = in.readInt();
        if (selectedSlot < 0 || selectedSlot >= inventory.length) selectedSlot = 0;
        int invCount = in.readInt();
        if (invCount != inventory.length) throw new Exception("Invalid saved inventory");
        for (int i = 0; i < inventory.length; i++) inventory[i] = Math.max(0, in.readInt());
        int savedCount = in.readInt();
        if (savedCount < 0 || savedCount > MAX_DECORATIONS)
            throw new Exception("Invalid saved decorations");
        decorationCount = 0;
        for (int i = 0; i < savedCount; i++) {
            int type = in.readInt();
            float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
            float savedYaw = in.readFloat();
            boolean opened = in.readBoolean();
            if (type < BLOCK_TORCH || type > BLOCK_DOOR) continue;
            yaw = savedYaw;
            if (placeDecoration(type, x, y, z)) {
                int at = decorationCount - 1;
                decorationYaw[at] = savedYaw;
                decorationOpen[at] = opened;
                decorationMeshes[at].setOrientation(savedYaw + (opened ? 90.0f : 0.0f), 0.0f, 1.0f, 0.0f);
            }
        }
        yaw = savedPlayerYaw;
        in.close();
        updateCamera();
        updateTorchLights();
        reloadEditedChunks();
    }

    /**
     * Height smoothing is visual only: spawning at the smoothed height can put
     * the camera inside the actual height-map column on steep slopes. Also
     * repair older saves whose player position was stored below the surface.
     */
    private void ensurePlayerNotBuried() {
        cameraX = clampWorldX(cameraX);
        cameraZ = clampWorldZ(cameraZ);
        float groundY = terrain.getHeight((int) cameraX, (int) cameraZ) + 1.65f;
        if (cameraY < groundY) {
            cameraY = groundY;
            verticalVelocity = 0.0f;
            airborne = false;
        }
        updateCamera();
    }

    boolean hasSetupError() { return errorMessage != null; }
    String getSetupError() { return errorMessage; }

    private void setupScene() throws Exception {
        // Initialize M3G objects inside the guarded setup path so API/linkage
        // failures can be reported by the constructor instead of escaping
        // from field initializers.
        graphics3D = Graphics3D.getInstance();
        world = new World();
        terrainGroup = new Group();
        waterGroup = new Group();
        cloudGroup = new Group();
        blockParticleGroup = new Group();
        decorationGroup = new Group();
        camera = new Camera();

        Image atlas = createWoolAtlas(Image.createImage("/mcj2me/textures.png"));
        terrainAtlasImage = atlas;
        guiImage = Image.createImage("/mcj2me/gui.png");
        guiBlocksImage = Image.createImage("/mcj2me/gui_blocks.png");
        inventoryImage = Image.createImage("/mcj2me/inventory.png");
        blockIcons[0] = createGuiBlockIcon(GUI_ICON_X[0], GUI_ICON_Y[0]);
        blockIcons[1] = createGuiBlockIcon(GUI_ICON_X[1], GUI_ICON_Y[1]);
        blockIcons[2] = createGuiBlockIcon(GUI_ICON_X[2], GUI_ICON_Y[2]);
        blockIcons[3] = createGuiBlockIcon(GUI_ICON_X[3], GUI_ICON_Y[3]);
        blockIcons[4] = createGuiBlockIcon(GUI_ICON_X[4], GUI_ICON_Y[4]);
        for (int bi = 5; bi < BLOCK_NAMES.length; bi++) {
            blockIcons[bi] = createTerrainBlockIcon(atlas, BLOCK_TILE_X[bi], BLOCK_TILE_Y[bi]);
        }
        Image cloudsImage = Image.createImage("/mcj2me/clouds.png");
        Image2D image2D = new Image2D(Image2D.RGBA, atlas);
        Texture2D texture = new Texture2D(image2D);
        texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        terrainTexture = texture;

        groundAppearance = new Appearance();
        groundAppearance.setTexture(0, texture);
        // Opaque terrain faces are wound outward, so discard back faces.
        // Transparent water/clouds keep two-sided rendering below because the
        // camera can see them from underneath.
        PolygonMode terrainPolygonMode = new PolygonMode();
        // Keep terrain two-sided until every generated tree-face winding is verified.
        terrainPolygonMode.setCulling(PolygonMode.CULL_NONE);
        terrainPolygonMode.setShading(PolygonMode.SHADE_FLAT);
        groundAppearance.setPolygonMode(terrainPolygonMode);

        PolygonMode transparentPolygonMode = new PolygonMode();
        transparentPolygonMode.setCulling(PolygonMode.CULL_NONE);
        transparentPolygonMode.setShading(PolygonMode.SHADE_FLAT);

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
        waterAppearance.setPolygonMode(transparentPolygonMode);
        CompositingMode waterBlend = new CompositingMode();
        waterBlend.setBlending(CompositingMode.ALPHA);
        waterBlend.setDepthWriteEnable(false);
        waterBlend.setAlphaWriteEnable(false);
        waterAppearance.setCompositingMode(waterBlend);
        cloudTexture = new Texture2D(new Image2D(Image2D.RGBA, cloudsImage));
        cloudTexture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        cloudTexture.setWrapping(Texture2D.WRAP_REPEAT, Texture2D.WRAP_REPEAT);
        Appearance cloudAppearance = new Appearance();
        cloudAppearance.setTexture(0, cloudTexture);
        cloudAppearance.setPolygonMode(transparentPolygonMode);
        CompositingMode cloudBlend = new CompositingMode();
        cloudBlend.setBlending(CompositingMode.ALPHA);
        cloudBlend.setDepthWriteEnable(false);
        cloudBlend.setAlphaWriteEnable(false);
        cloudAppearance.setCompositingMode(cloudBlend);

        skyBackground = new Background();
        skyBackground.setColor(0x87BFEA);
        world.setBackground(skyBackground);
        distanceFog = new Fog();
        distanceFog.setColor(0xB6CBE0);
        distanceFog.setLinear(IndevViewerMIDlet.fogFarDistance() * 0.38f,
            IndevViewerMIDlet.fogFarDistance());
        groundAppearance.setFog(distanceFog);
        waterAppearance.setFog(distanceFog);
        cloudAppearance.setFog(distanceFog);

        ambientLight = new Light();
        ambientLight.setMode(Light.AMBIENT);
        ambientLight.setColor(0xFFFFFF);
        world.addChild(ambientLight);

        cameraY = terrain.getHeight(64, 105) + 1.65f;
        camera.setPerspective(70.0f, 1.0f, 0.1f, 180.0f);
        world.addChild(camera);
        world.setActiveCamera(camera);
        world.addChild(terrainGroup);
        world.addChild(waterGroup);
        world.addChild(decorationGroup);
        setupDecorations();
        setupTorchLights();
        Mesh clouds = buildCloudPlane(cloudAppearance);
        if (clouds != null) cloudGroup.addChild(clouds);
        // A separate, very large sea surface continues beyond the finite map.
        Mesh horizonSea = buildHorizonSea();
        if (horizonSea != null) waterGroup.addChild(horizonSea);
        world.addChild(cloudGroup);
        setupBlockParticles(atlas);
        world.addChild(blockParticleGroup);
        passiveMobs = new PassiveMob(world, terrain, cameraX, cameraZ);
        hostileMobs = new HostileMob(world, terrain, cameraX, cameraZ);
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

    synchronized void resume() {
        if (running) {
            paused = false;
            clearKeys();
            repaint();
        } else {
            start();
        }
    }

    synchronized void stop() {
        running = false;
        paused = true;
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
                // Stream at most one missing chunk pair per tick to avoid large
                // mesh-allocation spikes on memory-constrained J2ME devices.
                updateChunks();
                updateDayNight();
                animateWater();
                applyMobVisibility();
                if (!IndevViewerMIDlet.humbleMode) {
                    if (passiveMobs != null) passiveMobs.update(cameraX, cameraZ);
                    if (hostileMobs != null) hostileMobs.update(cameraX, cameraZ, daylightLevel <= 0.42f);
                }
                updateTntFuse();
                if (++torchLightUpdateTicks >= 10) {
                    torchLightUpdateTicks = 0;
                    updateTorchLights();
                }
                // Frameskip affects rendering only; simulation and chunk streaming keep running every tick.
                int skip = IndevViewerMIDlet.frameskip;
                if (skip < 0) skip = 0;
                if (skip > 5) skip = 5;
                if (frameSkipCounter >= skip) {
                    frameSkipCounter = 0;
                    repaint();
                } else {
                    frameSkipCounter++;
                }
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
        drawNightOverlay(g);
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

    private void drawNightOverlay(Graphics g) {
        if (IndevViewerMIDlet.humbleMode) return;
        int alpha = (int)((1.0f - daylightLevel) * 100.0f);
        alpha = (alpha / 25) * 25;
        if (alpha < 0) alpha = 0;
        if (alpha > 100) alpha = 100;
        if (alpha == 0) return;
        int w = getWidth(), h = getHeight();
        if (nightOverlay == null || nightOverlayWidth != w || nightOverlayHeight != h
                || nightOverlayAlpha != alpha) {
            int[] pixels = new int[w * h];
            int color = alpha << 24;
            for (int i = 0; i < pixels.length; i++) pixels[i] = color;
            nightOverlay = Image.createRGBImage(pixels, w, h, true);
            nightOverlayWidth = w;
            nightOverlayHeight = h;
            nightOverlayAlpha = alpha;
        }
        g.drawImage(nightOverlay, 0, 0, Graphics.TOP | Graphics.LEFT);
    }

    private boolean isCameraUnderwater() {
        return terrain.isWaterAt((int) cameraX, (int) cameraZ)
            && cameraY < WATER_LEVEL;
    }

    private void drawInventory(Graphics g) {
        int panelW = Math.min(176, inventoryImage.getWidth());
        int panelH = Math.min(166, inventoryImage.getHeight());
        int panelX = (getWidth() - panelW) / 2;
        int panelY = (getHeight() - panelH) / 2;
        g.drawRegion(inventoryImage, 0, 0, panelW, panelH, 0,
            panelX, panelY, Graphics.TOP | Graphics.LEFT);

        // The classic inventory's 9x3 main grid begins at (8, 84).
        int gridX = panelX + 8;
        int gridY = panelY + 84;
        for (int slot = 0; slot < 27; slot++) {
            int item = inventoryPageStart + slot;
            int sx = gridX + (slot % 9) * 18;
            int sy = gridY + (slot / 9) * 18;
            if (item < blockIcons.length) {
                g.drawImage(blockIcons[item], sx + 1, sy + 1,
                    Graphics.TOP | Graphics.LEFT);
                if (inventory[item] > 0) {
                    g.setColor(0xFFFFFF);
                    g.drawString(Integer.toString(inventory[item]), sx + 10,
                        sy + 8, Graphics.TOP | Graphics.LEFT);
                }
            }
        }
        int cursorX = gridX + (inventoryCursor % 9) * 18;
        int cursorY = gridY + (inventoryCursor / 9) * 18;
        g.setColor(0xFFFFFF);
        g.drawRect(cursorX - 1, cursorY - 1, 17, 17);
        g.drawRect(cursorX - 2, cursorY - 2, 19, 19);
        int selectedItem = inventoryPageStart + inventoryCursor;
        String selectedName = selectedItem < BLOCK_NAMES.length
            ? BLOCK_NAMES[selectedItem] : "EMPTY";
        g.setColor(0xFFFFFF);
        g.drawString(selectedName + "  (5: select, 7/9: page, *: close)",
            Math.max(2, panelX), Math.min(getHeight() - 16, panelY + panelH + 2),
            Graphics.TOP | Graphics.LEFT);
    }

    private boolean isSwimming() {
        return terrain.isWaterAt((int) cameraX, (int) cameraZ)
            && cameraY < WATER_LEVEL + 1.0f;
    }

    private void drawHud(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x101820);
        g.fillRect(3, 3, Math.min(w - 6, 112), 15);
        g.setColor(0xFFFFFF);
        g.drawString("MC J2ME", 7, 4, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xFFFFFF);
        g.drawString(daylightLevel > 0.42f ? "DAY" : "NIGHT", w - 5, 4,
            Graphics.TOP | Graphics.RIGHT);
        if (inventoryOpen) {
            drawInventory(g);
            return;
        }
        int barX = (w - 182) / 2;
        int barY = h - 23;
        g.drawRegion(guiImage, 0, 0, 182, 22, 0, barX, barY, Graphics.TOP | Graphics.LEFT);
        int firstBlock = selectedSlot - 4;
        if (firstBlock < 0) firstBlock = 0;
        if (firstBlock > BLOCK_NAMES.length - 9) firstBlock = BLOCK_NAMES.length - 9;
        for (int i = 0; i < 9; i++) {
            int block = firstBlock + i;
            int slotX = barX + 1 + i * 20;
            if (block == selectedSlot) g.drawRegion(guiImage, 0, 22, 24, 22, 0,
                slotX, barY, Graphics.TOP | Graphics.LEFT);
            g.drawImage(blockIcons[block], slotX + 4, barY + 3,
                Graphics.TOP | Graphics.LEFT);
            if (inventory[block] > 1) {
                g.setColor(0xFFFFFF);
                g.drawString(Integer.toString(inventory[block]), slotX + 11, barY + 10,
                    Graphics.TOP | Graphics.LEFT);
            }
        }
        int cx = w / 2, cy = h / 2;
        g.setColor(0xFFFFFF);
        g.drawLine(cx - 3, cy, cx + 3, cy);
        g.drawLine(cx, cy - 3, cx, cy + 3);
        if (splashTicks > 0) {
            g.setColor(0xBDEBFF);
            for (int b = 0; b < 5; b++) {
                int bx = cx + ((b * 13 + splashTicks * 3) % 23) - 11;
                int by = cy + ((b * 7 + splashTicks * 4) % 19) - 9;
                g.drawArc(bx, by, 2 + (splashTicks % 3), 2 + (splashTicks % 3), 0, 360);
            }
        }

    }

    protected synchronized void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);

        if (isBackKey(keyCode)) {
            if (inventoryOpen) {
                inventoryOpen = false;
                repaint();
            } else if (!paused) {
                pause();
                midlet.showPauseMenu();
            }
            return;
        }

        if (keyCode == '#') {
            inventoryOpen = true;
            clearKeys();
            repaint();
            return;
        }
        if (inventoryOpen) {
            if (keyCode == '*' || keyCode == 42) {
                inventoryOpen = false;
                repaint();
                return;
            }
            if (keyCode == '9' && inventoryPageStart == 0) {
                inventoryPageStart = 13;
                repaint();
                return;
            }
            if (keyCode == '7' && inventoryPageStart != 0) {
                inventoryPageStart = 0;
                repaint();
                return;
            }
            int col = inventoryCursor % 9;
            int row = inventoryCursor / 9;
            if (action == LEFT || keyCode == '4') col--;
            else if (action == RIGHT || keyCode == '6') col++;
            else if (action == UP || keyCode == '2') row--;
            else if (action == DOWN || keyCode == '8') row++;
            if (col < 0) col = 0;
            if (col > 8) col = 8;
            if (row < 0) row = 0;
            if (row > 2) row = 2;
            inventoryCursor = row * 9 + col;
            if (action == FIRE || keyCode == '5' || keyCode == ' ') {
                int item = inventoryPageStart + inventoryCursor;
                if (item < BLOCK_NAMES.length && item != TerrainGenerator.BLOCK_BEDROCK)
                    selectedSlot = item;
            }
            repaint();
            return;
        }

        if (IndevViewerMIDlet.controlPressed(CONTROL_FORWARD,keyCode,action)) forward=true;
        if (IndevViewerMIDlet.controlPressed(CONTROL_BACKWARD,keyCode,action)) backward=true;
        if (IndevViewerMIDlet.controlPressed(CONTROL_STRAFE_LEFT,keyCode,action)) strafeLeft=true;
        if (IndevViewerMIDlet.controlPressed(CONTROL_STRAFE_RIGHT,keyCode,action)) strafeRight=true;
        if (IndevViewerMIDlet.controlPressed(CONTROL_LOOK_UP,keyCode,action)) lookUp=true;
        if (IndevViewerMIDlet.controlPressed(CONTROL_LOOK_DOWN,keyCode,action)) lookDown=true;
        if (IndevViewerMIDlet.controlPressed(CONTROL_TURN_LEFT,keyCode,action)) turnLeft=true;
        if (IndevViewerMIDlet.controlPressed(CONTROL_TURN_RIGHT,keyCode,action)) turnRight=true;

        if (IndevViewerMIDlet.controlPressed(CONTROL_BREAK,keyCode,action)
                && actionCooldown == 0) {
            if (!attackMobs()) breakBlock();
            actionCooldown = 4;
        } else if (IndevViewerMIDlet.controlPressed(CONTROL_PLACE,keyCode,action)
                && actionCooldown == 0) {
            placeBlock();
            actionCooldown = 4;
        } else if (keyCode == '*' || keyCode == 42) {
            cycleSelectedBlock(1);
        } else if (keyCode == '7') {
            cycleSelectedBlock(-1);
        } else if (keyCode == '9') {
            cycleSelectedBlock(1);
        }

        if (IndevViewerMIDlet.controlPressed(CONTROL_JUMP,keyCode,action)) {
            jumpHeld = true;
            if (isSwimming()) {
                verticalVelocity = Math.max(verticalVelocity, 0.24f);
                airborne = true;
            } else if (!airborne) {
                verticalVelocity = JUMP_SPEED;
                airborne = true;
            }
        }
    }

    private void cycleSelectedBlock(int direction) {
        int next = selectedSlot;
        for (int tries = 0; tries < BLOCK_NAMES.length; tries++) {
            next += direction;
            if (next < 0) next = BLOCK_NAMES.length - 1;
            if (next >= BLOCK_NAMES.length) next = 0;
            if (next != TerrainGenerator.BLOCK_BEDROCK) { selectedSlot = next; return; }
        }
    }

    private boolean isBackKey(int keyCode) {
        // MIDP has no standard BACK key code; support common emulator and phone mappings.
        return keyCode == 27 || keyCode == 8 || keyCode == 127 || keyCode == -7 || keyCode == -6;
    }

    protected synchronized void keyReleased(int keyCode) {
        int action = getGameAction(keyCode);
        if (IndevViewerMIDlet.controlPressed(CONTROL_JUMP,keyCode,action))
            jumpHeld = false;
        if (IndevViewerMIDlet.controlPressed(CONTROL_FORWARD,keyCode,action)) forward=false;
        if (IndevViewerMIDlet.controlPressed(CONTROL_BACKWARD,keyCode,action)) backward=false;
        if (IndevViewerMIDlet.controlPressed(CONTROL_STRAFE_LEFT,keyCode,action)) strafeLeft=false;
        if (IndevViewerMIDlet.controlPressed(CONTROL_STRAFE_RIGHT,keyCode,action)) strafeRight=false;
        if (IndevViewerMIDlet.controlPressed(CONTROL_LOOK_UP,keyCode,action)) lookUp=false;
        if (IndevViewerMIDlet.controlPressed(CONTROL_LOOK_DOWN,keyCode,action)) lookDown=false;
        if (IndevViewerMIDlet.controlPressed(CONTROL_TURN_LEFT,keyCode,action)) turnLeft=false;
        if (IndevViewerMIDlet.controlPressed(CONTROL_TURN_RIGHT,keyCode,action)) turnRight=false;
    }

    private synchronized void clearKeys() {
        forward = backward = strafeLeft = strafeRight = false;
        lookUp = lookDown = turnLeft = turnRight = false;
        jumpHeld = false;
    }

    private void updateMovement() {
        boolean changed = false;

        if (turnLeft) { yaw -= IndevViewerMIDlet.sensitivity; changed = true; }
        if (turnRight) { yaw += IndevViewerMIDlet.sensitivity; changed = true; }
        if (lookUp) {
            pitch += IndevViewerMIDlet.sensitivity;
            if (pitch > 88.0f) pitch = 88.0f;
            changed = true;
        }
        if (lookDown) {
            pitch -= IndevViewerMIDlet.sensitivity;
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
        updateBlockParticles();
        if (changed) updateCamera();
        // Chunk streaming is advanced once per game-loop tick in run().
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

                // Leaves are solid too: reject movement when the player's
                // body overlaps any generated leaf cube at this X/Z column.
                int firstBodyY = Math.max(0, (int) Math.floor(feetY));
                int lastBodyY = Math.min(TerrainGenerator.HEIGHT - 1,
                    (int) Math.floor(cameraY + 0.10f));
                for (int by = firstBodyY; by <= lastBodyY; by++) {
                    if (terrain.isLeafBlockAt(bx, by, bz)) return false;
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
        // Keep the cloud quad near the camera to preserve vertex precision.
        // Snap to a coarse grid so this repositioning is invisible in motion.
        cloudGroup.setTranslation(
            ((int) cameraX / 64) * 64,
            0.0f,
            ((int) cameraZ / 64) * 64);
        updateChunkVisibility();
    }

    /**
     * Hide terrain and water chunks wholly behind the camera's forward plane.
     * Their meshes remain cached, so turning around re-enables them without
     * rebuilding geometry or creating garbage.
     */
    private int facingSector() {
        float normalized = yaw % 360.0f;
        if (normalized < 0.0f) normalized += 360.0f;
        return ((int) ((normalized + 22.5f) / 45.0f)) & 7;
    }

    private boolean isChunkInFront(int x, int z, float forwardX, float forwardZ) {
        float dx = x * CHUNK + CHUNK * 0.5f - cameraX;
        float dz = z * CHUNK + CHUNK * 0.5f - cameraZ;
        // Keep chunks whose bounds might still cross the camera's forward plane.
        float behindMargin = CHUNK * 0.71f + 1.0f;
        return dx * forwardX + dz * forwardZ >= -behindMargin;
    }

    private void updateChunkVisibility() {
        float radians = (float) (yaw * Math.PI / 180.0);
        float forwardX = (float) Math.sin(radians);
        float forwardZ = -(float) Math.cos(radians);

        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                boolean visible = isChunkInFront(x, z, forwardX, forwardZ);
                Group terrainChunk = activeChunks[x][z];
                if (terrainChunk != null) terrainChunk.setRenderingEnable(visible);
                Mesh waterMesh = activeWaterChunks[x][z];
                if (waterMesh != null) waterMesh.setRenderingEnable(visible);
            }
        }
    }

    /**
     * Direction-aware chunk streaming: meshes behind the camera are detached
     * and released for GC. Rebuilding is incremental (one terrain/water pair
     * per game tick), avoiding a large allocation spike when turning around.
     */
    void applySettingsChange() {
        frameSkipCounter = 0;
        if (lastHumbleMode != IndevViewerMIDlet.humbleMode) {
            lastHumbleMode = IndevViewerMIDlet.humbleMode;
            applyMobVisibility();
            reloadEditedChunks();
            updateDayNight();
            updateTorchLights();
            repaint();
        }
    }

    private void applyMobVisibility() {
        boolean showMobs = !IndevViewerMIDlet.humbleMode;
        if (passiveMobs != null) passiveMobs.setEnabled(showMobs);
        if (hostileMobs != null) hostileMobs.setEnabled(showMobs);
    }

    private synchronized void updateChunks() {
        int centerX = ((int) cameraX) / CHUNK;
        int centerZ = ((int) cameraZ) / CHUNK;
        int sector = facingSector();
        if (centerX != loadedCenterX || centerZ != loadedCenterZ
                || sector != loadedFacingSector) {
            chunksDirty = true;
        }
        if (!chunksDirty) return;

        float radians = (float) (yaw * Math.PI / 180.0f);
        float forwardX = (float) Math.sin(radians);
        float forwardZ = -(float) Math.cos(radians);

        // First discard chunks outside the local window or behind the player.
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                boolean inRange = Math.abs(x - centerX) <= RADIUS
                    && Math.abs(z - centerZ) <= RADIUS;
                // Retain nearby chunks behind the camera; unload only outside range.
                if (!inRange) {
                    Group old = activeChunks[x][z];
                    if (old != null) {
                        terrainGroup.removeChild(old);
                        activeChunks[x][z] = null;
                    }
                    Mesh oldWater = activeWaterChunks[x][z];
                    if (oldWater != null) {
                        waterGroup.removeChild(oldWater);
                        activeWaterChunks[x][z] = null;
                    }
                    builtChunks[x][z] = false;
                }
            }
        }

        loadedCenterX = centerX;
        loadedCenterZ = centerZ;
        loadedFacingSector = sector;

        // Prioritize the nearest missing chunk so the area around the player
        // becomes usable first instead of filling the cache in array order.
        int bestX = -1;
        int bestZ = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                boolean inRange = Math.abs(x - centerX) <= RADIUS
                    && Math.abs(z - centerZ) <= RADIUS;
                boolean wanted = inRange && isChunkInFront(x, z, forwardX, forwardZ);
                if (wanted && !builtChunks[x][z]) {
                    float dx = x * CHUNK + CHUNK * 0.5f - cameraX;
                    float dz = z * CHUNK + CHUNK * 0.5f - cameraZ;
                    float distance = dx * dx + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestX = x;
                        bestZ = z;
                    }
                }
            }
        }

        if (bestX >= 0) {
            Group mesh = buildChunk(bestX, bestZ);
            if (mesh != null) {
                activeChunks[bestX][bestZ] = mesh;
                terrainGroup.addChild(mesh);
            }
            Mesh waterMesh = buildWaterChunk(bestX, bestZ);
            if (waterMesh != null) {
                activeWaterChunks[bestX][bestZ] = waterMesh;
                waterGroup.addChild(waterMesh);
            }
            builtChunks[bestX][bestZ] = true;
            updateChunkVisibility();
            return; // chunksDirty remains true; continue next tick
        }

        chunksDirty = false;
        updateChunkVisibility();
    }

    /**
     * Builds exposed faces of the water block volume. Each wet column contains
     * water cells from its ground surface up to WATER_LEVEL; tops and shoreline
     * walls are emitted as block faces instead of one continuous flat sheet.
     */
    /** Force a fresh terrain/water mesh rebuild after an edit, even if the
     * player remains inside the same chunk. Synchronization prevents the game
     * loop from racing the key handler while meshes are removed and recreated. */
    private synchronized void reloadEditedChunks() {
        // An edit invalidates the currently visible terrain meshes, but not
        // chunks elsewhere in the world. Drop only the active window; the
        // next updateChunks() call recreates these meshes from the new data.
        int centerX = ((int) cameraX) / CHUNK;
        int centerZ = ((int) cameraZ) / CHUNK;
        for (int x = Math.max(0, centerX - RADIUS);
             x <= Math.min(7, centerX + RADIUS); x++) {
            for (int z = Math.max(0, centerZ - RADIUS);
                 z <= Math.min(7, centerZ + RADIUS); z++) {
                Group old = activeChunks[x][z];
                if (old != null) {
                    terrainGroup.removeChild(old);
                    activeChunks[x][z] = null;
                }
                Mesh oldWater = activeWaterChunks[x][z];
                if (oldWater != null) {
                    waterGroup.removeChild(oldWater);
                    activeWaterChunks[x][z] = null;
                }
                builtChunks[x][z] = false;
            }
        }
        loadedCenterX = -99;
        loadedCenterZ = -99;
        loadedFacingSector = -99;
        chunksDirty = true;
        updateChunks();
    }

    private Mesh buildHorizonSea() {
        // Four strips form a ring outside the finite 0..127 world. There is
        // deliberately NO water quad over the playable map, so the horizon
        // plane cannot intersect valleys or make the whole world look flooded.
        short edge = (short) (TerrainGenerator.SIZE - 1);
        short far = 1024;
        short negativeFar = (short) (-far);
        short y = (short) HORIZON_SEA_LEVEL;
        short[] positions = {
            // north, z < 0
            0, y, negativeFar, 0, y, 0, edge, y, negativeFar, edge, y, 0,
            // south, z > world edge
            0, y, edge, 0, y, far, edge, y, edge, edge, y, far,
            // west, x < 0
            negativeFar, y, 0, negativeFar, y, edge, 0, y, 0, 0, y, edge,
            // east, x > world edge
            edge, y, 0, edge, y, edge, far, y, 0, far, y, edge
        };
        short[] texCoords = {
            0,0, 0,1, 1,0, 1,1,
            0,0, 0,1, 1,0, 1,1,
            0,0, 0,1, 1,0, 1,1,
            0,0, 0,1, 1,0, 1,1
        };
        int[] indices = {0,1,2,3, 4,5,6,7, 8,9,10,11, 12,13,14,15};
        int[] stripLengths = {4,4,4,4};
        VertexArray positionArray = new VertexArray(16, 3, 2);
        positionArray.set(0, 16, positions);
        VertexArray texArray = new VertexArray(16, 2, 2);
        texArray.set(0, 16, texCoords);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(positionArray, 1.0f, null);
        buffer.setTexCoords(0, texArray, 512.0f, null);
        TriangleStripArray triangles = new TriangleStripArray(0, stripLengths);
        return new Mesh(buffer, triangles, waterAppearance);
    }

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
        int[] stripLengths = new int[quads];
        int vertex = 0, strip = 0;
        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                if (!terrain.isWaterBlockAt(x, WATER_LEVEL - 1, z)) continue;
                int h = terrain.getHeight(x, z);
                vertex = addWaterQuad(positions, texCoords, stripLengths,
                    vertex, strip++, x, WATER_LEVEL, z,
                    x, WATER_LEVEL, z + 1,
                    x + 1, WATER_LEVEL, z,
                    x + 1, WATER_LEVEL, z + 1);

                int nh = terrain.getHeight(x - 1, z);
                if (!terrain.isWaterAt(x - 1, z) && Math.max(h, nh) < WATER_LEVEL) {
                    int y0 = Math.max(h, nh);
                    vertex = addWaterQuad(positions, texCoords, stripLengths,
                        vertex, strip++, x, y0, z + 1, x, WATER_LEVEL, z + 1,
                        x, y0, z, x, WATER_LEVEL, z);
                }
                nh = terrain.getHeight(x + 1, z);
                if (!terrain.isWaterAt(x + 1, z) && Math.max(h, nh) < WATER_LEVEL) {
                    int y0 = Math.max(h, nh);
                    vertex = addWaterQuad(positions, texCoords, stripLengths,
                        vertex, strip++, x + 1, y0, z, x + 1, WATER_LEVEL, z,
                        x + 1, y0, z + 1, x + 1, WATER_LEVEL, z + 1);
                }
                nh = terrain.getHeight(x, z - 1);
                if (!terrain.isWaterAt(x, z - 1) && Math.max(h, nh) < WATER_LEVEL) {
                    int y0 = Math.max(h, nh);
                    vertex = addWaterQuad(positions, texCoords, stripLengths,
                        vertex, strip++, x, y0, z, x, WATER_LEVEL, z,
                        x + 1, y0, z, x + 1, WATER_LEVEL, z);
                }
                nh = terrain.getHeight(x, z + 1);
                if (!terrain.isWaterAt(x, z + 1) && Math.max(h, nh) < WATER_LEVEL) {
                    int y0 = Math.max(h, nh);
                    vertex = addWaterQuad(positions, texCoords, stripLengths,
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
        TriangleStripArray triangles = new TriangleStripArray(0, stripLengths);
        return new Mesh(buffer, triangles, waterAppearance);
    }

    private int addWaterQuad(short[] positions, short[] texCoords,
                             int[] stripLengths, int vertex, int strip,
                             int x0, int y0, int z0, int x1, int y1, int z1,
                             int x2, int y2, int z2, int x3, int y3, int z3) {
        vertex = addWaterVertex(positions, texCoords, vertex, x0, y0, z0, 0, 0);
        vertex = addWaterVertex(positions, texCoords, vertex, x1, y1, z1, 0, 1);
        vertex = addWaterVertex(positions, texCoords, vertex, x2, y2, z2, 1, 0);
        vertex = addWaterVertex(positions, texCoords, vertex, x3, y3, z3, 1, 1);
        stripLengths[strip] = 4;
        return vertex;
    }

    private int addWaterVertex(short[] positions, short[] texCoords,
                               int vertex, int x, int y, int z, int u, int v) {
        int p = vertex * 3;
        positions[p] = (short) x;
        positions[p + 1] = (short) y;
        positions[p + 2] = (short) z;
        int t = vertex * 2;
        texCoords[t] = (short) u;
        texCoords[t + 1] = (short) v;
        return vertex + 1;
    }

    /**
     * Scale one 48x48 pre-rendered inventory block icon to 16x16 using nearest
     * neighbour sampling, preserving the pixel-art look on small J2ME screens.
     */
    // Generate all 16 classic wool colors from the atlas's white wool tile.
    // The generated variants occupy unused atlas cells on row 6.
    private Image createWoolAtlas(Image source) {
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getRGB(pixels, 0, width, 0, 0, width, height);
        int[] woolColors = {
            0xF0F0F0, 0xF2B233, 0xD87FAD, 0x99BBDD,
            0xE5E533, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
            0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2,
            0x664C33, 0x667F33, 0x993333, 0x191919
        };
        int[] base = new int[256];
        source.getRGB(base, 0, 16, 0, 4 * 16, 16, 16);
        for (int color = 0; color < woolColors.length; color++) {
            int tint = woolColors[color];
            int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
            int tileX = color * 16;
            for (int py = 0; py < 16; py++) {
                for (int px = 0; px < 16; px++) {
                    int original = base[py * 16 + px];
                    int a = (original >>> 24) & 255;
                    int r = (original >> 16) & 255;
                    int g = (original >> 8) & 255;
                    int b = original & 255;
                    int nr = (r * tr) / 255, ng = (g * tg) / 255, nb = (b * tb) / 255;
                    pixels[(7 * 16 + py) * width + tileX + px] =
                        (a << 24) | (nr << 16) | (ng << 8) | nb;
                }
            }
        }
        return Image.createRGBImage(pixels, width, height, true);
    }

    private Image createGuiBlockIcon(int sourceX, int sourceY) {
        int[] source = new int[48 * 48];
        guiBlocksImage.getRGB(source, 0, 48, sourceX, sourceY, 48, 48);
        int[] scaled = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                scaled[y * 16 + x] = source[(y * 3) * 48 + x * 3];
            }
        }
        return Image.createRGBImage(scaled, 16, 16, true);
    }

    private Image createTerrainBlockIcon(Image atlas, int tileX, int tileY) {
        int[] pixels = new int[16 * 16];
        atlas.getRGB(pixels, 0, 16, tileX * 16, tileY * 16, 16, 16);
        return Image.createRGBImage(pixels, 16, 16, true);
    }

    private Mesh buildCloudPlane(Appearance appearance) {
        // Keep one perfectly flat quad with fixed corners. The texture repeats
        // at 1/8 UV scale, making cloud masses many blocks wide instead of tiny
        // pixel-art speckles. Moving cloudGroup animates it without warping UVs.
        short[] positions = {
            -256, 50, -256,
            -256, 50, 256,
             256, 50, -256,
             256, 50, 256
        };
        short[] texCoords = {0, 0, 0, 1, 1, 0, 1, 1};
        int[] indices = {0, 1, 2, 3};
        int[] stripLengths = {4};
        VertexArray positionArray = new VertexArray(4, 3, 2);
        positionArray.set(0, 4, positions);
        VertexArray texArray = new VertexArray(4, 2, 2);
        texArray.set(0, 4, texCoords);
        VertexBuffer buffer = new VertexBuffer();
        buffer.setPositions(positionArray, 1.0f, null);
        buffer.setTexCoords(0, texArray, 1.0f, null);
        TriangleStripArray triangles = new TriangleStripArray(indices, stripLengths);
        return new Mesh(buffer, triangles, appearance);
    }

    private void updateDayNight() {
        if (IndevViewerMIDlet.humbleMode) {
            daylightLevel = 1.0f;
            skyBackground.setColor(0x87BFEA);
            distanceFog.setColor(0xB6CBE0);
            ambientLight.setColor(0xFFFFFF);
            return;
        }
        dayTick++;
        if (dayTick >= DAY_TICKS) dayTick = 0;
        // Recompute lighting/color state every fourth tick to reduce per-frame work
        // on older Java ME devices while keeping day/night changes visually smooth.
        if ((dayTick & 3) != 0) return;
        float phase = (float)(dayTick * (Math.PI * 2.0 / DAY_TICKS));
        float daylight = 0.15f + 0.85f * (0.5f + 0.5f * (float)Math.cos(phase));
        daylightLevel = daylight;
        skyBackground.setColor(blendColor(0x10182D, 0x87BFEA, daylight));
        distanceFog.setColor(blendColor(0x151923, 0xB6CBE0, daylight));
        ambientLight.setColor(blendColor(0x22283D, 0xFFFFFF, daylight));
        if (dayTick % 5 == 0) {
            float far = IndevViewerMIDlet.fogFarDistance() * (0.65f + daylight * 0.35f);
            distanceFog.setLinear(far * 0.38f, far);
        }
    }

    private int blendColor(int night, int day, float amount) {
        if (amount < 0.0f) amount = 0.0f;
        if (amount > 1.0f) amount = 1.0f;
        int nr = (night >> 16) & 255, ng = (night >> 8) & 255, nb = night & 255;
        int dr = (day >> 16) & 255, dg = (day >> 8) & 255, db = day & 255;
        int r = nr + (int)((dr - nr) * amount);
        int g = ng + (int)((dg - ng) * amount);
        int b = nb + (int)((db - nb) * amount);
        return (r << 16) | (g << 8) | b;
    }

    private boolean attackMobs() {
        if (hostileMobs != null && hostileMobs.hit(cameraX, cameraY, cameraZ, yaw, pitch)) {
            if (hostileMobs.consumeDeathEvent()) {
                spawnMobDeathParticles(hostileMobs.getDeathX(), hostileMobs.getDeathY(), hostileMobs.getDeathZ());
            }
            return true;
        }
        if (passiveMobs != null && passiveMobs.hit(cameraX, cameraY, cameraZ, yaw, pitch)) {
            if (passiveMobs.consumeDeathEvent()) {
                spawnMobDeathParticles(passiveMobs.getDeathX(), passiveMobs.getDeathY(), passiveMobs.getDeathZ());
            }
            return true;
        }
        return false;
    }

    private void animateWater() {
        waterPhase += 0.012f;
        if (waterPhase >= 1.0f) waterPhase -= 1.0f;
        cloudPhase += 0.0008f;
        if (cloudPhase >= 1.0f) cloudPhase -= 1.0f;
        // Scroll the texture, not the vertices. The cloud mesh follows the
        // camera in coarse 64-unit steps, avoiding huge-coordinate jitter.
        cloudTexture.setTranslation(cloudPhase, 0.0f, 0.0f);
        // Texture2D is Transformable in M3G; repeating coordinates make this
        // a cheap scrolling-water animation without rebuilding terrain meshes.
        waterTexture.setTranslation(waterPhase, waterPhase * 0.65f, 0.0f);
    }

    private Group buildChunk(int chunkX, int chunkZ) {
        for (int i = 0; i < shadeCacheKeys.length; i++) shadeCacheKeys[i] = -1;
        int startX = chunkX * CHUNK;
        int startZ = chunkZ * CHUNK;
        int quads = 0;
        int x, z, h;
        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                h = terrain.getHeight(x, z);
                int nh = terrain.getHeight(x - 1, z); if (h > nh) quads += h - nh;
                nh = terrain.getHeight(x + 1, z); if (h > nh) quads += h - nh;
                nh = terrain.getHeight(x, z - 1); if (h > nh) quads += h - nh;
                nh = terrain.getHeight(x, z + 1); if (h > nh) quads += h - nh;
                if (terrain.isTreeAt(x, z)) {
                    int treeBase = h;
                    int treeHeight = terrain.getTreeHeight(x, z);
                    int treeY;
                    for (treeY = 0; treeY < treeHeight; treeY++) {
                        quads += countExposedTreeFaces(x, treeBase + treeY, z);
                    }
                    int crown = treeBase + treeHeight - 1;
                    for (treeY = crown - 1; treeY <= crown + 1; treeY++) {
                        int radius = (treeY == crown + 1) ? 1 : 2;
                        int lx, lz;
                        for (lx = x - radius; lx <= x + radius; lx++) {
                            for (lz = z - radius; lz <= z + radius; lz++) {
                                if (lx == x && lz == z && treeY <= crown) continue;
                                if (terrain.isLeafBlockAt(lx, treeY, lz)) {
                                    quads += countExposedTreeFaces(lx, treeY, lz);
                                }
                            }
                        }
                    }
                }
            }
        }

        int vertexCount = quads * 4;
        short[] positions = new short[vertexCount * 3];
        short[] texCoords = new short[vertexCount * 2];
        byte[] colors = new byte[vertexCount * 3];
        int[] stripLengths = new int[quads];
        int vertex = 0;
        int strip = 0;

        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                h = terrain.getHeight(x, z);
                int neighbor = terrain.getHeight(x - 1, z);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, colors, stripLengths,
                        vertex, strip++, x, sy, z + 1, x, sy + 1, z + 1,
                        x, sy, z, x, sy + 1, z, sideTile(terrain.getTopType(x, z), terrain.getBlockTypeAt(x, sy, z), sy, h), sideTileY(terrain.getTopType(x, z), terrain.getBlockTypeAt(x, sy, z), sy, h));
                }
                neighbor = terrain.getHeight(x + 1, z);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, colors, stripLengths,
                        vertex, strip++, x + 1, sy, z, x + 1, sy + 1, z,
                        x + 1, sy, z + 1, x + 1, sy + 1, z + 1, sideTile(terrain.getTopType(x, z), terrain.getBlockTypeAt(x, sy, z), sy, h), sideTileY(terrain.getTopType(x, z), terrain.getBlockTypeAt(x, sy, z), sy, h));
                }
                neighbor = terrain.getHeight(x, z - 1);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, colors, stripLengths,
                        vertex, strip++, x, sy, z, x, sy + 1, z,
                        x + 1, sy, z, x + 1, sy + 1, z, sideTile(terrain.getTopType(x, z), terrain.getBlockTypeAt(x, sy, z), sy, h), sideTileY(terrain.getTopType(x, z), terrain.getBlockTypeAt(x, sy, z), sy, h));
                }
                neighbor = terrain.getHeight(x, z + 1);
                for (int sy = neighbor; sy < h; sy++) {
                    vertex = addQuad(positions, texCoords, colors, stripLengths,
                        vertex, strip++, x + 1, sy, z + 1, x + 1, sy + 1, z + 1,
                        x, sy, z + 1, x, sy + 1, z + 1, sideTile(terrain.getTopType(x, z), terrain.getBlockTypeAt(x, sy, z), sy, h), sideTileY(terrain.getTopType(x, z), terrain.getBlockTypeAt(x, sy, z), sy, h));
                }
                if (terrain.isTreeAt(x, z)) {
                    int base = h, th = terrain.getTreeHeight(x, z), y;
                    for (y = 0; y < th; y++) { vertex = addTreeCube(positions, texCoords, colors, stripLengths, vertex, strip, x, base + y, z, 4, 1); strip += countExposedTreeFaces(x, base + y, z); }
                    int crown = base + th - 1;
                    for (y = crown - 1; y <= crown + 1; y++) { int radius = (y == crown + 1) ? 1 : 2, lx, lz;
                        for (lx = x - radius; lx <= x + radius; lx++) for (lz = z - radius; lz <= z + radius; lz++) {
                            if (lx == x && lz == z && y <= crown) continue;
                            if (terrain.isLeafBlockAt(lx, y, lz)) {
                                vertex = addTreeCube(positions, texCoords, colors, stripLengths, vertex, strip, lx, y, lz, 4, 3);
                                strip += countExposedTreeFaces(lx, y, lz);
                            }
                        }
                    }
                }
            }
        }

        Group chunkGroup = new Group();
        if (vertexCount > 0) {
            VertexArray vertexArray = new VertexArray(vertexCount, 3, 2);
            vertexArray.set(0, vertexCount, positions);
            VertexArray texArray = new VertexArray(vertexCount, 2, 2);
            texArray.set(0, vertexCount, texCoords);
            VertexBuffer buffer = new VertexBuffer();
            buffer.setPositions(vertexArray, 1.0f, null);
            buffer.setTexCoords(0, texArray, 1.0f / 256.0f, null);
            VertexArray colorArray = new VertexArray(vertexCount, 3, 1);
            colorArray.set(0, vertexCount, colors);
            buffer.setColors(colorArray);
            TriangleStripArray triangles = new TriangleStripArray(0, stripLengths);
            chunkGroup.addChild(new Mesh(buffer, triangles, groundAppearance));
        }
        buildGreedyTopMeshes(chunkGroup, chunkX, chunkZ);
        return chunkGroup;
    }



    /** Merge compatible adjacent top faces while repeating their own 16x16 tile. */
    private void buildGreedyTopMeshes(Group chunkGroup, int chunkX, int chunkZ) {
        int startX = chunkX * CHUNK, startZ = chunkZ * CHUNK;
        int[][] keys = greedyKeys, hs = greedyHeights;
        int[][] bases = greedyBases, glows = greedyGlows;
        boolean[][] used = greedyUsed;
        int[] rx=greedyRectX, rz=greedyRectZ, rw=greedyRectW, rd=greedyRectD, rh=greedyRectH;
        int[] rk=greedyRectKey, rb=greedyRectBase, rg=greedyRectGlow;
        int[] perTile=greedyPerTile;
        int count=0, x, z;
        for (x=0;x<CHUNK;x++) for (z=0;z<CHUNK;z++) used[x][z]=false;
        for (x=0;x<256;x++) perTile[x]=0;
        for (x=0;x<CHUNK;x++) for(z=0;z<CHUNK;z++) {
            int wx=startX+x, wz=startZ+z, h=terrain.getHeight(wx,wz);
            int type=terrain.getTopType(wx,wz), tx=BLOCK_TILE_X[type], ty=BLOCK_TILE_Y[type];
            if(type==TerrainGenerator.BLOCK_TNT && tntFuseTicks>0 && wx==tntX && wz==tntZ && tntFlashOn){tx=9;ty=0;}
            keys[x][z]=ty*16+tx; hs[x][z]=h;
            bases[x][z]=IndevViewerMIDlet.humbleMode ? 255 : terrain.getShadowShade(wx,wz,h);
            glows[x][z]=IndevViewerMIDlet.humbleMode ? 0 : torchGlowAt(wx,h,wz);
        }
        for(x=0;x<CHUNK;x++) for(z=0;z<CHUNK;z++) {
            if(used[x][z]) continue;
            int key=keys[x][z], h=hs[x][z], base=bases[x][z], glow=glows[x][z];
            int depth=1;
            while(z+depth<CHUNK && !used[x][z+depth] && keys[x][z+depth]==key
                && hs[x][z+depth]==h && bases[x][z+depth]==base && glows[x][z+depth]==glow) depth++;
            int width=1; boolean extend=true;
            while(x+width<CHUNK && extend) {
                for(int dz=0;dz<depth;dz++) {
                    int xx=x+width, zz=z+dz;
                    if(used[xx][zz] || keys[xx][zz]!=key || hs[xx][zz]!=h
                        || bases[xx][zz]!=base || glows[xx][zz]!=glow){extend=false;break;}
                }
                if(extend) width++;
            }
            for(int dx=0;dx<width;dx++) for(int dz=0;dz<depth;dz++) used[x+dx][z+dz]=true;
            rx[count]=x; rz[count]=z; rw[count]=width; rd[count]=depth; rh[count]=h;
            rk[count]=key; rb[count]=base; rg[count]=glow; count++;
        }
        for(int i=0;i<count;i++) perTile[rk[i]]++;
        for(int key=0;key<256;key++) {
            int n=perTile[key]; if(n==0) continue;
            short[] pos=new short[n*12], uv=new short[n*8]; byte[] colors=new byte[n*12];
            int[] strips=new int[n]; int out=0;
            for(int i=0;i<count;i++) {
                if(rk[i]!=key) continue;
                int wx=startX+rx[i], wz=startZ+rz[i], w=rw[i], d=rd[i], y=rh[i];
                int base=rb[i], glow=rg[i], shade=base+glow; if(shade>255)shade=255;
                int green=shade-glow/5, blue=shade-glow/2;
                if(green<base)green=base; if(blue<base)blue=base;
                int p=out*12;
                pos[p]=(short)wx;pos[p+1]=(short)y;pos[p+2]=(short)wz;
                pos[p+3]=(short)wx;pos[p+4]=(short)y;pos[p+5]=(short)(wz+d);
                pos[p+6]=(short)(wx+w);pos[p+7]=(short)y;pos[p+8]=(short)wz;
                pos[p+9]=(short)(wx+w);pos[p+10]=(short)y;pos[p+11]=(short)(wz+d);
                int t=out*8;
                uv[t]=0;uv[t+1]=0; uv[t+2]=0;uv[t+3]=(short)(d*16);
                uv[t+4]=(short)(w*16);uv[t+5]=0; uv[t+6]=(short)(w*16);uv[t+7]=(short)(d*16);
                for(int v=0;v<4;v++){int c=(out*4+v)*3;colors[c]=(byte)shade;colors[c+1]=(byte)green;colors[c+2]=(byte)blue;}
                strips[out]=4; out++;
            }
            VertexArray pa=new VertexArray(n*4,3,2);pa.set(0,n*4,pos);
            VertexArray ta=new VertexArray(n*4,2,2);ta.set(0,n*4,uv);
            VertexArray ca=new VertexArray(n*4,3,1);ca.set(0,n*4,colors);
            VertexBuffer vb=new VertexBuffer();vb.setPositions(pa,1.0f,null);
            vb.setTexCoords(0,ta,1.0f/16.0f,null);vb.setColors(ca);
            chunkGroup.addChild(new Mesh(vb,new TriangleStripArray(0,strips),getTerrainTileAppearance(key)));
        }
    }

    private Appearance getTerrainTileAppearance(int key) {
        if(terrainTileAppearances[key]!=null)return terrainTileAppearances[key];
        int tx=key&15, ty=key>>4; int[] pixels=new int[256];
        terrainAtlasImage.getRGB(pixels,0,16,tx*16,ty*16,16,16);
        Image tile=Image.createRGBImage(pixels,16,16,true);
        Texture2D tex=new Texture2D(new Image2D(Image2D.RGBA,tile));
        tex.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_NEAREST);
        tex.setWrapping(Texture2D.WRAP_REPEAT,Texture2D.WRAP_REPEAT);
        Appearance app=new Appearance();app.setTexture(0,tex);app.setFog(distanceFog);
        PolygonMode pm=new PolygonMode();pm.setCulling(PolygonMode.CULL_NONE);pm.setShading(PolygonMode.SHADE_FLAT);
        app.setPolygonMode(pm);terrainTileAppearances[key]=app;return app;
    }

    private int sideTile(int topType, int layerType, int y, int height) {
        if (topType == TerrainGenerator.BLOCK_GRASS && y == height - 1) return 3;
        if (layerType == TerrainGenerator.BLOCK_WOOD) return 4;
        if (layerType < 0 || layerType >= BLOCK_TILE_X.length) layerType = TerrainGenerator.BLOCK_STONE;
        return BLOCK_TILE_X[layerType];
    }

    private int sideTileY(int topType, int layerType, int y, int height) {
        if (topType == TerrainGenerator.BLOCK_GRASS && y == height - 1) return 0;
        if (layerType == TerrainGenerator.BLOCK_WOOD) return 1;
        if (layerType < 0 || layerType >= BLOCK_TILE_Y.length) layerType = TerrainGenerator.BLOCK_STONE;
        return BLOCK_TILE_Y[layerType];
    }

    /**
     * Tree voxels only: decorations, particles and other special geometry keep
     * their original rendering path and are never passed through this culler.
     */
    private boolean isTreeVoxelAt(int x, int y, int z) {
        if (y < 0 || y >= TerrainGenerator.HEIGHT) return false;
        if (terrain.isLeafBlockAt(x, y, z)) return true;
        if (!terrain.isTreeAt(x, z)) return false;
        int base = terrain.getHeight(x, z);
        return y >= base && y < base + terrain.getTreeHeight(x, z);
    }

    /** Number of tree-cube faces not touching another tree voxel. */
    private int countExposedTreeFaces(int x, int y, int z) {
        int faces = 0;
        if (!isTreeVoxelAt(x, y + 1, z)) faces++;
        if (!isTreeVoxelAt(x, y, z + 1)) faces++;
        if (!isTreeVoxelAt(x + 1, y, z)) faces++;
        if (!isTreeVoxelAt(x - 1, y, z)) faces++;
        if (!isTreeVoxelAt(x, y, z - 1)) faces++;
        // Leaf textures contain transparent pixels, so even a face touching
        // another leaf must be emitted: otherwise the canopy can show a hole
        // from below through the transparent parts of the neighboring block.
        if (terrain.isLeafBlockAt(x, y, z) || !isTreeVoxelAt(x, y - 1, z)) faces++;
        return faces;
    }

    /** Emit exposed tree faces while preserving the original atlas tile mapping. */
    private int addTreeCube(short[] positions, short[] texCoords, byte[] colors,
                            int[] stripLengths, int vertex, int strip,
                            int x, int y, int z, int tx, int ty) {
        int topX = (tx == 4 && ty == 1) ? 5 : tx;
        int topY = (tx == 4 && ty == 1) ? 1 : ty;
        if (!isTreeVoxelAt(x, y + 1, z)) {
            vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                x, y+1, z, x, y+1, z+1, x+1, y+1, z, x+1, y+1, z+1, topX, topY);
        }
        if (!isTreeVoxelAt(x, y, z + 1)) {
            vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                x, y, z+1, x, y+1, z+1, x+1, y, z+1, x+1, y+1, z+1, tx, ty);
        }
        if (!isTreeVoxelAt(x + 1, y, z)) {
            // +X face: all four vertices must lie on x + 1; z spans the block.
            vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                x+1, y, z, x+1, y+1, z, x+1, y, z+1, x+1, y+1, z+1, tx, ty);
        }
        if (!isTreeVoxelAt(x - 1, y, z)) {
            // -X face: all four vertices lie on x.
            vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                x, y, z, x, y+1, z, x, y, z+1, x, y+1, z+1, tx, ty);
        }
        if (!isTreeVoxelAt(x, y, z - 1)) {
            // -Z face: all four vertices lie on z.
            vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                x, y, z, x, y+1, z, x+1, y, z, x+1, y+1, z, tx, ty);
        }
        if ((tx == 4 && ty == 3) || !isTreeVoxelAt(x, y - 1, z)) {
            // Always draw leaf undersides, even when another leaf is directly
            // below. Leaf textures are transparent, so culling this internal
            // face can leave visible gaps when viewed from underneath.
            vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                x, y, z, x+1, y, z, x, y, z+1, x+1, y, z+1, tx, ty);
        }
        return vertex;
    }
    /**
     * Build flat, textured world-space fragments. Each particle samples a small
     * patch from its block's atlas tile, like classic Minecraft block debris.
     */
    private void setupBlockParticles(Image atlas) {
        short[] positions = {
            -1, -1, 0,
            -1,  1, 0,
             1, -1, 0,
             1,  1, 0
        };
        short[] texCoords = {0, 0, 0, 1, 1, 0, 1, 1};
        int[] indices = {0, 1, 2, 3};
        int[] stripLengths = {4};
        VertexArray vertexArray = new VertexArray(4, 3, 2);
        vertexArray.set(0, 4, positions);
        VertexArray texArray = new VertexArray(4, 2, 2);
        texArray.set(0, 4, texCoords);
        blockParticleBuffer = new VertexBuffer();
        blockParticleBuffer.setPositions(vertexArray, 0.5f, null);
        blockParticleBuffer.setTexCoords(0, texArray, 1.0f, null);
        blockParticleTriangles = new TriangleStripArray(indices, stripLengths);

        int[] patchPixels = new int[8 * 8];
        for (int t = 0; t < blockParticleAppearances.length; t++) {
            int tileX = BLOCK_TILE_X[t] * 16 + 4;
            int tileY = BLOCK_TILE_Y[t] * 16 + 4;
            atlas.getRGB(patchPixels, 0, 8, tileX, tileY, 8, 8);
            Image patch = Image.createRGBImage(patchPixels, 8, 8, true);
            Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, patch));
            texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
            texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);

            Appearance appearance = new Appearance();
            appearance.setTexture(0, texture);
            appearance.setFog(distanceFog);
            PolygonMode pm = new PolygonMode();
            pm.setCulling(PolygonMode.CULL_NONE);
            pm.setShading(PolygonMode.SHADE_FLAT);
            appearance.setPolygonMode(pm);
            blockParticleAppearances[t] = appearance;
        }
        int[] grayPixel = {0xFF858585};
        Image grayImage = Image.createRGBImage(grayPixel, 1, 1, true);
        Texture2D grayTexture = new Texture2D(new Image2D(Image2D.RGBA, grayImage));
        grayTexture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        grayTexture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        mobDeathParticleAppearance = new Appearance();
        mobDeathParticleAppearance.setTexture(0, grayTexture);
        mobDeathParticleAppearance.setFog(distanceFog);
        PolygonMode grayMode = new PolygonMode();
        grayMode.setCulling(PolygonMode.CULL_NONE);
        grayMode.setShading(PolygonMode.SHADE_FLAT);
        mobDeathParticleAppearance.setPolygonMode(grayMode);

        for (int i = 0; i < BLOCK_PARTICLE_COUNT; i++) {
            blockParticleMeshes[i] = new Mesh(blockParticleBuffer,
                blockParticleTriangles, blockParticleAppearances[0]);
            blockParticleMeshes[i].setRenderingEnable(false);
            blockParticleGroup.addChild(blockParticleMeshes[i]);
        }
    }

    private void spawnBlockParticles(int blockType, float x, float y, float z) {
        int type = blockType;
        if (type < 0 || type >= blockParticleAppearances.length) type = 1;
        for (int i = 0; i < BLOCK_PARTICLE_COUNT; i++) {
            double angle = (Math.PI * 2.0 * i / BLOCK_PARTICLE_COUNT)
                + ((i % 3) - 1) * 0.17;
            float horizontalSpeed = 0.035f + (i % 4) * 0.012f;
            blockParticleX[i] = x;
            blockParticleY[i] = y;
            blockParticleZ[i] = z;
            blockParticleVX[i] = (float) Math.cos(angle) * horizontalSpeed;
            blockParticleVZ[i] = (float) Math.sin(angle) * horizontalSpeed;
            blockParticleVY[i] = 0.045f + (i % 4) * 0.012f;
            blockParticleSize[i] = 0.07f + (i % 3) * 0.025f;
            blockParticleMeshes[i].setAppearance(0, blockParticleAppearances[type]);
            blockParticleMeshes[i].setScale(blockParticleSize[i], blockParticleSize[i],
                                             blockParticleSize[i]);
            blockParticleMeshes[i].setOrientation((i * 37) % 360, 1.0f, 1.0f, 0.5f);
            blockParticleMeshes[i].setTranslation(x, y, z);
            blockParticleMeshes[i].setRenderingEnable(true);
        }
        particleTicks = BLOCK_PARTICLE_LIFE;
    }

    private void spawnExplosionDebris(int[] types, float[] xs, float[] ys, float[] zs) {
        for (int i = 0; i < BLOCK_PARTICLE_COUNT; i++) {
            int type = types[i];
            if (type < 0 || type >= blockParticleAppearances.length) type = TerrainGenerator.BLOCK_DIRT;
            double angle = (Math.PI * 2.0 * i / BLOCK_PARTICLE_COUNT)
                + ((i % 3) - 1) * 0.21;
            float horizontalSpeed = 0.055f + (i % 4) * 0.018f;
            blockParticleX[i] = xs[i];
            blockParticleY[i] = ys[i];
            blockParticleZ[i] = zs[i];
            blockParticleVX[i] = (float) Math.cos(angle) * horizontalSpeed;
            blockParticleVZ[i] = (float) Math.sin(angle) * horizontalSpeed;
            blockParticleVY[i] = 0.075f + (i % 4) * 0.018f;
            blockParticleSize[i] = 0.08f + (i % 3) * 0.025f;
            blockParticleMeshes[i].setAppearance(0, blockParticleAppearances[type]);
            blockParticleMeshes[i].setScale(blockParticleSize[i], blockParticleSize[i],
                                             blockParticleSize[i]);
            blockParticleMeshes[i].setOrientation((i * 37) % 360, 1.0f, 1.0f, 0.5f);
            blockParticleMeshes[i].setTranslation(xs[i], ys[i], zs[i]);
            blockParticleMeshes[i].setRenderingEnable(true);
        }
        particleTicks = BLOCK_PARTICLE_LIFE;
    }

    /** Gray cube-like fragments on mob death; reuses the existing particle pool. */
    private void spawnMobDeathParticles(float x, float y, float z) {
        for (int i = 0; i < BLOCK_PARTICLE_COUNT; i++) {
            double angle = (Math.PI * 2.0 * i / BLOCK_PARTICLE_COUNT)
                + ((i % 3) - 1) * 0.16;
            float speed = 0.045f + (i % 4) * 0.014f;
            blockParticleX[i] = x;
            blockParticleY[i] = y;
            blockParticleZ[i] = z;
            blockParticleVX[i] = (float)Math.cos(angle) * speed;
            blockParticleVZ[i] = (float)Math.sin(angle) * speed;
            blockParticleVY[i] = 0.045f + (i % 4) * 0.014f;
            blockParticleSize[i] = 0.055f + (i % 3) * 0.018f;
            blockParticleMeshes[i].setAppearance(0, mobDeathParticleAppearance);
            blockParticleMeshes[i].setScale(blockParticleSize[i], blockParticleSize[i],
                                             blockParticleSize[i]);
            blockParticleMeshes[i].setOrientation((i * 31) % 360, 1.0f, 1.0f, 0.5f);
            blockParticleMeshes[i].setTranslation(x, y, z);
            blockParticleMeshes[i].setRenderingEnable(true);
        }
        particleTicks = BLOCK_PARTICLE_LIFE;
    }

    private void updateBlockParticles() {
        if (IndevViewerMIDlet.humbleMode || particleTicks <= 0) {
            if (particleTicks > 0) {
                particleTicks = 0;
                for (int i = 0; i < BLOCK_PARTICLE_COUNT; i++) blockParticleMeshes[i].setRenderingEnable(false);
            }
            return;
        }
        for (int i = 0; i < BLOCK_PARTICLE_COUNT; i++) {
            blockParticleX[i] += blockParticleVX[i];
            blockParticleY[i] += blockParticleVY[i];
            blockParticleZ[i] += blockParticleVZ[i];
            blockParticleVY[i] -= 0.0045f;
            int ground = terrain.getHeight((int) blockParticleX[i], (int) blockParticleZ[i]);
            if (blockParticleY[i] < ground + 0.035f) {
                blockParticleY[i] = ground + 0.035f;
                blockParticleVY[i] = 0.0f;
                blockParticleVX[i] *= 0.72f;
                blockParticleVZ[i] *= 0.72f;
            }
            blockParticleMeshes[i].setTranslation(blockParticleX[i],
                blockParticleY[i], blockParticleZ[i]);
            blockParticleMeshes[i].setOrientation((i * 37 + (BLOCK_PARTICLE_LIFE - particleTicks) * 13)
                % 360, 1.0f, 1.0f, 0.5f);
        }
        particleTicks--;
        if (particleTicks <= 0) {
            for (int i = 0; i < BLOCK_PARTICLE_COUNT; i++) {
                blockParticleMeshes[i].setRenderingEnable(false);
            }
        }
    }

    private void updateTntFuse() {
        if (tntFuseTicks <= 0) return;
        tntFuseTicks--;
        tntFlashCounter++;
        if (tntFlashCounter >= 12) {
            tntFlashCounter = 0;
            tntFlashOn = !tntFlashOn;
            reloadEditedChunks();
        }
        if (tntFuseTicks == 0) explodeTnt();
    }

    private void explodeTnt() {
        int cx = tntX, cz = tntZ;
        tntX = -1; tntZ = -1; tntFlashOn = false; tntFlashCounter = 0;
        if (cx < 0 || cz < 0) return;
        int radius = 4;
        // Scatter one shared burst of debris across the affected blocks. The
        // particle pool is small on CLDC devices, so each particle samples a
        // different destroyed column instead of restarting the pool per block.
        int[] debrisTypes = new int[BLOCK_PARTICLE_COUNT];
        float[] debrisX = new float[BLOCK_PARTICLE_COUNT];
        float[] debrisY = new float[BLOCK_PARTICLE_COUNT];
        float[] debrisZ = new float[BLOCK_PARTICLE_COUNT];
        for (int i = 0; i < BLOCK_PARTICLE_COUNT; i++) {
            double angle = (Math.PI * 2.0 * i / BLOCK_PARTICLE_COUNT)
                + ((i % 3) - 1) * 0.21;
            int offset = 1 + (i % radius);
            int bx = Math.max(1, Math.min(TerrainGenerator.SIZE - 2,
                cx + (int)(Math.cos(angle) * offset)));
            int bz = Math.max(1, Math.min(TerrainGenerator.SIZE - 2,
                cz + (int)(Math.sin(angle) * offset)));
            int oldHeight = terrain.getHeight(bx, bz);
            debrisTypes[i] = terrain.getTopType(bx, bz);
            debrisX[i] = bx + 0.5f;
            debrisY[i] = oldHeight + 0.35f;
            debrisZ[i] = bz + 0.5f;
        }
        for (int x = Math.max(1, cx-radius); x <= Math.min(TerrainGenerator.SIZE-2, cx+radius); x++) {
            for (int z = Math.max(1, cz-radius); z <= Math.min(TerrainGenerator.SIZE-2, cz+radius); z++) {
                int dx=x-cx, dz=z-cz, d2=dx*dx+dz*dz;
                if (d2 > radius*radius) continue;
                int depth = 1 + radius - (int)Math.sqrt(d2);
                int nh = terrain.getHeight(x,z)-depth;
                if (nh < 2) nh = 2;
                terrain.setHeight(x,z,nh);
                terrain.setTopType(x,z,(x==cx && z==cz) ? TerrainGenerator.BLOCK_STONE :
                    (nh < 20 ? TerrainGenerator.BLOCK_STONE : TerrainGenerator.BLOCK_DIRT));
            }
        }
        spawnExplosionDebris(debrisTypes, debrisX, debrisY, debrisZ);
        reloadEditedChunks();
        resolvePlayerAfterTerrainEdit();
    }

    private void breakBlock() {
        int decorationHit = raycastDecoration();
        if (decorationHit >= 0) {
            int type = decorationTypes[decorationHit];
            inventory[type]++;
            spawnBlockParticles(type, decorationX[decorationHit], decorationY[decorationHit] + 0.35f,
                decorationZ[decorationHit]);
            decorationGroup.removeChild(decorationMeshes[decorationHit]);
            int last = decorationCount - 1;
            if (decorationHit != last) {
                decorationMeshes[decorationHit] = decorationMeshes[last];
                decorationTypes[decorationHit] = decorationTypes[last];
                decorationX[decorationHit] = decorationX[last];
                decorationY[decorationHit] = decorationY[last];
                decorationZ[decorationHit] = decorationZ[last];
                decorationYaw[decorationHit] = decorationYaw[last];
                decorationOpen[decorationHit] = decorationOpen[last];
            }
            decorationMeshes[last] = null;
            decorationOpen[last] = false;
            decorationCount--;
            updateTorchLights();
            if (type == BLOCK_TORCH) reloadEditedChunks();
            return;
        }

        int[] leafHit = raycastLeafBlock();
        if (leafHit != null && terrain.removeLeafBlock(leafHit[0], leafHit[1], leafHit[2])) {
            spawnBlockParticles(4, leafHit[0] + 0.5f, leafHit[1] + 0.5f,
                leafHit[2] + 0.5f);
            reloadEditedChunks();
            updateCamera();
            return;
        }

        // hit = {x, z, blockY, sideHit}; sideHit allows mining exposed cliff/cave-wall faces.
        int[] hit = raycastSurface();
        if (hit == null) return;
        int x = hit[0], z = hit[1], blockY = hit[2];
        int h = terrain.getHeight(x, z);
        if (h <= 1) return;

        boolean sideHit = hit[3] != 0;
        int type = sideHit ? terrain.getBlockTypeAt(x, blockY, z)
                           : terrain.getTopType(x, z);
        if (type == TerrainGenerator.BLOCK_BEDROCK) return;
        if (type == TerrainGenerator.BLOCK_TNT) {
            if (tntFuseTicks == 0) {
                tntX=x; tntZ=z; tntFuseTicks=125; tntFlashCounter=0; tntFlashOn=true;
                reloadEditedChunks();
            }
            return;
        }
        if (type >= 0 && type < inventory.length && type != TerrainGenerator.BLOCK_BEDROCK) inventory[type]++;
        spawnBlockParticles(type, x + 0.5f, blockY + 0.5f, z + 0.5f);

        if (sideHit) {
            // This terrain is height-map based, so a side edit lowers that column
            // to the struck layer rather than creating a fully voxel-shaped cavity.
            int newHeight = blockY + 1;
            if (newHeight >= h) return;
            terrain.setHeight(x, z, newHeight);
            terrain.setTopType(x, z, type);
        } else {
            int nextType = terrain.getSubsurfaceType(x, h - 2, z);
            terrain.setHeight(x, z, h - 1);
            terrain.setTopType(x, z, nextType);
        }
        reloadEditedChunks();
        resolvePlayerAfterTerrainEdit();
    }

    private void setupDecorations() {
        int[] indices = {0, 1, 2, 3};
        int[] stripLengths = {4};
        decorationTriangles = new TriangleStripArray(indices, stripLengths);
        for (int i = 0; i < 5; i++) {
            short[] positions = {-1, 0, 0, -1, 2, 0, 1, 0, 0, 1, 2, 0};
            short[] uv = {
                (short)(BLOCK_TILE_X[BLOCK_TORCH + i] * 16 + 1), (short)((BLOCK_TILE_Y[BLOCK_TORCH + i] + 1) * 16 - 1),
                (short)(BLOCK_TILE_X[BLOCK_TORCH + i] * 16 + 1), (short)(BLOCK_TILE_Y[BLOCK_TORCH + i] * 16 + 1),
                (short)((BLOCK_TILE_X[BLOCK_TORCH + i] + 1) * 16 - 1), (short)((BLOCK_TILE_Y[BLOCK_TORCH + i] + 1) * 16 - 1),
                (short)((BLOCK_TILE_X[BLOCK_TORCH + i] + 1) * 16 - 1), (short)(BLOCK_TILE_Y[BLOCK_TORCH + i] * 16 + 1)
            };
            VertexArray va = new VertexArray(4, 3, 2);
            va.set(0, 4, positions);
            VertexArray ta = new VertexArray(4, 2, 2);
            ta.set(0, 4, uv);
            VertexBuffer vb = new VertexBuffer();
            vb.setPositions(va, 0.5f, null);
            vb.setTexCoords(0, ta, 1.0f / 256.0f, null);
            decorationBuffers[i] = vb;
            Appearance appearance = new Appearance();
            appearance.setTexture(0, terrainTexture);
            appearance.setFog(distanceFog);
            PolygonMode pm = new PolygonMode();
            pm.setCulling(PolygonMode.CULL_NONE);
            pm.setShading(PolygonMode.SHADE_FLAT);
            appearance.setPolygonMode(pm);
            CompositingMode blend = new CompositingMode();
            blend.setBlending(CompositingMode.ALPHA);
            blend.setDepthWriteEnable(false);
            appearance.setCompositingMode(blend);
            decorationAppearances[i] = appearance;
        }
    }

    private void setupTorchLights() {
        for (int i = 0; i < torchLights.length; i++) {
            Light light = new Light();
            light.setMode(Light.OMNI);
            light.setColor(0xFFB45A);
            light.setIntensity(1.0f);
            light.setAttenuation(1.0f, 0.08f, 0.025f);
            light.setTranslation(0.0f, -1000.0f, 0.0f);
            world.addChild(light);
            torchLights[i] = light;
        }
    }

    private boolean placeDecoration(int type, float x, float y, float z) {
        int variant = type - BLOCK_TORCH;
        if (variant < 0 || variant >= decorationBuffers.length) return false;
        Mesh mesh = type == BLOCK_DOOR
            ? createDoorMesh(decorationAppearances[variant])
            : new Mesh(decorationBuffers[variant], decorationTriangles, decorationAppearances[variant]);
        if (type == BLOCK_TORCH) mesh.setScale(0.18f, 0.58f, 1.0f);
        else if (type == BLOCK_SAPLING) mesh.setScale(0.48f, 0.62f, 1.0f);
        else if (type != BLOCK_DOOR) mesh.setScale(0.38f, 0.48f, 1.0f);
        mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
        // Door geometry's local origin is its hinge; other decorations stay centered.
        if (type == BLOCK_DOOR) mesh.setTranslation(x - 0.5f, y, z);
        else mesh.setTranslation(x, y, z);
        decorationGroup.addChild(mesh);
        int at = decorationCount++;
        decorationMeshes[at] = mesh;
        decorationTypes[at] = type;
        decorationX[at] = x;
        decorationY[at] = y;
        decorationZ[at] = z;
        decorationYaw[at] = yaw;
        decorationOpen[at] = false;
        return true;
    }

    /**
     * Build a two-block-tall door. In the Indev atlas the wooden door's upper
     * tile is at (1,5) and its lower tile is directly below at (1,6).
     * Local X=0 is the hinge edge, so rotating the mesh opens it like a real
     * door instead of spinning it around its center.
     */
    private Mesh createDoorMesh(Appearance appearance) {
        final int quadCount = 8;
        short[] positions = new short[quadCount * 12];
        short[] texCoords = new short[quadCount * 8];
        int v = 0;
        int doorX = BLOCK_TILE_X[BLOCK_DOOR], doorY = BLOCK_TILE_Y[BLOCK_DOOR];
        int plankX = BLOCK_TILE_X[11], plankY = BLOCK_TILE_Y[11];

        // Front and back panels. Each half is exactly one block high;
        // both sides use the same atlas tile and UV orientation.
        v = appendDoorQuad(positions, texCoords, v,
            0,0,-1, 0,32,-1, 32,0,-1, 32,32,-1, doorX,doorY+1);
        v = appendDoorQuad(positions, texCoords, v,
            0,32,-1, 0,64,-1, 32,32,-1, 32,64,-1, doorX,doorY);
        v = appendDoorQuad(positions, texCoords, v,
            0,0,1, 0,32,1, 32,0,1, 32,32,1, doorX,doorY+1);
        v = appendDoorQuad(positions, texCoords, v,
            0,32,1, 0,64,1, 32,32,1, 32,64,1, doorX,doorY);

        // Thin side edges and top/bottom caps use the plank tile.
        v = appendDoorQuad(positions, texCoords, v,
            0,0,1, 0,64,1, 0,0,-1, 0,64,-1, plankX,plankY);
        v = appendDoorQuad(positions, texCoords, v,
            32,0,-1, 32,64,-1, 32,0,1, 32,64,1, plankX,plankY);
        v = appendDoorQuad(positions, texCoords, v,
            0,64,-1, 0,64,1, 32,64,-1, 32,64,1, plankX,plankY);
        v = appendDoorQuad(positions, texCoords, v,
            0,0,1, 0,0,-1, 32,0,1, 32,0,-1, plankX,plankY);

        VertexArray pa = new VertexArray(v, 3, 2);
        pa.set(0, v, positions);
        VertexArray ta = new VertexArray(v, 2, 2);
        ta.set(0, v, texCoords);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 32.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 256.0f, null);
        int[] stripLengths = {4,4,4,4,4,4,4,4};
        int[] indices = new int[v];
        for (int i=0; i<v; i++) indices[i]=i;
        TriangleStripArray triangles = new TriangleStripArray(indices, stripLengths);
        return new Mesh(vb, triangles, appearance);
    }

    private int appendDoorQuad(short[] positions, short[] texCoords, int vertex,
                               int x0,int y0,int z0, int x1,int y1,int z1,
                               int x2,int y2,int z2, int x3,int y3,int z3,
                               int tileX,int tileY) {
        int p=vertex*3;
        positions[p]=(short)x0; positions[p+1]=(short)y0; positions[p+2]=(short)z0;
        positions[p+3]=(short)x1; positions[p+4]=(short)y1; positions[p+5]=(short)z1;
        positions[p+6]=(short)x2; positions[p+7]=(short)y2; positions[p+8]=(short)z2;
        positions[p+9]=(short)x3; positions[p+10]=(short)y3; positions[p+11]=(short)z3;
        int t=vertex*2, u0=tileX*16+1, u1=(tileX+1)*16-1;
        int v0=tileY*16+1, v1=(tileY+1)*16-1;
        texCoords[t]=(short)u0; texCoords[t+1]=(short)v1;
        texCoords[t+2]=(short)u0; texCoords[t+3]=(short)v0;
        texCoords[t+4]=(short)u1; texCoords[t+5]=(short)v1;
        texCoords[t+6]=(short)u1; texCoords[t+7]=(short)v0;
        return vertex+4;
    }

    private void toggleDoor(int index) {
        if (index < 0 || index >= decorationCount || decorationTypes[index] != BLOCK_DOOR) return;
        decorationOpen[index] = !decorationOpen[index];
        float angle = decorationYaw[index] + (decorationOpen[index] ? 90.0f : 0.0f);
        decorationMeshes[index].setOrientation(angle, 0.0f, 1.0f, 0.0f);
    }

    private int raycastDecoration() {
        double yr = Math.PI * pitch / 180.0;
        double xr = Math.PI * yaw / 180.0;
        float dx = (float)(Math.sin(xr) * Math.cos(yr));
        float dz = (float)(-Math.cos(xr) * Math.cos(yr));
        float dy = (float)Math.sin(yr);
        for (float d = 0.05f; d <= 5.0f; d += 0.05f) {
            float px = cameraX + dx * d, py = cameraY + dy * d, pz = cameraZ + dz * d;
            for (int i = decorationCount - 1; i >= 0; i--) {
                float radius = decorationTypes[i] == BLOCK_TORCH ? 0.22f : 0.48f;
                float height = decorationTypes[i] == BLOCK_DOOR ? 2.0f :
                    (decorationTypes[i] == BLOCK_TORCH ? 1.15f : 0.96f);
                if (Math.abs(px - decorationX[i]) <= radius &&
                    Math.abs(pz - decorationZ[i]) <= radius &&
                    py >= decorationY[i] && py <= decorationY[i] + height) return i;
            }
        }
        return -1;
    }

    private void updateTorchLights() {
        if (IndevViewerMIDlet.humbleMode) {
            for (int i = 0; i < torchLights.length; i++) torchLights[i].setIntensity(0.0f);
            return;
        }
        int[] nearest = {-1, -1, -1, -1};
        float[] distances = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        for (int i = 0; i < decorationCount; i++) {
            if (decorationTypes[i] != BLOCK_TORCH) continue;
            float dx = decorationX[i] - cameraX, dz = decorationZ[i] - cameraZ;
            float d2 = dx * dx + dz * dz;
            for (int slot = 0; slot < nearest.length; slot++) {
                if (d2 < distances[slot]) {
                    for (int shift = nearest.length - 1; shift > slot; shift--) {
                        nearest[shift] = nearest[shift - 1];
                        distances[shift] = distances[shift - 1];
                    }
                    nearest[slot] = i;
                    distances[slot] = d2;
                    break;
                }
            }
        }
        for (int i = 0; i < torchLights.length; i++) {
            if (nearest[i] >= 0 && distances[i] < 18.0f * 18.0f) {
                int at = nearest[i];
                torchLights[i].setTranslation(decorationX[at], decorationY[at] + 0.7f, decorationZ[at]);
                torchLights[i].setIntensity(1.0f);
            } else {
                torchLights[i].setIntensity(0.0f);
                torchLights[i].setTranslation(0.0f, -1000.0f, 0.0f);
            }
        }
    }

    private void placeBlock() {
        // Use the place/interact key on an existing door to open or close it.
        // This check comes before inventory validation, so interaction works
        // even when the currently selected hotbar slot is empty.
        int decorationHit = raycastDecoration();
        if (decorationHit >= 0 && decorationTypes[decorationHit] == BLOCK_DOOR) {
            toggleDoor(decorationHit);
            return;
        }
        if (selectedSlot < 0 || selectedSlot >= inventory.length || inventory[selectedSlot] <= 0) return;
        int[] hit = raycastSurface();
        if (hit == null) return;
        int x = hit[0], z = hit[1], h = terrain.getHeight(x, z);
        if (selectedSlot >= BLOCK_TORCH && selectedSlot <= BLOCK_DOOR) {
            if (decorationCount >= MAX_DECORATIONS) return;
            if (h >= TerrainGenerator.HEIGHT - 3) return;
            if (placeDecoration(selectedSlot, x + 0.5f, h, z + 0.5f)) {
                inventory[selectedSlot]--;
                updateTorchLights();
                if (selectedSlot == BLOCK_TORCH) reloadEditedChunks();
            }
            return;
        }
        if (h >= TerrainGenerator.HEIGHT - 3) return;
        terrain.setHeight(x, z, h + 1);
        terrain.setTopType(x, z, selectedSlot);
        inventory[selectedSlot]--;
        reloadEditedChunks();
        resolvePlayerAfterTerrainEdit();
    }

    /**
     * Keep the current jump/swim trajectory when a distant block is edited.
     * Only correct the player if the edited terrain has actually moved the
     * ground into the player's feet, or removed the support under a grounded
     * player. This avoids snapping the camera to the ground on every edit.
     */
    private void resolvePlayerAfterTerrainEdit() {
        float groundY = terrain.getHeight((int) cameraX, (int) cameraZ) + 1.65f;
        if (cameraY < groundY) {
            // A newly raised block intersects the player: place them safely
            // on top, and cancel only the downward motion into that block.
            cameraY = groundY;
            if (verticalVelocity < 0.0f) verticalVelocity = 0.0f;
            airborne = false;
        } else if (cameraY > groundY + 0.02f) {
            // If the block underfoot was removed, begin falling naturally.
            // Existing upward/downward velocity is deliberately preserved.
            airborne = true;
        }
        updateCamera();
    }
    private int[] raycastLeafBlock() {
        double yr = Math.PI * pitch / 180.0;
        double xr = Math.PI * yaw / 180.0;
        float dx = (float) (Math.sin(xr) * Math.cos(yr));
        float dz = (float) (-Math.cos(xr) * Math.cos(yr));
        float dy = (float) Math.sin(yr);
        // Start almost at the camera; a larger minimum distance skipped nearby leaves.
        for (float d = 0.05f; d <= 5.0f; d += 0.04f) {
            int x = (int) (cameraX + dx * d);
            int y = (int) (cameraY + dy * d);
            int z = (int) (cameraZ + dz * d);
            if (terrain.isLeafBlockAt(x, y, z)) return new int[] {x, y, z};
        }
        return null;
    }

    /**
     * Sample the view ray at short intervals so blocks close to the camera
     * are not skipped. Returns {x, z, blockY, sideHit}; sideHit is true when
     * the ray intersects an exposed vertical face below the column's top.
     */
    private int[] raycastSurface() {
        double yr = Math.PI * pitch / 180.0;
        double xr = Math.PI * yaw / 180.0;
        float dx = (float) (Math.sin(xr) * Math.cos(yr));
        float dz = (float) (-Math.cos(xr) * Math.cos(yr));
        float dy = (float) Math.sin(yr);

        for (float d = 0.05f; d <= 5.0f; d += 0.04f) {
            float fx = cameraX + dx * d;
            float fy = cameraY + dy * d;
            float fz = cameraZ + dz * d;
            int x = (int) fx, z = (int) fz;
            int blockY = (int) fy;
            if (x < 1 || z < 1 || x >= TerrainGenerator.SIZE - 1
                    || z >= TerrainGenerator.SIZE - 1 || blockY < 0) continue;

            int surface = terrain.getHeight(x, z);
            // Top-face hit, including a small tolerance around the surface.
            if (fy <= surface + 0.25f && fy >= surface - 1.0f) {
                return new int[] {x, z, surface - 1, 0};
            }

            // Side-face hit: this column rises above the ray while at least one
            // neighboring column leaves the sampled block layer exposed.
            if (blockY < surface - 1 && blockY >= 0) {
                if (terrain.getHeight(x - 1, z) <= blockY
                        || terrain.getHeight(x + 1, z) <= blockY
                        || terrain.getHeight(x, z - 1) <= blockY
                        || terrain.getHeight(x, z + 1) <= blockY) {
                    return new int[] {x, z, blockY, 1};
                }
            }
        }
        return null;
    }

    private int addQuad(short[] positions, short[] texCoords, byte[] colors,
                        int[] stripLengths, int vertex, int strip,
                        int x0, int y0, int z0, int x1, int y1, int z1,
                        int x2, int y2, int z2, int x3, int y3, int z3,
                        int tileX, int tileY) {
        // Indev-style lighting is block-flat: all four vertices of a face
        // share one shade, preventing M3G from interpolating triangular gradients.
        int centerX = (x0 + x1 + x2 + x3) / 4;
        int centerY = (y0 + y1 + y2 + y3) / 4;
        int centerZ = (z0 + z1 + z2 + z3) / 4;
        int baseShade;
        if (IndevViewerMIDlet.humbleMode) baseShade = 255;
        else {
            int cacheKey = centerY * TerrainGenerator.SIZE * TerrainGenerator.SIZE + centerZ * TerrainGenerator.SIZE + centerX;
            int cacheSlot = (cacheKey ^ (cacheKey >>> 9)) & (shadeCacheKeys.length - 1);
            if (shadeCacheKeys[cacheSlot] == cacheKey) baseShade = shadeCacheValues[cacheSlot];
            else { baseShade = terrain.getShadowShade(centerX, centerZ, centerY); shadeCacheKeys[cacheSlot] = cacheKey; shadeCacheValues[cacheSlot] = baseShade; }
        }
        int glow = IndevViewerMIDlet.humbleMode ? 0 : torchGlowAt(centerX, centerY, centerZ);
        int shade = baseShade + glow;
        if (shade > 255) shade = 255;
        int firstVertex = vertex;
        vertex = addVertex(positions, texCoords, colors, vertex, x0, y0, z0,
                           tileX, tileY, 0, shade);
        vertex = addVertex(positions, texCoords, colors, vertex, x1, y1, z1,
                           tileX, tileY, 1, shade);
        vertex = addVertex(positions, texCoords, colors, vertex, x2, y2, z2,
                           tileX, tileY, 2, shade);
        vertex = addVertex(positions, texCoords, colors, vertex, x3, y3, z3,
                           tileX, tileY, 3, shade);
        if (glow > 0) {
            int green = shade - glow / 5;
            int blue = shade - glow / 2;
            if (green < baseShade) green = baseShade;
            if (blue < baseShade) blue = baseShade;
            for (int v = firstVertex; v < vertex; v++) {
                int offset = v * 3;
                colors[offset] = (byte) shade;
                colors[offset + 1] = (byte) green;
                colors[offset + 2] = (byte) blue;
            }
        }
        stripLengths[strip] = 4;
        return vertex;
    }

    /** Bakes warm torch light into terrain vertex colours for CLDC/M3G renderers
     * that do not calculate per-pixel point lighting. The scene also carries
     * real M3G point lights, so nearby supported renderers get dynamic lighting. */
    private int torchGlowAt(int x, int y, int z) {
        int glow = 0;
        for (int i = 0; i < decorationCount; i++) {
            if (decorationTypes[i] != BLOCK_TORCH) continue;
            float dx = x + 0.5f - decorationX[i];
            float dy = y + 0.5f - (decorationY[i] + 0.7f);
            float dz = z + 0.5f - decorationZ[i];
            // Most faces are outside the torch radius. Avoid the relatively
            // expensive square root unless this torch can affect the face.
            float distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < 49.0f) {
                float distance = (float)Math.sqrt(distanceSquared);
                int amount = (int)((7.0f - distance) * 28.0f);
                if (amount > glow) glow = amount;
            }
        }
        return glow;
    }

    private int addVertex(short[] positions, short[] texCoords, byte[] colors,
                          int vertex, int x, int y, int z,
                          int tileX, int tileY, int corner, int shade) {
        int p = vertex * 3;
        positions[p] = (short) x;
        positions[p + 1] = (short) y;
        positions[p + 2] = (short) z;
        int colorOffset = vertex * 3;
        colors[colorOffset] = (byte) shade;
        colors[colorOffset + 1] = (byte) shade;
        colors[colorOffset + 2] = (byte) shade;

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
