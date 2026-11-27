Erebus for Minecraft **1.21.11** (Fabric).

**Install:** put the `erebus-<version>.jar` below in your `mods` folder together with [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11. Requires Fabric Loader 0.20+ and Java 21. Open the dashboard with Right Shift.

### New in 0.5.0
- **Predicted structures on the map**: turn on Show predicted in the Map module (or the Predicted chip on the full map) and pick which kinds. They're worked out from the world seed with the game's own world generation code, and in testing they matched `/locate`. The seed comes from singleplayer, Seed Cracker, or Seed Cracker's new Known seed setting. Vanilla world generation settings only.
- **Seed Cracker tolerates misreads**: if a few structures were misdetected, it still finds the seed (confirmed against the server's hashed seed) and says which structures it ignored.
- **One marker per structure**: trial chambers (and other large structures) seen from different sides no longer get two markers. With the seed known, the marker goes on the structure's start. Use Forget structures in the Map module to clear duplicates already on your map.
- **New minimap**: classic frame, compass letters, mob dots, player names, waypoints pinned to the edge, coordinates / biome / facing / other-dimension lines, and zoom from 0.125x to 8x with zoom in/out keys.

### New in 0.4.0
- **Freecam** (ported from Nyx), client-side only.
- **Structure detection that doesn't flag builds**: the map only marks a structure once its real layout is confirmed.

### What's in it
- **Dashboard** with module categories, search, keybinds and settings, an editable theme (presets, every color, radius, blur, animations) and its own UI font, Erebus Sans.
- **HUD** with a drag-and-drop editor: watermark, module list, coordinates, server info, nearby players, supplies, armor, notifications, minimap, finder legend.
- **World map** (M) recorded as you play, with waypoints, structure detection and base/stash markers.
- **Seed Cracker** (proof of concept) for servers with vanilla world generation.
- **Freecam**, client-side only.
- **Basefinding:** Stash ESP, Player Blocks, Old Chunks, Logout Spots.
- **Combat:** Auto Safe Anchor, Anchor Aura and Crystal Aura. All three work only through simulated mouse and key input (hotbar keys, mouse movement, use and attack keys), never crafted packets, and share the hands through one lock so they never click at the same time.

See the README for every module and setting.
