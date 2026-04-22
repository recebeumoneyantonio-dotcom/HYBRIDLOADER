package com.hybridcore.maliopt;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * FBFetchBloomPass v3.1 — Bloom ACES + Saturação
 *
 * Pass 2 do pipeline de pós-processamento MaliOpt.
 * Corre em WorldRenderEvents.LAST, depois do PLSLightingPass.
 *
 * Pipeline por frame:
 *   1. Copiar cena principal → sceneCopyFbo   (resolução completa)
 *   2. Extrair pixels brilhantes → brightFbo   (meia resolução)
 *   3. Blur Gaussiano horizontal → blurFbo     (meia resolução)
 *   4. Blur Gaussiano vertical → brightFbo     (meia resolução, reutilizado)
 *   5. Composite ACES + Saturação → framebuffer principal
 *
 * CORRECÇÕES APLICADAS:
 *   Fix 3 — highp vec3(x) é sintaxe inválida em GLSL ES.
 *            "highp" só pode aparecer na DECLARAÇÃO de uma variável,
 *            nunca como operador de cast.
 *            Linha corrigida:
 *              ERRADO:  highp vec3 hdr = highp vec3(scene) + highp vec3(glow) * highp float(uIntensity);
 *              CORRECTO: highp vec3 hdr = vec3(scene) + vec3(glow) * uIntensity;
 *
 *   Fix 2 — uSaturation deve ser inicializado explicitamente.
 *            O valor padrão de um uniform float não inicializado é 0.0.
 *            mix(gray, color, 0.0) devolve apenas cinzento → imagem monocromática.
 *            Solução: glUniform1f(uCompSaturation, 1.2f) em phaseComposite().
 *
 *   Fix smoothstep — não aplicável neste pass (é o PLSLightingPass que usa smoothstep).
 *
 * REGRAS DO PIPELINE:
 *   - NUNCA aplicar tonemapping no PLSLightingPass. O tonemapping (ACES)
 *     é responsabilidade EXCLUSIVA deste pass.
 *   - NUNCA usar loops com contagem variável em fragment shaders no Mali-G52.
 *   - NUNCA declarar uniforms sem os registar em cacheUniforms() e inicializar
 *     em cada chamada do pass correspondente.
 */
public class FBFetchBloomPass {

    private static final Logger LOGGER = LoggerFactory.getLogger("MaliOpt");

    /*
     * Resolução de trabalho do bloom — meia resolução do ecrã.
     * O Mali-G52 MC2 tem apenas 2 shader cores: processar bloom a
     * resolução completa desperdiçaria ciclos visualmente imperceptíveis.
     * O composite final é feito a resolução completa com upscale linear.
     */
    private static final int BLOOM_W = 540;
    private static final int BLOOM_H = 270;

    // ----- FBOs e texturas -----
    private int sceneCopyFbo = -1;
    private int sceneCopyTex = -1;
    private int brightFbo    = -1;
    private int brightTex    = -1;
    private int blurFbo      = -1;
    private int blurTex      = -1;

    // ----- Programas GLSL -----
    private int progBright    = -1;
    private int progBlur      = -1;
    private int progComposite = -1;

    // ----- Uniforms: Bright -----
    private int uBrightScene     = -1;
    private int uBrightThreshold = -1;

    // ----- Uniforms: Blur -----
    private int uBlurSrc       = -1;
    private int uBlurDirection = -1;
    private int uBlurTexelSize = -1;

    // ----- Uniforms: Composite -----
    private int uCompScene      = -1;
    private int uCompBloom      = -1;
    private int uCompIntensity  = -1;
    private int uCompSaturation = -1;  // Fix 2: declarado aqui, inicializado em phaseComposite()

    // ----- Quad fullscreen -----
    private int quadVao = -1;
    private int quadVbo = -1;

    // ----- Dimensões do framebuffer principal -----
    private int screenW = 1080;
    private int screenH = 540;

    private boolean initialized = false;
    private boolean broken      = false;

    // =========================================================
    //  SHADERS GLSL ES 3.0
    // =========================================================

    /** Vertex shader partilhado por todos os passes — NDC quad simples. */
    private static final String VERT_SHARED =
        "#version 300 es\n"
      + "precision highp float;\n"
      + "layout(location = 0) in vec2 aPos;\n"
      + "out vec2 vUV;\n"
      + "void main() {\n"
      + "    vUV = aPos * 0.5 + 0.5;\n"
      + "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
      + "}\n";

    // ----------------------------------------------------------
    // FASE 1 — Extracção de pixels brilhantes
    // Pixels com luminância acima do threshold contribuem para o bloom.
    // A intensidade do bright é proporcional ao excesso acima do threshold,
    // o que cria uma transição suave em vez de um corte duro.
    // ----------------------------------------------------------
    private static final String FRAG_BRIGHT =
        "#version 300 es\n"
      + "precision mediump float;\n"
      + "in vec2 vUV;\n"
      + "out vec4 fragColor;\n"
      + "uniform sampler2D uScene;\n"
      + "uniform float uThreshold;\n"
      + "void main() {\n"
      + "    vec3 col = texture(uScene, vUV).rgb;\n"
      + "    // Luminância perceptual BT.709\n"
      + "    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));\n"
      + "    // Extracção suave: quanto acima do threshold, mais brilhante\n"
      + "    float excess = max(lum - uThreshold, 0.0);\n"
      + "    float scale  = excess / max(1.0 - uThreshold, 0.001);\n"
      + "    fragColor = vec4(col * scale, 1.0);\n"
      + "}\n";

    // ----------------------------------------------------------
    // FASE 2 — Blur Gaussiano 9-tap separável (bilinear trick)
    // Pesos de um kernel Gaussiano com sigma=1.0 optimizado para
    // 5 amostras via bilinear filtering (equivale a 9-tap exacto).
    // A separabilidade permite fazer horizontal + vertical em dois passes
    // com custo de 5 amostras cada, em vez de 9×9 = 81 num único pass.
    // ----------------------------------------------------------
    private static final String FRAG_BLUR =
        "#version 300 es\n"
      + "precision mediump float;\n"
      + "in vec2 vUV;\n"
      + "out vec4 fragColor;\n"
      + "uniform sampler2D uSrc;\n"
      + "uniform vec2 uDirection;\n"   // (1,0) horizontal ou (0,1) vertical
      + "uniform vec2 uTexelSize;\n"    // 1/largura, 1/altura
      + "void main() {\n"
      + "    vec2 step1 = uDirection * uTexelSize * 1.3846153846;\n"
      + "    vec2 step2 = uDirection * uTexelSize * 3.2307692308;\n"
      + "    vec4 result = texture(uSrc, vUV)            * 0.2270270270;\n"
      + "    result     += texture(uSrc, vUV + step1)    * 0.3162162162;\n"
      + "    result     += texture(uSrc, vUV - step1)    * 0.3162162162;\n"
      + "    result     += texture(uSrc, vUV + step2)    * 0.0702702703;\n"
      + "    result     += texture(uSrc, vUV - step2)    * 0.0702702703;\n"
      + "    fragColor = result;\n"
      + "}\n";

    // ----------------------------------------------------------
    // FASE 3 — Composite: cena + bloom, ACES tonemapping, saturação
    //
    // FIX 3 — REGRA DEFINITIVA GLSL ES:
    //   "highp" é um qualificador de precisão que APENAS pode aparecer
    //   na declaração de uma variável. NÃO é um operador de cast.
    //   A expressão "highp vec3(x)" não existe em GLSL ES.
    //
    //   ERRADO:  highp vec3 hdr = highp vec3(scene) + highp vec3(glow) * highp float(u);
    //   CORRECTO: highp vec3 hdr = vec3(scene) + vec3(glow) * u;
    //             ^--- "highp" apenas aqui, na declaração
    //
    // FIX 2 — uSaturation:
    //   Inicializado em Java: glUniform1f(uCompSaturation, 1.2f)
    //   Sem isso, o valor seria 0.0 → mix(gray, color, 0.0) = cinzento.
    //
    // ACES Filmic Tonemapper (Narkowicz 2015):
    //   f(x) = clamp( x(2.51x + 0.03) / (x(2.43x + 0.59) + 0.14), 0, 1 )
    //   Opera em vec3: cada canal R, G, B é processado independentemente.
    //   Isto preserva a saturação e faz roll-off suave nos highlights.
    //
    // Este pass é o ÚNICO no pipeline que aplica tonemapping.
    // O PLSLightingPass não deve tonar — só warmth e AO cirúrgicos.
    // ----------------------------------------------------------
    private static final String FRAG_COMPOSITE =
        "#version 300 es\n"
      + "precision mediump float;\n"
      + "in vec2 vUV;\n"
      + "out vec4 fragColor;\n"
      + "uniform sampler2D uScene;\n"
      + "uniform sampler2D uBloom;\n"
      + "uniform float uIntensity;\n"
      + "uniform float uSaturation;\n"   // Fix 2: deve ser 1.2
      + "\n"
      + "// ACES Filmic Tonemapper — Narkowicz 2015\n"
      + "// Opera em vec3: sem loops, sem branches — ideal para Mali-G52\n"
      + "vec3 aces(vec3 x) {\n"
      + "    return clamp(\n"
      + "        (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14),\n"
      + "        0.0, 1.0\n"
      + "    );\n"
      + "}\n"
      + "\n"
      + "void main() {\n"
      + "    vec3 scene = texture(uScene, vUV).rgb;\n"
      + "    vec3 glow  = texture(uBloom, vUV).rgb;\n"
      + "\n"
      + "    // Fix 3: highp APENAS na declaração da variável\n"
      + "    // NÃO usar: highp vec3(scene) — essa sintaxe não existe\n"
      + "    highp vec3 hdr = vec3(scene) + vec3(glow) * uIntensity;\n"
      + "\n"
      + "    // ACES tonemapping — responsabilidade exclusiva deste pass\n"
      + "    vec3 color = aces(hdr);\n"
      + "\n"
      + "    // Boost de saturação pós-ACES (Fix 2: uSaturation = 1.2)\n"
      + "    // mix(a, b, 0.0) = a; mix(a, b, 1.0) = b; > 1.0 = supersaturação\n"
      + "    float gray = dot(color, vec3(0.2126, 0.7152, 0.0722));\n"
      + "    color = mix(vec3(gray), color, uSaturation);\n"
      + "\n"
      + "    // Clamp final obrigatório — evita artefactos em HDR overflow\n"
      + "    color = clamp(color, 0.0, 1.0);\n"
      + "\n"
      + "    fragColor = vec4(color, 1.0);\n"
      + "}\n";

    // =========================================================
    //  INICIALIZAÇÃO
    // =========================================================

    /**
     * Inicializa o pass. Chamar uma vez após o framebuffer principal
     * estar disponível, passando as suas dimensões reais.
     * Em caso de erro de compilação de shader, broken=true e o pass
     * não corre, sem afectar o resto do jogo.
     */
    public void init(int screenW, int screenH) {
        if (broken) return;
        this.screenW = screenW;
        this.screenH = screenH;

        try {
            buildShaders();
            buildFBOs();
            buildQuad();
            initialized = true;
            LOGGER.info("[MaliOpt] ✅ FBFetchBloomPass v3.1 iniciado ({}x{} → bloom {}x{})",
                        screenW, screenH, BLOOM_W, BLOOM_H);
        } catch (RuntimeException e) {
            LOGGER.error("[MaliOpt] FBFetchBloomPass: falha de inicialização — pass desativado", e);
            broken = true;
            cleanup();
        }
    }

    private void buildShaders() {
        progBright    = compileProgram("Bloom_Bright_frag",    VERT_SHARED, FRAG_BRIGHT);
        progBlur      = compileProgram("Bloom_Blur_frag",      VERT_SHARED, FRAG_BLUR);
        progComposite = compileProgram("Bloom_Composite_frag", VERT_SHARED, FRAG_COMPOSITE);
        cacheUniforms();
    }

    /**
     * Regista todos os uniform locations.
     * REGRA: cada uniform declarado no GLSL DEVE ter um campo int aqui
     * e DEVE ser chamado com glUniform* em cada frame no pass correspondente.
     * Esquecer qualquer um produz bugs visuais difíceis de diagnosticar.
     */
    private void cacheUniforms() {
        uBrightScene     = glGetUniformLocation(progBright,    "uScene");
        uBrightThreshold = glGetUniformLocation(progBright,    "uThreshold");

        uBlurSrc         = glGetUniformLocation(progBlur,      "uSrc");
        uBlurDirection   = glGetUniformLocation(progBlur,      "uDirection");
        uBlurTexelSize   = glGetUniformLocation(progBlur,      "uTexelSize");

        uCompScene       = glGetUniformLocation(progComposite, "uScene");
        uCompBloom       = glGetUniformLocation(progComposite, "uBloom");
        uCompIntensity   = glGetUniformLocation(progComposite, "uIntensity");
        uCompSaturation  = glGetUniformLocation(progComposite, "uSaturation");  // Fix 2
    }

    private void buildFBOs() {
        // Cópia da cena a resolução completa — necessária para o composite final
        sceneCopyTex = createTexture(screenW, screenH);
        sceneCopyFbo = createFBO(sceneCopyTex);

        // Bright mask + resultado do blur — meia resolução
        brightTex = createTexture(BLOOM_W, BLOOM_H);
        brightFbo = createFBO(brightTex);

        blurTex = createTexture(BLOOM_W, BLOOM_H);
        blurFbo = createFBO(blurTex);
    }

    private void buildQuad() {
        // Quad NDC em TRIANGLE_STRIP: (-1,-1), (1,-1), (-1,1), (1,1)
        float[] verts = { -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f };

        quadVao = glGenVertexArrays();
        quadVbo = glGenBuffers();

        glBindVertexArray(quadVao);
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
        glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0L);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    // =========================================================
    //  EXECUÇÃO POR FRAME
    // =========================================================

    /**
     * Executa o bloom pass completo.
     *
     * @param mainFramebuffer ID do framebuffer principal do Minecraft (0 = default)
     */
    public void render(int mainFramebuffer) {
        if (!initialized || broken) return;

        // Guardar estado GL completo — o Minecraft não deve ver estado corrompido
        int[] prevFBO     = new int[1];
        int[] prevProg    = new int[1];
        int[] prevView    = new int[4];
        boolean wasDepth  = glIsEnabled(GL_DEPTH_TEST);
        boolean wasBlend  = glIsEnabled(GL_BLEND);

        glGetIntegerv(GL_FRAMEBUFFER_BINDING, prevFBO);
        glGetIntegerv(GL_CURRENT_PROGRAM,     prevProg);
        glGetIntegerv(GL_VIEWPORT,            prevView);

        // Desactivar depth e blend — o bloom é um pass 2D puro
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glBindVertexArray(quadVao);

        try {
            // Passo 1: copiar cena principal para buffer próprio
            copySceneToFBO(mainFramebuffer);

            // Passo 2: extrair pixels brilhantes (threshold = 0.75)
            phaseBright();

            // Passo 3: blur horizontal (brightTex → blurFbo)
            phaseBlur(brightTex, blurFbo, 1.0f, 0.0f);

            // Passo 4: blur vertical (blurTex → brightFbo, reutilizado como destino final)
            phaseBlur(blurTex, brightFbo, 0.0f, 1.0f);

            // Passo 5: composite ACES de volta para o framebuffer principal
            phaseComposite(mainFramebuffer);

        } finally {
            // Restaurar estado GL — crítico para não corromper o Minecraft
            glBindVertexArray(0);
            glBindFramebuffer(GL_FRAMEBUFFER, prevFBO[0]);
            glUseProgram(prevProg[0]);
            glViewport(prevView[0], prevView[1], prevView[2], prevView[3]);
            if (wasDepth) glEnable(GL_DEPTH_TEST);
            if (wasBlend) glEnable(GL_BLEND);
        }
    }

    /** Copia o framebuffer principal para sceneCopyFbo via blit. */
    private void copySceneToFBO(int mainFbo) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, mainFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneCopyFbo);
        glBlitFramebuffer(
            0, 0, screenW, screenH,
            0, 0, screenW, screenH,
            GL_COLOR_BUFFER_BIT, GL_LINEAR
        );
    }

    /** Phase 1 — extrai pixels brilhantes da cena. */
    private void phaseBright() {
        glBindFramebuffer(GL_FRAMEBUFFER, brightFbo);
        glViewport(0, 0, BLOOM_W, BLOOM_H);
        glUseProgram(progBright);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneCopyTex);
        glUniform1i(uBrightScene, 0);
        glUniform1f(uBrightThreshold, 0.75f);  // threshold: pixels acima de 75% luminância

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Phase 2 — blur Gaussiano separável num único eixo. */
    private void phaseBlur(int srcTex, int dstFbo, float dirX, float dirY) {
        glBindFramebuffer(GL_FRAMEBUFFER, dstFbo);
        glViewport(0, 0, BLOOM_W, BLOOM_H);
        glUseProgram(progBlur);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, srcTex);
        glUniform1i(uBlurSrc, 0);
        glUniform2f(uBlurDirection, dirX, dirY);
        glUniform2f(uBlurTexelSize, 1.0f / BLOOM_W, 1.0f / BLOOM_H);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * Phase 3 — composite final com ACES tonemapping.
     *
     * FIX 2: uSaturation DEVE ser inicializado aqui com glUniform1f.
     * Valor padrão do uniform não inicializado = 0.0 → imagem monocromática.
     */
    private void phaseComposite(int mainFbo) {
        glBindFramebuffer(GL_FRAMEBUFFER, mainFbo);
        glViewport(0, 0, screenW, screenH);
        glUseProgram(progComposite);

        // Textura da cena original (unit 0)
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneCopyTex);
        glUniform1i(uCompScene, 0);

        // Textura do bloom processado (unit 1)
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, brightTex);
        glUniform1i(uCompBloom, 1);

        // Intensidade do bloom — conservador para não overwhelmar a vanilla palette
        glUniform1f(uCompIntensity, 0.35f);

        // FIX 2: uSaturation inicializado explicitamente — NUNCA omitir
        // 1.2 = boost de 20% de saturação pós-ACES, sem over-saturation
        glUniform1f(uCompSaturation, 1.2f);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    // =========================================================
    //  UTILITÁRIOS GL
    // =========================================================

    private int compileProgram(String name, String vertSrc, String fragSrc) {
        int vs = compileShader(name + "_vert", GL_VERTEX_SHADER,   vertSrc);
        int fs = compileShader(name,           GL_FRAGMENT_SHADER, fragSrc);

        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);

        String linkLog = glGetProgramInfoLog(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            glDeleteShader(vs);
            glDeleteShader(fs);
            glDeleteProgram(prog);
            throw new RuntimeException("[SEL] Link " + name + " falhou:\n" + linkLog);
        }
        if (!linkLog.isEmpty()) {
            LOGGER.warn("[MaliOpt] Link {} warnings: {}", name, linkLog);
        }

        glDeleteShader(vs);
        glDeleteShader(fs);
        return prog;
    }

    private int compileShader(String name, int type, String src) {
        int sh = glCreateShader(type);
        glShaderSource(sh, src);
        glCompileShader(sh);

        String log = glGetShaderInfoLog(sh);
        if (glGetShaderi(sh, GL_COMPILE_STATUS) == GL_FALSE) {
            LOGGER.error("[SEL] Shader {} falhou:\n{}", name, log);
            glDeleteShader(sh);
            throw new RuntimeException("[SEL] Shader " + name + " falhou:\n" + log);
        }
        if (!log.isEmpty()) {
            LOGGER.warn("[MaliOpt] Shader {} warnings: {}", name, log);
        }
        return sh;
    }

    private int createTexture(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    private int createFBO(int tex) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incompleto: status=0x" + Integer.toHexString(status));
        }
        return fbo;
    }

    // =========================================================
    //  CLEANUP
    // =========================================================

    public void cleanup() {
        if (sceneCopyFbo != -1) { glDeleteFramebuffers(sceneCopyFbo); sceneCopyFbo = -1; }
        if (sceneCopyTex != -1) { glDeleteTextures(sceneCopyTex);     sceneCopyTex = -1; }
        if (brightFbo    != -1) { glDeleteFramebuffers(brightFbo);    brightFbo    = -1; }
        if (brightTex    != -1) { glDeleteTextures(brightTex);        brightTex    = -1; }
        if (blurFbo      != -1) { glDeleteFramebuffers(blurFbo);      blurFbo      = -1; }
        if (blurTex      != -1) { glDeleteTextures(blurTex);          blurTex      = -1; }
        if (progBright    != -1) { glDeleteProgram(progBright);    progBright    = -1; }
        if (progBlur      != -1) { glDeleteProgram(progBlur);      progBlur      = -1; }
        if (progComposite != -1) { glDeleteProgram(progComposite); progComposite = -1; }
        if (quadVao != -1) { glDeleteVertexArrays(quadVao); quadVao = -1; }
        if (quadVbo != -1) { glDeleteBuffers(quadVbo);      quadVbo = -1; }
        initialized = false;
    }

    // =========================================================
    //  ESTADO
    // =========================================================

    /** @return true se o pass está activo e a processar frames. */
    public boolean isActive() { return initialized && !broken; }

    /** @return true se houve um erro irrecuperável e o pass está desactivado. */
    public boolean isBroken() { return broken; }
}
