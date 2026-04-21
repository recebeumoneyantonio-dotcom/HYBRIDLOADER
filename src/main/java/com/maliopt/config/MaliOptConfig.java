package com.maliopt.config;

import com.maliopt.MaliOptMod;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

public class MaliOptConfig {

    private static final Path       CONFIG_PATH = Paths.get("config", "maliopt.properties");
    private static final Properties PROPS       = new Properties();

    // Fase 1
    public static boolean enableTextureOpt  = true;
    public static boolean enableShaderCache = true;
    public static boolean enableDiscardFBO  = true;
    public static boolean enableDepthOpt    = true;

    // Fase 2 — novo
    public static boolean enableParallelCompile = true;
    public static boolean enableBufferStorage   = true;
    public static boolean enableTileOptimizer   = true;
    public static boolean enableTextureStorage  = true;
    public static int     shaderCacheMaxMb      = 64;

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                PROPS.load(in);
                enableTextureOpt      = bool("enable_texture_opt",       true);
                enableShaderCache     = bool("enable_shader_cache",       true);
                enableDiscardFBO      = bool("enable_discard_fbo",        true);
                enableDepthOpt        = bool("enable_depth_opt",          true);
                enableParallelCompile = bool("enable_parallel_compile",   true);
                enableBufferStorage   = bool("enable_buffer_storage",     true);
                enableTileOptimizer   = bool("enable_tile_optimizer",     true);
                enableTextureStorage  = bool("enable_texture_storage",    true);
                shaderCacheMaxMb      = integer("shader_cache_max_mb",    64);
                MaliOptMod.LOGGER.info("[MaliOpt] Config carregada");
            } catch (IOException e) {
                MaliOptMod.LOGGER.warn("[MaliOpt] Erro ao ler config — usando defaults", e);
            }
        } else {
            save();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            PROPS.setProperty("enable_texture_opt",     String.valueOf(enableTextureOpt));
            PROPS.setProperty("enable_shader_cache",    String.valueOf(enableShaderCache));
            PROPS.setProperty("enable_discard_fbo",     String.valueOf(enableDiscardFBO));
            PROPS.setProperty("enable_depth_opt",       String.valueOf(enableDepthOpt));
            PROPS.setProperty("enable_parallel_compile",String.valueOf(enableParallelCompile));
            PROPS.setProperty("enable_buffer_storage",  String.valueOf(enableBufferStorage));
            PROPS.setProperty("enable_tile_optimizer",  String.valueOf(enableTileOptimizer));
            PROPS.setProperty("enable_texture_storage", String.valueOf(enableTextureStorage));
            PROPS.setProperty("shader_cache_max_mb",    String.valueOf(shaderCacheMaxMb));
            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                PROPS.store(out, "MaliOpt Phase 2 Config");
            }
        } catch (IOException e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] Erro ao salvar config", e);
        }
    }

    private static boolean bool(String key, boolean def) {
        return Boolean.parseBoolean(PROPS.getProperty(key, String.valueOf(def)));
    }

    private static int integer(String key, int def) {
        try { return Integer.parseInt(PROPS.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }
}
