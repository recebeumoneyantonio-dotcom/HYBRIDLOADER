package com.maliopt.shader;

import com.maliopt.MaliOptMod;
import com.maliopt.gpu.GPUDetector;
import org.lwjgl.opengl.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * ShaderCache — Fase 3c (4/4)
 *
 * Cache de programas GLSL compilados por GPU e resolução.
 * Elimina o stutter de primeiro carregamento.
 *
 * ── ESTRUTURA ────────────────────────────────────────────────────
 *   .minecraft/maliopt/cache/
 *     └── G52_MC2/
 *         ├── bloom_fetch_1150x540.bin
 *         ├── bloom_fallback_1150x540.bin
 *         └── lighting_1150x540.bin
 *
 * ── CHAVE DE CACHE ───────────────────────────────────────────────
 *   GPU model + resolução + SHA-256(source GLSL) → nome do ficheiro
 *   Mudança em qualquer um invalida o cache automaticamente.
 *
 * ── SUPORTE OPENGL ───────────────────────────────────────────────
 *   Usa GL_ARB_get_program_binary / glProgramBinary (OpenGL 4.1 /
 *   GL_OES_get_program_binary em GLES).
 *   Se o driver não suportar, cache é desabilitado graciosamente.
 *
 * ── IMPORTANTE PARA MOBILEGLUES ──────────────────────────────────
 *   MobileGlues (OpenGL→GLES) pode ou não passar glProgramBinary
 *   para o driver Mali nativo. Testamos na primeira execução
 *   e desabilitamos se falhar.
 */
public final class ShaderCache {

    private static Path   cacheDir         = null;
    private static boolean programBinaryOk = false;
    private static boolean enabled         = false;
    private static String  gpuSlug         = "unknown";

    private ShaderCache() {}

    // ════════════════════════════════════════════════════════════════
    // INIT
    // ════════════════════════════════════════════════════════════════

    public static void init(Path gameDir) {
        try {
            // Slug seguro para nome de directório
            gpuSlug = GPUDetector.getGPUModel()
                .replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_");

            cacheDir = gameDir.resolve("maliopt").resolve("cache").resolve(gpuSlug);
            Files.createDirectories(cacheDir);

            // Verifica se glProgramBinary funciona neste driver
            programBinaryOk = checkProgramBinarySupport();
            enabled = programBinaryOk;

            MaliOptMod.LOGGER.info("[Cache] ✅ Inicializado — GPU={} programBinary={}",
                gpuSlug, programBinaryOk);
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[Cache] Falhou init: {} — cache desativado", e.getMessage());
            enabled = false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // API PÚBLICA
    // ════════════════════════════════════════════════════════════════

    /**
     * Tenta carregar programa do cache.
     * @return program ID se acerto, 0 se miss ou cache desabilitado
     */
    public static int load(String shaderName, String vertSrc, String fragSrc,
                           int width, int height) {
        if (!enabled) return 0;

        String key = buildKey(shaderName, vertSrc, fragSrc, width, height);
        Path   file = cacheDir.resolve(key + ".bin");

        if (!Files.exists(file)) return 0;

        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length < 8) return 0;  // ficheiro corrompido

            // Primeiros 4 bytes = formato binário (binaryFormat do driver)
            int binaryFormat = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                             | ((data[2] & 0xFF) << 8)  |  (data[3] & 0xFF);

            ByteBuffer buf = ByteBuffer.allocateDirect(data.length - 4);
            buf.put(data, 4, data.length - 4).flip();

            int prog = GL20.glCreateProgram();
            GL41.glProgramBinary(prog, binaryFormat, buf);

            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                MaliOptMod.LOGGER.debug("[Cache] Miss invalidado: {}", key);
                GL20.glDeleteProgram(prog);
                Files.deleteIfExists(file);  // apaga cache inválido
                return 0;
            }

            MaliOptMod.LOGGER.debug("[Cache] ✅ Hit: {}", key);
            return prog;

        } catch (Exception e) {
            MaliOptMod.LOGGER.debug("[Cache] Erro ao carregar {}: {}", key, e.getMessage());
            return 0;
        }
    }

    /**
     * Guarda programa compilado no cache.
     * Chamar depois de linkProgram bem-sucedido.
     */
    public static void save(int programId, String shaderName,
                            String vertSrc, String fragSrc,
                            int width, int height) {
        if (!enabled) return;

        try {
            // Obtém tamanho necessário
            int[] length = new int[1];
            GL20.glGetProgramiv(programId, GL41.GL_PROGRAM_BINARY_LENGTH, length);
            if (length[0] == 0) return;

            ByteBuffer   buf    = ByteBuffer.allocateDirect(length[0]);
            int[]        format = new int[1];
            GL41.glGetProgramBinary(programId, length, format, buf);

            // Serializa: [4 bytes format][binary data]
            String key  = buildKey(shaderName, vertSrc, fragSrc, width, height);
            Path   file = cacheDir.resolve(key + ".bin");

            byte[] out = new byte[4 + buf.remaining()];
            out[0] = (byte)((format[0] >> 24) & 0xFF);
            out[1] = (byte)((format[0] >> 16) & 0xFF);
            out[2] = (byte)((format[0] >>  8) & 0xFF);
            out[3] = (byte)( format[0]         & 0xFF);
            buf.get(out, 4, buf.remaining());

            Files.write(file, out, StandardOpenOption.CREATE,
                                   StandardOpenOption.TRUNCATE_EXISTING);

            MaliOptMod.LOGGER.debug("[Cache] Guardado: {} ({} bytes)", key, out.length);

        } catch (Exception e) {
            MaliOptMod.LOGGER.debug("[Cache] Erro ao guardar: {}", e.getMessage());
        }
    }

    /**
     * Apaga todo o cache desta GPU.
     */
    public static void clearAll() {
        if (cacheDir == null) return;
        try {
            Files.walk(cacheDir)
                .filter(p -> p.toString().endsWith(".bin"))
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            MaliOptMod.LOGGER.info("[Cache] Cache limpo para GPU: {}", gpuSlug);
        } catch (IOException e) {
            MaliOptMod.LOGGER.warn("[Cache] Erro ao limpar: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // INTERNALS
    // ════════════════════════════════════════════════════════════════

    private static String buildKey(String name, String vert, String frag,
                                   int w, int h) {
        // SHA-256 do source para detectar mudanças
        String hash = sha256Short(vert + frag);
        return name + "_" + w + "x" + h + "_" + hash;
    }

    private static String sha256Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            // Usa só primeiros 8 bytes (16 hex chars) — suficiente para evitar colisões
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static boolean checkProgramBinarySupport() {
        try {
            // Testa se GL_PROGRAM_BINARY_LENGTH é suportado
            int dummy = GL20.glCreateProgram();
            GL20.glGetProgramiv(dummy, GL41.GL_PROGRAM_BINARY_LENGTH, new int[1]);
            GL20.glDeleteProgram(dummy);
            // Se chegou aqui sem exception, está suportado
            return GL20.glGetInteger(GL41.GL_NUM_PROGRAM_BINARY_FORMATS) > 0;
        } catch (Exception e) {
            MaliOptMod.LOGGER.info("[Cache] glProgramBinary não suportado: {}", e.getMessage());
            return false;
        }
    }

    public static boolean isEnabled()     { return enabled; }
    public static String  getGpuSlug()    { return gpuSlug; }
    public static Path    getCacheDir()   { return cacheDir; }
                }
