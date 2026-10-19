package mcj2me;

/**
 * Recipe registry for the Minecraft J2ME crafting system.
 *
 * Recipes are split into the 2x2 player-inventory grid and the 3x3 crafting
 * table grid, like Minecraft Legacy/PE. A grid cell contains an item/block ID;
 * -1 means empty. Recipe matching supports moving a shaped recipe anywhere in
 * the grid and horizontal mirroring.
 *
 * IDs 0..43 are the existing WorldCanvas IDs. IDs 44+ are reserved for
 * craftable items that still need to be added to the game's item/inventory
 * registry before their recipes can be used in-game.
 */
public final class crafts {
    private crafts() { }

    public static final int EMPTY = -1;
    public static final int WHEAT = 104;

    // Existing WorldCanvas IDs.
    public static final int GRASS = 0, DIRT = 1, STONE = 2, WOOD = 3, LEAVES = 4;
    public static final int COAL_ORE = 5, IRON_ORE = 6, GOLD_ORE = 7, DIAMOND_ORE = 8;
    public static final int BEDROCK = 9, TNT = 10, OAK_PLANKS = 11, SAND = 12, GRAVEL = 13;
    public static final int BRICK_BLOCK = 14, OBSIDIAN = 15, TORCH = 16;
    public static final int YELLOW_FLOWER = 17, RED_FLOWER = 18, SAPLING = 19, DOOR = 20;
    public static final int IRON_BLOCK = 21, GOLD_BLOCK = 22, DIAMOND_BLOCK = 23;
    public static final int WHITE_WOOL = 24, ORANGE_WOOL = 25, MAGENTA_WOOL = 26;
    public static final int LIGHT_BLUE_WOOL = 27, YELLOW_WOOL = 28, LIME_WOOL = 29;
    public static final int PINK_WOOL = 30, GRAY_WOOL = 31, LIGHT_GRAY_WOOL = 32;
    public static final int CYAN_WOOL = 33, PURPLE_WOOL = 34, BLUE_WOOL = 35;
    public static final int BROWN_WOOL = 36, GREEN_WOOL = 37, RED_WOOL = 38, BLACK_WOOL = 39;
    public static final int WATER_BUCKET = 40, EMPTY_BUCKET = 41, RAW_PORKCHOP = 42, RAW_BEEF = 43;

    // Reserved IDs for the next inventory/item-registry expansion.
    public static final int STICK = 44, CRAFTING_TABLE = 45, COAL = 46;
    public static final int CHARCOAL = 47, IRON_INGOT = 48, GOLD_INGOT = 49;
    public static final int DIAMOND = 50, FLINT = 51, STRING = 52, FEATHER = 53;
    public static final int PAPER = 54, BOOK = 55, CHEST = 56, FURNACE = 57;
    public static final int LADDER = 58, SIGN = 59, BOWL = 60, BREAD = 61;
    public static final int WOODEN_PICKAXE = 62, STONE_PICKAXE = 63, IRON_PICKAXE = 64;
    public static final int DIAMOND_PICKAXE = 65, WOODEN_AXE = 66, STONE_AXE = 67;
    public static final int IRON_AXE = 68, DIAMOND_AXE = 69, WOODEN_SHOVEL = 70;
    public static final int STONE_SHOVEL = 71, IRON_SHOVEL = 72, DIAMOND_SHOVEL = 73;
    public static final int WOODEN_SWORD = 74, STONE_SWORD = 75, IRON_SWORD = 76;
    public static final int DIAMOND_SWORD = 77, WOODEN_HOE = 78, STONE_HOE = 79;
    public static final int IRON_HOE = 80, DIAMOND_HOE = 81, BOW = 82, ARROW = 83;
    public static final int BOAT = 84, MINECART = 85, RAIL = 86, PAPER_ITEM = PAPER;
    public static final int GLASS = 88, GLASS_PANE = 89, STONE_BRICKS = 90;
    public static final int LEVER = 91, STONE_BUTTON = 92, WOODEN_BUTTON = 93;
    public static final int PRESSURE_PLATE = 94, WOODEN_PRESSURE_PLATE = 95;
    public static final int REDSTONE_TORCH = 96, WOODEN_FENCE = 97, FENCE_GATE = 98;
    public static final int WOODEN_STAIRS = 99, STONE_STAIRS = 100, SLAB = 101;
    public static final int BUCKET = 102, FLINT_AND_STEEL = 103;

    public static final class Recipe {
        public final int width;
        public final int height;
        public final int[] ingredients;
        public final int output;
        public final int outputCount;
        public final boolean mirrored;

        private Recipe(int w, int h, int[] in, int out, int count, boolean mirror) {
            width = w;
            height = h;
            ingredients = in;
            output = out;
            outputCount = count;
            mirrored = mirror;
        }

        /** Match a 2x2 or 3x3 grid; grid is row-major and uses EMPTY for blanks. */
        public boolean matches(int[] grid, int gridSize) {
            if (grid == null || gridSize < width || gridSize < height
                    || grid.length < gridSize * gridSize) return false;
            for (int oy = 0; oy <= gridSize - height; oy++) {
                for (int ox = 0; ox <= gridSize - width; ox++) {
                    if (matchesAt(grid, gridSize, ox, oy, false)) return true;
                    if (mirrored && matchesAt(grid, gridSize, ox, oy, true)) return true;
                }
            }
            return false;
        }

        private boolean matchesAt(int[] grid, int size, int ox, int oy, boolean flip) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int expected = EMPTY;
                    if (x >= ox && x < ox + width && y >= oy && y < oy + height) {
                        int rx = x - ox;
                        if (flip) rx = width - 1 - rx;
                        expected = ingredients[(y - oy) * width + rx];
                    }
                    if (grid[y * size + x] != expected) return false;
                }
            }
            return true;
        }
    }

    private static Recipe r(int w, int h, int[] in, int out, int count, boolean mirror) {
        return new Recipe(w, h, in, out, count, mirror);
    }

    private static final Recipe[] INVENTORY_RECIPES = {
        // One log -> four planks; four planks -> a crafting table.
        r(1, 1, new int[] { WOOD }, OAK_PLANKS, 4, false),
        r(2, 2, new int[] { OAK_PLANKS, OAK_PLANKS, OAK_PLANKS, OAK_PLANKS },
                CRAFTING_TABLE, 1, false),
        // Planks are interchangeable for basic wooden components.
        r(1, 2, new int[] { OAK_PLANKS, OAK_PLANKS }, STICK, 4, false),
        r(1, 1, new int[] { COAL }, TORCH, 4, false),
        r(1, 1, new int[] { CHARCOAL }, TORCH, 4, false)
    };

    private static final Recipe[] TABLE_RECIPES = {
        // Building and utility blocks.
        r(2, 3, new int[] { OAK_PLANKS, OAK_PLANKS,
                            OAK_PLANKS, OAK_PLANKS,
                            OAK_PLANKS, OAK_PLANKS }, DOOR, 3, false),
        r(3, 3, new int[] { OAK_PLANKS, OAK_PLANKS, OAK_PLANKS,
                            OAK_PLANKS, EMPTY, OAK_PLANKS,
                            OAK_PLANKS, OAK_PLANKS, OAK_PLANKS }, CHEST, 1, false),
        r(3, 3, new int[] { STONE, STONE, STONE,
                            STONE, EMPTY, STONE,
                            STONE, STONE, STONE }, FURNACE, 1, false),
        r(3, 3, new int[] { OAK_PLANKS, OAK_PLANKS, OAK_PLANKS,
                            EMPTY, OAK_PLANKS, EMPTY,
                            EMPTY, OAK_PLANKS, EMPTY }, LADDER, 3, false),
        r(3, 3, new int[] { OAK_PLANKS, OAK_PLANKS, OAK_PLANKS,
                            OAK_PLANKS, OAK_PLANKS, OAK_PLANKS,
                            EMPTY, STICK, EMPTY }, SIGN, 3, false),
        r(3, 3, new int[] { OAK_PLANKS, EMPTY, OAK_PLANKS,
                            OAK_PLANKS, EMPTY, OAK_PLANKS,
                            OAK_PLANKS, EMPTY, OAK_PLANKS }, WOODEN_FENCE, 2, false),
        r(3, 3, new int[] { OAK_PLANKS, STICK, OAK_PLANKS,
                            OAK_PLANKS, STICK, OAK_PLANKS,
                            EMPTY, EMPTY, EMPTY }, FENCE_GATE, 1, true),
        r(3, 3, new int[] { OAK_PLANKS, EMPTY, EMPTY,
                            OAK_PLANKS, OAK_PLANKS, EMPTY,
                            OAK_PLANKS, OAK_PLANKS, OAK_PLANKS }, WOODEN_STAIRS, 4, true),
        r(3, 3, new int[] { STONE, EMPTY, EMPTY,
                            STONE, STONE, EMPTY,
                            STONE, STONE, STONE }, STONE_STAIRS, 4, true),
        r(3, 3, new int[] { STONE, STONE, STONE,
                            EMPTY, EMPTY, EMPTY,
                            EMPTY, EMPTY, EMPTY }, SLAB, 6, false),
        r(3, 3, new int[] { OAK_PLANKS, OAK_PLANKS, EMPTY,
                            OAK_PLANKS, OAK_PLANKS, EMPTY,
                            EMPTY, EMPTY, EMPTY }, WOODEN_PRESSURE_PLATE, 1, false),
        r(2, 1, new int[] { STONE, STONE }, STONE_BUTTON, 1, false),
        r(2, 1, new int[] { OAK_PLANKS, OAK_PLANKS }, WOODEN_BUTTON, 1, false),
        r(3, 3, new int[] { EMPTY, STICK, EMPTY,
                            STICK, STONE, STICK,
                            EMPTY, EMPTY, EMPTY }, LEVER, 1, false),
        r(3, 3, new int[] { EMPTY, STICK, EMPTY,
                            STICK, STICK, STICK,
                            EMPTY, EMPTY, EMPTY }, REDSTONE_TORCH, 1, false),

        // Storage, transport and paper.
        r(3, 3, new int[] { OAK_PLANKS, EMPTY, OAK_PLANKS,
                            OAK_PLANKS, EMPTY, OAK_PLANKS,
                            OAK_PLANKS, OAK_PLANKS, OAK_PLANKS }, BOAT, 1, false),
        r(3, 3, new int[] { IRON_INGOT, EMPTY, IRON_INGOT,
                            IRON_INGOT, EMPTY, IRON_INGOT,
                            IRON_INGOT, IRON_INGOT, IRON_INGOT }, MINECART, 1, false),
        r(3, 3, new int[] { IRON_INGOT, EMPTY, IRON_INGOT,
                            IRON_INGOT, IRON_INGOT, IRON_INGOT,
                            IRON_INGOT, EMPTY, IRON_INGOT }, BUCKET, 1, false),
        r(3, 3, new int[] { IRON_INGOT, IRON_INGOT, IRON_INGOT,
                            IRON_INGOT, EMPTY, IRON_INGOT,
                            IRON_INGOT, IRON_INGOT, IRON_INGOT }, IRON_BLOCK, 1, false),
        r(3, 3, new int[] { GOLD_INGOT, GOLD_INGOT, GOLD_INGOT,
                            GOLD_INGOT, EMPTY, GOLD_INGOT,
                            GOLD_INGOT, GOLD_INGOT, GOLD_INGOT }, GOLD_BLOCK, 1, false),
        r(3, 3, new int[] { DIAMOND, DIAMOND, DIAMOND,
                            DIAMOND, EMPTY, DIAMOND,
                            DIAMOND, DIAMOND, DIAMOND }, DIAMOND_BLOCK, 1, false),
        r(3, 3, new int[] { EMPTY, EMPTY, EMPTY,
                            PAPER, PAPER, PAPER,
                            EMPTY, EMPTY, EMPTY }, PAPER_ITEM, 3, false),
        r(3, 3, new int[] { PAPER, PAPER, EMPTY,
                            PAPER, PAPER, EMPTY,
                            EMPTY, EMPTY, EMPTY }, BOOK, 1, false),
        r(3, 3, new int[] { GLASS, GLASS, GLASS,
                            GLASS, GLASS, GLASS,
                            EMPTY, EMPTY, EMPTY }, GLASS_PANE, 16, false),
        r(3, 3, new int[] { STONE, STONE, STONE,
                            STONE, STONE, STONE,
                            STONE, STONE, STONE }, STONE_BRICKS, 1, false),
        r(3, 3, new int[] { EMPTY, EMPTY, EMPTY,
                            EMPTY, EMPTY, EMPTY,
                            EMPTY, EMPTY, EMPTY }, OBSIDIAN, 0, false),

        // Tools. Each recipe uses the classic shaped layout.
        r(3, 3, new int[] { OAK_PLANKS, OAK_PLANKS, OAK_PLANKS,
                            EMPTY, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, WOODEN_PICKAXE, 1, false),
        r(3, 3, new int[] { STONE, STONE, STONE,
                            EMPTY, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, STONE_PICKAXE, 1, false),
        r(3, 3, new int[] { IRON_INGOT, IRON_INGOT, IRON_INGOT,
                            EMPTY, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, IRON_PICKAXE, 1, false),
        r(3, 3, new int[] { DIAMOND, DIAMOND, DIAMOND,
                            EMPTY, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, DIAMOND_PICKAXE, 1, false),
        r(3, 3, new int[] { OAK_PLANKS, OAK_PLANKS, EMPTY,
                            OAK_PLANKS, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, WOODEN_AXE, 1, true),
        r(3, 3, new int[] { STONE, STONE, EMPTY,
                            STONE, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, STONE_AXE, 1, true),
        r(3, 3, new int[] { IRON_INGOT, IRON_INGOT, EMPTY,
                            IRON_INGOT, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, IRON_AXE, 1, true),
        r(3, 3, new int[] { DIAMOND, DIAMOND, EMPTY,
                            DIAMOND, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, DIAMOND_AXE, 1, true),
        r(3, 3, new int[] { OAK_PLANKS, EMPTY, EMPTY,
                            STICK, EMPTY, EMPTY,
                            STICK, EMPTY, EMPTY }, WOODEN_SHOVEL, 1, false),
        r(3, 3, new int[] { STONE, EMPTY, EMPTY,
                            STICK, EMPTY, EMPTY,
                            STICK, EMPTY, EMPTY }, STONE_SHOVEL, 1, false),
        r(3, 3, new int[] { IRON_INGOT, EMPTY, EMPTY,
                            STICK, EMPTY, EMPTY,
                            STICK, EMPTY, EMPTY }, IRON_SHOVEL, 1, false),
        r(3, 3, new int[] { DIAMOND, EMPTY, EMPTY,
                            STICK, EMPTY, EMPTY,
                            STICK, EMPTY, EMPTY }, DIAMOND_SHOVEL, 1, false),
        r(3, 3, new int[] { EMPTY, OAK_PLANKS, EMPTY,
                            EMPTY, OAK_PLANKS, EMPTY,
                            EMPTY, STICK, EMPTY }, WOODEN_SWORD, 1, false),
        r(3, 3, new int[] { EMPTY, STONE, EMPTY,
                            EMPTY, STONE, EMPTY,
                            EMPTY, STICK, EMPTY }, STONE_SWORD, 1, false),
        r(3, 3, new int[] { EMPTY, IRON_INGOT, EMPTY,
                            EMPTY, IRON_INGOT, EMPTY,
                            EMPTY, STICK, EMPTY }, IRON_SWORD, 1, false),
        r(3, 3, new int[] { EMPTY, DIAMOND, EMPTY,
                            EMPTY, DIAMOND, EMPTY,
                            EMPTY, STICK, EMPTY }, DIAMOND_SWORD, 1, false),
        r(3, 3, new int[] { OAK_PLANKS, OAK_PLANKS, EMPTY,
                            EMPTY, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, WOODEN_HOE, 1, true),
        r(3, 3, new int[] { STONE, STONE, EMPTY,
                            EMPTY, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, STONE_HOE, 1, true),
        r(3, 3, new int[] { IRON_INGOT, IRON_INGOT, EMPTY,
                            EMPTY, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, IRON_HOE, 1, true),
        r(3, 3, new int[] { DIAMOND, DIAMOND, EMPTY,
                            EMPTY, STICK, EMPTY,
                            EMPTY, STICK, EMPTY }, DIAMOND_HOE, 1, true),

        // Combat, food and miscellaneous classic recipes.
        r(1, 3, new int[] { FLINT, STICK, FEATHER }, ARROW, 4, false),
        r(3, 3, new int[] { EMPTY, STICK, STRING,
                            STICK, EMPTY, STRING,
                            EMPTY, STICK, STRING }, BOW, 1, true),
        r(3, 3, new int[] { EMPTY, EMPTY, EMPTY,
                            WHEAT, WHEAT, WHEAT,
                            EMPTY, EMPTY, EMPTY }, BREAD, 1, false),
        r(3, 3, new int[] { EMPTY, STICK, EMPTY,
                            STICK, EMPTY, STICK,
                            EMPTY, STICK, EMPTY }, RAIL, 16, false),
        r(2, 1, new int[] { FLINT, IRON_INGOT }, FLINT_AND_STEEL, 1, true)
    };

    // Wheat is a reserved ingredient until farming is implemented.

    public static int inventoryRecipeCount() { return INVENTORY_RECIPES.length; }
    public static int tableRecipeCount() { return TABLE_RECIPES.length; }

    public static Recipe inventoryRecipeAt(int index) {
        return index < 0 || index >= INVENTORY_RECIPES.length ? null : INVENTORY_RECIPES[index];
    }

    public static Recipe tableRecipeAt(int index) {
        return index < 0 || index >= TABLE_RECIPES.length ? null : TABLE_RECIPES[index];
    }

    /** Return the matching inventory (2x2) recipe, or null if there is none. */
    public static Recipe findInventoryRecipe(int[] grid) {
        for (int i = 0; i < INVENTORY_RECIPES.length; i++)
            if (INVENTORY_RECIPES[i].matches(grid, 2)) return INVENTORY_RECIPES[i];
        return null;
    }

    /** Return the matching crafting-table (3x3) recipe, or null if there is none. */
    public static Recipe findTableRecipe(int[] grid) {
        for (int i = 0; i < TABLE_RECIPES.length; i++)
            if (TABLE_RECIPES[i].matches(grid, 3)) return TABLE_RECIPES[i];
        return null;
    }
}
