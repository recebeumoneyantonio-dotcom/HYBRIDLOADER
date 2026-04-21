package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.config.MaliOptConfig;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL41;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ShaderCacheManager — Fase 2
 *
 * Melhorias vs Fase 1:
 * - Usa GL_ARM_mali_program_binary se disponível (formato específico Mali)
 * - GL_KHR_parallel_shader_compile para compilação assíncrona
 * - Escrita lazy em thread separada (não bloqueia render thread)
 * - Gestão de tamanho máximo do cache com LRU
 * - Verificação de integridade por checksum simples
 */
public class ShaderCacheManager {

    private static final Path   CACHE_DIR    = Paths.get("maliopt-cache", "shaders");
    private static final int    CACHE_VER    = 2; // Incrementa ao mudar formato
    private static boolean      supported    = false;
    private static int          maliFormat   = -1; // Formato binário ARM se disponível
    private static final AtomicLong cacheSize = new AtomicLong(0);

    // Thread dedicada para I/O de cache — nunca bloqueia o render thread
    private static ExecutorService ioExecutor;

    // Constante GL_KHR_parallel_shader_compile
    private static final int GL_COMPLETION_STATUS_KHR = 0x91B1;

    public static void init() {
        if (!GPUDetector.isMaliGPU() || !MaliOptConfig.enableShaderCache) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer countBuf = stack.mallocInt(1);
            GL11.glGetIntegerv(GL41.GL_NUM_PROGRAM_BINARY_FORMATS, countBuf);
            int formatCount = countBuf.get(0);

            if (formatCount <= 0) {
                MaliOptMod.LOGGER.warn("[MaliOpt] ShaderCache: 0 formatos binários — desactivado");
                return;
            }

            // Lê os formatos disponíveis
            IntBuffer formatsBuf = stack.mallocInt(formatCount);
            GL11.glGetIntegerv(GL41.GL_PROGRAM_BINARY_FORMATS, formatsBuf);

            // Procura formato ARM Mali específico
            // GL_ARM_mali_program_binary não define constante pública — usamos o que o driver retorna
            // Tipicamente é 0x8B30 ou similar — guardamos o primeiro disponível
            maliFormat = formatsBuf.get(0);
            MaliOptMod.LOGGER.info("[MaliOpt] Formato binário Mali: 0x{}", Integer.toHexString(maliFormat));

            Files.createDirectories(CACHE_DIR);
            supported = true;

            // IO thread — daemon para não bloquear shutdown
            ioExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MaliOpt-CacheIO");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

            // Calcula tamanho actual do cache
            calculateCacheSize();

            MaliOptMod.LOGGER.info("[MaliOpt] ShaderCache activo — {} formato(s), cache: {}KB",
                formatCount, cacheSize.get() / 1024);

            if (ExtensionActivator.hasParallelShaderCompile)
                MaliOptMod.LOGGER.info("[MaliOpt] ⚡ Compilação paralela de shaders ACTIVA");

        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] ShaderCache init falhou: {}", e.getMessage());
        }
    }

    /**
     * Tenta carregar programa do cache Mali.
     * @return true se carregou — podes saltar glLinkProgram completamente
     */
    public static boolean loadFromCache(int programId, String cacheKey) {
        if (!supported) return false;

        Path path = cachePath(cacheKey);
        if (!Files.exists(path)) return false;

        try {
            byte[] bytes = Files.readAllBytes(path);

            // Verifica header: [versão(4)] [formato(4)] [checksum(4)] [dados...]
            if (bytes.length < 12) {
                Files.deleteIfExists(path);
                return false;
            }

            int storedVer      = readInt(bytes, 0);
            int storedFormat   = readInt(bytes, 4);
            int storedChecksum = readInt(bytes, 8);
            int dataLen        = bytes.length - 12;

            // Versão diferente = cache inválido
            if (storedVer != CACHE_VER) {
                Files.deleteIfExists(path);
                return false;
            }

            // Checksum simples
            if (checksum(bytes, 12, dataLen) != storedChecksum) {
                MaliOptMod.LOGGER.warn("[MaliOpt] Cache corrompido: {}", cacheKey);
                Files.deleteIfExists(path);
                return false;
            }

            ByteBuffer buf = ByteBuffer.allocateDirect(dataLen);
            buf.put(bytes, 12, dataLen).flip();

            GL41.glProgramBinary(programId, storedFormat, buf);

            int[] status = new int[1];
            GL20.glGetProgramiv(programId, GL20.GL_LINK_STATUS, status);

            if (status[0] == GL11.GL_TRUE) {
                MaliOptMod.LOGGER.debug("[MaliOpt] Cache HIT: {}", cacheKey);
                // Actualiza timestamp (LRU) em background
                touchAsync(path);
                return true;
            } else {
                Files.deleteIfExists(path);
                return false;
            }

        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] loadFromCache '{}': {}", cacheKey, e.getMessage());
            return false;
        }
    }

    /**
     * Salva programa no cache — escrita assíncrona (não bloqueia render thread).
     * Chamar APÓS glLinkProgram com GL_LINK_STATUS == GL_TRUE.
     */
    public static void saveToCache(int programId, String cacheKey) {
        if (!supported || ioExecutor == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer lenBuf = stack.mallocInt(1);
            GL20.glGetProgramiv(programId, GL41.GL_PROGRAM_BINARY_LENGTH, lenBuf);
            int binaryLen = lenBuf.get(0);
            if (binaryLen <= 0) return;

            // Lê binário no render thread (precisa de contexto GL)
            ByteBuffer binary    = ByteBuffer.allocateDirect(binaryLen);
            IntBuffer  formatBuf = stack.mallocInt(1);
            IntBuffer  actualLen = stack.mallocInt(1);
            GL41.glGetProgramBinary(programId, actualLen, formatBuf, binary);

            int    format = formatBuf.get(0);
            int    len    = actualLen.get(0);
            byte[] data   = new byte[len];
            binary.get(data);

            // Escreve em I/O thread — não bloqueia rendering
            ioExecutor.submit(() -> writeCacheFile(cacheKey, format, data));

        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] saveToCache '{}': {}", cacheKey, e.getMessage());
        }
    }

    /**
     * FASE 2 — Compilação paralela.
     * Se GL_KHR_parallel_shader_compile estiver disponível,
     * a compilação acontece nas threads do driver (8 cores do Helio G85).
     * Verifica se compilação terminou sem bloquear.
     *
     * @return true se já compilou, false se ainda está em progresso
     */
    public static boolean isShaderCompiled(int shaderId) {
        if (!ExtensionActivator.hasParallelShaderCompile) {
            // Sem extensão — comportamento síncrono normal
            int[] status = new int[1];
            GL20.glGetShaderiv(shaderId, GL20.GL_COMPILE_STATUS, status);
            return status[0] == GL11.GL_TRUE;
        }

        try {
            int[] completionStatus = new int[1];
            GL20.glGetShaderiv(shaderId, GL_COMPLETION_STATUS_KHR, completionStatus);

            if (completionStatus[0] == GL11.GL_FALSE) {
                return false; // Ainda a compilar — não bloquear
            }

            // Compilação concluída — verifica resultado
            int[] compileStatus = new int[1];
            GL20.glGetShaderiv(shaderId, GL20.GL_COMPILE_STATUS, compileStatus);
            return compileStatus[0] == GL11.GL_TRUE;

        } catch (Exception e) {
            // Fallback síncrono
            int[] status = new int[1];
            GL20.glGetShaderiv(shaderId, GL20.GL_COMPILE_STATUS, status);
            return status[0] == GL11.GL_TRUE;
        }
    }

    // ── Métodos internos ────────────────────────────────────────────

    private static void writeCacheFile(String cacheKey, int format, byte[] data) {
        try {
            // Verifica limite de tamanho do cache
            long maxBytes = (long) MaliOptConfig.shaderCacheMaxMb * 1024 * 1024;
            if (cacheSize.get() > maxBytes) {
                evictOldest(maxBytes / 2); // LRU — liberta metade
            }

            // Header: [versão(4)] [formato(4)] [checksum(4)] [dados]
            byte[] toWrite = new byte[12 + data.length];
            writeInt(toWrite, 0, CACHE_VER);
            writeInt(toWrite, 4, format);
            writeInt(toWrite, 8, checksum(data, 0, data.length));
            System.arraycopy(data, 0, toWrite, 12, data.length);

            Path path = cachePath(cacheKey);
            Files.write(path, toWrite);
            cacheSize.addAndGet(toWrite.length);

            MaliOptMod.LOGGER.debug("[MaliOpt] Cache SAVE: {} ({}KB)", cacheKey, data.length / 1024);

        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] writeCacheFile '{}': {}", cacheKey, e.getMessage());
        }
    }

    private static void evictOldest(long targetSize) {
        try {
            Files.walk(CACHE_DIR)
                 .filter(p -> p.toString().endsWith(".bin"))
                 .sorted(Comparator.comparingLong(p -> {
                     try { return Files.getLastModifiedTime(p).toMillis(); }
                     catch (Exception e) { return Long.MAX_VALUE; }
                 }))
                 .forEach(p -> {
                     if (cacheSize.get() > targetSize) {
                         try {
                             long size = Files.size(p);
                             Files.delete(p);
                             cacheSize.addAndGet(-size);
                         } catch (IOException ignored) {}
                     }
                 });
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] evictOldest: {}", e.getMessage());
        }
    }

    private static void touchAsync(Path path) {
        if (ioExecutor != null) {
            ioExecutor.submit(() -> {
                try { path.toFile().setLastModified(System.currentTimeMillis()); }
                catch (Exception ignored) {}
            });
        }
    }

    private static void calculateCacheSize() {
        try {
            long size = Files.walk(CACHE_DIR)
                .filter(p -> p.toString().endsWith(".bin"))
                .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } })
                .sum();
            cacheSize.set(size);
        } catch (Exception ignored) {}
    }

    public static void clearCache() {
        try {
            if (Files.exists(CACHE_DIR)) {
                Files.walk(CACHE_DIR)
                     .filter(p -> p.toString().endsWith(".bin"))
                     .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                cacheSize.set(0);
                MaliOptMod.LOGGER.info("[MaliOpt] Cache limpo");
            }
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] clearCache: {}", e.getMessage());
        }
    }

    private static Path cachePath(String key) {
        return CACHE_DIR.resolve(key.replaceAll("[^a-zA-Z0-9_\\-]", "_") + ".bin");
    }

    // Checksum XOR simples — rápido, suficiente para detectar corrupção
    private static int checksum(byte[] data, int offset, int len) {
        int sum = 0x4D414C49; // "MALI" seed
        for (int i = offset; i < offset + len; i++) sum ^= (data[i] & 0xFF) << ((i % 4) * 8);
        return sum;
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24) | ((b[offset+1] & 0xFF) << 16)
             | ((b[offset+2] & 0xFF) << 8) | (b[offset+3] & 0xFF);
    }

    private static void writeInt(byte[] b, int offset, int val) {
        b[offset]   = (byte)(val >> 24); b[offset+1] = (byte)(val >> 16);
        b[offset+2] = (byte)(val >>  8); b[offset+3] = (byte) val;
    }

    public static boolean isSupported()   { return supported; }
    public static long    getCacheSizeKb() { return cacheSize.get() / 1024; }
 }
