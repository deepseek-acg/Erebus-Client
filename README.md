# Erebus

A utility client for the 6b6t anarchy server, built as a Fabric mod for Minecraft **1.21.11**.

This first version is the user interface only: the dashboard, an editable theme system, and
a HUD with a drag-and-drop editor. There are no modules yet. The dashboard and the module list
HUD are ready to show them once they're added.

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
Right-click or press Delete to hide an element.

Each element is stored relative to the nearest screen edge, so layouts keep their place when the
window size or GUI scale changes.

Built-in elements: Watermark, Module list, Coordinates (with Nether/Overworld conversion and
facing), Server info (FPS, ping, speed), Nearby players, Supplies (totems, crystals, gapples,
obsidian, XP bottles), Armor, and Notifications.

## Configuration

Settings are saved to `config/erebus/` when you close the dashboard or the game:

- `theme.json`: base preset, colors and effects
- `hud.json`: element visibility, positions and settings
- `client.json`: client preferences

## Adding modules (next step)

Extend `dev.erebus.client.module.Module`, add settings with `add(...)`, and register the instance
with `Erebus.modules().register(...)` during client init. The module then shows up in its
category, in search, in the module list HUD and in the keybind system automatically.

To preview the dashboard with placeholder modules (no behavior), start the client with
`-Derebus.previewModules=true`.

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
  theme/                 theme model and presets
  setting/               setting types shared by modules and HUD elements
  module/                module base class and manager (no modules yet)
  render/                2D drawing primitives (rounded rects, borders, shadows, gradients)
  config/                JSON persistence
  notify/                toast notifications
```
