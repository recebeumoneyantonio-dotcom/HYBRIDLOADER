package com.maliopt;

import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
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
            LOGGER.info("[MaliOpt] Cliente iniciado — verificando GPU...");
            LOGGER.info("[MaliOpt] Renderer : {}", GPUDetector.getRenderer());
            LOGGER.info("[MaliOpt] Vendor   : {}", GPUDetector.getVendor());
            LOGGER.info("[MaliOpt] Version  : {}", GPUDetector.getVersion());

            if (GPUDetector.isMaliGPU()) {
                LOGGER.info("[MaliOpt] ✅ GPU Mali detectada — activando optimizações");
                ExtensionActivator.activateAll();
                MaliPipelineOptimizer.init();
                ShaderCacheManager.init();
            } else {
                LOGGER.info("[MaliOpt] ⚠️  GPU não é Mali — mod inactivo");
            }
        });
    }
}
