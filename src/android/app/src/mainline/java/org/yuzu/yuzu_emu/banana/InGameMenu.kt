// SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.banana

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import dev.nxnative.android.HokkaidoInGame
import dev.nxnative.android.HokkaidoSettings
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.features.settings.ui.SettingsActivity
import org.yuzu.yuzu_emu.model.Game

/**
 * Hokkaido's in-game menu over a Banana Native game, in the game process: the menu (Pause, the on-screen
 * controller, Settings, Quick settings, Overlay options, Exit) from the start edge and quick settings from the end edge,
 * as in Hokkaido's emulation screen. The quick settings show Banana Native's panel tabs (PanelRows). Hidden while
 * closed, so every touch reaches the game; Back or the controller's menu combination opens it.
 */
internal class InGameMenu(private val screen: HokkaidoInGame.Screen) : HokkaidoInGame.Menu {
    private val activity = screen.activity()
    private val context: Context = themed()
    private val handler = Handler(Looper.getMainLooper())
    private var padNav = false
    private val padMade = mutableListOf<View>()
    private val drawer = object : DrawerLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                padNav = false
                restoreTouchFocus()
            }
            return super.dispatchTouchEvent(event)
        }
    }
    private val menu = NavigationView(context)
    private val sheet = NavigationView(context)
    private val tabs = TabLayout(context)
    private lateinit var scroll: ScrollView
    private lateinit var rows: PanelRows
    private var shownTab = -1
    private var heldDir = NONE

    private val tick = object : Runnable {
        override fun run() {
            if (!drawer.isDrawerVisible(GravityCompat.END)) return
            rows.sync()
            handler.postDelayed(this, SYNC_MS)
        }
    }

    init {
        drawer.visibility = View.GONE
        drawer.addView(FrameLayout(context), DrawerLayout.LayoutParams(MATCH, MATCH))

        val surface = color(com.google.android.material.R.attr.colorSurface)
        val header = menu.inflateHeaderView(R.layout.header_in_game)
        header.findViewById<TextView>(R.id.text_game_title).text = screen.name()
        header.findViewById<TextView>(R.id.cpu_backend).text = context.getString(R.string.game_engine_native)
        header.findViewById<TextView>(R.id.gpu_vendor).text =
            screen.driver() ?: context.getString(R.string.system_gpu_driver)
        menu.menu.apply {
            add(Menu.NONE, ID_PAUSE, 0, R.string.emulation_pause).setIcon(R.drawable.ic_pause)
            add(Menu.NONE, ID_CONTROLS, 1, R.string.emulation_show_overlay).setIcon(R.drawable.ic_controller)
            add(Menu.NONE, ID_SETTINGS, 2, R.string.fusion_hokkaido_settings).setIcon(R.drawable.ic_settings)
            add(Menu.NONE, ID_GAME_SETTINGS, 3, R.string.fusion_hokkaido_game_settings).setIcon(R.drawable.ic_settings)
            add(Menu.NONE, ID_NATIVE_SETTINGS, 4, R.string.banana_settings).setIcon(R.drawable.ic_graphics)
            add(Menu.NONE, ID_QUICK, 5, R.string.quick_settings).setIcon(R.drawable.ic_settings)
            add(Menu.NONE, ID_INPUT_SETTINGS, 6, R.string.fusion_hokkaido_controls).setIcon(R.drawable.ic_controller)
            add(Menu.NONE, ID_OVERLAY, 7, R.string.emulation_input_overlay).setIcon(R.drawable.ic_overlay)
            add(Menu.NONE, ID_EXIT, 8, R.string.emulation_exit).setIcon(R.drawable.ic_exit)
        }
        menu.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                ID_PAUSE -> {
                    if (screen.paused()) screen.resume() else screen.pause()
                    refreshMenu()
                }
                ID_CONTROLS -> {
                    screen.showControls(!screen.controlsShown())
                    refreshMenu()
                }
                ID_QUICK -> openSheet(TAB_PERFORMANCE)
                ID_SETTINGS -> openHokkaidoSettings(null, Settings.MenuTag.SECTION_ROOT)
                ID_GAME_SETTINGS -> openHokkaidoSettings(game(), Settings.MenuTag.SECTION_ROOT)
                ID_INPUT_SETTINGS -> openHokkaidoSettings(null, Settings.MenuTag.SECTION_INPUT)
                ID_NATIVE_SETTINGS -> {
                    screen.pause()
                    close()
                    HokkaidoSettings.open(
                        activity,
                        activity.intent.getStringExtra("title_id"),
                        screen.name(),
                        activity.intent.getStringExtra("game")
                    )
                }
                ID_OVERLAY -> openSheet(TAB_CONTROLS)
                ID_EXIT -> screen.stop()
            }
            true
        }
        menu.setBackgroundColor(surface)
        drawer.addView(menu, DrawerLayout.LayoutParams(WRAP, MATCH, Gravity.START))

        // Hokkaido's quick settings sheet, with Banana Native's panel tabs under its title
        val content = LayoutInflater.from(context).inflate(R.layout.layout_quick_settings, sheet, false) as ScrollView
        scroll = content
        (content.getChildAt(0) as LinearLayout).addView(tabs, 1, LinearLayout.LayoutParams(MATCH, WRAP))
        tabs.tabMode = TabLayout.MODE_SCROLLABLE
        screen.tabs().forEach { tabs.addTab(tabs.newTab().setText(it)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = screen.showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        rows = PanelRows(context, content.findViewById(R.id.quick_settings_container)) {
            drawer.requestDisallowInterceptTouchEvent(it)
        }
        sheet.addView(content)
        sheet.setBackgroundColor(surface)
        drawer.addView(sheet, DrawerLayout.LayoutParams(WRAP, MATCH, Gravity.END))

        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(view: View) {
                if (padNav) focusFirst()
                if (view === sheet) {
                    handler.removeCallbacks(tick)
                    handler.postDelayed(tick, SYNC_MS)
                }
            }

            override fun onDrawerClosed(view: View) {
                if (drawer.isDrawerVisible(GravityCompat.START) || drawer.isDrawerVisible(GravityCompat.END)) return
                handler.removeCallbacks(tick)
                drawer.visibility = View.GONE
                screen.close()
            }
        })
    }

    override fun view(): View = drawer

    private fun game(): Game = Game(
        title = screen.name(),
        path = activity.intent.getStringExtra("game").orEmpty(),
        programId = activity.intent.getStringExtra("title_id")?.toLongOrNull(16)?.toString().orEmpty()
    )

    private fun openHokkaidoSettings(game: Game?, menuTag: Settings.MenuTag) {
        screen.pause()
        close()
        val arguments = HomeNavigationDirections.actionGlobalSettingsActivity(game, menuTag).arguments
        activity.startActivity(Intent(activity, SettingsActivity::class.java).putExtras(arguments))
    }

    override fun open() {
        heldDir = NONE
        refreshMenu()
        drawer.visibility = View.VISIBLE
        drawer.openDrawer(GravityCompat.START)
    }

    override fun close() {
        heldDir = NONE
        restoreTouchFocus()
        handler.removeCallbacks(tick)
        if (drawer.visibility == View.VISIBLE) drawer.closeDrawers()
    }

    override fun focus() {
        useController()
        handler.post { focusFirst() }
    }

    override fun built(tab: Int, rows: LinearLayout) {
        val y = if (tab == shownTab) scroll.scrollY else 0
        shownTab = tab
        restoreTouchFocus()
        this.rows.render(rows)
        scroll.post {
            scroll.scrollTo(0, y)
            if (padNav && sheetShown()) focusFirst()
        }
    }

    override fun padKey(e: KeyEvent, confirm: Int, back: Int): Boolean {
        val k = e.keyCode
        if (k == KeyEvent.KEYCODE_BACK) return false
        useController()
        val dir = dirOf(k)
        if (e.action == KeyEvent.ACTION_UP) {
            if (dir != NONE && dir == heldDir) heldDir = NONE
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        val first = e.repeatCount == 0
        when {
            dir != NONE -> {
                // the same direction from the D-pad's keys and its hat once (padMotion)
                if (!first || dir != heldDir) move(dir)
                heldDir = dir
            }
            !first -> {}
            k == back -> back()
            k == confirm || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER -> press()
            (k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1) && sheetShown() -> {
                val n = tabs.tabCount
                val step = if (k == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1
                tabs.getTabAt((tabs.selectedTabPosition + step + n) % n)?.select()
                handler.post { focusFirst() }
            }
        }
        return true
    }

    /** The hat or the left stick as a D-pad, while the menu is open; the game gets none. */
    override fun padMotion(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_MOVE) return true
        useController()
        var x = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        var y = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (x == 0f && y == 0f) {
            x = e.getAxisValue(MotionEvent.AXIS_X)
            y = e.getAxisValue(MotionEvent.AXIS_Y)
        }
        val d = when {
            y < -0.5f -> UP
            y > 0.5f -> DOWN
            x < -0.5f -> LEFT
            x > 0.5f -> RIGHT
            else -> NONE
        }
        if (d != heldDir) {
            heldDir = d
            if (d != NONE) move(d)
        }
        return true
    }

    private fun sheetShown() = drawer.isDrawerVisible(GravityCompat.END)

    private fun openSheet(tab: Int) {
        drawer.closeDrawer(GravityCompat.START)
        if (tabs.selectedTabPosition != tab) tabs.getTabAt(tab)?.select() else screen.showTab(tab)
        drawer.openDrawer(GravityCompat.END)
    }

    /** Phone Back and a controller's back button share the same drawer navigation. */
    override fun back() {
        heldDir = NONE
        if (sheetShown()) {
            drawer.closeDrawer(GravityCompat.END)
            refreshMenu()
            drawer.openDrawer(GravityCompat.START)
            handler.post { focusFirst() }
        } else {
            screen.close()
        }
    }

    private fun press() {
        val f = currentFocus()
        if (f == null || touchNavigation()) focusFirst() else f.performClick()
    }

    private fun move(dir: Int) {
        val f = currentFocus()
        if (f == null || touchNavigation()) {
            focusFirst()
            return
        }
        if (f is Slider && (dir == LEFT || dir == RIGHT)) {
            val code = if (dir == LEFT) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            f.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code))
            return
        }
        val focusDir = when (dir) {
            UP -> View.FOCUS_UP
            DOWN -> View.FOCUS_DOWN
            LEFT -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        val next = f.focusSearch(focusDir) ?: return
        if (inside(next, if (sheetShown()) sheet else menu)) next.requestFocus()
    }

    /** Controller axes may leave Android in touch mode; make the visible rows focusable there too. */
    private fun useController() {
        padNav = true
        if (drawer.isInTouchMode) controllerRows(if (sheetShown()) sheet else menu)
    }

    private fun controllerRows(view: View) {
        if (view.isFocusable && !view.isFocusableInTouchMode) {
            view.isFocusableInTouchMode = true
            padMade.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) controllerRows(view.getChildAt(i))
        }
    }

    private fun restoreTouchFocus() {
        padMade.forEach { it.isFocusableInTouchMode = false }
        padMade.clear()
    }

    private fun touchNavigation() = drawer.isInTouchMode && !padNav

    private fun currentFocus(): View? {
        val root = if (sheetShown()) sheet else menu
        return drawer.findFocus()?.takeIf { it.isShown && inside(it, root) }
    }

    /** The first visible item of the active drawer, including controller navigation in touch mode. */
    private fun focusFirst() {
        val root = if (sheetShown()) sheet else menu
        if (padNav && drawer.isInTouchMode) controllerRows(root)
        root.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown }?.requestFocusFromTouch()
    }

    private fun inside(view: View, root: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === root) return true
            v = v.parent as? View
        }
        return false
    }

    private fun refreshMenu() {
        val paused = screen.paused()
        menu.menu.findItem(ID_PAUSE)
            .setTitle(if (paused) R.string.emulation_unpause else R.string.emulation_pause)
            .setIcon(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        menu.menu.findItem(ID_CONTROLS).setTitle(
            if (screen.controlsShown()) R.string.emulation_hide_overlay else R.string.emulation_show_overlay
        )
    }

    /** Hokkaido's theme and dark mode, as its own screens have them (HokkaidoBridge.play). */
    private fun themed(): Context {
        val themes = screen.themes()
        val themed = ContextThemeWrapper(activity, themes.firstOrNull() ?: R.style.Theme_Eden_Main)
        val config = Configuration(activity.resources.configuration)
        val night = if (screen.night()) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        themed.applyOverrideConfiguration(config)
        themes.drop(1).forEach { themed.theme.applyStyle(it, true) }
        return themed
    }

    private fun color(attr: Int): Int {
        val a = context.obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0)
        a.recycle()
        return c
    }

    private fun dirOf(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        else -> NONE
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SYNC_MS = 300L
        const val ID_PAUSE = 1
        const val ID_CONTROLS = 2
        const val ID_QUICK = 3
        const val ID_OVERLAY = 4
        const val ID_EXIT = 5
        const val ID_SETTINGS = 6
        const val ID_GAME_SETTINGS = 7
        const val ID_INPUT_SETTINGS = 8
        const val ID_NATIVE_SETTINGS = 9

        // Banana Native's panel tabs (SidePanel.TAB_NAMES)
        const val TAB_PERFORMANCE = 0
        const val TAB_CONTROLS = 2

        const val NONE = 0
        const val UP = 1
        const val DOWN = 2
        const val LEFT = 3
        const val RIGHT = 4
    }
}
