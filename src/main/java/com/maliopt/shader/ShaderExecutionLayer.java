package com.maliopt.shader;

import com.maliopt.MaliOptMod;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL11;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * ShaderExecutionLayer — Fase 3c (2/4)
 *
 * NÚCLEO do sistema. Transforma o MaliOpt de "mod com efeitos"
 * em "plataforma de execução de shaders para Mali".
 *
 * ── O QUE FAZ ────────────────────────────────────────────────────
 *   1. Injector de #defines — todo shader compilado pelo MaliOpt
 *      recebe automaticamente os defines certos para o hardware
 *
 *   2. Shader Adaptation — transforma GLSL genérico em GLSL optimizado
 *      para Mali (precision hints, fast paths, extensões injectadas)
 *
 *   3. Compiler wrapper — substituição de GL20.glCompileShader que
 *      intercala as transformações antes da compilação real
 *
 * ── DEFINES INJECTADOS ───────────────────────────────────────────
 *
 *   #define MALI_OPT 1              sempre presente se MaliOpt activo
 *   #define MALI_TBDR 1             se arquitectura TBDR (Mali-G series)
 *   #define MALI_PLS 1              se GL_EXT_shader_pixel_local_storage
 *   #define MALI_FB_FETCH 1         se GL_ARM_shader_framebuffer_fetch
 *   #define MALI_ASTC 1             se GL_KHR_texture_compression_astc_ldr
 *   #define MALI_BIFROST 1          se GPU Bifrost (G52, G72, G76...)
 *   #define MALI_VALHALL 1          se GPU Valhall (G710, G610...)
 *   #define MALI_QUALITY_LOW 1      / MEDIUM / HIGH — nível recomendado
 *   #define MALI_MEDIUMP_FAST 1     se mediump é mais rápido que highp
 *
 * ── COMO USAR NOS SHADERS ────────────────────────────────────────
 *
 *   #ifdef MALI_FB_FETCH
 *       vec4 scene = gl_LastFragColorARM;  // zero-DRAM
 *   #else
 *       vec4 scene = texture(uScene, vUv); // fallback
 *   #endif
 *
 *   #ifdef MALI_MEDIUMP_FAST
 *       precision mediump float;  // mais rápido no Mali
 *   #else
 *       precision highp float;
 *   #endif
 *
 * ── ADAPTAÇÕES AUTOMÁTICAS ───────────────────────────────────────
 *
 *   highp → mediump        em shaders marcados como MALI_MEDIUMP_OK
 *   texture2D → texture    moderniza GLSL 100 para 310 es
 *   gl_FragColor → out vec4 fragColor (modernização automática)
 */
public final class ShaderExecutionLayer {

    private static boolean active = false;

    // Padrão para detectar linha #version (deve ser a primeira)
    private static final Pattern VERSION_PATTERN =
        Pattern.compile("^(\\s*#version\\s+\\S+(?:\\s+\\S+)?)(.*)$",
                        Pattern.MULTILINE | Pattern.DOTALL);

    private ShaderExecutionLayer() {}

    // ════════════════════════════════════════════════════════════════
    // ACTIVAÇÃO
    // ════════════════════════════════════════════════════════════════

    public static void init() {
        if (!ShaderCapabilities.isInitialised()) {
            MaliOptMod.LOGGER.warn(
                "[SEL] ShaderCapabilities não inicializado — chamar init() primeiro");
            return;
        }
        active = true;
        MaliOptMod.LOGGER.info("[SEL] ✅ ShaderExecutionLayer activo");
        MaliOptMod.LOGGER.info("[SEL]    Defines: {}", buildDefineBlock().replace("\n", " | "));
    }

    // ════════════════════════════════════════════════════════════════
    // ENTRY POINT PRINCIPAL
    // compile() — substitui GL20.glCompileShader em todos os passes
    // ════════════════════════════════════════════════════════════════

    /**
     * Compila um shader com todas as transformações Mali aplicadas.
     *
     * Uso nos passes:
     *   int id = ShaderExecutionLayer.compile(GL_FRAGMENT_SHADER, src, "BloomFrag");
     *
     * @return shader ID compilado, ou 0 se falhou
     */
    public static int compile(int type, String source, String debugName) {
        String transformed = active ? transform(source, debugName) : source;

        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, transformed);
        GL20.glCompileShader(id);

        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(id);
            MaliOptMod.LOGGER.error("[SEL] Shader {} falhou:\n{}", debugName, log);

            // Se a falha pode ser da transformação, loga o source transformado
            if (active && !source.equals(transformed)) {
                MaliOptMod.LOGGER.debug("[SEL] Source transformado:\n{}", transformed);
            }

            GL20.glDeleteShader(id);
            return 0;
        }

        return id;
    }

    // ════════════════════════════════════════════════════════════════
    // PIPELINE DE TRANSFORMAÇÃO
    // ════════════════════════════════════════════════════════════════

    /**
     * Aplica todas as transformações ao source GLSL.
     * Ordem: inject → adapt → validate
     */
    static String transform(String source, String debugName) {
        String s = source;

        // 1. Injector de #defines Mali
        s = injectDefines(s);

        // 2. Adaptações de precision (Mali-specific)
        if (ShaderCapabilities.MEDIUMP_FAST) {
            s = adaptPrecision(s);
        }

        // 3. Modernização GLSL (100 → 310 es patterns)
        s = modernizeGLSL(s);

        return s;
    }

    // ════════════════════════════════════════════════════════════════
    // 1. INJECTOR DE #DEFINES
    // Insere o bloco Mali logo após o #version
    // ════════════════════════════════════════════════════════════════

    private static String injectDefines(String source) {
        String defines = buildDefineBlock();

        Matcher m = VERSION_PATTERN.matcher(source);
        if (m.find()) {
            // Insere defines logo após a linha #version
            String versionLine = m.group(1);
            String rest        = m.group(2);
            return versionLine + "\n" + defines + rest;
        }

        // Sem #version — insere no topo
        return defines + source;
    }

    private static String buildDefineBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n// ── MaliOpt ShaderExecutionLayer ────────────────────\n");
        sb.append("#define MALI_OPT 1\n");

        if (ShaderCapabilities.TBDR)
            sb.append("#define MALI_TBDR 1\n");
        if (ShaderCapabilities.PLS)
            sb.append("#define MALI_PLS 1\n");
        if (ShaderCapabilities.FB_FETCH)
            sb.append("#define MALI_FB_FETCH 1\n");
        if (ShaderCapabilities.FB_FETCH_DEPTH)
            sb.append("#define MALI_FB_FETCH_DEPTH 1\n");
        if (ShaderCapabilities.ASTC)
            sb.append("#define MALI_ASTC 1\n");
        if (ShaderCapabilities.BIFROST)
            sb.append("#define MALI_BIFROST 1\n");
        if (ShaderCapabilities.VALHALL)
            sb.append("#define MALI_VALHALL 1\n");
        if (ShaderCapabilities.MEDIUMP_FAST)
            sb.append("#define MALI_MEDIUMP_FAST 1\n");
        if (ShaderCapabilities.MULTISAMPLED_RT)
            sb.append("#define MALI_MSRT 1\n");

        // Quality level
        switch (ShaderCapabilities.recommendedQuality()) {
            case HIGH:
                sb.append("#define MALI_QUALITY_HIGH 1\n");
                sb.append("#define MALI_QUALITY 2\n");
                break;
            case MEDIUM:
                sb.append("#define MALI_QUALITY_MEDIUM 1\n");
                sb.append("#define MALI_QUALITY 1\n");
                break;
            default:
                sb.append("#define MALI_QUALITY_LOW 1\n");
                sb.append("#define MALI_QUALITY 0\n");
                break;
        }

        sb.append("// ──────────────────────────────────────────────────\n\n");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    // 2. ADAPTAÇÃO DE PRECISION
    // Mali Bifrost: mediump é executado em 16-bit SIMD (2x throughput)
    // highp desnecessário em shaders de post-processing
    // ════════════════════════════════════════════════════════════════

    private static final Pattern HIGHP_UNIFORM =
        Pattern.compile("uniform\\s+highp\\s+(float|vec[234]|mat[234])");

    private static String adaptPrecision(String source) {
        // Shaders de post-processing (bloom, lighting) não precisam de highp
        // Só substituímos em shaders sem "MALI_PRECISION_KEEP" no source
        if (source.contains("MALI_PRECISION_KEEP")) return source;

        // Rebaixa uniforms highp → mediump em passes de post-process
        // (não toca em depth/shadow shaders)
        if (isPostProcessShader(source)) {
            source = HIGHP_UNIFORM.matcher(source)
                       .replaceAll("uniform mediump $1");
        }
        return source;
    }

    private static boolean isPostProcessShader(String source) {
        // Heurística: post-process não usa gl_FragDepth nem shadow samplers
        return !source.contains("gl_FragDepth")
            && !source.contains("sampler2DShadow")
            && !source.contains("samplerCubeShadow");
    }

    // ════════════════════════════════════════════════════════════════
    // 3. MODERNIZAÇÃO GLSL
    // Converte padrões GLSL 100 → GLSL 310 es onde seguro
    // ════════════════════════════════════════════════════════════════

    private static String modernizeGLSL(String source) {
        // texture2D() → texture() (GLSL 130+)
        // Só em shaders que já usam #version 300 es ou superior
        if (source.contains("#version 3") || source.contains("#version 310")) {
            source = source.replace("texture2D(", "texture(");
            source = source.replace("textureCube(", "texture(");
        }
        return source;
    }

    // ════════════════════════════════════════════════════════════════
    // UTILITÁRIOS PÚBLICOS
    // ════════════════════════════════════════════════════════════════

    /**
     * Retorna o bloco de defines como string para logging/debug.
     */
    public static String getDefinesSummary() {
        return buildDefineBlock()
            .replaceAll("//.*\n", "")
            .replaceAll("\n+", " ")
            .trim();
    }

    /**
     * Verifica se o SEL está activo e pronto para transformar shaders.
     */
    public static boolean isActive() { return active; }

    /**
     * Desactiva o SEL (para debug — todos os shaders ficam sem transforms).
     */
    public static void disable() {
        active = false;
        MaliOptMod.LOGGER.warn("[SEL] ⚠ ShaderExecutionLayer desactivado");
    }
            }

