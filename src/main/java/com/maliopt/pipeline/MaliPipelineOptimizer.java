package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

public class MaliPipelineOptimizer {

    private static boolean initialized = false;

    // Attachments a descartar no fim do frame
    // Depth + Stencil: Mali TBDR não precisa escrever estes de volta para DRAM
    private static final int[] DISCARD_ATTACHMENTS = {
        GL30.GL_DEPTH_ATTACHMENT,
        GL30.GL_STENCIL_ATTACHMENT
    };

    /**
     * Chamado UMA VEZ após GL context disponível.
     * NUNCA chamar por frame.
     */
    public static void init() {
        if (initialized || !GPUDetector.isMaliGPU()) return;
        initialized = true;

        MaliOptMod.LOGGER.info("[MaliOpt] Inicializando MaliPipelineOptimizer...");

        if (MaliOptConfig.enableDepthOpt) {
            applyDepthHints();
        }

        MaliOptMod.LOGGER.info("[MaliOpt] MaliPipelineOptimizer pronto ✅");
    }

    /**
     * Chamado UMA VEZ — configura hints de depth.
     */
    private static void applyDepthHints() {
        try {
            // Range default mas explícito — evita estado indeterminado no GL4ES
            GL11.glDepthRange(0.0, 1.0);
            GL11.glDepthFunc(GL11.GL_LEQUAL);

            MaliOptMod.LOGGER.info("[MaliOpt] Depth hints aplicados ✅");
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] Depth hints falharam: {}", e.getMessage());
        }
    }

    /**
     * Optimiza uma textura que já está em bind.
     * APENAS para texturas do próprio mod — não chamar aleatoriamente.
     * Chamar logo após glBindTexture().
     */
    public static void optimizeBoundTexture() {
        if (!MaliOptConfig.enableTextureOpt) return;
        try {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,
                GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,
                GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            // GL_CLAMP_TO_EDGE (GL12) — GL_CLAMP está deprecated
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,
                GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,
                GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] optimizeBoundTexture falhou: {}", e.getMessage());
        }
    }

    /**
     * Chamado no fim de cada frame.
     * glInvalidateFramebuffer (GL4.3) = glDiscardFramebufferEXT em GLES.
     * GL4ES traduz correctamente.
     * Poupa write-back de tile memory → DRAM no TBDR Mali.
     */
    public static void onFrameEnd() {
        if (!MaliOptConfig.enableDiscardFBO) return;
        if (!ExtensionActivator.hasDiscardFramebuffer) return;

        try {
            GL43.glInvalidateFramebuffer(GL30.GL_FRAMEBUFFER, DISCARD_ATTACHMENTS);
        } catch (Exception e) {
            // Falha silenciosa — não bloqueia rendering
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
