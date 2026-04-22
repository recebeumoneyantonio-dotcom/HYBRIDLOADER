package com.maliopt;

import com.maliopt.config.MaliOptConfig;
import com.maliopt.config.MaliOptConfigScreen;
import com.maliopt.config.MaliOptVisualConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.MobileGluesDetector;
import com.maliopt.mixin.GameOptionsAccessor;
import com.maliopt.performance.PerformanceGuard;
import com.maliopt.pipeline.FBFetchBloomPass;
import com.maliopt.pipeline.MaliPipelineOptimizer;
import com.maliopt.pipeline.PLSLightingPass;
import com.maliopt.pipeline.ShadowPass;
import com.maliopt.pipeline.SSRPass;
import com.maliopt.pipeline.ColoredLightsPass;
import com.maliopt.pipeline.ShaderCacheManager;
import com.maliopt.shader.ShaderCache;
import com.maliopt.shader.ShaderCapabilities;
import com.maliopt.shader.ShaderExecutionLayer;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MaliOptMod implements ClientModInitializer, ModMenuApi {

    public static final String MOD_ID = "maliopt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int MAX_RENDER_DISTANCE     = 3;
    private static final int MAX_SIMULATION_DISTANCE = 5;

    private static boolean nativePluginLoaded = false;

    public static boolean isNativePluginLoaded() { return nativePluginLoaded; }

    // ── ModMenu API ───────────────────────────────────────────────────
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MaliOptConfigScreen::create;
    }

    // ── Inicialização ─────────────────────────────────────────────────
    @Override
    public void onInitializeClient() {
        LOGGER.info("[MaliOpt] Iniciando...");

        // 1. Carregar configs (legacy + nova visual)
        MaliOptConfig.load();
        MaliOptVisualConfig.load();

        // 2. Plugin nativo
        nativePluginLoaded = loadNativePlugin();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            MobileGluesDetector.detect();

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
                ShaderCapabilities.init(nativePluginLoaded);
                ShaderExecutionLayer.init();

                try {
                    ShaderCache.init(FabricLoader.getInstance().getGameDir());
                } catch (Exception e) {
                    LOGGER.warn("[MaliOpt] ShaderCache.init falhou: {}", e.getMessage());
                }

                MaliPipelineOptimizer.init();
                ShaderCacheManager.init();

                // Passes existentes
                PLSLightingPass.init();
                FBFetchBloomPass.init();

                // Novos passes visuais
                ShadowPass.init();
                SSRPass.init();
                ColoredLightsPass.init();

                forceDistances(client);

            } else {
                LOGGER.info("[MaliOpt] ⚠️  GPU não é Mali — mod inactivo");
            }
        });

        // ── Pipeline de post-process ──────────────────────────────────
        WorldRenderEvents.LAST.register(context -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            MaliOptVisualConfig cfg = MaliOptVisualConfig.get();

            PerformanceGuard.update(mc);

            // Lighting pass — respeita toggle do menu
            if (PLSLightingPass.isReady()
                    && cfg.lightingEnabled
                    && PerformanceGuard.lightingPassEnabled()) {
                PLSLightingPass.render(mc);
            }

            // Bloom — respeita toggle do menu
            if (FBFetchBloomPass.isReady()
                    && cfg.bloomEnabled
                    && PerformanceGuard.bloomEnabled()) {
                FBFetchBloomPass.render(mc);
            }

            // Sombras
            if (ShadowPass.isReady() && cfg.shadowsEnabled) {
                ShadowPass.render(mc);
            }

            // SSR
            if (SSRPass.isReady() && cfg.ssrEnabled) {
                SSRPass.render(mc);
            }

            // Luzes coloridas
            if (ColoredLightsPass.isReady() && cfg.coloredLightsEnabled) {
                ColoredLightsPass.render(mc);
            }
        });

        // Cleanup
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            PLSLightingPass.cleanup();
            FBFetchBloomPass.cleanup();
            ShadowPass.cleanup();
            SSRPass.cleanup();
            ColoredLightsPass.cleanup();
        });
    }

    // ── Plugin nativo ─────────────────────────────────────────────────

    private static boolean loadNativePlugin() {
        try {
            System.loadLibrary("maliopt");
            LOGGER.info("[MaliOpt] ✅ libmaliopt.so carregado");
            return true;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[MaliOpt] Plugin nativo não disponível — fallback OpenGL");
            return false;
        } catch (SecurityException e) {
            LOGGER.warn("[MaliOpt] Permissão negada: {}", e.getMessage());
            return false;
        }
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
            }

            SimpleOption<Integer> simDist = acc.maliopt_getSimulationDistance();
            int currentSim = simDist.getValue();
            if (currentSim > MAX_SIMULATION_DISTANCE) {
                simDist.setValue(MAX_SIMULATION_DISTANCE);
                LOGGER.info("[MaliOpt] Simulation distance: {} → {} ✅", currentSim, MAX_SIMULATION_DISTANCE);
                changed = true;
            }

            if (changed) client.options.write();

        } catch (Exception e) {
            LOGGER.warn("[MaliOpt] forceDistances falhou: {}", e.getMessage());
        }
    }

    private static String formatMGVersion(int v) {
        if (v <= 0) return "desconhecida";
        return (v / 1000) + "." + ((v % 1000) / 100) + "." + (v % 100);
    }
                }
