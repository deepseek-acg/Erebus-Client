package dev.erebus.client.gui.map;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.erebus.client.Erebus;
import dev.erebus.client.gui.Ui;
import dev.erebus.client.gui.UiScreen;
import dev.erebus.client.gui.widget.Button;
import dev.erebus.client.gui.widget.ContextMenu;
import dev.erebus.client.gui.widget.Drawable;
import dev.erebus.client.gui.widget.ScrollPanel;
import dev.erebus.client.gui.widget.TextField;
import dev.erebus.client.gui.widget.Widget;
import dev.erebus.client.map.MapRenderer;
import dev.erebus.client.map.MapStore;
import dev.erebus.client.map.Waypoints;
import dev.erebus.client.module.basefinding.BaseFinds;
import dev.erebus.client.module.world.MapModule;
import dev.erebus.client.notify.Notifications;
import dev.erebus.client.render.Render2D;
import dev.erebus.client.render.font.Icon;
import dev.erebus.client.util.Anim;
import dev.erebus.client.util.Colors;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The full-screen map. Drag to move, scroll to zoom (towards the cursor), right-click for actions, Space to jump
 * back to you. The sidebar lists everything found (bases, stashes, structures) and your waypoints.
 */
public final class WorldMapScreen extends UiScreen {
    private static final int TOP = 26;
    private static final int BOTTOM = 16;
    private static final int SIDE_MAX = 186;
    private static final double MIN_SCALE = 1 / 8192.0;
    private static final double MAX_SCALE = 12;

    private static double savedScale = 1;
    private static boolean sidebarOpen = true;
    private static boolean sidebarChosen;
    private static int tab;
    private static boolean grid;

    private final Screen parent;
    private final MapRenderer.Layers layers;
    private final List<MapRenderer.Drawn> drawn = new ArrayList<>();
    private final Anim zoom;
    private final Anim travel = new Anim(1f, 12f);
    private double cx;
    private double cz;
    private double fromX, fromZ, toX, toZ;
    private boolean follow = true;
    private double anchorSx = Double.NaN, anchorSy, anchorWx, anchorWz;
    private boolean dragging;
    private double lastX, lastY;
    private MapRenderer.Drawn hover;
    private int listSignature;
    private long lastListBuild;
    private final long openedAt = System.currentTimeMillis();

    public WorldMapScreen(Screen parent) {
        super(Component.literal("Map"));
        this.parent = parent;
        MapModule m = MapModule.get();
        layers = m != null ? m.layers() : new MapRenderer.Layers();
        layers.chunkGrid = grid;
        zoom = new Anim((float) Math.log(savedScale), 16f);
        if (mc().player != null) {
            cx = mc().player.getX();
            cz = mc().player.getZ();
        }
    }

    private static net.minecraft.client.Minecraft mc() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    @Override
    protected boolean fullWindow() {
        return true;
    }

    @Override
    protected int designWidth() {
        return width;
    }

    @Override
    protected int designHeight() {
        return height;
    }

    private double scale() {
        return Math.exp(zoom.get());
    }

    private int side() {
        return Math.min(SIDE_MAX, Math.max(150, width * 2 / 5));
    }


    private int mapW() {
        return width - (sidebarOpen ? side() : 0);
    }

    private int mapH() {
        return height - TOP - BOTTOM;
    }

    private double toWorldX(double sx) {
        return cx + (sx - mapW() / 2.0) / scale();
    }

    private double toWorldZ(double sy) {
        return cz + (sy - (TOP + mapH() / 2.0)) / scale();
    }

    // ------------------------------------------------------------------------------------------------ layout

    @Override
    protected void build() {
        if (!sidebarChosen) sidebarOpen = width >= 560; // narrow windows start with the map only
        root.add(new MapArea());

        // top bar
        root.add(new Drawable(0, 0, width, TOP, (ui, self, mx, my) -> {
            ui.rect(0, 0, width, TOP, Colors.withAlpha(ui.theme.get(dev.erebus.client.theme.ThemeColor.HEADER), 0xFF));
            ui.rect(0, TOP - 1, width, 1, ui.theme.divider());
            ui.icon(Icon.MAP, 9, 9, 8, ui.theme.accent());
            int tx = 24 + ui.textBold("Map", 24, 9, ui.theme.text());
            String where = MapStore.ready() ? MapStore.server() + "  ·  " + dimName(MapStore.dimension()) : "Not recording: turn on the Map module";
            ui.text(ui.ellipsize(where, Math.max(40, chipsLeft - tx - 20)), tx + 10, 9, ui.theme.dim());
        }));
        int bx = width - 8;
        Chip sideToggle = root.add(new Chip(0, 5, Icon.GRID, null, "Sidebar", () -> sidebarOpen, () -> {
            sidebarOpen = !sidebarOpen;
            sidebarChosen = true;
            rebuild();
        }));
        sideToggle.x = bx - sideToggle.width;
        bx = sideToggle.x - 10;
        Object[][] chips = {
                {"Grid", 0xFFB0B0BA}, {"Explored", MapRenderer.EXPLORED}, {"Waypoints", 0xFFFFD24A}, {"Players", MapRenderer.PLAYER},
                {"Spawners", MapRenderer.SPAWNER}, {"Storage", 0xFFFFA62B}, {"Stashes", MapRenderer.STASH}, {"Bases", MapRenderer.BASE},
                {"Structures", 0xFF2DD4BF}};
        int needed = 0;
        for (Object[] c : chips) needed += dev.erebus.client.render.font.Text.width((String) c[0]) + 25;
        int titleRoom = 24 + dev.erebus.client.render.font.Text.width("Map", true) + 90;
        if (bx - needed > titleRoom) {
            for (Object[] c : chips) {
                String name = (String) c[0];
                Chip chip = root.add(new Chip(0, 5, null, (Integer) c[1], name, () -> layer(name), () -> flip(name)));
                chip.x = bx - chip.width;
                bx = chip.x - 3;
            }
        } else { // not enough room: one Layers button that opens a menu of toggles
            Chip[] holder = new Chip[1];
            holder[0] = root.add(new Chip(0, 5, Icon.EYE, null, "Layers", () -> false, () -> {
                ContextMenu menu = new ContextMenu("Show on the map");
                for (int i = chips.length - 1; i >= 0; i--) {
                    String name = (String) chips[i][0];
                    menu.item(layer(name) ? Icon.CHECK : null, name, () -> flip(name));
                }
                menu.openAt(ui, holder[0].x, holder[0].bottom() + 2, width, height);
            }));
            holder[0].x = bx - holder[0].width;
            bx = holder[0].x - 3;
        }
        chipsLeft = bx;

        // bottom bar
        root.add(new Drawable(0, height - BOTTOM, width, BOTTOM, (ui, self, mx, my) -> {
            ui.rect(0, self.y, width, BOTTOM, Colors.withAlpha(ui.theme.get(dev.erebus.client.theme.ThemeColor.HEADER), 0xFF));
            ui.rect(0, self.y, width, 1, ui.theme.divider());
            int ty = self.y + 4;
            int x = 8;
            if (mx >= 0 && mx < mapW() && my > TOP && my < height - BOTTOM) {
                int wx = (int) Math.floor(toWorldX(mx)), wz = (int) Math.floor(toWorldZ(my));
                x += ui.text("X ", x, ty, ui.theme.accent());
                x += ui.text(wx + "   ", x, ty, ui.theme.text());
                x += ui.text("Z ", x, ty, ui.theme.accent());
                x += ui.text(wz + "   ", x, ty, ui.theme.text());
                String other = MapStore.dimension().endsWith("the_nether") ? "Overworld " + wx * 8 + ", " + wz * 8
                        : MapStore.dimension().endsWith("overworld") ? "Nether " + Math.floorDiv(wx, 8) + ", " + Math.floorDiv(wz, 8) : "";
                x += ui.text("chunk " + Math.floorDiv(wx, 16) + ", " + Math.floorDiv(wz, 16) + (other.isEmpty() ? "" : "   " + other), x, ty, ui.theme.dim());
            }
            double s = scale();
            String z = s >= 1 ? String.format(Locale.ROOT, "%.1f px per block", s) : String.format(Locale.ROOT, "1 px = %,.0f blocks", 1 / s);
            String help = "Drag: move  ·  Scroll: zoom  ·  Right-click: actions  ·  Space: you";
            int right = width - 8;
            int zw = ui.width(z);
            if (x + zw + 16 < right) ui.textRight(z, right, ty, ui.theme.dim());
            int hw = ui.width(help);
            if (x + hw + zw + 40 < right) ui.text(help, right - zw - 24 - hw, ty, Colors.mulAlpha(ui.theme.dim(), 0.8f));
        }));

        if (sidebarOpen) buildSidebar();
    }

    private int chipsLeft;

    private void buildSidebar() {
        int sx = width - side();
        int top = TOP;
        int h = height - TOP - BOTTOM;
        root.add(new Drawable(sx, top, side(), h, (ui, self, mx, my) -> {
            ui.rect(self.x, self.y, self.width, self.height, Colors.withAlpha(ui.theme.get(dev.erebus.client.theme.ThemeColor.BACKGROUND), 0xFF));
            ui.rect(self.x, self.y, 1, self.height, ui.theme.divider());
        }));
        int tw = (side() - 18) / 2;
        List<MapStore.Mark> finds = finds();
        root.add(new Tab(sx + 6, top + 6, tw, "Finds", finds.size(), 0));
        root.add(new Tab(sx + 12 + tw, top + 6, tw, "Waypoints", Waypoints.all().size(), 1));

        int listTop = top + 30;
        int listBottom = top + h - (tab == 1 ? 30 : 6);
        ScrollPanel list = root.add(new ScrollPanel(sx + 1, listTop, side() - 1, listBottom - listTop));
        int ry = listTop + 2;
        if (tab == 0) {
            if (finds.isEmpty()) {
                list.add(new dev.erebus.client.gui.widget.TextBlock(sx + 10, ry + 4, side() - 22,
                        "Nothing found here yet. Turn on Stash ESP and Player Blocks (Basefinding); structures you pass are recognised automatically.",
                        dev.erebus.client.gui.widget.TextBlock.Tone.DIM));
            }
            for (MapStore.Mark m : finds) {
                list.add(new FindRow(sx + 5, ry, side() - 12, m));
                ry += 30;
            }
        } else {
            List<Waypoints.Waypoint> wps = Waypoints.all();
            if (wps.isEmpty()) {
                list.add(new dev.erebus.client.gui.widget.TextBlock(sx + 10, ry + 4, side() - 22,
                        "No waypoints yet. Right-click anywhere on the map and choose Add waypoint, or use the button below.",
                        dev.erebus.client.gui.widget.TextBlock.Tone.DIM));
            }
            for (Waypoints.Waypoint w : wps) {
                list.add(new WaypointRow(sx + 5, ry, side() - 12, w));
                ry += 24;
            }
            root.add(new Button(sx + 8, listBottom + 6, side() - 16, 18, "Add waypoint at your position", Button.Style.PRIMARY, () -> {
                if (mc().player == null) return;
                Waypoints.add(Waypoints.nextName(), (int) Math.floor(mc().player.getX()), (int) Math.floor(mc().player.getY()),
                        (int) Math.floor(mc().player.getZ()));
                rebuild();
            }));
        }
        listSignature = signature();
        lastListBuild = System.currentTimeMillis();
    }

    /** Bases, stashes and structures on this map, unexplored first, then by distance. */
    private List<MapStore.Mark> finds() {
        List<MapStore.Mark> out = new ArrayList<>();
        for (MapStore.Mark m : MapStore.alerts()) {
            if (m.explored() && layers.hideExplored) continue;
            out.add(m);
        }
        double px = mc().player != null ? mc().player.getX() : cx, pz = mc().player != null ? mc().player.getZ() : cz;
        out.sort(Comparator.comparing(MapStore.Mark::explored)
                .thenComparingDouble(m -> (m.x() - px) * (m.x() - px) + (m.z() - pz) * (m.z() - pz)));
        return out;
    }

    private int signature() {
        int s = MapStore.alerts().size() * 31 + Waypoints.all().size();
        for (MapStore.Mark m : MapStore.alerts()) if (m.explored()) s += 7;
        return s;
    }

    // ------------------------------------------------------------------------------------------------ layers

    private boolean layer(String name) {
        return switch (name) {
            case "Structures" -> layers.structures;
            case "Bases" -> layers.bases;
            case "Stashes" -> layers.stashes;
            case "Storage" -> layers.storage;
            case "Spawners" -> layers.spawners;
            case "Players" -> layers.players;
            case "Waypoints" -> layers.waypoints;
            case "Grid" -> layers.chunkGrid;
            default -> !layers.hideExplored;
        };
    }

    private void flip(String name) {
        switch (name) {
            case "Structures" -> layers.structures = !layers.structures;
            case "Bases" -> layers.bases = !layers.bases;
            case "Stashes" -> layers.stashes = !layers.stashes;
            case "Storage" -> layers.storage = !layers.storage;
            case "Spawners" -> layers.spawners = !layers.spawners;
            case "Players" -> layers.players = !layers.players;
            case "Waypoints" -> layers.waypoints = !layers.waypoints;
            case "Grid" -> {
                layers.chunkGrid = !layers.chunkGrid;
                grid = layers.chunkGrid;
            }
            default -> {
                layers.hideExplored = !layers.hideExplored;
                rebuild();
            }
        }
    }

    private static String dimName(String id) {
        if (id.endsWith("the_nether")) return "Nether";
        if (id.endsWith("the_end")) return "End";
        if (id.endsWith("overworld")) return "Overworld";
        return id;
    }

    // ------------------------------------------------------------------------------------------------ frame

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        // the map covers the screen
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        double before = scale();
        zoom.update(ui.dt);
        double s = scale();
        if (!Double.isNaN(anchorSx) && before != s) { // keep the world point under the cursor still while zooming
            cx = anchorWx - (anchorSx - mapW() / 2.0) / s;
            cz = anchorWz - (anchorSy - (TOP + mapH() / 2.0)) / s;
        }
        if (zoom.get() == zoom.target()) anchorSx = Double.NaN;
        if (travel.get() < 1f) {
            float t = travel.update(ui.dt);
            cx = fromX + (toX - fromX) * t;
            cz = fromZ + (toZ - fromZ) * t;
        }
        if (follow && mc().player != null) {
            cx = mc().player.getX();
            cz = mc().player.getZ();
        }
        long now = System.currentTimeMillis();
        if (sidebarOpen && ui.focused() == null && ui.captured() == null && ui.popover() == null
                && (signature() != listSignature || now - lastListBuild > 4000)) {
            rebuild();
        }
        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    protected void renderOverlay(Ui ui, int mx, int my) {
        if (hover == null || ui.popover() != null) return;
        String title = hover.title();
        String detail = hover.detail();
        int w = Math.max(ui.width(title), ui.width(detail)) + 22;
        int h = 30;
        int x = mx + 12, y = my + 10;
        if (x + w > width - 4) x = mx - w - 8;
        if (y + h > height - BOTTOM - 2) y = my - h - 6;
        Render2D.shadow(ui.g, x, y, w, h, ui.smallRadius(), 4, 0x80000000);
        ui.round(x, y, w, h, ui.smallRadius(), Colors.withAlpha(ui.theme.get(dev.erebus.client.theme.ThemeColor.BACKGROUND), 0xF5));
        ui.border(x, y, w, h, ui.smallRadius(), ui.theme.border());
        ui.round(x + 7, y + 7, 6, 6, 3, hover.color() | 0xFF000000);
        ui.text(title, x + 17, y + 5, ui.theme.text());
        ui.text(detail, x + 7, y + 17, ui.theme.dim());
    }

    // ------------------------------------------------------------------------------------------------ navigation

    private void goTo(double x, double z) {
        follow = false;
        anchorSx = Double.NaN;
        fromX = cx;
        fromZ = cz;
        toX = x;
        toZ = z;
        travel.snap(0f);
        travel.target(1f);
    }

    private void zoomBy(double factor, double sx, double sy) {
        double target = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.exp(zoom.target()) * factor));
        if (!follow) {
            anchorSx = sx;
            anchorSy = sy;
            anchorWx = toWorldX(sx);
            anchorWz = toWorldZ(sy);
        }
        zoom.target((float) Math.log(target));
    }

    private void contextMenu(int mx, int my) {
        MapRenderer.Drawn d = MapRenderer.hovered(drawn, mx, my);
        ContextMenu menu;
        if (d != null && d.mark() != null) {
            MapStore.Mark m = d.mark();
            menu = markMenu(m);
        } else if (d != null && d.waypoint() != null) {
            Waypoints.Waypoint w = d.waypoint();
            menu = new ContextMenu(w.name())
                    .item(Icon.COPY, "Copy coordinates", () -> copy(w.x(), w.y(), w.z()))
                    .danger(Icon.TRASH, "Delete waypoint", () -> {
                        Waypoints.remove(w);
                        rebuild();
                    });
        } else {
            int wx = (int) Math.floor(toWorldX(mx)), wz = (int) Math.floor(toWorldZ(my));
            int wy = surfaceY(wx, wz);
            menu = new ContextMenu(wx + ", " + wz)
                    .item(Icon.FLAG, "Add waypoint here", () -> {
                        Waypoints.Waypoint w = Waypoints.add(Waypoints.nextName(), wx, wy, wz);
                        if (w != null) Notifications.success(w.name(), "added at " + wx + " " + wz);
                        tab = 1;
                        rebuild();
                    })
                    .item(Icon.TARGET, "Center here", () -> goTo(wx + 0.5, wz + 0.5))
                    .item(Icon.COPY, "Copy coordinates", () -> copy(wx, wy, wz));
        }
        menu.openAt(ui, mx, my, width, height);
    }

    private ContextMenu markMenu(MapStore.Mark m) {
        boolean lit = BaseFinds.isHighlighted(m);
        return new ContextMenu(m.label())
                .item(Icon.EYE, lit ? "Stop highlighting" : "Highlight in the world", () -> {
                    boolean on = BaseFinds.toggleHighlight(m);
                    Notifications.info(shortName(m), on ? "highlighted in the world" : "highlight off");
                })
                .item(Icon.CHECK, m.explored() ? "Mark not explored" : "Mark explored", () -> {
                    MapStore.setExplored(m, !m.explored());
                    rebuild();
                })
                .item(Icon.TARGET, "Center on it", () -> goTo(m.x() + 0.5, m.z() + 0.5))
                .item(Icon.COPY, "Copy coordinates", () -> copy(m.x(), m.y(), m.z()))
                .danger(Icon.TRASH, "Forget it", () -> {
                    MapStore.removeAlert(m);
                    rebuild();
                });
    }

    private static String shortName(MapStore.Mark m) {
        if (m.kind().equals("structure")) return m.label();
        return m.kind().equals("base") ? "Base" : "Stash";
    }

    /** Ground height at a spot if its chunk is loaded with terrain, otherwise your own height. */
    private int surfaceY(int x, int z) {
        int fallback = mc().player != null ? (int) Math.floor(mc().player.getY()) : 64;
        if (mc().level != null && mc().level.hasChunk(x >> 4, z >> 4)) {
            int h = mc().level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (h > mc().level.getMinY() + 1) return h;
        }
        return fallback;
    }

    private void copy(int x, int y, int z) {
        mc().keyboardHandler.setClipboard(x + " " + y + " " + z);
        Notifications.info("Coordinates", "copied " + x + " " + y + " " + z);
    }

    @Override
    protected boolean onKey(int key, int modifiers) {
        if (ui.focused() instanceof TextField) return false;
        if (key == GLFW.GLFW_KEY_SPACE) {
            follow = true;
            travel.snap(1f);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            sidebarOpen = !sidebarOpen;
            sidebarChosen = true;
            rebuild();
            return true;
        }
        if (key == GLFW.GLFW_KEY_G) {
            flip("Grid");
            return true;
        }
        int dx = key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A ? -1 : key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D ? 1 : 0;
        int dz = key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W ? -1 : key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S ? 1 : 0;
        if (dx != 0 || dz != 0) {
            double step = 80 / scale() * ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? 4 : 1);
            goTo(cx + dx * step, cz + dz * step);
            return true;
        }
        if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) {
            zoomBy(1.5, mapW() / 2.0, TOP + mapH() / 2.0);
            return true;
        }
        if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) {
            zoomBy(1 / 1.5, mapW() / 2.0, TOP + mapH() / 2.0);
            return true;
        }
        var mapKey = Erebus.mapKey();
        if (mapKey != null && KeyBindingHelper.getBoundKeyOf(mapKey).getValue() == key && System.currentTimeMillis() - openedAt > 250) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        savedScale = Math.exp(zoom.target());
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------------------------------------ widgets

    /** The map itself: drag to pan, scroll to zoom, right-click for the context menu. */
    private final class MapArea extends Widget {
        MapArea() {
            bounds(0, TOP, mapW(), mapH());
        }

        @Override
        public void render(Ui ui, int mx, int my) {
            drawn.clear();
            MapRenderer.draw(ui.g, x, y, width, height, cx, cz, scale(), 0, layers, drawn);
            boolean inside = contains(mx, my);
            hover = inside && !dragging ? MapRenderer.hovered(drawn, mx, my) : null;
            if (inside) ui.cursor(dragging ? CursorTypes.RESIZE_ALL : hover != null ? CursorTypes.POINTING_HAND : CursorTypes.ARROW);
            if (!MapStore.ready()) {
                ui.textCentered("The Map module is off, so nothing is being recorded.", x + width / 2, y + height / 2 - 10, ui.theme.text());
                ui.textCentered("Turn it on in the dashboard (World > Map), then come back.", x + width / 2, y + height / 2 + 4, ui.theme.dim());
            } else if (!follow) {
                String back = "Space: back to you";
                int bw = ui.width(back) + 16;
                ui.round(x + width / 2 - bw / 2, y + 8, bw, 16, 8, 0xC0101014);
                ui.textCentered(back, x + width / 2, y + 12, ui.theme.text());
            }
        }

        @Override
        public boolean mouseClicked(Ui ui, double mx, double my, int button) {
            if (!contains(mx, my)) return false;
            if (button == 1) {
                contextMenu((int) mx, (int) my);
                return true;
            }
            if (button == 0) {
                MapRenderer.Drawn d = MapRenderer.hovered(drawn, mx, my);
                if (d != null && d.mark() != null && (System.currentTimeMillis() - lastClick) < 300) {
                    goTo(d.mark().x() + 0.5, d.mark().z() + 0.5);
                }
                lastClick = System.currentTimeMillis();
                dragging = true;
                lastX = mx;
                lastY = my;
                ui.capture(this);
                return true;
            }
            return false;
        }

        private long lastClick;

        @Override
        public void mouseDragged(Ui ui, double mx, double my, int button) {
            if (!dragging) return;
            if (mx != lastX || my != lastY) {
                follow = false;
                travel.snap(1f);
                anchorSx = Double.NaN;
                cx -= (mx - lastX) / scale();
                cz -= (my - lastY) / scale();
            }
            lastX = mx;
            lastY = my;
        }

        @Override
        public void mouseReleased(Ui ui, double mx, double my, int button) {
            dragging = false;
        }

        @Override
        public boolean mouseScrolled(Ui ui, double mx, double my, double amount) {
            if (!contains(mx, my)) return false;
            zoomBy(amount > 0 ? 1.35 : 1 / 1.35, mx, my);
            return true;
        }
    }

    /** A toggle in the top bar with a colour dot (or icon). */
    private final class Chip extends Widget {
        private final Icon icon;
        private final Integer color;
        private final String label;
        private final java.util.function.BooleanSupplier on;
        private final Runnable click;

        Chip(int x, int y, Icon icon, Integer color, String label, java.util.function.BooleanSupplier on, Runnable click) {
            this.icon = icon;
            this.color = color;
            this.label = label;
            this.on = on;
            this.click = click;
            bounds(x, y, dev.erebus.client.render.font.Text.width(label) + 22, 16);
        }

        @Override
        public void render(Ui ui, int mx, int my) {
            boolean hot = contains(mx, my);
            boolean active = on.getAsBoolean();
            if (hot) ui.cursor(CursorTypes.POINTING_HAND);
            int bg = active ? ui.theme.selected() : hot ? ui.theme.hover() : 0x14FFFFFF;
            ui.round(x, y, width, height, 8, bg);
            if (active) ui.border(x, y, width, height, 8, Colors.withAlpha(ui.theme.accent(), 0x60));
            if (icon != null) ui.icon(icon, x + 6, y + 4, 8, active ? ui.theme.accent() : ui.theme.dim());
            else ui.round(x + 7, y + 6, 5, 5, 2, active ? color | 0xFF000000 : 0xFF55555E);
            ui.text(label, x + 16, y + 4, active ? ui.theme.text() : ui.theme.dim());
        }

        @Override
        public boolean mouseClicked(Ui ui, double mx, double my, int button) {
            if (button != 0 || !contains(mx, my)) return false;
            click.run();
            return true;
        }
    }

    /** Sidebar tab. */
    private final class Tab extends Widget {
        private final String label;
        private final int count;
        private final int index;

        Tab(int x, int y, int w, String label, int count, int index) {
            bounds(x, y, w, 18);
            this.label = label;
            this.count = count;
            this.index = index;
        }

        @Override
        public void render(Ui ui, int mx, int my) {
            boolean active = tab == index;
            boolean hot = contains(mx, my);
            if (hot) ui.cursor(CursorTypes.POINTING_HAND);
            ui.round(x, y, width, height, ui.smallRadius(), active ? ui.theme.selected() : hot ? ui.theme.hover() : 0x10FFFFFF);
            String c = String.valueOf(count);
            int tw = ui.width(label) + 6 + ui.width(c);
            int tx = x + (width - tw) / 2;
            tx += ui.text(label, tx, y + 5, active ? ui.theme.text() : ui.theme.dim()) + 6;
            ui.text(c, tx, y + 5, active ? ui.theme.accent() : Colors.mulAlpha(ui.theme.dim(), 0.8f));
        }

        @Override
        public boolean mouseClicked(Ui ui, double mx, double my, int button) {
            if (button != 0 || !contains(mx, my)) return false;
            tab = index;
            rebuild();
            return true;
        }
    }

    /** A base, stash or structure in the sidebar. Click: center on it. Right-click: actions. */
    private final class FindRow extends Widget {
        private final MapStore.Mark mark;

        FindRow(int x, int y, int w, MapStore.Mark mark) {
            bounds(x, y, w, 28);
            this.mark = mark;
        }

        private int color() {
            if (mark.explored()) return MapRenderer.EXPLORED;
            return switch (mark.kind()) {
                case "base" -> MapRenderer.BASE;
                case "stash" -> MapRenderer.STASH;
                default -> mark.color() | 0xFF000000;
            };
        }

        @Override
        public void render(Ui ui, int mx, int my) {
            boolean hot = contains(mx, my);
            boolean lit = BaseFinds.isHighlighted(mark);
            if (hot) ui.cursor(CursorTypes.POINTING_HAND);
            if (hot || lit) ui.round(x, y, width, height, ui.smallRadius(), lit ? ui.theme.selected() : ui.theme.hover());
            int c = color();
            Icon icon = switch (mark.kind()) {
                case "base" -> Icon.RADAR;
                case "stash" -> Icon.GRID;
                default -> Icon.PIN;
            };
            ui.round(x + 5, y + 6, 16, 16, 4, Colors.withAlpha(c, 0x30));
            ui.icon(icon, x + 9, y + 10, 8, c);
            String title = mark.kind().equals("structure") ? mark.label() : (mark.kind().equals("base") ? "Base" : "Stash");
            String sub = mark.kind().equals("structure") ? "" : mark.label().replaceFirst("^(Base|Stash): ", "");
            int dist = mc().player != null ? (int) Math.sqrt(Math.pow(mark.x() - mc().player.getX(), 2) + Math.pow(mark.z() - mc().player.getZ(), 2)) : 0;
            String distText = dist >= 1000 ? String.format(Locale.ROOT, "%.1fk", dist / 1000.0) : dist + "m";
            int right = right() - 6;
            ui.textRight(distText, right, y + 5, ui.theme.dim());
            ui.text(ui.ellipsize(title, width - 36 - ui.width(distText) - 8), x + 27, y + 5, mark.explored() ? ui.theme.dim() : ui.theme.text());
            String second = (sub.isEmpty() ? "" : sub + " · ") + mark.x() + " " + mark.z() + (mark.explored() ? " · explored" : "");
            ui.text(ui.ellipsize(second, width - 34), x + 27, y + 16, Colors.mulAlpha(ui.theme.dim(), 0.85f));
            if (lit) ui.icon(Icon.EYE, right - 9, y + 16, 7, ui.theme.accent());
        }

        @Override
        public boolean mouseClicked(Ui ui, double mx, double my, int button) {
            if (!contains(mx, my)) return false;
            if (button == 0) goTo(mark.x() + 0.5, mark.z() + 0.5);
            else if (button == 1) markMenu(mark).openAt(ui, (int) mx, (int) (my - ui.offsetY), width(), height());
            return true;
        }

        private int width() {
            return WorldMapScreen.this.width;
        }

        private int height() {
            return WorldMapScreen.this.height;
        }
    }

    /** A waypoint in the sidebar: rename in place, click the flag to center, trash to delete. */
    private final class WaypointRow extends Widget {
        private Waypoints.Waypoint wp;
        private final TextField name;

        WaypointRow(int x, int y, int w, Waypoints.Waypoint wp) {
            bounds(x, y, w, 22);
            this.wp = wp;
            this.name = new TextField(x + 24, y + 3, w - 80, 16, wp.name(), "Name", 32).noBackground()
                    .onChange(s -> this.wp = Waypoints.rename(this.wp, s));
        }

        @Override
        public void render(Ui ui, int mx, int my) {
            boolean hot = contains(mx, my);
            if (hot || ui.isFocused(name)) ui.round(x, y, width, height, ui.smallRadius(), ui.theme.hover());
            boolean flagHot = mx >= x && mx < x + 22 && my >= y && my < bottom();
            if (flagHot) ui.cursor(CursorTypes.POINTING_HAND);
            ui.icon(Icon.FLAG, x + 8, y + 7, 8, wp.color());
            name.render(ui, mx, my);
            int dist = mc().player != null ? (int) Math.sqrt(Math.pow(wp.x() - mc().player.getX(), 2) + Math.pow(wp.z() - mc().player.getZ(), 2)) : 0;
            String d = dist >= 1000 ? String.format(Locale.ROOT, "%.1fk", dist / 1000.0) : dist + "m";
            ui.textRight(d, right() - 22, y + 7, ui.theme.dim());
            boolean trashHot = mx >= right() - 18 && mx < right() && my >= y && my < bottom();
            if (trashHot) ui.cursor(CursorTypes.POINTING_HAND);
            ui.icon(Icon.TRASH, right() - 14, y + 7, 8, trashHot ? 0xFFFF8A95 : Colors.mulAlpha(ui.theme.dim(), 0.7f));
            if (flagHot) ui.tooltip(this, "Center the map on " + wp.name() + " (" + wp.x() + " " + wp.y() + " " + wp.z() + ")");
        }

        @Override
        public boolean mouseClicked(Ui ui, double mx, double my, int button) {
            if (!contains(mx, my)) return false;
            if (mx >= right() - 18) {
                Waypoints.remove(wp);
                rebuild();
                return true;
            }
            if (name.mouseClicked(ui, mx, my, button)) return true;
            goTo(wp.x() + 0.5, wp.z() + 0.5);
            return true;
        }

        @Override
        public boolean keyPressed(Ui ui, int key, int modifiers) {
            return name.keyPressed(ui, key, modifiers);
        }
    }
}
