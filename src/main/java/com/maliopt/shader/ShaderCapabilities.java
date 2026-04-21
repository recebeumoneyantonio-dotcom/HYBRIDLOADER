package com.maliopt.shader;

import com.maliopt.MaliOptMod;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.ExtensionActivator;

public final class ShaderCapabilities {

    // ── Extensões de framebuffer ─────────────────────────────────────
    public static boolean PLS              = false;
    public static boolean FB_FETCH         = false;
    public static boolean FB_FETCH_DEPTH   = false;

    // ── Compressão de texturas ───────────────────────────────────────
    public static boolean ASTC             = false;
    public static boolean ASTC_HDR         = false;

    // ── Render targets ───────────────────────────────────────────────
    public static boolean MULTISAMPLED_RT  = false;

    // ── Arquitectura GPU ─────────────────────────────────────────────
    public static boolean TBDR             = false;
    public static boolean BIFROST          = false;
    public static boolean VALHALL          = false;

    // ── Capacidades de shader ────────────────────────────────────────
    public static boolean MEDIUMP_FAST     = false;
    public static boolean FP16_ARITHMETIC  = false;

    // ── Estado ───────────────────────────────────────────────────────
    private static boolean initialised = false;

    private ShaderCapabilities() {}

    public static void init() {
        if (initialised) return;

        // Lê TUDO do ExtensionActivator — fonte única de verdade
        PLS            = ExtensionActivator.hasShaderPixelLocalStorage;
        FB_FETCH       = ExtensionActivator.hasFramebufferFetch;
        FB_FETCH_DEPTH = ExtensionActivator.hasFramebufferFetchDepth;

        ASTC           = ExtensionActivator.hasAstcLdr;
        ASTC_HDR       = ExtensionActivator.hasAstcHdr;
        MULTISAMPLED_RT= ExtensionActivator.hasMultisampledRenderToTexture;

        // FP16 — verificação direta (não existe no ExtensionActivator)
        FP16_ARITHMETIC = GPUDetector.hasExtension(
            "GL_EXT_shader_explicit_arithmetic_types_float16");

        // Arquitectura GPU — via getGPUModel() (alias de getRenderer())
        String model = GPUDetector.getGPUModel();
        if (model == null) model = "";

        TBDR    = model.contains("Mali-G");
        BIFROST = model.matches(".*Mali-G(31|51|52|71|72|76|77).*");
        VALHALL = model.matches(".*Mali-G(57|68|78|310|510|610|710|615|715).*");

        // Mali sempre tem mediump mais rápido (arquitectura SIMD 16-bit)
        MEDIUMP_FAST = TBDR;

        initialised = true;
        logCapabilities();
    }

    /** Pode usar o caminho TBDR ultra-eficiente (PLS + FBFetch) */
    public static boolean canUseTBDRPath() {
        return TBDR && (PLS || FB_FETCH);
    }

    /** Pode usar bloom zero-DRAM */
    public static boolean canUseZeroCopyBloom() {
        return FB_FETCH;
    }

    /** Qualidade recomendada para este hardware */
    public static ShaderQuality recommendedQuality() {
        if (VALHALL) return ShaderQuality.HIGH;
        if (BIFROST) return ShaderQuality.MEDIUM;
        return ShaderQuality.LOW;
    }

    public enum ShaderQuality { LOW, MEDIUM, HIGH }

    private static void logCapabilities() {
        MaliOptMod.LOGGER.info("[MaliOpt] ═══ ShaderCapabilities ═══════════════");
        MaliOptMod.LOGGER.info("[MaliOpt]  PLS:              {}", PLS);
        MaliOptMod.LOGGER.info("[MaliOpt]  FB_FETCH:         {}", FB_FETCH);
        MaliOptMod.LOGGER.info("[MaliOpt]  FB_FETCH_DEPTH:   {}", FB_FETCH_DEPTH);
        MaliOptMod.LOGGER.info("[MaliOpt]  ASTC:             {}", ASTC);
        MaliOptMod.LOGGER.info("[MaliOpt]  MULTISAMPLED_RT:  {}", MULTISAMPLED_RT);
        MaliOptMod.LOGGER.info("[MaliOpt]  TBDR:             {}", TBDR);
        MaliOptMod.LOGGER.info("[MaliOpt]  BIFROST:          {}", BIFROST);
        MaliOptMod.LOGGER.info("[MaliOpt]  VALHALL:          {}", VALHALL);
        MaliOptMod.LOGGER.info("[MaliOpt]  MEDIUMP_FAST:     {}", MEDIUMP_FAST);
        MaliOptMod.LOGGER.info("[MaliOpt]  FP16_ARITHMETIC:  {}", FP16_ARITHMETIC);
        MaliOptMod.LOGGER.info("[MaliOpt]  Quality:          {}", recommendedQuality());
        MaliOptMod.LOGGER.info("[MaliOpt] ══════════════════════════════════════");
    }

    public static boolean isInitialised() { return initialised; }
}
