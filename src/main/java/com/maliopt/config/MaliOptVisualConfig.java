package com.maliopt.config;

import com.google.gson.GsonBuilder;
import com.maliopt.MaliOptMod;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

/**
 * MaliOptVisualConfig — configuração visual central do MaliOpt.
 *
 * Gerida pelo YACL v3. Serializada em config/maliopt-visual.json.
 * Todos os campos têm um valor default seguro.
 *
 * Uso:
 *   MaliOptVisualConfig cfg = MaliOptVisualConfig.get();
 *   if (cfg.shadowsEnabled) { ... }
 */
public class MaliOptVisualConfig {

    // ── Handler YACL ──────────────────────────────────────────────────
    public static final ConfigClassHandler<MaliOptVisualConfig> HANDLER =
        ConfigClassHandler.createBuilder(MaliOptVisualConfig.class)
            .id(Identifier.of(MaliOptMod.MOD_ID, "visual_config"))
            .serializer(config -> GsonConfigSerializerBuilder.create(config)
                .setPath(FabricLoader.getInstance().getConfigDir()
                    .resolve("maliopt-visual.json"))
                .appendGsonBuilder(GsonBuilder::setPrettyPrinting)
                .setJson5(false)
                .build())
            .build();

    // ═══════════════════════════════════════════════════════════
    // CATEGORIA: SOMBRAS
    // ═══════════════════════════════════════════════════════════
    @SerialEntry public boolean shadowsEnabled    = false; // off por defeito — feature nova
    @SerialEntry public int     shadowResolution  = 512;   // seguro para Mali-G52
    @SerialEntry public int     shadowCascades    = 1;
    @SerialEntry public float   shadowDistance    = 48.0f;
    @SerialEntry public boolean shadowSoftEnabled = true;  // PCF suave

    // ═══════════════════════════════════════════════════════════
    // CATEGORIA: REFLEXOS (SSR)
    // ═══════════════════════════════════════════════════════════
    @SerialEntry public boolean ssrEnabled   = false;
    @SerialEntry public int     ssrMaxSteps  = 16;
    @SerialEntry public float   ssrStepSize  = 0.5f;
    @SerialEntry public boolean ssrWaterOnly = true;

    // ═══════════════════════════════════════════════════════════
    // CATEGORIA: ILUMINAÇÃO COLORIDA
    // ═══════════════════════════════════════════════════════════
    @SerialEntry public boolean coloredLightsEnabled = false;
    @SerialEntry public int     maxDynamicLights      = 4;

    // ═══════════════════════════════════════════════════════════
    // CATEGORIA: PERFORMANCE
    // ═══════════════════════════════════════════════════════════
    @SerialEntry public boolean autoQuality = true;
    @SerialEntry public int     targetFPS   = 45;

    // ═══════════════════════════════════════════════════════════
    // CATEGORIA: EFEITOS EXISTENTES (sincronizados com PerformanceGuard)
    // ═══════════════════════════════════════════════════════════
    @SerialEntry public boolean bloomEnabled   = true;
    @SerialEntry public float   bloomIntensity = 0.35f;
    @SerialEntry public boolean lightingEnabled = true;
    @SerialEntry public float   warmthStrength  = 0.06f;
    @SerialEntry public float   aoStrength      = 0.10f;

    // ── API pública ───────────────────────────────────────────────────

    /** Retorna a instância singleton carregada do disco. */
    public static MaliOptVisualConfig get() {
        return HANDLER.instance();
    }

    /** Carrega o ficheiro JSON do disco (cria com defaults se não existir). */
    public static void load() {
        HANDLER.load();
        MaliOptMod.LOGGER.info("[MaliOpt] MaliOptVisualConfig carregada ✅");
    }

    /** Persiste a configuração atual em disco. */
    public static void save() {
        HANDLER.save();
    }
}
