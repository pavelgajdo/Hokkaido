package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The automatic save backup before an in-app update installs (Updater, after the download is checked; Settings › Updates › Back up saves before updating, on by
 * default). Players who go back to an older version uninstall the app, which deletes app storage and the saves in it: this keeps a copy outside it first. The
 * decisions are UpdateBackup's (no Android, JVM-tested); here the saves are listed, the player is asked, and the work runs off the main thread with a progress dialog.
 * <ul>
 * <li>The saves: every game in the list, its save folder (Folders.save) for every user (Users.saveDir), as the game's Saves tab backs up one of them (SaveUi).</li>
 * <li>A save whose backup cannot go outside app storage (no All files access, or a game copied into the app: its backups folder is in app storage) is named in a
 *     dialog first: Open settings (All files access; the update waits and asks again on return), Update without backup, Cancel.</li>
 * <li>A failure names the game and the reason: Update anyway, Cancel. Otherwise a toast says how many games were backed up and the install goes on.</li>
 * </ul>
 */
final class UpdateBackupUi {
    private UpdateBackupUi() {}

    private static final String TAG = "nxupdate";
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static boolean gone(Activity a) { return a.isFinishing() || a.isDestroyed(); }

    /** Every save the launcher knows: each game's save folder for each user, once each */
    static List<UpdateBackup.Item> items(Context c) {
        List<UpdateBackup.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean access = Environment.isExternalStorageManager();
        List<Users.User> users = Users.list(c);
        for (Game g : Game.load(c)) {
            File save = Folders.save(c, g);
            if (save == null) continue;
            File backups = SaveUi.backups(g);
            boolean writable = access && SaveZip.writable(backups);
            for (Users.User u : users) {
                File s = Users.saveDir(save, u.uid);
                if (!seen.add(SaveStore.canon(s))) continue;
                String tag = u.uid.equals(Users.FIRST) ? "" : u.uid.substring(0, 8);
                out.add(new UpdateBackup.Item(g.titleId, g.name, tag, s, save, backups, writable));
            }
        }
        return out;
    }

    private static String label(UpdateBackup.Item it) { return it.user.isEmpty() ? it.name : Tx.s(R.string.settings_update_game_user, it.name, it.user); }

    /** Backs up the saves before install runs (when the setting is on); askLater: Open settings was picked, the update waits for the return; cancel: the update is dropped */
    static void beforeInstall(Activity a, Runnable install, Runnable askLater, Runnable cancel) {
        if (!Prefs.backupBeforeUpdate(a)) { install.run(); return; }
        Context app = a.getApplicationContext();
        AlertDialog wait = new PadFocus.Builder(a).setMessage(Tx.s(R.string.settings_update_backing_up)).setCancelable(false).show();
        new Thread(() -> {   // listing the games' folders reads the storage
            List<UpdateBackup.Item> items = items(app);
            List<UpdateBackup.Result> blocked = UpdateBackup.blocked(items, AppStorage.root(app));
            UI.post(() -> {
                if (wait.isShowing()) wait.dismiss();
                if (gone(a)) return;
                if (blocked.isEmpty()) { run(a, items, install, cancel); return; }
                StringBuilder list = new StringBuilder();
                for (UpdateBackup.Result r : blocked)
                    list.append(list.length() > 0 ? "\n" : "").append(Tx.s(UpdateBackup.IN_APP.equals(r.detail) ? R.string.settings_update_reason_in_app : R.string.settings_update_reason_no_access, label(r.item)));
                for (UpdateBackup.Result r : blocked) Log.i(TAG, "save backup before the update refused (" + r.detail + "): " + r.item.save);
                PadFocus.Builder b = new PadFocus.Builder(a);
                b.setTitle(Tx.s(R.string.settings_update_no_backup_title)).setMessage(Tx.s(R.string.settings_update_no_backup_message, list.toString()))
                        .setNegativeButton(Tx.s(R.string.common_cancel), (d, w) -> cancel.run())
                        .setNeutralButton(Tx.s(R.string.settings_update_without_backup), (d, w) -> run(a, items, install, cancel))   // the other saves are still backed up
                        .setCancelable(false);
                boolean access = Environment.isExternalStorageManager();
                if (!access) b.setPositiveButton(Tx.s(R.string.common_open_settings), (d, w) -> { askLater.run(); openAccessSettings(a); });
                b.show();
            });
        }, "nx-update-saves").start();
    }

    /** All files access for this app (as Home's access card opens it) */
    private static void openAccessSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.getPackageName())));
        } catch (RuntimeException e) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /** The backups, off the main thread with a progress dialog, then install (or the failure's question) */
    private static void run(Activity a, List<UpdateBackup.Item> items, Runnable install, Runnable cancel) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView st = Ui.body(a, Tx.s(R.string.settings_update_backing_up));
        box.addView(st);
        ProgressBar bar = Ui.progress(a);
        bar.setMax(Math.max(1, items.size()));
        Ui.add(box, bar, 12);
        AlertDialog d = Ui.sheet(a, Tx.s(R.string.settings_update_backing_up), box).setCancelable(false).show();
        Context app = a.getApplicationContext();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        new Thread(() -> {
            List<UpdateBackup.Result> res = UpdateBackup.run(items, AppStorage.root(app), stamp, it -> SaveUi.busy(it.titleId), (k, n, it) -> UI.post(() -> {
                bar.setProgress(k);
                if (it != null) st.setText(Tx.s(R.string.settings_update_backing_up_game, label(it), k + 1, n));
            }));
            for (UpdateBackup.Result r : res) Log.i(TAG, "save backup before the update: " + r.outcome + " " + r.item.save + (r.detail != null ? " (" + r.detail + ")" : ""));
            UI.post(() -> {
                if (d.isShowing()) d.dismiss();
                if (gone(a)) return;
                done(a, res, install, cancel);
            });
        }, "nx-update-saves").start();
    }

    private static void done(Activity a, List<UpdateBackup.Result> res, Runnable install, Runnable cancel) {
        List<UpdateBackup.Result> failed = UpdateBackup.with(res, UpdateBackup.Outcome.FAILED), running = UpdateBackup.with(res, UpdateBackup.Outcome.IN_USE);
        StringBuilder skipped = new StringBuilder();
        for (UpdateBackup.Result r : running) skipped.append(skipped.length() > 0 ? ", " : "").append(label(r.item));
        String runLine = running.isEmpty() ? "" : Tx.s(R.string.settings_update_skipped_running, skipped.toString());
        if (!failed.isEmpty()) {
            StringBuilder why = new StringBuilder();
            for (UpdateBackup.Result r : failed) why.append(why.length() > 0 ? "\n" : "").append(label(r.item)).append(": ").append(r.detail);
            if (!runLine.isEmpty()) why.append("\n").append(runLine);
            Ui.explain(a, Tx.s(R.string.settings_update_backup_failed_title), Tx.s(R.string.settings_update_backup_failed_message), why.toString())
                    .setPositiveButton(Tx.s(R.string.settings_update_anyway), (x, w) -> install.run())
                    .setNegativeButton(Tx.s(R.string.common_cancel), (x, w) -> cancel.run())
                    .setCancelable(false).show();
            return;
        }
        int n = UpdateBackup.gamesBackedUp(res);
        String msg = n > 0 ? Tx.n(R.plurals.settings_update_saves_backed_up, n, n)
                : !UpdateBackup.with(res, UpdateBackup.Outcome.UNCHANGED).isEmpty() ? Tx.s(R.string.settings_update_saves_unchanged) : "";
        if (!runLine.isEmpty()) msg = msg.isEmpty() ? runLine : msg + "\n" + runLine;
        if (!msg.isEmpty()) Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
        install.run();
    }
}
