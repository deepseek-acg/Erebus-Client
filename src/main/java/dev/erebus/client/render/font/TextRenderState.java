package dev.erebus.client.render.font;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/** A run of glyph quads from one atlas page, submitted to the GUI as a single element. */
record TextRenderState(
        RenderPipeline pipeline,
        TextureSetup textureSetup,
        Matrix3x2f pose,
        float[] quads,
        int count,
        @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements GuiElementRenderState {
    /** Floats per quad: x0, y0, x1, y1, u0, v0, u1, v1, color (as float bits). */
    static final int STRIDE = 9;

    @Override
    public void buildVertices(VertexConsumer vc) {
        for (int i = 0; i < count; i++) {
            int o = i * STRIDE;
            float x0 = quads[o], y0 = quads[o + 1], x1 = quads[o + 2], y1 = quads[o + 3];
            float u0 = quads[o + 4], v0 = quads[o + 5], u1 = quads[o + 6], v1 = quads[o + 7];
            int c = Float.floatToRawIntBits(quads[o + 8]);
            vc.addVertexWith2DPose(pose, x0, y0).setUv(u0, v0).setColor(c);
            vc.addVertexWith2DPose(pose, x0, y1).setUv(u0, v1).setColor(c);
            vc.addVertexWith2DPose(pose, x1, y1).setUv(u1, v1).setColor(c);
            vc.addVertexWith2DPose(pose, x1, y0).setUv(u1, v0).setColor(c);
        }
    }
}
