package dev.erebus.client.render.font;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** One TrueType font file loaded with stb_truetype. Metrics are in font units unless noted. */
public final class FontFace {
    private final ByteBuffer data;
    private final STBTTFontinfo info;
    public final int unitsPerEm;
    public final int ascent;
    public final int descent;
    private final Int2IntOpenHashMap glyphIndex = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap advance = new Int2IntOpenHashMap();
    private final Long2IntOpenHashMap kerning = new Long2IntOpenHashMap();

    private FontFace(ByteBuffer data) {
        this.data = data;
        this.info = STBTTFontinfo.malloc();
        if (!STBTruetype.stbtt_InitFont(info, data)) throw new IllegalStateException("not a TrueType font");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer a = stack.mallocInt(1), d = stack.mallocInt(1), g = stack.mallocInt(1);
            STBTruetype.stbtt_GetFontVMetrics(info, a, d, g);
            ascent = a.get(0);
            descent = d.get(0);
        }
        // stbtt_ScaleForMappingEmToPixels(info, 1) == 1 / unitsPerEm
        unitsPerEm = Math.round(1f / STBTruetype.stbtt_ScaleForMappingEmToPixels(info, 1f));
        glyphIndex.defaultReturnValue(-1);
        advance.defaultReturnValue(Integer.MIN_VALUE);
        kerning.defaultReturnValue(Integer.MIN_VALUE);
    }

    public static FontFace load(String resource) {
        try (InputStream in = FontFace.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing " + resource);
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length); // must stay alive as long as the font info
            buf.put(bytes).flip();
            return new FontFace(buf);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load font " + resource, e);
        }
    }

    STBTTFontinfo info() {
        return info;
    }

    public int glyph(int codepoint) {
        int g = glyphIndex.get(codepoint);
        if (g < 0) {
            g = STBTruetype.stbtt_FindGlyphIndex(info, codepoint);
            glyphIndex.put(codepoint, g);
        }
        return g;
    }

    public boolean has(int codepoint) {
        return glyph(codepoint) != 0;
    }

    /** Horizontal advance of a glyph, in font units. */
    public int advance(int glyph) {
        int a = advance.get(glyph);
        if (a == Integer.MIN_VALUE) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer adv = stack.mallocInt(1), lsb = stack.mallocInt(1);
                STBTruetype.stbtt_GetGlyphHMetrics(info, glyph, adv, lsb);
                a = adv.get(0);
            }
            advance.put(glyph, a);
        }
        return a;
    }

    /** Kerning adjustment between two glyphs, in font units. */
    public int kern(int left, int right) {
        if (left <= 0 || right <= 0) return 0;
        long key = ((long) left << 32) | (right & 0xFFFFFFFFL);
        int k = kerning.get(key);
        if (k == Integer.MIN_VALUE) {
            k = STBTruetype.stbtt_GetGlyphKernAdvance(info, left, right);
            kerning.put(key, k);
        }
        return k;
    }
}
