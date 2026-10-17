# Minecraft J2ME Edition — Indev terrain prototype

Procedural 128 × 128 terrain using Java ME MIDP 2.0, CLDC 1.1 and M3G
(JSR-184). This milestone adds acceleration/friction-based movement, jumping with gravity, smoother terrain following, trees with individually breakable leaves and wood textures, block-flat vertex-color shadows, per-block side texture tiling, surface-column block editing, a classic GUI hotbar with grass/dirt/stone/wood/leaves previews, large square-patterned, rigidly scrolling cloud layer, a sea plane reaching beyond the world edge, animated translucent water blocks, exposed shoreline water faces, water-entry splash particles and slower movement in water.
Solid terrain is still height-map based rather than a complete voxel engine, but water is represented as discrete vertical block cells from the terrain surface to the waterline. Movement uses square column collision with one-block stepping, and swimming physics still prevents the player from passing through the solid lake floor.

## Requirements

- Java ME / MIDP 2.0
- CLDC 1.1
- JSR-184 Mobile 3D API
- Resource images packaged inside the JAR at `/mcj2me/textures.png`, `/mcj2me/clouds.png`, `/mcj2me/gui.png` and `/mcj2me/gui_blocks.png`

## Controls

- **2 / 8:** forward / backward
- **4 / 6:** strafe left / right
- **D-pad left / right:** turn; **up / down:** look (nearly vertical, ±88°)
- **1:** break targeted surface block
- **3:** place selected block on the targeted column
- **Tecla estrella (`*`):** cycle forward through the five hotbar blocks (grass, dirt, stone, wood, leaves)
- **7 / 9:** cycle backward / forward through the five hotbar blocks
- **W / A / S / D:** movement in desktop emulators
- **Fire / center:** jump (press again after landing)

Each new world gets a random seed, which changes both the procedural terrain and lake placement. Lakes are generated as structures: the generator chooses random locations, sizes and depths, carves irregular bowl-shaped basins into the terrain, raises their edges into shorelines, and fills their interiors with water. Water is therefore part of the basin structure itself, rather than a later pass that searches for naturally low terrain. The generator currently attempts 7–12 lakes per world. Each water cell contributes to a block-like volume with a top face and exposed vertical shoreline faces rather than a single shared flat sheet. The water texture scrolls, a broad sea plane continues beyond the world boundary, entry into lake water triggers a splash, movement slows in water, a translucent blue screen tint appears only when the camera itself is below the waterline, and holding Fire/center strokes upward so the camera can break the surface. Solid-ground collision remains active underwater.

Terrain mining still changes the top of a terrain column; side-face mining, caves, persistence, mobs and survival are not implemented. Tree leaves are separate breakable canopy blocks. A lightweight Indev-style shadow approximation darkens surfaces beneath leaves and beside higher terrain.

Package all four PNG resources under the `mcj2me` resource package (`/mcj2me/`) and set `mcj2me.IndevViewerMIDlet` as the MIDlet class. The repository keeps copies in `src/mcj2me/` so NetBeans can package them as classpath resources. The hotbar previews use the matching 48×48 cells in `gui_blocks.png`; pressing the `*` key selects the next block.
