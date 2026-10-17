# Minecraft J2ME Edition — Indev terrain prototype

Procedural 128 × 128 terrain using Java ME MIDP 2.0, CLDC 1.1 and M3G
(JSR-184). This milestone adds acceleration/friction-based movement, jumping with gravity, smoother terrain following, decorative trees with wood/leaf textures, per-block side texture tiling, surface-column block editing, a hotbar, animated translucent water blocks, exposed shoreline water faces, water-entry splash particles and slower movement in water.
Solid terrain is still height-map based rather than a complete voxel engine, but water is represented as discrete vertical block cells from the terrain surface to the waterline. Movement uses square column collision with one-block stepping, and swimming physics still prevents the player from passing through the solid lake floor.

## Requirements

- Java ME / MIDP 2.0
- CLDC 1.1
- JSR-184 Mobile 3D API
- Texture atlas packaged at `/mcj2me/textures.png` inside the JAR

## Controls

- **2 / 8:** forward / backward
- **4 / 6:** strafe left / right
- **D-pad left / right:** turn; **up / down:** look (nearly vertical, ±88°)
- **1:** break targeted surface block
- **3:** place selected block on the targeted column
- **7 / 9:** cycle hotbar slot
- **W / A / S / D:** movement in desktop emulators
- **Fire / center:** jump (press again after landing)

Water occupies discrete block cells only in naturally enclosed lowland regions found after the terrain height map is generated. The generator flood-fills connected columns below the waterline, rejects lowlands that reach the map edge, and keeps only basins large and deep enough to read as lakes. This replaces the old approach of stamping circular depressions at predetermined coordinates. Each water cell contributes to a block-like volume with a top face and exposed vertical shoreline faces rather than a single shared flat sheet. The water texture scrolls, entry triggers a splash, movement slows in water, a translucent blue screen tint appears only when the camera itself is below the waterline, and holding Fire/center strokes upward so the camera can break the surface. Solid-ground collision remains active underwater.

Block editing currently changes the top of a terrain column; side-face mining,
caves, persistence, mobs and survival are not implemented. Trees are decorative.

Package the atlas at `/mcj2me/textures.png` and set
`mcj2me.IndevViewerMIDlet` as the MIDlet class.
