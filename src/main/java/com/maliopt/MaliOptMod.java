package com.maliopt;

import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import com.maliopt.gpu.MobileGluesDetector;
import com.maliopt.pipeline.MaliPipelineOptimizer;
import com.maliopt.pipeline.ShaderCacheManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MaliOptMod implements ClientModInitializer {

    public static final String MOD_ID = "maliopt";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[MaliOpt] Registando eventos...");

        // Config carrega sem GL — seguro aqui
        MaliOptConfig.load();

        // GL context SÓ existe após CLIENT_STARTED
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            // ── PASSO 1: Detectar renderer ────────────────────────────
            // MobileGluesDetector DEVE ser o primeiro a correr.
            // Precisa de contexto GL mas não depende de nenhuma outra classe.
            MobileGluesDetector.detect();

            // ── PASSO 2: Info base da GPU ──────────────────────────────
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

            // ── PASSO 3: Verificar GPU e activar optimizações ──────────
            if (GPUDetector.isMaliGPU()) {
                LOGGER.info("[MaliOpt] ✅ GPU Mali detectada — activando optimizações");

                // Com MobileGlues, getAllExtensions() já retorna as 102
                // extensões GLES reais — ExtensionActivator vai ver tudo ✅
                ExtensionActivator.activateAll();
                MaliPipelineOptimizer.init();
                ShaderCacheManager.init();

            } else {
                LOGGER.info("[MaliOpt] ⚠️  GPU não é Mali — mod inactivo");
            }
        });
    }

    /** Converte 1340 → "1.3.4" */
    private static String formatMGVersion(int v) {
        if (v <= 0) return "desconhecida";
        int major = v / 1000;
        int minor = (v % 1000) / 100;
        int patch = v % 100;
        return major + "." + minor + "." + patch;
    }
}
