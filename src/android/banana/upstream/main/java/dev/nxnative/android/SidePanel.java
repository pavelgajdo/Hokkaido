package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The in-game menu: one drawer from the left over the running game, which goes on behind it, as Eden's in-game drawer. Its left column is a pinned side menu (buildMenu): the
 * game's name and GPU driver over Pause (Resume while the game is paused), the four sections (TAB_NAMES: Performance, Display, Controls, General), and Stop. Picking a section
 * shows its rows in a second column to its right, inside the same drawer (showPage); the section stays marked in the menu. On a screen too narrow for both columns (a phone
 * held upright: stacked) the section takes the menu's place. There is no tab bar: the side menu is the only way between sections (and a controller's L / R).
 * The sections hold the frame rate cap (Native.fpsCapSet), frame generation (Lsfg.rows, Native.frameGenSet), the Upscaler and the dynamic resolution (GameUpscaler.rows, DrsSlider),
 * Game resolution (resolutionRow, in Display: live for a game with graphics_scale rows), the picture (Fit / Stretch, its size and position: GameActivity.picture,
 * TouchControls.positionRows), the overlay's items (Overlay.rows), which gamepad is Player 1 (PadOrder), the on-screen controls' rows (TouchControls.controlRows), the panel's own
 * size and text size (PanelLook), and a shortcut to this game's Button mapping (which applies at the next start). Every other change applies at once, without going back to the
 * launcher, and is kept for the next start.
 * Back (the phone's button or its edge swipe) and a controller (PadPanel: Home, or − and + held; Settings › Controller: in-game panel) open the menu (openMenu). A long press on
 * the on-screen controls' show / hide button and a swipe in from the right edge open the drawer on the section that showed last (open). Back and the controller's B go one level
 * up: from a section to the menu (showMenu), from the menu back to the game (close); a tap beside the drawer closes it at once. With a controller (padKey, padMotion) the D-pad
 * and the left stick move (held: repeated): in the menu up / down select a row and A (or right, on a section) picks it; in a section they move the focus, and moving never
 * changes a setting: A presses (on a slider it starts adjusting: left / right change it, A keeps, B undoes: adjustEnd), L / R switch sections. A and B are the buttons that are
 * the Switch's A and B in this game (PadPanel.confirmBack, the game's Button mapping). The focused row has a yellow frame (focusMoved): the theme's own focus highlight is hard
 * to see on the panel.
 */
final class SidePanel extends FrameLayout {
    private final Activity act;
    private final LinearLayout box;
    // The drawer: one card at the left holding the menu's column (menuScroll) and the section's (sheet: a divider and the section's rows, scroll / box)
    private final LinearLayout drawer, sheet;
    private final ScrollView scroll;
    private final Rows rows;
    private TextView fpsNow, lsfgNow, layerNow;
    private RadioGroup caps;
    // open: a section shows (sheet); menuOpen: the drawer shows (the menu, and the section beside it when open). open never holds without menuOpen
    private boolean open;
    private final LinearLayout menu;
    private final ScrollView menuScroll;
    private boolean menuOpen;
    // The menu's rows (menuRow), the section each is (-1: an action, Pause or Stop), and the one a controller has selected (-1: none, touch). The menu steps through them
    // itself (menuStep), outside Android's focus, so a controller moves in it whatever has the focus and whether or not the window is in touch mode
    private final java.util.ArrayList<View> menuRows = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> menuSection = new java.util.ArrayList<>();
    private int menuSel = -1;
    // Holds the focus while the menu has it (focusable in touch mode, no size): in touch mode the window's first D-pad press would otherwise go to leaving touch
    // mode and focusing some view (ViewRootImpl.leaveTouchMode), never reaching padKey; with a plain view focused Android lets it through
    private final View menuSink;
    // The icon of each section's row in the menu
    private static final int[] TAB_ICONS = {R.drawable.ic_speed, R.drawable.ic_display, R.drawable.ic_gamepad, R.drawable.ic_tune};
    private static final int MENU_TEXT = 0xFFE6E1E5, MENU_TEXT2 = 0xFFCAC4D0;   // Material 3's dark on-surface and on-surface-variant, as Eden's drawer
    private static final int MENU_ACTIVE = 0x3DFFD54F;   // the section that shows: a faint banana pill (a controller's selection is the full one)
    private final Runnable tick = this::refresh;

    // The sections: each a builder of its own (build); Performance's rows refresh twice a second while it shows
    static final String[] TAB_NAMES = {"Performance", "Display", "Controls", "General"};
    private static final int TAB_PERF = 0, TAB_DISPLAY = 1, TAB_CONTROLS = 2, TAB_GENERAL = 3;
    private int tab = TAB_PERF;
    private static final int FOCUS = 0xFFFFD54F;   // a controller's selection in the menu and the focus frame: a banana yellow, dark text on it
    private static final int FRAME_RADIUS_DP = 14;   // the focus frame's corners, and the press highlight's of a row (rowRipple): one shape

    // A controller: its confirm and back keys (padButtons), the held direction and its repeat
    private int confirm = KeyEvent.KEYCODE_BUTTON_B, back = KeyEvent.KEYCODE_BUTTON_A;
    private int heldDir = PadPanel.NONE, stickHeld = PadPanel.NONE, repeats;
    private final Runnable repeat = this::repeatTick;
    private View lit;   // the view with the focus frame
    /** GameActivity: the game's held buttons released when the drawer opens (releaseGameInput) */
    Runnable onOpen;
    /** Hokkaido owns the visible menu and page navigation; this panel still builds the settings rows. */
    private final HokkaidoInGame.Menu hk;

    /** The rows GameActivity adds from its on-screen controls (TouchControls) */
    interface Rows {
        void controls(LinearLayout box);   // TouchControls.controlRows
        void position(LinearLayout box);   // TouchControls.positionRows: the picture's position on this screen
    }

    // The ids of the choices a rebuild gives the focus back to (restyle)
    private static final int ID_SIZE = 5000, ID_TEXT = 5100, ID_FIT = 5200, ID_MAP = 5300, ID_ONE = 5400, ID_LABEL = 5450, ID_RES = 5500, ID_RES_PRESET = 5590;

    SidePanel(Activity a, Rows rows) {
        super(a);
        act = a;
        this.rows = rows;
        float dp = getResources().getDisplayMetrics().density;
        View scrim = new View(a);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        drawer = new LinearLayout(a);
        drawer.setOrientation(LinearLayout.HORIZONTAL);
        drawer.setClickable(true);   // a tap on the drawer's empty parts is not the scrim's
        final float rad = 24 * dp;   // the launcher's card corner: the drawer is a rounded card whose left side is off screen
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF81C1C1F);
        bg.setCornerRadii(new float[]{0, 0, rad, rad, rad, rad, 0, 0});
        drawer.setBackground(bg);
        drawer.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(-Math.round(rad), 0, v.getWidth(), v.getHeight(), rad); }
        });
        drawer.setClipToOutline(true);
        menu = new LinearLayout(a);
        menu.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(16 * dp);
        menu.setPadding(0, 0, 0, p);
        menuScroll = new ScrollView(a);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.setFocusable(false);
        menuScroll.addView(menu);
        drawer.addView(menuScroll, new LinearLayout.LayoutParams(menuWidth(), LinearLayout.LayoutParams.MATCH_PARENT));
        sheet = new LinearLayout(a);   // the section's column: a hairline against the menu, then its rows
        sheet.setOrientation(LinearLayout.HORIZONTAL);
        View rule = new View(a);
        rule.setBackgroundColor(0x1FFFFFFF);
        sheet.addView(rule, new LinearLayout.LayoutParams(Math.max(1, Math.round(dp)), LinearLayout.LayoutParams.MATCH_PARENT));
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, p, p, p);
        scroll = new ScrollView(a);
        scroll.addView(box);
        sheet.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        sheet.setVisibility(GONE);   // showPage shows it
        drawer.addView(sheet, new LinearLayout.LayoutParams(PanelLook.widthPx(Prefs.panelSize(a), dp, a.getResources().getDisplayMetrics().widthPixels), LinearLayout.LayoutParams.MATCH_PARENT));
        drawer.setVisibility(GONE);
        addView(drawer, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.START));
        menuSink = new View(a);
        menuSink.setFocusable(true);
        menuSink.setFocusableInTouchMode(true);
        menuSink.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(menuSink, new LayoutParams(1, 1));
        getViewTreeObserver().addOnGlobalFocusChangeListener(this::focusMoved);
        setVisibility(GONE);
        hk = a instanceof GameActivity ? HokkaidoInGame.menu((GameActivity) a, this) : null;
        if (hk != null) {
            removeAllViews();
            addView(hk.view(), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            setVisibility(VISIBLE);
        }
    }

    /** The controller's confirm and back keys (PadPanel.confirmBack: the buttons that are the Switch's A and B in this game) */
    void padButtons(int[] cb) { confirm = cb[0]; back = cb[1]; }

    /** The drawer shows: the game gets no pad input (GameActivity) */
    boolean isOpen() { return open || menuOpen; }
    /** Back (GameActivity.onBackPressed): one level up. A section goes back to the menu, the menu back to the game; with nothing showing the menu opens */
    void back() {
        if (hk != null) { if (isOpen()) hk.back(); else openMenu(); return; }
        if (open) showMenu(); else if (menuOpen) close(); else openMenu();
    }
    /** Opened or closed by a controller (GameActivity: Home, or − and + held): the menu, its first row selected, out of touch mode */
    void padToggle() { if (isOpen()) close(); else { openMenu(); if (hk != null) hk.focus(); else menuSelect(0); } }

    /** The drawer on the section that showed last (the on-screen controls' long press, the right edge's swipe) */
    void open() { if (hk != null) openMenu(); else showPage(tab); }

    /** The drawer slides out: back to the game */
    void close() {
        if (!isOpen()) return;
        open = menuOpen = false;
        adjusting = null;
        heldDir = stickHeld = PadPanel.NONE;
        removeCallbacks(repeat);
        removeCallbacks(tick);
        if (hk != null) { hk.close(); return; }
        drawer.animate().cancel();
        drawer.animate().translationX(-drawer.getWidth()).setDuration(150)
                .withEndAction(() -> { if (!menuOpen) { sheet.setVisibility(GONE); drawer.setVisibility(GONE); setVisibility(GONE); } }).start();
    }

    /** The menu (Back, a controller's Home): the drawer slides in with no section, or a section that showed goes back to it */
    void openMenu() {
        if (hk != null) {
            if (isOpen()) return;
            open = menuOpen = true;   // Hokkaido tracks which of its drawers shows
            if (onOpen != null) onOpen.run();
            hk.open();
            return;
        }
        if (open) { showMenu(); return; }
        if (menuOpen) return;
        slideIn();
        menuSelect(touchNav() ? -1 : 0);
    }

    /** The drawer slides in, the menu built anew (the game's name, its driver, Pause or Resume), no section yet; the menu holds the focus */
    private void slideIn() {
        if (onOpen != null) onOpen.run();
        menuOpen = true;
        heldDir = stickHeld = PadPanel.NONE;
        removeCallbacks(repeat);
        buildMenu();
        sheet.setVisibility(GONE);
        menuScroll.setVisibility(VISIBLE);
        sizes();
        setVisibility(VISIBLE);
        drawer.setVisibility(VISIBLE);
        menuScroll.scrollTo(0, 0);
        drawer.animate().cancel();
        drawer.setTranslationX(-(menuWidth() + sheetWidth()));   // the widest it gets: open() adds the section right after
        drawer.animate().translationX(0).setDuration(180).start();
        menuSink.requestFocus();
    }

    /** A section to the menu (Back, B): its column goes, the menu has the focus again with that section selected for a controller */
    private void showMenu() {
        if (!open) return;
        open = false;
        adjusting = null;
        heldDir = stickHeld = PadPanel.NONE;
        removeCallbacks(repeat);
        removeCallbacks(tick);
        sheet.setVisibility(GONE);
        menuScroll.setVisibility(VISIBLE);
        sizes();
        menuSink.requestFocus();
        menuSelect(touchNav() ? -1 : menuSection.indexOf(tab));
    }

    /**
     * Section k shows to the right of the menu (picked there, a controller's L / R, or open()): the drawer slides in first if it was closed; its rows built, scrolled
     * to its top; with a controller the focus on its first row. On a stacked screen the section takes the menu's place
     */
    private void showPage(int k) {
        boolean was = menuOpen;
        if (!menuOpen) slideIn();
        boolean same = open && k == tab;
        tab = k;
        if (!open) {
            open = true;
            sheet.setVisibility(VISIBLE);
            if (was) { sheet.setAlpha(0f); sheet.animate().alpha(1f).setDuration(150).start(); } else sheet.setAlpha(1f);
        }
        if (!same) {
            padMade.clear();   // (the old rows go)
            box.removeAllViews();
            build();
            if (padNav && isInTouchMode()) padRows(box);
            look();
            scroll.scrollTo(0, 0);
        }
        sizes();
        menuSel = -1;   // a controller works in the section now; the menu marks which one shows
        paintMenu();
        if (!touchNav()) post(() -> { if (open) focusFirst(); });
        removeCallbacks(tick);
        refresh();
    }

    /** The menu's column: 300 dp, at most 35 % of the screen but 240 dp when there is room for it (a phone held upright) */
    private int menuWidth() {
        float dp = getResources().getDisplayMetrics().density;
        int sw = getResources().getDisplayMetrics().widthPixels;
        return Math.min(Math.round(300 * dp), Math.max(Math.round(sw * 0.35f), Math.min(Math.round(240 * dp), Math.round(sw * 0.6f))));
    }
    /** Room for the scrim beside the drawer, where a tap closes it */
    private int scrimPx() { return Math.round(48 * getResources().getDisplayMetrics().density); }
    /** A screen too narrow for the menu and a section side by side (a section needs 320 dp): the section shows in the menu's place */
    private boolean stacked() {
        float dp = getResources().getDisplayMetrics().density;
        return getResources().getDisplayMetrics().widthPixels < menuWidth() + Math.round(320 * dp) + scrimPx();
    }
    /** The section's column: the panel's width (PanelLook: Settings' Size), within what the screen leaves beside the menu */
    private int sheetWidth() {
        float dp = getResources().getDisplayMetrics().density;
        int sw = getResources().getDisplayMetrics().widthPixels;
        return Math.max(0, Math.min(PanelLook.widthPx(Prefs.panelSize(act), dp, sw), sw - scrimPx() - (stacked() ? 0 : menuWidth())));
    }
    private int drawerWidth() {
        boolean menuShows = !open || !stacked();
        return (menuShows ? menuWidth() : 0) + (open ? sheetWidth() : 0);
    }
    /** The columns' widths now (the panel's size may have changed, or the screen turned), and the menu hidden while a section shows on a stacked screen */
    private void sizes() {
        menuScroll.setVisibility(open && stacked() ? GONE : VISIBLE);
        int mw = menuWidth(), sw = sheetWidth();
        if (menuScroll.getLayoutParams().width != mw) { menuScroll.getLayoutParams().width = mw; menuScroll.requestLayout(); }
        if (sheet.getLayoutParams().width != sw) { sheet.getLayoutParams().width = sw; sheet.requestLayout(); }
        sheet.getChildAt(0).setVisibility(menuScroll.getVisibility());   // the hairline only beside the menu
    }

    /**
     * The menu, laid out as Eden's drawer (a Material 3 navigation drawer): the game's name large at the top, the GPU driver and the title ID under it, then one
     * line per row with its icon: Pause (Resume while the game is paused), the four sections and Stop. Rows 56 dp high (the panel's row height when that is more),
     * text in the panel's text size
     */
    private void buildMenu() {
        menu.removeAllViews();
        menuRows.clear();
        menuSection.clear();
        menuSel = -1;
        float dp = getResources().getDisplayMetrics().density, ts = PanelLook.textScale(Prefs.panelText(act));
        int p = Math.round(24 * dp);
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(p, p, p, Math.round(16 * dp));
        TextView title = new TextView(act);
        String name = act.getIntent().getStringExtra("name");
        title.setText(name != null ? name : game());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24 * ts);
        title.setTextColor(MENU_TEXT);
        title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title);
        TextView sub = new TextView(act);
        GpuDriver.Info drv = GpuDriver.selected(act, game());   // what the launcher passed at the start (a DM_VK_DRIVER line in env.txt would win: the log's [vk] driver line says)
        sub.setText(Tx.s(R.string.game_menu_gpu_driver, drv == null ? Tx.s(R.string.game_menu_system_driver) : drv.label()) + "\n" + game());
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * ts);
        sub.setTextColor(MENU_TEXT2);
        sub.setPadding(0, Math.round(8 * dp), 0, 0);
        header.addView(sub);
        menu.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        GameActivity ga = act instanceof GameActivity ? (GameActivity) act : null;
        if (ga != null) {   // the game suspended (clock, graphics, threads, audio) until a tap on the screen, A / Start, or Resume here: GameActivity.pause
            if (ga.paused()) menuRow(R.drawable.ic_play, Tx.s(R.string.game_resume_the_game), -1, () -> { close(); ga.unpause(); });
            else menuRow(R.drawable.ic_pause, Tx.s(R.string.game_pause_the_game), -1, () -> { close(); ga.pause(); });
        }
        String[] names = Tx.arr(R.array.arr_panel_tabs);
        for (int i = 0; i < TAB_NAMES.length; i++) { int k = i; menuRow(TAB_ICONS[i], names[i], k, () -> showPage(k)); }
        menuRow(R.drawable.ic_exit, Tx.s(R.string.game_stop_the_game), -1, this::stopDialog);
        paintMenu();
    }

    /** One menu row: its icon and name on one line, a pill behind it (paintMenu), a ripple on a tap. Not focusable: menuStep moves. section: the section it shows, or -1 */
    private void menuRow(int icon, String label, int section, Runnable click) {
        float dp = getResources().getDisplayMetrics().density, ts = PanelLook.textScale(Prefs.panelText(act));
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Math.round(16 * dp), 0, Math.round(24 * dp), 0);
        r.setFocusable(false);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable(), mask = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(999 * dp);
        pill.setColor(0);
        mask.setCornerRadius(999 * dp);
        mask.setColor(Color.WHITE);
        r.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), pill, mask));
        r.setTag(pill);
        android.widget.ImageView iv = new android.widget.ImageView(act);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(MENU_TEXT2));
        int is = Math.round(24 * dp);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(is, is);
        il.setMarginEnd(Math.round(12 * dp));
        r.addView(iv, il);
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15 * ts);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(MENU_TEXT);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.setContentDescription(label);
        r.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(Math.round(56 * dp), PanelLook.rowPx(Prefs.panelSize(act), dp)));
        lp.setMarginStart(Math.round(12 * dp));
        lp.setMarginEnd(Math.round(12 * dp));
        menu.addView(r, lp);
        menuRows.add(r);
        menuSection.add(section);
    }

    /** The row a controller has selected (-1: none): the banana pill; kept in sight */
    private void menuSelect(int i) {
        menuSel = i < 0 || menuRows.isEmpty() ? -1 : Math.min(i, menuRows.size() - 1);
        paintMenu();
        if (menuSel >= 0) {
            View r = menuRows.get(menuSel);
            menuScroll.post(() -> {
                int top = r.getTop(), bottom = r.getBottom(), y = menuScroll.getScrollY(), h = menuScroll.getHeight();
                if (top < y) menuScroll.smoothScrollTo(0, menuSel == 0 ? 0 : top);
                else if (bottom > y + h) menuScroll.smoothScrollTo(0, bottom - h);
            });
        }
    }

    /** Each menu row's look: a controller's selection a banana pill with dark text and icon; the section that shows a faint banana pill with banana text; the rest plain */
    private void paintMenu() {
        for (int k = 0; k < menuRows.size(); k++) {
            LinearLayout r = (LinearLayout) menuRows.get(k);
            boolean sel = k == menuSel, shows = open && menuSection.get(k) == tab;
            ((android.graphics.drawable.GradientDrawable) r.getTag()).setColor(sel ? FOCUS : shows ? MENU_ACTIVE : 0);
            int fg = sel ? 0xFF000000 : shows ? FOCUS : 0;
            ((android.widget.ImageView) r.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(fg != 0 ? fg : MENU_TEXT2));
            ((TextView) r.getChildAt(1)).setTextColor(fg != 0 ? fg : MENU_TEXT);
            r.setSelected(sel || shows);
            String label = ((TextView) r.getChildAt(1)).getText().toString();
            r.setContentDescription(shows ? label + Tx.s(R.string.game_selected) : label);
        }
    }

    /** Up / down in the menu: the next row that way, stopping at the ends; the first press with none selected selects the first */
    private void menuStep(int delta) {
        menuSelect(menuSel < 0 ? 0 : Math.max(0, Math.min(menuRows.size() - 1, menuSel + delta)));
    }

    /** Stop (the menu's): asks first, the safe choice focused */
    private void stopDialog() {
        padDialog(new AlertDialog.Builder(act).setTitle(Tx.s(R.string.game_stop_the_game_2)).setMessage(Tx.s(R.string.game_the_game_ends_unsaved_progress))
                .setPositiveButton(Tx.s(R.string.common_stop), (d, w) -> {
                    if (act instanceof GameActivity) { ((GameActivity) act).stop(); return; }   // its screen and task go, and the process with them (GameActivity.endProcess)
                    Native.note("stopped by the user"); ExitWatch.stopped(); android.os.Process.killProcess(android.os.Process.myPid());
                })
                .setNegativeButton(Tx.s(R.string.game_keep_running), null).show());
    }

    /**
     * A controller key while the drawer is open (GameActivity.dispatchKeyEvent): back goes one level up (a section to the menu, the menu to the game), confirm presses what has
     * the focus or the menu's selected row, the D-pad moves (held: repeated; a slider being adjusted takes left / right), L / R switch sections. Each acts on its press.
     * Every key but Back is the drawer's: false lets Back through
     */
    boolean padKey(KeyEvent e) {
        if (hk != null) { padNav = true; return hk.padKey(e, confirm, back); }
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) return false;   // the activity's Back (back())
        padUse();
        boolean press = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0, up = e.getAction() == KeyEvent.ACTION_UP;
        int dir = PadPanel.ofKey(k);
        if (dir != PadPanel.NONE) { if (press) press(dir); else if (up) release(dir); return true; }
        if (!press) return true;
        if (!open) {   // the menu's own selection: back closes the drawer, confirm presses the selected row (the first press with none only selects)
            if (k == back) close();
            else if (k == confirm || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                if (menuSel < 0) { menuSelect(0); return true; }
                menuRows.get(menuSel).performClick();
                if (open) post(this::ensureFocus);   // a section picked with the controller: its focus out of touch mode, on its first row
            }
            return true;
        }
        boolean lost = findFocus() == null || touchNav() || !inside(findFocus());
        if (adjusting != null) {   // a slider being adjusted: confirm keeps its value, back puts the one it had back; L / R keep it and switch sections
            if (k == back) { adjustEnd(true); return true; }
            if (k == confirm || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { adjustEnd(false); return true; }
            if (k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1) adjustEnd(false);
        }
        if (k == back) showMenu();
        else if (k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1) { if (lost) ensureFocus(); showTab(PadPanel.tab(tab, k == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1, TAB_NAMES.length)); }
        else if (k == confirm || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { if (lost) ensureFocus(); else activate(); }   // the first press only shows where the focus is
        return true;
    }

    /** A controller's sticks and hat while the panel is open: the hat or the left stick as a direction (PadPanel.stick), held like a D-pad key. The game gets none */
    boolean padMotion(MotionEvent e) {
        if (hk != null) { padNav = true; return hk.padMotion(e); }
        if (e.getAction() != MotionEvent.ACTION_MOVE) return true;
        padUse();
        int d = PadPanel.stick(e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y), stickHeld);
        if (d == PadPanel.NONE) d = PadPanel.stick(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y), stickHeld);
        if (d != stickHeld) {
            if (stickHeld != PadPanel.NONE) release(stickHeld);
            stickHeld = d;
            if (d != PadPanel.NONE) press(d);
        }
        return true;
    }

    private void press(int dir) {
        if (dir == heldDir) return;   // the same direction from the D-pad's keys and its hat
        heldDir = dir;
        repeats = 0;
        move(dir);
        removeCallbacks(repeat);
        postDelayed(repeat, PadPanel.repeatDelay(0));
    }
    private void release(int dir) { if (dir == heldDir) { heldDir = PadPanel.NONE; removeCallbacks(repeat); } }
    private void repeatTick() {
        if (!isOpen() || heldDir == PadPanel.NONE) return;
        move(heldDir);
        postDelayed(repeat, PadPanel.repeatDelay(++repeats));
    }

    /**
     * One step: the focus moves to the nearest row or choice that way (the ScrollView keeps it in sight). Moving never changes a setting: a choice is made by
     * confirm (activate), and a slider changes only while it is being adjusted (confirm starts and ends that: adjustStart), by left / right
     */
    private void move(int dir) {
        if (!open) {   // the menu: up / down select, right opens the selected section (as confirm does)
            if (dir == PadPanel.UP || dir == PadPanel.DOWN) menuStep(dir == PadPanel.UP ? -1 : 1);
            else if (dir == PadPanel.RIGHT && menuSel >= 0 && menuSection.get(menuSel) >= 0) { showPage(menuSection.get(menuSel)); post(this::ensureFocus); }
            return;
        }
        View f = findFocus();
        if (f == null || touchNav() || !inside(f)) { adjusting = null; ensureFocus(); return; }   // (the menu's sink still has it when a section was picked by touch)
        if (adjusting != null) {
            if (f != adjusting) { adjusting = null; frame(f); }
            else {
                if (dir == PadPanel.LEFT || dir == PadPanel.RIGHT) {   // its own step, as the user's change (keyProgressIncrement)
                    int code = dir == PadPanel.LEFT ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
                    f.onKeyDown(code, new KeyEvent(KeyEvent.ACTION_DOWN, code));
                }
                return;   // up / down stay on it until confirm or back ends the adjusting
            }
        }
        int fd = dir == PadPanel.UP ? View.FOCUS_UP : dir == PadPanel.DOWN ? View.FOCUS_DOWN : dir == PadPanel.LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT;
        View n = f.focusSearch(fd);
        if (n != null && (fd == View.FOCUS_LEFT || fd == View.FOCUS_RIGHT) && !sameLine(f, n)) return;   // left / right stay in the line: from a slider or a lone row Android's search went off to another row
        if (n != null && inside(n)) focusOn(n);
        else if (fd == View.FOCUS_UP || fd == View.FOCUS_DOWN) scroll.smoothScrollBy(0, (fd == View.FOCUS_UP ? -1 : 1) * scroll.getHeight() / 3);   // the notes past the last row
    }

    /** a and b overlap vertically on the screen (the choices of one row) */
    private static boolean sameLine(View a, View b) {
        int[] pa = new int[2], pb = new int[2];
        a.getLocationOnScreen(pa);
        b.getLocationOnScreen(pb);
        return pa[1] < pb[1] + b.getHeight() && pb[1] < pa[1] + a.getHeight();
    }

    /** Confirm: presses what has the focus; on a slider it starts adjusting it */
    private void activate() {
        View f = findFocus();
        if (f instanceof SeekBar) { adjusting = (SeekBar) f; adjustFrom = adjusting.getProgress(); frame(f); }
        else if (f != null) f.performClick();
    }

    // The slider being adjusted with a controller (activate), and its value before
    private SeekBar adjusting;
    private int adjustFrom;
    /** The adjusting ends: the value kept, or (back) the one it had before, set back by the slider's own steps so that its listener applies it as the user's change */
    private void adjustEnd(boolean undo) {
        SeekBar s = adjusting;
        adjusting = null;
        if (s == null) return;
        if (undo) {
            int inc = Math.max(1, s.getKeyProgressIncrement()), code = s.getProgress() > adjustFrom ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            for (int n = Math.abs(s.getProgress() - adjustFrom) / inc + 1; n > 0 && s.getProgress() != adjustFrom; n--) s.onKeyDown(code, new KeyEvent(KeyEvent.ACTION_DOWN, code));
        }
        if (s.isFocused()) frame(s);
    }

    /** In the section's column (the menu has no focus of its own: menuSelect) */
    private boolean inside(View v) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == sheet) return true;
        return false;
    }

    /** Out of touch mode (a controller is in use: requestFocusFromTouch), the focus on the section: kept where it is, else on its first row; in the menu its first row selected */
    private void ensureFocus() {
        if (!open) { if (menuOpen && menuSel < 0) menuSelect(0); return; }
        View f = findFocus();
        if (f != null && !touchNav() && inside(f)) return;
        View r = firstRow();
        if (r != null) r.requestFocusFromTouch();
    }
    private void focusFirst() {
        View f = firstRow();
        if (f != null) focusOn(f);
    }
    private void focusOn(View v) { v.requestFocus(); }

    // A controller is in use in the panel (padNav, touchNav): set by its keys and its sticks and hat (padUse), cleared by a touch. Android's touch mode alone does
    // not say it: only keys end touch mode, and stick or hat motion (the left stick; the AYN Thor's controller's D-pad hat) leaves the window in it, where
    // requestFocusFromTouch cannot end it either (Android 13: the window manager refuses the change). The panel took every such move for a touch user's and
    // put the focus back on the section's first row, and a row that is not focusable in touch mode refused the focus. So while a controller is in use, the
    // section's focusable rows are made focusable in touch mode too (padRows), and a touch puts them back before it reaches them, so a tap still clicks at once
    private boolean padNav;
    private final java.util.ArrayList<View> padMade = new java.util.ArrayList<>();
    private boolean touchNav() { return isInTouchMode() && !padNav; }
    private void padUse() {
        padNav = true;
        if (isInTouchMode()) padRows(box);   // (each time: a section adds rows as its choices change; one already made is passed over)
    }
    private void padRows(View v) {
        if (v.isFocusable() && !v.isFocusableInTouchMode()) { v.setFocusableInTouchMode(true); padMade.add(v); }
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) padRows(g.getChildAt(i)); }
    }
    private void padRowsOff() {
        for (View v : padMade) v.setFocusableInTouchMode(false);
        padMade.clear();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && padNav) { padNav = false; padRowsOff(); }
        return super.dispatchTouchEvent(e);
    }
    /** The first row of the section (null: it has none) */
    private View firstRow() {
        java.util.ArrayList<View> l = box.getFocusables(View.FOCUS_FORWARD);
        return !l.isEmpty() ? l.get(0) : null;
    }

    /** The focus frame: a yellow outline on the focused row of the section (not in touch mode, where nothing has the focus) */
    private void focusMoved(View old, View now) {
        if (now != adjusting) adjusting = null;
        if (lit != null) { lit.setForeground(null); lit = null; }
        if (now == null || !inside(now) || touchNav()) return;
        frame(now);
    }
    /** The frame on v: a slider being adjusted gets a stronger fill and a thicker line */
    private void frame(View now) {
        if (lit != null && lit != now) lit.setForeground(null);
        float dp = getResources().getDisplayMetrics().density;
        boolean adj = now == adjusting;
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(adj ? 0x55FFD54F : 0x26FFD54F);
        d.setStroke(Math.round((adj ? 4f : 2.5f) * dp), FOCUS);
        d.setCornerRadius(FRAME_RADIUS_DP * dp);
        now.setDefaultFocusHighlightEnabled(false);   // Android's own (square) focus highlight would show past the frame's corners
        now.setForeground(d);
        lit = now;
    }

    /**
     * A dialog opened from the panel with a controller's confirm and back keys (on their press), as in the panel: Android's own fallback would take the bottom
     * button as confirm and the right one as back. Its focus starts on the safe choice (negative), so a confirm press does nothing that cannot be undone
     */
    private AlertDialog padDialog(AlertDialog d) {
        d.setOnKeyListener((di, k, e) -> {
            if (k != confirm && k != back) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (k == back) di.cancel();
                else { d.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); d.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); }
            }
            return true;
        });
        Button no = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (no != null && !touchNav()) focusOn(no);
        return d;
    }

    /** Another section from a controller's L / R: as picked in the menu */
    private void showTab(int k) {
        if (!open || k == tab) return;
        showPage(k);
    }

    /** Hokkaido's quick settings request one section's rows while its menu is open. */
    void hokkaidoTab(int k) {
        if (hk == null || !open || k < 0 || k >= TAB_NAMES.length) return;
        tab = k;
        padMade.clear();
        box.removeAllViews();
        build();
        look();
        refresh();
    }

    /** The panel's size and text size (Prefs.panelSize / panelText): the section's width, and every row's least height and text size, over what build() made */
    private void look() {
        if (hk != null) { hk.built(tab, box); return; }
        float dp = getResources().getDisplayMetrics().density;
        sizes();
        look(box, PanelLook.textScale(Prefs.panelText(act)), PanelLook.rowPx(Prefs.panelSize(act), dp));
    }
    private static void look(View v, float text, int row) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (text != 1f) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * text);
            if (v instanceof Button || v instanceof CompoundButton) t.setMinHeight(row);
            if (v instanceof CompoundButton && !(v.getBackground() instanceof RowRipple)) {
                // a check box or radio button: its press highlight in the focus frame's shape over the whole choice (its own was a small circle
                // around the box), and room after its text so that the frame does not touch it
                v.setBackground(rowRipple(v.getResources()));
                v.setDefaultFocusHighlightEnabled(false);
                int ph = Math.round(12 * v.getResources().getDisplayMetrics().density);
                v.setPaddingRelative(v.getPaddingStart(), v.getPaddingTop(), ph, v.getPaddingBottom());
            }
        } else if (v instanceof SeekBar) v.setMinimumHeight(row);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) look(((ViewGroup) v).getChildAt(i), text, row);
    }

    /** Built again with the new look or labels, at once (from a choice's listener: after it returns), the focus back on the choice that was pressed */
    private void restyle(int focusId) {
        post(() -> {
            if (!open) return;
            if (hk != null) { hokkaidoTab(tab); return; }
            int y = scroll.getScrollY();
            buildMenu();   // the menu's rows in the panel's new size and text size too
            box.removeAllViews();
            build();
            look();
            scroll.post(() -> {
                scroll.scrollTo(0, y);
                View f = focusId != 0 ? box.findViewById(focusId) : null;
                if (f != null) f.requestFocus();
            });
            refresh();
        });
    }

    private TextView head(String t) {
        TextView h = new TextView(act);
        h.setText(t);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(15);
        h.setPadding(0, Math.round(18 * getResources().getDisplayMetrics().density), 0, 0);
        box.addView(h);
        return h;
    }

    private TextView note(String t) {
        TextView n = new TextView(act);
        n.setText(t);
        n.setTextSize(12);
        n.setTextColor(Color.LTGRAY);
        box.addView(n);
        return n;
    }

    /** The running game's title ID (GameActivity's intent): the panel's settings are that game's own */
    private String game() { return String.valueOf(act.getIntent().getStringExtra("title_id")); }

    /** The section's rows (TAB_NAMES); Performance's here: the frame rate cap, the Upscaler (with a render_cfg game's Upscaling quality and Graphics quality), the dynamic resolution and frame generation */
    private void build() {
        if (hk == null && stacked()) {   // Hokkaido has its own tab bar; standalone needs the section's heading when stacked
            TextView t = head(Tx.arr(R.array.arr_panel_tabs)[tab]);
            t.setTextSize(20);
            t.setPadding(0, 0, 0, 0);
        }
        fpsNow = lsfgNow = layerNow = null;
        caps = null;
        if (tab == TAB_DISPLAY) { display(); return; }
        if (tab == TAB_CONTROLS) { controls(); return; }
        if (tab == TAB_GENERAL) { general(); return; }

        head(Tx.s(R.string.common_frame_rate_cap));   // the game's own frame rate; frame generation (below, its own heading) adds frames on top
        caps = new RadioGroup(act);
        if (Prefs.limited(game())) {   // a frame limit, no locking (Prefs.PACE_CAPS)
            int cur = Prefs.paceCap(act, game());
            for (int i = 0; i < Prefs.PACE_CAPS.length; i++) {
                RadioButton b = new RadioButton(act);
                b.setId(3000 + i); b.setText(Prefs.paceLabel(Prefs.PACE_CAPS[i]));
                if (Prefs.PACE_CAPS[i] == cur) b.setChecked(true);
                caps.addView(b);
            }
            caps.setOnCheckedChangeListener((g, id) -> {
                int v = Prefs.PACE_CAPS[id - 3000];
                Prefs.fpsCap(act, game(), v);
                Native.fpsLimitSet(v > 0 ? v : Prefs.paced(game()) ? 60 : 0);   // 60 fps: a paced game's fastest rate; for another, no limit (the game's own interval)
                refresh();
            });
        } else {
            int live = Integer.parseInt(Native.fpsCapStatus().split(" ")[0]), cur = live >= 0 ? live : Prefs.fpsCap(act, game());   // the cap in effect (env.txt or DM_CTL may have set another)
            for (int i = 0; i < Prefs.FPS_CAPS.length; i++) {
                int v = Prefs.FPS_CAPS[i];
                RadioButton b = new RadioButton(act);
                b.setId(2000 + i);
                b.setText(v == 0 ? Tx.s(R.string.game_the_game_s_own) : Tx.s(R.string.game_n_fps, v));
                if (v == cur || v == 0 && cur < 0) b.setChecked(true);   // a cap the launcher does not offer (40, 45 from env.txt or DM_CTL): none checked, the line below says it
                caps.addView(b);
            }
            caps.setOnCheckedChangeListener((g, id) -> {
                int v = Prefs.FPS_CAPS[id - 2000];
                Prefs.fpsCap(act, game(), v);   // the running game's own cap from now on (the main screen's stays the default of the others)
                Native.fpsCapSet(v);
                refresh();
            });
        }
        box.addView(caps);
        if (Prefs.limited(game()) && Prefs.presentOnly(game())) note(Prefs.PRESENT_ONLY_NOTE());   // fps_limit_present: the limit paces the presents only
        if (Prefs.fullSpeed(game())) {   // Full game speed at low frame rates: sead's controller at the measured rate, or late frames skipped (Game settings has the same), live in the host
            android.widget.CheckBox full = new android.widget.CheckBox(act);
            full.setText(Tx.s(R.string.game_full_game_speed_at_low));
            full.setChecked(Prefs.decouple(act, game()));
            full.setOnCheckedChangeListener((b, on) -> { Prefs.decouple(act, game(), on); Native.decoupleSet(on); });
            box.addView(full);
        }
        if (Prefs.steps(game())) {   // Hold 60 fps: one step a frame at the cap of 60 (Game settings has the same), live in the host
            android.widget.CheckBox hold = new android.widget.CheckBox(act);
            hold.setText(Tx.s(R.string.game_hold_60_fps_never_drop));
            hold.setChecked(Prefs.hold60(act, game()));
            hold.setOnCheckedChangeListener((b, on) -> { Prefs.hold60(act, game(), on); Native.hold60Set(on); });
            box.addView(hold);
        }
        fpsNow = note("");
        boolean simple = Prefs.simpleRes(act, game());   // a game with Resolution presets, tester rows off: no Upscaler or Dynamic resolution here (Settings › Help › Tester settings)
        if (!simple && (Prefs.dlssGame(game()) || GameUpscaler.shown(act, game()))) {   // right under the frame rate cap: the Upscaler (the game's own upscaler and the window's scaling to the screen), for games whose network it replaces; for a game on the DLSS stand-in (no network to replace), the stand-in's modes with Motion clarity / sharpness
            GameUpscaler.rows(act, game(), box);
            note(act instanceof GameActivity && ((GameActivity) act).live() ? Tx.s(R.string.game_applies_from_the_next_frame)
                    : Tx.s(R.string.game_applies_at_the_next_start));
            if (Prefs.dlssGame(game())) {   // a game on the DLSS stand-in: Screen scaling (the window to the screen) right under its Game upscaler, the two stages together
                GameUpscaler.screenRows(act, game(), box);
                note(act instanceof GameActivity && ((GameActivity) act).live() ? Tx.s(R.string.game_applies_from_the_next_frame_2)
                        : Tx.s(R.string.game_applies_at_the_next_start));
            }
        }
        Profile rp = Profile.of(game());
        if (rp != null && rp.renderCfg()) renderQuality(rp);   // a render_cfg game: Upscaling quality and Graphics quality under the Upscaler (Resolution is on Display)
        if (Prefs.drsGame(game()) && !simple) {
            TextView drsHead = head(Tx.s(R.string.common_dynamic_resolution));   // the render size's range (Prefs.DRS), applied at each step; the heading shows it
            box.addView(DrsSlider.view(act, Prefs.drs(act, game()), drsHead::setText, i -> { Prefs.drs(act, game(), i); Native.drsSet(Prefs.DRS[i][0], Prefs.DRS[i][1]); }));
        }

        lsfgNow = Lsfg.rows(act, box, "", () -> { Lsfg.push(act, game()); refresh(); }, game());   // the running game's own setting from now on (Settings' stays the default of the others)

        Profile prof = Profile.of(game());
        if (prof != null) {
            for (Profile.Option opt : prof.options) {
                if (!opt.live) continue;
                android.widget.CheckBox cb = new android.widget.CheckBox(act);
                cb.setText(opt.label);
                cb.setChecked(Prefs.gameOption(act, game(), opt.env));
                cb.setOnCheckedChangeListener((b, on) -> {
                    Prefs.gameOption(act, game(), opt.env, on);
                    pushLiveOption(opt.env, on);   // the running game reads ctl.txt (GameActivity's DM_CTL); the stored choice applies at every start
                });
                box.addView(cb);
                if (!opt.description.isEmpty()) note(opt.description);
            }
        }
        liveChoices(box);
    }

    /** The Advanced table's live choice rows for this game (a `live` column, e.g. Ocarina of Time's Draw distance): a slider, Auto first then the choices in order, that sends each step to the
     *  running game as an `env NAME=value` line (Auto: `env NAME=`, the switch empty) and keeps it as the game's Advanced choice for the next start */
    private void liveChoices(LinearLayout box) {
        for (AdvancedTable.Row row : AdvancedSettings.table(act)) {
            if (!row.live() || !row.forGame(game())) continue;
            java.util.List<String[]> opts = row.options();
            if (opts.isEmpty()) continue;
            String[] names = new String[opts.size() + 1];
            names[0] = Tx.s(R.string.game_auto);
            for (int i = 0; i < opts.size(); i++) names[i + 1] = opts.get(i)[0];
            String cur = AdvancedSettings.effective(act, row.name, game());
            int at = 0;
            for (int i = 0; i < opts.size(); i++) if (opts.get(i)[1].equals(cur)) at = i + 1;
            TextView h = head(row.title());
            box.addView(StepSlider.view(act, row.title(), names, at, Tx.s(R.string.game_auto_colon, row.deflt.isEmpty() ? Tx.s(R.string.game_the_game_s_own_2) : row.deflt), opts.get(opts.size() - 1)[0], h::setText, i -> {
                String v = i == 0 ? null : opts.get(i - 1)[1];
                AdvancedSettings.set(act, row.name, game(), v);
                pushLiveEnv(row.name, v == null ? "" : v);
            }));
            note(act instanceof GameActivity && ((GameActivity) act).live() ? Tx.s(R.string.game_applies_at_once_kept_for)
                    : Tx.s(R.string.game_applies_at_the_next_start));
        }
    }

    /** A live game option (the profile's `live` row), sent to the running game as a ctl.txt line: the switch is 1 or 0 */
    private void pushLiveOption(String env, boolean on) { pushLiveEnv(env, on ? "1" : "0"); }

    /** `env NAME=value` to the running game through ctl.txt (an empty value clears the switch) */
    private void pushLiveEnv(String env, String value) {
        java.io.File ext = act.getExternalFilesDir(null);
        if (ext == null) return;
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(ext, "ctl.txt"), true)) {
            w.write("env " + env + "=" + value + "\n");
        } catch (java.io.IOException e) { Native.note("[RED] game option " + env + ": the setting could not be sent to the game (" + e.getMessage() + "); it applies at the next start"); }
    }

    /** Display: Resolution (a render_cfg game: Resolution alone), the picture (Fit / Stretch, size, position) and the overlay's items */
    private void display() {
        resolutionRow();

        picture();

        head(Tx.s(R.string.settings_overlay));
        Overlay.rows(act, box, null, game());   // the running game's own overlay from now on
    }

    /** Controls: Player 1, the on-screen controls, rumble, the phone's Motion sensitivity and the Button mapping shortcut */
    private void controls() {
        playerOneRow();

        head(Tx.s(R.string.common_on_screen_controls));
        rows.controls(box);
        // Rumble: the game's vibration on the controller or the phone, this game's setting (Game settings › Rumble), applied at once (GameActivity.rumble)
        android.widget.CheckBox rum = new android.widget.CheckBox(act);
        rum.setText(Tx.s(R.string.game_rumble_the_game_s_vibration));
        rum.setChecked(Prefs.rumble(act, game()));
        rum.setOnCheckedChangeListener((b, on) -> { if (act instanceof GameActivity) ((GameActivity) act).rumble(on); else Prefs.rumble(act, game(), on); });
        box.addView(rum);

        TextView motionHead = head(Tx.s(R.string.game_motion_sensitivity));   // the phone's gyro scale (Prefs.MOTION_PCT), applied at each step and kept for the game (the same setting as Game settings › Controls)
        box.addView(StepSlider.view(act, Tx.s(R.string.game_motion_sensitivity), Prefs.MOTION_NAMES, Prefs.motionSens(act, game()), "Slower", "Faster", motionHead::setText,
                i -> { Prefs.motionSens(act, game(), i); Native.motionSens(Prefs.MOTION_PCT[i] / 100f); }));

        nextStartRows();
    }

    /**
     * Cheats: the cheats this run started with (GameActivity.cheats: the profile's file and the player's imported ones, merged), one switch each, live in the host
     * (Native.cheatSet, hle_cheats.c nxhost_cheat_set) and kept per game (Prefs.cheatOn). Only when the game started with Game settings › Cheats on: otherwise the
     * list is empty and the section is not shown (Cheats.panel). Grouped by the file's sections
     */
    private void cheatRows() {
        if (!(act instanceof GameActivity)) return;
        java.util.List<Cheats.Entry> list = ((GameActivity) act).cheats();
        if (list.isEmpty()) return;
        head(act.getString(R.string.cheats_title));
        note(act.getString(R.string.cheats_caution));
        java.util.Set<String> on = Prefs.cheatsOn(act, game());
        String sec = null;
        for (Cheats.Entry e : list) {
            if (!e.section.equals(sec)) {
                sec = e.section;
                if (!sec.isEmpty()) { TextView s = note(sec); s.setTypeface(Typeface.DEFAULT_BOLD); }
            }
            android.widget.CheckBox b = new android.widget.CheckBox(act);
            b.setText(e.name);
            b.setChecked(on.contains(e.key));
            b.setOnCheckedChangeListener((v, x) -> {
                Prefs.cheatOn(act, game(), e.key, x);
                int r = Native.cheatSet(e.key, x);
                if (r != 1) Native.note("[RED] [nxlaunch] cheat \"" + e.key + "\" " + (x ? "on" : "off") + ": the host did not take it (" + r + (r == 0 ? ": no plain cheat, see the cheats lines above" : r == -1 ? ": no such cheat" : ": not in this host") + ")");
            });
            box.addView(b);
        }
    }

    /**
     * Shaders, for a game the shader layer covers (a Switch 2 title: GameActivity.layerShown): one status line, the label's own state in words (LayerStatus.panelLine; "All shaders ready" when the
     * game has a prepared library and nothing waits), kept up to date while the tab shows (refresh), and the switch Shaders while playing (the same setting as Game settings). The host reads
     * the switch once, when the game starts, so the switch says so and does not pretend to be live
     */
    private void shaderRows() {
        if (!(act instanceof GameActivity) || !((GameActivity) act).layerShown()) return;
        head(Tx.s(R.string.game_shaders));
        layerNow = note(((GameActivity) act).layerLine());
        note(Tx.s(R.string.game_shaders_the_game_lacks_are_3));
        Button lab = row(ID_LABEL, Tx.s(R.string.game_building_shaders_label), Overlay.labelChoices()[Prefs.layerLabel(act, game())]);   // Off / the count / the words: takes effect at the label's next update
        lab.setOnClickListener(v -> padDialog(new AlertDialog.Builder(act).setTitle(Tx.s(R.string.game_building_shaders_label))
                .setSingleChoiceItems(Overlay.labelChoices(), Prefs.layerLabel(act, game()), (d, w) -> { Prefs.layerLabel(act, game(), w); d.dismiss(); restyle(ID_LABEL); })   // the running game's own label from now on
                .setNegativeButton(Tx.s(R.string.common_cancel), null).show()));
    }

    /** General: the panel's own look, cheats, shaders, Hide everything and Pause when the game comes back */
    private void general() {
        note(Tx.s(R.string.game_the_game_runs_on_behind));
        if (hk == null) panelRows();   // Hokkaido's menu has its own size and text style
        cheatRows();
        shaderRows();

        // One tap for a clean picture: host-drawn stats and tag, the log, the touch controls and the panel strip together, saved as one flag.
        // Back / Home still opens this panel, where the same button brings everything back; the overlay and log settings themselves are untouched.
        final boolean allHidden = Prefs.clean(act);
        Button clean = new Button(act);
        clean.setText(Tx.s(allHidden ? R.string.game_show_everything : R.string.game_hide_everything_clean_picture));
        clean.setOnClickListener(v -> {
            if (act instanceof GameActivity) ((GameActivity) act).clean(!allHidden);
            close();
        });
        box.addView(clean);

        // Pause and Stop are the menu's (buildMenu); Pause's own setting stays here
        android.widget.CheckBox ret = new android.widget.CheckBox(act);
        ret.setText(Tx.s(R.string.game_pause_when_the_game_comes));
        ret.setChecked(Prefs.pauseOnReturn(act, game()));
        ret.setOnCheckedChangeListener((b, on) -> Prefs.pauseOnReturn(act, game(), on));
        box.addView(ret);
    }

    /**
     * Resolution: this game's Game resolution (the same choices and the same setting as Game settings › Game resolution), only for a game whose profile has a lever
     * (Profile.hasResolution). A game with graphics_scale rows changes now: the host takes the choice's graphics scale and operation mode and posts the console's change
     * notifications, which the game answers by querying them again and resizing its scenes (Native.graphicsSet, hle_oe.c nxhost_graphics_mode_set). A window_fn or res_levels
     * game takes it at the next start. A game with one docked and one handheld row gets those two as radio buttons instead (Profile.modes).
     * A game with Resolution presets (ResPresets) gets them instead: the preset's window changes now, its 3D scene at the next start (the RomFS overlay is read at boot);
     * under Advanced the window row follows as before.
     */
    private void resolutionRow() {
        Profile p = Profile.of(game());
        if (p != null && p.renderCfg()) { renderResolution(p); return; }
        if (p == null || !p.hasResolution()) return;
        head(Tx.s(R.string.render_res_title));
        boolean now = p.resolutionLive() && act instanceof GameActivity;
        java.util.List<ResPresets.Preset> ps = ResPresets.presets(p);
        if (!ps.isEmpty()) {
            String rc = Prefs.resolutionChoice(act, game());
            boolean simple = Prefs.simpleRes(act, game());   // the tester rows are off (Settings › Help): the profile's choices alone
            Button pr = row(ID_RES_PRESET, Tx.s(R.string.render_res_title), ResPresets.ADVANCED.equals(rc) && simple ? "Custom setup" : ResPresets.label(ps, rc));
            pr.setOnClickListener(v -> {
                String recPreset = Prefs.rec(act, game(), Recommend.RES_PRESET);
                String[] labels = new String[simple ? ps.size() : ps.size() + 1];   // the same choices and words as Game settings › Resolution
                for (int k = 0; k < ps.size(); k++) labels[k] = ResPresets.itemText(ps.get(k), k == 0, ps.get(k).id.equals(recPreset));
                if (!simple) labels[ps.size()] = Tx.s(R.string.game_advanced_for_testers_nwindow_and);
                ResPresets.Preset cur0 = ResPresets.of(ps, Prefs.resolutionChoice(act, game()));
                new AlertDialog.Builder(act).setTitle(Tx.s(R.string.common_resolution)).setSingleChoiceItems(labels, cur0 != null ? ps.indexOf(cur0) : simple ? -1 : ps.size(), (d, w) -> {
                    Prefs.resPreset(act, game(), w < ps.size() ? ps.get(w).id : ResPresets.ADVANCED);   // the game's own setting: the next start takes it too
                    if (now) {
                        int[] m = p.resolutionLive(Prefs.startWindow(act, game()));
                        if (m != null && !Native.graphicsSet(m)) Native.note("Resolution: this host cannot change the window live; it applies at the next start");
                    }
                    d.dismiss();
                    restyle(ID_RES_PRESET);
                }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
            });
            note(ResPresets.NOTE + " " + Tx.s(R.string.game_kept_for_game_resolution));
            if (!ResPresets.ADVANCED.equals(rc) || simple) return;
        }
        String winTitle = ps.isEmpty() ? Tx.s(R.string.home_game_resolution) : Tx.s(R.string.game_advanced_u00b7_window);
        String cur = Prefs.gameWindow(act, game());
        String[] modes = p.modes();
        if (modes != null) {   // a game with one docked and one handheld row (Profile.modes): those two radio buttons only, drawn as the Frame rate cap's
            RadioGroup g = new RadioGroup(act);
            for (int k = 0; k < 2; k++) { RadioButton b = new RadioButton(act); b.setId(ID_RES + 1 + k); b.setText(p.modeLabel(k)); g.addView(b); }
            if (p.modeOf(cur) >= 0) g.check(ID_RES + 1 + p.modeOf(cur));   // Original shows as Docked (Profile.modeOf)
            g.setOnCheckedChangeListener((gr, id) -> {
                Prefs.gameWindow(act, game(), modes[id - ID_RES - 1]);   // the game's own setting: the next start takes it too
                if (!ps.isEmpty()) Prefs.resPreset(act, game(), ResPresets.ADVANCED);   // a window chosen under Advanced keeps Advanced
                if (now) {
                    int[] m = p.resolutionLive(Prefs.gameWindow(act, game()));
                    if (m != null && !Native.graphicsSet(m)) Native.note("Game resolution: this host cannot change it live; it applies at the next start");
                }
                restyle(id);
            });
            box.addView(g);
            return;
        }
        Button r = row(ID_RES, winTitle, (Prefs.ownWindow(act, game()) ? "" : "Default · ") + Prefs.windowName(cur, false));
        r.setOnClickListener(v -> {
            String[] labels = new String[Prefs.WINDOWS.length + 1];
            labels[0] = Tx.s(R.string.game_default_value, Prefs.windowName(Prefs.defaultWindow(act, game()), false));
            { Profile lp = Profile.of(game()); for (int li = 0; li < Prefs.WINDOWS.length; li++) labels[li + 1] = lp != null ? lp.windowLabel(Prefs.WINDOWS[li], Prefs.WINDOW_NAMES[li]) : Prefs.WINDOW_NAMES[li]; }   // a choice the game draws at another size says so (Profile.windowLabel)
            int checked = Prefs.ownWindow(act, game()) ? 1 + Math.max(0, java.util.Arrays.asList(Prefs.WINDOWS).indexOf(cur)) : 0;
            new AlertDialog.Builder(act).setTitle(winTitle).setSingleChoiceItems(labels, checked, (d, w) -> {
                Prefs.gameWindow(act, game(), w == 0 ? null : Prefs.WINDOWS[w - 1]);   // the game's own setting: the next start takes it too
                if (!ps.isEmpty()) Prefs.resPreset(act, game(), ResPresets.ADVANCED);   // a window chosen under Advanced keeps Advanced
                if (now) {
                    int[] m = p.resolutionLive(Prefs.gameWindow(act, game()));
                    if (m != null && !Native.graphicsSet(m)) Native.note("Game resolution: this host cannot change it live; it applies at the next start");
                }
                d.dismiss();
                restyle(ID_RES);
            }).setNegativeButton(Tx.s(R.string.common_cancel), null).show();
        });
        note(now ? Tx.s(R.string.game_applies_now_resizes_picture)
                + (p.resolution.contains("/console") ? Tx.s(R.string.game_the_larger_sizes_run_the) : "")
                : Tx.s(R.string.game_applies_at_the_next_start_2));
    }

    /**
     * Resolution, Upscaling quality and Graphics quality of a game whose profile has a render_cfg row: three radio groups, drawn as the Frame rate cap's, labels from the string
     * resources (RenderLabels); Resolution on the Display tab (renderResolution), the other two on Performance under the Upscaler (renderQuality). A pick is the game's own
     * setting (Prefs.render, the next start takes it). While the game runs, Resolution and Upscaling quality change at once (Native.renderSet: the host writes them at the
     * game's next notification 31); Graphics quality changes at once the same way. A pick whose render size needs a larger render target pool than the game started with is
     * clamped by the host until the next start, and only then one line says so under each part (Profile.renderRestartNote)
     */
    private java.util.function.BiConsumer<Profile.Render, Integer> renderPick(Profile p, Profile.Render cur) {
        boolean now = act instanceof GameActivity;
        return (r, focus) -> {
            Prefs.render(act, game(), r);
            int[] live = p.renderLive(cur, r);
            if (now && live != null && !Native.renderSet(live[0], live[1], live[2])) Native.note("Resolution: this host cannot change it live; it applies at the next start");
            restyle(focus);
        };
    }
    private void renderNote(Profile p, Profile.Render cur, RenderLabels l) {
        Profile.Render start = act instanceof GameActivity ? ((GameActivity) act).startRender() : null;
        if (start != null && p.renderRestartNote(cur, start, null, Prefs.lowRam(act))) note(l.restartNote);
    }
    private void renderResolution(Profile p) {
        Profile.Render cur = Prefs.render(act, p, game());
        RenderLabels l = new RenderLabels(act);
        String[] outs = p.renderOutputs();
        java.util.function.BiConsumer<Profile.Render, Integer> pick = renderPick(p, cur);
        head(l.resTitle);
        RadioGroup ro = new RadioGroup(act);
        for (int i = 0; i < outs.length; i++) { RadioButton b = new RadioButton(act); b.setId(ID_RES + 1 + i); b.setText(l.out(p, outs[i])); ro.addView(b); }
        ro.check(ID_RES + 1 + Math.max(0, java.util.Arrays.asList(outs).indexOf(cur.out)));
        ro.setOnCheckedChangeListener((gr, id) -> pick.accept(new Profile.Render(outs[id - ID_RES - 1], cur.pct, cur.quality), id));
        box.addView(ro);
        renderNote(p, cur, l);
    }
    private void renderQuality(Profile p) {
        Profile.Render cur = Prefs.render(act, p, game());
        RenderLabels l = new RenderLabels(act);
        java.util.function.BiConsumer<Profile.Render, Integer> pick = renderPick(p, cur);
        head(l.upscaleTitle);
        RadioGroup ru = new RadioGroup(act);
        for (int i = 0; i < Profile.RENDER_PCTS.length; i++) { RadioButton b = new RadioButton(act); b.setId(ID_RES + 20 + i); b.setText(l.pct(Profile.RENDER_PCTS[i])); ru.addView(b); }
        ru.check(ID_RES + 20 + Math.max(0, GameSettings.indexOfPct(cur.pct)));
        ru.setOnCheckedChangeListener((gr, id) -> pick.accept(new Profile.Render(cur.out, Profile.RENDER_PCTS[id - ID_RES - 20], cur.quality), id));
        box.addView(ru);
        head(l.gfxTitle);
        RadioGroup rg = new RadioGroup(act);
        for (int i = 0; i < 2; i++) { RadioButton b = new RadioButton(act); b.setId(ID_RES + 40 + i); b.setText(l.gfx[i]); rg.addView(b); }
        rg.check(ID_RES + 40 + (cur.quality ? 0 : 1));
        rg.setOnCheckedChangeListener((gr, id) -> pick.accept(new Profile.Render(cur.out, cur.pct, id == ID_RES + 40), id));
        box.addView(rg);
        renderNote(p, cur, l);
    }

    /** Picture: Fit / Stretch and the size (GameActivity.picture: live through DM_CTL, kept for this game), then the position on this screen (TouchControls) */
    private void picture() {
        head(Tx.s(R.string.game_picture));
        GameActivity ga = act instanceof GameActivity ? (GameActivity) act : null;
        boolean live = ga != null && ga.live();
        boolean st = live ? ga.runStretch() : Prefs.stretch(act, game());   // what the game shows now (an env.txt line may have set it), else what the next start uses
        int sz = live ? ga.runPictureSize() : Prefs.pictureSize(act, game());
        RadioGroup fit = new RadioGroup(act);
        fit.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {Tx.s(R.string.game_fit), Tx.s(R.string.game_stretch)};
        for (int i = 0; i < names.length; i++) { RadioButton b = new RadioButton(act); b.setId(ID_FIT + i); b.setText(names[i]); fit.addView(b); }
        fit.check(ID_FIT + (st ? 1 : 0));
        box.addView(fit);
        TextView sl = new TextView(act);
        SeekBar ss = new SeekBar(act);
        ss.setMax(PictureFit.steps());
        ss.setProgress(PictureFit.stepOfSize(sz));
        ss.setKeyProgressIncrement(1);   // the D-pad's left / right: 5 % a press
        Runnable label = () -> sl.setText(Tx.s(R.string.game_size_percent, PictureFit.sizeOfStep(ss.getProgress())) + (ss.getProgress() == PictureFit.steps() ? Tx.s(R.string.game_full) : ""));
        label.run();
        Runnable send = () -> { if (ga != null) ga.picture(fit.getCheckedRadioButtonId() == ID_FIT + 1, PictureFit.sizeOfStep(ss.getProgress())); };
        fit.setOnCheckedChangeListener((g, id) -> send.run());
        ss.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) { label.run(); send.run(); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        box.addView(sl);
        box.addView(ss);
        note(live ? Tx.s(R.string.game_fit_keeps_shape_note)
                : Tx.s(R.string.game_applies_at_the_next_start));
        rows.position(box);
    }

    /** The panel itself: its size (width, row height) and its text size, for every game, applied at once */
    private void panelRows() {
        head(Tx.s(R.string.game_panel));
        String[] what = {Tx.s(R.string.game_size), Tx.s(R.string.game_text_size)};
        int[] ids = {ID_SIZE, ID_TEXT}, cur = {Prefs.panelSize(act), Prefs.panelText(act)};
        for (int k = 0; k < 2; k++) {
            TextView l = new TextView(act); l.setText(what[k]); box.addView(l);
            RadioGroup g = new RadioGroup(act);
            g.setOrientation(RadioGroup.HORIZONTAL);
            for (int i = 0; i < Tx.arr(R.array.arr_panel_look).length; i++) { RadioButton b = new RadioButton(act); b.setId(ids[k] + i); b.setText(Tx.arr(R.array.arr_panel_look)[i]); g.addView(b); }
            g.check(ids[k] + cur[k]);
            int base = ids[k];
            g.setOnCheckedChangeListener((gr, id) -> {
                if (base == ID_SIZE) Prefs.panelSize(act, id - base); else Prefs.panelText(act, id - base);
                restyle(id);
            });
            box.addView(g);
        }
    }

    /**
     * A shortcut to this game's Button mapping (DM_PAD_MAP), which the host reads only at its start: one compact row under the "Next start" heading, "Button mapping"
     * then the mapping's summary in grey and a chevron. A borderless Button, so that look() gives it the panel's row height and text size and the D-pad can focus it.
     * Game language is set in the launcher only (Game settings › Game language).
     */
    private void nextStartRows() {
        if (!(act instanceof GameActivity)) return;
        GameActivity ga = (GameActivity) act;
        head(Tx.s(R.string.game_next_start));
        Profile prof = Profile.of(game());
        if (prof != null && !prof.options.isEmpty()) {
            for (Profile.Option opt : prof.options) {
                if (opt.live || opt.settingsOnly) continue;   // a settings-only option is the launcher's Game options alone
                android.widget.CheckBox cb = new android.widget.CheckBox(act);
                cb.setText(opt.label);
                Profile.Option source = Profile.findImpliedBy(prof.options, opt, env -> Prefs.gameOption(act, game(), env));
                if (source != null) {
                    cb.setChecked(true);
                    cb.setEnabled(false);
                } else {
                    cb.setChecked(Prefs.gameOption(act, game(), opt.env));
                    cb.setOnCheckedChangeListener((b, on) -> {
                        Prefs.gameOption(act, game(), opt.env, on);
                        if (opt.hasImplies()) build();
                    });
                }
                box.addView(cb);
            }
        }
        String sum = PadMapUi.label(act, game());
        Button map = row(ID_MAP, Tx.s(R.string.common_button_mapping), sum);
        map.setContentDescription(Tx.s(R.string.game_button_mapping_applies, sum));
        map.setOnClickListener(v -> PadMapUi.forGame(act, ga.game(), () -> restyle(ID_MAP)));
    }

    /** A row's press and focus highlight, in the focus frame's rounded shape (focusMoved): the theme's selectableItemBackground is square and showed past the frame's corners */
    private static final class RowRipple extends android.graphics.drawable.RippleDrawable {
        RowRipple(android.graphics.drawable.Drawable mask) { super(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, mask); }
        // a check box or radio button keeps its ripple to the box (CompoundButton sets the hotspot bounds to it): here it fills the whole choice, in the frame's shape
        @Override public void setHotspotBounds(int left, int top, int right, int bottom) {}
    }
    private RowRipple rowRipple() { return rowRipple(getResources()); }
    private static RowRipple rowRipple(android.content.res.Resources r) {
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(FRAME_RADIUS_DP * r.getDisplayMetrics().density);
        return new RowRipple(mask);
    }

    /**
     * One compact row: the label, then the value in grey and a chevron. A borderless Button, so that look() gives it the panel's row height and text size and the
     * D-pad can focus it.
     */
    private Button row(int id, String label, String value) {
        Button map = new Button(act);
        map.setId(id);
        map.setAllCaps(false);
        map.setBackground(rowRipple());   // a list row with the focus and press highlight, not a raised default Button
        map.setStateListAnimator(null);
        map.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        map.setTextColor(new TextView(act).getTextColors());   // the colour of the panel's other rows
        map.setTextSize(14);
        map.setSingleLine(true);
        map.setEllipsize(android.text.TextUtils.TruncateAt.END);
        map.setMinWidth(0);
        map.setMinimumWidth(0);
        int pv = Math.round(4 * getResources().getDisplayMetrics().density), ph = Math.round(12 * getResources().getDisplayMetrics().density);
        map.setPadding(ph, pv, ph, pv);   // the text clear of the focus frame's edge
        android.text.SpannableStringBuilder t = new android.text.SpannableStringBuilder(label + "  ");
        int from = t.length();
        t.append(value).append("  ›");
        t.setSpan(new android.text.style.ForegroundColorSpan(Color.LTGRAY), from, t.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        map.setText(t);
        box.addView(map, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return map;
    }

    /**
     * Player 1 (PadOrder): the gamepad on player 1, and a choice of the gamepads connected or Automatic (an external controller before a phone's own gamepad-like
     * device). Applies at once and is kept for every game, by the device's descriptor.
     */
    private void playerOneRow() {
        if (!(act instanceof GameActivity)) return;
        GameActivity ga = (GameActivity) act;
        java.util.List<PadOrder.Pad> pads = ga.gamepads();
        if (pads.isEmpty()) return;
        head(Tx.s(R.string.game_controllers));
        PadOrder.Pad one = ga.playerOne();
        String chosen = Prefs.padFirst(act);
        Button b = row(ID_ONE, Tx.s(R.string.game_player_1), (one != null ? one.name : Tx.s(R.string.game_none_lower)) + (chosen.isEmpty() ? Tx.s(R.string.game_automatic_paren) : ""));
        b.setOnClickListener(v -> {
            String[] names = new String[pads.size() + 1];
            names[0] = Tx.s(R.string.game_automatic);
            int checked = 0;
            for (int i = 0; i < pads.size(); i++) { names[i + 1] = pads.get(i).name; if (!chosen.isEmpty() && chosen.equals(pads.get(i).key)) checked = i + 1; }
            padDialog(new AlertDialog.Builder(act).setTitle(Tx.s(R.string.game_player_1)).setSingleChoiceItems(names, checked, (d, w) -> {
                ga.choosePlayerOne(w == 0 ? "" : pads.get(w - 1).key);
                d.dismiss();
                restyle(ID_ONE);
            }).setNegativeButton(Tx.s(R.string.common_cancel), null).show());
        });
    }

    /** Twice a second while open: what the game runs at, which caps it can hold, and the frame generation's state */
    private void refresh() {
        if (!open) return;
        if (layerNow != null) layerNow.setText(((GameActivity) act).layerLine());   // General's Shaders line
        if (tab != TAB_PERF) {   // only Performance and the Shaders line are live (showPage calls it again)
            removeCallbacks(tick);
            if (layerNow != null) postDelayed(tick, 1000);
            return;
        }
        String[] f = Native.fpsCapStatus().split(" ");
        int cap = Integer.parseInt(f[0]), rates = Integer.parseInt(f[1]), now = Integer.parseInt(f[2]);
        if (Prefs.limited(game())) {   // the frame limiter's caps are always available
            // The overlay shows the frame rate, the cap is checked above; one line only when the limit holds another rate: a game whose frame step moves in whole
            // vsyncs (hle_fpslock.c dm_fps_limit_step) runs 45 and 40 at 30, at full speed
            int chosen = Prefs.paceCap(act, game()), held = f.length > 3 ? Integer.parseInt(f[3]) : 0;
            boolean other = now > 0 && chosen > 0 && held > 0 && 60 % chosen != 0 && held < chosen;
            fpsNow.setVisibility(other ? VISIBLE : GONE);
            if (other) fpsNow.setText(Tx.s(R.string.game_whole_frames, chosen, held));
        } else {
            for (int i = 0; i < Prefs.FPS_CAPS.length; i++) {
                int v = Prefs.FPS_CAPS[i], bit = v == 15 ? 0 : v == 20 ? 1 : v == 30 ? 2 : 5;   // the host's bits: 15, 20, 30, (40, 45,) 60
                caps.getChildAt(i).setEnabled(v == 0 || cap >= 0 && (rates & 1 << bit) != 0);
            }
            // No cap in this build, or a game that picks its own frame rate: no choices (they would do nothing), one line instead
            // (rates is 0 too until the game's main is loaded: only a game that presents, now > 0, is known to pick its own)
            boolean own = cap >= 0 && rates == 0 && now > 0, none = cap < 0 || own;
            caps.setVisibility(none ? GONE : VISIBLE);
            if (cap >= 0 && now > 0) Prefs.ownRate(act, game(), own);   // its Game settings hide the cap too
            String s = cap < 0 ? Tx.s(R.string.game_this_build_has_no_frame)
                    : now <= 0 ? Tx.s(R.string.settings_starting)
                    : own ? Tx.s(R.string.game_this_game_picks_its_own_2)
                    : cap > 0 ? Tx.s(R.string.game_now_fps_capped, now, cap) : Tx.s(R.string.game_now_fps_own, now);
            fpsNow.setText(s);
        }
        if (lsfgNow != null) { String st = Native.frameGenStatus(); int sp = st.indexOf(' '); lsfgNow.setText(Tx.s(R.string.game_now_status, sp > 0 ? st.substring(sp + 1) : st)); }
        removeCallbacks(tick);   // a change calls refresh too: one loop, not one more per change
        postDelayed(tick, 500);
    }
}
