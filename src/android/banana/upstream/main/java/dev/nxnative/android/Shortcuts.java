package dev.nxnative.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.widget.Toast;

import java.util.Collections;
import java.util.Locale;

/**
 * Home screen shortcuts that start one game. A shortcut opens {@link FrontendLaunch} with the game's title ID, the path emulator frontends take (FRONTENDS.md):
 * a game that is ready starts at once, anything that needs the user (no pack, shaders to make, All files access) goes to Home with the game. The shortcut
 * holds only the title ID, so it follows the game's card when the game or pack is moved; if the card is gone the launch says so.
 * <p>
 * The icon is the one the card shows: the player's own cover (Covers), else the game's own icon (GameMeta: a dump's control data, a .nup's meta block), as an adaptive icon
 * so the launcher's shape applies (#368). A game with neither gets the card's plain tile (Ui.tile): the name's first letter on banana yellow.
 */
final class Shortcuts {
    private Shortcuts() { }

    static String id(Game g) { return "game-" + g.titleId.toUpperCase(Locale.ROOT); }

    /** The intent a shortcut for the game runs (a pinned shortcut needs an action) */
    static Intent intent(Context c, Game g) {
        return new Intent(Intent.ACTION_MAIN).setClassName(c, FrontendLaunch.class.getName()).putExtra("title_id", g.titleId.toUpperCase(Locale.ROOT));
    }

    static boolean supported(Context c) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        return sm != null && sm.isRequestPinShortcutSupported();
    }

    /** How long the pin waits for the game's own icon to be read (a .nup's meta block is read from the file) before it takes the letter tile */
    private static final long ICON_WAIT_MS = 1500;

    /** Asks the launcher to pin the game's shortcut (the launcher shows its own confirmation); a shortcut already pinned is updated in place. The icon is the cover or the game's icon */
    static void pin(Context c, Game g) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(c, Tx.s(R.string.settings_this_launcher_cannot_pin_shortcuts), Toast.LENGTH_LONG).show();
            return;
        }
        Bitmap cover = Covers.bitmap(c, g);
        if (cover != null) { request(c, sm, g, icon(cover)); return; }
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.postDelayed(() -> { if (done.compareAndSet(false, true)) request(c, sm, g, icon(c, g.name)); }, ICON_WAIT_MS);   // no icon in the game's data (the callback never comes): the letter
        GameMeta.loadControl(new java.io.File(g.gameDir), Edition.control(c, g), m -> {
            if (done.compareAndSet(false, true)) request(c, sm, g, m.icon != null ? icon(m.icon) : icon(c, g.name));
        });
    }

    private static void request(Context c, ShortcutManager sm, Game g, Icon icon) {
        ShortcutInfo info = new ShortcutInfo.Builder(c, id(g))
                .setShortLabel(g.name.length() > 25 ? g.name.substring(0, 24) + "…" : g.name)
                .setLongLabel(g.name)
                .setIcon(icon)
                .setIntent(intent(c, g))
                .build();
        try {
            if (!sm.requestPinShortcut(info, null)) Toast.makeText(c, Tx.s(R.string.settings_the_launcher_did_not_accept), Toast.LENGTH_LONG).show();
            else sm.updateShortcuts(Collections.singletonList(info));   // already pinned: a renamed game's label and a new cover follow
        } catch (RuntimeException e) {
            android.util.Log.w("nxnative", "Shortcuts: pin " + g.titleId + " failed: " + e);
            Toast.makeText(c, Tx.s(R.string.settings_could_not_add_shortcut, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** A picture as an adaptive icon: centre-cropped to the full 108 dp square (the launcher's mask keeps the middle two thirds, so the edges of the art may be cut) */
    static Icon icon(Bitmap art) {
        int size = 432, side = Math.min(art.getWidth(), art.getHeight());
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawBitmap(art, new Rect((art.getWidth() - side) / 2, (art.getHeight() - side) / 2, (art.getWidth() + side) / 2, (art.getHeight() + side) / 2),
                new Rect(0, 0, size, size), new Paint(Paint.FILTER_BITMAP_FLAG));
        return Icon.createWithAdaptiveBitmap(b);
    }

    /** The card's tile as an adaptive icon: the letter inside the 66 dp circle every launcher shape keeps, on a full-bleed 108 dp yellow square */
    static Icon icon(Context c, String name) {
        int size = 432;   // 108 dp at 4x
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(c.getColor(R.color.bn_banana));
        String n = name == null ? "" : name.trim();
        String letter = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(c.getColor(R.color.bn_on_banana));
        p.setTypeface(Typeface.create(Typeface.DEFAULT, 800, false));
        p.setTextSize(size * 0.36f);
        p.setTextAlign(Paint.Align.CENTER);
        Rect r = new Rect();
        p.getTextBounds(letter, 0, letter.length(), r);
        cv.drawText(letter, size / 2f, size / 2f - r.exactCenterY(), p);
        return Icon.createWithAdaptiveBitmap(b);
    }
}
