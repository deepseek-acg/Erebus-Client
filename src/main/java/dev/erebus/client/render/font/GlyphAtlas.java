package dev.erebus.client.render.font;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Glyphs of one face rasterized at one pixel size, packed into 512x512 texture pages on demand.
 * Pages are white with the glyph coverage in alpha, so any color can be applied when drawing.
 */
final class GlyphAtlas {
    static final int PAGE = 512;
    private static final int PAD = 1;

    /** A rasterized glyph. Offsets and sizes are in pixels; (xOff, yOff) is relative to the pen on the baseline. */
    record Glyph(int page, int u, int v, int w, int h, int xOff, int yOff) {
        static final Glyph EMPTY = new Glyph(-1, 0, 0, 0, 0, 0, 0);
    }

    final FontFace face;
    final float pixelSize;
    final float scale;
    final List<DynamicTexture> pages = new ArrayList<>();
    private final boolean[] dirty = new boolean[64];
    private final Int2ObjectOpenHashMap<Glyph> glyphs = new Int2ObjectOpenHashMap<>();
    private int cursorX;
    private int cursorY;
    private int rowHeight;
    long lastUsed;

    GlyphAtlas(FontFace face, float pixelSize) {
        this.face = face;
        this.pixelSize = pixelSize;
        this.scale = STBTruetype.stbtt_ScaleForMappingEmToPixels(face.info(), pixelSize);
    }

    Glyph get(int glyph) {
        Glyph g = glyphs.get(glyph);
        if (g == null) {
            g = rasterize(glyph);
            glyphs.put(glyph, g);
        }
        return g;
    }

    private Glyph rasterize(int glyph) {
        int x0, y0, x1, y1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer a = stack.mallocInt(1), b = stack.mallocInt(1), c = stack.mallocInt(1), d = stack.mallocInt(1);
            STBTruetype.stbtt_GetGlyphBitmapBox(face.info(), glyph, scale, scale, a, b, c, d);
            x0 = a.get(0);
            y0 = b.get(0);
            x1 = c.get(0);
            y1 = d.get(0);
        }
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0) return Glyph.EMPTY;
        if (cursorX + w + PAD > PAGE) {
            cursorX = 0;
            cursorY += rowHeight + PAD;
            rowHeight = 0;
        }
        if (pages.isEmpty() || cursorY + h + PAD > PAGE) newPage();
        int page = pages.size() - 1;
        ByteBuffer bitmap = MemoryUtil.memAlloc(w * h);
        try {
            STBTruetype.stbtt_MakeGlyphBitmap(face.info(), bitmap, w, h, w, scale, scale, glyph);
            NativeImage img = pages.get(page).getPixels();
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    int alpha = bitmap.get(yy * w + xx) & 0xFF;
                    img.setPixel(cursorX + xx, cursorY + yy, (alpha << 24) | 0xFFFFFF);
                }
            }
        } finally {
            MemoryUtil.memFree(bitmap);
        }
        dirty[page] = true;
        Glyph g = new Glyph(page, cursorX, cursorY, w, h, x0, y0);
        cursorX += w + PAD;
        rowHeight = Math.max(rowHeight, h);
        return g;
    }

    private void newPage() {
        NativeImage img = new NativeImage(PAGE, PAGE, true);
        img.fillRect(0, 0, PAGE, PAGE, 0x00FFFFFF);
        pages.add(new DynamicTexture(() -> "erebus font " + pixelSize, img));
        cursorX = 0;
        cursorY = 0;
        rowHeight = 0;
    }

    /** Sends newly rasterized glyphs to the GPU. */
    void upload() {
        for (int i = 0; i < pages.size(); i++) {
            if (dirty[i]) {
                pages.get(i).upload();
                dirty[i] = false;
            }
        }
    }

    void close() {
        for (DynamicTexture t : pages) t.close();
        pages.clear();
        glyphs.clear();
    }
}
