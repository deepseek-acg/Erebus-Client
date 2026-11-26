Erebus for Minecraft **1.21.11** (Fabric).

**Install:** put the `erebus-<version>.jar` below in your `mods` folder together with [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11. Requires Fabric Loader 0.20+ and Java 21. Open the dashboard with Right Shift.

### New in 0.3.0
- **Crystal Aura** (renamed from AutoCrystal) locks onto one spot and keeps placing and breaking crystals there. The camera doesn't move between crystals, so it breaks each one the tick it appears. It only moves to a new spot when the current one does little damage, the opponent is far off from it in angle, or another spot is much better.
- **Anchor Aura and Auto Safe Anchor** are much faster: no delays by default, a quicker camera, and a sequence ends the moment the anchor is gone. Anchor Aura sticks to its spot too, and swaps back and looks back only once a fight pauses.
- **Seed Cracker** (proof of concept, for servers with vanilla world generation): records desert pyramids, jungle temples, swamp huts and buried treasure as you travel, and works out the world seed from them and the hashed seed the server sends.
- Saved Crystal Aura settings start fresh because of the rename. Anchor settings you changed before keep their values, so old delays still apply until you lower them.

### What's in it
- **Dashboard** with module categories, search, keybinds and settings, an editable theme (presets, every color, radius, blur, animations) and its own UI font, Erebus Sans.
- **HUD** with a drag-and-drop editor: watermark, module list, coordinates, server info, nearby players, supplies, armor, notifications, minimap, finder legend.
- **World map** (M) recorded as you play, with waypoints, structure detection and base/stash markers.
- **Seed Cracker** (proof of concept) for servers with vanilla world generation.
- **Basefinding:** Stash ESP, Player Blocks, Old Chunks, Logout Spots.
- **Combat:** Auto Safe Anchor, Anchor Aura and Crystal Aura. All three work only through simulated mouse and key input (hotbar keys, mouse movement, use and attack keys), never crafted packets, and share the hands through one lock so they never click at the same time.

See the README for every module and setting.
