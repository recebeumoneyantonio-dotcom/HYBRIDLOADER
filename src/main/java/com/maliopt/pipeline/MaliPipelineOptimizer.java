package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL44;

public class MaliPipelineOptimizer {

    private static boolean initialized = false;

    public static void init() {
        if (initialized || !GPUDetector.isMaliGPU()) return;
        initialized = true;

        MaliOptMod.LOGGER.info("[MaliOpt] MaliPipelineOptimizer iniciando...");

        if (MaliOptConfig.enableDepthOpt)    applyDepthHints();
        if (MaliOptConfig.enableTileOptimizer) TileBasedOptimizer.init();

        MaliOptMod.LOGGER.info("[MaliOpt] MaliPipelineOptimizer pronto ✅");
    }

    private static void applyDepthHints() {
        try {
            GL11.glDepthRange(0.0, 1.0);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            MaliOptMod.LOGGER.info("[MaliOpt] Depth hints aplicados ✅");
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] Depth hints falharam: {}", e.getMessage());
        }
    }

    /**
     * Optimiza textura já em bind.
     * FASE 2: usa glTexStorage2D se GL_EXT_texture_storage disponível.
     * Texturas imutáveis = driver não precisa de re-validar estado a cada draw.
     *
     * Chamar logo após glBindTexture() + glTexImage2D().
     */
    public static void optimizeBoundTexture(int width, int height, int levels) {
        if (!MaliOptConfig.enableTextureOpt) return;
        try {
            // Filtros optimizados para Mali — NEAREST mais rápido em TBDR
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            // FASE 2 — textura imutável se extensão disponível
            if (MaliOptConfig.enableTextureStorage && ExtensionActivator.hasTextureStorage) {
                // GL42 = GL_EXT_texture_storage em GLES via GL4ES
                GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, GL30.GL_RGBA8, width, height);
            }
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] optimizeBoundTexture: {}", e.getMessage());
        }
    }

    /**
     * Versão simples sem texStorage (compatível com qualquer textura existente).
     */
    public static void optimizeBoundTexture() {
        if (!MaliOptConfig.enableTextureOpt) return;
        try {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] optimizeBoundTexture simple: {}", e.getMessage());
        }
    }

    /** Fim de frame — delega para TileBasedOptimizer */
    public static void onFrameEnd() {
        TileBasedOptimizer.onFrameEnd();
    }

    public static boolean isInitialized() { return initialized; }
}
