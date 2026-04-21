package com.maliopt;

import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.MobileGluesDetector;
import com.maliopt.mixin.GameOptionsAccessor;
import com.maliopt.pipeline.FBFetchBloomPass;
import com.maliopt.pipeline.MaliPipelineOptimizer;
import com.maliopt.pipeline.PLSLightingPass;
import com.maliopt.pipeline.ShaderCacheManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MaliOptMod implements ClientModInitializer {

    public static final String MOD_ID = "maliopt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int MAX_RENDER_DISTANCE     = 3;
    private static final int MAX_SIMULATION_DISTANCE = 5;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[MaliOpt] Registando eventos...");
        MaliOptConfig.load();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            MobileGluesDetector.detect();

            LOGGER.info("[MaliOpt] Cliente iniciado — verificando GPU...");
            LOGGER.info("[MaliOpt] Renderer : {}", GPUDetector.getRenderer());
            LOGGER.info("[MaliOpt] Vendor   : {}", GPUDetector.getVendor());
            LOGGER.info("[MaliOpt] Version  : {}", GPUDetector.getVersion());

            if (MobileGluesDetector.isActive()) {
                LOGGER.info("[MaliOpt] GL Layer : MobileGlues v{} ✅",
                    formatMGVersion(MobileGluesDetector.mobileGluesVersion));
            } else {
                LOGGER.info("[MaliOpt] GL Layer : GL4ES (extensões Mali limitadas)");
            }

            if (GPUDetector.isMaliGPU()) {
                LOGGER.info("[MaliOpt] ✅ GPU Mali detectada — activando optimizações");

                ExtensionActivator.activateAll();
                MaliPipelineOptimizer.init();
                ShaderCacheManager.init();

                // Fase 3a — Lighting pass
                PLSLightingPass.init();

                // Fase 3b — Bloom via FBFetch
                FBFetchBloomPass.init();

                forceDistances(client);

            } else {
                LOGGER.info("[MaliOpt] ⚠️  GPU não é Mali — mod inactivo");
            }
        });

        // ── Post-process pipeline ─────────────────────────────────
        // Ordem importante: primeiro lighting, depois bloom.
        // Ambos correm após o mundo estar completamente renderizado.
        WorldRenderEvents.LAST.register(context -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            // 1. PLSLightingPass — warmth, AO, gamma
            if (PLSLightingPass.isReady()) {
                PLSLightingPass.render(mc);
            }

            // 2. FBFetchBloomPass — bloom com ou sem FBFetch
            //    Se FBFetch disponível: lê da tile memory (ZERO DRAM)
            //    Se não: texture sample (1 pass, ainda mais rápido que Iris)
            if (FBFetchBloomPass.isReady()) {
                FBFetchBloomPass.render(mc);
            }
        });

        // Cleanup ao fechar
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            PLSLightingPass.cleanup();
            FBFetchBloomPass.cleanup();
        });
    }

    private static void forceDistances(MinecraftClient client) {
        if (client == null || client.options == null) return;
        try {
            boolean changed = false;
            GameOptionsAccessor acc = (GameOptionsAccessor)(Object) client.options;

            SimpleOption<Integer> viewDist = acc.maliopt_getViewDistance();
            int currentRender = viewDist.getValue();
            if (currentRender > MAX_RENDER_DISTANCE) {
                viewDist.setValue(MAX_RENDER_DISTANCE);
                LOGGER.info("[MaliOpt] Render distance: {} → {} ✅", currentRender, MAX_RENDER_DISTANCE);
                changed = true;
            } else {
                LOGGER.info("[MaliOpt] Render distance: {} (já dentro do limite)", currentRender);
            }

            SimpleOption<Integer> simDist = acc.maliopt_getSimulationDistance();
            int currentSim = simDist.getValue();
            if (currentSim > MAX_SIMULATION_DISTANCE) {
                simDist.setValue(MAX_SIMULATION_DISTANCE);
                LOGGER.info("[MaliOpt] Simulation distance: {} → {} ✅", currentSim, MAX_SIMULATION_DISTANCE);
                changed = true;
            } else {
                LOGGER.info("[MaliOpt] Simulation distance: {} (já dentro do limite)", currentSim);
            }

            if (changed) {
                client.options.write();
                LOGGER.info("[MaliOpt] Distâncias guardadas em options.txt ✅");
            }

        } catch (Exception e) {
            LOGGER.warn("[MaliOpt] forceDistances falhou: {}", e.getMessage());
        }
    }

    private static String formatMGVersion(int v) {
        if (v <= 0) return "desconhecida";
        return (v / 1000) + "." + ((v % 1000) / 100) + "." + (v % 100);
    }
}
