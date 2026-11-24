package dev.nxnative.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Lossless Scaling frame generation (LSFG): the user's own Lossless.dll and the settings. The DLL is never part of the APK: the launcher looks for it in a few known places on
 * the phone's storage (it has All files access), takes the first it finds, and keeps a copy in the app's private files; the user can also pick it by hand. The host reads
 * the copy (DM_LSFG_DLL) and the settings (DM_LSFG*), and takes changes made while the game runs through Native.frameGenSet.
 */
final class Lsfg {
    private Lsfg() {}

    /** The places looked in, in order; then the app's own folders */
    static final String[] KNOWN = {"/storage/emulated/0/Documents/Emulation/bios/Lossless.dll", "/storage/emulated/0/Switch2/Lossless.dll", "/storage/emulated/0/Download/Lossless.dll"};
    static final int[] FLOWS = {25, 50, 75, 100};

    /** The main screen's display modes in Hz (the game's window is on it), or null. What PanelRates offers frame generation's choices from */
    static float[] panelModes(Context c) {
        try {
            android.hardware.display.DisplayManager dm = c.getSystemService(android.hardware.display.DisplayManager.class);
            android.view.Display d = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (d == null) return null;
            android.view.Display.Mode[] ms = d.getSupportedModes();
            float[] r = new float[ms.length];
            for (int i = 0; i < ms.length; i++) r[i] = ms[i].getRefreshRate();
            return r;
        } catch (RuntimeException e) { return null; }
    }
    /** Android's own peak refresh setting (Settings.System peak_refresh_rate, Display › Smooth display and the like; 0 when unset or not readable) */
    static float peakHz(Context c) {
        try { return android.provider.Settings.System.getFloat(c.getContentResolver(), "peak_refresh_rate", 0f); } catch (RuntimeException e) { return 0f; }
    }
    /** The fastest refresh the main screen runs a game at (0 = not known: every choice is offered) */
    static int maxHz(Context c) { return PanelRates.maxHz(panelModes(c), peakHz(c)); }
    static int[] multipliers(Context c) { return PanelRates.multipliers(maxHz(c)); }
    static int[] targets(Context c) { return PanelRates.targets(maxHz(c), panelModes(c)); }

    static File privateCopy(Context c) { return new File(c.getFilesDir(), "lsfg/Lossless.dll"); }

    private static SharedPreferences prefs(Context c) { return Prefs.of(c); }
    // The settings: the main screen's defaults (Settings › Frame generation: "lsfg", "lsfg_mult", ...), then each game's own value of each (Game settings › Frame
    // generation, the in-game panel: "<key>@<title ID>"). A game without its own value takes the default; titleId null reads and writes the defaults
    static final String[] KEYS = {"lsfg", "lsfg_mult", "lsfg_target", "lsfg_flow", "lsfg_quality"};
    private static String key(String k, String titleId) { return OwnSetting.key(k, titleId); }
    private static boolean bool(Context c, String k, String titleId, boolean d) { return OwnSetting.bool(Prefs.store(prefs(c)), k, titleId, d); }
    private static int num(Context c, String k, String titleId, int d) { return OwnSetting.num(Prefs.store(prefs(c)), k, titleId, d); }
    static boolean on(Context c) { return on(c, null); }
    static boolean on(Context c, String titleId) { return bool(c, "lsfg", titleId, false); }
    /** The multiplier and target in use: the saved choice, or the nearest one the screen can show (the saved one stays, for a faster refresh setting later) */
    static int multiplier(Context c) { return multiplier(c, null); }
    static int multiplier(Context c, String titleId) { return PanelRates.clamp(num(c, "lsfg_mult", titleId, 2), multipliers(c)); }
    static int target(Context c) { return target(c, null); }
    static int target(Context c, String titleId) { return PanelRates.clamp(num(c, "lsfg_target", titleId, 0), targets(c)); }
    /** Motion estimation resolution, % of the picture: 50 by default (at 1280x720 on an Adreno 740, 3x at the game's own 33 fps where 100 % was not worth one
     *  generated frame). The old Auto (0) was the full size: read as 100 */
    static int flow(Context c) { return flow(c, null); }
    static int flow(Context c, String titleId) { int v = num(c, "lsfg_flow", titleId, 50); return v <= 0 ? 100 : v; }
    /** The quality shader set (lsfg-vk's v3.1: more channels, about four times the GPU time) instead of performance; the host falls back to performance without it in the DLL */
    static boolean quality(Context c) { return quality(c, null); }
    static boolean quality(Context c, String titleId) { return bool(c, "lsfg_quality", titleId, false); }
    /** Whether the game has any frame generation setting of its own */
    static boolean own(Context c, String titleId) { return OwnSetting.own(Prefs.store(prefs(c)), KEYS, titleId); }
    /** The game follows the main screen's frame generation again (every one of its settings) */
    static void ownClear(Context c, String titleId) { Prefs.remove(prefs(c), OwnSetting.ownKeys(KEYS, titleId)); }
    /** A row's value: Off, or On with the multiplier (or the target rate) */
    static String label(Context c, String titleId) {
        if (!on(c, titleId)) return Tx.s(R.string.game_off);
        int t = target(c, titleId);
        return t > 0 ? Tx.s(R.string.game_lsfg_on_target, t) : Tx.s(R.string.home_on_multiplier, multiplier(c, titleId));
    }

    /** Where the DLL in use came from (for the main screen and the log) */
    static String note = "";

    private static boolean looksLikeDll(File f) {
        if (!f.isFile() || f.length() < 100_000) return false;
        try (InputStream in = new FileInputStream(f)) { return in.read() == 'M' && in.read() == 'Z'; } catch (IOException | SecurityException e) { return false; }
    }

    private static void copy(InputStream in, File to) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(to.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        if (!tmp.renameTo(to)) throw new IOException("cannot replace " + to);
    }

    /** The DLL to use: the first known place that has one (copied into private files when the copy differs from it), else an earlier copy. Null = none. */
    static File find(Context c) {
        File copy = privateCopy(c);
        File ext = c.getExternalFilesDir(null);
        String[] where = ext != null ? new String[KNOWN.length + 1] : KNOWN;
        if (ext != null) { System.arraycopy(KNOWN, 0, where, 0, KNOWN.length); where[KNOWN.length] = new File(ext, "Lossless.dll").getPath(); }
        for (String p : where) {
            File f = new File(p);
            if (!looksLikeDll(f)) continue;
            try {
                if (!copy.isFile() || copy.length() != f.length() || copy.lastModified() != f.lastModified()) {
                    try (InputStream in = new FileInputStream(f)) { copy(in, copy); }
                    copy.setLastModified(f.lastModified());
                }
                note = Tx.s(R.string.game_dll_from, p.toString());
                return copy;
            } catch (IOException | SecurityException e) {
                note = Tx.s(R.string.game_dll_could_not_be_copied, p.toString(), e.getMessage());
            }
        }
        if (looksLikeDll(copy)) { note = Tx.s(R.string.game_lossless_dll_the_copy_picked); return copy; }
        if (note.isEmpty() || !note.contains("could not")) note = "Lossless.dll not found (looked in Documents/Emulation/bios, Switch2, Download and the app's files)";
        return null;
    }

    /** The manual pick (the fallback when it is in none of the known places): copied into private files. Null = done, else the error. */
    static String install(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return "cannot open " + uri;
            copy(in, privateCopy(c));
        } catch (IOException | SecurityException e) { return e.getMessage(); }
        if (!looksLikeDll(privateCopy(c))) { privateCopy(c).delete(); return Tx.s(R.string.game_that_file_is_not_a); }
        note = Tx.s(R.string.game_lossless_dll_picked_by_hand);
        return null;
    }

    /** The host's switches for a run of the game (its own settings, else the main screen's; env.txt lines come after these and win) */
    static void env(Context c, List<String> env, String titleId) {
        File dll = find(c);
        if (dll != null) env.add("DM_LSFG_DLL=" + dll.getPath());
        env.add("DM_LSFG=" + (on(c, titleId) ? 1 : 0));
        env.add("DM_LSFG_MULT=" + multiplier(c, titleId));
        env.add("DM_LSFG_TARGET=" + target(c, titleId));
        env.add("DM_LSFG_FLOW=" + flow(c, titleId));
        env.add("DM_LSFG_QUALITY=" + (quality(c, titleId) ? 1 : 0));
    }

    private static RadioGroup choice(Context c, int[] values, int current, String[] labels, java.util.function.IntConsumer pick) {
        RadioGroup g = new RadioGroup(c);
        g.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            RadioButton b = new RadioButton(c);
            b.setText(labels[i]);
            b.setId(1000 + i);
            g.addView(b);
            if (values[i] == current) b.setChecked(true);
        }
        g.setOnCheckedChangeListener((gr, id) -> pick.accept(values[id - 1000]));
        return g;
    }

    /**
     * The settings rows (the main screen's dialog: titleId null, the defaults; a game's Game settings and the in-game panel: that game's own values, each written as
     * the game's own); apply runs after each change (in game: Native.frameGenSet). Returns the status line (null without LSFG in this build)
     */
    static TextView rows(Context c, LinearLayout box, String status, Runnable apply, String titleId) {
        TextView head = new TextView(c);
        head.setText(Tx.s(R.string.game_frame_generation_lossless_scaling_yo));
        head.setPadding(0, 24, 0, 0);
        box.addView(head);
        if (!BuildConfig.NX_LSFG) { TextView t = new TextView(c); t.setText(Tx.s(R.string.game_not_in_this_build_built)); box.addView(t); return null; }
        // The options only where they apply: everything below the switch only with frame generation on; the frames per game frame only with a fixed rate (with a
        // target the multiplier follows the target)
        LinearLayout opts = new LinearLayout(c), fixed = new LinearLayout(c);
        opts.setOrientation(LinearLayout.VERTICAL); fixed.setOrientation(LinearLayout.VERTICAL);
        Runnable vis = () -> { opts.setVisibility(on(c, titleId) ? View.VISIBLE : View.GONE); fixed.setVisibility(target(c, titleId) == 0 ? View.VISIBLE : View.GONE); };
        CheckBox on = new CheckBox(c);
        on.setText(Tx.s(R.string.game_frame_generation_lsfg));
        on.setChecked(on(c, titleId));
        on.setOnCheckedChangeListener((b, v) -> { prefs(c).edit().putBoolean(key("lsfg", titleId), v).commit(); vis.run(); apply.run(); });
        box.addView(on);
        TextView st = new TextView(c);
        st.setText(status);
        st.setTextSize(12);
        box.addView(st);
        // Only the target rates and multipliers the main screen can show (PanelRates: its modes held to Android's peak refresh setting), with a line saying why when some are left out
        int hz = maxHz(c); int[] tv = targets(c), mv = multipliers(c);
        String[] tl = new String[tv.length], ml = new String[mv.length];
        for (int i = 0; i < tv.length; i++) tl[i] = tv[i] == 0 ? Tx.s(R.string.game_fixed) : String.valueOf(tv[i]);
        for (int i = 0; i < mv.length; i++) ml[i] = mv[i] + "x";
        TextView l2 = new TextView(c); l2.setText(Tx.s(R.string.game_target_frame_rate)); opts.addView(l2);
        opts.addView(choice(c, tv, target(c, titleId), tl, v -> { prefs(c).edit().putInt(key("lsfg_target", titleId), v).commit(); vis.run(); apply.run(); }));
        TextView l1 = new TextView(c); l1.setText(Tx.s(R.string.game_frames_per_game_frame)); fixed.addView(l1);
        fixed.addView(choice(c, mv, multiplier(c, titleId), ml, v -> { prefs(c).edit().putInt(key("lsfg_mult", titleId), v).commit(); apply.run(); }));
        opts.addView(fixed);
        if (hz > 0 && (mv.length < PanelRates.MULTS.length || tv.length < PanelRates.TARGETS.length + 1)) {
            TextView lim = new TextView(c); lim.setText(Tx.s(R.string.game_lsfg_screen_limit, hz)); lim.setTextSize(12); opts.addView(lim);
        }
        TextView l3 = new TextView(c); l3.setText(Tx.s(R.string.game_motion_estimation_resolution_lower_i)); opts.addView(l3);
        opts.addView(choice(c, FLOWS, flow(c, titleId), new String[]{"25%", "50%", "75%", "100%"}, v -> { prefs(c).edit().putInt(key("lsfg_flow", titleId), v).commit(); apply.run(); }));
        TextView l4 = new TextView(c); l4.setText(Tx.s(R.string.game_lsfg_shaders)); opts.addView(l4);   // the shader set (lsfg_chain.c: performance or quality)
        opts.addView(choice(c, new int[]{0, 1}, quality(c, titleId) ? 1 : 0, new String[]{Tx.s(R.string.game_performance), Tx.s(R.string.game_lsfg_quality)},
                            v -> { prefs(c).edit().putBoolean(key("lsfg_quality", titleId), v == 1).commit(); apply.run(); }));
        box.addView(opts);
        vis.run();
        return st;
    }

    /** The running game's settings to the host (its own, else the main screen's) */
    static void push(Context c, String titleId) { Native.frameGenQuality(quality(c, titleId)); Native.frameGenSet(on(c, titleId), multiplier(c, titleId), target(c, titleId), flow(c, titleId)); }

    /**
     * While the game runs (GameActivity.hostSetup to onDestroy): the multiplier and target in use follow the screen. A saved 4x held to 2x on a screen at 60 Hz stayed 2x
     * after the screen was set to 120 Hz with the game running (the AYN Thor's own setting), until the next start, as nothing gave the host the new value. Android's peak
     * refresh setting and the main display are watched, and the host gets the settings again when what they allow changes. Returns what stops the watching
     */
    static Runnable follow(Context c, android.os.Handler h, String titleId) {
        final int[] last = {multiplier(c, titleId), target(c, titleId)};
        Runnable check = () -> {
            int m = multiplier(c, titleId), t = target(c, titleId);
            if (m == last[0] && t == last[1]) return;
            last[0] = m; last[1] = t;
            Native.note("frame generation: the screen now runs at up to " + maxHz(c) + " Hz: " + m + "x, target " + (t == 0 ? "fixed" : t + " fps"));
            push(c, titleId);
        };
        android.database.ContentObserver obs = new android.database.ContentObserver(h) { @Override public void onChange(boolean self) { check.run(); } };
        try { c.getContentResolver().registerContentObserver(android.provider.Settings.System.getUriFor("peak_refresh_rate"), false, obs); } catch (RuntimeException e) { /* not watched: the display listener still is */ }
        android.hardware.display.DisplayManager dm = c.getSystemService(android.hardware.display.DisplayManager.class);
        android.hardware.display.DisplayManager.DisplayListener dl = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {}
            @Override public void onDisplayRemoved(int id) {}
            @Override public void onDisplayChanged(int id) { if (id == android.view.Display.DEFAULT_DISPLAY) check.run(); }
        };
        if (dm != null) dm.registerDisplayListener(dl, h);
        return () -> {
            try { c.getContentResolver().unregisterContentObserver(obs); } catch (RuntimeException e) { /* never registered */ }
            if (dm != null) dm.unregisterDisplayListener(dl);
        };
    }
}
