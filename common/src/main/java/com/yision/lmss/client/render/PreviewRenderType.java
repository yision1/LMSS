package com.yision.lmss.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class PreviewRenderType extends RenderType {
    private PreviewRenderType(List<RenderStateShard> states) {
        super("lmss_preview", DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS,
                256, false, true, () -> states.forEach(RenderStateShard::setupRenderState),
                () -> states.forEach(RenderStateShard::clearRenderState));
    }

    public static RenderType create(ResourceLocation texture) {
        return new PreviewRenderType(List.of(
                new ShaderStateShard(GameRenderer::getPositionTexShader),
                new TextureStateShard(texture, true, false),
                TRANSLUCENT_TRANSPARENCY, LEQUAL_DEPTH_TEST, COLOR_WRITE, NO_CULL));
    }
}
