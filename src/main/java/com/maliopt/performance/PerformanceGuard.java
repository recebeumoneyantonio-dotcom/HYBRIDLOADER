package com.maliopt.shader;

import com.maliopt.MaliOptMod;
import net.minecraft.client.MinecraftClient;

/**
 * PerformanceGuard — Fase 3c (3/4)
 *
 * Monitora FPS em tempo real e adapta a qualidade dos passes
 * automaticamente para garantir performance.
 *
 * ── LIMIARES ─────────────────────────────────────────────────────
 *   FPS < 20  → DEGRADED  (desliga bloom, PLS em modo mínimo)
 *   FPS < 35  → LOW       (bloom simples, sem AO)
 *   FPS < 55  → MEDIUM    (pipeline normal)
 *   FPS ≥ 55  → HIGH      (pipeline completo)
 *
 * ── SISTEMA DE HISTERESE ─────────────────────────────────────────
 *   Evita flicker ao subir/descer de qualidade constantemente.
 *   Para DESCER qualidade:  precisa de 60 frames abaixo do limiar
 *   Para SUBIR qualidade:   precisa de 120 frames acima do limiar
 *
 * ── USO NOS PASSES ───────────────────────────────────────────────
 *   if (!PerformanceGuard.bloomEnabled()) return;
 *   float intensity = PerformanceGuard.bloomIntensity();
 */
public final class PerformanceGuard {

    // ── Limiares de FPS ─────────────────────────────────────────────
    private static final int FPS_DEGRADED = 20;
    private static final int FPS_LOW      = 35;
    private static final int FPS_MEDIUM   = 55;

    // ── Histerese ────────────────────────────────────────────────────
    private static final int FRAMES_TO_DOWNGRADE = 60;
    private static final int FRAMES_TO_UPGRADE   = 120;

    // ── Estado ───────────────────────────────────────────────────────
    public enum Quality { DEGRADED, LOW, MEDIUM, HIGH }

    private static Quality current        = Quality.HIGH;
    private static Quality target         = Quality.HIGH;
    private static int     frameCounter   = 0;
    private static int     sampleSum      = 0;
    private static int     sampleCount    = 0;
    private static long    lastLogTime    = 0;

    // Janela de amostragem FPS (frames)
    private static final int SAMPLE_WINDOW = 20;

    private PerformanceGuard() {}

    // ════════════════════════════════════════════════════════════════
    // UPDATE — chamar uma vez por frame (ex: no WorldRenderEvents.LAST)
    // ════════════════════════════════════════════════════════════════

    public static void update(MinecraftClient mc) {
        int fps = mc.getCurrentFps();

        // Acumula amostras
        sampleSum += fps;
        sampleCount++;

        if (sampleCount >= SAMPLE_WINDOW) {
            int avgFps = sampleSum / sampleCount;
            sampleSum   = 0;
            sampleCount = 0;
            evaluateFps(avgFps);
        }
    }

    private static void evaluateFps(int avgFps) {
        Quality desired = classify(avgFps);

        if (desired == target) {
            // Sem mudança de alvo — acumula frames confirmados
            frameCounter++;

            int threshold = (desired.ordinal() < current.ordinal())
                ? FRAMES_TO_DOWNGRADE   // descida rápida
                : FRAMES_TO_UPGRADE;    // subida lenta

            if (frameCounter >= threshold) {
                applyQuality(desired, avgFps);
                frameCounter = 0;
            }
        } else {
            // Alvo mudou — reinicia contador
            target       = desired;
            frameCounter = 0;
        }
    }

    private static Quality classify(int fps) {
        if (fps < FPS_DEGRADED) return Quality.DEGRADED;
        if (fps < FPS_LOW)      return Quality.LOW;
        if (fps < FPS_MEDIUM)   return Quality.MEDIUM;
        return Quality.HIGH;
    }

    private static void applyQuality(Quality q, int fps) {
        if (q == current) return;

        Quality prev = current;
        current = q;

        long now = System.currentTimeMillis();
        if (now - lastLogTime > 3000) {  // limita log a 1x por 3s
            MaliOptMod.LOGGER.info(
                "[PerfGuard] Qualidade {} → {} (FPS médio: {})",
                prev, current, fps);
            lastLogTime = now;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // QUERIES — usadas pelos passes
    // ════════════════════════════════════════════════════════════════

    /** Bloom deve estar activo? */
    public static boolean bloomEnabled() {
        return current != Quality.DEGRADED;
    }

    /** Intensidade do bloom ajustada à qualidade actual */
    public static float bloomIntensity() {
        switch (current) {
            case DEGRADED: return 0.0f;
            case LOW:      return 0.15f;
            case MEDIUM:   return 0.25f;
            case HIGH:     return 0.35f;
            default:       return 0.35f;
        }
    }

    /** Radius do bloom ajustado à qualidade */
    public static float bloomRadius() {
        switch (current) {
            case DEGRADED: return 0.0f;
            case LOW:      return 0.8f;
            case MEDIUM:   return 1.2f;
            case HIGH:     return 1.8f;
            default:       return 1.8f;
        }
    }

    /** Threshold do bloom (mais alto = menos pixels processados) */
    public static float bloomThreshold() {
        switch (current) {
            case DEGRADED: return 1.0f;   // nada passa (bloom desligado)
            case LOW:      return 0.80f;
            case MEDIUM:   return 0.65f;
            case HIGH:     return 0.55f;
            default:       return 0.55f;
        }
    }

    /** PLSLightingPass deve estar activo? */
    public static boolean lightingPassEnabled() {
        return current != Quality.DEGRADED;
    }

    /** Warmth do lighting pass ajustado */
    public static float warmth() {
        switch (current) {
            case DEGRADED: return 0.0f;
            case LOW:      return 0.04f;
            case MEDIUM:   return 0.06f;
            case HIGH:     return 0.06f;
            default:       return 0.06f;
        }
    }

    /** AO do lighting pass ajustado */
    public static float ambientOcclusion() {
        switch (current) {
            case DEGRADED: return 0.0f;
            case LOW:      return 0.06f;
            case MEDIUM:   return 0.10f;
            case HIGH:     return 0.12f;
            default:       return 0.12f;
        }
    }

    /** Qualidade actual (para UI de debug) */
    public static Quality getCurrentQuality() { return current; }

    /** FPS necessário para subir para HIGH */
    public static int targetFpsForHigh() { return FPS_MEDIUM; }
  }
