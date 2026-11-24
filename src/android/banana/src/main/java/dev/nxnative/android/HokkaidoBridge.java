// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.nxnative.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Hokkaido runs Banana Native as an engine behind its own UI: what a game needs before it plays, preparing it
 * (PackService's job, as Ondevice starts it), its progress, and starting it (GameActivity, as FrontendLaunch does).
 * Banana Native's own screens stay out of the player's way. Its checks and job code are used as they are, so a
 * sync of upstream/ brings their changes; keep this in step with FrontendLaunch.ready and Ondevice.Panel.
 */
public final class HokkaidoBridge {
    private HokkaidoBridge() {}

    /**
     * NxApp.onCreate's work: Hokkaido's application runs it in every process. Not NxApp.applyTheme: it sets the night
     * mode of the whole app, Hokkaido's screens too.
     */
    public static void install(Context context) {
        android.app.Application app = (android.app.Application) context.getApplicationContext();
        NxApp.app = app;   // Tx reads Banana Native's strings through it
        Words.source = new Words.Source() {
            @Override public String string(String key) {
                int id = app.getResources().getIdentifier(key, "string", app.getPackageName());
                return id == 0 ? null : app.getResources().getString(id);
            }
            @Override public String plural(String key, int count) {
                int id = app.getResources().getIdentifier(key, "plurals", app.getPackageName());
                return id == 0 ? null : app.getResources().getQuantityText(id, count).toString();
            }
        };
        PickedNup.install(context);
        ShaderLibs.appFiles = app.getExternalFilesDir(null);
        Readiness.minSdk = BuildConfig.NX_MIN_SDK;
        UpdateCheck.android12 = BuildConfig.NX_MIN_SDK < 33;
        DumpCheck.workOverride = (dump, titleId) -> workFolder(app, dump, titleId);
        // The embedded launcher does not normally open Banana's MainActivity. Keep its initial GPU facts probe,
        // so the same driver recommendations are available before the first game's readiness check.
        if (app.getPackageName().equals(android.app.Application.getProcessName())) GpuGl.probe(app, null);
    }

    /**
     * Prepare's work folder for a dump: &lt;dump&gt;/../&lt;title ID&gt;-hokkaido-work. Banana Native's own app keeps its job in
     * &lt;title ID&gt;-work beside the same dump; sharing it, each app resumed or overwrote the other's job, whose pack is in
     * the other app's storage. An unfinished Prepare of this app in &lt;title ID&gt;-work (Hokkaido 0.7.0) goes on there.
     */
    static File workFolder(Context c, File dump, String titleId) {
        File parent = dump.getAbsoluteFile().getParentFile();
        File base = parent != null ? parent : dump;
        File ours = new File(base, titleId + "-hokkaido-fusion-work"), shared = new File(base, titleId + "-work");
        if (!ours.exists()) {
            File jf = Job.fileOf(shared);
            if (jf.isFile()) {
                try {
                    Job j = Job.read(jf);
                    if (!"done".equals(j.state()) && SaveStore.inside(j.pack(), AppStorage.packs(c))) return shared;
                } catch (IOException e) {
                    // not a job of this app
                }
            }
        }
        return ours;
    }

    /**
     * Banana Native's status for a game (its profile's readiness.tsv status row: "playable", "bring-up"), or
     * null when this Banana Native has no profile for it and cannot run it.
     */
    public static String status(String titleId) {
        Profile p = Profile.of(titleId == null ? "" : titleId.toUpperCase(Locale.ROOT));
        return p == null ? null : p.status;
    }

    /** Embedded, Banana Native never updates itself: Hokkaido's releases carry it (Updater.sources). */
    static boolean embedded() {
        return true;
    }

    /** What a game needs before it plays in Banana Native. */
    public static final class Plan {
        public static final int READY = 0, PREPARE = 1, RESUME = 2, RUNNING = 3, PROBLEM = 4;

        public final int kind;
        public final String title;
        public final String message;
        public final String detail;
        /** A build Banana Native does not list: preparing it anyway is possible (PrepareText.ANYWAY_WARNING). */
        public final boolean canTryAnyway;

        final String dump;
        final Game game;
        final DumpCheck.Result result;
        final Job job;
        final File packDir;
        final boolean code, shaders;

        Plan(int kind, String title, String message, String detail, boolean canTryAnyway, String dump, Game game,
             DumpCheck.Result result, Job job, File packDir, boolean code, boolean shaders) {
            this.kind = kind;
            this.title = title;
            this.message = message;
            this.detail = detail == null ? "" : detail;
            this.canTryAnyway = canTryAnyway;
            this.dump = dump;
            this.game = game;
            this.result = result;
            this.job = job;
            this.packDir = packDir;
            this.code = code;
            this.shaders = shaders;
        }

        /** The ready game's title ID (READY), else empty. */
        public String titleId() {
            return game != null ? game.titleId : "";
        }

        static Plan problem(String title, String message, String detail) {
            return new Plan(PROBLEM, title, message, detail, false, null, null, null, null, null, false, false);
        }
    }

    /** The plan for a game folder or file. Reads files: call it off the main thread. */
    public static Plan plan(Context c, String dumpPath, boolean tryAnyway) {
        File dump = new File(Frontend.normal(dumpPath));
        if (!dump.exists()) return Plan.problem(Words.s("fusion_native_game_not_found", "Game not found"),
                Words.s("err_path_is_not_there", "%1$s is not there.", dump), null);
        if (!dump.canRead()) return Plan.problem(Words.s("fusion_native_cannot_read", "Cannot read the game"),
                Words.s("err_cannot_be_read", "%1$s cannot be read. Check that All files access is on.", dump), null);

        String id;
        try {
            id = Pack.playableIdOf(dump);
        } catch (IOException e) {
            return Plan.problem(Words.s("fusion_native_cannot_read", "Cannot read the game"), e.getMessage(), null);
        }
        String missing = Profile.missing(id, Words.s("fusion_native_this_game", "This game"));
        if (missing != null) return Plan.problem(Words.s("fusion_native_not_supported", "Not supported by Banana Native"), missing, null);

        migrateWindow(c, id);

        Game ready = ready(c, dump, id, tryAnyway);
        if (ready != null) return new Plan(Plan.READY, ready.name, "", null, false, dump.getPath(), ready, null, null, null, false, false);
        // A finished pack of a build this app has not tested (made with Try anyway) starts after a warning, as MainActivity's Play anyway:
        // preparing it again only makes the same pack
        if (!tryAnyway) {
            Found f = found(c, dump, id);
            BuildCheck.Result b = f == null ? null : untested(c, f.pack, dump);
            if (b != null) {
                Profile p = Profile.of(id);
                String warning = b.kind == BuildCheck.Kind.UNSUPPORTED ? PrepareText.UNSUPPORTED_WARNING() : PrepareText.ANYWAY_WARNING();
                return new Plan(Plan.PROBLEM, untestedTitle(p), b.message + "\n\n" + warning, b.detail,
                                true, dump.getPath(), null, null, null, null, false, false);
            }
        }

        // A job for this dump: running, or one to resume (Ondevice.Panel.checked).
        File existing = null;
        boolean any = tryAnyway;
        try {
            File jf = Job.fileOf(DumpCheck.workOf(dump, Pack.titleIdOf(dump)));
            if (jf.isFile()) {
                existing = jf;
                try { any |= Job.read(jf).tryAnyway(); } catch (IOException e) { /* another format: a new job replaces it */ }
            }
        } catch (IOException e) {
            // DumpCheck says what is wrong
        }
        DumpCheck.Result r = DumpCheck.check(dump, existing == null, any);
        if (!r.ok()) {
            Profile p = Profile.of(id);
            String title = r.otherBuild ? untestedTitle(p) : Words.s("prepare_cannot_prepare_this_dump", "Cannot prepare this dump");
            String message = r.otherBuild ? r.error + "\n\n" + PrepareText.ANYWAY_WARNING() : r.error;
            return new Plan(Plan.PROBLEM, title, message, r.detail, r.otherBuild, dump.getPath(), null, null, null, null, false, false);
        }
        Profile prof = Profile.of(r.titleId);
        String name = prof != null ? prof.name : r.titleId;
        if (existing != null) {
            try {
                Job j = Job.read(existing);
                String s = j.state();
                boolean same = j.dump().getPath().equals(r.dump.getPath()) && j.version() == r.version;
                if (same && "running".equals(s) && PackServiceWatch.alive(c)) {
                    return new Plan(Plan.RUNNING, name, j.statusText(true), null, false, dump.getPath(), null, r, j, null, false, false);
                }
                if (same && (s.equals("running") || s.equals("paused") || s.equals("failed") || s.equals("pending"))) {
                    return new Plan(Plan.RESUME, name, j.statusText(false) + "\n\n" + Words.s("prepare_it_goes_on", "It goes on from where it stopped."), j.errorDetail(),
                                    false, dump.getPath(), null, r, j, null, false, false);
                }
            } catch (IOException e) {
                // a damaged job.json: a new job replaces it
            }
        }

        // A pack that is already there: what fits is kept (Ondevice.Panel.checked).
        File packDir = Folders.packFor(c, r.dump, r.titleId);
        try {
            Pack p = packDir == null ? null : Pack.read(packDir);
            if (p != null && !p.simulated && p.titleId.equals(r.titleId)) {
                String build = PackCheck.blocking(c, p, r.dump);
                String library = PackCheck.shaders(packDir, r.titleId);
                boolean code = build != null;
                // Match Ondevice.Panel.checked: an older playable library or the shader layer makes the full
                // shader set optional, including when the game's code must be rewritten. Build all remains an
                // explicit action in the game's settings; starting a game only prepares what it needs to play.
                String env = Prefs.layerEnv(c, r.titleId);
                boolean covered = Pack.shadersOnDemand(packDir, r.titleId, env);
                boolean older = Pack.libraryOf(packDir) == null
                        && (Pack.playableLibrary(packDir, r.titleId) != null || Pack.legacyLibrary(packDir, r.titleId) != null);
                ShaderState.Need need = ShaderState.need(covered, older, code, library != null);
                boolean shaders = need == ShaderState.Need.CODE_AND_SHADERS || need == ShaderState.Need.SHADERS;
                String what = code && shaders
                        ? Words.s("prepare_rewriting_the_game_s_code_3", "Rewriting the game's code (a few minutes). ") + Ondevice.shadersText(packDir, r.titleId)
                        : code ? Words.s("prepare_rewriting_code_few_minutes", "Rewriting the game's code (a few minutes, in the background).") : Ondevice.shadersText(packDir, r.titleId);
                String why = (build != null ? build + "\n\n" : "") + (library != null ? library : "");
                if (need == ShaderState.Need.CODE_SHADERS_OPTIONAL) {
                    boolean builds = covered || Pack.shaderLayerFills(packDir, r.titleId, env);
                    what += "\n\n" + PrepareText.recommendText(ShaderLibs.minutesText(prof.shaderTotal), builds);
                }
                if (!code && !shaders) what = Words.s("prepare_pack_fits", "Its pack fits: nothing needs preparing.\n\nPack: %1$s", packDir);
                return new Plan(Plan.PREPARE, name, what, why.trim(), false, dump.getPath(), null, r, null, packDir, code, shaders);
            }
        } catch (IOException e) {
            // no usable pack: the whole job
        }
        // A game the shader layer covers is prepared without shaders: it plays once its code is done and builds its shaders while it runs (Ondevice.Panel.checked)
        boolean covered = ShaderLibs.layerCovers(r.titleId, Prefs.layerEnv(c, r.titleId));
        int total = prof != null ? prof.shaderTotal : 0;
        String version = r.version == 0 ? Words.s("prepare_base_game", "Base game") : Words.s("fusion_native_update", "Update %1$d", r.version);
        String head = Words.s("fusion_native_dump_summary", "%1$s%2$s, %3$d DLC.", version,
                r.untested ? Words.s("prepare_untested_version_try_anyway", " (untested version: Try anyway)") : "", r.dlc) + "\n\n";
        String space = "\n\n" + Words.s("fusion_native_prepare_space", "Needs about 1.5 GB of free space (%1$s GB free now).",
                String.format(Locale.getDefault(), "%.1f", r.free / 1073741824.0));
        String text = covered ? head + PrepareText.codeOnlyText(ShaderLibs.minutesText(total)) + space
                : head + Words.s("fusion_native_prepare_full", "Banana Native makes the game's pack from your dump: it rewrites the game's code (a few minutes) and translates %1$s shaders (%2$s in the background, best on the charger). You can pause any time.",
                        String.format(Locale.getDefault(), "%,d", total), ShaderLibs.jobMinutesText(total)) + space;
        String where = Words.s("fusion_native_prepare_paths", "Game: %1$s\nWorking files: %2$s\nPack: %3$s", r.dump, r.work, AppStorage.pack(c, r.titleId));
        return new Plan(Plan.PREPARE, name, text, where, false, dump.getPath(), null, r, null, null, true, !covered);
    }

    private static String untestedTitle(Profile p) {
        return p == null ? Words.s("fusion_native_untested_version", "Untested version")
                : Words.s("fusion_native_untested_game", "Untested version of %1$s", p.name);
    }

    /**
     * The plan that translates every shader of a prepared game now, its code kept (Job.adopt with the shaders; the job a game the shader layer covers
     * leaves to the player): its scenes no longer draw late or wrongly while their shaders are built. The layer stays on for shaders the set lacks.
     * Reads files: call it off the main thread.
     */
    public static Plan shadersPlan(Context c, String dumpPath) {
        File dump = new File(Frontend.normal(dumpPath));
        String id;
        try {
            id = Pack.playableIdOf(dump);
        } catch (IOException e) {
            return Plan.problem(Words.s("fusion_native_cannot_read", "Cannot read the game"), e.getMessage(), null);
        }
        Profile prof = Profile.of(id);
        if (prof == null) return Plan.problem(Words.s("fusion_native_not_supported", "Not supported by Banana Native"),
                Profile.missing(id, Words.s("fusion_native_this_game", "This game")), null);
        boolean any = false;
        try {
            File jf = Job.fileOf(DumpCheck.workOf(dump, Pack.titleIdOf(dump)));
            if (jf.isFile()) {
                Job j = Job.read(jf);
                any = j.tryAnyway();
                if ("running".equals(j.state()) && PackServiceWatch.alive(c)) {
                    return new Plan(Plan.RUNNING, prof.name, j.statusText(true), null, false, dump.getPath(), null, null, j, null, false, false);
                }
            }
        } catch (IOException e) {
            // no job of this format: the new one replaces it
        }
        DumpCheck.Result r = DumpCheck.check(dump, false, any);
        if (!r.ok()) return Plan.problem(Words.s("prepare_cannot_prepare_this_dump", "Cannot prepare this dump"), r.error, r.detail);
        File packDir = Folders.packFor(c, r.dump, r.titleId);
        Pack p = null;
        try {
            p = packDir == null ? null : Pack.read(packDir);
        } catch (IOException e) {
            // no pack: the game is prepared first
        }
        if (p == null || p.simulated || !p.titleId.equals(r.titleId)) {
            return Plan.problem(prof.name, Words.s("fusion_native_prepare_code_first", "Start the game once on Banana Native first: its code is prepared then."), null);
        }
        String text = Words.s("fusion_native_prepare_all_shaders", "Builds the game's full shader set before play. This can reduce incomplete scenes while shaders are compiled. It takes a while and uses the CPU heavily; keep the phone charging. Shaders missing from the set are still built while you play.");
        return new Plan(Plan.PREPARE, prof.name, text, null, false, dump.getPath(), null, r, null, packDir, false, true);
    }

    /**
     * Starts preparing: a new job, one that redoes what an existing pack lacks, or the stopped job resumed. False
     * when nothing needed redoing (a pack that fits is adopted at once): plan again, it is ready.
     */
    public static boolean prepare(Context c, Plan plan) throws Exception {
        Job j;
        if (plan.kind == Plan.RESUME || plan.kind == Plan.RUNNING) {
            j = plan.job;
        } else if (plan.kind == Plan.PREPARE && plan.packDir != null) {
            j = Job.adopt(plan.result, plan.packDir, plan.code, plan.shaders);
            j.save();
            if (!plan.code && !plan.shaders) {
                Job.remember(c, j.file);
                addGame(c, j);
                return false;
            }
        } else if (plan.kind == Plan.PREPARE) {
            j = Job.create(plan.result, AppStorage.pack(c, plan.result.titleId), !plan.shaders);   // (code only: its shaders are built while it plays)
            j.save();
        } else {
            throw new IllegalStateException("nothing to prepare");
        }
        Job.remember(c, j.file);
        c.startForegroundService(new Intent(c, PackService.class).setAction(PackService.ACTION_START)
                .putExtra(PackService.EXTRA_JOB, j.file.getPath()));
        return true;
    }

    /** Pauses the job that runs now (PackService.ACTION_PAUSE). */
    public static void pause(Context c) {
        c.startService(new Intent(c, PackService.class).setAction(PackService.ACTION_PAUSE).putExtra(PackService.EXTRA_FROM, "hokkaido"));
    }

    /**
     * Before a ready game starts, connect Hokkaido's save folder, which both engines then use. A save Banana
     * Native kept in its own folder copies in once, when Hokkaido's is still empty. Driver, resolution and tuning
     * belong to Banana's own settings and recommendations; Hokkaido's defaults must not overwrite them at launch.
     * Reads and writes files: call it off the main thread.
     */
    public static void prepareStart(Context c, Plan plan, String saveDir) throws IOException {
        String id = plan.game.titleId;
        migrateWindow(c, id);
        if (saveDir == null || saveDir.isEmpty()) return;
        File shared = new File(saveDir);
        if (!shared.isDirectory() && !shared.mkdirs()) throw new IOException(Words.s("fusion_native_cannot_create_folder", "Cannot create the folder %1$s.", shared));
        // Only the first time the game uses Hokkaido's folder: later an empty one is a deleted save, never refilled.
        File own = AppStorage.save(c, id);
        boolean first = Prefs.folder(c, "save_" + id) == null;
        if (first && isEmpty(shared) && !own.getAbsoluteFile().equals(shared.getAbsoluteFile()) && !isEmpty(own)) copyTree(own, shared);
        Prefs.folder(c, "save_" + id, shared.getPath());
    }

    private static final String OCARINA_OF_TIME = "0400486024DEC000";

    /**
     * Older fusion releases copied Hokkaido's default 1x into Banana's legacy window setting. That made a fresh
     * game use Quality instead of its profile's recommended preset. Migrate only that exact, marked value; a
     * different size, explicit scene setting or preset remains the player's choice. The marker is then retired.
     */
    private static void migrateWindow(Context c, String id) {
        SharedPreferences prefs = Prefs.of(c);
        String mark = "hokkaido_window_" + id;
        if (!prefs.contains(mark)) return;
        SharedPreferences.Editor e = prefs.edit();
        if ("1920x1080".equals(prefs.getString(mark, null))
                && "1920x1080".equals(prefs.getString(Prefs.gameKey("window", id), null))
                && !prefs.contains(Prefs.gameKey("res_preset", id))
                && !prefs.contains(Prefs.gameKey("scene_res", id))) {
            e.remove(Prefs.gameKey("window", id));
        }
        e.remove(mark).commit();
    }

    /**
     * An explicit action on Banana's game settings page, never automatic: restore its resolution and driver
     * selection, use upstream recommendations, and retire legacy fusion tuning only when its marker and value
     * still match. Different advanced values, saves, controls and non-recommended preferences are kept.
     */
    public static void useRecommendedSettings(Context c, String titleId) {
        if (titleId == null || titleId.isEmpty()) return;
        String id = titleId.toUpperCase(Locale.ROOT);
        SharedPreferences prefs = Prefs.of(c);
        SharedPreferences.Editor e = prefs.edit();
        int legacy = prefs.getInt("hokkaido_settings_" + id, 0);
        if (OCARINA_OF_TIME.equals(id) && legacy > 0) {
            removeIfEqual(prefs, e, Prefs.gameKey("ram_saver", id), false);
            removeIfEqual(prefs, e, Prefs.gameKey("hold60", id), true);
            removeIfEqual(prefs, e, Prefs.gameKey("full_vegetation", id), false);
            if (legacy >= 2) {
                removeIfEqual(prefs, e, "adv:DM_VK_TEX_SOFT@" + id, "1");
                removeIfEqual(prefs, e, "adv:DM_VK_PLACE_PART_MB@" + id, "256");
            }
        }
        if (prefs.getBoolean("hokkaido_mesh_" + id, false)) {
            removeIfEqual(prefs, e, "adv:DM_VK_MESH@" + id, "0");
        }
        e.remove(Prefs.gameKey("window", id)).remove(Prefs.gameKey("scene_res", id))
                .remove(Prefs.gameKey("res_preset", id)).remove(Prefs.gameKey("gpu_driver", id))
                .remove("hokkaido_window_" + id).remove("hokkaido_settings_" + id).remove("hokkaido_mesh_" + id).commit();
        // Read recommendations again after releasing the old explicit driver: they depend on its real facts.
        Prefs.backToRecommended(c, id);
    }

    private static void removeIfEqual(SharedPreferences prefs, SharedPreferences.Editor e, String key, Object value) {
        if (value.equals(prefs.getAll().get(key))) e.remove(key);
    }

    private static boolean isEmpty(File dir) {
        String[] l = dir.list();
        return l == null || l.length == 0;
    }

    private static void copyTree(File from, File to) throws IOException {
        File[] l = from.listFiles();
        if (l == null) return;
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException(Words.s("fusion_native_cannot_create_folder", "Cannot create the folder %1$s.", to));
        for (File f : l) {
            File t = new File(to, f.getName());
            if (f.isDirectory()) {
                copyTree(f, t);
            } else {
                java.nio.file.Files.copy(f.toPath(), t.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * The game Banana Native's game process still holds ({title ID, name}), or null. Each game has a host of its own
     * and the process holds one: a game left without Stop the game keeps it, and another game's start is refused
     * there ("is running, stop it first").
     */
    public static String[] runningGame(Context c) {
        if (gameProcess(c) == null) return null;
        String title = null, name = null;
        try {
            for (String line : java.nio.file.Files.readAllLines(ExitWatch.record(c).toPath())) {
                if (line.startsWith("title=")) title = line.substring(6);
                else if (line.startsWith("name=")) name = line.substring(5);
            }
        } catch (IOException e) {
            // no record: an unknown game
        }
        return new String[] {title == null ? "" : title, name == null ? "" : name};
    }

    /** Closes Banana Native's game process when it holds this game (its save is about to change under it). */
    public static void closeGame(Context c, String titleId) {
        String[] running = runningGame(c);
        if (running != null && running[0].equalsIgnoreCase(titleId)) stopRunningGame(c);
    }

    /** Closes Banana Native's game process (what a game there has not saved is lost); waits up to 3 s for it to go. */
    public static void stopRunningGame(Context c) {
        Integer pid = gameProcess(c);
        if (pid == null) return;
        android.os.Process.killProcess(pid);
        for (int i = 0; i < 30 && gameProcess(c) != null; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
    }

    private static Integer gameProcess(Context c) {
        android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
        if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo p : ps) if (p.processName.endsWith(":game")) return p.pid;
        return null;
    }

    /**
     * The game options Banana Native shows on its own Game settings page, for Hokkaido's Game properties. They take
     * effect at the game's next start. Soft textures is the game's Advanced row DM_VK_TEX_SOFT.
     */
    public static final int OPTION_FULL_VEGETATION = 0, OPTION_RAM_SAVER = 1, OPTION_SOFT_TEXTURES = 2, OPTION_RAM_SAVER_STRONG = 3;

    /** Whether the game has the option: full vegetation only where its profile has a vegetation detail */
    public static boolean hasOption(String titleId, int option) {
        Profile p = Profile.of(titleId);
        return p != null && (option != OPTION_FULL_VEGETATION || p.vegetationDetail);
    }

    public static boolean option(Context c, String titleId, int option) {
        switch (option) {
            case OPTION_FULL_VEGETATION: return Prefs.fullVegetation(c, titleId);
            case OPTION_RAM_SAVER: return Prefs.ramSaver(c, titleId);
            case OPTION_RAM_SAVER_STRONG: return Prefs.ramSaverStrong(c, titleId);
            default: return "1".equals(AdvancedSettings.effective(c, "DM_VK_TEX_SOFT", titleId));
        }
    }

    public static void setOption(Context c, String titleId, int option, boolean on) {
        switch (option) {
            case OPTION_FULL_VEGETATION: Prefs.fullVegetation(c, titleId, on); break;
            // Strong includes the RAM saver, as on Banana Native's Game settings page
            case OPTION_RAM_SAVER: Prefs.ramSaver(c, titleId, on); if (!on) Prefs.ramSaverStrong(c, titleId, false); break;
            case OPTION_RAM_SAVER_STRONG: Prefs.ramSaverStrong(c, titleId, on); if (on) Prefs.ramSaver(c, titleId, true); break;
            default: AdvancedSettings.set(c, "DM_VK_TEX_SOFT", titleId, on ? "1" : "0");
        }
    }

    /**
     * Banana Native's Import pack (PackUi.importRun) for Hokkaido: a pack that Banana Native's own app exported (its
     * game's Manage › Export pack…, banana-pack-&lt;title ID&gt;-&lt;version&gt;.zip) or a pack folder, checked against the game
     * in dumpPath and put into this app's packs/&lt;title ID&gt;, which the game's next start finds first. Null when it is
     * in, else why not; nothing changes then. Blocks for as long as the copy takes: call it off the main thread.
     */
    public static String importPack(Context c, File source, String titleId, String dumpPath) {
        File packs = AppStorage.packs(c), target = AppStorage.pack(c, titleId);
        try {
            String[] running = runningGame(c);
            if (running != null && running[0].equalsIgnoreCase(titleId)) {
                throw new IOException(Words.s("err_game_running_stop_first", "%1$s is running: stop it first (its in-game panel › Stop the game).", running[1].isEmpty() ? titleId : running[1]));
            }
            if (!packs.isDirectory() && !packs.mkdirs()) throw new IOException(Words.s("fusion_native_cannot_create_folder", "Cannot create the folder %1$s.", packs));
            long need = source.length();
            if (packs.getUsableSpace() < need + (256L << 20)) {
                throw new IOException(Words.s("fusion_native_pack_space", "The pack needs %1$s, and the phone has %2$s free.", SaveStore.size(need), SaveStore.size(packs.getUsableSpace())));
            }
            File remembered = Job.remembered(c);
            if (remembered != null && remembered.isFile()) {
                Job j = Job.read(remembered);
                if (SaveStore.same(j.pack(), target) && !"done".equals(j.state())) {
                    throw new IOException(Words.s("game_delete_prepare_running", "%1$s is being prepared: stop or finish that first.", j.name()));
                }
            }
            Pack p = PackZip.stage(source, packs, titleId);
            try {
                String bad = dumpPath == null ? null : PackCheck.blocking(c, p, new File(dumpPath));
                if (bad != null) throw new IOException(bad);
                PackZip.swapIn(p.dir, target);
            } finally {
                if (p.dir.exists()) SaveStore.deleteTree(p.dir);
            }
            Folders.pickedPack(c, titleId, null);   // a folder picked in Banana Native's own launcher would win over the import
            return null;
        } catch (IOException | RuntimeException e) {
            String m = e.getMessage();
            return m == null || m.isEmpty() ? e.toString() : m;
        }
    }

    /** Hokkaido's theme for the game screen's menu (HokkaidoInGame.Screen): its style resources, and its dark mode. */
    static final String EXTRA_THEMES = "hokkaido_themes", EXTRA_NIGHT = "hokkaido_night";

    /**
     * Starts a ready game (Plan.READY) in Banana Native's game process, as FrontendLaunch.play does, with Hokkaido's
     * theme (its style resources in order, and its dark mode) for the in-game menu there. Not as a frontend's game: the
     * game screen joins Hokkaido's own task, so its end shows Hokkaido (a frontend's would send that task back).
     */
    public static void play(Context c, Plan plan, int[] themes, boolean night) {
        Game g = plan.game;
        c.startActivity(new Intent(c, GameActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("title_id", g.titleId).putExtra("name", g.name).putExtra("game", g.gameDir)
                .putExtra("pack", g.packDir)
                .putExtra(EXTRA_THEMES, themes).putExtra(EXTRA_NIGHT, night));
    }

    /** A job's progress, for Hokkaido's progress screen. */
    public static final class Progress {
        public final String name;
        public final String state;
        public final String text;
        public final double overall;
        public final boolean alive;
        public final boolean ready;
        public final String error;

        Progress(Job j, boolean alive, boolean ready) {
            this.name = j.name();
            this.state = j.state();
            this.text = j.statusText(alive);
            this.overall = j.overall();
            this.alive = alive;
            this.ready = ready;
            this.error = "failed".equals(j.state()) ? j.errorDetail() : "";
        }
    }

    public interface Listener {
        void onProgress(Progress progress);
    }

    /**
     * Follows the remembered job: PackService's state messages while it runs (Ondevice.Panel), else job.json once a
     * second. A finished job's game goes into Banana Native's game list (Ondevice.Panel.addGame), so it plays next time.
     */
    public static final class Watch implements ServiceConnection {
        private final Context context;
        private final Listener listener;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Messenger client = new Messenger(new Handler(Looper.getMainLooper(), this::onMessage));
        private Messenger service;
        private boolean bound;
        private boolean alive;
        private boolean stopped;
        private Job job;

        public Watch(Context context, Listener listener) {
            this.context = context.getApplicationContext();
            this.listener = listener;
        }

        public void start() {
            stopped = false;
            bound = context.bindService(new Intent(context, PackService.class), this, 0);
            tick();
        }

        public void stop() {
            stopped = true;
            handler.removeCallbacksAndMessages(null);
            if (service != null) {
                Message m = Message.obtain(null, PackService.MSG_UNREGISTER);
                m.replyTo = client;
                try { service.send(m); } catch (RemoteException e) { /* the service is gone */ }
            }
            if (bound) {
                context.unbindService(this);
                bound = false;
            }
            service = null;
        }

        private void tick() {
            if (stopped) return;
            if (service == null) {
                File f = Job.remembered(context);
                try {
                    if (f != null && f.isFile()) job = Job.read(f);
                } catch (IOException e) {
                    // being renamed over: keep the last one
                }
                if (!bound) bound = context.bindService(new Intent(context, PackService.class), this, 0);
                report();
            }
            handler.postDelayed(this::tick, 1000);
        }

        private boolean onMessage(Message m) {
            if (m.what != PackService.MSG_STATE) return false;
            Bundle d = m.getData();
            Job j = Job.of(new File(d.getString("file", "")), d.getString("job", "{}"));
            if (j != null) job = j;
            alive = d.getBoolean("alive");
            report();
            return true;
        }

        private void report() {
            if (job == null || stopped) return;
            boolean ready = ready(context, job);
            if (ready && !alive) addGame(context, job);
            listener.onProgress(new Progress(job, alive, ready));
        }

        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = new Messenger(b);
            Message m = Message.obtain(null, PackService.MSG_REGISTER);
            m.replyTo = client;
            try { service.send(m); } catch (RemoteException e) { service = null; }
        }

        @Override public void onServiceDisconnected(ComponentName n) {
            service = null;
            alive = false;
            report();
        }
    }

    /** Whether PackService's process runs (a job that says running without it was stopped). */
    private static final class PackServiceWatch {
        static boolean alive(Context c) {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
            if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo p : ps) if (p.processName.endsWith(":pack")) return true;
            return false;
        }
    }

    /** A completed code-only job is ready when its shaders can be built during play or an older library plays. */
    private static boolean ready(Context c, Job j) {
        if (!"done".equals(j.state()) || j.apkChanged()) return false;
        try {
            Pack p = Pack.read(j.pack());
            return !p.simulated && p.unfinishedModule() == null && p.titleId.equals(j.titleId())
                    && shadersPlayable(c, j.pack(), j.titleId());
        } catch (IOException e) {
            return false;
        }
    }

    /** One shader readiness rule for the progress screen and the next start (FrontendLaunch.ready). */
    private static boolean shadersPlayable(Context c, File packDir, String id) {
        return Pack.libraryBlock(packDir, id, Prefs.layerEnv(c, id)) == null
                || Pack.playableLibrary(packDir, id) != null || Pack.legacyLibrary(packDir, id) != null;
    }

    /** The finished job's game into the list with its pack (Ondevice.Panel.addGame). */
    private static void addGame(Context c, Job j) {
        for (Game g : Game.load(c)) {
            if (GameList.samePath(g.gameDir, j.dump().getPath()) && GameList.samePath(g.packDir, j.pack().getPath())) return;
        }
        Pack p;
        try { p = Pack.read(j.pack()); } catch (IOException e) { return; }
        if (p.simulated) return;
        Game g = new Game(j.titleId(), p.name.isEmpty() ? j.name() : p.name, j.dump().getPath(), j.pack().getPath());
        Game.put(c, g);
        String picked = Folders.pickedPack(c, g.titleId);
        if (picked != null && !picked.equals(g.packDir)) Folders.pickedPack(c, g.titleId, g.packDir);
    }

    /** The card and pack a start of this game uses (FrontendLaunch.ready) */
    private static final class Found {
        final Game card;
        final File packDir;
        final Pack pack;

        Found(Game card, File packDir, Pack pack) {
            this.card = card;
            this.packDir = packDir;
            this.pack = pack;
        }
    }

    /** This game's card (null when it has none) and the pack that fits it, null when no pack does */
    private static Found found(Context c, File game, String id) {
        List<Game> list = Game.load(c);
        List<Integer> same = GameList.matches(list, id, game.getPath(), Game.key(game.getPath(), id), Game::key);
        Game card = same.isEmpty() ? null : list.get(same.get(0));
        File packDir = card != null ? new File(card.packDir) : Folders.packFor(c, game, id);
        if (packDir == null) return null;
        try {
            Pack p = Pack.read(packDir);
            if (!p.titleId.equalsIgnoreCase(id)) return null;
            if (Nup.isNup(game) ? p.romfsPath(game) == null : p.romfs(game) == null) return null;
            return new Found(card, packDir, p);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Why a finished pack starts only after a warning (MainActivity's Play anyway: a build this app does not support, or another version's RomFS), null when it
     * starts without one or not at all
     */
    private static BuildCheck.Result untested(Context c, Pack p, File game) {
        if (p.unfinishedModule() != null || PackCheck.rewriting(c, p.dir, game, p.titleId) != null) return null;
        BuildCheck.Result b = BuildCheck.build(p, game);
        return b.kind == BuildCheck.Kind.UNSUPPORTED || b.kind == BuildCheck.Kind.ROMFS ? b : null;
    }

    /** FrontendLaunch.ready: the game's card when it can start now, else null. anyway: an untested pack starts too (the player said Try anyway) */
    private static Game ready(Context c, File game, String id, boolean anyway) {
        Found f = SaveUi.busy(id) ? null : found(c, game, id);
        if (f == null) return null;
        if (PackCheck.blocking(c, f.pack, game) != null && !(anyway && untested(c, f.pack, game) != null)) return null;
        // A missing or older shader library blocks only a game the shader layer does not cover (FrontendLaunch.ready), and, as on Banana
        // Native's own Home (MainActivity), not one whose older library still plays: its shader update is optional (Build all shaders now).
        // Without this, every new shader chain sent the game to a Prepare that translated all its shaders again.
        if (!shadersPlayable(c, f.packDir, id)) return null;
        if (f.card != null && GameList.samePath(f.card.gameDir, game.getPath())) return f.card;
        // A card of the same title and build at another path (another copy of the dump) is moved to the dump Hokkaido starts:
        // the game would otherwise read the other copy's files (an incomplete copy: a black screen)
        String name = f.card != null ? f.card.name : f.pack.name.isEmpty() ? id : f.pack.name;
        return Game.put(c, new Game(id, name, game.getPath(), f.packDir.getPath()));
    }
}
