package dev.erebus.client.render.font;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix3x2f;

import java.util.Arrays;
import java.util.Iterator;

/**
 * Draws Erebus Sans. Glyphs are rasterized for the exact on-screen pixel size (GUI scale times any pose scaling),
 * and every glyph lands on whole screen pixels, so text stays sharp and smoothly anti-aliased at any scale.
 * Layout uses the font's unscaled metrics, so widths match what is drawn at every size.
 */
public final class ErebusFont {
    /** Default em size, in GUI units. Sized so text sits in the same 9-unit line box as Minecraft's font. */
    public static final float SIZE = 9f;
    /** Baseline position within the line box at the default size. */
    public static final float BASELINE = 7f;

    private static FontFace regular;
    private static FontFace bold;
    private static boolean failed;
    private static final Long2ObjectOpenHashMap<GlyphAtlas> ATLASES = new Long2ObjectOpenHashMap<>();
    private static long lastSweep;

    private ErebusFont() {
    }

    /** Loads the font files; false if they could not be loaded (callers fall back to Minecraft's font). */
    public static boolean available() {
        if (regular != null) return true;
        if (failed) return false;
        try {
            regular = FontFace.load("/erebus/font/ErebusSans-Regular.ttf");
            bold = FontFace.load("/erebus/font/ErebusSans-Bold.ttf");
            return true;
        } catch (Throwable t) {
            failed = true;
            org.slf4j.LoggerFactory.getLogger("Erebus").error("Could not load Erebus Sans; using Minecraft's font", t);
            return false;
        }
    }

    private static FontFace face(boolean isBold) {
        return isBold ? bold : regular;
    }

    // ------------------------------------------------------------------------------------------------ metrics

    public static float width(String s, boolean isBold) {
        return width(s, isBold, SIZE);
    }

    public static float width(String s, boolean isBold, float size) {
        if (s == null || s.isEmpty() || !available()) return 0;
        FontFace f = face(isBold);
        long units = 0;
        int prev = -1;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int g = glyphFor(f, cp);
            if (prev > 0) units += f.kern(prev, g);
            units += f.advance(g);
            prev = g;
        }
        return units * size / f.unitsPerEm;
    }

    private static int glyphFor(FontFace f, int cp) {
        int g = f.glyph(cp);
        return g != 0 ? g : f.glyph('?');
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /** Draws text with its line box's top-left at (x, y). Returns the advance width in GUI units. */
    public static float draw(GuiGraphics g, String s, float x, float y, int color, boolean isBold, boolean shadow) {
        return draw(g, s, x, y, color, isBold, shadow, SIZE);
    }

    public static float draw(GuiGraphics g, String s, float x, float y, int color, boolean isBold, boolean shadow, float size) {
        if (s == null || s.isEmpty() || (color >>> 24) == 0 || !available()) return 0;
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        float poseScale = (float) Math.hypot(pose.m00(), pose.m01());
        int gui = Minecraft.getInstance().getWindow().getGuiScale();
        float pxPerUnit = gui * poseScale;
        if (pxPerUnit <= 0.01f) return 0;
        if (shadow) {
            int shadowAlpha = Math.round((color >>> 24) * 0.55f);
            float off = Math.max(1, Math.round(pxPerUnit * 0.5f * size / SIZE)) / pxPerUnit;
            emit(g, pose, pxPerUnit, s, x + off, y + off, shadowAlpha << 24, isBold, size);
        }
        return emit(g, pose, pxPerUnit, s, x, y, color, isBold, size);
    }

    private static float emit(GuiGraphics g, Matrix3x2f pose, float pxPerUnit, String s, float x, float y, int color, boolean isBold, float size) {
        FontFace f = face(isBold);
        float pixelSize = Math.max(4f, Math.round(size * pxPerUnit * 4f) / 4f);
        GlyphAtlas atlas = atlas(f, isBold, pixelSize);
        float k = 1f / pxPerUnit;

        // snap the baseline origin to the screen's pixel grid
        float bx = x;
        float by = y + BASELINE * size / SIZE;
        int gui = Minecraft.getInstance().getWindow().getGuiScale();
        float sx = (pose.m00() * bx + pose.m10() * by + pose.m20()) * gui;
        float sy = (pose.m01() * bx + pose.m11() * by + pose.m21()) * gui;
        bx += (Math.round(sx) - sx) * k;
        by += (Math.round(sy) - sy) * k;

        float[][] perPage = new float[4][];
        int[] counts = new int[4];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float pen = 0;
        int prev = -1;
        float colorBits = Float.intBitsToFloat(color);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int gi = glyphFor(f, cp);
            if (prev > 0) pen += f.kern(prev, gi) * atlas.scale;
            GlyphAtlas.Glyph gl = atlas.get(gi);
            if (gl.page() >= 0) {
                if (gl.page() >= perPage.length) {
                    perPage = Arrays.copyOf(perPage, gl.page() + 4);
                    counts = Arrays.copyOf(counts, gl.page() + 4);
                }
                float[] q = perPage[gl.page()];
                int n = counts[gl.page()];
                if (q == null) q = new float[TextRenderState.STRIDE * Math.max(8, s.length())];
                if ((n + 1) * TextRenderState.STRIDE > q.length) q = Arrays.copyOf(q, q.length * 2);
                float x0 = bx + (Math.round(pen) + gl.xOff()) * k;
                float y0 = by + gl.yOff() * k;
                float x1 = x0 + gl.w() * k;
                float y1 = y0 + gl.h() * k;
                int o = n * TextRenderState.STRIDE;
                q[o] = x0;
                q[o + 1] = y0;
                q[o + 2] = x1;
                q[o + 3] = y1;
                q[o + 4] = gl.u() / (float) GlyphAtlas.PAGE;
                q[o + 5] = gl.v() / (float) GlyphAtlas.PAGE;
                q[o + 6] = (gl.u() + gl.w()) / (float) GlyphAtlas.PAGE;
                q[o + 7] = (gl.v() + gl.h()) / (float) GlyphAtlas.PAGE;
                q[o + 8] = colorBits;
                perPage[gl.page()] = q;
                counts[gl.page()] = n + 1;
                minX = Math.min(minX, x0);
                minY = Math.min(minY, y0);
                maxX = Math.max(maxX, x1);
                maxY = Math.max(maxY, y1);
            }
            pen += f.advance(gi) * atlas.scale;
            prev = gi;
        }
        atlas.upload();
        if (minX <= maxX) {
            ScreenRectangle local = new ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY),
                    (int) Math.ceil(maxX - Math.floor(minX)) + 1, (int) Math.ceil(maxY - Math.floor(minY)) + 1);
            ScreenRectangle bounds = local.transformMaxBounds(pose);
            ScreenRectangle scissor = g.scissorStack.peek();
            if (scissor != null) bounds = scissor.intersection(bounds);
            if (bounds != null) {
                var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                for (int p = 0; p < perPage.length; p++) {
                    if (counts[p] == 0) continue;
                    DynamicTexture tex = atlas.pages.get(p);
                    g.guiRenderState.submitGuiElement(new TextRenderState(RenderPipelines.GUI_TEXTURED,
                            TextureSetup.singleTexture(tex.getTextureView(), sampler), pose, perPage[p], counts[p], scissor, bounds));
                }
            }
        }
        sweep();
        return pen * k;
    }

    private static GlyphAtlas atlas(FontFace f, boolean isBold, float pixelSize) {
        long key = ((long) (isBold ? 1 : 0) << 32) | Math.round(pixelSize * 4);
        GlyphAtlas a = ATLASES.get(key);
        if (a == null) {
            a = new GlyphAtlas(f, pixelSize);
            ATLASES.put(key, a);
        }
        a.lastUsed = System.currentTimeMillis();
        return a;
    }

    /** Frees sizes that haven't been drawn for a while (for example the in-between sizes of an opening animation). */
    private static void sweep() {
        long now = System.currentTimeMillis();
        if (now - lastSweep < 5000) return;
        lastSweep = now;
        Iterator<GlyphAtlas> it = ATLASES.values().iterator();
        while (it.hasNext()) {
            GlyphAtlas a = it.next();
            if (now - a.lastUsed > 20_000) {
                a.close();
                it.remove();
            }
        }
    }
}
