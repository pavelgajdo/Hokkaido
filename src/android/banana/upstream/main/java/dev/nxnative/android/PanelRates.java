package dev.nxnative.android;

/**
 * Frame generation's choices the screen can show (Lsfg's rows; no Android types, so PanelRatesJvmTest runs it on the JVM). The screen shows one image a refresh, so a game
 * at 30 fps needs 60 Hz for 2x, 90 for 3x and 120 for 4x; the host's refresh cap (vka_framegen.c) holds the generated frames to what fits anyway, so a choice the screen
 * cannot show only costs GPU work and working images (one set a generated frame). The fastest refresh is the display's fastest mode held to Android's own peak refresh
 * setting: the AYN Thor's main screen has 60 and 120 Hz modes, and with its system setting at 60 Hz it stayed at 60 although frame generation asked for 240 fps.
 */
final class PanelRates {
    private PanelRates() {}

    static final int BASE_FPS = 30;   // the game rate the multipliers are offered for (the common 30 fps game)
    static final int[] MULTS = {2, 3, 4}, TARGETS = {60, 90, 120};

    /** The fastest refresh (Hz, rounded) the screen runs at: its modes' fastest, held to the peak setting when that is a finite rate above 0. 0 = not known */
    static int maxHz(float[] modeHz, float peakHz) {
        float best = 0;
        if (modeHz != null) for (float r : modeHz) if (r > best && !Float.isInfinite(r) && !Float.isNaN(r)) best = r;
        if (best <= 0) return 0;
        if (peakHz > 0 && !Float.isInfinite(peakHz) && !Float.isNaN(peakHz) && peakHz < best) best = peakHz;
        return Math.round(best);
    }

    /** The multipliers whose output at BASE_FPS fits maxHz (2x always; all of them when the rate is not known) */
    static int[] multipliers(int maxHz) {
        if (maxHz <= 0) return MULTS.clone();
        int n = 0; int[] out = new int[MULTS.length];
        for (int m : MULTS) if (m == MULTS[0] || m * BASE_FPS <= maxHz + 1) out[n++] = m;
        return java.util.Arrays.copyOf(out, n);
    }

    /** 0 (a fixed multiplier) and each target rate at most maxHz that a mode runs at or at a whole multiple of (a 120 Hz panel shows 60 evenly, not 90); all when not known */
    static int[] targets(int maxHz, float[] modeHz) {
        int[] out = new int[TARGETS.length + 1]; int n = 0;
        out[n++] = 0;
        for (int t : TARGETS) {
            boolean ok = maxHz <= 0;
            if (!ok && t <= maxHz + 1 && modeHz != null)
                for (float r : modeHz) { int hz = Math.round(r); if (hz >= t && hz <= maxHz + 1 && Math.abs(hz - Math.round((float) hz / t) * t) <= 1) { ok = true; break; } }
            if (ok) out[n++] = t;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** The setting to use: v when it is offered, else the largest offered value under it, else the smallest offered */
    static int clamp(int v, int[] offered) {
        int best = Integer.MIN_VALUE;
        for (int o : offered) { if (o == v) return v; if (o < v && o > best) best = o; }
        if (best != Integer.MIN_VALUE) return best;
        int min = Integer.MAX_VALUE; for (int o : offered) min = Math.min(min, o);
        return offered.length > 0 ? min : v;
    }
}
