package dev.nxnative.android;

import java.util.Locale;

/**
 * The compiling-shaders screen's words and numbers (android/README.md, In-game notices; pure Java: dm/tools/tests/test_android_preparescreen.py runs it on the JVM).
 * While the host holds the game at its start to make the pipelines of earlier runs and of the game's profile (vka_pso.c, DM_VK_PREPARE), it answers
 * Native.prepareStatus() with "&lt;taken&gt; &lt;total&gt; &lt;seconds left or -1&gt;"; GameActivity shows the screen while that answer is not empty.
 */
final class PrepareScreen {
    private PrepareScreen() {}

    /** The host's answer as {taken, total, seconds left (-1: not known yet)}; null when there is no hold to show (empty, or not three numbers) */
    static int[] parse(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("\\s+");
        if (p.length != 3) return null;
        try {
            int done = Integer.parseInt(p[0]), total = Integer.parseInt(p[1]), eta = Integer.parseInt(p[2]);
            if (total <= 0 || done < 0) return null;
            return new int[] {Math.min(done, total), total, eta < 0 ? -1 : eta};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 0..1 for the ring */
    static float fraction(int[] st) { return st == null || st[1] <= 0 ? 0f : (float) st[0] / st[1]; }

    static String title() { return "Compiling shaders"; }

    /** "812 of 1,875" */
    static String count(int[] st) { return String.format(Locale.US, "%,d of %,d", st[0], st[1]); }

    /** The time left, rounded so that it does not jump about: "About 40 s left", "About 2 min left"; "Working out the time left" before the host knows */
    static String left(int[] st) {
        int s = st[2];
        if (s < 0) return "Working out the time left";
        if (s < 10) return "Almost done";
        if (s < 60) return "About " + ((s + 4) / 5 * 5) + " s left";
        int m = (s + 30) / 60;
        return "About " + m + " min left";
    }

    static String note() { return "This happens once for each GPU driver, after the game or the driver changes. Later starts skip it."; }

    static String skip() { return "Skip"; }

    /** Under the Skip button: what skipping costs */
    static String skipNote() { return "The game starts now and may stutter while the rest compiles."; }
}
