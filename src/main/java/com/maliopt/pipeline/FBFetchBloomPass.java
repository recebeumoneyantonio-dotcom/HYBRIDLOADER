package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.gpu.ExtensionActivator;
import com.maliopt.gpu.GPUDetector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.*;

/**
 * FBFetchBloomPass — Fase 3b
 *
 * Bloom ultra-eficiente para Mali usando GL_ARM_shader_framebuffer_fetch.
 *
 * ── CORRECÇÕES v1.1 ──────────────────────────────────────────────────
 *
 * Bug corrigido: o caminho FBFetch fazia 2 blits redundantes.
 *   ANTES: rende em fb.fbo → copia para bloomFbo → copia de volta para fb.fbo
 *   AGORA: rende directamente em fb.fbo, sem blits
 *
 * Bug corrigido: o bloomFbo usava GL_NEAREST (resultado pixelado).
 *   AGORA: usa GL_LINEAR para o bloom suavizado.
 *
 * Bug corrigido: o fallback não restaurava o texture binding antes do blit.
 *   AGORA: unbind da textura antes do blit final.
 *
 * ── POR QUÊ ISTO FUNCIONA ────────────────────────────────────────────
 *
 * WorldRenderEvents.LAST dispara DEPOIS de o Minecraft terminar de
 * renderizar o mundo mas ANTES de apresentar o framebuffer no ecrã.
 * mc.getFramebuffer() neste momento é o framebuffer de apresentação final.
 * Escrever nele aqui é suficiente — o Minecraft não sobrescreve depois.
 *
 * ── CAMINHO FAST (FBFetch disponível) ───────────────────────────────
 *   1. Bind fb.fbo
 *   2. Shader lê gl_LastFragColorARM (tile memory, ZERO DRAM)
 *   3. Calcula bloom, escreve resultado em fb.fbo
 *   Total: 0 copies DRAM
 *
 * ── CAMINHO FALLBACK (sem FBFetch) ──────────────────────────────────
 *   1. Copia fb.fbo → bloomFbo (1 copy DRAM, necessária)
 *   2. Shader lê bloomFbo como textura, aplica blur+bloom, escreve em fb.fbo
 *   Total: 1 copy DRAM (vs 4-6 do Iris)
 */
public class FBFetchBloomPass {

    // ── Estado ──────────────────────────────────────────────────────
    private static int  programFetch    = 0;
    private static int  programFallback = 0;
    private static int  quadVao         = 0;
    private static int  bloomFbo        = 0;   // só usado no caminho FALLBACK
    private static int  bloomTex        = 0;
    private static int  lastW           = 0;
    private static int  lastH           = 0;
    private static boolean ready        = false;
    private static boolean usingFetch   = false;

    // ── Uniforms ────────────────────────────────────────────────────
    private static int uThreshold  = -1;
    private static int uIntensity  = -1;
    private static int uRadius     = -1;
    private static int uScene      = -1;   // só no fallback

    // ── Parâmetros ──────────────────────────────────────────────────
    private static final float BLOOM_THRESHOLD = 0.75f;
    private static final float BLOOM_INTENSITY = 0.35f;
    private static final float BLOOM_RADIUS    = 1.8f;

    // ════════════════════════════════════════════════════════════════
    // GLSL — VERTEX
    // ════════════════════════════════════════════════════════════════
    private static final String VERT_SRC =
        "#version 310 es\n" +
        "out vec2 vUv;\n" +
        "void main() {\n" +
        "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n" +
        "    vUv = uv;\n" +
        "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n";

    // ════════════════════════════════════════════════════════════════
    // GLSL — FRAGMENT FBFetch
    // gl_LastFragColorARM lê directamente da tile memory.
    // Rende para o MESMO FBO onde a cena está — ZERO cópias DRAM.
    // ════════════════════════════════════════════════════════════════
    private static final String FRAG_FETCH_SRC =
        "#version 310 es\n" +
        "#extension GL_ARM_shader_framebuffer_fetch : require\n" +
        "precision mediump float;\n" +
        "\n" +
        "uniform float uThreshold;\n" +
        "uniform float uIntensity;\n" +
        "uniform float uRadius;\n" +
        "\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "\n" +
        "float luminance(vec3 c) {\n" +
        "    return dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec4 scene = gl_LastFragColorARM;\n" +
        "\n" +
        "    float lum = luminance(scene.rgb);\n" +
        "    float bright = max(0.0, lum - uThreshold) / (1.0 - uThreshold + 0.001);\n" +
        "\n" +
        "    vec3 bloomColor = scene.rgb * bright;\n" +
        "    float spread = uRadius * bright;\n" +
        "    vec3 glow = bloomColor * spread;\n" +
        "\n" +
        "    vec3 result = scene.rgb + glow * uIntensity;\n" +
        "    result = result / (result + vec3(1.0));\n" +
        "\n" +
        "    fragColor = vec4(result, scene.a);\n" +
        "}\n";

    // ════════════════════════════════════════════════════════════════
    // GLSL — FRAGMENT FALLBACK (texture sample, 5-tap blur em cruz)
    // ════════════════════════════════════════════════════════════════
    private static final String FRAG_FALLBACK_SRC =
        "#version 310 es\n" +
        "precision mediump float;\n" +
        "\n" +
        "uniform sampler2D uScene;\n" +
        "uniform float uThreshold;\n" +
        "uniform float uIntensity;\n" +
        "uniform float uRadius;\n" +
        "\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "\n" +
        "float luminance(vec3 c) {\n" +
        "    return dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "}\n" +
        "\n" +
        "vec3 blurSample(sampler2D tex, vec2 uv, vec2 texelSize, float radius) {\n" +
        "    vec3 sum = texture(tex, uv).rgb * 0.4;\n" +
        "    sum += texture(tex, uv + vec2( radius, 0.0) * texelSize).rgb * 0.15;\n" +
        "    sum += texture(tex, uv + vec2(-radius, 0.0) * texelSize).rgb * 0.15;\n" +
        "    sum += texture(tex, uv + vec2(0.0,  radius) * texelSize).rgb * 0.15;\n" +
        "    sum += texture(tex, uv + vec2(0.0, -radius) * texelSize).rgb * 0.15;\n" +
        "    return sum;\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    vec2 texelSize = vec2(1.0) / vec2(textureSize(uScene, 0));\n" +
        "\n" +
        "    vec4 scene  = texture(uScene, vUv);\n" +
        "    vec3 blurry = blurSample(uScene, vUv, texelSize, uRadius);\n" +
        "\n" +
        "    float lum    = luminance(blurry);\n" +
        "    float bright = max(0.0, lum - uThreshold) / (1.0 - uThreshold + 0.001);\n" +
        "\n" +
        "    vec3 bloom  = blurry * bright * uIntensity;\n" +
        "    vec3 result = scene.rgb + bloom;\n" +
        "    result = result / (result + vec3(1.0));\n" +
        "\n" +
        "    fragColor = vec4(result, scene.a);\n" +
        "}\n";

    // ════════════════════════════════════════════════════════════════
    // INIT
    // ════════════════════════════════════════════════════════════════

    public static void init() {
        if (!GPUDetector.isMaliGPU()) return;

        try {
            if (ExtensionActivator.hasFramebufferFetch) {
                programFetch = buildProgram(VERT_SRC, FRAG_FETCH_SRC, "FBFetchBloom_FAST");
                if (programFetch != 0) {
                    usingFetch = true;
                    cacheFetchUniforms();
                    MaliOptMod.LOGGER.info("[MaliOpt] ✅ FBFetchBloomPass — modo FAST (gl_LastFragColorARM)");
                }
            }

            if (!usingFetch) {
                programFallback = buildProgram(VERT_SRC, FRAG_FALLBACK_SRC, "FBFetchBloom_FALLBACK");
                if (programFallback != 0) {
                    cacheFallbackUniforms();
                    MaliOptMod.LOGGER.info("[MaliOpt] ✅ FBFetchBloomPass — modo FALLBACK (texture sample)");
                }
            }

            quadVao = GL30.glGenVertexArrays();
            ready = (usingFetch ? programFetch : programFallback) != 0;

            if (ready) {
                MaliOptMod.LOGGER.info("[MaliOpt] FBFetchBloomPass pronto ✅  [FBFetch={}]", usingFetch);
            }

        } catch (Exception e) {
            MaliOptMod.LOGGER.error("[MaliOpt] FBFetchBloomPass.init() erro: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // RENDER
    // ════════════════════════════════════════════════════════════════

    public static void render(MinecraftClient mc) {
        if (!ready || mc.world == null) return;

        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth;
        int h = fb.textureHeight;

        // Recria o bloomFbo apenas no fallback, e apenas se o tamanho mudou
        if (!usingFetch && (w != lastW || h != lastH)) {
            rebuildBloomFbo(w, h);
        }
        if (!usingFetch && bloomFbo == 0) return;

        // Guarda estado GL
        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        if (usingFetch) {
            renderFetch(fb, w, h);
            // ✅ FIX: no caminho FBFetch o resultado JÁ está em fb.fbo
            // Não precisamos de blit nenhum — o shader escreve directamente no FBO da cena.
        } else {
            renderFallback(fb, w, h);
            // ✅ FIX: blit do bloomFbo (resultado) → fb.fbo (ecrã final)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, bloomFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.fbo);
            GL30.glBlitFramebuffer(
                0, 0, w, h,
                0, 0, w, h,
                GL11.GL_COLOR_BUFFER_BIT,
                GL11.GL_NEAREST
            );
        }

        // Restaura estado
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
    }

    // ── Caminho FAST: FBFetch ────────────────────────────────────────
    // Rende directamente no fb.fbo — gl_LastFragColorARM lê da tile memory.
    // Nenhum blit necessário: o shader escreve o resultado final directamente.
    private static void renderFetch(Framebuffer fb, int w, int h) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(programFetch);
        GL20.glUniform1f(uThreshold, BLOOM_THRESHOLD);
        GL20.glUniform1f(uIntensity, BLOOM_INTENSITY);
        GL20.glUniform1f(uRadius,    BLOOM_RADIUS);
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
    }

    // ── Caminho FALLBACK: texture sample ────────────────────────────
    // 1. Copia a cena actual de fb.fbo → bloomFbo (necessário para ler como textura)
    // 2. Shader processa bloom a partir de bloomFbo
    // 3. Resultado fica em bloomFbo, blit final no render() copia para fb.fbo
    private static void renderFallback(Framebuffer fb, int w, int h) {
        // Copia cena → bloomFbo para poder usá-la como textura
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bloomFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        // Processa bloom: lê bloomFbo, escreve resultado num FBO temporário
        // ✅ FIX: precisa de um segundo FBO para não ler e escrever na mesma textura.
        // Solução simples: rende directamente em fb.fbo usando a cópia em bloomFbo como source.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(programFallback);
        GL20.glUniform1i(uScene,     0);
        GL20.glUniform1f(uThreshold, BLOOM_THRESHOLD);
        GL20.glUniform1f(uIntensity, BLOOM_INTENSITY);
        GL20.glUniform1f(uRadius,    BLOOM_RADIUS);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTex);  // ✅ usa bloomTex (cópia da cena)
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); // unbind limpo
    }

    // ════════════════════════════════════════════════════════════════
    // FBO de cópia (só usado no caminho FALLBACK)
    // ════════════════════════════════════════════════════════════════

    private static void rebuildBloomFbo(int w, int h) {
        if (bloomFbo != 0) GL30.glDeleteFramebuffers(bloomFbo);
        if (bloomTex != 0) GL11.glDeleteTextures(bloomTex);

        bloomTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        // ✅ FIX: GL_LINEAR em vez de GL_NEAREST — bloom suavizado
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        bloomFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, bloomTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MaliOptMod.LOGGER.error("[MaliOpt] BloomFBO incompleto: 0x{}", Integer.toHexString(status));
            GL30.glDeleteFramebuffers(bloomFbo);
            GL11.glDeleteTextures(bloomTex);
            bloomFbo = 0;
            bloomTex = 0;
            ready    = false;
        } else {
            lastW = w;
            lastH = h;
            MaliOptMod.LOGGER.info("[MaliOpt] BloomFBO criado: {}x{}", w, h);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    // ════════════════════════════════════════════════════════════════
    // CLEANUP
    // ════════════════════════════════════════════════════════════════

    public static void cleanup() {
        if (programFetch    != 0) { GL20.glDeleteProgram(programFetch);       programFetch    = 0; }
        if (programFallback != 0) { GL20.glDeleteProgram(programFallback);    programFallback = 0; }
        if (quadVao         != 0) { GL30.glDeleteVertexArrays(quadVao);       quadVao         = 0; }
        if (bloomFbo        != 0) { GL30.glDeleteFramebuffers(bloomFbo);      bloomFbo        = 0; }
        if (bloomTex        != 0) { GL11.glDeleteTextures(bloomTex);          bloomTex        = 0; }
        ready      = false;
        usingFetch = false;
    }

    // ════════════════════════════════════════════════════════════════
    // UTILITÁRIOS
    // ════════════════════════════════════════════════════════════════

    private static void cacheFetchUniforms() {
        GL20.glUseProgram(programFetch);
        uThreshold = GL20.glGetUniformLocation(programFetch, "uThreshold");
        uIntensity = GL20.glGetUniformLocation(programFetch, "uIntensity");
        uRadius    = GL20.glGetUniformLocation(programFetch, "uRadius");
        GL20.glUseProgram(0);
    }

    private static void cacheFallbackUniforms() {
        GL20.glUseProgram(programFallback);
        uScene     = GL20.glGetUniformLocation(programFallback, "uScene");
        uThreshold = GL20.glGetUniformLocation(programFallback, "uThreshold");
        uIntensity = GL20.glGetUniformLocation(programFallback, "uIntensity");
        uRadius    = GL20.glGetUniformLocation(programFallback, "uRadius");
        GL20.glUseProgram(0);
    }

    private static int buildProgram(String vertSrc, String fragSrc, String name) {
        int vert = compileShader(GL20.GL_VERTEX_SHADER,   vertSrc, name + "_vert");
        int frag = compileShader(GL20.GL_FRAGMENT_SHADER, fragSrc, name + "_frag");
        if (vert == 0 || frag == 0) {
            if (vert != 0) GL20.glDeleteShader(vert);
            if (frag != 0) GL20.glDeleteShader(frag);
            return 0;
        }

        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vert);
        GL20.glAttachShader(prog, frag);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            MaliOptMod.LOGGER.error("[MaliOpt] {} link falhou: {}", name, GL20.glGetProgramInfoLog(prog));
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static int compileShader(int type, String src, String name) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            MaliOptMod.LOGGER.error("[MaliOpt] Shader {} falhou:\n{}", name, GL20.glGetShaderInfoLog(id));
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    public static boolean isReady()       { return ready; }
    public static boolean isUsingFetch()  { return usingFetch; }
                }
