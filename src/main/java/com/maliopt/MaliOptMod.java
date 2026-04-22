package com.maliopt;

import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.MobileGluesDetector;
import com.maliopt.mixin.GameOptionsAccessor;
import com.maliopt.performance.PerformanceGuard;
import com.maliopt.pipeline.FBFetchBloomPass;
import com.maliopt.pipeline.MaliPipelineOptimizer;
import com.maliopt.pipeline.PLSLightingPass;
import com.maliopt.pipeline.ShaderCacheManager;
import com.maliopt.shader.ShaderCache;
import com.maliopt.shader.ShaderCapabilities;
import com.maliopt.shader.ShaderExecutionLayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MaliOptMod implements ClientModInitializer {

    public static final String MOD_ID = "maliopt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int MAX_RENDER_DISTANCE     = 3;
    private static final int MAX_SIMULATION_DISTANCE = 5;

    // ── Estado do plugin nativo ────────────────────────────────────────
    private static boolean nativePluginLoaded = false;

    /** Indica se libmaliopt.so foi carregada com sucesso. */
    public static boolean isNativePluginLoaded() {
        return nativePluginLoaded;
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("[MaliOpt] Registando eventos...");
        MaliOptConfig.load();

        // Tenta carregar o plugin nativo o mais cedo possível —
        // antes do CLIENT_STARTED, para que ShaderCapabilities
        // já saiba qual caminho usar durante o init.
        nativePluginLoaded = loadNativePlugin();

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

                // ── 1. Extensões hardware ────────────────────────────
                ExtensionActivator.activateAll();

                // ── 2. Capacidades de shader ─────────────────────────
                // Passa o flag nativo — ShaderCapabilities escolhe
                // automaticamente o melhor caminho disponível.
                ShaderCapabilities.init(nativePluginLoaded);

                // ── 3. Camada de execução de shaders ─────────────────
                ShaderExecutionLayer.init();

                // ── 4. Cache de shaders compilados ───────────────────
                try {
                    ShaderCache.init(FabricLoader.getInstance().getGameDir());
                } catch (Exception e) {
                    LOGGER.warn("[MaliOpt] ShaderCache.init falhou: {}", e.getMessage());
                }

                // ── 5. Pipeline de renderização ───────────────────────
                MaliPipelineOptimizer.init();
                ShaderCacheManager.init();

                // ── 6. Passes de post-processing ─────────────────────
                PLSLightingPass.init();
                FBFetchBloomPass.init();

                forceDistances(client);

            } else {
                LOGGER.info("[MaliOpt] ⚠️  GPU não é Mali — mod inactivo");
            }
        });

        // ── Post-process pipeline ─────────────────────────────────
        WorldRenderEvents.LAST.register(context -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            // 0. Atualiza monitor de performance (uma vez por frame)
            PerformanceGuard.update(mc);

            // 1. PLSLightingPass — warmth, AO, gamma
            if (PLSLightingPass.isReady() && PerformanceGuard.lightingPassEnabled()) {
                PLSLightingPass.render(mc);
            }

            // 2. FBFetchBloomPass — bloom adaptativo
            if (FBFetchBloomPass.isReady() && PerformanceGuard.bloomEnabled()) {
                FBFetchBloomPass.render(mc);
            }
        });

        // Cleanup ao fechar
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            PLSLightingPass.cleanup();
            FBFetchBloomPass.cleanup();
        });
    }

    // ════════════════════════════════════════════════════════════════
    // PLUGIN NATIVO
    // ════════════════════════════════════════════════════════════════

    /**
     * Tenta carregar libmaliopt.so do plugin APK.
     *
     * O ZalithLauncher inclui automaticamente a biblioteca do plugin
     * no java.library.path do processo Minecraft.
     * Se a biblioteca não for encontrada, o mod continua normalmente
     * usando o caminho OpenGL via ExtensionActivator (fallback seguro).
     *
     * @return true se carregou com sucesso, false caso contrário.
     */
    private static boolean loadNativePlugin() {
        try {
            System.loadLibrary("maliopt");
            LOGGER.info("[MaliOpt] ✅ Plugin nativo libmaliopt.so carregado — " +
                        "extensões reais do driver activas");
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[MaliOpt] Plugin nativo não disponível — " +
                        "usando detecção OpenGL (fallback seguro)");
            return false;
        } catch (SecurityException e) {
            LOGGER.warn("[MaliOpt] Permissão negada ao carregar plugin nativo: {}",
                        e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════

    private static void forceDistances(MinecraftClient client) {
        if (client == null || client.options == null) return;
        try {
            boolean changed = false;
            GameOptionsAccessor acc = (GameOptionsAccessor)(Object) client.options;

            SimpleOption<Integer> viewDist = acc.maliopt_getViewDistance();
            int currentRender = viewDist.getValue();
            if (currentRender > MAX_RENDER_DISTANCE) {
                viewDist.setValue(MAX_RENDER_DISTANCE);
                LOGGER.info("[MaliOpt] Render distance: {} → {} ✅",
                    currentRender, MAX_RENDER_DISTANCE);
                changed = true;
            } else {
                LOGGER.info("[MaliOpt] Render distance: {} (já dentro do limite)",
                    currentRender);
            }

            SimpleOption<Integer> simDist = acc.maliopt_getSimulationDistance();
            int currentSim = simDist.getValue();
            if (currentSim > MAX_SIMULATION_DISTANCE) {
                simDist.setValue(MAX_SIMULATION_DISTANCE);
                LOGGER.info("[MaliOpt] Simulation distance: {} → {} ✅",
                    currentSim, MAX_SIMULATION_DISTANCE);
                changed = true;
            } else {
                LOGGER.info("[MaliOpt] Simulation distance: {} (já dentro do limite)",
                    currentSim);
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
