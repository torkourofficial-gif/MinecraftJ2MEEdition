package mcj2me;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;

final class WorldCanvas extends Canvas implements Runnable {
    // Keep the movement IDs local so this class does not depend on MIDlet constant visibility.
    private static final int CONTROL_FORWARD = 0, CONTROL_BACKWARD = 1;
    private static final int CONTROL_STRAFE_LEFT = 2, CONTROL_STRAFE_RIGHT = 3;
    private static final int CONTROL_LOOK_UP = 4, CONTROL_LOOK_DOWN = 5;
    private static final int CONTROL_TURN_LEFT = 6, CONTROL_TURN_RIGHT = 7;
    private static final int CONTROL_JUMP = 8, CONTROL_BREAK = 9, CONTROL_PLACE = 10;
    private static final int CHUNK = 16;
    private static final int RADIUS = 1;
    private static final float PLAYER_HEIGHT = 1.8f; // Minecraft player AABB: 0.6 x 1.8 blocks.
    private static final float PLAYER_EYE_HEIGHT = 1.62f;
    private static final float PLAYER_HEAD_OFFSET = PLAYER_HEIGHT - PLAYER_EYE_HEIGHT;
    private static final float PLAYER_HALF_WIDTH = 0.30f;
    private static final float BLOCK_REACH = 5.0f;

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

    private final Random particleRandom = new Random();
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
    // Doors are children of a hinge pivot, so their panel sweeps an arc around one edge.
    private final Group[] decorationPivots = new Group[MAX_DECORATIONS];
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
    private Image hudIconsImage;
    private Image guiBlocksImage;
    private Image inventoryImage;
    private final Image[] blockIcons = new Image[42];
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
    private Appearance flowerAppearance;
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
    private final int[] surfaceHitResult = new int[8];
    private final int[] leafHitResult = new int[3];
    private boolean paused;
    private boolean pauseOverlayOpen;
    private int pauseSelection;
    private Image pauseOverlay;
    private int pauseOverlayWidth = -1, pauseOverlayHeight = -1;
    private boolean forward, backward, strafeLeft, strafeRight;
    private boolean lookUp, lookDown, turnLeft, turnRight;
    private boolean jumpHeld;
    private boolean flying;
    private int jumpTapWindowTicks;
    private int flightTapWindowTicks;
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
    // Coordinates are pixel offsets in the 512x512 gui_blocks atlas.
    // The first five entries are the actual grass, dirt, stone, oak-log and
    // leaves cube icons; the remaining blocks are mapped by their own atlas cells.
    private static final int[] GUI_ICON_X = {432, 384, 0, 288, 192};
    private static final int[] GUI_ICON_Y = {0, 0, 0, 48, 0};
    private static final int BLOCK_TORCH = 16, BLOCK_YELLOW_FLOWER = 17, BLOCK_RED_FLOWER = 18, BLOCK_SAPLING = 19, BLOCK_DOOR = 20;
    private static final int ITEM_WATER_BUCKET = 40, ITEM_EMPTY_BUCKET = 41;
    private static final int MAX_WATER_SOURCES = 8;
    private static final int WATER_FLOW_INTERVAL = 10;
    private final int[] waterFlowX = new int[MAX_WATER_SOURCES];
    private final int[] waterFlowZ = new int[MAX_WATER_SOURCES];
    private final int[] waterFlowLevel = new int[MAX_WATER_SOURCES];
    private final int[] waterFlowTicks = new int[MAX_WATER_SOURCES];
    private final boolean[] waterFlowActive = new boolean[MAX_WATER_SOURCES];
    private int waterFlowClock = 0;
    private static final String[] BLOCK_NAMES = {
        "GRASS", "DIRT", "STONE", "WOOD", "LEAVES", "COAL ORE", "IRON ORE", "GOLD ORE", "DIAMOND ORE",
        "BEDROCK", "TNT", "OAK PLANKS", "SAND", "GRAVEL", "BRICK", "OBSIDIAN",
        "TORCH", "YELLOW FLOWER", "RED FLOWER", "SAPLING", "DOOR",
        "IRON BLOCK", "GOLD BLOCK", "DIAMOND BLOCK",
        "WHITE WOOL", "ORANGE WOOL", "MAGENTA WOOL", "LIGHT BLUE WOOL", "YELLOW WOOL", "LIME WOOL",
        "PINK WOOL", "GRAY WOOL", "LIGHT GRAY WOOL", "CYAN WOOL", "PURPLE WOOL", "BLUE WOOL",
        "BROWN WOOL", "GREEN WOOL", "RED WOOL", "BLACK WOOL", "WATER BUCKET", "EMPTY BUCKET"
    };
    // Creative-style starter inventory: every block stack starts at 64.
    // Buckets remain individual items; mining can never grow a stack past 64.
    private final int[] inventory = {
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64,
        64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 1, 0
    };
    private int selectedSlot = 0, particleTicks = 0, actionCooldown = 0;
    private int tntFuseTicks = 0, tntX = -1, tntZ = -1, tntFlashCounter = 0;
    private boolean tntFlashOn = false;
    private static final int BLOCK_PARTICLE_COUNT = 16;
    private static final int BLOCK_PARTICLE_LIFE = 26;
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
    private String setupStage = "constructor";
    private final String worldName;
    private final int worldSeed;
    private final int worldId;
    private final int worldMode;
    private int health = 20;
    private int zombieDamageCooldown = 0;
    private int lastMiningDelay = 4;
    private boolean breakHeld;
    private int miningProgress;
    private static final int MAX_DROPPED_ITEMS = 8;
    private final Mesh[] droppedMeshes = new Mesh[MAX_DROPPED_ITEMS];
    private final int[] droppedTypes = new int[MAX_DROPPED_ITEMS];
    private final float[] droppedX = new float[MAX_DROPPED_ITEMS];
    private final float[] droppedY = new float[MAX_DROPPED_ITEMS];
    private final float[] droppedZ = new float[MAX_DROPPED_ITEMS];
    private final int[] droppedAge = new int[MAX_DROPPED_ITEMS];
    private Group droppedItemGroup;

    WorldCanvas(IndevViewerMIDlet owner, String name, int seed) {
        this(owner, name, seed, 0, IndevViewerMIDlet.MODE_CREATIVE, null);
    }

    WorldCanvas(IndevViewerMIDlet owner, String name, int seed, int id, byte[] savedTerrain) {
        this(owner, name, seed, id, IndevViewerMIDlet.MODE_CREATIVE, savedTerrain);
    }

    WorldCanvas(IndevViewerMIDlet owner, String name, int seed, int id, int mode, byte[] savedTerrain) {
        midlet = owner;
        worldName = name;
        worldSeed = seed;
        worldId = id;
        worldMode = mode == IndevViewerMIDlet.MODE_SURVIVAL ? IndevViewerMIDlet.MODE_SURVIVAL : IndevViewerMIDlet.MODE_CREATIVE;
        if (worldMode == IndevViewerMIDlet.MODE_SURVIVAL) {
            for (int i = 0; i < inventory.length; i++) inventory[i] = 0;
            selectedSlot = 0;
        }
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
            errorMessage = "M3G setup failed at " + setupStage + ": " + e.toString();
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
        int activeWaterSources = 0;
        for (int i = 0; i < MAX_WATER_SOURCES; i++) if (waterFlowActive[i]) activeWaterSources++;
        out.writeInt(activeWaterSources);
        for (int i = 0; i < MAX_WATER_SOURCES; i++) if (waterFlowActive[i]) {
            out.writeInt(waterFlowX[i]); out.writeInt(waterFlowZ[i]); out.writeInt(waterFlowLevel[i]);
            out.writeInt(waterFlowTicks[i]);
        }
        out.writeInt(health);
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
        // Older save records can contain the pre-bucket inventory (40 slots).
        // Keep defaults for newly added slots, then overwrite slots present in
        // the save so water buckets and the placed-water state remain compatible.
        if (invCount < 0 || invCount > inventory.length)
            throw new Exception("Invalid saved inventory");
        for (int i = 0; i < invCount; i++) inventory[i] = Math.max(0, in.readInt());
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
                if (type == BLOCK_DOOR && decorationPivots[at] != null)
                    decorationPivots[at].setOrientation(savedYaw + (opened ? 90.0f : 0.0f), 0.0f, 1.0f, 0.0f);
            }
        }
        if (in.available() >= 4) {
            int savedFlows = in.readInt();
            if (savedFlows < 0 || savedFlows > MAX_WATER_SOURCES) throw new Exception("Invalid saved water flow");
            for (int i = 0; i < savedFlows; i++) {
                waterFlowX[i] = in.readInt(); waterFlowZ[i] = in.readInt();
                waterFlowLevel[i] = in.readInt(); waterFlowTicks[i] = in.readInt();
                waterFlowActive[i] = true;
            }
        }
        if (in.available() >= 4) {
            health = Math.max(1, Math.min(20, in.readInt()));
            if (in.available() >= 4) in.readInt(); // discard legacy food count
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

        int spawnX = (int) cameraX;
        int spawnZ = (int) cameraZ;
        if (!isPlayerColumnClear(spawnX, spawnZ)) {
            // Search outwards for a clear surface column instead of leaving
            // the camera inside terrain, trees, leaves, or water.
            boolean found = false;
            for (int radius = 1; radius <= 12 && !found; radius++) {
                for (int dx = -radius; dx <= radius && !found; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                        int x = spawnX + dx, z = spawnZ + dz;
                        if (x < 4 || z < 4 || x >= TerrainGenerator.SIZE - 4 ||
                            z >= TerrainGenerator.SIZE - 4) continue;
                        if (isPlayerColumnClear(x, z)) {
                            spawnX = x;
                            spawnZ = z;
                            cameraX = x + 0.5f;
                            cameraZ = z + 0.5f;
                            found = true;
                            break;
                        }
                    }
                }
            }
        }

        float groundY = terrain.getHeight(spawnX, spawnZ) + PLAYER_EYE_HEIGHT;
        if (cameraY < groundY || !isPlayerColumnClear(spawnX, spawnZ)) {
            cameraY = groundY;
            verticalVelocity = 0.0f;
            airborne = false;
        }
        updateCamera();
    }

    private boolean isPlayerColumnClear(int x, int z) {
        if (x < 2 || z < 2 || x >= TerrainGenerator.SIZE - 2 ||
            z >= TerrainGenerator.SIZE - 2) return false;
        int ground = terrain.getHeight(x, z);
        if (terrain.isWaterAt(x, z) || terrain.isTreeAt(x, z)) return false;
        // The player occupies roughly two vertical cells above the ground.
        for (int y = ground; y <= ground + 2; y++) {
            if (terrain.isLeafBlockAt(x, y, z)) return false;
        }
        // Avoid sharp ledges where the camera can clip into a neighboring column.
        if (Math.abs(ground - terrain.getHeight(x - 1, z)) > 1 ||
            Math.abs(ground - terrain.getHeight(x + 1, z)) > 1 ||
            Math.abs(ground - terrain.getHeight(x, z - 1)) > 1 ||
            Math.abs(ground - terrain.getHeight(x, z + 1)) > 1) return false;
        return true;
    }

    boolean hasSetupError() { return errorMessage != null; }
    String getSetupError() { return errorMessage; }

    private void setupScene() throws Exception {
        // Initialize M3G objects inside the guarded setup path so API/linkage
        // failures can be reported by the constructor instead of escaping
        // from field initializers.
        setupStage = "Graphics3D.getInstance";
        graphics3D = Graphics3D.getInstance();
        world = new World();
        terrainGroup = new Group();
        waterGroup = new Group();
        cloudGroup = new Group();
        blockParticleGroup = new Group();
        droppedItemGroup = new Group();
        decorationGroup = new Group();
        camera = new Camera();

        setupStage = "loading /mcj2me/textures.png";
        Image atlas = createWoolAtlas(ResourceImages.load("/mcj2me/textures.png"));
        terrainAtlasImage = atlas;
        setupStage = "loading /mcj2me/gui.png";
        guiImage = ResourceImages.load("/mcj2me/gui.png");
        setupStage = "loading /mcj2me/gui_blocks.png";
        guiBlocksImage = ResourceImages.load("/mcj2me/gui_blocks.png");
        setupStage = "loading /mcj2me/inventory.png";
        inventoryImage = ResourceImages.load("/mcj2me/inventory.png");
        setupStage = "loading /mcj2me/items.png";
        Image itemsImage = ResourceImages.load("/mcj2me/items.png");
        hudIconsImage = ResourceImages.load("/mcj2me/icons.png");
        setupStage = "creating inventory icon 0 (grass)";
        blockIcons[0] = createGuiBlockIcon(GUI_ICON_X[0], GUI_ICON_Y[0]);
        setupStage = "creating inventory icon 1 (dirt)";
        blockIcons[1] = createGuiBlockIcon(GUI_ICON_X[1], GUI_ICON_Y[1]);
        setupStage = "creating inventory icon 2 (stone)";
        blockIcons[2] = createGuiBlockIcon(GUI_ICON_X[2], GUI_ICON_Y[2]);
        setupStage = "creating inventory icon 3 (wood)";
        blockIcons[3] = createGuiBlockIcon(GUI_ICON_X[3], GUI_ICON_Y[3]);
        setupStage = "creating inventory icon 4 (leaves)";
        blockIcons[4] = createGuiBlockIcon(GUI_ICON_X[4], GUI_ICON_Y[4]);
        for (int bi = 5; bi < 40; bi++) {
            setupStage = "creating inventory icon " + bi + " (" + BLOCK_NAMES[bi] + ")";
            blockIcons[bi] = createGuiBlockInventoryIcon(bi, atlas);
        }
        // Coordinates are 16x16 cells in the 256x256 classic items atlas.
        // Keep these distinct from the armor icons near the top-left of the sheet.
        setupStage = "creating inventory icon 20 (door from items.png)";
        blockIcons[BLOCK_DOOR] = createTerrainBlockIcon(atlas, BLOCK_TILE_X[BLOCK_DOOR], BLOCK_TILE_Y[BLOCK_DOOR]);
        setupStage = "creating inventory icon 40 (water bucket from items.png)";
        blockIcons[ITEM_WATER_BUCKET] = createItemsIcon(itemsImage, 11, 4);
        setupStage = "creating inventory icon 41 (empty bucket from items.png)";
        blockIcons[ITEM_EMPTY_BUCKET] = createItemsIcon(itemsImage, 10, 4);
        setupStage = "loading /mcj2me/clouds.png";
        Image cloudsImage = ResourceImages.load("/mcj2me/clouds.png");
        setupStage = "creating terrain Image2D/Texture2D";
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
        // Terrain cube faces are opaque. Plant cut-outs are rendered separately
        // with alpha testing so transparent flower pixels never affect side faces.
        flowerAppearance = new Appearance();
        flowerAppearance.setTexture(0, texture);
        flowerAppearance.setFog(distanceFog);
        PolygonMode flowerPolygonMode = new PolygonMode();
        flowerPolygonMode.setCulling(PolygonMode.CULL_NONE);
        flowerPolygonMode.setShading(PolygonMode.SHADE_FLAT);
        flowerAppearance.setPolygonMode(flowerPolygonMode);
        CompositingMode flowerBlend = new CompositingMode();
        flowerBlend.setBlending(CompositingMode.ALPHA);
        flowerBlend.setAlphaThreshold(0.5f);
        flowerAppearance.setCompositingMode(flowerBlend);

        PolygonMode transparentPolygonMode = new PolygonMode();
        transparentPolygonMode.setCulling(PolygonMode.CULL_NONE);
        transparentPolygonMode.setShading(PolygonMode.SHADE_FLAT);

        // Extract the classic still-water tile from the existing atlas, tint it
        // with partial alpha, then repeat/scroll it independently for animation.
        setupStage = "creating water texture";
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
        setupStage = "creating cloud texture";
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

        setupStage = "creating fog, background and ambient light";
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

        cameraY = terrain.getHeight(64, 105) + PLAYER_EYE_HEIGHT;
        camera.setPerspective(70.0f, 1.0f, 0.1f, 180.0f);
        world.addChild(camera);
        world.setActiveCamera(camera);
        world.addChild(terrainGroup);
        world.addChild(waterGroup);
        world.addChild(decorationGroup);
        world.addChild(droppedItemGroup);
        setupStage = "creating decorations and torch lights";
        setupDecorations();
        setupTorchLights();
        setupStage = "building cloud mesh";
        Mesh clouds = buildCloudPlane(cloudAppearance);
        if (clouds != null) cloudGroup.addChild(clouds);
        // A separate, very large sea surface continues beyond the finite map.
        setupStage = "building horizon sea mesh";
        Mesh horizonSea = buildHorizonSea();
        if (horizonSea != null) waterGroup.addChild(horizonSea);
        world.addChild(cloudGroup);
        setupStage = "building block-particle meshes";
        setupBlockParticles(atlas);
        world.addChild(blockParticleGroup);
        setupStage = "loading passive mob textures";
        passiveMobs = new PassiveMob(world, terrain, cameraX, cameraZ);
        setupStage = "loading hostile mob texture";
        hostileMobs = new HostileMob(world, terrain, cameraX, cameraZ);
        setupStage = "updating camera and building initial terrain chunks";
        updateCamera();
        updateChunks();
        setupStage = "complete";
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
        pauseOverlayOpen = true;
        pauseSelection = 0;
        clearKeys();
        repaint();
    }

    synchronized void resume() {
        if (running) {
            paused = false;
            pauseOverlayOpen = false;
            clearKeys();
            repaint();
        } else {
            start();
        }
    }

    synchronized boolean isPauseOverlayOpen() { return pauseOverlayOpen; }

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
                tickMining();
                tickSurvival();
                tickDroppedItems();
                tickWaterFlow();
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
        if (pauseOverlayOpen) drawPauseOverlay(g);
    }

    private void drawPauseOverlay(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (pauseOverlay == null || pauseOverlayWidth != w || pauseOverlayHeight != h) {
            int[] pixels = new int[w * h];
            for (int i = 0; i < pixels.length; i++) pixels[i] = 0x99000000;
            pauseOverlay = Image.createRGBImage(pixels, w, h, true);
            pauseOverlayWidth = w; pauseOverlayHeight = h;
        }
        g.drawImage(pauseOverlay, 0, 0, Graphics.TOP | Graphics.LEFT);
        int bw = Math.min(w - 24, 210); if (bw < 1) bw = w;
        int bh = Math.min(29, Math.max(22, h / 11)), bx = (w - bw) / 2;
        int y = Math.max(8, (h - bh * 2 - 10) / 2);
        g.setColor(0xFFFFFF);
        g.drawString("Game menu", w / 2, y - 30, Graphics.TOP | Graphics.HCENTER);
        drawPauseButton(g, bx, y, bw, bh, "Settings", pauseSelection == 0);
        drawPauseButton(g, bx, y + bh + 10, bw, bh, "Save and quit to title", pauseSelection == 1);
        g.drawString("BACK: resume   UP/DOWN: select", w / 2, h - 12, Graphics.BOTTOM | Graphics.HCENTER);
    }

    private void drawPauseButton(Graphics g, int x, int y, int w, int h, String text, boolean selected) {
        g.setColor(selected ? 0x666666 : 0x333333); g.fillRect(x, y, w, h);
        g.setColor(selected ? 0xFFFFFF : 0xAAAAAA); g.drawRect(x, y, w - 1, h - 1);
        g.setColor(selected ? 0xFFFF55 : 0xFFFFFF);
        Font font = Font.getDefaultFont();
        g.drawString(text, x + (w - font.stringWidth(text)) / 2,
            y + (h - font.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
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
        int surface = terrain.getWaterSurfaceLevel((int) cameraX, (int) cameraZ);
        return surface > 0 && cameraY < surface;
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
        int surface = terrain.getWaterSurfaceLevel((int) cameraX, (int) cameraZ);
        return surface > 0 && cameraY < surface + 1.0f;
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
        if (worldMode == IndevViewerMIDlet.MODE_SURVIVAL) {
            int heartY=h-36, heartX=(w-90)/2;
            for(int heart=0;heart<10;heart++){
                int value=health-heart*2, sx=value>=2?52:(value==1?61:16);
                if(hudIconsImage!=null)g.drawRegion(hudIconsImage,sx,0,9,9,0,
                    heartX+heart*9,heartY,Graphics.TOP|Graphics.LEFT);
            }
        } else {
            g.setColor(0xDDDDDD);
            g.drawString("CREATIVE", w - 5, 20, Graphics.TOP | Graphics.RIGHT);
        }
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
            } else if (pauseOverlayOpen) {
                resume();
            } else {
                pause();
            }
            return;
        }

        if (pauseOverlayOpen) {
            if (action == UP || action == DOWN || keyCode == '2' || keyCode == '8') {
                pauseSelection = (pauseSelection + 1) % 2;
                repaint();
            } else if (action == FIRE || keyCode == '5' || keyCode == ' ' || keyCode == 10) {
                if (pauseSelection == 0) midlet.openPauseSettings();
                else midlet.returnToMainMenu();
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
                inventoryPageStart = 15;
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
            if (attackMobs()) {
                breakHeld = false;
                actionCooldown = 4;
            } else if (worldMode == IndevViewerMIDlet.MODE_SURVIVAL) {
                if (!breakHeld) {
                    breakHeld = true;
                    miningProgress = 0;
                }
            } else {
                breakBlock();
                actionCooldown = 4;
            }
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

        if (IndevViewerMIDlet.controlPressed(CONTROL_JUMP,keyCode,action) && !jumpHeld) {
            jumpHeld = true;
            if (worldMode == IndevViewerMIDlet.MODE_SURVIVAL) {
                if (isSwimming()) {
                    verticalVelocity = Math.max(verticalVelocity, 0.24f);
                    airborne = true;
                } else if (!airborne || cameraY <= getGroundHeightBelow(cameraX,cameraZ,cameraY-PLAYER_EYE_HEIGHT)+PLAYER_EYE_HEIGHT+0.12f) {
                    verticalVelocity=JUMP_SPEED; airborne=true;
                }
            } else if (flying) {
                // Like Minecraft creative: tap jump twice quickly to stop flying.
                if (flightTapWindowTicks > 0) {
                    flying = false;
                    flightTapWindowTicks = 0;
                    verticalVelocity = 0.0f;
                    airborne = true;
                } else {
                    flightTapWindowTicks = 12;
                }
            } else if (isSwimming()) {
                verticalVelocity = Math.max(verticalVelocity, 0.24f);
                airborne = true;
            } else if (airborne && jumpTapWindowTicks > 0
                    && cameraY > getGroundHeightBelow(cameraX,cameraZ,cameraY-PLAYER_EYE_HEIGHT)+PLAYER_EYE_HEIGHT+0.12f) {
                // Only treat a double-tap as flight while genuinely airborne.
                flying = true;
                verticalVelocity = 0.0f;
            } else if (!airborne || cameraY <= getGroundHeightBelow(cameraX,cameraZ,cameraY-PLAYER_EYE_HEIGHT)+PLAYER_EYE_HEIGHT+0.12f) {
                verticalVelocity=JUMP_SPEED; airborne=true; jumpTapWindowTicks=12;
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
        if (IndevViewerMIDlet.controlPressed(CONTROL_BREAK,keyCode,action)) {
            breakHeld = false;
            miningProgress = 0;
        }
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
        breakHeld = false;
        miningProgress = 0;
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

        float groundY = getGroundHeightBelow(cameraX, cameraZ, cameraY - PLAYER_EYE_HEIGHT) + PLAYER_EYE_HEIGHT;
        boolean inWater = isSwimming();
        if (inWater && !wasInWater) splashTicks = 10;
        wasInWater = inWater;
        if (splashTicks > 0) splashTicks--;

        if (jumpTapWindowTicks > 0) jumpTapWindowTicks--;
        if (flightTapWindowTicks > 0) flightTapWindowTicks--;

        if (flying) {
            // Creative flight: jump held rises; releasing it hovers in place.
            if (jumpHeld) {
                float nextFlightY = cameraY + 0.20f;
                if (canOccupyAt(cameraX, nextFlightY - PLAYER_EYE_HEIGHT, cameraZ))
                    cameraY = nextFlightY;
            }
            verticalVelocity = 0.0f;
            airborne = true;
            changed = true;
        } else if (inWater) {
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
            float nextCameraY = cameraY + verticalVelocity;
            if (canOccupyAt(cameraX, nextCameraY - PLAYER_EYE_HEIGHT, cameraZ)) cameraY = nextCameraY;
            else { verticalVelocity = 0.0f; }

            // Water never disables solid-ground collision. Keep the player's
            // feet above the terrain even while buoyancy/gravity is active.
            if (cameraY < groundY) {
                cameraY = groundY;
                if (verticalVelocity < 0.0f) verticalVelocity = 0.0f;
            }
            changed = true;
        } else if (airborne) {
            float nextCameraY = cameraY + verticalVelocity;
            if (canOccupyAt(cameraX, nextCameraY - PLAYER_EYE_HEIGHT, cameraZ)) cameraY = nextCameraY;
            else { verticalVelocity = 0.0f; }
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
        return canOccupyAt(x, cameraY - PLAYER_EYE_HEIGHT, z);
    }

    /** Minecraft-sized player AABB tested against every voxel it overlaps. */
    private boolean canOccupyAt(float x, float feetY, float z) {
        if (x < 1.0f || z < 1.0f || x > 126.0f || z > 126.0f) return false;

        float headY = feetY + PLAYER_HEIGHT;
        int minX = floorVoxel(x - PLAYER_HALF_WIDTH + 0.001f);
        int maxX = floorVoxel(x + PLAYER_HALF_WIDTH - 0.001f);
        int minZ = floorVoxel(z - PLAYER_HALF_WIDTH + 0.001f);
        int maxZ = floorVoxel(z + PLAYER_HALF_WIDTH - 0.001f);
        int firstBodyY = Math.max(0, (int) Math.floor(feetY + 0.02f));
        int lastBodyY = Math.min(TerrainGenerator.HEIGHT - 1,
            (int) Math.floor(headY - 0.02f));

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                // Test actual occupied voxels instead of the original column
                // height. A mined tunnel must become empty for player collision.
                for (int by = firstBodyY; by <= lastBodyY; by++) {
                    if (terrain.getBlockTypeAt(bx, by, bz) >= 0) return false;
                    if (terrain.isLeafBlockAt(bx, by, bz)) return false;
                }

                // Tree trunks are generated separately from terrain voxels.
                if (terrain.isTreeAt(bx, bz)) {
                    int treeBase = terrain.getHeight(bx, bz);
                    int treeTop = treeBase + terrain.getTreeHeight(bx, bz);
                    if (headY > treeBase && feetY < treeTop) return false;
                }
            }
        }
        return true;
    }

    /** Finds the highest solid voxel directly below the player's feet. */
    private float getGroundHeightBelow(float x, float z, float feetY) {
        // Resolve support across the player's full 0.6 x 0.6 footprint, not
        // just the center column. This prevents edge sinking and stair clipping.
        float ground = 0.0f;
        ground = Math.max(ground, groundAtColumn(floorVoxel(x - PLAYER_HALF_WIDTH + 0.001f),
            floorVoxel(z - PLAYER_HALF_WIDTH + 0.001f), feetY));
        ground = Math.max(ground, groundAtColumn(floorVoxel(x + PLAYER_HALF_WIDTH - 0.001f),
            floorVoxel(z - PLAYER_HALF_WIDTH + 0.001f), feetY));
        ground = Math.max(ground, groundAtColumn(floorVoxel(x - PLAYER_HALF_WIDTH + 0.001f),
            floorVoxel(z + PLAYER_HALF_WIDTH - 0.001f), feetY));
        ground = Math.max(ground, groundAtColumn(floorVoxel(x + PLAYER_HALF_WIDTH - 0.001f),
            floorVoxel(z + PLAYER_HALF_WIDTH - 0.001f), feetY));
        ground = Math.max(ground, groundAtColumn(floorVoxel(x), floorVoxel(z), feetY));
        return ground;
    }

    private float groundAtColumn(int x, int z, float feetY) {
        if (x < 0 || z < 0 || x >= TerrainGenerator.SIZE || z >= TerrainGenerator.SIZE) return 0.0f;
        int startY = Math.min(floorVoxel(feetY + 0.02f), TerrainGenerator.HEIGHT - 1);
        for (int y = startY; y >= 0; y--) {
            if (terrain.getBlockTypeAt(x, y, z) >= 0) return y + 1.0f;
        }
        return 0.0f;
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
    private synchronized void invalidateEditedArea(int minX, int maxX, int minZ, int maxZ) {
        minX = Math.max(1, minX - 1);
        maxX = Math.min(TerrainGenerator.SIZE - 2, maxX + 1);
        minZ = Math.max(1, minZ - 1);
        maxZ = Math.min(TerrainGenerator.SIZE - 2, maxZ + 1);
        int minChunkX = minX / CHUNK, maxChunkX = maxX / CHUNK;
        int minChunkZ = minZ / CHUNK, maxChunkZ = maxZ / CHUNK;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
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
        // Let the normal game loop rebuild at most one chunk per tick.
        chunksDirty = true;
    }

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
                int surface = terrain.getWaterSurfaceLevel(x, z);
                if (surface <= terrain.getHeight(x, z)) continue;
                quads++;
                if (hasExposedWaterSide(x, z, x - 1, z, surface)) quads++;
                if (hasExposedWaterSide(x, z, x + 1, z, surface)) quads++;
                if (hasExposedWaterSide(x, z, x, z - 1, surface)) quads++;
                if (hasExposedWaterSide(x, z, x, z + 1, surface)) quads++;
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
                int surface = terrain.getWaterSurfaceLevel(x, z);
                if (surface <= terrain.getHeight(x, z)) continue;
                vertex = addWaterQuad(positions, texCoords, stripLengths,
                    vertex, strip++, x, surface, z,
                    x, surface, z + 1, x + 1, surface, z, x + 1, surface, z + 1);
                int base = waterSideBase(x, z, x - 1, z, surface);
                if (base >= 0) vertex = addWaterQuad(positions, texCoords, stripLengths,
                    vertex, strip++, x, base, z + 1, x, surface, z + 1,
                    x, base, z, x, surface, z);
                base = waterSideBase(x, z, x + 1, z, surface);
                if (base >= 0) vertex = addWaterQuad(positions, texCoords, stripLengths,
                    vertex, strip++, x + 1, base, z, x + 1, surface, z,
                    x + 1, base, z + 1, x + 1, surface, z + 1);
                base = waterSideBase(x, z, x, z - 1, surface);
                if (base >= 0) vertex = addWaterQuad(positions, texCoords, stripLengths,
                    vertex, strip++, x, base, z, x, surface, z,
                    x + 1, base, z, x + 1, surface, z);
                base = waterSideBase(x, z, x, z + 1, surface);
                if (base >= 0) vertex = addWaterQuad(positions, texCoords, stripLengths,
                    vertex, strip++, x + 1, base, z + 1, x + 1, surface, z + 1,
                    x, base, z + 1, x, surface, z + 1);
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

    private boolean hasExposedWaterSide(int x, int z, int nx, int nz, int surface) {
        return waterSideBase(x, z, nx, nz, surface) >= 0;
    }

    private int waterSideBase(int x, int z, int nx, int nz, int surface) {
        int neighborSurface = terrain.getWaterSurfaceLevel(nx, nz);
        if (neighborSurface >= surface) return -1;
        int base;
        if (neighborSurface > 0) {
            base = Math.max(terrain.getHeight(x, z), neighborSurface);
        } else {
            base = Math.max(terrain.getHeight(x, z), terrain.getHeight(nx, nz));
        }
        return base < surface ? base : -1;
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

    private Image createItemsIcon(Image items, int tileX, int tileY) {
        int[] pixels = new int[16 * 16];
        items.getRGB(pixels, 0, 16, tileX * 16, tileY * 16, 16, 16);
        return Image.createRGBImage(pixels, 16, 16, true);
    }

    private Image createGuiBlockInventoryIcon(int block, Image terrain) {
        // Match each inventory item to its own icon. gui_blocks.png contains
        // 48x48 rendered cube icons on a 48px grid; terrain-only decorations
        // and wool variants use the original 16x16 terrain atlas instead.
        int sx = 0, sy = 0;
        switch (block) {
            case 0:  sx = 432; sy = 0; break;    // grass block
            case 1:  sx = 384; sy = 0; break;    // dirt
            case 2:  sx = 0;   sy = 0; break;    // stone
            case 3:  sx = 288; sy = 48; break;   // oak log
            case 4:  sx = 192; sy = 0; break;    // leaves
            case 5:  sx = 336; sy = 144; break;  // coal ore
            case 6:  sx = 384; sy = 144; break;  // iron ore
            case 7:  sx = 432; sy = 144; break;  // gold ore
            case 8:  sx = 0;   sy = 192; break;  // diamond ore
            case 9:  return createTerrainBlockIcon(terrain, BLOCK_TILE_X[block], BLOCK_TILE_Y[block]); // bedrock
            case 10: sx = 288; sy = 336; break;  // TNT
            case 11: sx = 240; sy = 0; break;    // oak planks
            case 12: sx = 48;  sy = 48; break;   // sand
            case 13: sx = 240; sy = 48; break;   // gravel
            case 14: sx = 288; sy = 0; break;    // bricks
            case 15: sx = 336; sy = 192; break;  // obsidian
            case 16: return createTerrainBlockIcon(terrain, BLOCK_TILE_X[block], BLOCK_TILE_Y[block]);
            case 17: return createTerrainBlockIcon(terrain, BLOCK_TILE_X[block], BLOCK_TILE_Y[block]);
            case 18: return createTerrainBlockIcon(terrain, BLOCK_TILE_X[block], BLOCK_TILE_Y[block]);
            case 19: return createTerrainBlockIcon(terrain, BLOCK_TILE_X[block], BLOCK_TILE_Y[block]);
            case 21: sx = 192; sy = 144; break;  // iron block
            case 22: sx = 144; sy = 192; break;  // gold block
            case 23: sx = 240; sy = 192; break;  // diamond block
            default:
                if (block >= 24 && block < 40) {
                    // Wool cubes in gui_blocks.png, mapped in classic dye order:
                    // white, orange, magenta, light blue, yellow, lime, pink,
                    // gray, light gray, cyan, purple, blue, brown, green, red, black.
                    final int[] woolX = {96, 432, 384, 336, 288, 240, 192, 144,
                                         336, 288, 240, 192, 144, 96, 48, 0};
                    final int[] woolY = {240, 240, 240, 240, 240, 240, 240, 240,
                                         288, 288, 288, 288, 288, 288, 288, 288};
                    return createGuiBlockIcon(woolX[block - 24], woolY[block - 24]);
                }
                return createTerrainBlockIcon(terrain, BLOCK_TILE_X[block], BLOCK_TILE_Y[block]);
        }
        return createGuiBlockIcon(sx, sy);
    }

    private Image createGuiBlockIcon(int sourceX, int sourceY) {
        // Some gui_blocks.png atlas coordinates point beyond the 512x512
        // sheet's right edge. Clamp the crop to a complete 48x48 cell before
        // getRGB(), which otherwise throws IllegalArgumentException on phones.
        int sheetWidth = guiBlocksImage.getWidth();
        int sheetHeight = guiBlocksImage.getHeight();
        if (sheetWidth < 1 || sheetHeight < 1) {
            throw new IllegalArgumentException("gui_blocks.png has invalid dimensions");
        }
        if (sourceX < 0) sourceX = 0;
        if (sourceY < 0) sourceY = 0;
        if (sourceX + 48 > sheetWidth) sourceX = Math.max(0, sheetWidth - 48);
        if (sourceY + 48 > sheetHeight) sourceY = Math.max(0, sheetHeight - 48);

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


    private void spawnDroppedBlock(int type, float x, float y, float z) {
        if (type < 0 || type >= blockIcons.length || type == TerrainGenerator.BLOCK_BEDROCK) return;
        spawnDroppedItem(type, blockIcons[type], x, y, z);
    }

    private void spawnDroppedItem(int type, Image icon, float x, float y, float z) {
        if (droppedItemGroup == null || icon == null) return;
        int slot = -1;
        for (int i = 0; i < MAX_DROPPED_ITEMS; i++) {
            if (droppedMeshes[i] == null) { slot = i; break; }
        }
        if (slot < 0) {
            slot = 0;
            for (int i = 1; i < MAX_DROPPED_ITEMS; i++)
                if (droppedAge[i] > droppedAge[slot]) slot = i;
            droppedItemGroup.removeChild(droppedMeshes[slot]);
            droppedMeshes[slot] = null;
        }
        try {
            Mesh mesh = type >= 0 ? createDroppedBlockMesh(type) : createDroppedItemMesh(icon);
            droppedMeshes[slot] = mesh;
            droppedTypes[slot] = type;
            droppedX[slot] = x;
            droppedY[slot] = y;
            droppedZ[slot] = z;
            droppedAge[slot] = 0;
            mesh.setTranslation(x, y, z);
            droppedItemGroup.addChild(mesh);
        } catch (Exception ignored) { }
    }


    /**
     * Dropped blocks use the terrain atlas on each face, not the GUI inventory
     * cube icon. Grass and logs therefore retain their different face textures.
     */
    private Mesh createDroppedBlockMesh(int type) {
        if (type < 0 || type >= BLOCK_TILE_X.length || type >= BLOCK_TILE_Y.length
                || type >= blockIcons.length) return createDroppedItemMesh(blockIcons[0]);
        int topX = BLOCK_TILE_X[type], topY = BLOCK_TILE_Y[type];
        int sideX = topX, sideY = topY, bottomX = topX, bottomY = topY;
        if (type == TerrainGenerator.BLOCK_GRASS) {
            topX = 0; topY = 0;
            sideX = 3; sideY = 0;
            bottomX = 2; bottomY = 0;
        } else if (type == TerrainGenerator.BLOCK_WOOD) {
            topX = 5; topY = 1;
            sideX = 4; sideY = 1;
            bottomX = 5; bottomY = 1;
        } else if (type == TerrainGenerator.BLOCK_STONE) {
            topX = 1; topY = 0;
            sideX = 1; sideY = 0;
            bottomX = 1; bottomY = 0;
        } else if (type == TerrainGenerator.BLOCK_TNT) {
            // Classic terrain atlas: TNT has a distinct top label tile.
            topX = 9; topY = 0;
            sideX = 8; sideY = 0;
            bottomX = 9; bottomY = 0;
        }
        short[] p = {
            -3,-3,-3, -3,3,-3, 3,-3,-3, 3,3,-3,
            3,-3,3, 3,3,3, -3,-3,3, -3,3,3,
            -3,-3,3, -3,3,3, -3,-3,-3, -3,3,-3,
            3,-3,-3, 3,3,-3, 3,-3,3, 3,3,3,
            -3,3,-3, -3,3,3, 3,3,-3, 3,3,3,
            -3,-3,3, -3,-3,-3, 3,-3,3, 3,-3,-3
        };
        short[] uv = new short[48];
        Appearance[] faceAppearances = new Appearance[6];
        IndexBuffer[] faceIndices = new IndexBuffer[6];
        int[] tileX = {sideX, sideX, sideX, sideX, topX, bottomX};
        int[] tileY = {sideY, sideY, sideY, sideY, topY, bottomY};
        for (int face = 0; face < 6; face++) {
            int b=face*8, base=face*4;
            boolean horizontal=face>=4, xFace=face==2 || face==3;
            int minX=32767,maxX=-32768,minY=32767,maxY=-32768,minZ=32767,maxZ=-32768;
            for(int k=0;k<4;k++){
                int pi=(base+k)*3,xx=p[pi],yy=p[pi+1],zz=p[pi+2];
                if(xx<minX)minX=xx; if(xx>maxX)maxX=xx;
                if(yy<minY)minY=yy; if(yy>maxY)maxY=yy;
                if(zz<minZ)minZ=zz; if(zz>maxZ)maxZ=zz;
            }
            for(int k=0;k<4;k++){
                int pi=(base+k)*3,xx=p[pi],yy=p[pi+1],zz=p[pi+2];
                int uf=(horizontal || !xFace)?(xx==maxX?1:0):(zz==maxZ?1:0);
                int vf=horizontal?(zz==maxZ?1:0):(yy==maxY?1:0);
                boolean ore=(tileX[face]==2 && (tileY[face]==1 || tileY[face]==2 || tileY[face]==3))
                    || (tileX[face]==1 && tileY[face]==2);
                if((type==TerrainGenerator.BLOCK_GRASS && face<4)||ore)uf=1-uf;
                uv[b+k*2]=(short)(uf*1024); uv[b+k*2+1]=(short)(vf*1024);
            }
            int[] faceVertexIndices={base,base+1,base+2,base+3};
            faceIndices[face]=new TriangleStripArray(faceVertexIndices,new int[]{4});
            faceAppearances[face]=getTerrainTileAppearance((tileY[face]<<4)|tileX[face]);
        }
        VertexArray pa = new VertexArray(24, 3, 2);
        pa.set(0, 24, p);
        VertexArray ta = new VertexArray(24, 2, 2);
        ta.set(0, 24, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 16.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 1024.0f, null);
        return new Mesh(vb, faceIndices, faceAppearances);
    }

    private Mesh createDroppedItemMesh(Image icon) {
        Texture2D texture = new Texture2D(new Image2D(Image2D.RGBA, icon));
        texture.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        texture.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        Appearance appearance = new Appearance();
        appearance.setTexture(0, texture);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        pm.setShading(PolygonMode.SHADE_FLAT);
        appearance.setPolygonMode(pm);
        short[] p = {
            -3,-3,-3, -3,3,-3, 3,-3,-3, 3,3,-3,
            3,-3,3, 3,3,3, -3,-3,3, -3,3,3,
            -3,-3,3, -3,3,3, -3,-3,-3, -3,3,-3,
            3,-3,-3, 3,3,-3, 3,-3,3, 3,3,3,
            -3,3,-3, -3,3,3, 3,3,-3, 3,3,3,
            -3,-3,3, -3,-3,-3, 3,-3,3, 3,-3,-3
        };
        short[] uv = new short[48];
        int[] indices = new int[24];
        int[] strips = {4,4,4,4,4,4};
        for (int face = 0; face < 6; face++) {
            int b = face * 8;
            uv[b] = 0; uv[b+1] = 0;
            uv[b+2] = 0; uv[b+3] = 1024;
            uv[b+4] = 1024; uv[b+5] = 0;
            uv[b+6] = 1024; uv[b+7] = 1024;
            for (int c = 0; c < 4; c++) indices[face * 4 + c] = face * 4 + c;
        }
        VertexArray pa = new VertexArray(24, 3, 2);
        pa.set(0, 24, p);
        VertexArray ta = new VertexArray(24, 2, 2);
        ta.set(0, 24, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1.0f / 16.0f, null);
        vb.setTexCoords(0, ta, 1.0f / 1024.0f, null);
        return new Mesh(vb, new TriangleStripArray(indices, strips), appearance);
    }

    private void tickDroppedItems() {
        if (droppedItemGroup == null) return;
        for (int i = 0; i < MAX_DROPPED_ITEMS; i++) {
            Mesh mesh = droppedMeshes[i];
            if (mesh == null) continue;
            droppedAge[i]++;
            float bob = (float)Math.sin(droppedAge[i] * 0.18f) * 0.07f;
            mesh.setOrientation(droppedAge[i] * 9.0f, 0.0f, 1.0f, 0.0f);
            mesh.setTranslation(droppedX[i], droppedY[i] + bob, droppedZ[i]);
            float dx = droppedX[i] - cameraX;
            float dy = droppedY[i] - cameraY;
            float dz = droppedZ[i] - cameraZ;
            if (dx*dx + dy*dy + dz*dz < 1.75f * 1.75f) {
                if (droppedTypes[i] < 0) { droppedItemGroup.removeChild(mesh); droppedMeshes[i]=null; droppedTypes[i]=0; continue; }
                else if (droppedTypes[i] < inventory.length && inventory[droppedTypes[i]] < 64)
                    inventory[droppedTypes[i]]++;
                droppedItemGroup.removeChild(mesh);
                droppedMeshes[i] = null;
            } else if (droppedAge[i] > 250) {
                droppedItemGroup.removeChild(mesh);
                droppedMeshes[i] = null;
            }
        }
    }

    private void tickSurvival() {
        if (worldMode != IndevViewerMIDlet.MODE_SURVIVAL) return;
        if (zombieDamageCooldown > 0) zombieDamageCooldown--;
        if (hostileMobs != null && zombieDamageCooldown == 0
                && hostileMobs.touchesPlayer(cameraX, cameraZ)) {
            health -= 2;
            if (health < 0) health = 0;
            zombieDamageCooldown = 25;
            if (health == 0) {
                health = 10;
                cameraX = 64.0f;
                cameraZ = 105.0f;
                cameraY = terrain.getHeight((int)cameraX, (int)cameraZ) + PLAYER_EYE_HEIGHT + 0.1f;
                verticalVelocity = 0.0f;
                airborne = false;
                updateCamera();
            }
        }
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
        int flowerQuads = 0;
        int x, z, h;
        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                h = terrain.getHeight(x, z);
                int yy;
                for (yy = 0; yy < h; yy++) {
                    if (terrain.isBlockRemoved(x, yy, z)) continue;
                    if (isTerrainFaceExposed(x, yy, z, x - 1, z)) quads++;
                    if (isTerrainFaceExposed(x, yy, z, x + 1, z)) quads++;
                    if (isTerrainFaceExposed(x, yy, z, x, z - 1)) quads++;
                    if (isTerrainFaceExposed(x, yy, z, x, z + 1)) quads++;
                    // A mined voxel exposes the underside of the block above
                    // and the top of the block below. Natural top faces remain
                    // handled by the greedy surface mesh to avoid duplicates.
                    if (yy + 1 < h && terrain.isBlockRemoved(x, yy + 1, z)) quads++;
                    if (yy > 0 && terrain.isBlockRemoved(x, yy - 1, z)) quads++;
                }
                int flowerType = terrain.getFlowerType(x, z);
                if (flowerType >= 0) flowerQuads += 2;
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
        for (int p = 0; p < terrain.getPlacedBlockCount(); p++) {
            int id = terrain.getPlacedBlockIdAtIndex(p);
            int py = id / (TerrainGenerator.SIZE * TerrainGenerator.SIZE);
            int rem = id - py * TerrainGenerator.SIZE * TerrainGenerator.SIZE;
            int pz = rem / TerrainGenerator.SIZE, px = rem - pz * TerrainGenerator.SIZE;
            if (px < startX || px >= startX + CHUNK || pz < startZ || pz >= startZ + CHUNK) continue;
            if (terrain.getBlockTypeAt(px - 1, py, pz) < 0) quads++;
            if (terrain.getBlockTypeAt(px + 1, py, pz) < 0) quads++;
            if (terrain.getBlockTypeAt(px, py - 1, pz) < 0) quads++;
            if (terrain.getBlockTypeAt(px, py + 1, pz) < 0) quads++;
            if (terrain.getBlockTypeAt(px, py, pz - 1) < 0) quads++;
            if (terrain.getBlockTypeAt(px, py, pz + 1) < 0) quads++;
        }
        int vertexCount = quads * 4;
        int flowerVertexCount = flowerQuads * 4;
        short[] flowerPositions = new short[flowerVertexCount * 3];
        short[] flowerTexCoords = new short[flowerVertexCount * 2];
        byte[] flowerColors = new byte[flowerVertexCount * 3];
        int[] flowerStripLengths = new int[flowerQuads];
        short[] positions = new short[vertexCount * 3];
        short[] texCoords = new short[vertexCount * 2];
        byte[] colors = new byte[vertexCount * 3];
        int[] stripLengths = new int[quads];
        int vertex = 0;
        int strip = 0;
        int flowerStrip = 0;

        for (x = startX; x < startX + CHUNK; x++) {
            for (z = startZ; z < startZ + CHUNK; z++) {
                h = terrain.getHeight(x, z);
                int sy;
                for (sy = 0; sy < h; sy++) {
                    if (terrain.isBlockRemoved(x, sy, z)) continue;
                    int layerType = terrain.getBlockTypeAt(x, sy, z);
                    if (isTerrainFaceExposed(x, sy, z, x - 1, z)) {
                        vertex = addQuad(positions, texCoords, colors, stripLengths,
                            vertex, strip++, x, sy, z + 1, x, sy + 1, z + 1,
                            x, sy, z, x, sy + 1, z, sideTile(terrain.getTopType(x, z), layerType, sy, h), sideTileY(terrain.getTopType(x, z), layerType, sy, h));
                    }
                    if (isTerrainFaceExposed(x, sy, z, x + 1, z)) {
                        vertex = addQuad(positions, texCoords, colors, stripLengths,
                            vertex, strip++, x + 1, sy, z, x + 1, sy + 1, z,
                            x + 1, sy, z + 1, x + 1, sy + 1, z + 1, sideTile(terrain.getTopType(x, z), layerType, sy, h), sideTileY(terrain.getTopType(x, z), layerType, sy, h));
                    }
                    if (isTerrainFaceExposed(x, sy, z, x, z - 1)) {
                        vertex = addQuad(positions, texCoords, colors, stripLengths,
                            vertex, strip++, x, sy, z, x, sy + 1, z,
                            x + 1, sy, z, x + 1, sy + 1, z, sideTile(terrain.getTopType(x, z), layerType, sy, h), sideTileY(terrain.getTopType(x, z), layerType, sy, h));
                    }
                    if (isTerrainFaceExposed(x, sy, z, x, z + 1)) {
                        vertex = addQuad(positions, texCoords, colors, stripLengths,
                            vertex, strip++, x + 1, sy, z + 1, x + 1, sy + 1, z + 1,
                            x, sy, z + 1, x, sy + 1, z + 1, sideTile(terrain.getTopType(x, z), layerType, sy, h), sideTileY(terrain.getTopType(x, z), layerType, sy, h));
                    }
                    if (sy + 1 < h && terrain.isBlockRemoved(x, sy + 1, z)) {
                        int topX = BLOCK_TILE_X[layerType], topY = BLOCK_TILE_Y[layerType];
                        vertex = addQuad(positions, texCoords, colors, stripLengths,
                            vertex, strip++, x, sy + 1, z, x, sy + 1, z + 1,
                            x + 1, sy + 1, z, x + 1, sy + 1, z + 1, topX, topY);
                    }
                    if (sy > 0 && terrain.isBlockRemoved(x, sy - 1, z)) {
                        int bottomType = layerType == TerrainGenerator.BLOCK_GRASS
                            ? TerrainGenerator.BLOCK_DIRT : layerType;
                        int bottomX = BLOCK_TILE_X[bottomType], bottomY = BLOCK_TILE_Y[bottomType];
                        vertex = addQuad(positions, texCoords, colors, stripLengths,
                            vertex, strip++, x, sy, z, x + 1, sy, z,
                            x, sy, z + 1, x + 1, sy, z + 1, bottomX, bottomY);
                    }
                }
                int flowerType = terrain.getFlowerType(x, z);
                if (flowerType >= 0) {
                    int fx = BLOCK_TILE_X[flowerType], fy = BLOCK_TILE_Y[flowerType];
                    // Keep crossed plant planes out of opaque terrain geometry:
                    // alpha-tested pixels belong only to the flower itself.
                    int flowerVertex = flowerStrip * 4;
                    flowerVertex = addQuad(flowerPositions, flowerTexCoords, flowerColors,
                        flowerStripLengths, flowerVertex, flowerStrip++, x, h, z,
                        x + 1, h, z + 1, x, h + 1, z, x + 1, h + 1, z + 1, fx, fy);
                    flowerVertex = addQuad(flowerPositions, flowerTexCoords, flowerColors,
                        flowerStripLengths, flowerVertex, flowerStrip++, x + 1, h, z,
                        x, h, z + 1, x + 1, h + 1, z, x, h + 1, z + 1, fx, fy);
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

        for (int p = 0; p < terrain.getPlacedBlockCount(); p++) {
            int id = terrain.getPlacedBlockIdAtIndex(p);
            int py = id / (TerrainGenerator.SIZE * TerrainGenerator.SIZE);
            int rem = id - py * TerrainGenerator.SIZE * TerrainGenerator.SIZE;
            int pz = rem / TerrainGenerator.SIZE, px = rem - pz * TerrainGenerator.SIZE;
            if (px < startX || px >= startX + CHUNK || pz < startZ || pz >= startZ + CHUNK) continue;
            int type = terrain.getPlacedBlockTypeAtIndex(p);
            int tx = BLOCK_TILE_X[type], ty = BLOCK_TILE_Y[type];
            if (terrain.getBlockTypeAt(px - 1, py, pz) < 0)
                vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                    px,py,pz+1, px,py+1,pz+1, px,py,pz, px,py+1,pz, tx,ty);
            if (terrain.getBlockTypeAt(px + 1, py, pz) < 0)
                vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                    px+1,py,pz, px+1,py+1,pz, px+1,py,pz+1, px+1,py+1,pz+1, tx,ty);
            if (terrain.getBlockTypeAt(px, py - 1, pz) < 0)
                vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                    px,py,pz, px+1,py,pz, px,py,pz+1, px+1,py,pz+1, tx,ty);
            if (terrain.getBlockTypeAt(px, py + 1, pz) < 0)
                vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                    px,py+1,pz, px,py+1,pz+1, px+1,py+1,pz, px+1,py+1,pz+1, tx,ty);
            if (terrain.getBlockTypeAt(px, py, pz - 1) < 0)
                vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                    px,py,pz, px,py+1,pz, px+1,py,pz, px+1,py+1,pz, tx,ty);
            if (terrain.getBlockTypeAt(px, py, pz + 1) < 0)
                vertex = addQuad(positions, texCoords, colors, stripLengths, vertex, strip++,
                    px+1,py,pz+1, px+1,py+1,pz+1, px,py,pz+1, px,py+1,pz+1, tx,ty);
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
        if (flowerVertexCount > 0) {
            VertexArray flowerPositionArray = new VertexArray(flowerVertexCount, 3, 2);
            flowerPositionArray.set(0, flowerVertexCount, flowerPositions);
            VertexArray flowerTexArray = new VertexArray(flowerVertexCount, 2, 2);
            flowerTexArray.set(0, flowerVertexCount, flowerTexCoords);
            VertexArray flowerColorArray = new VertexArray(flowerVertexCount, 3, 1);
            flowerColorArray.set(0, flowerVertexCount, flowerColors);
            VertexBuffer flowerBuffer = new VertexBuffer();
            flowerBuffer.setPositions(flowerPositionArray, 1.0f, null);
            flowerBuffer.setTexCoords(0, flowerTexArray, 1.0f / 256.0f, null);
            flowerBuffer.setColors(flowerColorArray);
            chunkGroup.addChild(new Mesh(flowerBuffer,
                new TriangleStripArray(0, flowerStripLengths), flowerAppearance));
        }
        buildGreedyTopMeshes(chunkGroup, chunkX, chunkZ);
        return chunkGroup;
    }



    /** True if a solid terrain voxel has an exposed horizontal face toward its neighbor. */
    private boolean isTerrainFaceExposed(int x, int y, int z, int nx, int nz) {
        if (terrain.isBlockRemoved(x, y, z)) return false;
        int neighborHeight = terrain.getHeight(nx, nz);
        if (y >= neighborHeight) return true;
        return terrain.isBlockRemoved(nx, y, nz);
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
            int type=terrain.getTopType(wx,wz);
            // Never emit a greedy surface quad for a voxel recorded as removed.
            // This is the source of the lingering "ghost top" after side mining
            // or loading older edited-world data.
            if (h <= 0 || terrain.isBlockRemoved(wx, h - 1, wz) || terrain.getPlacedBlockTypeAt(wx, h, wz) >= 0) {
                keys[x][z] = -1; hs[x][z] = h;
                bases[x][z] = 0; glows[x][z] = 0;
                continue;
            }
            int tx=BLOCK_TILE_X[type], ty=BLOCK_TILE_Y[type];
            // The top of a TNT block is not its side texture, even before ignition.
            if(type==TerrainGenerator.BLOCK_TNT){tx=9;ty=0;}
            if(type==TerrainGenerator.BLOCK_TNT && tntFuseTicks>0 && wx==tntX && wz==tntZ && tntFlashOn){tx=9;ty=0;}
            keys[x][z]=ty*16+tx; hs[x][z]=h;
            bases[x][z]=IndevViewerMIDlet.humbleMode ? 255 : terrain.getShadowShade(wx,wz,h);
            glows[x][z]=IndevViewerMIDlet.humbleMode ? 0 : torchGlowAt(wx,h,wz);
        }
        for(x=0;x<CHUNK;x++) for(z=0;z<CHUNK;z++) {
            if(used[x][z] || keys[x][z] < 0) continue;
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
        // Leaf cubes are opaque for simpler rendering and fewer alpha artifacts.
        if (tx == 4 && ty == 3) {
            for (int p = 0; p < pixels.length; p++) pixels[p] |= 0xFF000000;
        }
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
        // Stone and ore blocks use the stone/ore face tiles from textures.png,
        // not the top-face coordinates of the GUI cube-icon atlas.
        if (layerType == TerrainGenerator.BLOCK_STONE) return 1;
        return BLOCK_TILE_X[layerType];
    }

    private int sideTileY(int topType, int layerType, int y, int height) {
        if (topType == TerrainGenerator.BLOCK_GRASS && y == height - 1) return 0;
        if (layerType == TerrainGenerator.BLOCK_WOOD) return 1;
        if (layerType < 0 || layerType >= BLOCK_TILE_Y.length) layerType = TerrainGenerator.BLOCK_STONE;
        if (layerType == TerrainGenerator.BLOCK_STONE) return 0;
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
        // Cull every face shared by adjacent tree voxels, including leaves.
        // Alpha-test handles transparent pixels inside an exposed leaf face;
        // it must not force internal lateral/underside faces to be rendered.
        if (!isTreeVoxelAt(x, y - 1, z)) faces++;
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
        if (!isTreeVoxelAt(x, y - 1, z)) {
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
        // Minecraft-style debris uses a single flat, camera-facing sprite.
        // It falls and slides, but never spins like a miniature cube.
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
            // Fragments begin at different points inside the mined voxel and
            // burst outward with a little random variation, like vanilla.
            float dx = particleRandom.nextFloat() - 0.5f;
            float dy = particleRandom.nextFloat() - 0.5f;
            float dz = particleRandom.nextFloat() - 0.5f;
            float directionLength = (float) Math.sqrt(dx * dx + dz * dz);
            if (directionLength < 0.001f) {
                dx = 1.0f;
                dz = 0.0f;
                directionLength = 1.0f;
            }
            float horizontalSpeed = 0.025f + particleRandom.nextFloat() * 0.065f;
            blockParticleX[i] = x + dx * 0.72f;
            blockParticleY[i] = y + dy * 0.72f;
            blockParticleZ[i] = z + dz * 0.72f;
            blockParticleVX[i] = (dx / directionLength) * horizontalSpeed;
            blockParticleVZ[i] = (dz / directionLength) * horizontalSpeed;
            blockParticleVY[i] = 0.015f + particleRandom.nextFloat() * 0.085f;
            blockParticleSize[i] = 0.045f + particleRandom.nextFloat() * 0.035f;
            blockParticleMeshes[i].setAppearance(0, blockParticleAppearances[type]);
            blockParticleMeshes[i].setScale(blockParticleSize[i], blockParticleSize[i],
                                             blockParticleSize[i]);
            blockParticleMeshes[i].setOrientation(0.0f, 0.0f, 1.0f, 0.0f);
            blockParticleMeshes[i].setTranslation(blockParticleX[i], blockParticleY[i], blockParticleZ[i]);
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
            blockParticleMeshes[i].setTranslation(blockParticleX[i], blockParticleY[i], blockParticleZ[i]);
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
            blockParticleMeshes[i].setOrientation(0.0f, 0.0f, 1.0f, 0.0f);
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
            float previousY = blockParticleY[i];
            blockParticleX[i] += blockParticleVX[i];
            blockParticleY[i] += blockParticleVY[i];
            blockParticleZ[i] += blockParticleVZ[i];
            // Gravity, air drag and a tiny amount of spin. Do not use getHeight:
            // that only describes the old heightmap surface and breaks particles
            // inside caves, mined holes, and edited voxel columns.
            blockParticleVY[i] -= 0.008f;
            blockParticleVX[i] *= 0.985f;
            blockParticleVZ[i] *= 0.985f;

            int px = (int) Math.floor(blockParticleX[i]);
            int pz = (int) Math.floor(blockParticleZ[i]);
            int belowY = (int) Math.floor(blockParticleY[i]);
            if (blockParticleVY[i] < 0.0f && previousY >= belowY + 1.0f
                    && terrain.getBlockTypeAt(px, belowY, pz) >= 0) {
                // Land on the actual solid voxel, including exposed blocks below
                // a mined gap. A short bounce is closer to the original game.
                blockParticleY[i] = belowY + 1.015f;
                blockParticleVY[i] = -blockParticleVY[i] * 0.22f;
                blockParticleVX[i] *= 0.62f;
                blockParticleVZ[i] *= 0.62f;
                if (blockParticleVY[i] < 0.012f) blockParticleVY[i] = 0.0f;
            }
            blockParticleMeshes[i].setTranslation(blockParticleX[i],
                blockParticleY[i], blockParticleZ[i]);
            // Keep each sprite's orientation fixed throughout its fall.
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
            // Only the TNT's chunk needs new UVs for the flashing tile.
            invalidateEditedArea(tntX, tntX, tntZ, tntZ);
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
        invalidateEditedArea(cx - radius, cx + radius, cz - radius, cz + radius);
        // Terrain edits must not snap the player to a column's surface height.
        // Normal movement resolves support using the voxel-aware ground query.
    }

    private void breakBlock() {
        int decorationHit = raycastDecoration();
        if (decorationHit >= 0) {
            int type = decorationTypes[decorationHit];
            lastMiningDelay = miningDelay(type);
            int removedDecorationX = (int)decorationX[decorationHit];
            int removedDecorationZ = (int)decorationZ[decorationHit];
            if (worldMode == IndevViewerMIDlet.MODE_CREATIVE) {
                if (inventory[type] < 64) inventory[type]++;
            } else {
                spawnDroppedBlock(type, decorationX[decorationHit], decorationY[decorationHit] + 0.35f, decorationZ[decorationHit]);
            }
            spawnBlockParticles(type, decorationX[decorationHit], decorationY[decorationHit] + 0.35f,
                decorationZ[decorationHit]);
            if (decorationTypes[decorationHit] == BLOCK_DOOR && decorationPivots[decorationHit] != null)
                decorationGroup.removeChild(decorationPivots[decorationHit]);
            else decorationGroup.removeChild(decorationMeshes[decorationHit]);
            int last = decorationCount - 1;
            if (decorationHit != last) {
                decorationMeshes[decorationHit] = decorationMeshes[last];
                decorationPivots[decorationHit] = decorationPivots[last];
                decorationTypes[decorationHit] = decorationTypes[last];
                decorationX[decorationHit] = decorationX[last];
                decorationY[decorationHit] = decorationY[last];
                decorationZ[decorationHit] = decorationZ[last];
                decorationYaw[decorationHit] = decorationYaw[last];
                decorationOpen[decorationHit] = decorationOpen[last];
            }
            decorationMeshes[last] = null;
            decorationPivots[last] = null;
            decorationOpen[last] = false;
            decorationCount--;
            updateTorchLights();
            if (type == BLOCK_TORCH) {
                invalidateEditedArea(removedDecorationX, removedDecorationX,
                    removedDecorationZ, removedDecorationZ);
            }
            return;
        }

        int[] leafHit = raycastLeafBlock();
        if (leafHit != null && terrain.removeLeafBlock(leafHit[0], leafHit[1], leafHit[2])) {
            lastMiningDelay = miningDelay(4);
            if (worldMode == IndevViewerMIDlet.MODE_SURVIVAL)
                spawnDroppedBlock(4, leafHit[0] + 0.5f, leafHit[1] + 0.5f, leafHit[2] + 0.5f);
            spawnBlockParticles(4, leafHit[0] + 0.5f, leafHit[1] + 0.5f,
                leafHit[2] + 0.5f);
            invalidateEditedArea(leafHit[0], leafHit[0], leafHit[2], leafHit[2]);
            updateCamera();
            return;
        }

        int[] placedHit = raycastSurface();
        if (placedHit != null) {
            int placedType = terrain.getPlacedBlockTypeAt(placedHit[0], placedHit[2], placedHit[1]);
            if (placedType >= 0 && terrain.removePlacedBlockAt(placedHit[0], placedHit[2], placedHit[1])) {
                lastMiningDelay = miningDelay(placedType);
                if (worldMode == IndevViewerMIDlet.MODE_CREATIVE && placedType < inventory.length && inventory[placedType] < 64) inventory[placedType]++;
                else if (worldMode == IndevViewerMIDlet.MODE_SURVIVAL) spawnDroppedBlock(placedType, placedHit[0] + 0.5f, placedHit[2] + 0.5f, placedHit[1] + 0.5f);
                spawnBlockParticles(placedType, placedHit[0] + 0.5f, placedHit[2] + 0.5f, placedHit[1] + 0.5f);
                invalidateEditedArea(placedHit[0], placedHit[0], placedHit[1], placedHit[1]);
                return;
            }
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
        lastMiningDelay = miningDelay(type);
        if (type == TerrainGenerator.BLOCK_TNT) {
            if (tntFuseTicks == 0) {
                tntX=x; tntZ=z; tntFuseTicks=125; tntFlashCounter=0; tntFlashOn=true;
                invalidateEditedArea(x, x, z, z);
            }
            return;
        }
        if (worldMode == IndevViewerMIDlet.MODE_CREATIVE && type >= 0 && type < inventory.length && type != TerrainGenerator.BLOCK_BEDROCK && inventory[type] < 64) inventory[type]++;
        else if (worldMode == IndevViewerMIDlet.MODE_SURVIVAL) spawnDroppedBlock(type, x + 0.5f, blockY + 0.5f, z + 0.5f);
        spawnBlockParticles(type, x + 0.5f, blockY + 0.5f, z + 0.5f);

        if (blockY == h - 1) {
            // Surface mining reveals the next layer at the top.
            int nextType = terrain.getSubsurfaceType(x, h - 2, z);
            terrain.setHeight(x, z, h - 1);
            terrain.setTopType(x, z, nextType);
        } else {
            // Side mining removes only the targeted XYZ voxel.
            if (!terrain.removeBlockAt(x, blockY, z)) return;
        }
        invalidateEditedArea(x, x, z, z);
        // Mining changes terrain only; do not snap cameraY to getHeight().
        // That shortcut can lift the player several blocks when mining a side
        // face or while the jump arc is still active.
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
        Mesh mesh;
        if (type == BLOCK_DOOR) {
            // Keep door placement from silently failing if a handset's M3G
            // implementation rejects the multi-strip custom mesh. Fall back
            // to the already-supported decoration quad, scaled to door height.
            try {
                mesh = createDoorMesh(decorationAppearances[variant]);
            } catch (RuntimeException meshError) {
                mesh = new Mesh(decorationBuffers[variant], decorationTriangles,
                    decorationAppearances[variant]);
                mesh.setScale(0.48f, 1.0f, 1.0f);
            }
        } else {
            mesh = new Mesh(decorationBuffers[variant], decorationTriangles,
                decorationAppearances[variant]);
        }
        if (type == BLOCK_TORCH) mesh.setScale(0.18f, 0.58f, 1.0f);
        else if (type == BLOCK_SAPLING) mesh.setScale(0.48f, 0.62f, 1.0f);
        else if (type != BLOCK_DOOR) mesh.setScale(0.38f, 0.48f, 1.0f);
        Group pivot = null;
        float placedYaw = yaw;
        if (type == BLOCK_DOOR) {
            placedYaw = ((int) Math.floor((yaw + 45.0f) / 90.0f)) * 90.0f;
            while (placedYaw >= 360.0f) placedYaw -= 360.0f;
            while (placedYaw < 0.0f) placedYaw += 360.0f;
            pivot = new Group();
            pivot.setOrientation(placedYaw, 0.0f, 1.0f, 0.0f);
            float hingeX = x - 0.5f, hingeZ = z - 0.5f;
            if (placedYaw == 90.0f) hingeZ += 1.0f;
            else if (placedYaw == 180.0f) { hingeX += 1.0f; hingeZ += 1.0f; }
            else if (placedYaw == 270.0f) hingeX += 1.0f;
            pivot.setTranslation(hingeX, y, hingeZ);
            mesh.setTranslation(0.0f, 0.0f, 0.0f);
            pivot.addChild(mesh);
            decorationGroup.addChild(pivot);
        } else {
            mesh.setOrientation(yaw, 0.0f, 1.0f, 0.0f);
            mesh.setTranslation(x, y, z);
            decorationGroup.addChild(mesh);
        }
        int at = decorationCount++;
        decorationMeshes[at] = mesh;
        decorationPivots[at] = pivot;
        decorationTypes[at] = type;
        decorationX[at] = x;
        decorationY[at] = y;
        decorationZ[at] = z;
        decorationYaw[at] = (type == BLOCK_DOOR) ? placedYaw : yaw;
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
        Group pivot = decorationPivots[index];
        if (pivot != null) pivot.setOrientation(angle, 0.0f, 1.0f, 0.0f);
    }

    private int raycastDecoration() {
        double yr = Math.PI * pitch / 180.0;
        double xr = Math.PI * yaw / 180.0;
        float dx = (float)(Math.sin(xr) * Math.cos(yr));
        float dz = (float)(-Math.cos(xr) * Math.cos(yr));
        float dy = (float)Math.sin(yr);
        for (float d = 0.0f; d <= BLOCK_REACH; d += 0.025f) {
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


    private void tickMining() {
        if (worldMode != IndevViewerMIDlet.MODE_SURVIVAL || !breakHeld || actionCooldown > 0) return;
        int delay = getMiningDelayForTarget();
        if (delay >= 999) {
            miningProgress = 0;
            return;
        }
        if (++miningProgress >= delay) {
            breakBlock();
            miningProgress = 0;
            actionCooldown = 4;
        }
    }

    private int getMiningDelayForTarget() {
        int decoration = raycastDecoration();
        if (decoration >= 0) return miningDelay(decorationTypes[decoration]);
        int[] leaf = raycastLeafBlock();
        if (leaf != null) return miningDelay(TerrainGenerator.BLOCK_LEAVES);
        int[] hit = raycastSurface();
        if (hit == null) return 5;
        int placed = terrain.getPlacedBlockTypeAt(hit[0], hit[2], hit[1]);
        if (placed >= 0) return miningDelay(placed);
        int x = hit[0], z = hit[1], y = hit[2];
        int type = hit[3] != 0 ? terrain.getBlockTypeAt(x, y, z) : terrain.getTopType(x, z);
        return miningDelay(type);
    }

    /**
     * Approximate classic Minecraft Indev bare-hand mining time.
     * The game loop runs much faster than a Minecraft game tick on some
     * Java ME devices, so use deliberate frame counts rather than tiny
     * single-digit delays. There are no mining tools yet, so stone and ores
     * take substantially longer than wood, dirt, sand or gravel.
     */
    private int miningDelay(int type) {
        if (worldMode == IndevViewerMIDlet.MODE_CREATIVE) return 1;
        if (type == TerrainGenerator.BLOCK_BEDROCK) return 999;
        if (type == TerrainGenerator.BLOCK_OBSIDIAN) return 240;
        if (type == TerrainGenerator.BLOCK_STONE || type == TerrainGenerator.BLOCK_COAL_ORE
                || type == TerrainGenerator.BLOCK_IRON_ORE || type == TerrainGenerator.BLOCK_GOLD_ORE
                || type == TerrainGenerator.BLOCK_DIAMOND_ORE) return 150;
        if (type == TerrainGenerator.BLOCK_WOOD) return 60;
        if (type == TerrainGenerator.BLOCK_PLANKS) return 45;
        if (type == TerrainGenerator.BLOCK_BRICK) return 90;
        if (type == TerrainGenerator.BLOCK_DIRT || type == TerrainGenerator.BLOCK_SAND
                || type == TerrainGenerator.BLOCK_GRAVEL) return 15;
        if (type == TerrainGenerator.BLOCK_LEAVES) return 8;
        if (type == TerrainGenerator.BLOCK_GRASS) return 18;
        return 20;
    }

    private void placeBlock() {
        int decorationHit = raycastDecoration();
        if (decorationHit >= 0 && decorationTypes[decorationHit] == BLOCK_DOOR) {
            toggleDoor(decorationHit);
            return;
        }
        if (selectedSlot < 0 || selectedSlot >= inventory.length || inventory[selectedSlot] <= 0) return;
        if (selectedSlot == ITEM_EMPTY_BUCKET) { collectWaterBucket(); return; }
        int[] hit = raycastSurface();
        if (hit == null) return;
        int x = hit[5], y = hit[6], z = hit[7];
        if (x < 1 || z < 1 || x >= TerrainGenerator.SIZE - 1 ||
                z >= TerrainGenerator.SIZE - 1 || y < 0 || y >= TerrainGenerator.HEIGHT) return;
        if (selectedSlot == ITEM_WATER_BUCKET) { placeWaterBucket(x, z, y); return; }
        if (selectedSlot >= BLOCK_TORCH && selectedSlot <= BLOCK_DOOR) {
            if (decorationCount >= MAX_DECORATIONS) return;
            int decorationHeight = selectedSlot == BLOCK_DOOR ? 2 : 1;
            if (!isPlacementSpaceClear(x, y, z, decorationHeight)) return;
            if (placeDecoration(selectedSlot, x + 0.5f, y, z + 0.5f)) {
                inventory[selectedSlot]--;
                updateTorchLights();
                if (selectedSlot == BLOCK_TORCH) invalidateEditedArea(x, x, z, z);
            }
            return;
        }
        if (selectedSlot > TerrainGenerator.BLOCK_WOOL_BLACK) return;
        if (!isPlacementSpaceClear(x, y, z, 1)) return;
        if (terrain.setPlacedBlockAt(x, y, z, selectedSlot)) {
            inventory[selectedSlot]--;
            invalidateEditedArea(x, x, z, z);
        }
    }

    /**
     * Prevent placing blocks or decorations inside existing terrain, leaves,
     * tree trunks, decorations, or the player's occupied space.
     */
    private boolean isPlacementSpaceClear(int x, int y, int z, int height) {
        if (x < 1 || z < 1 || x >= TerrainGenerator.SIZE - 1 ||
            z >= TerrainGenerator.SIZE - 1 || y < 0 ||
            y + height >= TerrainGenerator.HEIGHT) return false;
        float feetY = cameraY - PLAYER_EYE_HEIGHT;
        float headY = feetY + PLAYER_HEIGHT;
        for (int by = y; by < y + height; by++) {
            if (terrain.getBlockTypeAt(x, by, z) >= 0 ||
                terrain.isLeafBlockAt(x, by, z)) return false;
            if (terrain.isTreeAt(x, z)) {
                int treeBase = terrain.getHeight(x, z);
                int treeTop = treeBase + terrain.getTreeHeight(x, z);
                if (by >= treeBase && by < treeTop) return false;
            }
            for (int d = 0; d < decorationCount; d++) {
                if (Math.abs(decorationX[d] - (x + 0.5f)) < 0.48f &&
                    Math.abs(decorationZ[d] - (z + 0.5f)) < 0.48f &&
                    by >= decorationY[d] &&
                    by < decorationY[d] + (decorationTypes[d] == BLOCK_DOOR ? 2 : 1))
                    return false;
            }
            if (Math.abs(cameraX - (x + 0.5f)) < PLAYER_HALF_WIDTH &&
                Math.abs(cameraZ - (z + 0.5f)) < PLAYER_HALF_WIDTH &&
                by + 1.0f > feetY && by < headY) return false;
        }
        return true;
    }

    private void placeWaterBucket(int x, int z, int targetY) {
        int level = targetY + 1;
        if (level >= TerrainGenerator.HEIGHT - 1 || terrain.isWaterAt(x, z) || terrain.getHeight(x, z) > targetY) return;
        if (isWaterFlowBlocked(x, z, level)) return;
        int free = -1;
        for (int i = 0; i < MAX_WATER_SOURCES; i++) {
            if (!waterFlowActive[i]) { free = i; break; }
        }
        if (free < 0) return;
        terrain.setPlacedWaterLevel(x, z, level);
        inventory[ITEM_WATER_BUCKET]--;
        inventory[ITEM_EMPTY_BUCKET]++;
        waterFlowX[free] = x;
        waterFlowZ[free] = z;
        waterFlowLevel[free] = level;
        waterFlowTicks[free] = 0;
        waterFlowActive[free] = true;
        invalidateEditedArea(x, x, z, z);
    }

    private void collectWaterBucket() {
        int waterX = -1, waterZ = -1, waterLevel = -1;
        double yr = Math.PI * pitch / 180.0;
        double xr = Math.PI * yaw / 180.0;
        float dx = (float) (Math.sin(xr) * Math.cos(yr));
        float dz = (float) (-Math.cos(xr) * Math.cos(yr));
        float dy = (float) Math.sin(yr);
        float waterY = cameraY;
        for (float distance = 0.0f; distance <= BLOCK_REACH; distance += 0.025f) {
            float fx = cameraX + dx * distance;
            waterY = cameraY + dy * distance;
            float fz = cameraZ + dz * distance;
            int x = (int) fx, z = (int) fz;
            int level = terrain.getPlacedWaterLevel(x, z);
            if (level > 0 && waterY <= level && waterY >= terrain.getHeight(x, z)) {
                waterX = x; waterZ = z; waterLevel = level; break;
            }
        }
        if (waterX < 0) return;
        // Prefer the original source position when the flow is still active.
        for (int i = 0; i < MAX_WATER_SOURCES; i++) {
            if (waterFlowLevel[i] == waterLevel
                    && Math.abs(waterFlowX[i] - waterX) + Math.abs(waterFlowZ[i] - waterZ) <= 2) {
                waterX = waterFlowX[i]; waterZ = waterFlowZ[i]; break;
            }
        }
        // Remove the small 5x5-diamond puddle belonging to this source, not
        // natural lakes. Keep this bounded for predictable memory/performance.
        for (int dxCell = -2; dxCell <= 2; dxCell++) {
            for (int dzCell = -2; dzCell <= 2; dzCell++) {
                if (Math.abs(dxCell) + Math.abs(dzCell) > 2) continue;
                int x = waterX + dxCell, z = waterZ + dzCell;
                if (terrain.getPlacedWaterLevel(x, z) == waterLevel) {
                    terrain.setPlacedWaterLevel(x, z, 0);
                    invalidateEditedArea(x, x, z, z);
                }
            }
        }
        for (int i = 0; i < MAX_WATER_SOURCES; i++) {
            if (waterFlowActive[i] && Math.abs(waterFlowX[i] - waterX) + Math.abs(waterFlowZ[i] - waterZ) <= 2)
                waterFlowActive[i] = false;
        }
        inventory[ITEM_EMPTY_BUCKET]--;
        inventory[ITEM_WATER_BUCKET]++;
    }

    private boolean isWaterFlowBlocked(int x, int z, int level) {
        if (x < 1 || z < 1 || x >= TerrainGenerator.SIZE - 1 || z >= TerrainGenerator.SIZE - 1)
            return true;
        if (terrain.getHeight(x, z) >= level) return true;
        if (terrain.isTreeAt(x, z) && level <= terrain.getHeight(x, z) + terrain.getTreeHeight(x, z))
            return true;
        if (terrain.isLeafBlockAt(x, level, z)) return true;
        for (int i = 0; i < decorationCount; i++) {
            if ((int) decorationX[i] == x && (int) decorationZ[i] == z
                    && decorationTypes[i] != BLOCK_TORCH) return true;
        }
        return false;
    }

    private void tickWaterFlow() {
        if (++waterFlowClock < WATER_FLOW_INTERVAL) return;
        waterFlowClock = 0;
        for (int i = 0; i < MAX_WATER_SOURCES; i++) {
            if (!waterFlowActive[i]) continue;
            int sx = waterFlowX[i], sz = waterFlowZ[i], level = waterFlowLevel[i];
            boolean spread = false;
            for (int distance = 1; distance <= 2 && !spread; distance++) {
                for (int dx = -distance; dx <= distance && !spread; dx++) {
                    int remaining = distance - Math.abs(dx);
                    for (int dz = -remaining; dz <= remaining; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) != distance) continue;
                        int x = sx + dx, z = sz + dz;
                        if (terrain.getWaterSurfaceLevel(x, z) > 0) continue;
                        if (isWaterFlowBlocked(x, z, level)) continue;
                        boolean connected = terrain.getWaterSurfaceLevel(x - 1, z) == level
                            || terrain.getWaterSurfaceLevel(x + 1, z) == level
                            || terrain.getWaterSurfaceLevel(x, z - 1) == level
                            || terrain.getWaterSurfaceLevel(x, z + 1) == level;
                        if (!connected) continue;
                        terrain.setPlacedWaterLevel(x, z, level);
                        invalidateEditedArea(x, x, z, z);
                        spread = true;
                        break;
                    }
                }
            }
            if (!spread) waterFlowActive[i] = false;
        }
    }

    /**
     * Keep the current jump/swim trajectory when a distant block is edited.
     * Only correct the player if the edited terrain has actually moved the
     * ground into the player's feet, or removed the support under a grounded
     * player. This avoids snapping the camera to the ground on every edit.
     */
    private void resolvePlayerAfterTerrainEdit() {
        float groundY = terrain.getHeight((int) cameraX, (int) cameraZ) + PLAYER_EYE_HEIGHT;
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
        // A leaf is selectable only if it is the first block intersected.
        int[] solidHit = raycastSurface();
        float solidDistance = solidHit == null ? 5001.0f : surfaceHitResult[4];
        double yr = Math.PI * pitch / 180.0;
        double xr = Math.PI * yaw / 180.0;
        float dx = (float) (Math.sin(xr) * Math.cos(yr));
        float dy = (float) Math.sin(yr);
        float dz = (float) (-Math.cos(xr) * Math.cos(yr));
        int x = floorVoxel(cameraX), y = floorVoxel(cameraY), z = floorVoxel(cameraZ);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        float tx = dx == 0 ? Float.MAX_VALUE : (((dx > 0 ? x + 1 : x) - cameraX) / dx);
        float ty = dy == 0 ? Float.MAX_VALUE : (((dy > 0 ? y + 1 : y) - cameraY) / dy);
        float tz = dz == 0 ? Float.MAX_VALUE : (((dz > 0 ? z + 1 : z) - cameraZ) / dz);
        float dtx = dx == 0 ? Float.MAX_VALUE : Math.abs(1.0f / dx);
        float dty = dy == 0 ? Float.MAX_VALUE : Math.abs(1.0f / dy);
        float dtz = dz == 0 ? Float.MAX_VALUE : Math.abs(1.0f / dz);
        float distance = 0.0f;
        while (distance <= BLOCK_REACH) {
            if (terrain.isLeafBlockAt(x, y, z)) {
                if ((int)(distance * 1000.0f) <= solidDistance) {
                    leafHitResult[0] = x; leafHitResult[1] = y; leafHitResult[2] = z;
                    return leafHitResult;
                }
                return null;
            }
            if (tx <= ty && tx <= tz) { distance = tx; tx += dtx; x += sx; }
            else if (ty <= tz) { distance = ty; ty += dty; y += sy; }
            else { distance = tz; tz += dtz; z += sz; }
        }
        return null;
    }

    private int floorVoxel(float value) {
        int whole = (int) value;
        return value < whole ? whole - 1 : whole;
    }

    /**
     * Classic voxel ray trace. It only hits visible faces, not invisible
     * cells buried below a height-map surface. Result: x, z, y, sideHit, distance*1000.
     */
    private int[] raycastSurface() {
        double yr = Math.PI * pitch / 180.0;
        double xr = Math.PI * yaw / 180.0;
        float dx = (float) (Math.sin(xr) * Math.cos(yr));
        float dy = (float) Math.sin(yr);
        float dz = (float) (-Math.cos(xr) * Math.cos(yr));

        int x = floorVoxel(cameraX), y = floorVoxel(cameraY), z = floorVoxel(cameraZ);
        int sx = dx > 0.0f ? 1 : -1;
        int sy = dy > 0.0f ? 1 : -1;
        int sz = dz > 0.0f ? 1 : -1;
        float tx = dx == 0.0f ? Float.MAX_VALUE :
            (((dx > 0.0f ? x + 1 : x) - cameraX) / dx);
        float ty = dy == 0.0f ? Float.MAX_VALUE :
            (((dy > 0.0f ? y + 1 : y) - cameraY) / dy);
        float tz = dz == 0.0f ? Float.MAX_VALUE :
            (((dz > 0.0f ? z + 1 : z) - cameraZ) / dz);
        float dtx = dx == 0.0f ? Float.MAX_VALUE : Math.abs(1.0f / dx);
        float dty = dy == 0.0f ? Float.MAX_VALUE : Math.abs(1.0f / dy);
        float dtz = dz == 0.0f ? Float.MAX_VALUE : Math.abs(1.0f / dz);
        float distance = 0.0f;
        int enteredAxis = -1;
        int previousX = x, previousY = y, previousZ = z;

        // Amanatides-Woo DDA: visit the voxel cells crossed by the view ray.
        // The height-map world only has editable column surfaces, so side hits
        // are accepted where the neighbouring column leaves a wall exposed.
        while (distance <= BLOCK_REACH) {
            if (x >= 1 && z >= 1 && x < TerrainGenerator.SIZE - 1
                    && z < TerrainGenerator.SIZE - 1 && y >= 0
                    && y < TerrainGenerator.HEIGHT) {
                int surface = terrain.getHeight(x, z);
                float nextBoundary = Math.min(tx, Math.min(ty, tz));
                // Intersect the actual top plane during this ray segment. This
                // also catches shallow/diagonal views that enter the cell from a side.
                if (dy < -0.0001f && surface > 0
                        && terrain.getBlockTypeAt(x, surface - 1, z) >= 0) {
                    float topDistance = (surface - cameraY) / dy;
                    if (topDistance >= distance - 0.0001f
                            && topDistance <= nextBoundary + 0.0001f
                            && topDistance >= 0.0f) {
                        float topX = cameraX + dx * topDistance;
                        float topZ = cameraZ + dz * topDistance;
                        if (floorVoxel(topX) == x && floorVoxel(topZ) == z) {
                            surfaceHitResult[0] = x;
                            surfaceHitResult[1] = z;
                            surfaceHitResult[2] = surface - 1;
                            surfaceHitResult[3] = 0;
                            surfaceHitResult[4] = (int)(topDistance * 1000.0f);
                            surfaceHitResult[5] = x; surfaceHitResult[6] = surface; surfaceHitResult[7] = z;
                            return surfaceHitResult;
                        }
                    }
                }
                boolean occupied = terrain.getBlockTypeAt(x, y, z) >= 0;
                boolean topFace = enteredAxis == 1 && dy < 0.0f && occupied
                    && terrain.getBlockTypeAt(x, y + 1, z) < 0;
                boolean sideFace = false;

                if (occupied && (enteredAxis == 0 || enteredAxis == 2)) {
                    int previousHeight = terrain.getHeight(previousX, previousZ);
                    int previousType = terrain.getBlockTypeAt(previousX, y, previousZ);
                    sideFace = y >= previousHeight || previousType < 0;
                }

                if (topFace || sideFace) {
                    surfaceHitResult[0] = x;
                    surfaceHitResult[1] = z;
                    surfaceHitResult[2] = y;
                    surfaceHitResult[3] = sideFace ? 1 : 0;
                    surfaceHitResult[4] = (int)(distance * 1000.0f);
                    if (topFace) { surfaceHitResult[5] = x; surfaceHitResult[6] = y + 1; surfaceHitResult[7] = z; }
                    else { surfaceHitResult[5] = previousX; surfaceHitResult[6] = previousY; surfaceHitResult[7] = previousZ; }
                    return surfaceHitResult;
                }
            }

            previousX = x;
            previousY = y;
            previousZ = z;
            if (tx <= ty && tx <= tz) {
                distance = tx; tx += dtx; x += sx; enteredAxis = 0;
            } else if (ty <= tz) {
                distance = ty; ty += dty; y += sy; enteredAxis = 1;
            } else {
                distance = tz; tz += dtz; z += sz; enteredAxis = 2;
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
        int firstVertex=vertex;
        boolean horizontal=y0==y1&&y0==y2&&y0==y3, xFace=x0==x1&&x0==x2&&x0==x3;
        int minX=Math.min(Math.min(x0,x1),Math.min(x2,x3)),maxX=Math.max(Math.max(x0,x1),Math.max(x2,x3));
        int minY=Math.min(Math.min(y0,y1),Math.min(y2,y3)),maxY=Math.max(Math.max(y0,y1),Math.max(y2,y3));
        int minZ=Math.min(Math.min(z0,z1),Math.min(z2,z3)),maxZ=Math.max(Math.max(z0,z1),Math.max(z2,z3));
        vertex=addVertex(positions,texCoords,colors,vertex,x0,y0,z0,tileX,tileY,uvU(x0,z0,horizontal,xFace,minX,maxX,minZ,maxZ),uvV(y0,z0,horizontal,minY,maxY,minZ,maxZ),shade);
        vertex=addVertex(positions,texCoords,colors,vertex,x1,y1,z1,tileX,tileY,uvU(x1,z1,horizontal,xFace,minX,maxX,minZ,maxZ),uvV(y1,z1,horizontal,minY,maxY,minZ,maxZ),shade);
        vertex=addVertex(positions,texCoords,colors,vertex,x2,y2,z2,tileX,tileY,uvU(x2,z2,horizontal,xFace,minX,maxX,minZ,maxZ),uvV(y2,z2,horizontal,minY,maxY,minZ,maxZ),shade);
        vertex=addVertex(positions,texCoords,colors,vertex,x3,y3,z3,tileX,tileY,uvU(x3,z3,horizontal,xFace,minX,maxX,minZ,maxZ),uvV(y3,z3,horizontal,minY,maxY,minZ,maxZ),shade);
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

    private int uvU(int x,int z,boolean horizontal,boolean xFace,int minX,int maxX,int minZ,int maxZ) {
        int value=(horizontal||!xFace)?x:z,min=(horizontal||!xFace)?minX:minZ,max=(horizontal||!xFace)?maxX:maxZ;
        return value==max&&max!=min?1:0;
    }
    private int uvV(int y,int z,boolean horizontal,int minY,int maxY,int minZ,int maxZ) {
        int value=horizontal?z:y,min=horizontal?minZ:minY,max=horizontal?maxZ:maxY;
        return value==max&&max!=min?1:0;
    }
    private int addVertex(short[] positions, short[] texCoords, byte[] colors,
                          int vertex, int x, int y, int z,
                          int tileX, int tileY, int uFraction, int vFraction, int shade) {
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
        boolean oreTile=(tileX==2&&(tileY==1||tileY==2||tileY==3))||(tileX==1&&tileY==2);
        boolean grassSideTile=tileX==3&&tileY==0;
        if(oreTile||grassSideTile)uFraction=1-uFraction;
        int u=uFraction==0?left:right, v=vFraction==0?bottom:top;
        int t = vertex * 2;
        texCoords[t] = (short) u;
        texCoords[t + 1] = (short) v;
        return vertex + 1;
    }
}
