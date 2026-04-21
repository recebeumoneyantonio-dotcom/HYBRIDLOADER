package com.maliopt.pipeline;

import com.maliopt.MaliOptMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.*;

public class PLSLightingPass {

    private static int     program   = 0;
    private static int     quadVao   = 0;
    private static int     outputFbo = 0;
    private static int     outputTex = 0;
    private static int     lastW     = 0;
    private static int     lastH     = 0;
    private static boolean ready     = false;

    // ── Vertex: fullscreen triangle sem VBO ──────────────────────────
    private static final String VERT =
        "#version 310 es\n" +
        "out vec2 vUv;\n" +
        "void main() {\n" +
        "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n" +
        "    vUv = uv;\n" +
        "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n";

    // ── Fragment: PLS — escreve APENAS no tile memory ─────────────────
    // Regra GL_EXT_shader_pixel_local_storage:
    // não é possível escrever em fragColor E em PLS no mesmo shader.
    // O resultado fica no tile memory; o blit posterior envia para DRAM.
    private static final String FRAG =
        "#version 310 es\n" +
        "#extension GL_EXT_shader_pixel_local_storage : require\n" +
        "precision mediump float;\n" +
        "\n" +
        "// PLS — tile memory para lighting data (lido em passes seguintes)\n" +
        "__pixel_localEXT PLSData {\n" +
        "    layout(rgba8)          lowp    vec4 baseColor;\n" +
        "    layout(r11f_g11f_b10f) mediump vec3 lightAccum;\n" +
        "} pls;\n" +
        "\n" +
        "uniform sampler2D uScene;\n" +
        "uniform float uWarmth;\n" +
        "uniform float uAO;\n" +
        "uniform float uGamma;\n" +
        "\n" +
        "in vec2 vUv;\n" +
        "\n" +
        "void main() {\n" +
        "    vec4  scene = texture(uScene, vUv);\n" +
        "    float lum   = dot(scene.rgb, vec3(0.299, 0.587, 0.114));\n" +
        "\n" +
        "    // Warmth nas zonas iluminadas (tochas, lava, portais)\n" +
        "    vec3 warm = scene.rgb * vec3(\n" +
        "        1.0 + uWarmth * lum,\n" +
        "        1.0 + uWarmth * 0.45 * lum,\n" +
        "        1.0 - uWarmth * 0.25 * lum\n" +
        "    );\n" +
        "\n" +
        "    // AO suave nas zonas escuras\n" +
        "    float ao     = mix(1.0 - uAO, 1.0, lum);\n" +
        "    vec3  result = pow(clamp(warm * ao, 0.0, 1.0), vec3(1.0 / uGamma));\n" +
        "\n" +
        "    // Escreve APENAS no PLS — sem fragColor\n" +
        "    // Tile flush envia para o color attachment automaticamente\n" +
        "    pls.baseColor  = vec4(result, scene.a);\n" +
        "    pls.lightAccum = result;\n" +
        "}\n";

    // ─────────────────────────────────────────────────────────────────

    public static void init() {
        try {
            int vert = compile(GL20.GL_VERTEX_SHADER,   VERT, "PLS_vert");
            int frag = compile(GL20.GL_FRAGMENT_SHADER, FRAG, "PLS_frag");
            if (vert == 0 || frag == 0) return;

            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vert);
            GL20.glAttachShader(program, frag);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(vert);
            GL20.glDeleteShader(frag);

            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                MaliOptMod.LOGGER.error("[MaliOpt] PLS link falhou: {}",
                    GL20.glGetProgramInfoLog(program));
                GL20.glDeleteProgram(program);
                program = 0;
                return;
            }

            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "uScene"),  0);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "uWarmth"), 0.18f);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "uAO"),     0.12f);
            GL20.glUniform1f(GL20.glGetUniformLocation(program, "uGamma"),  1.08f);
            GL20.glUseProgram(0);

            quadVao = GL30.glGenVertexArrays();

            ready = true;
            MaliOptMod.LOGGER.info("[MaliOpt] ✅ PLSLightingPass iniciado");

        } catch (Exception e) {
            MaliOptMod.LOGGER.error("[MaliOpt] PLSLightingPass.init() erro: {}", e.getMessage());
        }
    }

    public static void render(MinecraftClient mc) {
        if (!ready || program == 0 || mc.world == null) return;

        Framebuffer fb = mc.getFramebuffer();
        int w = fb.textureWidth;
        int h = fb.textureHeight;

        if (w != lastW || h != lastH) {
            rebuildOutputFbo(w, h);
        }
        if (outputFbo == 0) return;

        // Guarda estado GL
        int     prevFbo     = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int     prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        boolean depth       = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend       = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);

        // PLS pass → outputFbo
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.getColorAttachment());
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);

        // Blit resultado → FBO do Minecraft
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, outputFbo);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fb.fbo);
        GL30.glBlitFramebuffer(
            0, 0, w, h,
            0, 0, w, h,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
        );

        // Restaura estado
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blend) GL11.glEnable(GL11.GL_BLEND);
    }

    private static void rebuildOutputFbo(int w, int h) {
        if (outputFbo != 0) GL30.glDeleteFramebuffers(outputFbo);
        if (outputTex != 0) GL11.glDeleteTextures(outputTex);

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

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            MaliOptMod.LOGGER.error("[MaliOpt] PLS FBO incompleto: {}", status);
            GL30.glDeleteFramebuffers(outputFbo);
            GL11.glDeleteTextures(outputTex);
            outputFbo = 0;
            outputTex = 0;
        } else {
            lastW = w;
            lastH = h;
            MaliOptMod.LOGGER.info("[MaliOpt] PLS FBO criado: {}x{}", w, h);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    public static void cleanup() {
        if (program   != 0) { GL20.glDeleteProgram(program);        program   = 0; }
        if (quadVao   != 0) { GL30.glDeleteVertexArrays(quadVao);   quadVao   = 0; }
        if (outputFbo != 0) { GL30.glDeleteFramebuffers(outputFbo); outputFbo = 0; }
        if (outputTex != 0) { GL11.glDeleteTextures(outputTex);     outputTex = 0; }
        ready = false;
    }

    public static boolean isReady() { return ready; }

    private static int compile(int type, String src, String name) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            MaliOptMod.LOGGER.error("[MaliOpt] Shader {} falhou: {}",
                name, GL20.glGetShaderInfoLog(id));
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }
}
