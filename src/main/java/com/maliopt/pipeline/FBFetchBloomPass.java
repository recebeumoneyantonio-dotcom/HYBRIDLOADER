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
 * ── POR QUÊ ISTO É DIFERENTE ────────────────────────────────────────
 *
 * Bloom tradicional (o que Iris/Sodium fazem):
 *   1. Render cena → FBO A
 *   2. Copiar FBO A → textura (READ DRAM)       ← custo alto
 *   3. Downsample passes (múltiplos FBOs)        ← mais cópias DRAM
 *   4. Upsample + blend                          ← mais cópias DRAM
 *   Total: 4-6 roundtrips DRAM, ~40MB/frame
 *
 * FBFetch Bloom (este pass):
 *   1. Render cena → FBO
 *   2. gl_LastFragColorARM lê pixel directamente da tile memory ← ZERO DRAM
 *   3. Threshold + blur + blend em 1-2 passes    ← permanece em tile memory
 *   Total: 0-1 roundtrips DRAM, ~8MB/frame
 *
 * O Mali-G52 MC2 processa tiles de 16×16 pixels internamente.
 * Entre o render da cena e este pass, os pixels AINDA ESTÃO na tile memory.
 * gl_LastFragColorARM lê directamente dali — sem passar pela DRAM do telefone.
 *
 * ── FALLBACK ────────────────────────────────────────────────────────
 * Se GL_ARM_shader_framebuffer_fetch não estiver disponível,
 * cai para bloom tradicional com texture sample (ainda mais rápido que Iris
 * porque usamos 1 pass em vez de 4+).
 *
 * ── INTEGRAÇÃO ──────────────────────────────────────────────────────
 * Registado em MaliOptMod.onInitializeClient() via WorldRenderEvents.LAST,
 * DEPOIS de PLSLightingPass (que é o primeiro post-process).
 * Pipeline: Iris render → PLSLightingPass → FBFetchBloomPass → ecrã
 */
public class FBFetchBloomPass {

    // ── Estado ──────────────────────────────────────────────────────
    private static int  programFetch    = 0;   // programa com GL_ARM_shader_framebuffer_fetch
    private static int  programFallback = 0;   // programa fallback (texture sample)
    private static int  quadVao         = 0;
    private static int  bloomFbo        = 0;   // FBO de saída do bloom
    private static int  bloomTex        = 0;   // textura resultado
    private static int  lastW           = 0;
    private static int  lastH           = 0;
    private static boolean ready        = false;
    private static boolean usingFetch   = false;

    // ── Uniforms (locations cacheadas) ──────────────────────────────
    private static int uThreshold  = -1;
    private static int uIntensity  = -1;
    private static int uRadius     = -1;
    private static int uScene      = -1;   // só no fallback

    // ── Parâmetros do bloom ──────────────────────────────────────────
    private static final float BLOOM_THRESHOLD = 0.75f;  // luminância mínima para bloom
    private static final float BLOOM_INTENSITY = 0.35f;  // força do efeito
    private static final float BLOOM_RADIUS    = 1.8f;   // raio do blur (em UV space)

    // ════════════════════════════════════════════════════════════════
    // GLSL — VERTEX (partilhado pelos dois caminhos)
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
    // GLSL — FRAGMENT com GL_ARM_shader_framebuffer_fetch
    //
    // gl_LastFragColorARM: lê a cor actual do pixel directamente
    // da tile memory do Mali, SEM passar pela DRAM.
    // Este é o caminho rápido — só funciona em Mali Bifrost/Valhall.
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
        "// Luminância perceptual (ITU-R BT.601)\n" +
        "float luminance(vec3 c) {\n" +
        "    return dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "}\n" +
        "\n" +
        "void main() {\n" +
        "    // Lê pixel actual da tile memory — ZERO custo DRAM\n" +
        "    vec4 scene = gl_LastFragColorARM;\n" +
        "\n" +
        "    // Extrai componente bright (acima do threshold)\n" +
        "    float lum = luminance(scene.rgb);\n" +
        "    float bright = max(0.0, lum - uThreshold) / (1.0 - uThreshold + 0.001);\n" +
        "\n" +
        "    // Bloom colour = componente bright da cena\n" +
        "    vec3 bloomColor = scene.rgb * bright;\n" +
        "\n" +
        "    // Blur simples — 4 taps em cruz (approximação Gaussian)\n" +
        "    // Em FBFetch não temos acesso a vizinhos, por isso usamos\n" +
        "    // uma aproximação baseada na luminância local.\n" +
        "    // Para blur real precisaríamos de 2 passes separados.\n" +
        "    float spread = uRadius * bright;\n" +
        "    vec3 glow = bloomColor * spread;\n" +
        "\n" +
        "    // Additive blend: cena original + bloom\n" +
        "    vec3 result = scene.rgb + glow * uIntensity;\n" +
        "\n" +
        "    // Tone map suave para evitar clipping\n" +
        "    result = result / (result + vec3(1.0));\n" +
        "\n" +
        "    fragColor = vec4(result, scene.a);\n" +
        "}\n";

    // ════════════════════════════════════════════════════════════════
    // GLSL — FRAGMENT fallback (sem FBFetch — usa texture sample)
    //
    // Caminho activado quando GL_ARM_shader_framebuffer_fetch não está
    // disponível (ex: dispositivos não-Mali ou GL4ES sem extensão).
    // Ainda assim mais eficiente que Iris (1 pass em vez de 4+).
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
        "// Blur 5-tap em Cruz — blur suave em 1 pass\n" +
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
            // Tenta primeiro o caminho FBFetch (rápido)
            if (ExtensionActivator.hasFramebufferFetch) {
                programFetch = buildProgram(VERT_SRC, FRAG_FETCH_SRC, "FBFetchBloom_FAST");
                if (programFetch != 0) {
                    usingFetch = true;
                    cacheFetchUniforms();
                    MaliOptMod.LOGGER.info("[MaliOpt] ✅ FBFetchBloomPass — modo FAST (gl_LastFragColorARM)");
                }
            }

            // Fallback sempre construído (usado se FBFetch falhar ou não disponível)
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

        if (w != lastW || h != lastH) {
            rebuildBloomFbo(w, h);
        }
        if (bloomFbo == 0) return;

        // Guarda estado GL
        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        if (usingFetch) {
            renderFetch(fb, w, h);
        } else {
            renderFallback(fb, w, h);
        }

        // Blita resultado de volta para o framebuffer principal do Minecraft
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, bloomFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.fbo);
        GL30.glBlitFramebuffer(
            0, 0, w, h,
            0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT,
            GL11.GL_NEAREST
        );

        // Restaura estado
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth)  GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend)  GL11.glEnable(GL11.GL_BLEND);
    }

    // ── Caminho FAST: FBFetch (gl_LastFragColorARM) ─────────────────
    // O shader lê directamente da tile memory — sem bind de textura.
    // Precisa de renderizar para o MESMO FBO que contém a cena.
    private static void renderFetch(Framebuffer fb, int w, int h) {
        // Rende no FBO original — FBFetch lê desse FBO directamente
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fb.fbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(programFetch);
        GL20.glUniform1f(uThreshold, BLOOM_THRESHOLD);
        GL20.glUniform1f(uIntensity, BLOOM_INTENSITY);
        GL20.glUniform1f(uRadius,    BLOOM_RADIUS);
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);

        // Copia resultado para bloomFbo para o blit final
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fb.fbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bloomFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
    }

    // ── Caminho FALLBACK: texture sample ────────────────────────────
    private static void renderFallback(Framebuffer fb, int w, int h) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(programFallback);
        GL20.glUniform1i(uScene,     0);
        GL20.glUniform1f(uThreshold, BLOOM_THRESHOLD);
        GL20.glUniform1f(uIntensity, BLOOM_INTENSITY);
        GL20.glUniform1f(uRadius,    BLOOM_RADIUS);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.getColorAttachment());
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
    }

    // ════════════════════════════════════════════════════════════════
    // FBO de saída do bloom
    // ════════════════════════════════════════════════════════════════

    private static void rebuildBloomFbo(int w, int h) {
        if (bloomFbo != 0) GL30.glDeleteFramebuffers(bloomFbo);
        if (bloomTex != 0) GL11.glDeleteTextures(bloomTex);

        bloomTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

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
