package com.maliopt.performance;

import com.maliopt.MaliOptMod;
import net.minecraft.client.MinecraftClient;

public final class PerformanceGuard {

    private static final int FPS_DEGRADED = 20;
    private static final int FPS_LOW      = 35;
    private static final int FPS_MEDIUM   = 55;

    private static final int FRAMES_TO_DOWNGRADE = 60;
    private static final int FRAMES_TO_UPGRADE   = 120;

    public enum Quality { DEGRADED, LOW, MEDIUM, HIGH }

    private static Quality current      = Quality.HIGH;
    private static Quality target       = Quality.HIGH;
    private static int     frameCounter = 0;
    private static int     sampleSum    = 0;
    private static int     sampleCount  = 0;
    private static long    lastLogTime  = 0;

    private static final int SAMPLE_WINDOW = 20;

    private PerformanceGuard() {}

    public static void update(MinecraftClient mc) {
        if (mc == null) return;
        int fps = mc.getCurrentFps();

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
            frameCounter++;

            int threshold = (desired.ordinal() < current.ordinal())
                ? FRAMES_TO_DOWNGRADE
                : FRAMES_TO_UPGRADE;

            if (frameCounter >= threshold) {
                applyQuality(desired, avgFps);
                frameCounter = 0;
            }
        } else {
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
        if (now - lastLogTime > 3000) {
            MaliOptMod.LOGGER.info("[PerfGuard] Qualidade {} → {} (FPS médio: {})",
                prev, current, fps);
            lastLogTime = now;
        }
    }

    public static boolean bloomEnabled()        { return current != Quality.DEGRADED; }
    public static boolean lightingPassEnabled() { return current != Quality.DEGRADED; }

    public static float bloomIntensity() {
        switch (current) {
            case DEGRADED: return 0.0f;
            case LOW:      return 0.15f;
            case MEDIUM:   return 0.25f;
            default:       return 0.35f;
        }
    }

    public static float bloomRadius() {
        switch (current) {
            case DEGRADED: return 0.0f;
            case LOW:      return 0.8f;
            case MEDIUM:   return 1.2f;
            default:       return 1.8f;
        }
    }

    public static float bloomThreshold() {
        switch (current) {
            case DEGRADED: return 1.0f;
            case LOW:      return 0.80f;
            case MEDIUM:   return 0.65f;
            default:       return 0.55f;
        }
    }

    public static float warmth() {
        switch (current) {
            case DEGRADED: return 0.0f;
            case LOW:      return 0.04f;
            default:       return 0.06f;
        }
    }

    public static float ambientOcclusion() {
        switch (current) {
            case DEGRADED: return 0.0f;
            case LOW:      return 0.06f;
            case MEDIUM:   return 0.10f;
            default:       return 0.12f;
        }
    }

    public static Quality getCurrentQuality()  { return current; }
    public static int     targetFpsForHigh()   { return FPS_MEDIUM; }
}
