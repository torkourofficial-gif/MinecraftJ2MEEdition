# Minecraft J2ME Edition — Indev terrain prototype

Procedural 128 × 128 terrain using Java ME MIDP 2.0, CLDC 1.1 and M3G
(JSR-184). This milestone adds terrain-following movement, decorative trees,
surface-column block editing, a hotbar and a lightweight break-particle effect.
It is still height-map based, not a complete voxel engine.

## Requirements

- Java ME / MIDP 2.0
- CLDC 1.1
- JSR-184 Mobile 3D API
- Texture atlas packaged at `/mcj2me/textures.png` inside the JAR

## Controls

- **2 / 8:** forward / backward
- **4 / 6:** strafe left / right
- **D-pad left / right:** turn; **up / down:** look
- **1:** break targeted surface block
- **3:** place selected block on the targeted column
- **7 / 9:** cycle hotbar slot
- **W / A / S / D:** movement in desktop emulators
- **Fire / center:** tilt camera down slightly

Block editing currently changes the top of a terrain column; side-face mining,
caves, persistence, mobs and survival are not implemented. Trees are decorative.

Package the atlas at `/mcj2me/textures.png` and set
`mcj2me.IndevViewerMIDlet` as the MIDlet class.
