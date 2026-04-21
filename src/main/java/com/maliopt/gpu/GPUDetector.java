package com.maliopt.gpu;

import com.maliopt.MaliOptMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class GPUDetector {

    private static String      cachedRenderer   = null;
    private static String      cachedVendor     = null;
    private static String      cachedVersion    = null;
    private static Set<String> cachedExtensions = null;
    private static Boolean     isMali           = null;

    private static String safeGet(int param) {
        try {
            String val = GL11.glGetString(param);
            return val != null ? val : "";
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] glGetString({}) falhou: {}", param, e.getMessage());
            return "";
        }
    }

    public static String getRenderer() {
        if (cachedRenderer == null) cachedRenderer = safeGet(GL11.GL_RENDERER);
        return cachedRenderer;
    }

    public static String getVendor() {
        if (cachedVendor == null) cachedVendor = safeGet(GL11.GL_VENDOR);
        return cachedVendor;
    }

    public static String getVersion() {
        if (cachedVersion == null) cachedVersion = safeGet(GL11.GL_VERSION);
        return cachedVersion;
    }

    /**
     * FASE 2 — Fix crítico.
     * GL4ES com glGetString(GL_EXTENSIONS) retorna extensões desktop → todas ❌.
     * glGetStringi em loop retorna extensões GLES reais do driver Mali.
     */
    public static Set<String> getAllExtensions() {
        if (cachedExtensions != null) return cachedExtensions;

        Set<String> exts = new HashSet<>();

        // Método moderno — GL 3.0+ / GLES 3.0+ — ng_gl4es suporta
        try {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            if (count > 0) {
                for (int i = 0; i < count; i++) {
                    String ext = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                    if (ext != null && !ext.isEmpty()) exts.add(ext);
                }
                MaliOptMod.LOGGER.info("[MaliOpt] glGetStringi: {} extensões encontradas", exts.size());
            }
        } catch (Exception e) {
            MaliOptMod.LOGGER.warn("[MaliOpt] glGetStringi falhou: {}", e.getMessage());
        }

        // Fallback — método antigo se glGetStringi falhar
        if (exts.isEmpty()) {
            String flat = safeGet(GL11.GL_EXTENSIONS);
            if (!flat.isEmpty()) {
                Collections.addAll(exts, flat.split(" "));
                MaliOptMod.LOGGER.info("[MaliOpt] Fallback glGetString: {} extensões", exts.size());
            }
        }

        cachedExtensions = Collections.unmodifiableSet(exts);
        return cachedExtensions;
    }

    public static boolean hasExtension(String ext) {
        return getAllExtensions().contains(ext);
    }

    public static boolean isMaliGPU() {
        if (isMali == null) {
            String r = getRenderer().toLowerCase();
            String v = getVendor().toLowerCase();
            isMali = r.contains("mali") || v.contains("arm");
        }
        return isMali;
    }

    public static boolean isBifrost() {
        String r = getRenderer();
        return r.contains("G52") || r.contains("G57")
            || r.contains("G68") || r.contains("G76")
            || r.contains("G77");
    }

    public static void resetCache() {
        cachedRenderer   = null;
        cachedVendor     = null;
        cachedVersion    = null;
        cachedExtensions = null;
        isMali           = null;
    }
}
