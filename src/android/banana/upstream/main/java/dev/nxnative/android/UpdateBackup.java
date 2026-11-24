package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The automatic save backup before an in-app update installs (Updater, UpdateBackupUi; Settings › Updates › Back up saves before updating). No Android classes:
 * the JVM test (android/app/src/jvmtest's UpdateBackupJvmTest, run by dm/tools/tests/test_android_updatebackup.py) runs it on the Mac.
 * <p>
 * The saves are the ones the game card's Saves tab works on (SaveUi): for each game, its save folder (Folders.save) for each user (Users.saveDir: the folder itself
 * for the first user, &lt;save&gt;_user_&lt;uid&gt; beside it for the others), each with its device save (SaveStore.parts). Each goes through SaveStore.backup into the
 * game's shared backups folder (Prefs.backupsDir: &lt;Switch2&gt;/save-backups/&lt;id&gt;), named &lt;stamp&gt;-before-update (and -user-&lt;uid prefix&gt; for other users).
 * A backup is only worth it outside app storage (an uninstall deletes app storage), so a backups folder inside it, or one the app may not write (no All files access),
 * is refused. A save with no file is skipped, a save whose game runs (its lock is held) is skipped, and a save that holds what its newest backup holds (the backup's
 * manifest against a scan of the save; the backup's own files checked against its manifest too) is skipped, so repeated updates do not pile up copies. Nothing here
 * deletes or overwrites a backup or a save: SaveStore.backup only reads the save and writes a new folder under a free name.
 */
final class UpdateBackup {
    private UpdateBackup() {}

    static final String BEFORE_UPDATE = "-before-update";
    /** Why a save cannot be backed up outside app storage */
    static final String IN_APP = "in-app", NO_ACCESS = "no-access";

    /** One save the launcher knows: a game's save folder for one user */
    static final class Item {
        final String titleId, name, user;   // user: "" for the first user, else the start of its uid (the backup's name says it)
        final File save, lock, backups;   // the user's save; the game's own save folder, whose lock a running game holds; the shared backups folder
        final boolean writable;   // the app may write the backups folder (All files access, and the folder or the one above it is writable)

        Item(String titleId, String name, String user, File save, File lock, File backups, boolean writable) {
            this.titleId = titleId; this.name = name; this.user = user == null ? "" : user;
            this.save = save; this.lock = lock; this.backups = backups; this.writable = writable;
        }
    }

    enum Outcome { BACKED_UP, UNCHANGED, EMPTY, IN_USE, REFUSED, FAILED }

    static final class Result {
        final Item item;
        final Outcome outcome;
        final String detail;   // FAILED: the reason; REFUSED: IN_APP or NO_ACCESS; BACKED_UP: the backup's folder; UNCHANGED: the backup that holds it
        Result(Item item, Outcome outcome, String detail) { this.item = item; this.outcome = outcome; this.detail = detail; }
    }

    interface Progress { void at(int done, int total, Item item); }
    interface Busy { boolean busy(Item item); }

    /** Whether the save (main or device) holds no file */
    static boolean empty(File save) {
        for (File p : SaveStore.parts(save).values()) if (SaveStore.hasFiles(p)) return false;
        return true;
    }

    /** Why the item's backup would not land outside app storage (IN_APP, NO_ACCESS), or null when it would */
    static String refuse(Item it, File appRoot) {
        if (CopyPlan.inApp(appRoot, it.backups.getAbsolutePath()) || SaveStore.inside(it.backups, appRoot)) return IN_APP;
        if (!it.writable) return NO_ACCESS;
        return null;
    }

    /** The saves with something in them whose backup cannot be made outside app storage: the player is asked before the update */
    static List<Result> blocked(List<Item> items, File appRoot) {
        List<Result> out = new ArrayList<>();
        for (Item it : items) {
            if (empty(it.save)) continue;
            String why = refuse(it, appRoot);
            if (why != null) out.add(new Result(it, Outcome.REFUSED, why));
        }
        return out;
    }

    /**
     * The newest backup of this save in backups (by its manifest's source) when it holds exactly what the save holds now and its own files still match its manifest;
     * else null (no backup of it, a damaged one, a pending restore, or the save changed). Only reads
     */
    static SaveStore.Backup unchangedSince(File save, File backups) {
        if (SaveStore.pending(save)) return null;
        Map<String, File> parts = SaveStore.parts(save);
        if (!save.isDirectory()) parts.remove(SaveStore.MAIN);   // as SaveStore.backup does: a backup of only the device save
        for (SaveStore.Backup b : SaveStore.list(backups)) {
            if (b.source.isEmpty() || !(b.source.equals(save.getAbsolutePath()) || SaveStore.same(new File(b.source), save))) continue;
            try {
                SaveStore.Manifest m = SaveStore.readManifest(new File(backups, b.name + SaveStore.MANIFEST));
                if (m.diff(SaveStore.scan(b.dir)) != null) return null;   // the newest backup is damaged: a new one is made
                return m.diff(SaveStore.scanParts(parts)) == null ? b : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** The backup's name: &lt;stamp&gt;-before-update, -user-&lt;uid prefix&gt; for a user other than the first (SaveStore.freeName adds -2, -3 when taken) */
    static String name(String stamp, Item it) { return stamp + BEFORE_UPDATE + (it.user.isEmpty() ? "" : "-user-" + it.user); }

    /** Backs up every save that needs it (see the class comment), one after the other; the result of each, in order. Refused saves are skipped (the player was asked) */
    static List<Result> run(List<Item> items, File appRoot, String stamp, Busy busy, Progress progress) {
        List<Result> out = new ArrayList<>();
        int k = 0;
        for (Item it : items) {
            if (progress != null) progress.at(k++, items.size(), it);
            out.add(one(it, appRoot, stamp, busy));
        }
        if (progress != null) progress.at(items.size(), items.size(), null);
        return out;
    }

    static Result one(Item it, File appRoot, String stamp, Busy busy) {
        if (empty(it.save)) return new Result(it, Outcome.EMPTY, null);
        String why = refuse(it, appRoot);
        if (why != null) return new Result(it, Outcome.REFUSED, why);
        if ((busy != null && busy.busy(it)) || SaveStore.inUse(it.lock) || SaveStore.inUse(it.save)) return new Result(it, Outcome.IN_USE, null);
        SaveStore.Backup same = unchangedSince(it.save, it.backups);
        if (same != null) return new Result(it, Outcome.UNCHANGED, same.dir.getPath());
        try {
            SaveStore.Backup b = SaveStore.backup(it.save, it.backups, name(stamp, it));
            return new Result(it, Outcome.BACKED_UP, b.dir.getPath());
        } catch (IOException | RuntimeException e) {
            return new Result(it, Outcome.FAILED, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** How many games had a save backed up now */
    static int gamesBackedUp(List<Result> l) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Result r : l) if (r.outcome == Outcome.BACKED_UP) ids.add(r.item.titleId);
        return ids.size();
    }

    static List<Result> with(List<Result> l, Outcome o) {
        List<Result> out = new ArrayList<>();
        for (Result r : l) if (r.outcome == o) out.add(r);
        return out;
    }
}
