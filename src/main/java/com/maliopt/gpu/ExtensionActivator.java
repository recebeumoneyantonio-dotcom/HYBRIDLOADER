package com.maliopt.gpu;

import com.maliopt.MaliOptMod;

public class ExtensionActivator {

    // Flags usadas pelo resto do mod para tomar decisões
    public static boolean hasDiscardFramebuffer          = false;
    public static boolean hasMaliShaderBinary            = false;
    public static boolean hasMultisampledRenderToTexture = false;
    public static boolean hasTextureStorage              = false;
    public static boolean hasPackedDepthStencil          = false;
    public static boolean hasDepth24                     = false;

    public static void activateAll() {
        if (!GPUDetector.isMaliGPU()) return;

        MaliOptMod.LOGGER.info("[MaliOpt] ======= Extensões GL =======");

        // TIER 1 — críticas para TBDR
        hasDiscardFramebuffer = check("GL_EXT_discard_framebuffer");
        hasMaliShaderBinary   = check("GL_ARM_mali_shader_binary");

        // TIER 2 — optimizações adicionais
        hasMultisampledRenderToTexture = check("GL_EXT_multisampled_render_to_texture");
        hasTextureStorage              = check("GL_EXT_texture_storage");
        hasPackedDepthStencil          = check("GL_OES_packed_depth_stencil");
        hasDepth24                     = check("GL_OES_depth24");

        MaliOptMod.LOGGER.info("[MaliOpt] ============================");

        if      (GPUDetector.isBifrost()) LOGGER_INFO("Arquitectura: Bifrost ✅");
        else if (GPUDetector.isValhall()) LOGGER_INFO("Arquitectura: Valhall ✅");
        else                              LOGGER_INFO("Arquitectura: Mali genérico");
    }

    private static boolean check(String ext) {
        boolean present = GPUDetector.hasExtension(ext);
        MaliOptMod.LOGGER.info("[MaliOpt]   {} {}", present ? "✅" : "❌", ext);
        return present;
    }

    private static void LOGGER_INFO(String msg) {
        MaliOptMod.LOGGER.info("[MaliOpt] {}", msg);
    }
}
