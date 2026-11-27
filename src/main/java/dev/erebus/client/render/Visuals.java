package dev.erebus.client.render;

import dev.erebus.client.gui.Ui;
import dev.erebus.client.render.font.Icon;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The shapes the "Better visuals" extras are drawn with, so every module's extras look like one family: soft
 * pulses, rings, wire spheres for blast radii, corner brackets around things, beams and floating labels. Purely
 * cosmetic; nothing here touches the game.
 */
public final class Visuals {
    private Visuals() {
    }

    /** Seconds since an arbitrary start, for animations. */
    public static double time() {
        return (System.nanoTime() / 1_000_000L % 3_600_000L) / 1000.0;
    }

    /** 0..1..0 over {@code period} seconds. */
    public static float pulse(double period) {
        return (float) (0.5 + 0.5 * Math.sin(time() * Math.PI * 2 / period));
    }

    /** 0..1 rising over {@code period} seconds, then starting again. */
    public static float phase(double period, double offset) {
        double t = time() / period + offset;
        return (float) (t - Math.floor(t));
    }

    /** The color with its alpha replaced (0-255). */
    public static int alpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
    }

    /** The color with its alpha scaled by {@code f}. */
    public static int fade(int color, float f) {
        return alpha(color, Math.round(((color >>> 24) & 0xFF) * Math.max(0, Math.min(1, f))));
    }

    /** A flat ring around {@code c} in the horizontal plane. */
    public static void ring(Vec3 c, double r, int color, float width) {
        ring(c, r, color, width, 40);
    }

    public static void ring(Vec3 c, double r, int color, float width, int segments) {
        Vec3 prev = null;
        for (int i = 0; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            Vec3 p = new Vec3(c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r);
            if (prev != null) Gizmos.line(prev, p, color, width);
            prev = p;
        }
    }

    /** A wire sphere: a few rings of latitude and two meridians. Used for blast radii. */
    public static void sphere(Vec3 c, double r, int color, float width) {
        for (int i = -2; i <= 2; i++) {
            double y = r * i / 3.0;
            ring(c.add(0, y, 0), Math.sqrt(Math.max(0, r * r - y * y)), i == 0 ? color : fade(color, 0.6f), width, 36);
        }
        for (int m = 0; m < 2; m++) {
            double ang = m * Math.PI / 2;
            Vec3 prev = null;
            for (int i = 0; i <= 36; i++) {
                double a = Math.PI * 2 * i / 36;
                double h = Math.cos(a) * r;
                Vec3 p = c.add(Math.cos(ang) * h, Math.sin(a) * r, Math.sin(ang) * h);
                if (prev != null) Gizmos.line(prev, p, fade(color, 0.45f), width);
                prev = p;
            }
        }
    }

    /** A ring that grows outward and fades, again every {@code period} seconds. */
    public static void ripple(Vec3 c, double maxR, int color, float width, double period, double offset) {
        float p = phase(period, offset);
        ring(c, 0.2 + maxR * p, fade(color, 1 - p), width);
    }

    /** Corner brackets around a box: an outline that's only there at the corners. */
    public static void brackets(AABB b, int color, float width) {
        double lx = (b.maxX - b.minX) * 0.28, ly = (b.maxY - b.minY) * 0.28, lz = (b.maxZ - b.minZ) * 0.28;
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? b.minX : b.maxX, y = (i & 2) == 0 ? b.minY : b.maxY, z = (i & 4) == 0 ? b.minZ : b.maxZ;
            double sx = (i & 1) == 0 ? 1 : -1, sy = (i & 2) == 0 ? 1 : -1, sz = (i & 4) == 0 ? 1 : -1;
            Vec3 o = new Vec3(x, y, z);
            Gizmos.line(o, o.add(lx * sx, 0, 0), color, width).setAlwaysOnTop();
            Gizmos.line(o, o.add(0, ly * sy, 0), color, width).setAlwaysOnTop();
            Gizmos.line(o, o.add(0, 0, lz * sz), color, width).setAlwaysOnTop();
        }
    }

    /** A box with a soft fill that breathes. */
    public static void glowBox(AABB b, int color, float width, int maxFill) {
        int fill = Math.round(maxFill * (0.45f + 0.55f * pulse(1.6)));
        Gizmos.cuboid(b, GizmoStyle.strokeAndFill(color, width, alpha(color, fill))).setAlwaysOnTop();
    }

    /** A vertical beam with a ring at its foot. */
    public static void beam(Vec3 base, double height, int color, float width) {
        Gizmos.line(base, base.add(0, height, 0), color, width);
        Gizmos.line(base, base.add(0, Math.min(height, 6), 0), fade(color, 1f), width * 2.2f);
        ripple(base.add(0, 0.05, 0), 1.6, color, width, 2.2, 0);
    }

    /** A curved line from a to b, bowed up by {@code lift}: reads as "this goes to that". */
    public static void arc(Vec3 a, Vec3 b, double lift, int color, float width) {
        Vec3 mid = a.add(b).scale(0.5).add(0, lift, 0);
        Vec3 prev = a;
        for (int i = 1; i <= 16; i++) {
            double t = i / 16.0, u = 1 - t;
            Vec3 p = a.scale(u * u).add(mid.scale(2 * u * t)).add(b.scale(t * t));
            Gizmos.line(prev, p, color, width).setAlwaysOnTop();
            prev = p;
        }
        // a little dot running along it, so the direction shows
        double t = phase(1.1, 0), u = 1 - t;
        Vec3 dot = a.scale(u * u).add(mid.scale(2 * u * t)).add(b.scale(t * t));
        Gizmos.point(dot, color, 6f).setAlwaysOnTop();
    }

    /** A crisp label floating at a world position (drawn on the HUD). */
    public static void label(Ui ui, Vec3 at, Icon icon, String text, String detail, int color) {
        float[] p = Projection.toScreen(at);
        if (p == null) return;
        ui.worldLabel(icon, text, detail, color, p[0], p[1] - 16);
    }
}
