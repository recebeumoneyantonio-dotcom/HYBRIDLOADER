package com.maliopt.gpu;

import com.maliopt.MaliOptMod;

public class ExtensionActivator {

    // ── TIER 1 — TBDR críticas ──────────────────────────────────────
    public static boolean hasDiscardFramebuffer          = false;
    public static boolean hasMaliShaderBinary            = false;
    public static boolean hasMaliProgramBinary           = false; // NOVO Fase 2
    public static boolean hasGetProgramBinary            = false; // NOVO Fase 2
    public static boolean hasParallelShaderCompile       = false; // NOVO Fase 2

    // ── TIER 2 — Texturas e buffers ─────────────────────────────────
    public static boolean hasTextureStorage              = false;
    public static boolean hasBufferStorage               = false; // NOVO Fase 2
    public static boolean hasPackedDepthStencil          = false;
    public static boolean hasDepth24                     = false;
    public static boolean hasMultisampledRenderToTexture = false;

    // ── TIER 3 — TBDR avançado (base para Fase 3) ───────────────────
    public static boolean hasShaderPixelLocalStorage     = false; // NOVO Fase 2
    public static boolean hasFramebufferFetch            = false; // NOVO Fase 2
    public static boolean hasFramebufferFetchDepth       = false; // NOVO Fase 2

    // ── TIER 4 — Texturas comprimidas ───────────────────────────────
    public static boolean hasAstcLdr                     = false; // NOVO Fase 2
    public static boolean hasAstcHdr                     = false; // NOVO Fase 2

    public static void activateAll() {
        if (!GPUDetector.isMaliGPU()) return;

        MaliOptMod.LOGGER.info("[MaliOpt] ══════ Extensões GLES Reais ══════");

        // TIER 1
        MaliOptMod.LOGGER.info("[MaliOpt] ── TIER 1: TBDR Core ──");
        hasDiscardFramebuffer    = check("GL_EXT_discard_framebuffer");
        hasMaliShaderBinary      = check("GL_ARM_mali_shader_binary");
        hasMaliProgramBinary     = check("GL_ARM_mali_program_binary");
        hasGetProgramBinary      = check("GL_OES_get_program_binary");
        hasParallelShaderCompile = check("GL_KHR_parallel_shader_compile");

        // TIER 2
        MaliOptMod.LOGGER.info("[MaliOpt] ── TIER 2: Buffers & Texturas ──");
        hasTextureStorage              = check("GL_EXT_texture_storage");
        hasBufferStorage               = check("GL_EXT_buffer_storage");
        hasPackedDepthStencil          = check("GL_OES_packed_depth_stencil");
        hasDepth24                     = check("GL_OES_depth24");
        hasMultisampledRenderToTexture = check("GL_EXT_multisampled_render_to_texture");

        // TIER 3
        MaliOptMod.LOGGER.info("[MaliOpt] ── TIER 3: TBDR Avançado ──");
        hasShaderPixelLocalStorage = check("GL_EXT_shader_pixel_local_storage");
        hasFramebufferFetch        = check("GL_ARM_shader_framebuffer_fetch");
        hasFramebufferFetchDepth   = check("GL_ARM_shader_framebuffer_fetch_depth_stencil");

        // TIER 4
        MaliOptMod.LOGGER.info("[MaliOpt] ── TIER 4: Compressão ──");
        hasAstcLdr = check("GL_KHR_texture_compression_astc_ldr");
        hasAstcHdr = check("GL_KHR_texture_compression_astc_hdr");

        MaliOptMod.LOGGER.info("[MaliOpt] ══════════════════════════════════");

        // Quando MobileGlues está a limitar as extensões expostas (menos de 50),
        // aplica capacidades conhecidas do hardware Bifrost/Valhall.
        int detected = GPUDetector.getAllExtensions().size();
        if (detected < 50 && (GPUDetector.isBifrost() || GPUDetector.isValhall())) {
            applyForcedMaliMode(detected);
        }

        logCapabilities();
    }

    /**
     * Modo forçado Mali — activa capacidades conhecidas do hardware
     * quando o layer (MobileGlues) não expõe as extensões correctas.
     *
     * ── TIER 1 — GLES 3.0 core / confirmado por ShaderCache ────────
     *   hasDiscardFramebuffer  glInvalidateFramebuffer é GLES 3.0 core.
     *                          TileBasedOptimizer.onFrameEnd() usa-a para
     *                          poupar 2-4 MB/frame de bandwidth de depth.
     *
     *   hasMaliProgramBinary   Confirmado pelo ShaderCacheManager que
     *   hasGetProgramBinary    detectou o formato 0x8f61 no arranque.
     *
     *   hasPackedDepthStencil  GLES 3.0 core — sempre disponível.
     *   hasDepth24             GLES 3.0 core — sempre disponível.
     *
     * ── TIER 2 — GLES 3.0/3.2 core ─────────────────────────────────
     *   hasTextureStorage      glTexStorage2D é GLES 3.0 core.
     *                          MaliPipelineOptimizer.optimizeBoundTexture()
     *                          usa GL42.glTexStorage2D que o LWJGL mapeia
     *                          para a mesma função em GLES 3.x.
     *                          Cria texturas imutáveis — o driver elimina
     *                          a verificação de estado em cada draw call.
     *
     *   hasBufferStorage       glBufferStorage promovido para GLES 3.2 core.
     *                          MobileGlues corre sobre GLES 3.2 — garantido.
     *                          Sem chamadas GL directas no código actual:
     *                          usado apenas para TIER e preparação Fase 3.
     *
     * ── NÃO forçamos ────────────────────────────────────────────────
     *   hasParallelShaderCompile  só funciona com extensão real no driver.
     *   hasShaderPixelLocalStorage  TIER 3 — precisa de driver real.
     *   hasFramebufferFetch         TIER 3 — precisa de driver real.
     */
    private static void applyForcedMaliMode(int detected) {
        MaliOptMod.LOGGER.info("[MaliOpt] ⚠️  Layer a limitar extensões ({} detectadas de ~102 esperadas)", detected);
        MaliOptMod.LOGGER.info("[MaliOpt] 🔧 Modo forçado Mali Bifrost — aplicando capacidades GLES 3.0/3.2 confirmadas");

        // ── TIER 1 ───────────────────────────────────────────────────
        if (!hasDiscardFramebuffer) {
            hasDiscardFramebuffer = true;
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ hasDiscardFramebuffer  [forçado — GLES 3.0 core]");
        }
        if (!hasPackedDepthStencil) {
            hasPackedDepthStencil = true;
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ hasPackedDepthStencil  [forçado — GLES 3.0 core]");
        }
        if (!hasDepth24) {
            hasDepth24 = true;
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ hasDepth24             [forçado — GLES 3.0 core]");
        }
        if (!hasMaliProgramBinary) {
            hasMaliProgramBinary = true;
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ hasMaliProgramBinary   [forçado — formato 0x8f61 confirmado]");
        }
        if (!hasGetProgramBinary) {
            hasGetProgramBinary = true;
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ hasGetProgramBinary    [forçado — GLES 3.0 core]");
        }

        // ── TIER 2 ───────────────────────────────────────────────────
        if (!hasTextureStorage) {
            hasTextureStorage = true;
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ hasTextureStorage      [forçado — glTexStorage2D GLES 3.0 core]");
        }
        if (!hasBufferStorage) {
            hasBufferStorage = true;
            MaliOptMod.LOGGER.info("[MaliOpt]   ✅ hasBufferStorage       [forçado — glBufferStorage GLES 3.2 core]");
        }
    }

    private static boolean check(String ext) {
        boolean present = GPUDetector.hasExtension(ext);
        MaliOptMod.LOGGER.info("[MaliOpt]   {} {}", present ? "✅" : "❌", ext);
        return present;
    }

    private static void logCapabilities() {
        int tier = 0;
        if (hasDiscardFramebuffer && hasMaliProgramBinary) tier = 1;
        if (tier == 1 && hasTextureStorage && hasBufferStorage) tier = 2;
        if (tier == 2 && hasShaderPixelLocalStorage && hasFramebufferFetch) tier = 3;

        MaliOptMod.LOGGER.info("[MaliOpt] Nível de optimização: TIER {}/3", tier);

        if (hasParallelShaderCompile)
            MaliOptMod.LOGGER.info("[MaliOpt] ⚡ Compilação paralela de shaders ACTIVA");
        if (hasShaderPixelLocalStorage)
            MaliOptMod.LOGGER.info("[MaliOpt] ⚡ Pixel Local Storage DISPONÍVEL (Fase 3)");
        if (hasFramebufferFetch)
            MaliOptMod.LOGGER.info("[MaliOpt] ⚡ Framebuffer Fetch DISPONÍVEL (Fase 3/4)");
    }
}
