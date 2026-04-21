package com.maliopt.gpu;

import com.maliopt.MaliOptMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class MobileGluesDetector {

    public static final String EXT_MOBILEGLUES    = "GL_MG_mobileglues";
    public static final String EXT_BACKEND_ACCESS = "GL_MG_backend_string_getter_access";
    public static final String EXT_SETTINGS_DUMP  = "GL_MG_settings_string_dump";

    public static boolean isMobileGlues          = false;
    public static boolean hasBackendStringAccess = false;
    public static boolean hasSettingsDump        = false;
    public static int     mobileGluesVersion     = 0;

    private static Set<String> backendExtensions = null;

    public static void detect() {
        Set<String> frontendExts = readExtensionsRaw();

        isMobileGlues          = frontendExts.contains(EXT_MOBILEGLUES);
        hasBackendStringAccess = frontendExts.contains(EXT_BACKEND_ACCESS);
        hasSettingsDump        = frontendExts.contains(EXT_SETTINGS_DUMP);

        if (!isMobileGlues) {
            MaliOptMod.LOGGER.info("[MaliOpt] MobileGlues: não detectado (GL4ES activo)");
            return;
        }

        String version = safeGetString(GL11.GL_VERSION);
        mobileGluesVersion = parseVersion(version);

        MaliOptMod.LOGGER.info("[MaliOpt] ✅ MobileGlues detectado!");
        MaliOptMod.LOGGER.info("[MaliOpt]   Version  : {}", version);
        MaliOptMod.LOGGER.info("[MaliOpt]   MG Build : {}", mobileGluesVersion);
        MaliOptMod.LOGGER.info("[MaliOpt]   Backend access: {}", hasBackendStringAccess ? "✅" : "❌");
    }

    public static Set<String> getBackendExtensions() {
        if (backendExtensions != null) return backendExtensions;

        if (!isMobileGlues || !hasBackendStringAccess) {
            backendExtensions = Collections.emptySet();
            return backendExtensions;
        }

        Set<String> exts = new HashSet<>();

        try {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            MaliOptMod.LOGGER.info("[MaliOpt] Backend extensions count: {}", count);

            for (int i = 0; i < count; i++) {
                String ext = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (ext != null && !ext.isEmpty()) exts.add(ext);
            }

            MaliOptMod.LOGGER.info("[MaliOpt] ✅ {} extensões GLES reais lidas do backend", exts.size());
            logCriticalExtensions(exts);

        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] getBackendExtensions falhou: {}", e.getMessage());
        }

        backendExtensions = Collections.unmodifiableSet(exts);
        return backendExtensions;
    }

    public static boolean backendHasExtension(String ext) {
        return getBackendExtensions().contains(ext);
    }

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

    private static Set<String> readExtensionsRaw() {
        Set<String> result = new HashSet<>();
        try {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                String ext = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (ext != null) result.add(ext);
            }
        } catch (Exception e) {
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

    public static boolean isActive()         { return isMobileGlues; }
    public static boolean hasBackendAccess() { return hasBackendStringAccess; }
}
