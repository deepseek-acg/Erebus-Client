Erebus for Minecraft **1.21.11** (Fabric).

**Install:** put the `erebus-<version>.jar` below in your `mods` folder together with [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11. Requires Fabric Loader 0.20+ and Java 21. Open the dashboard with Right Shift.

### New in 0.7.1
- **Light Finder** (Basefinding): finds light nature doesn't explain. Player-made lights (torches, lanterns, glowstone, froglights, lamps, campfires, lit furnaces, beacons, portals...) are told apart from natural ones (lava, glow lichen, glow berries, amethyst, sculk, Nether glowstone, End rods in the End, mineshaft torches) and from the lights of villages and other structures, and the client's own light data shows lit space underground or indoors that no natural source explains. Lit furnaces, beacons, portals and lights above the Nether roof stand out, areas are alerted and marked on the map, and a light placed near you raises a live alert: someone is there right now.

### New in 0.7.0
- **Player ESP** (ported from Nyx): boxes or corners, tracers and sharp name tags through walls with health, distance, ping, held item, armor and totem pops; colors by distance, health or team; friends list; skip tab-list bots.
- **Auto Totem** (ported from Nyx's Smarter AutoTotem): random, ping-adjusted reaction times, done entirely with real key presses and cursor moves (hotbar swap, or open the inventory, hover a totem, swap, close). Smart mode reacts to crystals, anchors, beds, falls and maces nearby, not just low health; keeps an optional spare in a hotbar slot; pop alerts.
- **X-Ray** (ported from Nyx): only ores (of the dimension you're in), storage, player blocks and your own list show. Ores with no air next to them are dimmed, as they're probably fakes on anti-xray servers, and exposed ones are outlined. Caves, lava and water are shaded through the rock.
- **Crystal Aura no longer gives up after you hit someone**: following your opponent with the mouse used to count as "flicking away", so it let go of the spot and did nothing for the rest of the knock-up. Mouse movement is now measured properly (net over the last 150 ms, so tracking back and forth cancels out), only a flick *away* from where it's aiming counts, it takes half as much again right after a hit, and the turn to the spot holds the camera right after a hit so the click lands while you're still tracking.
- **Aim Assist** (Combat): pulls your view towards the opponent you're aiming near, on top of your own mouse movement, through simulated mouse input only. Base speed plus a variable part that grows with how far off you are, a speed cap, ramp-up, and softer pull once you're on them. Gaussian randomness for the speed and the point it aims at (spread and smoothness adjustable). For multiplayer: friends list, ignore teammates, bots (players missing from the tab list), creative, invisible and naked players, wall check, sticky targeting, movement prediction, bow/crossbow/trident drop compensation, weapon-only and while-attacking activation, only-while-aiming, and flick-to-release. Better visuals and an FOV circle show what it's doing.
- **Seed Cracker handles servers with their own structure salts**: Spigot and Paper can give each kind of structure its own salt (`seed-village`, `seed-desert`... in `spigot.yml`), a common way to protect a seed. If no seed fits the game's own settings, Seed Cracker now tries again with each kind on its own salt, tied to the seed by what those settings don't change: the End pillars, how each structure's first piece came out, outposts' extra roll and mineshafts. It then works out each kind's salt, says which settings were changed, and predicted structures on the map use them. Read the End pillars on such servers: they're effectively required. Turn it off with Custom structure salts.
- **Spots a fake hashed seed**: when seeds fit but none matches the hashed seed the server sends, it says the server may be sending a fake one.

### New in 0.6.5
- **Better visuals**: purely cosmetic extras for every module where they make sense. The combat modules show what they're doing (the anchor sequence's step, charges, shield and blast radius; Crystal Aura's spot, crystal, blast and the damage it should do), Stash ESP gets a shape for each kind of find and beams over bases, Freecam marks your body, Logout Spots draws a ghost, Seed Cracker outlines the start chunks it read, and more. On by default: turn them off on the Client page, or per module with each module's Better visuals setting.

### New in 0.6.4
- **Mineshaft rooms are checked against the seed**: the game rolls each mineshaft's start room size from the seed, so Seed Cracker now measures it (8 to 13 blocks each way) and checks it. A room that isn't really a mineshaft's start almost never has the right size, so it's set aside instead of spoiling the crack, and a real one counts for more. Chat shows the size ("Mineshaft (room 9x12)"). Several rooms in what looks like one big network are separate mineshafts whose corridors ran together, so each one counts.

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
