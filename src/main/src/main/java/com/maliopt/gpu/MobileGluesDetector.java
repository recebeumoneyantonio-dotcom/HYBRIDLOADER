package com.maliopt.gpu;

import com.maliopt.MaliOptMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Detector específico para MobileGlues (V1.3.3+)
 *
 * MobileGlues expõe 3 extensões customizadas:
 *   GL_MG_mobileglues               → confirma que MobileGlues está activo
 *   GL_MG_backend_string_getter_access → permite ler extensões GLES reais do backend
 *   GL_MG_settings_string_dump      → permite ler configurações actuais
 *
 * Com GL_MG_backend_string_getter_access activo, as chamadas
 * glGetString(GL_EXTENSIONS) e glGetStringi(GL_EXTENSIONS, i)
 * retornam as extensões do driver GLES nativo — não as do frontend GL.
 *
 * Isto resolve o problema central: com GL4ES tínhamos 87 extensões desktop.
 * Com MobileGlues + backend_string_getter_access temos as 102 extensões
 * GLES reais do Mali-G52 MC2.
 */
public class MobileGluesDetector {

    // Extensões MobileGlues customizadas
    public static final String EXT_MOBILEGLUES        = "GL_MG_mobileglues";
    public static final String EXT_BACKEND_ACCESS     = "GL_MG_backend_string_getter_access";
    public static final String EXT_SETTINGS_DUMP      = "GL_MG_settings_string_dump";

    // Estado detectado
    public static boolean isMobileGlues              = false;
    public static boolean hasBackendStringAccess     = false;
    public static boolean hasSettingsDump            = false;
    public static int     mobileGluesVersion         = 0;

    // Extensões GLES reais do backend Mali (preenchidas após detecção)
    private static Set<String> backendExtensions     = null;

    /**
     * Passo 1 — Detectar se MobileGlues está activo.
     * Chamar ANTES de qualquer outra detecção de extensões.
     */
    public static void detect() {
        // Lê extensões do frontend (o que MobileGlues expõe como GL)
        Set<String> frontendExts = readExtensionsRaw();

        isMobileGlues          = frontendExts.contains(EXT_MOBILEGLUES);
        hasBackendStringAccess = frontendExts.contains(EXT_BACKEND_ACCESS);
        hasSettingsDump        = frontendExts.contains(EXT_SETTINGS_DUMP);

        if (!isMobileGlues) {
            MaliOptMod.LOGGER.info("[MaliOpt] MobileGlues: não detectado (GL4ES activo)");
            return;
        }

        // Lê versão do renderer string
        // MobileGlues reporta algo como "MobileGlues 1.3.4 ..."
        String renderer = safeGetString(GL11.GL_RENDERER);
        String version  = safeGetString(GL11.GL_VERSION);
        mobileGluesVersion = parseVersion(version);

        MaliOptMod.LOGGER.info("[MaliOpt] ✅ MobileGlues detectado!");
        MaliOptMod.LOGGER.info("[MaliOpt]   Renderer : {}", renderer);
        MaliOptMod.LOGGER.info("[MaliOpt]   Version  : {}", version);
        MaliOptMod.LOGGER.info("[MaliOpt]   MG Build : {}", mobileGluesVersion);
        MaliOptMod.LOGGER.info("[MaliOpt]   Backend access: {}", hasBackendStringAccess ? "✅" : "❌");
    }

    /**
     * Passo 2 — Ler extensões GLES reais do backend Mali.
     *
     * Com GL_MG_backend_string_getter_access activo, glGetStringi
     * retorna as extensões do driver GLES nativo em vez das do frontend.
     * Resultado: as 102 extensões Mali reais em vez das 87 desktop.
     *
     * Chamar APÓS detect(). Só funciona se hasBackendStringAccess == true.
     */
    public static Set<String> getBackendExtensions() {
        if (backendExtensions != null) return backendExtensions;

        if (!isMobileGlues || !hasBackendStringAccess) {
            backendExtensions = Collections.emptySet();
            return backendExtensions;
        }

        Set<String> exts = new HashSet<>();

        try {
            // Com backend_string_getter_access, glGetStringi retorna
            // extensões do driver GLES — não as do frontend OpenGL
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            MaliOptMod.LOGGER.info("[MaliOpt] Backend extensions count: {}", count);

            for (int i = 0; i < count; i++) {
                String ext = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (ext != null && !ext.isEmpty()) exts.add(ext);
            }

            MaliOptMod.LOGGER.info("[MaliOpt] ✅ {} extensões GLES reais lidas do backend", exts.size());

            // Log das extensões Mali críticas que agora devemos ver
            logCriticalExtensions(exts);

        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] getBackendExtensions falhou: {}", e.getMessage());
        }

        backendExtensions = Collections.unmodifiableSet(exts);
        return backendExtensions;
    }

    /**
     * Verifica se uma extensão existe no backend GLES real.
     */
    public static boolean backendHasExtension(String ext) {
        return getBackendExtensions().contains(ext);
    }

    /**
     * Log das extensões críticas para confirmar que o backend access funciona.
     */
    private static void logCriticalExtensions(Set<String> exts) {
        String[] critical = {
            "GL_EXT_discard_framebuffer",
            "GL_ARM_mali_shader_binary",
            "GL_ARM_mali_program_binary",
            "GL_KHR_parallel_shader_compile",
            "GL_EXT_shader_pixel_local_storage",
            "GL_ARM_shader_framebuffer_fetch",
            "GL_OES_get_program_binary",
            "GL_EXT_texture_storage",
            "GL_EXT_buffer_storage",
            "GL_KHR_texture_compression_astc_ldr"
        };

        MaliOptMod.LOGGER.info("[MaliOpt] ── Extensões Mali críticas (backend real) ──");
        for (String ext : critical) {
            MaliOptMod.LOGGER.info("[MaliOpt]   {} {}", exts.contains(ext) ? "✅" : "❌", ext);
        }
    }

    // ── Utilitários ────────────────────────────────────────────────

    private static Set<String> readExtensionsRaw() {
        Set<String> result = new HashSet<>();
        try {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                String ext = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (ext != null) result.add(ext);
            }
        } catch (Exception e) {
            // Fallback
            String flat = safeGetString(GL11.GL_EXTENSIONS);
            if (flat != null && !flat.isEmpty())
                Collections.addAll(result, flat.split(" "));
        }
        return result;
    }

    private static String safeGetString(int param) {
        try {
            String s = GL11.glGetString(param);
            return s != null ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int parseVersion(String versionStr) {
        // "MobileGlues 1.3.4" → 1340
        // "4.6.0 MobileGlues 1.3.4" → 1340
        try {
            int idx = versionStr.indexOf("MobileGlues");
            if (idx < 0) return 0;
            String after = versionStr.substring(idx + 12).trim();
            String[] parts = after.split("\\.");
            if (parts.length >= 3) {
                return Integer.parseInt(parts[0]) * 1000
                     + Integer.parseInt(parts[1]) * 100
                     + Integer.parseInt(parts[2].replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public static boolean isActive()           { return isMobileGlues; }
    public static boolean hasBackendAccess()   { return hasBackendStringAccess; }
}
