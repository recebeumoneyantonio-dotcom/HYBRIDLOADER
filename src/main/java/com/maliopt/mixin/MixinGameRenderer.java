package com.maliopt.mixin;

import com.maliopt.MaliOptMod;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.pipeline.MaliPipelineOptimizer;
import com.maliopt.pipeline.ShaderCacheManager;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    // Flag de init tardio — caso ClientLifecycleEvents não tenha disparado a tempo
    private static boolean lateInitDone = false;

    /**
     * HEAD do frame — init tardio de segurança.
     * MC 1.21.1 usa RenderTickCounter (mudou desde 1.20.5)
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void maliopt$onRenderHead(RenderTickCounter tickCounter,
                                      boolean tick,
                                      CallbackInfo ci) {
        if (!GPUDetector.isMaliGPU()) return;

        if (!lateInitDone && !MaliPipelineOptimizer.isInitialized()) {
            MaliOptMod.LOGGER.info("[MaliOpt] Init tardio — activando via render hook");
            MaliPipelineOptimizer.init();
            ShaderCacheManager.init();
            lateInitDone = true;
        }
    }

    /**
     * TAIL do frame — discard de depth/stencil.
     * Fundamental para TBDR: evita write-back de tile memory desnecessário.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void maliopt$onRenderTail(RenderTickCounter tickCounter,
                                      boolean tick,
                                      CallbackInfo ci) {
        if (!GPUDetector.isMaliGPU()) return;
        MaliPipelineOptimizer.onFrameEnd();
    }
}
