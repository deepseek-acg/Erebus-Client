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
| Map | World | Records a map of every chunk you load, per server and dimension, and recognises structures (trial chambers, ancient cities, strongholds, monuments, fortresses, bastions, end cities and more) from their blocks. On by default. |
| Stash ESP | Basefinding | Outlines and tracers to storage and other base giveaways (chests, shulkers, ender chests, furnaces, villagers, item frames, chest minecarts...). Alerts when an area looks like a base or a stash, marks it on the map and logs it to `erebus/finds.txt`. |
| Player Blocks | Basefinding | Highlights blocks the world never generates (concrete, shulkers, beacons, mineral blocks, redstone parts...) and building materials where they don't belong, skipping natural structures and biomes where they occur naturally. Lets Stash ESP tell bases from stashes. |
| Old Chunks | Basefinding | Marks chunks generated before 1.18 (Overworld) or 1.16 (Nether): old trails lead to old bases. |
| Logout Spots | Render | Marks where players were standing when they logged out, with name, health and how long ago. |
| Auto Safe Anchor | Combat | One press: places a respawn anchor where you look, charges it, puts a shield block between you and the blast, detonates it, then turns off. Ported from Mist. |
| Anchor Aura | Combat | Runs the same sequence by itself at whichever nearby spot hurts an opponent most, never inside anyone's hitbox and never at a spot that could kill you. Ported from Mist. |

Map, Stash ESP, Player Blocks, Old Chunks and Logout Spots are ported from Nyx and adapted to
1.21.11. Compared with Nyx: the minimap is a HUD element you can place anywhere, there are personal
waypoints (on the maps and in the world), finds are listed in the map's sidebar where you can
highlight any of them in the world, world labels are drawn as crisp HUD text, and Stash ESP's
"Silent Mode" confidence scoring became a plain "Dim lone blocks" option. Nyx's team map sharing,
navigator integration and editable block lists were not ported.

## Anchor modules and simulated input

Auto Safe Anchor and Anchor Aura never build interactions or packets themselves. Everything they do
is simulated input that the game then handles exactly like a player's:

- the hotbar slot is picked by pressing its number key (`input/InputSim`),
- the camera turns through synthetic mouse movement fed into Minecraft's own mouse handling, so
  the turn goes through your sensitivity, comes in whole mouse steps and respects inverted mouse
  settings (`input/MouseSim`, `mixin/MouseHandlerMixin`),
- crouching is the sneak key held down,
- every click is the use key, so the game sends its own packet for whatever its own crosshair hits.

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
| Timing | Start delay; delays after placing, charging and shielding; delay when no shield is placed; swap delay (or click in the same tick as the slot switch); settle time before a click; crouch and stand delays; random extra ticks with a uniform or Gaussian spread; detonate check; charge and detonate attempts; timeout. |
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
you try settings out on a dummy.

Damage is estimated with vanilla's explosion formula, including block exposure, armor, toughness,
Resistance and Protection/Blast Protection (`util/ExplosionDamage`). Mist's silent rotations,
session fatigue, server presets and CrystalAura hand-off were not ported; these turn the real
camera.

## Map

Press M. Drag to move, scroll to zoom toward the cursor, Space to jump back to yourself, Tab for
the sidebar, G for the chunk grid. Right-click anywhere to add a waypoint, center or copy
coordinates; right-click a base, stash or structure to highlight it in the world, mark it
explored, or forget it. The sidebar lists everything found (closest first) and your waypoints,
which you can rename in place.

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
  util/                  explosion damage estimate
  config/                JSON persistence
  notify/                toast notifications
tools/build_font.py      builds Erebus Sans from Geist
```
