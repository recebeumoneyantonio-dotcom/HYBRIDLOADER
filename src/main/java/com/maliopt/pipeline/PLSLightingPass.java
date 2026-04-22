package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.shader.ShaderExecutionLayer;
import com.maliopt.performance.PerformanceGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.*;

/**
 * PLSLightingPass — Fase 3a (v2.1)
 *
 * ── FILOSOFIA ────────────────────────────────────────────────────
 *
 *   Este pass NÃO é um filtro. É uma melhoria cirúrgica da imagem base.
 *
 *   REGRA DE OURO:
 *     Se desligares este pass, o jogador não deve notar diferença dramática.
 *     Se ligares, deve parecer "o vanilla, mas melhor".
 *
 *   O QUE FAZ:
 *     - Warmth MUITO subtil: apenas nas zonas de lux alto (tochas, sol)
 *     - AO leve: só nas sombras profundas reais (lum < 0.2)
 *     - Micro-contraste: realça edge entre luz e sombra
 *     - ZERO gamma correction aqui — o BloomPass trata isso
 *     - ZERO tonemapping aqui — é responsabilidade exclusiva do BloomPass
 *
 *   O QUE NÃO FAZ (NUNCA):
 *     - Não escurece a imagem globalmente
 *     - Não altera a saturação
 *     - Não aplica qualquer curve global
 *     - Não faz nada se PerformanceGuard estiver em DEGRADED
 *
 * ── INTEGRAÇÃO NO PIPELINE ────────────────────────────────────────
 *   1. PLSLightingPass  → micro-ajustes na base  [SEM tonemapping]
 *   2. FBFetchBloomPass → bloom + glow + ACES    [ÚNICO tonemapper]
 *
 * ── CHANGELOG v2.1 ───────────────────────────────────────────────
 *   FIX: smoothstep(0.2, 0.0, lum) — undefined behavior em GLSL ES
 *        A especificação GLSL ES 3.1 define o resultado como INDEFINIDO
 *        quando edge0 >= edge1. Embora funcione na maioria dos drivers
 *        Mali em prática, é UB técnico que pode quebrar em compilações
 *        futuras ou com optimizações agressivas do compilador.
 *        Corrigido para: 1.0 - smoothstep(0.0, 0.2, lum)
 *        Comportamento idêntico, sem UB.
 *
 * ── SEGURANÇA ────────────────────────────────────────────────────
 *   - Falha silenciosa em qualquer erro de compilação/FBO
 *   - Restaura estado GL completo após render
 *   - Nunca crasha — ready=false desativa o pass inteiro
 */
public class PLSLightingPass {

    private static int     program   = 0;
    private static int     quadVao   = 0;
    private static int     outputFbo = 0;
    private static int     outputTex = 0;
    private static int     lastW     = 0;
    private static int     lastH     = 0;
    private static boolean ready     = false;

    // Uniform locations (cached)
    private static int uWarmth = -1;
    private static int uAO     = -1;

    // ── GLSL VERTEX ───────────────────────────────────────────────────
    // Fullscreen triangle sem buffer — zero alocação
    private static final String VERT =
        "#version 310 es\n" +
        "out vec2 vUv;\n" +
        "void main() {\n" +
        "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n" +
        "    vUv = uv;\n" +
        "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n";

    // ── GLSL FRAGMENT ─────────────────────────────────────────────────
    //
    // WARMTH v2.0:
    //   Só atua em zonas com lum > 0.5 (luz real: tochas, sol, fogo)
    //   Zonas escuras ficam intactas — sem cast amarelo nas sombras
    //   uWarmth=0.05 → boost máximo de +5% no red em zonas muito brilhantes
    //
    // AO v2.1 (FIX):
    //   smoothstep(0.2, 0.0, lum) era UB em GLSL ES (edge0 >= edge1).
    //   Substituído por: 1.0 - smoothstep(0.0, 0.2, lum)
    //   Comportamento idêntico: aoMask=1 quando lum=0, aoMask=0 quando lum>=0.2.
    //   Só escurece onde lum < 0.2 (sombras profundas reais).
    //   Zonas médias e claras: ao=1.0 (sem efeito).
    //
    // MICRO-CONTRASTE:
    //   Leve S-curve local apenas no canal de luminância
    //   Realça a diferença entre zonas claras e escuras sem alterar hue
    //
    private static final String FRAG =
        "#version 310 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D uScene;\n" +
        "uniform float uWarmth;\n" +  // 0.0 – 0.08 (default 0.05)
        "uniform float uAO;\n" +      // 0.0 – 0.15 (default 0.10)
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "\n" +
        "void main() {\n" +
        "    vec4  base = texture(uScene, vUv);\n" +
        "    vec3  c    = base.rgb;\n" +
        "    float lum  = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "\n" +
        "    // ── WARMTH: só em zonas brilhantes (lum > 0.5) ──────────\n" +
        "    // smoothstep garante transição suave sem artefactos\n" +
        "    float warmMask = smoothstep(0.5, 1.0, lum);\n" +
        "    c *= vec3(\n" +
        "        1.0 + uWarmth * warmMask,\n" +
        "        1.0 + uWarmth * 0.35 * warmMask,\n" +
        "        1.0 - uWarmth * 0.20 * warmMask\n" +
        "    );\n" +
        "\n" +
        "    // ── AO: só em sombras profundas (lum < 0.2) ─────────────\n" +
        "    // v2.1 FIX: era smoothstep(0.2, 0.0, lum) — UB em GLSL ES\n" +
        "    // (edge0 >= edge1 → resultado indefinido per-spec).\n" +
        "    // Corrigido: 1.0 - smoothstep(0.0, 0.2, lum)\n" +
        "    // Comportamento idêntico: 0 quando lum>=0.2, 1 quando lum=0.\n" +
        "    float aoMask = 1.0 - smoothstep(0.0, 0.2, lum);\n" +
        "    float ao     = 1.0 - uAO * aoMask;\n" +
        "    c *= ao;\n" +
        "\n" +
        "    // ── MICRO-CONTRASTE: S-curve subtil na luminância ────────\n" +
        "    // Não altera hue — só ajusta o contraste local\n" +
        "    float lumNew   = dot(c, vec3(0.299, 0.587, 0.114));\n" +
        "    float contrast = lumNew * lumNew * (3.0 - 2.0 * lumNew); // smoothstep\n" +
        "    float contrastBlend = 0.06; // intensidade muito baixa\n" +
        "    if (lumNew > 0.001) {\n" +
        "        c *= mix(1.0, contrast / lumNew, contrastBlend);\n" +
        "    }\n" +
        "\n" +
        "    // Sem clamp agressivo — o BloomPass trata os highlights\n" +
        "    fragColor = vec4(c, base.a);\n" +
        "}\n";

    // ── INIT ──────────────────────────────────────────────────────────

    public static void init() {
        try {
            int vert = ShaderExecutionLayer.compile(
                GL20.GL_VERTEX_SHADER, VERT, "LightingPass_vert");
            int frag = ShaderExecutionLayer.compile(
                GL20.GL_FRAGMENT_SHADER, FRAG, "LightingPass_frag");

            if (vert == 0 || frag == 0) {
                MaliOptMod.LOGGER.error("[MaliOpt] PLSLightingPass: shader compilation falhou — pass desativado");
                if (vert != 0) GL20.glDeleteShader(vert);
                if (frag != 0) GL20.glDeleteShader(frag);
                return;
            }

            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vert);
            GL20.glAttachShader(program, frag);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                MaliOptMod.LOGGER.error("[MaliOpt] PLSLightingPass link falhou: {}",
                    GL20.glGetProgramInfoLog(program));
                GL20.glDeleteProgram(program);
                program = 0;
                return;
            }

            // Cache uniform locations
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uScene"), 0);
            uWarmth = GL20.glGetUniformLocation(program, "uWarmth");
            uAO     = GL20.glGetUniformLocation(program, "uAO");
            GL20.glUseProgram(0);

            quadVao = GL30.glGenVertexArrays();
            ready   = true;
            MaliOptMod.LOGGER.info("[MaliOpt] ✅ PLSLightingPass v2.1 iniciado (smoothstep UB fix, vanilla-safe)");

        } catch (Exception e) {
            MaliOptMod.LOGGER.error("[MaliOpt] PLSLightingPass.init() excepção: {}", e.getMessage());
            // Falha silenciosa — ready=false garante que o pass não corre
        }
    }

    // ── RENDER ───────────────────────────────────────────────────────

    public static void render(MinecraftClient mc) {
        if (!ready || program == 0 || mc.world == null) return;
        if (!PerformanceGuard.lightingPassEnabled()) return;

        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth;
        int h = fb.textureHeight;

        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) {
            rebuildOutputFbo(w, h);
        }
        if (outputFbo == 0) return;

        // Guarda estado GL
        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        // Render para outputFbo
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);

        // Uniforms dinâmicos via PerformanceGuard
        GL20.glUniform1f(uWarmth, PerformanceGuard.warmth());
        GL20.glUniform1f(uAO,     PerformanceGuard.ambientOcclusion());

        // Lê a cena actual do framebuffer do Minecraft
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.getColorAttachment());

        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        // Blit resultado → framebuffer principal do Minecraft
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outputFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.fbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        // Restaura estado GL completo
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
    }

    // ── FBO ──────────────────────────────────────────────────────────

    private static void rebuildOutputFbo(int w, int h) {
        // Limpa anterior
        if (outputFbo != 0) { GL30.glDeleteFramebuffers(outputFbo); outputFbo = 0; }
        if (outputTex != 0) { GL11.glDeleteTextures(outputTex);     outputTex = 0; }

        // Tenta RGBA8 primeiro — mais compatível com Mali GLES 3.2
        // RGBA16F seria preferível mas pode falhar em alguns dispositivos
        outputTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        outputFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outputTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MaliOptMod.LOGGER.error("[MaliOpt] PLS FBO incompleto (0x{}) — pass desativado",
                Integer.toHexString(status));
            GL30.glDeleteFramebuffers(outputFbo);
            GL11.glDeleteTextures(outputTex);
            outputFbo = 0;
            outputTex = 0;
            return;
        }

        lastW = w;
        lastH = h;
        MaliOptMod.LOGGER.info("[MaliOpt] PLS FBO RGBA8: {}x{}", w, h);
    }

    // ── CLEANUP ──────────────────────────────────────────────────────

    public static void cleanup() {
        if (program   != 0) { GL20.glDeleteProgram(program);        program   = 0; }
        if (quadVao   != 0) { GL30.glDeleteVertexArrays(quadVao);   quadVao   = 0; }
        if (outputFbo != 0) { GL30.glDeleteFramebuffers(outputFbo); outputFbo = 0; }
        if (outputTex != 0) { GL11.glDeleteTextures(outputTex);     outputTex = 0; }
        ready = false;
    }

    public static boolean isReady() { return ready; }
}
