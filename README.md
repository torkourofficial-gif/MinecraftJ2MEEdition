# Minecraft J2ME Edition — Indev Viewer

The first milestone is a small **spectator-mode terrain viewer** inspired by
Minecraft Indev 20100223. It generates a deterministic 128 × 128 height map
(up to 64 blocks high), uses the existing `textures.png` atlas, and renders
only the 3 × 3 terrain chunks around the camera to limit heap use.

## Requirements

- Java ME / MIDP 2.0
- CLDC 1.1
- JSR-184 Mobile 3D API (M3G) implementation
- The repository's `textures.png` in the root of the application JAR

This is intended for devices with an M3G implementation. A phone having Java
ME alone is not enough; the emulator/runtime must provide JSR-184.

## Source layout

- `src/mcj2me/IndevViewerMIDlet.java` — MIDlet entry point
- `src/mcj2me/TerrainGenerator.java` — deterministic procedural height map
- `src/mcj2me/WorldCanvas.java` — M3G scene, chunk mesh generation and controls
- `textures.png` — original atlas already in the repository

## Controls

- **W / Up:** move forward
- **S / Down:** move backward
- **A / D:** strafe left / right
- **Left / Right:** turn
- **E / Q:** fly up / down
- **Fire / center:** tilt the camera farther down

The camera has no collision, gravity, block interaction, inventory, saving or
survival mechanics yet. The terrain is height-map based rather than a full
voxel world, so caves and overhangs are not generated in this milestone.

## Build notes

Create a MIDP 2.0 project in a Java ME SDK that includes JSR-184, add the three
Java files under `src`, and include `textures.png` at the JAR root as a
resource. Package `mcj2me.IndevViewerMIDlet` as the MIDlet class and use the
JAD metadata in `MinecraftIndevViewer.jad`.

The GitHub source has not been compiled against a particular vendor's M3G
runtime yet. Small API differences between phone runtimes and emulators may
require adjustments.
