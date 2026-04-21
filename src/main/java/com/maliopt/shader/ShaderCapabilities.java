package com.maliopt.shader;

import com.maliopt.MaliOptMod;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.ExtensionActivator;

/**
 * ShaderCapabilities — Fase 3c (1/4)
 *
 * Detecção central de capacidades do Mali para o ShaderExecutionLayer.
 * Todos os passes e shaders consultam esta classe — nunca chamam
 * GLES directamente para verificar extensões.
 *
 * ── PIPELINE DE INICIALIZAÇÃO ─────────────────────────────────────
 *   1. GPUDetector detecta modelo GPU (G52, G76, G710...)
 *   2. ExtensionActivator activa extensões GLES disponíveis
 *   3. ShaderCapabilities.init() lê os resultados e expõe flags
 *   4. ShaderExecutionLayer usa os flags para injectar #defines
 *
 * ── FLAGS DISPONÍVEIS ─────────────────────────────────────────────
 *   PLS              → GL_EXT_shader_pixel_local_storage
 *   FB_FETCH         → GL_ARM_shader_framebuffer_fetch
 *   FB_FETCH_DEPTH   → GL_ARM_shader_framebuffer_fetch_depth_stencil
 *   ASTC             → GL_KHR_texture_compression_astc_ldr
 *   MULTISAMPLED_RT  → GL_EXT_multisampled_render_to_texture
 *   TBDR             → deduzido do modelo Mali (G-series = TBDR)
 *   BIFROST          → Mali G51/G52/G71/G72/G76/G77
 *   VALHALL          → Mali G57/G68/G78/G310/G510/G610/G710
 */
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
    public static boolean TBDR             = false;   // Tile-Based Deferred Rendering
    public static boolean BIFROST          = false;
    public static boolean VALHALL          = false;

    // ── Capacidades de shader ────────────────────────────────────────
    public static boolean MEDIUMP_FAST     = false;   // mediump mais rápido que highp
    public static boolean FP16_ARITHMETIC  = false;   // GL_EXT_shader_explicit_arithmetic_types_float16

    // ── Estado ───────────────────────────────────────────────────────
    private static boolean initialised = false;

    private ShaderCapabilities() {}

    // ════════════════════════════════════════════════════════════════
    // INIT — chamar depois de GPUDetector e ExtensionActivator
    // ════════════════════════════════════════════════════════════════

    public static void init() {
        if (initialised) return;

        // Extensões de framebuffer (vindas do ExtensionActivator)
        PLS            = ExtensionActivator.hasPixelLocalStorage;
        FB_FETCH       = ExtensionActivator.hasFramebufferFetch;
        FB_FETCH_DEPTH = ExtensionActivator.hasFramebufferFetchDepth;

        // Extensões de textura (detectar directamente)
        String ext = getExtensionString();
        ASTC           = ext.contains("GL_KHR_texture_compression_astc_ldr");
        ASTC_HDR       = ext.contains("GL_KHR_texture_compression_astc_hdr");
        MULTISAMPLED_RT= ext.contains("GL_EXT_multisampled_render_to_texture");
        FP16_ARITHMETIC= ext.contains("GL_EXT_shader_explicit_arithmetic_types_float16");

        // Arquitectura GPU
        String model = GPUDetector.getGPUModel(); // ex: "Mali-G52 MC2"
        TBDR     = model.contains("Mali-G");
        BIFROST  = isBifrost(model);
        VALHALL  = isValhall(model);

        // Mali sempre tem mediump mais rápido (arquitectura SIMD 16-bit)
        MEDIUMP_FAST = TBDR;

        initialised = true;
        logCapabilities();
    }

    // ════════════════════════════════════════════════════════════════
    // DETECÇÃO DE ARQUITECTURA
    // ════════════════════════════════════════════════════════════════

    private static boolean isBifrost(String model) {
        // Bifrost: G31, G51, G52, G71, G72, G76, G77
        return model.matches(".*Mali-G(31|51|52|71|72|76|77).*");
    }

    private static boolean isValhall(String model) {
        // Valhall: G57, G68, G78, G310, G510, G610, G710, G615, G715
        return model.matches(".*Mali-G(57|68|78|310|510|610|710|615|715).*");
    }

    private static String getExtensionString() {
        try {
            String s = org.lwjgl.opengl.GL11.glGetString(
                org.lwjgl.opengl.GL11.GL_EXTENSIONS);
            return s != null ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ════════════════════════════════════════════════════════════════
    // QUERIES COMPOSTAS (usadas pelo ShaderExecutionLayer)
    // ════════════════════════════════════════════════════════════════

    /** Pode usar o caminho TBDR ultra-eficiente (PLS + FBFetch) */
    public static boolean canUseTBDRPath() {
        return TBDR && (PLS || FB_FETCH);
    }

    /** Pode usar o bloom path zero-DRAM */
    public static boolean canUseZeroCopyBloom() {
        return FB_FETCH;
    }

    /** Qualidade de shader recomendada */
    public static ShaderQuality recommendedQuality() {
        if (VALHALL)  return ShaderQuality.HIGH;
        if (BIFROST)  return ShaderQuality.MEDIUM;
        return ShaderQuality.LOW;
    }

    public enum ShaderQuality { LOW, MEDIUM, HIGH }

    // ════════════════════════════════════════════════════════════════
    // LOG
    // ════════════════════════════════════════════════════════════════

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
        MaliOptMod.LOGGER.info("[MaliOpt]  Quality:          {}", recommendedQuality());
        MaliOptMod.LOGGER.info("[MaliOpt] ══════════════════════════════════════");
    }

    public static boolean isInitialised() { return initialised; }
          }
