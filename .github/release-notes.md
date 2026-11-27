Erebus for Minecraft **1.21.11** (Fabric).

**Install:** put the `erebus-<version>.jar` below in your `mods` folder together with [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11. Requires Fabric Loader 0.20+ and Java 21. Open the dashboard with Right Shift.

### New in 0.6.3
- **Mineshafts are harder to fake**: a mineshaft's start room has a dome of air above it, and that's now required, so a room a player dug (with a flat ceiling) next to rails or wooden supports doesn't count as one.
- **A misread mineshaft no longer blocks the seed**: if nothing fits exactly, up to 2 mineshafts (or other clues that only check a seed) are set aside, and chat says which. Before, one wrong mineshaft could stop it finding anything unless you had 5 or more temples, huts, igloos or outposts.

### New in 0.6.2
- **Seed Cracker reads mineshafts**: from their start room, straight from the chunks the server sends (no need to go underground). They're everywhere, and the same rule placed them in chunks from 1.12 on. In testing, 1 outpost, the End pillars and 3 mineshafts were enough for the seed.
- **Villages, igloos and outposts count for more**: the cracker also records how each one's first piece came out (its rotation, and which town centre a village has) and checks that against the seed. In testing, 2 villages, an igloo and an outpost went from thousands of possible seeds to exactly one. Abandoned villages count like any other: they're the same structure with a zombie centre.
- **Cracking checks the real search size first**: some sets of structures pin the seed down less than usual; instead of grinding for minutes, it asks for one more temple, igloo or outpost.
- **Crystal Aura and Anchor Aura name who they skipped**: instead of "no player in range", the module list says who's nearest and why they don't count (in creative, out of range, on Ignore names...).

### New in 0.6.1
- **Crystal Aura and Anchor Aura say why they're idle**: the module list shows the reason (no players in range, no crystals, no glowstone in your hotbar, no good spot...). They only go for other players unless Target mobs is on, so in singleplayer, where there are none, they now tell you to turn it on instead of silently doing nothing.

### New in 0.6.0
- **Settings keep your place**: changing a setting in the dashboard no longer scrolls the settings back to the top.
- **Seed Cracker reads more**: igloos, villages (every biome, zombie villages too), pillager outposts and the End's pillars, on top of temples, swamp huts and buried treasure. The pillars still count when their tops are broken off (the bedrock each was capped with stays, and their width under the island is read otherwise). In testing, 2 outposts, 1 village and the End pillars were enough for the seed.
- **One marker per structure**: a trial chamber (or another large structure) seen from different sides no longer gets two markers. With the seed known, the marker goes on the structure's start. Use Forget structures in the Map module to clear duplicates already on your map.
- **Fullbright** (ported from Nyx), client-side only.

### New in 0.5.0
- **Predicted structures on the map**: turn on Show predicted in the Map module (or the Predicted chip on the full map) and pick which kinds. They're worked out from the world seed with the game's own world generation code, and in testing they matched `/locate`. The seed comes from singleplayer, Seed Cracker, or Seed Cracker's new Known seed setting. Vanilla world generation settings only.
- **Seed Cracker tolerates misreads**: if a few structures were misdetected, it still finds the seed (confirmed against the server's hashed seed) and says which structures it ignored.
- **New minimap**: classic frame, compass letters, mob dots, player names, waypoints pinned to the edge, coordinates / biome / facing / other-dimension lines, and zoom from 0.125x to 8x with zoom in/out keys.

### New in 0.4.0
- **Freecam** (ported from Nyx), client-side only.
- **Structure detection that doesn't flag builds**: the map only marks a structure once its real layout is confirmed.

### What's in it
- **Dashboard** with module categories, search, keybinds and settings, an editable theme (presets, every color, radius, blur, animations) and its own UI font, Erebus Sans.
- **HUD** with a drag-and-drop editor: watermark, module list, coordinates, server info, nearby players, supplies, armor, notifications, minimap, finder legend.
- **World map** (M) recorded as you play, with waypoints, structure detection and base/stash markers.
- **Seed Cracker** (proof of concept) for servers with vanilla world generation.
- **Freecam** and **Fullbright**, client-side only.
- **Basefinding:** Stash ESP, Player Blocks, Old Chunks, Logout Spots.
- **Combat:** Auto Safe Anchor, Anchor Aura and Crystal Aura. All three work only through simulated mouse and key input (hotbar keys, mouse movement, use and attack keys), never crafted packets, and share the hands through one lock so they never click at the same time.

See the README for every module and setting.
