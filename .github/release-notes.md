Erebus for Minecraft **1.21.11** (Fabric).

**Install:** put the `erebus-<version>.jar` below in your `mods` folder together with [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11. Requires Fabric Loader 0.20+ and Java 21. Open the dashboard with Right Shift.

### New in 0.4.0
- **Freecam** (ported from Nyx): fly the camera around while your player stays put. It's client-side only: your position, rotation and packets don't change. It can block clicks while the camera is out, hide your hand, keep nearby entities visible, and keep to loaded chunks in spectate mode.
- **Structure detection that doesn't flag builds**: the map only marks a structure once its real layout is confirmed.
  - Desert pyramids, jungle temples and swamp huts (new): marker blocks at the exact spot the game's code puts them.
  - Strongholds: the full 12-frame portal ring. Ocean monuments: their exact height, footprint and the ocean above. Nether fortresses: a blaze spawner or a stretch of bridge.
  - End cities, bastions and ruined portals: matched against the game's own structure templates.
- The Map module has **Rescan structures** and **Forget structures**, to clear markers left by older versions.

### What's in it
- **Dashboard** with module categories, search, keybinds and settings, an editable theme (presets, every color, radius, blur, animations) and its own UI font, Erebus Sans.
- **HUD** with a drag-and-drop editor: watermark, module list, coordinates, server info, nearby players, supplies, armor, notifications, minimap, finder legend.
- **World map** (M) recorded as you play, with waypoints, structure detection and base/stash markers.
- **Seed Cracker** (proof of concept) for servers with vanilla world generation.
- **Freecam**, client-side only.
- **Basefinding:** Stash ESP, Player Blocks, Old Chunks, Logout Spots.
- **Combat:** Auto Safe Anchor, Anchor Aura and Crystal Aura. All three work only through simulated mouse and key input (hotbar keys, mouse movement, use and attack keys), never crafted packets, and share the hands through one lock so they never click at the same time.

See the README for every module and setting.
