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
- The dashboard remembers where you were: the page, the module (or HUD element, or message filter)
  picked on each page and how far each list and settings pane is scrolled, until the game closes.
- It opens with a fade and a little spring and fades away when closed; pages crossfade, the
  highlight glides to the page or module you pick, and a module's settings cascade in row by row.
  Turn animations off on the Theme page.
- **Messages** keeps everything the modules told you this session (finds, alerts, seeds,
  triangulated withers), newest first, grouped by module, with buttons to copy coordinates, the seed
  or the whole message. The sidebar shows how many came in since you last looked.
- **Client** has the menu scale, menu keybind and general preferences, the World tags settings and
  Also in chat.

### Messages and notifications

Nothing goes to Minecraft's chat. Every message appears as a notification in the corner (the
Notifications HUD element), wrapped over a few lines and staying up longer the longer it is, and is
kept on the Messages page. The Notifications element has the toast duration, message width and how
many lines of a long message to show. **Also in chat** on the Client page puts messages in chat too,
only for you.

### World tags

The labels floating in the world (Light Finder's lights, Activity Radar's spots, Stash ESP's bases,
Seed Cracker's structures, players, waypoints, logout spots, the combat modules' targets) all follow
the **World tags** settings on the Client page:

- **Tag size** (0.85x by default) and **Tag opacity**.
- **Shrink with distance**: far tags are drawn smaller, down to 60% at 160 blocks.
- **Fade near crosshair**: a tag right where you're looking fades, so it doesn't hide what you aim at.
- **Tag details**: the second part (distance, counts, how long ago) always, only for tags near the
  crosshair, or never.

Tags that would land on top of each other stack upwards instead, with a thin line down to the spot
each one marks.

Each module that draws tags also has its own **Tag size** (under Tags in its settings), on top of the
client-wide one, to make one module's tags smaller than the rest.

### HUD editor

Drag elements to move them. Positions snap to a 4px grid, to screen edges and to the horizontal
center; hold Alt to move freely. Arrow keys nudge the selected element (Shift for bigger steps).
Right-click or press Delete to hide an element. The toolbar gets out of the way while you drag;
press H to hide it entirely.

Each element is stored relative to the nearest screen edge, so layouts keep their place when the
window size or GUI scale changes.

With **Keep HUD elements apart** (Client page, on by default), an element that would cover another
one, or the hotbar, hearts, food and armor bars, the potion icons or the chat box, slides out of the
way: away from its screen edge first, then sideways, and back when there's room again. On a small
screen with everything turned on it goes where it covers the least. Notifications are drawn on top
of everything on a solid background.

Built-in elements: Watermark, Module list (modules slide in and out as you toggle them),
Coordinates (with Nether/Overworld conversion and facing), Server info (FPS, ping, speed), Nearby
players, Supplies (totems, crystals, golden apples, obsidian, XP bottles; enchanted golden apples
can be turned on too), Armor, Minimap, Finder legend and Notifications (drawn over the others), and:

| Element | What it shows |
| --- | --- |
| Compass | A heading tape across the top: directions and degrees sliding past as you turn, your waypoints and the bases and stashes found so far marked where they lie (with a name and distance when you face one, an arrow at the edge for those behind you). |
| Rotation | Exact yaw and pitch on a heading dial and pitch gauge, the nearest axis or diagonal and how far off it you are, green when you're on it and which way to turn: for lining up on a highway. |
| Hunger | Ten food segments with the hidden saturation laid over them in gold, the numbers, and when it matters: no regen below 18 food, no sprinting at 6. |
| Effects | Potion effects with their level and time left, each with a bar running down; good ones first, harmful ones tinted, ones about to run out blink. |
| Target | The player you're fighting (looking at, last hit, or nearest in range): face, health with the part just lost draining away and absorption in gold, distance, totem pops, totem in hand, armor wear. |
| Speed graph | Your speed over the last few seconds as a filled graph, with now, average and peak, in blocks/s or km/h. |
| World info | Day and time, moon phase, biome, block and sky light at your feet (and whether monsters can spawn there, or will at night), chunk, your spot in it and region file. |

The new elements start off; turn them on from the HUD page, or with a layout preset (PvP adds Target,
Effects and Hunger; Travel adds Compass, Rotation and Speed graph; Base adds Compass and World info).

## Modules

| Module | Category | What it does |
| --- | --- | --- |
| Map | World | Records a map of every chunk you load, per server and dimension, and recognises structures (trial chambers, ancient cities, strongholds, monuments, fortresses, bastions, end cities, temples, swamp huts and more), each confirmed against its real layout so builds aren't flagged. On by default. |
| Seed Cracker | World | Works out the world seed from desert pyramids, jungle temples, swamp huts, igloos, villages, pillager outposts, mineshafts, buried treasure and the End's pillars, including on Spigot and Paper servers that set their own structure salts (and works those salts out). |
| Stash ESP | Basefinding | Outlines and tracers to storage and other base giveaways (chests, shulkers, ender chests, furnaces, villagers, item frames, chest minecarts...). Alerts when an area looks like a base or a stash, marks it on the map and logs it to `erebus/finds.txt`. |
| Player Blocks | Basefinding | Highlights blocks the world never generates (concrete, shulkers, beacons, mineral blocks, redstone parts...) and building materials where they don't belong, skipping natural structures and biomes where they occur naturally. Lets Stash ESP tell bases from stashes. |
| Light Finder | Basefinding | Finds light nature doesn't explain: player-made lights and lit space underground or indoors, ignoring lava, glow lichen, Nether glowstone, mineshaft torches and the lights of villages and other structures. Flags lit furnaces, beacons, portals and lights above the Nether roof, and alerts when a light is placed nearby. |
| Activity Radar | Basefinding | Shows activity you can't see, with exact positions through rock: machines running (dispensers, pistons, redstone, spawners), chests opening, blocks broken or placed, doors and levers, minecarts rolling; triangulates withers, dragon deaths and End portals across the dimension. Alerts when someone is at work nearby. |
| Old Chunks | Basefinding | Marks chunks generated before 1.18 (Overworld) or 1.16 (Nether): old trails lead to old bases. |
| Freecam | Render | Flies the camera away from your player while your player stays put. Client-side only. Ported from Nyx. |
| Fullbright | Render | Lights everything up, like a permanent Night Vision only you see. Client-side only. Ported from Nyx. |
| Logout Spots | Render | Marks where players were standing when they logged out, with name, health and how long ago. |
| Player ESP | Render | Boxes, tracers and name tags on other players through walls, with health, distance, ping, held item, armor and totem pops. Ported from Nyx. |
| X-Ray | Render | Shows only ores, spawners, storage and the blocks you pick; dims ores that are probably fakes on anti-xray servers and outlines the real ones; shades caves, lava and water. Ported from Nyx. |
| XP Optimizer | Player | Throws experience bottles with a human click rhythm (wandering click speed, Gaussian jitter) and stops as soon as the XP on its way will finish repairing your mending gear. Hold right-click, or bind it to throw until repaired and turn off. |
| Auto Totem | Combat | Keeps a totem in your offhand with a random, ping-adjusted delay, by real key presses and cursor moves; Smart mode reacts to crystals, anchors, beds, falls and maces, not just low health. Ported from Nyx. |
| Auto Safe Anchor | Combat | One press: places a respawn anchor where you look, charges it, puts a shield block between you and the blast, detonates it, then turns off. Ported from Mist. |
| Crystal Aura | Combat | Places and breaks end crystals on nearby opponents as fast as the server answers, sticking to one spot and placing obsidian only when it clearly pays off. Ported from Mist. |
| Aim Assist | Combat | Pulls your view towards the opponent you're aiming near, on top of your own mouse movement: variable speed with a cap, Gaussian speed and aim-point randomness, friend/team/bot filters, prediction and projectile drop compensation. |
| Anchor Aura | Combat | Runs the same sequence by itself at whichever nearby spot hurts an opponent most, never inside anyone's hitbox and never at a spot that could kill you. Ported from Mist. |

Map, Stash ESP, Player Blocks, Old Chunks and Logout Spots are ported from Nyx and adapted to
1.21.11. Compared with Nyx: the minimap is a HUD element you can place anywhere, there are personal
waypoints (on the maps and in the world), finds are listed in the map's sidebar where you can
highlight any of them in the world, world labels are drawn as crisp HUD text, and Stash ESP's
"Silent Mode" confidence scoring became a plain "Dim lone blocks" option. Nyx's team map sharing,
navigator integration and editable block lists were not ported.

## Combat modules and simulated input

Auto Safe Anchor, Anchor Aura, Crystal Aura and Aim Assist never build interactions or packets themselves. Everything they do
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
with no AI makes a good dummy). Turning either on in singleplayer without it says so in a message, and
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
- Flicking the mouse away from where it's aiming (net, over 150 ms) makes it drop the crystal and stay
  out of the way briefly. Following the opponent doesn't count: movement towards the spot is ignored,
  and back-and-forth tracking cancels out. Right after you hit someone it takes half as much again,
  and the turn to the spot holds the camera even with Hold camera off, so the click lands while
  you're still following them.
- It can run always, only for a moment after you hit someone with a weapon, or only while a key
  is held.
- It breaks crystals already standing near the opponent when that's worth it (one click instead of
  two), with Anti weakness switching to a sword when a fist wouldn't break them.

Mist's silent rotations, burst/idle pacing, server presets and session fatigue were not ported.
Timing, camera movement, damage limits and targeting have the same kind of settings as the anchor
modules.

### Aim Assist

Helps your aim rather than aiming for you. While an opponent is inside the FOV cone around your
crosshair, the view is pulled towards them by adding mouse movement to your own each frame (the same
synthetic mouse path as the other combat modules: through your sensitivity, in whole mouse counts).
It never holds the camera, and it steps aside while another module is turning it.

- **Speed.** `Base speed + Variable speed × degrees off target`, so it pulls harder when you're far
  off and eases in as you close on them, never above **Speed cap**. It ramps in over **Ramp up**
  after picking a target so it never snaps, drops to **On-target strength** once your crosshair is on
  their hitbox, and has separate **Horizontal** and **Vertical** strength.
- **Gaussian.** The speed wanders around its value as a smooth Gaussian process
  (Ornstein–Uhlenbeck): **Speed spread** is its standard deviation, **Speed smoothness** how slowly
  it drifts. The point it aims at drifts inside the hitbox the same way (**Point spread**, **Point
  smoothness**), so it doesn't track one fixed pixel.
- **Aim at.** The point of the hitbox nearest your crosshair (most natural), or head, chest, body or
  legs. **Prediction** leads a moving target by a few ticks of their movement, to cover ping.
- **Projectile aim.** With a bow being drawn, a charged crossbow or a trident being thrown, it aims
  where the shot will land: the arrow's drag and gravity are simulated to find the pitch, and the
  target's movement during the flight is led too. (In testing, a husk 22 blocks away was hit with a
  plain draw and release.)
- **Targets.** Players (and hostile mobs with **Target mobs**) within **Range**, picked by
  crosshair angle, distance or health, kept with **Sticky target** and switched no faster than
  **Switch delay**. Skips **Friends** (a name list), teammates (scoreboard team or the same name
  color), players missing from the tab list (**Ignore bots**: NPCs and the fake players some
  anticheats spawn to catch aim helpers), creative players, optionally invisible or naked ones, and
  anyone behind blocks unless **Through walls** is on.
- **Activation.** **Weapons only** (sword, axe, mace, trident, or a ready bow/crossbow), **Only while
  attacking**, and **Only while aiming**: it only helps while you're moving the mouse yourself, above
  **Mouse threshold**. **Flick to release** lets go of a target when you whip the mouse away from
  them. It pauses while eating, in menus and in Freecam.
- **Showing it.** **Show FOV** draws the cone around your crosshair. With Better visuals: brackets
  around the target, the point it's aiming at, a guide line from your crosshair, and a label with
  their name, health, distance (and flight time for projectiles), pink while tracking and green
  once your crosshair is on them.

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
- **How the first piece came out.** The game rolls each structure's first piece from the seed and its
  chunk: the rotation of an igloo, village or outpost, and which town centre a village gets. Both are
  recorded and checked, about 4 more bits per village and 2 per igloo or outpost. Abandoned (zombie)
  villages aren't a separate structure: they're ordinary villages whose centre came out as a zombie
  variant (about 1 in 51 in plains), placed by the same rule, so they count like any other. Their
  centres differ from the normal ones by only a few path blocks, so a centre counts as "this design,
  normal or abandoned".
- **Mineshafts.** Every chunk rolls a 1-in-250 chance for one, from the seed and the chunk's
  coordinates, and the game starts each with a room whose corner is exactly 2 blocks into the start
  chunk: a flat band of cave air 3 high over 8 to 13 blocks each way, with a dome of air on top. A
  room counts when that band is there at the corner, on a mostly solid floor, with walls just outside
  its near sides, the dome open above its middle (a room a player dug has a flat ceiling there) and
  mineshaft woodwork, rails or cobwebs around it. Every mineshaft has exactly one such room, so
  several rooms in what looks like one huge network are separate mineshafts whose corridors ran into
  each other (they sprawl up to about 10 chunks, so that's common). The room's width along x and z
  (8 to 13 each) is rolled from the seed too, so it's measured and checked: a room that isn't really
  a mineshaft's start has the wrong size 35 times in 36, and a real one adds about 5 bits. (Chunks
  from before 1.18 rolled one more number first, so there only the mineshaft itself counts.) The client gets the blocks underground with every chunk, so
  you don't need to go down. Each is worth about as much as a village, they're everywhere, and the
  same rule placed them in chunks from 1.12 on. They only check seeds rather than narrow the low bits,
  so they need at least one temple, igloo, outpost, a few villages or the End pillars alongside.
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

**Misread structures.** If no seed fits every structure, it first allows up to 2 misread mineshafts
(or other clues that only check a seed: buried treasure, an outpost's roll, how a first piece came
out), which costs little, then looks for a seed that fits all but up to 3 of the structures (needs a
few more structures than an exact fit), takes the first whose top bits match the
hashed seed, and sets aside the structures that don't fit, saying which. Once the seed is known, a
newly recorded structure that doesn't fit it is ignored straight away. If you already know a
server's seed, enter it as **Known seed** instead.

How much it takes depends on the mix. In testing: 2 pillager outposts, 1 village and the End pillars
(read by width only) gave the seed in under a second, and so did 1 outpost, the End pillars and 3
mineshafts; 4–6 villages with a couple of temples or igloos usually do; temples alone vary by seed,
from about 6 to 8. The structures' information partly overlaps (what they say about the seed's high
bits is correlated, and some sets of positions pin down the low bits much less than usual), so the
module waits until it estimates there's enough, checks the real size of the search before starting
it (and asks for another temple, igloo or outpost if it would take more than a minute or so), and
the hashed seed settles whatever few candidates are left.

**Why not dungeons.** Since 1.18, features like dungeons are placed with a different random
generator (Xoroshiro) seeded from the full 64-bit seed, which can't be run backwards the way the
structures' generator can, so a dungeon can only confirm a seed, not help find it. Dungeons in chunks
generated before 1.18 would work, but that needs the version that generated them. Actions in the
dashboard: Crack now, Copy seed, Forget this server.

### Servers with their own structure salts

Spigot and Paper let a server replace each structure type's salt (`seed-village`, `seed-desert`,
`seed-igloo`, `seed-jungle`, `seed-swamp`, `seed-outpost`... in `spigot.yml`, plus Paper's
`seed-buriedtreasure` and `seed-mineshaft`), a common way to protect a seed. Then where each type's
structures land only says something about *seed + that type's salt*, so types don't agree with each
other and nothing fits the game's own settings. With **Custom structure salts** on (the default), the
cracker then tries again with each type on its own unknown salt (`seed/SaltSolver`):

- **Anchors.** Those settings never touch the End pillars, how a structure's first piece came out
  (the rotation of an igloo, village or outpost and a village's centre), an outpost's extra roll, or
  a mineshaft's room size (or, unless `seed-mineshaft` is set, where mineshafts are). Those tie the
  structures to the seed itself.
- **Per type.** Each type with enough finds is cracked on its own as *seed + salt*.
- **Listing seeds.** The End pillars fix 16 of the seed's low 32 bits. With them and one cracked type,
  each pillar-allowed low half gives exactly one seed (about 65,000 to check). With the pillars alone
  it's about 4 billion, checked in 15–25 seconds. The anchors drop almost all of them, and the
  server's hashed seed picks the full seed from what's left, as usual.
- **Salts.** With the seed known, each type's salt follows from its finds (about 4–5 of a type pin it
  down), and is worked out again as more are found. Chat lists which types have custom salts and the
  setting each comes from, whether mineshafts have their own salt, and whether the hashed seed looks
  fake.
- **Predictions.** Predicted structures on the map use the salts found. Types whose salt isn't known
  yet aren't predicted (strongholds are, they don't use a salt).

**What it takes.** The End pillars are effectively required. A type's own positions barely pin down
the top 13 or so bits of its *seed + salt* (pyramids, huts, igloos and outposts pick their spot with
`nextInt(24)`, villages with `nextInt(26)`, and values 3·2^35 or 13·2^35 apart place them almost
identically), so even a dozen of one type leave dozens of possibilities, each worth 4 billion seeds
without the pillars. Beyond the pillars, about 20 bits of anchors: 2 mineshafts with their room sizes,
or a handful of villages, igloos and outposts. In testing, on a server whose datapack changed the
salts of villages, pyramids, igloos, huts, jungle temples and outposts, the End pillars, 7 villages,
an igloo, 2 outposts, a swamp hut and 3 mineshafts gave the exact seed and the village salt. The
outpost salt followed after 3 more outposts. Offline it also found the seed with Paper's mineshaft
salt changed (from the rooms' sizes).

Servers whose terrain doesn't come from Minecraft's own generator can't be cracked this way.

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

## Better visuals

Purely cosmetic extras drawn on top of what each module already shows. They never change what a
module does, what it sends or when. Turn them all off with **Better visuals** on the dashboard's
Client page, or one module at a time with its own **Better visuals** setting (in its Visuals
group). Both are on by default.

| Module | What it adds |
| --- | --- |
| Auto Safe Anchor, Anchor Aura | The step the sequence is on, colour-coded at the anchor (placing, charging with its four charges lit up, shielding, detonating), the shield block still to come, the blast radius with a ripple as it goes off, an arc to where it's aiming, and the opponent it's for with the damage the spot should do. |
| Crystal Aura | Brackets on the locked spot, the crystal with points circling it, the blast radius, the obsidian it's placing, and the opponent with the damage to them and to you. |
| Stash ESP | A shape for each kind of find: brackets and a ring over the lid of chests and other storage, a spinning cage around spawners, sparks circling ender chests and enchanting tables, a pulsing ring on shulkers, halos over villagers, rising embers over furnaces. Beams and labels over each base or stash it marked. |
| Player Blocks | The closest placed blocks glow softly instead of a flat fill. |
| Aim Assist | Brackets around the target, the point it's aiming at, a guide line from your crosshair and a label with name, health and distance (and a projectile's flight time); pink while tracking, green on target. |
| Old Chunks | Posts at the corners of nearby old chunks, with a slow sweep across them. |
| Logout Spots | A ghost of the player where they logged out, with a halo. |
| Light Finder | Lights glow with a soft halo, marked areas get a beam and a label with what was found, and new lights pulse. |
| Activity Radar | Events ripple out as they happen, busy machines pulse, and bearings to global events are drawn as long rays. |
| Player ESP | A ring under each player that shows their health, and a gold burst when someone pops a totem. |
| Freecam | While the camera is out: a halo over your body, brackets around it and a line back to it. |
| Map | A beam and a label over each structure found nearby. |
| Seed Cracker | The start chunk of each structure it recorded nearby, outlined with what it is, and a label on each End pillar with what it read. |

## Light Finder

Light is one of the clearest signs of a player: almost nothing underground gives off light on its own, and
nobody digs a tunnel or a bunker without lighting it.

- **Lights.** Every light-giving block in the chunks you have loaded is sorted into natural or
  player-made. Natural: lava, fire, magma, glow lichen, glow berries, sea pickles, amethyst, sculk and
  the like; glowstone, shroomlight and lanterns in the Nether; End rods in the End; the wall torches
  mineshafts hang on their support beams. Lights natural structures also have (torches, lanterns,
  campfires, candles, sea lanterns, copper bulbs, End rods) are excused near villages, strongholds,
  trial chambers, ancient cities, bastions, End cities, monuments, outposts and mansions, recognised by
  their give-away blocks, illagers or the Map module's structure marks. Glowstone, froglights, jack
  o'lanterns and redstone lamps never generate in one, so they always count.
- **Light levels.** The client already has the block light of every loaded chunk. Air that's
  underground or under a roof and lit at least to **Min light** is counted unless something natural (or
  excused) explains it: light drops by one per block, so a source of brightness e only explains a spot
  lit to L within e − L blocks. A chunk is only judged once its neighbours have been read too, since
  light crosses chunk borders. A large unexplained lit volume is a base or a tunnel network even when
  the lights themselves are few or hidden.
- **What stands out.** Lit furnaces, smokers and blast furnaces (they go out on their own, so someone
  was smelting), beacons, conduits, lit nether portals, charged respawn anchors and enchanting tables
  count for more, and any light above the Nether roof is flagged on its own.
- **Live.** When the server tells you a light was placed (or a furnace lit) more than a few blocks from
  you, you get an alert, and the light pulses with a label for a minute: someone is working there right
  now, often out of sight underground.
- **Marked and alerted.** Chunks with enough lights (**Min lights per chunk**, beacons and furnaces
  counting for several) or enough unexplained lit space (**Min lit blocks per chunk**) are marked: the
  lights get outlines in their kind's color and the lit space is shaded. Neighbouring marked chunks form
  an area; a big enough one ("Lit area", "Active base" with a lit furnace, "Nether roof base") is
  alerted, written to `erebus/finds.txt` and marked on the map like Stash ESP's finds.
- **Filters.** Each kind of light can be turned off, and **Ignore near spawn** and **Ignore highways**
  skip the places on old anarchy servers where everything is player-made or travellers light the way.

In testing: a sealed room underground at y 9 with six torches and a lit furnace was found as an
"Active base" (7 lights, 171 lit blocks) from 16 blocks away through the rock, while the lava caves and
the mineshaft around it, the spawn area with a trial chamber under it and a desert village at night
weren't flagged; a glowstone block placed 18 blocks away raised a live alert, a torch placed in the
village didn't.

## Activity Radar

The server tells your client about a lot of what happens around you, with exact coordinates and
whether or not there's rock in the way. Activity Radar listens and puts it on screen:

- **Within 64 blocks** (level and block events): dispensers and droppers firing, crafters, pistons,
  note blocks and bells, spawners spawning (a player is within their range), chests, ender chests and
  shulker boxes opening, anvils, grindstones and smithing tables being used, End portal frames
  filled, and blocks being broken (with what was broken; leaves, crops and the like left out).
- **Anywhere you have loaded** (block changes, your whole render distance): redstone switching
  (powered rails, repeaters, comparators, lamps, observers, hoppers, dropper triggers, redstone wire),
  doors, trapdoors, gates and barrels opening, levers and buttons, and blocks being placed that the
  world doesn't place on its own (lava meeting water is left out). Daylight sensors, sculk and
  redstone ore, which switch by themselves, are ignored.
- **Minecarts** moving with nobody in them, as far as the server sends them.
- **Across the dimension**: a wither spawning, the dragon dying or an End portal opening is told to
  every player in the dimension, placed 32 blocks from them in its real direction. Each one is a
  bearing; a source that repeats (a wither farm) is placed where bearings taken from different spots
  cross, and marked on the map.

Activity close together is grouped into a spot with a label: what's going on, how many events a
second, how regular (a clock's frequency) and how deep underground. A spot where someone is at work
(storage opened, a block broken or placed, a door, lever or workstation used) is alerted as
"someone is there now"; a spot running faster than **Machine rate** is alerted as a machine. Anything
within **Ignore near you** of you is your own doing and left out.

**What mobs and the world do is left out**, so a spot means a person (or a machine someone built):

- blocks broken by mobs: a sheep eating grass, zombies and vindicators breaking doors, the wither, a
  ravager, silverfish coming out of stone, an arrow shattering a pot, a boat over a lily pad; and
  blocks that break or grow by themselves (flowers, saplings, mushrooms, vines, berries, frogspawn,
  sniffer eggs, snow and more, on top of leaves and crops);
- doors, trapdoors and gates opened by villagers, illagers, piglins or zombies, or flung open by a wind
  charge. Mobs are only sent to you so far out (less than your render distance on most servers), so
  past the farthest one you've seen, a door only counts with a player next to it;
- pressure plates and tripwires set off by a mob, an item or an arrow (and the lamps, doors and
  dispensers they set off, like a jungle temple's arrow trap), and the plate's release;
- wooden buttons and targets hit by arrows, levers and buttons hit by wind charges, lightning rods
  struck by lightning, bells rung by villagers or arrows, chests opened by copper golems;
- blocks an enderman puts down, an anvil or other falling block landing;
- a resting minecart shoved by a mob: a cart has to keep rolling for 4 blocks before it counts;
- a spawner spawning because of you: it needs a player within 16 blocks.

In testing: an observer clock driving a dropper in a sealed room 56 blocks underground was alerted
from 32 blocks away ("15 redstone, 2 dispenser, 1.7/s"); a door, a lever, a broken wall block and a
placed block from 30 blocks away each raised "someone is there now"; a wither spawning 60 blocks
away gave its direction, and a second one heard from another spot was placed at exactly where it
spawned; a minecart on rails was followed along its track.
Against the world: 16 lambs grazing a pen 18 blocks away ate 21 grass blocks in a minute and
nothing was flagged; a door opened next to a villager, a door a wind charge flung open, a door opened
next to a husk, a plate with an item or a husk on it (and the lamp it lit), a wooden button an arrow
was shot into, a cart shoved across the floor and a falling anvil landing were all left out, while
the same door, button and a broken block with nobody near were still flagged.

What reaches you depends on the server: these are vanilla ranges, and plugins can trim them (a server
can also turn global sound events off).

## Player Blocks: what counts as natural

Player Blocks flags two things: blocks the world never generates (concrete, shulker boxes, mineral
blocks, redstone parts...) and building materials (planks, glass, terracotta, packed ice, snow
blocks...) where nature wouldn't put them. To keep the second list honest it checks, for each block:

- **The biome, and the biomes a few blocks around it.** Each block's biome is picked with some jitter
  at biome borders, terracotta bands and snow run a little past them, and icebergs spill out of
  frozen oceans onto the shore, so terracotta counts as natural within 8 blocks of a badlands biome
  and ice within 16 of a cold one. Mossy cobblestone is natural in old-growth taiga (its boulders),
  cobblestone touching lava or water is natural (they flowed together).
- **Whether the chunk's surroundings have loaded.** The client reads a chunk it hasn't got as plains,
  and a village's outer houses can be in view while its bell isn't, so building materials are only
  judged in chunks with everything within 2 chunks loaded (at the edge of your render distance they
  wait until you come closer).
- **Natural structures, at the right height.** Villages, mineshafts (by their supports: fences two
  high with planks across, which also finds badlands mineshafts at the surface), strongholds,
  ancient cities (deepslate bricks and tiles together), trial chambers, temples, igloos, ruins,
  bastions, End cities and mansions (by their illagers) excuse building materials around them, but
  only within 24 blocks of the height they were found at: a base on the surface above a mineshaft is
  still a base.
- **Never-natural blocks that structures use anyway**, found by reading every structure the game
  has: trial chambers' white and red concrete, ancient cities' redstone blocks, comparators, targets
  and note blocks, mansions' diamond and lapis blocks, damaged anvils in mansions and trail ruins,
  bastions' quartz and End cities' ender chests. Each is only excused near its own structure.

In testing on a dedicated server, the badlands, eroded and wooded badlands (and a badlands
mineshaft), frozen ocean icebergs, ice spikes, snowy slopes over a trial chamber and old-growth
taiga showed nothing, where before they showed hundreds of terracotta blocks, packed ice and
concrete; orange terracotta, packed ice and snow blocks placed in plains above a mineshaft were
flagged.

## Player ESP

Every other player within range gets a box (or just its corners), a tracer and a name tag that stays
sharp at any distance (drawn as HUD text). The tag can show health, distance, ping, what they hold
(and whether there's a totem in their offhand), how many armor pieces they wear (and if one is about to
break), totem pops this life and coordinates. Colors go by distance (red close, green far), health or
their team color; names on the **Friends** list get their own color, and **Ignore bots** skips players
missing from the tab list. Pops are counted from the event the server sends when a totem is used, and
reset when the player dies. With Better visuals, a ring under each player shows their health and a gold
burst marks a pop. Ported from Nyx; Nyx's on-screen player list is the existing Nearby players HUD
element.

## XP Optimizer

Throws experience bottles for you and stops the moment the XP already in the air and on the ground
will finish repairing your mending gear (armor you wear and what you hold), so no bottle is wasted.
Every bottle is a real press of the use key; looking down is simulated mouse movement.

- **Activation**: *Hold use* takes over while you hold right-click with bottles (looking down, by
  default): it does the clicking and holds back the game's own repeat, and once your gear is covered
  holding longer throws nothing more. *While enabled* (bind it to a key) picks the bottles from your
  hotbar, looks straight down, throws until your gear is repaired, then puts your slot and view back
  and turns itself off, with a message saying how many it threw.
- **Click rhythm**: the click speed wanders between **Min CPS** and **Max CPS**, picking a new value
  to drift towards every **Speed change** seconds, and every gap gets **Jitter** (Gaussian, in ms) on
  top. **Max per tick** caps how many bottles go in one game tick.
- **Stop when repaired** counts what's on its way two ways: the bottles and orbs it can see, and its
  own tally of what it threw (7 XP a bottle on average) minus what's been absorbed since. The
  orbs alone undercount, because the game merges orbs near each other and doesn't tell the client by
  how many. **Extra bottles** throws a few more on purpose.
- **Better visuals**: a repair bar under the crosshair with your gear's durability, the XP on its
  way, how much is still needed and the current click speed.

In testing, a diamond chestplate and leggings needing 360 XP took 52 bottles (of 64) at an average
of 10.7 clicks a second with gaps from 33 to 190 ms, were fully repaired, and 4 XP ended up in the
XP bar; slot and view went back to where they were.

## Auto Totem

Keeps a totem in your offhand, as you would by hand (ported from Nyx's Smarter AutoTotem):

- **Real input only.** With a totem in the hotbar: its number key, your swap-hands key one tick later
  (the slot change has to reach the server first, or it would swap what you held before), then back to
  your slot. Otherwise: your inventory key, the cursor moved over a totem (eased, landing somewhere on
  the slot rather than its exact centre), your swap-hands key on it, and the inventory key again, each
  after its own short random pause. Keys go through the game's own key handler, so the inventory gets
  them exactly as from the keyboard.
- **Timing.** A new random reaction time for every refill (min to max), with part of your ping taken
  off (your key press reaches the server that much later anyway), a separate shorter one right after a
  pop, and no delay at all below **Instant below**.
- **Smart mode** only swaps a totem in when it's needed: an empty offhand, low health, a pop, or danger.
  Danger is an end crystal, a charged respawn anchor (outside the Nether) or a bed (outside the
  Overworld) close enough to take most of your health by the game's own explosion maths, a fall that
  could, or a player within 5 blocks coming down at you with a mace. Danger skips the delay and takes
  the fastest way.
- **Where from.** Inventory first keeps hotbar totems for emergencies; **Keep hotbar slot** keeps a spare
  in a chosen slot, refilled while it's safe (nobody within 12 blocks, standing still), so the fast way
  is ready when it matters.
- **Pop alerts** say how many totems are left.

## X-Ray

Hides every block except the ones you want, through the chunk meshes (hidden blocks have nothing to
draw and don't cover their neighbours; the ones shown are drawn at full brightness). Ported from Nyx.

- **What shows.** The ores of the dimension you're in (each kind can be turned off), anything on the
  **Extra blocks** list (ids, comma separated), storage, blocks only players place (Player Blocks' list)
  and optionally bedrock. Water and lava can be hidden too, and Fullbright comes on with it.
- **Anti-xray awareness.** Servers running anti-xray (Paper's engine modes) send fake ores in place of
  buried stone and real ores only where they touch air. X-Ray checks each ore around you: ones with no
  air, water or lava next to them are dimmed (probably fake there), and exposed ones get an outline in
  their color (real even with anti-xray, and reachable from a cave). The module list shows how many of
  each are around.
- **Empty areas.** Caves, tunnels, lava and water underground are shaded so their shape shows through
  the rock (below **Max height** in the Overworld).

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
