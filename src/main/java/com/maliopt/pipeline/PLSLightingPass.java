package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import com.maliopt.shader.ShaderExecutionLayer;
import com.maliopt.shader.PerformanceGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.*;

/**
 * PLSLightingPass — Fase 3a (v1.2)
 *
 * Warmth suave + AO simulado. Sem tonemapping.
 *
 * ── CORRECÇÕES v1.2 ──────────────────────────────────────────────
 *
 *   uWarmth 0.18 → 0.06  — warmth agressivo causava tint laranja
 *   uAO 0.12 mantido     — AO subtil está correcto
 *
 *   Integração com ShaderExecutionLayer:
 *     shaders compilados via SEL.compile() recebem #defines Mali
 *
 *   Integração com PerformanceGuard:
 *     warmth e AO ajustados dinamicamente por FPS
 *     pass desactivado automaticamente em modo DEGRADED
 *
 * ── PIPELINE CORRECTO ────────────────────────────────────────────
 *   1. PLSLightingPass  → warmth (suave) + AO  [sem tonemapping]
 *   2. FBFetchBloomPass → bloom espacial + reinhard [único tonemapper]
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

    // ── GLSL ─────────────────────────────────────────────────────────

    private static final String VERT =
        "#version 310 es\n" +
        "out vec2 vUv;\n" +
        "void main() {\n" +
        "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n" +
        "    vUv = uv;\n" +
        "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n";

    // Warmth subtil + AO. SEM gamma/tonemapping — isso é trabalho do BloomPass.
    // Os #defines Mali são injectados automaticamente pelo ShaderExecutionLayer.
    private static final String FRAG =
        "#version 310 es\n" +
        "precision mediump float;\n" +   // mediump suficiente para color grading
        "uniform sampler2D uScene;\n" +
        "uniform float uWarmth;\n" +
        "uniform float uAO;\n" +
        "in vec2 vUv;\n" +
        "out vec4 fragColor;\n" +
        "\n" +
        "void main() {\n" +
        "    vec4 scene = texture(uScene, vUv);\n" +
        "    float lum  = dot(scene.rgb, vec3(0.299, 0.587, 0.114));\n" +
        "\n" +
        "    // Warmth v1.2: escala reduzida para evitar cast laranja\n" +
        "    // uWarmth=0.06 → boost máximo de +6% no red, -1.5% no blue\n" +
        "    vec3 warm = scene.rgb * vec3(\n" +
        "        1.0 + uWarmth * lum,\n" +
        "        1.0 + uWarmth * 0.45 * lum,\n" +
        "        1.0 - uWarmth * 0.25 * lum\n" +
        "    );\n" +
        "\n" +
        "    // AO simulado: escurece proporcionalmente às zonas escuras\n" +
        "    float ao     = mix(1.0 - uAO, 1.0, lum);\n" +
        "    vec3 result  = warm * ao;\n" +
        "\n" +
        "    // Sem clamp agressivo: permite valores ligeiramente > 1.0\n" +
        "    // O reinhard no BloomPass trata os highlights correctamente\n" +
        "    fragColor = vec4(result, scene.a);\n" +
        "}\n";

    // ── INIT ──────────────────────────────────────────────────────────

    public static void init() {
        try {
            // Compila via SEL — injeta #defines Mali automaticamente
            int vert = ShaderExecutionLayer.compile(
                GL20.GL_VERTEX_SHADER, VERT, "LightingPass_vert");
            int frag = ShaderExecutionLayer.compile(
                GL20.GL_FRAGMENT_SHADER, FRAG, "LightingPass_frag");
            if (vert == 0 || frag == 0) return;

            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vert);
            GL20.glAttachShader(program, frag);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                MaliOptMod.LOGGER.error("[MaliOpt] LightingPass link falhou: {}",
                    GL20.glGetProgramInfoLog(program));
                GL20.glDeleteProgram(program);
                program = 0;
                return;
            }

            // Cache de uniform locations
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uScene"), 0);
            uWarmth = GL20.glGetUniformLocation(program, "uWarmth");
            uAO     = GL20.glGetUniformLocation(program, "uAO");
            GL20.glUseProgram(0);

            quadVao = GL30.glGenVertexArrays();
            ready   = true;
            MaliOptMod.LOGGER.info("[MaliOpt] ✅ PLSLightingPass v1.2 iniciado");
        } catch (Exception e) {
            MaliOptMod.LOGGER.error("[MaliOpt] PLSLightingPass.init(): {}", e.getMessage());
        }
    }

    // ── RENDER ───────────────────────────────────────────────────────

    public static void render(MinecraftClient mc) {
        if (!ready || program == 0 || mc.world == null) return;

        // PerformanceGuard: desactiva em DEGRADED
        if (!PerformanceGuard.lightingPassEnabled()) return;

        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth;
        int h = fb.textureHeight;
        if (w != lastW || h != lastH) rebuildOutputFbo(w, h);
        if (outputFbo == 0) return;

        int prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);

        // Uniforms dinâmicos via PerformanceGuard
        GL20.glUniform1f(uWarmth, PerformanceGuard.warmth());
        GL20.glUniform1f(uAO,     PerformanceGuard.ambientOcclusion());

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.getColorAttachment());
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);  // unbind antes do blit

        // Blit → framebuffer principal
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outputFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.fbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
    }

    // ── FBO ──────────────────────────────────────────────────────────

    private static void rebuildOutputFbo(int w, int h) {
        if (outputFbo != 0) GL30.glDeleteFramebuffers(outputFbo);
        if (outputTex != 0) GL11.glDeleteTextures(outputTex);

        outputTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
            w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);  // FP16 para preservar highlights
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        outputFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outputTex, 0);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MaliOptMod.LOGGER.error("[MaliOpt] PLS FBO incompleto: 0x{}",
                Integer.toHexString(status));
            // Fallback: tenta RGBA8 se RGBA16F não suportado
            GL30.glDeleteFramebuffers(outputFbo);
            GL11.glDeleteTextures(outputTex);
            rebuildOutputFboRGBA8(w, h);
        } else {
            lastW = w; lastH = h;
            MaliOptMod.LOGGER.info("[MaliOpt] PLS FBO RGBA16F: {}x{}", w, h);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static void rebuildOutputFboRGBA8(int w, int h) {
        outputTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, outputTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
            w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        outputFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outputTex, 0);

        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MaliOptMod.LOGGER.error("[MaliOpt] PLS FBO RGBA8 também falhou");
            GL30.glDeleteFramebuffers(outputFbo);
            GL11.glDeleteTextures(outputTex);
            outputFbo = 0; outputTex = 0;
        } else {
            lastW = w; lastH = h;
            MaliOptMod.LOGGER.info("[MaliOpt] PLS FBO RGBA8 (fallback): {}x{}", w, h);
        }
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
