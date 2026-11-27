# Erebus

A utility client for the 6b6t anarchy server, built as a Fabric mod for Minecraft **1.21.11**.

It has a dashboard with an editable theme, a HUD with a drag-and-drop editor, its own UI font
(Erebus Sans), a recorded world map with waypoints, and basefinding modules ported from Nyx.

## Requirements

- Java 21
- Fabric Loader 0.20+ and Fabric API for 1.21.11

## Build and run

```bash
./gradlew build
```

The mod jar is written to `build/libs/erebus-<version>.jar`. Drop it into your `mods` folder
alongside Fabric API.

To start a development client:

```bash
./gradlew runClient
```

## Using it

| Action | Default |
| --- | --- |
| Open or close the dashboard | Right Shift (rebind in the dashboard's Client page or in Controls) |
| Open the map | M |
| Search modules | Start typing, or Ctrl+F |
| Toggle a module from the list | Right-click it |
| Clear a keybind | Right-click the keybind button, or press Backspace while it's listening |

### Dashboard

- **Module categories** (Combat, Movement, Render, Player, World, Misc) list modules on the left
  and the selected module's settings on the right. Settings render automatically from the module's
  setting definitions: switches, sliders, mode pickers, color pickers, text fields and keybinds.
- **HUD** turns elements on and off, applies layout presets (PvP, Travel, Base, All), edits each
  element's settings and opens the HUD editor.
- **Theme** starts from a preset (Void, Abyss, Ember, Mono). You can then change every color with an
  HSV picker or hex code, plus corner radius, panel opacity, drop shadow, background blur, text
  shadow and animations. Changes apply live.
- **Client** has the menu scale, menu keybind and general preferences.

### HUD editor

Drag elements to move them. Positions snap to a 4px grid, to screen edges and to the horizontal
center; hold Alt to move freely. Arrow keys nudge the selected element (Shift for bigger steps).
Right-click or press Delete to hide an element. The toolbar gets out of the way while you drag;
press H to hide it entirely.

Each element is stored relative to the nearest screen edge, so layouts keep their place when the
window size or GUI scale changes.

Built-in elements: Watermark, Module list, Coordinates (with Nether/Overworld conversion and
facing), Server info (FPS, ping, speed), Nearby players, Supplies (totems, crystals, gapples,
obsidian, XP bottles), Armor, and Notifications.

## Modules

| Module | Category | What it does |
| --- | --- | --- |
| Map | World | Records a map of every chunk you load, per server and dimension, and recognises structures (trial chambers, ancient cities, strongholds, monuments, fortresses, bastions, end cities, temples, swamp huts and more), each confirmed against its real layout so builds aren't flagged. On by default. |
| Seed Cracker | World | Works out the world seed from desert pyramids, jungle temples, swamp huts, igloos, villages, pillager outposts, buried treasure and the End's pillars. Proof of concept for servers with vanilla world generation settings. |
| Stash ESP | Basefinding | Outlines and tracers to storage and other base giveaways (chests, shulkers, ender chests, furnaces, villagers, item frames, chest minecarts...). Alerts when an area looks like a base or a stash, marks it on the map and logs it to `erebus/finds.txt`. |
| Player Blocks | Basefinding | Highlights blocks the world never generates (concrete, shulkers, beacons, mineral blocks, redstone parts...) and building materials where they don't belong, skipping natural structures and biomes where they occur naturally. Lets Stash ESP tell bases from stashes. |
| Old Chunks | Basefinding | Marks chunks generated before 1.18 (Overworld) or 1.16 (Nether): old trails lead to old bases. |
| Freecam | Render | Flies the camera away from your player while your player stays put. Client-side only. Ported from Nyx. |
| Fullbright | Render | Lights everything up, like a permanent Night Vision only you see. Client-side only. Ported from Nyx. |
| Logout Spots | Render | Marks where players were standing when they logged out, with name, health and how long ago. |
| Auto Safe Anchor | Combat | One press: places a respawn anchor where you look, charges it, puts a shield block between you and the blast, detonates it, then turns off. Ported from Mist. |
| Crystal Aura | Combat | Places and breaks end crystals on nearby opponents as fast as the server answers, sticking to one spot and placing obsidian only when it clearly pays off. Ported from Mist. |
| Anchor Aura | Combat | Runs the same sequence by itself at whichever nearby spot hurts an opponent most, never inside anyone's hitbox and never at a spot that could kill you. Ported from Mist. |

Map, Stash ESP, Player Blocks, Old Chunks and Logout Spots are ported from Nyx and adapted to
1.21.11. Compared with Nyx: the minimap is a HUD element you can place anywhere, there are personal
waypoints (on the maps and in the world), finds are listed in the map's sidebar where you can
highlight any of them in the world, world labels are drawn as crisp HUD text, and Stash ESP's
"Silent Mode" confidence scoring became a plain "Dim lone blocks" option. Nyx's team map sharing,
navigator integration and editable block lists were not ported.

## Combat modules and simulated input

Auto Safe Anchor, Anchor Aura and Crystal Aura never build interactions or packets themselves. Everything they do
is simulated input that the game then handles exactly like a player's:

- the hotbar slot is picked by pressing its number key (`input/InputSim`),
- the camera turns through synthetic mouse movement fed into Minecraft's own mouse handling, so
  the turn goes through your sensitivity, comes in whole mouse steps and respects inverted mouse
  settings (`input/MouseSim`, `mixin/MouseHandlerMixin`),
- crouching is the sneak key held down,
- every click is the use key (or, to break a crystal, the attack key), so the game sends its own
  packet for whatever its own crosshair hits.

Before clicking they predict the crosshair with the same raycast the game uses (`input/Crosshair`)
and pick a point on the block face that the crosshair can actually reach, so a snow layer, plant,
berry bush, item frame or crystal in the way is aimed around instead of being clicked by mistake.
A click only goes out once the camera has been still long enough that the server already has the
view the click is made with. Blocks whose right-click does something else (containers, doors,
crafting stations, berry bushes, cake, beds...) are never used as a support to place against.

Both share one sequence (`module/combat/AnchorSequence`): place, charge, shield, detonate, check it
went off. Every part of it can be tuned:

| Group | Settings |
| --- | --- |
| Timing | Start delay; delays after placing, charging and shielding; delay when no shield is placed; swap delay (or click in the same tick as the slot switch); settle time before a click; crouch and stand delays; random extra ticks with a uniform or Gaussian spread; detonate retry; charge and detonate attempts; timeout. Delays default to 0 and the sequence ends the moment the anchor is gone. |
| Camera | Max and min turn speed, easing, how much the path curves, overshoot chance and amount, aim point on a face (nearest, center or random) and edge margin, holding the camera against your own mouse, looking back afterwards with its own delay and speed. |
| Shield | Always, only when the blast would reach you (with a damage threshold), or never; shield block (glowstone, obsidian, or any full block in your hotbar); crouch-placing against the anchor; how far to search for a shield spot; retries; whether to detonate when the shield never went down. |
| Items | Detonate with an empty slot, a safe item or a fixed slot; swap back to your slot. |
| Safety | Anti suicide with a health margin (checked with an explosion damage estimate right before detonating); wait or cancel while you're eating or blocking; only start on the ground; warnings. |
| Render | Outline the anchor and shield spots while it runs, with their colors. |

Auto Safe Anchor adds using an anchor you're looking at and repeating (with a delay) while its key
is held. Anchor Aura adds target range, place range, target priority (closest, lowest health, most
damage), ignoring unarmored players or names, min damage, max self damage, how much self damage
counts against a spot, a lethal override, a lower min damage against low-health opponents, re-arm
delay and randomness, per-spot cooldown, search interval, an optional key to hold for it to run,
and a chance to take a nearly-as-good spot so cycles don't all look the same. "Target mobs" lets
you try settings out on a dummy. Like Crystal Aura it sticks to one spot (see below), and it swaps
back and looks back only once no new anchor has followed for a moment, not after every anchor.

**Trying them in singleplayer.** Crystal Aura and Anchor Aura only go for other players unless
"Target mobs" is on, and a singleplayer world has none, so turn it on and use a hostile mob (a zombie
with no AI makes a good dummy). Turning either on in singleplayer without it says so in chat, and
the module list shows why either is idle ("no players: turn on Target mobs", "no crystals", "no
glowstone in your hotbar", "no good spot"...).

The three share the camera, hotbar and keys through one lock (`input/InputLock`): only one of them
clicks at a time, the automatic ones wait for each other, and pressing Auto Safe Anchor's key takes
over from whatever is running.

### Crystal Aura

Ported from Mist's CrystalAura, which places obsidian for anyone you knock up and crystals on any
obsidian near an opponent. Breaking uses the attack key on the crosshair, so a crystal is only
placed where you could hit it afterwards (entity reach, 3 blocks in survival).

It locks onto one spot and keeps placing and breaking crystals there. The view that places a
crystal on top of a block also hits the crystal standing on it, so once the camera is on the spot
nothing has to turn: it breaks a crystal the tick it appears and places the next the tick the old
one is gone, about one crystal per round trip to the server. Delays all default to 0.

It only moves to another spot when the current one genuinely stops working, instead of chasing the
best spot every crystal (an opponent knocked up above you otherwise drags it around the whole time):

- the spot would do less than **Keep above** damage (4),
- the opponent is more than **Max angle** away from it as seen from you (45°),
- another spot does at least **Switch if better by** more damage (5), checked every
  **Recheck every** ticks (10),
- or it becomes unsafe, out of reach, or blocked.

Turning **Stick to spot** off goes back to the best spot every crystal.

Places less obsidian:

- An existing obsidian or bedrock base always wins unless a new block's crystal would do at least
  **Min gain** more damage (2 by default).
- A new block is only placed if the crystal still does enough damage where the opponent will be by
  the time it goes down (their movement is extrapolated over the whole obsidian, crystal and break
  sequence), and it's checked again right before the crystal is placed.
- Because it sticks to a spot, one block is reused for many crystals.
- A cap on blocks per time window (3 per 10 s by default), the choice between never, after a hit
  and when needed, and avoiding the opponent's path and landing spot.
- A spot where a placement failed is skipped for a while instead of retried.

Less annoying to use:

- With crystals in the offhand it places them without switching away from your weapon.
- It swaps back and (optionally) looks back only once there's been no crystal for a few ticks, not
  after every crystal, so the hotbar and camera don't flick back and forth mid-fight.
- It skips spots that need a big camera turn (75° by default), and gives the mouse back right
  after each click.
- It pauses while you eat or block, while you hold the use key, and (by default) unless you hold a
  weapon, an end crystal or nothing, so it never fires while you build.
- Flicking the mouse while it turns makes it drop the crystal and stay out of the way briefly.
- It can run always, only for a moment after you hit someone with a weapon, or only while a key
  is held.
- It breaks crystals already standing near the opponent when that's worth it (one click instead of
  two), with Anti weakness switching to a sword when a fist wouldn't break them.

Mist's silent rotations, burst/idle pacing, server presets and session fatigue were not ported.
Timing, camera movement, damage limits and targeting have the same kind of settings as the anchor
modules.

### Damage estimates

Damage is estimated with vanilla's explosion formula, including block exposure, armor, toughness,
Resistance and Protection/Blast Protection (`util/ExplosionDamage`). Mist's silent rotations,
session fatigue and server presets were not ported; these turn the real camera.

## Seed Cracker (proof of concept)

Turn it on and travel: it watches the overworld chunks the server sends you for structures whose start
chunk can be read exactly from their blocks, keeps them per server in `.minecraft/erebus/seeds/`, and
cracks the seed by itself once it has enough. Nothing is sent to the server.

- **What it reads.** Desert pyramids (the blue terracotta in the middle of the floor), jungle temples
  (the lever puzzle's sticky pistons), swamp huts (the cauldron next to the crafting table) and buried
  treasure (a chest at the same spot in its chunk, under sand). Each marker has to sit exactly where
  one of the structure's four orientations puts it, so player builds don't count.
- **Igloos, villages and pillager outposts** are matched against the game's own templates: the igloo's
  dome, a village's meeting point (the bell, well or fountain in the middle, any biome, zombie villages
  too) and an outpost's watchtower. The game places all three from their start chunk's corner, so for
  each possible start chunk there's only one place to look per rotation (two for an outpost's tower),
  and a match there gives the start chunk exactly. Natural ground (grass, dirt, sand, snow) and the
  bell, which players take, don't count towards the match. An outpost also passed an extra 1-in-5 roll,
  which is checked too.
- **The End's pillars.** The game shuffles the ten pillars' heights with a 16-bit number from the seed.
  Each pillar was topped with bedrock at its original height, which can't be mined in survival, so the
  bedrock gives its exact slot even after the obsidian around it is gone (and respawning the dragon
  rebuilds the pillars exactly). Where the bedrock is gone too, the pillar's width under the island
  (which nobody mines) still narrows it down: all ten by bedrock pin the number exactly (16 bits), all
  ten by width alone leave about 5 options (13.7 bits). If a server's End turns out to have its own
  seed, cracking tries again without the pillars and says so.
- **Low 48 bits.** Structures pick their chunk inside a region with Java's 48-bit random generator,
  seeded from the seed, the region and a per-structure salt. Each temple, hut or igloo position fixes
  some low bits outright (24 = 8 x 3 positions per axis: 3 bits per axis), a village 1 bit per axis
  (26 = 2 x 13), so the low 20 bits are brute-forced against all of them, then the remaining 28 bits
  for each survivor (`seed/SeedSolver`).
- **Top 16 bits.** Structures never use them. The server sends a hash of the full seed (the first 8
  bytes of its SHA-256, which the client uses for biome blending); all 65,536 options are hashed and
  compared.
- **Checked twice.** The result is re-checked against every recorded structure with the game's own
  structure placement code before it's reported.

**Misread structures.** If no seed fits every structure, it looks for one that fits all but up to 3 of
them (needs a few more structures than an exact fit), takes the first whose top bits match the
hashed seed, and sets aside the structures that don't fit, saying which. Once the seed is known, a
newly recorded structure that doesn't fit it is ignored straight away. If you already know a
server's seed, enter it as **Known seed** instead.

How much it takes depends on the mix. In testing: 2 pillager outposts, 1 village and the End pillars
(read by width only) gave the seed in under a second; 4–6 villages with a couple of temples or
igloos usually do; temples alone vary by seed, from about 6 to 8. The structures' information
partly overlaps (what they say about the seed's high bits is correlated), so the module waits until
it estimates there's enough, and the hashed seed settles whatever few candidates are left.

**Why not dungeons.** Since 1.18, features like dungeons are placed with a different random
generator (Xoroshiro) seeded from the full 64-bit seed, which can't be run backwards the way the
structures' generator can, so a dungeon can only confirm a seed, not help find it. Dungeons in chunks
generated before 1.18 would work, but that needs the version that generated them. It's a proof of concept for vanilla settings:
servers that change structure salts (`spigot.yml`) or hide the hashed seed need more work. Actions in
the dashboard: Crack now, Copy seed, Forget this server.

## Map

Press M. Drag to move, scroll to zoom toward the cursor, Space to jump back to yourself, Tab for
the sidebar, G for the chunk grid. Right-click anywhere to add a waypoint, center or copy
coordinates; right-click a base, stash or structure to highlight it in the world, mark it
explored, or forget it. The sidebar lists everything found (closest first) and your waypoints,
which you can rename in place.

### Predicted structures

With the world seed known (always in singleplayer; in multiplayer from Seed Cracker, found or entered
as Known seed), the map can show where structures generate, as outlined markers. Turn on **Show
predicted** in the Map module (or the Predicted chip on the full map) and pick which kinds: villages,
temples, huts, igloos, outposts, monuments, mansions, ancient cities, trail ruins, trial chambers,
ruined portals, shipwrecks, ocean ruins, strongholds, fortresses, bastions and end cities.

They're worked out with the game's own world generation code, built on the client from its built-in
registries and the vanilla tag and template files in the game jar: structure placement and frequency
rules, the biome sources and terrain noise, each structure's own placement checks (biome, and terrain
height where it needs flat or high ground), the stronghold rings, and where a jigsaw structure's start
piece lands. In testing every prediction checked matched `/locate` (villages, desert pyramids,
outposts, monuments, trial chambers, strongholds). Predictions are only right for servers with
vanilla world generation settings, and a structure that's already been found shows as found instead.

### Minimap

A square map with a light frame (or your theme's accent, or none), N/E/S/W on its edge (turning with
the map when **Rotate with you** is on), mob dots (hostile red, others green), other players' names,
waypoints off the map pinned to its edge, and lines underneath for coordinates, biome, facing and
Nether/Overworld coordinates. **Zoom** goes from 0.125x to 8x, and **Zoom in key** / **Zoom out key**
step through it (the zoom shows briefly in the corner).

### Structure detection

Block counts only pick out candidates; nothing is marked until the structure's actual layout is
confirmed, so player builds made of the same blocks (prismarine farms, nether brick or purpur
bases, decorative portals, TNT traps) aren't flagged:

| Structure | Confirmed by |
| --- | --- |
| Desert pyramid, jungle temple, swamp hut | A marker (the floor's blue terracotta, the lever puzzle's sticky pistons, the cauldron by the crafting table) at the exact spot in its start chunk that the game's code puts it, for one of its four orientations. |
| Stronghold | The portal room's full ring of 12 end portal frames. |
| Ocean monument | Dark prismarine and sea lanterns only at the monument's height (y 39-61) and inside its 58-block square, under water at sea level. |
| Nether fortress | Its blaze spawner, or a stretch of its standard bridge: a 5-wide double deck with walls on both edges and open air above. |
| End city, bastion, ruined portal | A match against the game's own structure templates (read from the game files): most of a piece's blocks in exactly the template's positions, in one of its 8 rotations and mirrorings. Ruined portals also need their stone-brick trim in place and no lit portal. |
| Trial chamber, ancient city, end gateway, geode, archaeology | Blocks that can't be obtained in survival (vaults and trial spawners, reinforced deepslate, gateways in bedrock, budding amethyst, suspicious sand and gravel). |

Large structures (trial chambers, ancient cities, fortresses, bastions, end cities) are seen piece by
piece, and their pieces can be 100+ blocks apart. When the seed is known (singleplayer, cracked, or set
as Seed Cracker's Known seed), each one's marker goes on the structure's start, worked out from the
seed, so every piece of it gives the same marker. Without the seed, a second trial chamber marker
needs to be at least 160 blocks from the first.

The Map module's dashboard page has **Rescan structures** (check loaded chunks again) and **Forget
structures** (drop every structure marker on this map and rescan), for clearing markers left by
older versions.

## Freecam

Detaches the camera so you can fly it around (WASD, jump and sneak for up and down, sprint for
2.5x speed) and look with the mouse, while your player stands still. It only changes what's
rendered and where your movement keys and mouse go: nothing about your player's position,
rotation or packets changes, so the server sees you standing where you are.

- **Freeze player**: your movement keys stop reaching your player, as if you let go of them.
- **Block clicks**: attack and use clicks do nothing while the camera is out, so you don't hit or
  use whatever your real player happens to be looking at.
- **Keep entities visible**: players, mobs and items near your player stay drawn however far the
  camera goes.
- **Spectate mode**: the camera stays inside loaded chunks and within a distance of your player,
  and follows you if the server moves you.
- It turns off when you take damage (optional), and pauses the combat modules while it's on.

Nyx's silent rotations and movement-packet freezing were left out (they change what's sent), and
the player is no longer pinned in place against knockback, which would put the client out of step
with the server.

## Font

Erebus Sans is the client's UI font: a renamed derivative of [Geist](https://vercel.com/font) by
Vercel, subset for the client, with tabular digits by default (so numbers on the HUD don't
jitter) and a set of UI icons added in the Private Use Area. It is drawn by the client's own
renderer, which rasterizes glyphs with stb_truetype at the exact on-screen pixel size, so text stays
sharp and smoothly anti-aliased at every GUI scale. It can be switched off in Theme. Rebuild it
with `tools/build_font.py`. Licensed under the SIL Open Font License 1.1 (`erebus/font/OFL.txt`).

## Configuration

Settings are saved to `config/erebus/` when you close the dashboard or the game:

- `theme.json`: base preset, colors and effects
- `hud.json`: element visibility, positions and settings
- `client.json`: client preferences
- `modules.json`: which modules are on, their keybinds and settings

Maps live in `.minecraft/erebus/maps/<server>/<dimension>/`: 512x512-block PNG tiles, markers and
waypoints.

## Adding modules

Extend `dev.erebus.client.module.Module`, add settings with `add(...)` (use `group("...")` to
start a new section), override `onPreTick` (runs before the game handles input, for simulated input), `onTick`, `onRenderWorld` (draw with Minecraft's `Gizmos`) or
`onRenderOverlay` (2D, with `Projection` for world positions), and register it in
`dev.erebus.client.module.Modules`. It then shows up in its category, in search, in the module
list HUD and in the keybind system, and its settings are saved automatically.

To preview the dashboard with placeholder modules (no behavior), run
`./gradlew runClient -PpreviewModules`, or start any client with `-Derebus.previewModules=true`.

## Project layout

```
src/main/java/dev/erebus/client/
  ErebusClient.java      entrypoint: keybind, HUD hook, ticking, saving
  Erebus.java            shared state (theme, HUD, modules, config)
  gui/                   UiScreen base, Ui context
    widget/              widget toolkit (buttons, switches, sliders, text field, color picker, ...)
    dashboard/           dashboard screen and pages
    hud/                 HUD editor screen
  hud/                   HUD element base, manager and built-in elements
  map/                   map tiles and markers (MapStore), waypoints, map renderer
  gui/map/               full-screen map
  theme/                 theme model and presets
  setting/               setting types shared by modules and HUD elements
  module/                module base class, manager and modules (world/, basefinding/, render/, combat/)
  render/                2D drawing primitives, world-to-screen projection
  render/font/           Erebus Sans renderer (stb_truetype glyph atlases) and icons
  input/                 simulated mouse and key input, crosshair prediction
  seed/                  seed cracking: structure scanner, solver, per-server data
  util/                  explosion damage estimate
  config/                JSON persistence
  notify/                toast notifications
tools/build_font.py      builds Erebus Sans from Geist
```
